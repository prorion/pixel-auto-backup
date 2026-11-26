package main

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestRestartAndResumeRange(t *testing.T) {
	dir := t.TempDir()
	sourceDir := t.TempDir()
	original := "0123456789abcdef"
	if err := os.WriteFile(filepath.Join(sourceDir, "camera.mp4"), []byte(original), 0600); err != nil {
		t.Fatal(err)
	}
	os.WriteFile(filepath.Join(sourceDir, "notes.txt"), []byte("skip"), 0600)
	files, skipped, err := scan(sourceDir)
	if err != nil || len(files) != 1 || len(skipped) != 1 {
		t.Fatalf("scan: %v %v %v", files, skipped, err)
	}
	s := newState()
	s.Settings.Root = sourceDir
	s.Files = files
	s.Settings.ReserveGB = 0
	s.Device = "registered-device"
	s.Running = true
	s.reserve(GB)
	path := filepath.Join(dir, "job.json")
	if err = store(path, s); err != nil {
		t.Fatal(err)
	}
	restored, err := loadState(path)
	if err != nil || restored.Active == nil || restored.Active.ID != 1 || len(restored.Files) != 1 {
		t.Fatalf("reload: %+v %v", restored, err)
	}
	c := controller{s: restored, statePath: path, key: "test", report: report{Accessible: true}}
	r := httptest.NewRequest("GET", "/v1/file/"+files[0].ID, nil)
	r.Header.Set("Authorization", "Bearer test")
	r.Header.Set("X-Device", s.Device)
	r.Header.Set("Range", "bytes=5-")
	w := httptest.NewRecorder()
	c.phone().ServeHTTP(w, r)
	if w.Code != 206 || w.Body.String() != original[5:] || w.Header().Get("X-File-SHA256") == "" {
		t.Fatalf("resumed download: %v %v", w.Code, w.Body.String())
	}
	c.phone().ServeHTTP(httptest.NewRecorder(), r.Clone(r.Context()))
	restored, err = loadState(path)
	if err != nil || restored.Files[0].SHA != w.Header().Get("X-File-SHA256") {
		t.Fatal("hash did not persist")
	}
}

func TestApprovalAndDuplicateReports(t *testing.T) {
	s := newState()
	s.Settings.ReserveGB = 0
	s.Files = []*video{{ID: "a", Size: 200}, {ID: "b", Size: 300}, {ID: "c", Size: 600}}
	s.reserve(500)
	r := report{Batch: 1, Files: map[string]int{"a": verified, "b": copied, "c": verified}, Current: "a", Bytes: 100}
	s.apply(r)
	if s.apply(r) {
		t.Fatal("duplicate report changed durable state")
	}
	p := s.progress(r)
	if p.Ready || p.BatchReceived != 500 || p.VerifiedCount != 1 || p.LastVerified == 0 {
		t.Fatalf("mixed report: %+v", p)
	}
	r.Files["a"] = cleaned
	s.apply(r)
	if s.Files[0].Stage != verified {
		t.Fatal("cleanup accepted before authorization")
	}
	r.Files["b"] = verified
	s.apply(r)
	if ready, _ := s.flags(); !ready || s.Active.Cleanup {
		t.Fatal("manual approval gate failed")
	}
	s.Active.Cleanup = true
	r.Files["b"] = cleaned
	s.apply(r)
	if s.Active != nil || s.progress(r).VerifiedCount != 2 {
		t.Fatal("batch did not close or lost verified count")
	}
	s.reserve(600)
	if s.Active == nil || s.Active.ID != 2 || s.Active.IDs[0] != "c" {
		t.Fatal("next batch not selected")
	}
	s.apply(r)
	if s.Files[2].Stage != queued {
		t.Fatal("old batch report modified new batch")
	}
}

func TestSyncRequiresAuthenticationAndKnownDevice(t *testing.T) {
	s := newState()
	s.Device = "first-device-identifier"
	c := controller{s: s, key: "secret"}
	server := httptest.NewServer(c.phone())
	defer server.Close()
	call := func(key, device string) (int, string) {
		data, _ := json.Marshal(report{Version: 3})
		r, _ := http.NewRequest("POST", server.URL+"/v1/sync", strings.NewReader(string(data)))
		if key != "" {
			r.Header.Set("Authorization", "Bearer "+key)
		}
		r.Header.Set("X-Device", device)
		response, err := server.Client().Do(r)
		if err != nil {
			t.Fatal(err)
		}
		defer response.Body.Close()
		payload, err := io.ReadAll(response.Body)
		if err != nil {
			t.Fatal(err)
		}
		return response.StatusCode, string(payload)
	}
	if code, _ := call("", s.Device); code != 401 {
		t.Fatalf("unauthenticated request: %d", code)
	}
	if code, _ := call("secret", "other-device-identifier"); code != 409 {
		t.Fatalf("unpaired phone: %d", code)
	}
	if code, text := call("secret", s.Device); code != 200 || !strings.Contains(text, "\"interval\":15") {
		t.Fatalf("paired phone: %d %s", code, text)
	}
}

func TestPairThenExplicitStartAndPause(t *testing.T) {
	s := newState()
	s.Device = "waiting-pixel-instance"
	s.Settings.ReserveGB = 0
	s.Files = []*video{{ID: "vid", Size: 100}}
	c := controller{s: s, key: "test", statePath: filepath.Join(t.TempDir(), "job.json")}
	handler := c.phone()
	post := func(access bool) map[string]any {
		data, _ := json.Marshal(report{Version: 3, Accessible: access, Free: GB})
		req := httptest.NewRequest("POST", "/v1/sync", strings.NewReader(string(data)))
		req.Header.Set("Authorization", "Bearer test")
		req.Header.Set("X-Device", s.Device)
		w := httptest.NewRecorder()
		handler.ServeHTTP(w, req)
		if w.Code != 200 {
			t.Fatalf("sync: %d", w.Code)
		}
		var response map[string]any
		if err := json.Unmarshal(w.Body.Bytes(), &response); err != nil {
			t.Fatal(err)
		}
		return response
	}
	requestFile := func() int {
		req := httptest.NewRequest("GET", "/v1/file/vid", nil)
		req.Header.Set("Authorization", "Bearer test")
		req.Header.Set("X-Device", s.Device)
		w := httptest.NewRecorder()
		handler.ServeHTTP(w, req)
		return w.Code
	}
	if response := post(false); response["active"] != nil {
		t.Fatal("connection started transfer")
	}
	if err := c.setRunning(true); err == nil {
		t.Fatal("started while accessibility was off")
	}
	if response := post(true); response["active"] != nil || response["running"] != false {
		t.Fatal("accessibility started transfer")
	}
	if err := c.setRunning(true); err != nil {
		t.Fatal(err)
	}
	if response := post(true); response["active"] == nil || response["running"] != true || response["startId"] == "" {
		t.Fatalf("missing explicit start: %v", response)
	}
	if err := c.setRunning(false); err != nil {
		t.Fatal(err)
	}
	if code := requestFile(); code != http.StatusLocked {
		t.Fatalf("paused file access: %d", code)
	}
	if response := post(true); response["active"] == nil || response["running"] != false {
		t.Fatal("pause lost staging batch")
	}
	if err := c.setRunning(true); err != nil {
		t.Fatal(err)
	}
	post(false)
	if code := requestFile(); code != http.StatusLocked {
		t.Fatalf("permission-off file access: %d", code)
	}
}
