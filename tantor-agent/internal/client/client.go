package client

import (
	"bytes"
	"crypto/tls"
	"crypto/x509"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
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
	// Load client cert
	cert, err := tls.LoadX509KeyPair(cfg.Agent.CertFile, cfg.Agent.KeyFile)
	if err != nil {
		// Fallback to insecure if no certs provided (for dev only, in prod should fail)
		// return nil, fmt.Errorf("failed to load client cert: %w", err)
	}

	// Load CA cert
	caCert, err := os.ReadFile(cfg.Agent.CACert)
	if err != nil {
		// return nil, fmt.Errorf("failed to read CA cert: %w", err)
	}
	caCertPool := x509.NewCertPool()
	caCertPool.AppendCertsFromPEM(caCert)

	tlsConfig := &tls.Config{
		Certificates: []tls.Certificate{cert},
		RootCAs:      caCertPool,
		// For local dev without valid certs:
		InsecureSkipVerify: true,
	}

	transport := &http.Transport{TLSClientConfig: tlsConfig}
	client := &http.Client{
		Transport: transport,
		Timeout:   10 * time.Minute,
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
	downloadClient := &http.Client{
		Transport: c.httpClient.Transport,
		Timeout:   10 * time.Minute,
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
