package api

import (
	"encoding/json"
	"reflect"
	"testing"
)

func TestTaskUnmarshalNormalizesParameterValues(t *testing.T) {
	payload := []byte(`{
		"task_id":"task-1",
		"claim_token":"claim-1",
		"cluster_id":"cluster-1",
		"command":"PRECHECK_SCHEMA",
		"parameters":{
			"name":"schema-registry",
			"port":8081,
			"enabled":true,
			"ratio":1.25,
			"unset":null,
			"metadata":{"role":"schema"}
		}
	}`)

	var task Task
	if err := json.Unmarshal(payload, &task); err != nil {
		t.Fatalf("unmarshal task: %v", err)
	}

	want := map[string]string{
		"name":     "schema-registry",
		"port":     "8081",
		"enabled":  "true",
		"ratio":    "1.25",
		"unset":    "",
		"metadata": `{"role":"schema"}`,
	}
	if !reflect.DeepEqual(task.Parameters, want) {
		t.Fatalf("parameters mismatch\n got: %#v\nwant: %#v", task.Parameters, want)
	}
	if task.TaskID != "task-1" || task.ClaimToken != "claim-1" || task.Command != "PRECHECK_SCHEMA" {
		t.Fatalf("normal task fields were not preserved: %#v", task)
	}
}

func TestTaskUnmarshalInitializesMissingParameters(t *testing.T) {
	var task Task
	if err := json.Unmarshal([]byte(`{"task_id":"task-2","command":"HEARTBEAT"}`), &task); err != nil {
		t.Fatalf("unmarshal task: %v", err)
	}
	if task.Parameters == nil || len(task.Parameters) != 0 {
		t.Fatalf("expected initialized empty parameters, got %#v", task.Parameters)
	}
}
