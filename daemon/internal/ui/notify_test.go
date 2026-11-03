package ui

import (
	"testing"
)

func TestNotifier_Initialization(t *testing.T) {
	n := NewNotifier()
	if n == nil {
		t.Fatal("expected non-nil Notifier")
	}

	// Should not crash even when paths are empty or in headless environments
	err := n.NotifyFileReceived("test.txt", "/tmp/test.txt", "Pixel Phone")
	if err != nil {
		t.Logf("notify returned: %v (expected in headless CI without display)", err)
	}

	err = n.NotifyTextReceived("https://example.com", "Pixel Phone")
	if err != nil {
		t.Logf("notify text returned: %v", err)
	}
}
