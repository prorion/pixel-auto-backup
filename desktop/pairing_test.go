package main

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"math/big"
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

func clientProof(t *testing.T, server *httptest.Server, display, device, pin string) map[string]string {
	t.Helper()
	secret := big.NewInt(37)
	public := new(big.Int).Exp(srpG, secret, srpN)
	status, challenge := pairRequest(t, server, "POST", "/v1/pair/start", map[string]string{
		"a": hex.EncodeToString(srpPad(public)), "device": device})
	if status != 200 {
		t.Fatalf("pair session: %d", status)
	}
	salt, err := hex.DecodeString(challenge["salt"])
	if err != nil {
		t.Fatal(err)
	}
	b, ok := new(big.Int).SetString(challenge["b"], 16)
	if !ok {
		t.Fatal("missing server public value")
	}
	x := srpInteger(srpHash(salt, srpHash([]byte("autopixel:"+strings.ReplaceAll(display, "-", "")))))
	u := srpInteger(srpHash(srpPad(public), srpPad(b)))
	base := new(big.Int).Exp(srpG, x, srpN)
	base.Sub(b, base.Mul(base, srpK)).Mod(base, srpN)
	exponent := new(big.Int).Add(secret, new(big.Int).Mul(u, x))
	key := srpHash(srpPad(new(big.Int).Exp(base, exponent, srpN)))
	return map[string]string{"device": device, "id": challenge["id"],
		"proof": hex.EncodeToString(pairProof(key, "phone", pin, challenge["id"], device, ""))}
}

func TestShortPairing(t *testing.T) {
	c, server := pairFixture(t)
	a := hex.EncodeToString(srpPad(srpG))
	begin := map[string]string{"device": pairDevice, "a": a}
	if status, _ := pairRequest(t, server, "POST", "/v1/pair/start", begin); status != 410 {
		t.Fatalf("pairing open by default: %d", status)
	}
	issued, _, err := c.issuePair()
	if err != nil {
		t.Fatal(err)
	}
	if len(issued) != 7 || issued[3] != '-' {
		t.Fatalf("bad pairing format: %q", issued)
	}

	payload := clientProof(t, server, issued, pairDevice, strings.Repeat("0", 64))
	if status, _ := pairRequest(t, server, "POST", "/v1/pair", payload); status != 401 {
		t.Fatalf("wrong certificate proof: %d", status)
	}
	payload = clientProof(t, server, issued, pairDevice, c.pin)
	status, result := pairRequest(t, server, "POST", "/v1/pair", payload)
	if status != 200 || result["token"] != c.key || result["proof"] == "" {
		t.Fatalf("invalid pairing: %d", status)
	}
	saved, err := loadState(c.statePath)
	if err != nil || saved.Device != pairDevice {
		t.Fatal("pairing did not persist")
	}
	if status, _ := pairRequest(t, server, "POST", "/v1/pair", payload); status != 410 {
		t.Fatalf("accepted replay: %d", status)
	}

	fresh, _, err := c.issuePair()
	if err != nil {
		t.Fatal(err)
	}
	payload = clientProof(t, server, fresh, "another-pixel-instance", c.pin)
	if status, _ := pairRequest(t, server, "POST", "/v1/pair", payload); status != 409 {
		t.Fatalf("replaced profile: %d", status)
	}
	c.mu.Lock()
	c.pair.until = time.Now().Add(-time.Second)
	c.mu.Unlock()
	if status, _ := pairRequest(t, server, "POST", "/v1/pair/start", begin); status != 410 {
		t.Fatalf("expired code accepted: %d", status)
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
