package deploy

import (
	"os"
	"path/filepath"
	"testing"
)

func TestPathListContains(t *testing.T) {
	javaBin := filepath.Join(string(os.PathSeparator), "opt", "java-17", "bin")
	otherBin := filepath.Join(string(os.PathSeparator), "usr", "bin")
	pathValue := javaBin + string(os.PathListSeparator) + otherBin

	if !pathListContains(pathValue, javaBin) {
		t.Fatalf("expected %q to contain %q", pathValue, javaBin)
	}
	if pathListContains(pathValue, filepath.Join(string(os.PathSeparator), "opt", "java-21", "bin")) {
		t.Fatal("unexpected match for a different Java directory")
	}
}
