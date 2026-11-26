package main

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	_ "embed"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"math/big"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"time"
)

//go:embed dashboard.html
var dashboard []byte

type controller struct {
	mu        sync.Mutex
	s         state
	report    report
	last      time.Time
	statePath string
	key       string
	webKey    string
	pin       string
	dataPort  int
}

func randomKey() string {
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	return base64.RawURLEncoding.EncodeToString(b)
}
func credentials(dir string) (tls.Certificate, string, error) {
	certPath, keyPath := filepath.Join(dir, "pc.crt"), filepath.Join(dir, "pc.key")
	cert, err := tls.LoadX509KeyPair(certPath, keyPath)
	if errors.Is(err, os.ErrNotExist) {
		key, e := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
		if e != nil {
			return cert, "", e
		}
		n, e := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 128))
		if e != nil {
			return cert, "", e
		}
		template := x509.Certificate{SerialNumber: n, Subject: pkix.Name{CommonName: "Pixel Auto Backup"},
			NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().AddDate(10, 0, 0),
			KeyUsage: x509.KeyUsageDigitalSignature, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}}
		der, e := x509.CreateCertificate(rand.Reader, &template, &template, &key.PublicKey, key)
		if e != nil {
			return cert, "", e
		}
		private, e := x509.MarshalECPrivateKey(key)
		if e != nil {
			return cert, "", e
		}
		if e = os.WriteFile(keyPath, pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: private}), 0600); e != nil {
			return cert, "", e
		}
		if e = os.WriteFile(certPath, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}), 0600); e != nil {
			return cert, "", e
		}
		cert, err = tls.LoadX509KeyPair(certPath, keyPath)
	}
	if err != nil {
		return cert, "", err
	}
	return cert, fmt.Sprintf("%x", sha256.Sum256(cert.Certificate[0])), nil
}
func writeJSON(w http.ResponseWriter, data any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	json.NewEncoder(w).Encode(data)
}
func body(w http.ResponseWriter, r *http.Request, data any) bool {
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4<<20)).Decode(data); err != nil {
		http.Error(w, "요청을 읽을 수 없습니다.", 400)
		return false
	}
	return true
}
func auth(key string, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if subtle.ConstantTimeCompare([]byte(r.Header.Get("Authorization")), []byte("Bearer "+key)) != 1 {
			http.Error(w, "연결 인증이 필요합니다.", 401)
			return
		}
		next.ServeHTTP(w, r)
	})
}
func localIPs() []string {
	addrs, _ := net.InterfaceAddrs()
	var result []string
	for _, a := range addrs {
		ip, _, _ := net.ParseCIDR(a.String())
		if ip != nil && ip.To4() != nil && !ip.IsLoopback() && !ip.IsLinkLocalUnicast() {
			result = append(result, ip.String())
		}
	}
	return result
}
func (c *controller) save() error {
	if err := store(c.statePath, c.s); err != nil {
		s, loadErr := loadState(c.statePath)
		if loadErr == nil {
			c.s = s
		}
		return err
	}
	return nil
}
func (c *controller) saveSettings(w http.ResponseWriter, r *http.Request) {
	var config settings
	if !body(w, r, &config) {
		return
	}
	config.Root = strings.TrimSpace(config.Root)
	config.Account = strings.ToLower(strings.TrimSpace(config.Account))
	if config.Root == "" || !validSettings(config) {
		http.Error(w, "원본 폴더, 계정 및 설정 범위를 확인해 주세요.", 400)
		return
	}
	var err error
	config.Root, err = filepath.Abs(config.Root)
	if err == nil {
		config.Root, err = filepath.EvalSymlinks(config.Root)
	}
	info, statErr := os.Stat(config.Root)
	if err != nil || statErr != nil || !info.IsDir() {
		http.Error(w, "원본 폴더를 열 수 없습니다.", 400)
		return
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.s.Active != nil && (c.s.Settings.Root != config.Root || c.s.Settings.Account != config.Account) {
		http.Error(w, "현재 작업을 정리한 뒤 원본 폴더나 백업 계정을 변경할 수 있습니다.", 409)
		return
	}
	if c.s.Settings.Root != config.Root || len(c.s.Files) == 0 {
		files, skipped, e := scan(config.Root)
		if e != nil {
			http.Error(w, e.Error(), 400)
			return
		}
		c.s.Files, c.s.Skipped = files, skipped
	}
	c.s.Settings = config
	if ready, _ := c.s.flags(); ready && config.AutoClean {
		c.s.Active.Cleanup = true
	}
	if err = c.save(); err != nil {
		http.Error(w, err.Error(), 500)
		return
	}
	writeJSON(w, map[string]bool{"ok": true})
}
func (c *controller) addMore(w http.ResponseWriter, r *http.Request) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.s.Settings.Root == "" {
		http.Error(w, "원본 폴더를 먼저 저장해 주세요.", 409)
		return
	}
	videos, skipped, err := scan(c.s.Settings.Root)
	if err != nil {
		http.Error(w, err.Error(), 400)
		return
	}
	seen := make(map[string]bool, len(c.s.Files))
	for _, v := range c.s.Files {
		seen[v.ID] = true
	}
	for _, v := range videos {
		if !seen[v.ID] {
			c.s.Files = append(c.s.Files, v)
		}
	}
	c.s.Skipped = skipped
	if err = c.save(); err != nil {
		http.Error(w, err.Error(), 500)
		return
	}
	writeJSON(w, map[string]bool{"ok": true})
}
func (c *controller) ui() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /{$}", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Header().Set("Cache-Control", "no-store")
		w.Write(dashboard)
	})
	api := http.NewServeMux()
	api.HandleFunc("GET /ui/status", func(w http.ResponseWriter, r *http.Request) {
		c.mu.Lock()
		defer c.mu.Unlock()
		skipped := c.s.Skipped
		if len(skipped) > 30 {
			skipped = skipped[:30]
		}
		stale := c.last.IsZero() || time.Since(c.last) > time.Duration(c.s.Settings.Interval*4)*time.Second
		writeJSON(w, map[string]any{"settings": c.s.Settings, "progress": c.s.progress(c.report), "last": checkedTime(c.last),
			"phone": c.report, "stale": stale, "skipped": skipped, "skippedCount": len(c.s.Skipped), "ips": localIPs()})
	})
	api.HandleFunc("POST /ui/settings", c.saveSettings)
	api.HandleFunc("POST /ui/scan", c.addMore)
	api.HandleFunc("POST /ui/approve", func(w http.ResponseWriter, r *http.Request) {
		var request struct{ Batch int }
		if !body(w, r, &request) {
			return
		}
		c.mu.Lock()
		defer c.mu.Unlock()
		ready, _ := c.s.flags()
		if !ready || c.s.Active.ID != request.Batch {
			http.Error(w, "이 묶음의 백업 확인이 아직 끝나지 않았습니다.", 409)
			return
		}
		c.s.Active.Cleanup = true
		if err := c.save(); err != nil {
			http.Error(w, err.Error(), 500)
			return
		}
		writeJSON(w, map[string]bool{"ok": true})
	})
	api.HandleFunc("POST /ui/pair", func(w http.ResponseWriter, r *http.Request) {
		var request struct {
			IP   string
			Mode string
		}
		if !body(w, r, &request) {
			return
		}
		if request.Mode == "usb" {
			port := "tcp:" + strconv.Itoa(c.dataPort)
			cmd := exec.Command("adb", "-d", "reverse", port, port)
			if out, err := cmd.CombinedOutput(); err != nil {
				http.Error(w, "ADB와 USB 디버깅을 확인해 주세요. "+strings.TrimSpace(string(out)), 400)
				return
			}
			request.IP = "127.0.0.1"
		} else {
			valid := false
			for _, ip := range localIPs() {
				if ip == request.IP {
					valid = true
				}
			}
			if !valid {
				http.Error(w, "이 PC의 네트워크 주소를 선택해 주세요.", 400)
				return
			}
		}
		data, _ := json.Marshal(map[string]string{
			"url": "https://" + net.JoinHostPort(request.IP, strconv.Itoa(c.dataPort)),
			"pin": c.pin, "token": c.key,
		})
		writeJSON(w, map[string]string{"code": "PAB1." + base64.RawURLEncoding.EncodeToString(data)})
	})
	mux.Handle("/ui/", auth(c.webKey, api))
	return mux
}
func (c *controller) phone() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("POST /v1/sync", func(w http.ResponseWriter, r *http.Request) {
		var p report
		if !body(w, r, &p) {
			return
		}
		device := r.Header.Get("X-Device")
		if len(device) < 16 || len(device) > 100 {
			http.Error(w, "픽셀 식별 정보 오류", 400)
			return
		}
		c.mu.Lock()
		defer c.mu.Unlock()
		if c.s.Device != "" && c.s.Device != device {
			http.Error(w, "다른 픽셀과 연결된 작업입니다.", 409)
			return
		}
		changed := false
		if c.s.Device == "" {
			c.s.Device = device
			changed = true
		}
		c.report, c.last = p, time.Now()
		c.report.Free = max(p.Free, 0)
		if c.s.apply(p) {
			changed = true
		}
		newBatch, reason := c.s.reserve(c.report.Free)
		if changed || newBatch {
			if err := c.save(); err != nil {
				http.Error(w, err.Error(), 500)
				return
			}
		}
		writeJSON(w, map[string]any{"active": c.s.Active, "videos": c.s.activeFiles(), "interval": c.s.Settings.Interval,
			"reserve": c.s.Settings.ReserveGB * GB, "account": c.s.Settings.Account, "message": reason})
	})
	mux.HandleFunc("GET /v1/file/{id}", func(w http.ResponseWriter, r *http.Request) {
		c.mu.Lock()
		if c.s.Device == "" || c.s.Device != r.Header.Get("X-Device") {
			c.mu.Unlock()
			http.Error(w, "픽셀 연결을 확인해 주세요.", 403)
			return
		}
		var found *video
		for _, v := range c.s.activeFiles() {
			if v.ID == r.PathValue("id") {
				found = v
				break
			}
		}
		if found == nil {
			c.mu.Unlock()
			http.NotFound(w, r)
			return
		}
		v := *found
		root := c.s.Settings.Root
		c.mu.Unlock()
		f, err := source(root, &v)
		if err != nil {
			http.Error(w, err.Error(), 409)
			return
		}
		defer f.Close()
		if v.SHA == "" {
			v.SHA, err = hashFile(f)
			if err != nil {
				http.Error(w, err.Error(), 500)
				return
			}
			c.mu.Lock()
			for _, item := range c.s.activeFiles() {
				if item.ID == v.ID {
					item.SHA = v.SHA
				}
			}
			err = c.save()
			c.mu.Unlock()
			if err != nil {
				http.Error(w, err.Error(), 500)
				return
			}
		}
		w.Header().Set("X-File-SHA256", v.SHA)
		w.Header().Set("Content-Type", "application/octet-stream")
		http.ServeContent(w, r, v.Name, time.Unix(0, v.ModTime), f)
	})
	return auth(c.key, mux)
}
func token(dir string) (string, error) {
	path := filepath.Join(dir, "pair.token")
	if b, err := os.ReadFile(path); err == nil {
		return strings.TrimSpace(string(b)), nil
	} else if !errors.Is(err, os.ErrNotExist) {
		return "", err
	}
	k := randomKey()
	return k, os.WriteFile(path, []byte(k), 0600)
}
func openBrowser(url string) {
	switch runtime.GOOS {
	case "windows":
		exec.Command("rundll32.exe", "url.dll,FileProtocolHandler", url).Start()
	case "darwin":
		exec.Command("open", url).Start()
	default:
		exec.Command("xdg-open", url).Start()
	}
}
func main() {
	dir, _ := os.UserConfigDir()
	data := flag.String("data", filepath.Join(dir, "PixelAutoBackup"), "app data directory")
	uiPort := flag.Int("ui-port", 47625, "loopback dashboard port")
	dataPort := flag.Int("port", 47626, "phone TLS port")
	browser := flag.Bool("browser", true, "open dashboard automatically")
	flag.Parse()
	if err := os.MkdirAll(*data, 0700); err != nil {
		log.Fatal(err)
	}
	uiListener, err := net.Listen("tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(*uiPort)))
	if err != nil {
		log.Fatal(err)
	}
	phoneListener, err := net.Listen("tcp", ":"+strconv.Itoa(*dataPort))
	if err != nil {
		log.Fatal(err)
	}
	cert, pin, err := credentials(*data)
	if err != nil {
		log.Fatal(err)
	}
	k, err := token(*data)
	if err != nil {
		log.Fatal(err)
	}
	path := filepath.Join(*data, "job.json")
	s, err := loadState(path)
	if err != nil {
		log.Fatal(err)
	}
	c := controller{s: s, statePath: path, key: k, webKey: randomKey(), pin: pin, dataPort: *dataPort}
	go func() {
		server := http.Server{Handler: c.phone(), ReadHeaderTimeout: 10 * time.Second,
			TLSConfig: &tls.Config{Certificates: []tls.Certificate{cert}, MinVersion: tls.VersionTLS12}}
		log.Fatal(server.ServeTLS(phoneListener, "", ""))
	}()
	url := "http://" + uiListener.Addr().String() + "/#" + c.webKey
	fmt.Fprintln(os.Stdout, url)
	if *browser {
		openBrowser(url)
	}
	server := http.Server{Handler: c.ui(), ReadHeaderTimeout: 10 * time.Second}
	err = server.Serve(uiListener)
	if !errors.Is(err, http.ErrServerClosed) && !errors.Is(err, io.EOF) {
		log.Fatal(err)
	}
}
