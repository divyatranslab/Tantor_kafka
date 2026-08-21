package main

import (
	"context"
	"net/http"
	"sync/atomic"
	"testing"
	"time"
)

type roundTripperFunc func(*http.Request) (*http.Response, error)

func (function roundTripperFunc) RoundTrip(request *http.Request) (*http.Response, error) {
	return function(request)
}

func TestTaskPollDoesNotRetryAndClosesErrorResponse(t *testing.T) {
	body := &trackingBody{}
	var attempts atomic.Int32
	client := newResilientHTTPClient(testHTTPSettings(), nil)
	client.client.Transport = roundTripperFunc(func(request *http.Request) (*http.Response, error) {
		attempts.Add(1)
		return &http.Response{
			StatusCode: http.StatusServiceUnavailable,
			Header:     make(http.Header),
			Body:       body,
			Request:    request,
		}, nil
	})

	pollForTask(
		context.Background(),
		client,
		"http://tantor.invalid",
		DiscoveredCluster{Name: "cluster-a", BootstrapServers: "broker:9092"},
		"broker-a",
		"",
		false,
		time.Second,
	)

	if attempts.Load() != 1 {
		t.Fatalf("task poll must not retry; got %d attempts", attempts.Load())
	}
	if !body.closed.Load() {
		t.Fatal("task poll error response was not closed")
	}
}
