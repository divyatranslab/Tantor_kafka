package deploy

import (
	"reflect"
	"testing"
)

func TestPrerequisitePorts(t *testing.T) {
	got := prerequisitePorts("9092,2181,2888,3888,2181,invalid")
	want := []string{"9092", "2181", "2888", "3888"}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("prerequisitePorts() = %v, want %v", got, want)
	}
}

func TestPrerequisitePortsUsesKafkaDefaults(t *testing.T) {
	got := prerequisitePorts("")
	want := []string{"9092", "9093", "7071", "7072"}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("prerequisitePorts() = %v, want %v", got, want)
	}
}
