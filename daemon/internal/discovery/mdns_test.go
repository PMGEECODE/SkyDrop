package discovery

import (
	"testing"
)

func TestAdvertiser_Validation(t *testing.T) {
	adv := NewAdvertiser()
	defer adv.Shutdown()

	// Missing instance name
	err := adv.Register(ServiceConfig{
		InstanceName: "",
		Port:         8443,
	})
	if err == nil {
		t.Fatal("expected error for empty instance name")
	}

	// Invalid port
	err = adv.Register(ServiceConfig{
		InstanceName: "TestLaptop",
		Port:         -1,
	})
	if err == nil {
		t.Fatal("expected error for invalid port")
	}
}

func TestAdvertiser_RegisterAndShutdown(t *testing.T) {
	adv := NewAdvertiser()

	err := adv.Register(ServiceConfig{
		InstanceName: "TestLaptopUnit",
		ServiceName:  "_filedrop._tcp",
		Port:         8443,
		TextRecords:  []string{"version=1.0.0"},
	})
	if err != nil {
		t.Fatalf("failed to register advertiser: %v", err)
	}

	// Attempting double register should fail
	err = adv.Register(ServiceConfig{
		InstanceName: "TestLaptopUnit",
		Port:         8443,
	})
	if err == nil {
		t.Fatal("expected error on duplicate register call")
	}

	adv.Shutdown()

	// Should be safe to call Shutdown multiple times
	adv.Shutdown()
}
