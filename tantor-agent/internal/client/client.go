package client

import (
	"bytes"
	"crypto/tls"
	"crypto/x509"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"strings"
	"time"

	"io.translab/tantor-agent/internal/config"
	"io.translab/tantor-agent/pkg/api"
)

// APIClient handles communication with the Tantor Server
type APIClient struct {
	cfg        *config.Config
	httpClient *http.Client
}

func NewAPIClient(cfg *config.Config) (*APIClient, error) {
	if err := cfg.ValidateTransport(); err != nil {
		return nil, err
	}
	cert, err := tls.LoadX509KeyPair(cfg.Agent.CertFile, cfg.Agent.KeyFile)
	if err != nil {
		return nil, fmt.Errorf("load agent client certificate: %w", err)
	}

	// Load CA cert
	caCert, err := os.ReadFile(cfg.Agent.CACert)
	if err != nil {
		return nil, fmt.Errorf("read control-plane CA certificate: %w", err)
	}
	caCertPool := x509.NewCertPool()
	if !caCertPool.AppendCertsFromPEM(caCert) {
		return nil, fmt.Errorf("control-plane CA certificate contains no valid PEM certificates")
	}

	tlsConfig := &tls.Config{
		Certificates: []tls.Certificate{cert},
		RootCAs:      caCertPool,
		MinVersion:   tls.VersionTLS12,
	}

	transport := &http.Transport{TLSClientConfig: tlsConfig}
	client := &http.Client{
		Transport:     transport,
		Timeout:       10 * time.Minute,
		CheckRedirect: secureRedirectPolicy,
	}

	return &APIClient{
		cfg:        cfg,
		httpClient: client,
	}, nil
}

func (c *APIClient) RegisterHost(req *api.HostRegistration) error {
	return c.post("/api/v1/agents/register", req, nil)
}

func (c *APIClient) SendHeartbeat(hb *api.HostHeartbeat) error {
	return c.post("/api/v1/agents/heartbeat", hb, nil)
}

func (c *APIClient) PollTasks() ([]api.Task, error) {
	var tasks []api.Task
	url := fmt.Sprintf("%s/api/v1/agents/%s/tasks", c.cfg.Agent.ServerURL, c.cfg.Agent.HostID)

	resp, err := c.httpClient.Get(url)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	if resp.StatusCode == http.StatusNoContent {
		return tasks, nil // No tasks
	}

	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("unexpected status: %d", resp.StatusCode)
	}

	if err := json.NewDecoder(resp.Body).Decode(&tasks); err != nil {
		return nil, err
	}
	return tasks, nil
}

func (c *APIClient) ReportTaskResult(result *api.TaskResult) error {
	return c.post("/api/v1/agents/tasks/result", result, nil)
}

func (c *APIClient) DownloadArtifact(url, destPath string) (string, error) {
	if err := requireSameHTTPSAuthority(c.cfg.Agent.ServerURL, url); err != nil {
		return "", err
	}
	downloadClient := &http.Client{
		Transport:     c.httpClient.Transport,
		Timeout:       10 * time.Minute,
		CheckRedirect: secureRedirectPolicy,
	}

	resp, err := downloadClient.Get(url)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("failed to download artifact, status: %d", resp.StatusCode)
	}

	out, err := os.Create(destPath)
	if err != nil {
		return "", err
	}
	defer out.Close()

	_, err = io.Copy(out, resp.Body)
	if err != nil {
		return "", err
	}

	// Read X-Checksum-SHA256 header as per Phase 1 contract
	checksum := resp.Header.Get("X-Checksum-SHA256")
	return checksum, nil
}

func secureRedirectPolicy(request *http.Request, via []*http.Request) error {
	if request.URL.Scheme != "https" {
		return fmt.Errorf("refusing redirect to non-HTTPS URL %q", request.URL.String())
	}
	if len(via) > 0 && !strings.EqualFold(request.URL.Host, via[0].URL.Host) {
		return fmt.Errorf("refusing cross-authority redirect from %q to %q", via[0].URL.Host, request.URL.Host)
	}
	if len(via) >= 10 {
		return fmt.Errorf("stopped after 10 redirects")
	}
	return nil
}

func requireSameHTTPSAuthority(serverURL, targetURL string) error {
	server, err := url.Parse(serverURL)
	if err != nil {
		return fmt.Errorf("invalid configured server URL: %w", err)
	}
	target, err := url.Parse(targetURL)
	if err != nil || target.Scheme != "https" || target.Host == "" {
		return fmt.Errorf("artifact URL must be an absolute https URL")
	}
	if !strings.EqualFold(server.Host, target.Host) {
		return fmt.Errorf("artifact URL authority %q does not match control-plane authority %q", target.Host, server.Host)
	}
	return nil
}

func (c *APIClient) post(path string, reqBody interface{}, respBody interface{}) error {
	data, err := json.Marshal(reqBody)
	if err != nil {
		return err
	}

	url := c.cfg.Agent.ServerURL + path
	resp, err := c.httpClient.Post(url, "application/json", bytes.NewBuffer(data))
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	if resp.StatusCode >= 400 {
		bodyBytes, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("API error %d: %s", resp.StatusCode, string(bodyBytes))
	}

	if respBody != nil {
		return json.NewDecoder(resp.Body).Decode(respBody)
	}
	return nil
}
