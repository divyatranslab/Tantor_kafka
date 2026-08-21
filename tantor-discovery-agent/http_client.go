package main

import (
	"bytes"
	"context"
	"crypto/tls"
	"crypto/x509"
	"errors"
	"fmt"
	"io"
	"math/rand"
	"net"
	"net/http"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"
)

var errCircuitOpen = errors.New("HTTP circuit is open")

type agentHTTPClients struct {
	backend *resilientHTTPClient
	metrics *resilientHTTPClient
}

type resilientHTTPClient struct {
	client   *http.Client
	settings HTTPSettings
	breaker  *circuitBreaker
	random   *rand.Rand
	randomMu sync.Mutex
}

type circuitBreaker struct {
	mu        sync.Mutex
	failures  int
	threshold int
	openFor   time.Duration
	openUntil time.Time
	halfOpen  bool
}

func newAgentHTTPClients(settings HTTPSettings, cfg DiscoveryConfig) (*agentHTTPClients, error) {
	if err := cfg.ValidateTransport(); err != nil {
		return nil, err
	}
	certificate, err := tls.LoadX509KeyPair(cfg.TLSClientCert, cfg.TLSClientKey)
	if err != nil {
		return nil, fmt.Errorf("load discovery client certificate: %w", err)
	}
	caPEM, err := os.ReadFile(cfg.TLSCACert)
	if err != nil {
		return nil, fmt.Errorf("read control-plane CA certificate: %w", err)
	}
	roots := x509.NewCertPool()
	if !roots.AppendCertsFromPEM(caPEM) {
		return nil, fmt.Errorf("control-plane CA certificate contains no valid PEM certificates")
	}
	tlsConfig := &tls.Config{
		MinVersion:   tls.VersionTLS12,
		Certificates: []tls.Certificate{certificate},
		RootCAs:      roots,
	}
	return &agentHTTPClients{
		backend: newResilientHTTPClient(settings, tlsConfig),
		metrics: newResilientHTTPClient(settings, nil),
	}, nil
}

func newResilientHTTPClient(settings HTTPSettings, tlsConfig *tls.Config) *resilientHTTPClient {
	transport := &http.Transport{
		Proxy: http.ProxyFromEnvironment,
		DialContext: (&net.Dialer{
			Timeout:   settings.ConnectTimeout,
			KeepAlive: 30 * time.Second,
		}).DialContext,
		ForceAttemptHTTP2:     true,
		MaxIdleConns:          20,
		MaxIdleConnsPerHost:   5,
		IdleConnTimeout:       90 * time.Second,
		TLSHandshakeTimeout:   settings.TLSHandshakeTimeout,
		ResponseHeaderTimeout: settings.ResponseHeaderTimeout,
		ExpectContinueTimeout: time.Second,
		TLSClientConfig:       tlsConfig,
	}
	return &resilientHTTPClient{
		client: &http.Client{
			Transport: transport,
			Timeout:   settings.RequestTimeout,
			CheckRedirect: func(request *http.Request, via []*http.Request) error {
				if len(via) > 0 && via[0].URL.Scheme == "https" && request.URL.Scheme != "https" {
					return fmt.Errorf("refusing HTTPS downgrade redirect to %q", request.URL.String())
				}
				if len(via) >= 10 {
					return fmt.Errorf("stopped after 10 redirects")
				}
				return nil
			},
		},
		settings: settings,
		breaker: &circuitBreaker{
			threshold: settings.CircuitFailureThreshold,
			openFor:   settings.CircuitOpenDuration,
		},
		random: rand.New(rand.NewSource(time.Now().UnixNano())),
	}
}

func (c *resilientHTTPClient) do(
	ctx context.Context,
	method, requestURL, contentType string,
	body []byte,
	retry bool,
) (*http.Response, error) {
	if !c.breaker.allow(time.Now()) {
		return nil, fmt.Errorf("%w for %s", errCircuitOpen, requestURL)
	}

	attempts := 1
	operationCtx := ctx
	cancel := func() {}
	if retry {
		attempts = c.settings.RetryMaxAttempts
		operationCtx, cancel = context.WithTimeout(ctx, c.settings.RetryTotalTimeout)
	}
	defer cancel()

	for attempt := 1; attempt <= attempts; attempt++ {
		request, err := http.NewRequestWithContext(operationCtx, method, requestURL, bytes.NewReader(body))
		if err != nil {
			c.breaker.fail(time.Now())
			return nil, err
		}
		if contentType != "" {
			request.Header.Set("Content-Type", contentType)
		}

		response, requestErr := c.client.Do(request)
		if requestErr == nil && !retryableStatus(response.StatusCode) {
			if circuitFailureStatus(response.StatusCode) {
				c.breaker.fail(time.Now())
			} else {
				c.breaker.succeed()
			}
			return response, nil
		}

		if operationCtx.Err() != nil {
			if response != nil {
				closeResponse(response)
			}
			if ctx.Err() == nil {
				c.breaker.fail(time.Now())
			}
			return nil, operationCtx.Err()
		}

		if !retry || attempt == attempts || (requestErr == nil && !retryableStatus(response.StatusCode)) {
			c.breaker.fail(time.Now())
			return response, requestErr
		}

		delay := c.retryDelay(attempt, response)
		if response != nil {
			closeResponse(response)
		}
		timer := time.NewTimer(delay)
		select {
		case <-operationCtx.Done():
			timer.Stop()
			return nil, operationCtx.Err()
		case <-timer.C:
		}
	}

	return nil, errors.New("HTTP request attempts exhausted")
}

func (c *resilientHTTPClient) retryDelay(attempt int, response *http.Response) time.Duration {
	if response != nil {
		if delay, ok := parseRetryAfter(response.Header.Get("Retry-After"), time.Now()); ok {
			if delay > c.settings.RetryMaxBackoff {
				return c.settings.RetryMaxBackoff
			}
			return delay
		}
	}

	delay := c.settings.RetryInitialBackoff
	for i := 1; i < attempt && delay < c.settings.RetryMaxBackoff; i++ {
		delay *= 2
		if delay > c.settings.RetryMaxBackoff {
			delay = c.settings.RetryMaxBackoff
		}
	}
	c.randomMu.Lock()
	jitter := time.Duration(c.random.Int63n(int64(delay/2) + 1))
	c.randomMu.Unlock()
	return delay/2 + jitter
}

func retryableStatus(status int) bool {
	switch status {
	case http.StatusRequestTimeout, http.StatusTooManyRequests, http.StatusBadGateway,
		http.StatusServiceUnavailable, http.StatusGatewayTimeout:
		return true
	default:
		return false
	}
}

func circuitFailureStatus(status int) bool {
	return status == http.StatusRequestTimeout || status == http.StatusTooManyRequests || status >= 500
}

func parseRetryAfter(value string, now time.Time) (time.Duration, bool) {
	value = strings.TrimSpace(value)
	if value == "" {
		return 0, false
	}
	if seconds, err := strconv.Atoi(value); err == nil && seconds >= 0 {
		return time.Duration(seconds) * time.Second, true
	}
	when, err := http.ParseTime(value)
	if err != nil {
		return 0, false
	}
	if !when.After(now) {
		return 0, true
	}
	return when.Sub(now), true
}

func closeResponse(response *http.Response) {
	if response == nil || response.Body == nil {
		return
	}
	_, _ = io.Copy(io.Discard, io.LimitReader(response.Body, 32*1024))
	_ = response.Body.Close()
}

func (b *circuitBreaker) allow(now time.Time) bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.openUntil.IsZero() {
		return true
	}
	if now.Before(b.openUntil) || b.halfOpen {
		return false
	}
	b.halfOpen = true
	return true
}

func (b *circuitBreaker) succeed() {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.failures = 0
	b.openUntil = time.Time{}
	b.halfOpen = false
}

func (b *circuitBreaker) fail(now time.Time) {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.failures++
	if b.halfOpen || b.failures >= b.threshold {
		b.openUntil = now.Add(b.openFor)
		b.halfOpen = false
	}
}
