package main

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

const pairDevice = "test-pixel-instance-01"

func pairFixture(t *testing.T) (*controller, *httptest.Server) {
	t.Helper()
	c := &controller{s: newState(), statePath: filepath.Join(t.TempDir(), "job.json"), key: randomKey()}
	server := httptest.NewTLSServer(c.phone())
	t.Cleanup(server.Close)
	c.pin = fmt.Sprintf("%x", sha256.Sum256(server.Certificate().Raw))
	return c, server
}

func pairRequest(t *testing.T, server *httptest.Server, method, path string, payload any) (int, map[string]string) {
	t.Helper()
	data, err := json.Marshal(payload)
	if err != nil {
		t.Fatal(err)
	}
	req, err := http.NewRequest(method, server.URL+path, bytes.NewReader(data))
	if err != nil {
		t.Fatal(err)
	}
	response, err := server.Client().Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	var result map[string]string
	if response.StatusCode == 200 {
		if err := json.NewDecoder(response.Body).Decode(&result); err != nil {
			t.Fatal(err)
		}
	} else {
		io.Copy(io.Discard, response.Body)
	}
	return response.StatusCode, result
}

func TestShortPairing(t *testing.T) {
	c, server := pairFixture(t)
	code, _ := pairRequest(t, server, "GET", "/v1/pair/challenge", nil)
	if code != 410 {
		t.Fatalf("pairing opened by default: %d", code)
	}
	issued, _, err := c.issuePair()
	if err != nil {
		t.Fatal(err)
	}
	short := strings.ReplaceAll(issued, "-", "")
	if len(issued) != 14 || len(short) != 12 {
		t.Fatalf("invalid display code length: %d", len(issued))
	}
	code, challenge := pairRequest(t, server, "GET", "/v1/pair/challenge", nil)
	if code != 200 || challenge["nonce"] == "" {
		t.Fatal("no challenge")
	}
	nonce := challenge["nonce"]
	payload := map[string]string{"device": pairDevice, "nonce": nonce,
		"proof": hex.EncodeToString(pairProof(short, "phone", strings.Repeat("0", 64), nonce, pairDevice, ""))}
	if code, _ := pairRequest(t, server, "POST", "/v1/pair", payload); code != 401 {
		t.Fatalf("wrong certificate proof: %d", code)
	}

	payload["proof"] = hex.EncodeToString(pairProof(short, "phone", c.pin, nonce, pairDevice, ""))
	code, result := pairRequest(t, server, "POST", "/v1/pair", payload)
	expected := hex.EncodeToString(pairProof(short, "pc", c.pin, nonce, pairDevice, c.key))
	if code != 200 || result["token"] != c.key || result["proof"] != expected {
		t.Fatalf("pairing rejected or invalid: %d", code)
	}
	durable, err := loadState(c.statePath)
	if err != nil || durable.Device != pairDevice {
		t.Fatal("pairing did not persist")
	}
	if code, _ := pairRequest(t, server, "POST", "/v1/pair", payload); code != 410 {
		t.Fatalf("code reuse: %d", code)
	}

	fresh, _, err := c.issuePair()
	if err != nil {
		t.Fatal(err)
	}
	_, challenge = pairRequest(t, server, "GET", "/v1/pair/challenge", nil)
	nonce = challenge["nonce"]
	other := "another-pixel-instance"
	payload = map[string]string{"device": other, "nonce": nonce,
		"proof": hex.EncodeToString(pairProof(strings.ReplaceAll(fresh, "-", ""), "phone", c.pin, nonce, other, ""))}
	if code, _ := pairRequest(t, server, "POST", "/v1/pair", payload); code != 409 {
		t.Fatalf("profile replaced: %d", code)
	}

	c.mu.Lock()
	c.pair.until = time.Now().Add(-time.Second)
	c.mu.Unlock()
	if code, _ := pairRequest(t, server, "GET", "/v1/pair/challenge", nil); code != 410 {
		t.Fatalf("expired code accepted: %d", code)
	}
}

func TestAndroidPairingInteroperability(t *testing.T) {
	client := os.Getenv("PAB_PAIR_TEST_CLIENT")
	if client == "" {
		t.Skip("PAB_PAIR_TEST_CLIENT is optional")
	}
	c, server := pairFixture(t)
	issued, _, err := c.issuePair()
	if err != nil {
		t.Fatal(err)
	}
	cmd := exec.Command(client, strings.TrimPrefix(server.URL, "https://"), issued, pairDevice)
	if output, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("Android pairing: %v\n%s", err, output)
	}
	durable, err := loadState(c.statePath)
	if err != nil || durable.Device != pairDevice {
		t.Fatalf("Android device binding: %s %v", durable.Device, err)
	}
}
