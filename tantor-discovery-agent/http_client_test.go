package main

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"
)

func trustedTestServerTLS(server *httptest.Server) *tls.Config {
	roots := x509.NewCertPool()
	roots.AddCert(server.Certificate())
	serverName := server.Certificate().DNSNames[0]
	return &tls.Config{MinVersion: tls.VersionTLS12, RootCAs: roots, ServerName: serverName}
}

func TestTLSValidationAndHostnameVerificationFailClosed(t *testing.T) {
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))
	defer server.Close()

	trusted := newResilientHTTPClient(testHTTPSettings(), trustedTestServerTLS(server))
	response, err := trusted.do(context.Background(), http.MethodGet, server.URL, "", nil, false)
	if err != nil {
		t.Fatalf("trusted TLS request failed: %v", err)
	}
	closeResponse(response)

	untrusted := newResilientHTTPClient(testHTTPSettings(), &tls.Config{MinVersion: tls.VersionTLS12})
	if response, err = untrusted.do(context.Background(), http.MethodGet, server.URL, "", nil, false); err == nil {
		closeResponse(response)
		t.Fatal("expected untrusted CA to fail")
	}

	wrongHostTLS := trustedTestServerTLS(server)
	wrongHostTLS.ServerName = "wrong-host.example"
	wrongHost := newResilientHTTPClient(testHTTPSettings(), wrongHostTLS)
	if response, err = wrongHost.do(context.Background(), http.MethodGet, server.URL, "", nil, false); err == nil {
		closeResponse(response)
		t.Fatal("expected wrong hostname to fail")
	}
}

func TestMTLSEndpointRejectsMissingClientCertificate(t *testing.T) {
	server := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))
	server.TLS = &tls.Config{MinVersion: tls.VersionTLS12, ClientAuth: tls.RequireAnyClientCert}
	server.StartTLS()
	defer server.Close()

	client := newResilientHTTPClient(testHTTPSettings(), trustedTestServerTLS(server))
	if response, err := client.do(context.Background(), http.MethodGet, server.URL, "", nil, false); err == nil {
		closeResponse(response)
		t.Fatal("expected mTLS endpoint to reject a missing client certificate")
	}
}

func TestHTTPSDowngradeRedirectIsRejected(t *testing.T) {
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Location", "http://attacker.example/")
		w.WriteHeader(http.StatusFound)
	}))
	defer server.Close()
	client := newResilientHTTPClient(testHTTPSettings(), trustedTestServerTLS(server))
	if response, err := client.do(context.Background(), http.MethodGet, server.URL, "", nil, false); err == nil {
		closeResponse(response)
		t.Fatal("expected HTTPS downgrade redirect to fail")
	}
}

func testHTTPSettings() HTTPSettings {
	return HTTPSettings{
		ConnectTimeout:          50 * time.Millisecond,
		TLSHandshakeTimeout:     50 * time.Millisecond,
		ResponseHeaderTimeout:   50 * time.Millisecond,
		RequestTimeout:          100 * time.Millisecond,
		RetryTotalTimeout:       500 * time.Millisecond,
		RetryMaxAttempts:        3,
		RetryInitialBackoff:     time.Millisecond,
		RetryMaxBackoff:         5 * time.Millisecond,
		CircuitFailureThreshold: 2,
		CircuitOpenDuration:     25 * time.Millisecond,
	}
}

func TestHTTPClientTimesOutWhenServerNeverResponds(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, request *http.Request) {
		<-request.Context().Done()
	}))
	defer server.Close()

	settings := testHTTPSettings()
	settings.ResponseHeaderTimeout = 40 * time.Millisecond
	settings.RequestTimeout = 60 * time.Millisecond
	client := newResilientHTTPClient(settings, nil)

	started := time.Now()
	response, err := client.do(context.Background(), http.MethodGet, server.URL, "", nil, false)
	if response != nil {
		closeResponse(response)
	}
	if err == nil {
		t.Fatal("expected a timeout error")
	}
	if elapsed := time.Since(started); elapsed > 500*time.Millisecond {
		t.Fatalf("request was not bounded; elapsed %s", elapsed)
	}
}

func TestHTTPClientCancellationInterruptsActiveRequest(t *testing.T) {
	requestStarted := make(chan struct{})
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, request *http.Request) {
		close(requestStarted)
		<-request.Context().Done()
	}))
	defer server.Close()

	settings := testHTTPSettings()
	settings.ResponseHeaderTimeout = 5 * time.Second
	settings.RequestTimeout = 5 * time.Second
	client := newResilientHTTPClient(settings, nil)
	ctx, cancel := context.WithCancel(context.Background())

	done := make(chan error, 1)
	go func() {
		_, err := client.do(ctx, http.MethodGet, server.URL, "", nil, false)
		done <- err
	}()
	<-requestStarted
	cancel()

	select {
	case err := <-done:
		if !errors.Is(err, context.Canceled) {
			t.Fatalf("expected context cancellation, got %v", err)
		}
	case <-time.After(500 * time.Millisecond):
		t.Fatal("active request did not stop after cancellation")
	}
}

func TestHTTPClientRetriesTransientStatus(t *testing.T) {
	var attempts atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, request *http.Request) {
		if attempts.Add(1) < 3 {
			w.WriteHeader(http.StatusServiceUnavailable)
			return
		}
		w.WriteHeader(http.StatusOK)
	}))
	defer server.Close()

	client := newResilientHTTPClient(testHTTPSettings(), nil)
	response, err := client.do(context.Background(), http.MethodPost, server.URL, "application/json", []byte(`{}`), true)
	if err != nil {
		t.Fatalf("request failed after retry: %v", err)
	}
	defer closeResponse(response)
	if response.StatusCode != http.StatusOK {
		t.Fatalf("expected 200, got %d", response.StatusCode)
	}
	if attempts.Load() != 3 {
		t.Fatalf("expected 3 attempts, got %d", attempts.Load())
	}
}

func TestCircuitBreakerOpensAndRecovers(t *testing.T) {
	var healthy atomic.Bool
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, request *http.Request) {
		if !healthy.Load() {
			w.WriteHeader(http.StatusInternalServerError)
			return
		}
		w.WriteHeader(http.StatusOK)
	}))
	defer server.Close()

	settings := testHTTPSettings()
	client := newResilientHTTPClient(settings, nil)
	for i := 0; i < settings.CircuitFailureThreshold; i++ {
		response, err := client.do(context.Background(), http.MethodGet, server.URL, "", nil, false)
		if err != nil {
			t.Fatalf("unexpected transport error: %v", err)
		}
		closeResponse(response)
	}
	if _, err := client.do(context.Background(), http.MethodGet, server.URL, "", nil, false); !errors.Is(err, errCircuitOpen) {
		t.Fatalf("expected open circuit, got %v", err)
	}

	healthy.Store(true)
	time.Sleep(settings.CircuitOpenDuration + 10*time.Millisecond)
	response, err := client.do(context.Background(), http.MethodGet, server.URL, "", nil, false)
	if err != nil {
		t.Fatalf("half-open probe failed: %v", err)
	}
	closeResponse(response)
}

type trackingBody struct {
	closed atomic.Bool
}

func (body *trackingBody) Read(buffer []byte) (int, error) { return 0, io.EOF }
func (body *trackingBody) Close() error {
	body.closed.Store(true)
	return nil
}

func TestCloseResponseClosesBody(t *testing.T) {
	body := &trackingBody{}
	closeResponse(&http.Response{Body: body})
	if !body.closed.Load() {
		t.Fatal("response body was not closed")
	}
}
