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
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"errors"
	"flag"
	"fmt"
	"math/big"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
)

type controller struct {
	mu        sync.Mutex
	s         state
	report    report
	last      time.Time
	statePath string
	key       string
	pin       string
	dataPort  int
	pair      pairing
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
func (c *controller) saveSettings(config settings) error {
	config.Root = strings.TrimSpace(config.Root)
	config.Account = strings.ToLower(strings.TrimSpace(config.Account))
	if config.Root == "" || !validSettings(config) {
		return errors.New("원본 폴더, 계정 및 설정 범위를 확인해 주세요.")
	}
	root, err := filepath.Abs(config.Root)
	if err == nil {
		root, err = filepath.EvalSymlinks(root)
	}
	if err != nil {
		return fmt.Errorf("원본 폴더를 열 수 없습니다. %w", err)
	}
	info, err := os.Stat(root)
	if err != nil || !info.IsDir() {
		return errors.New("원본 폴더를 열 수 없습니다.")
	}
	config.Root = root

	c.mu.Lock()
	defer c.mu.Unlock()
	if c.s.Active != nil && (c.s.Settings.Root != config.Root || c.s.Settings.Account != config.Account) {
		return errors.New("현재 묶음을 정리한 뒤 원본 폴더나 백업 계정을 변경할 수 있습니다.")
	}
	if c.s.Settings.Root != config.Root || len(c.s.Files) == 0 {
		files, skipped, err := scan(config.Root)
		if err != nil {
			return err
		}
		c.s.Files, c.s.Skipped = files, skipped
	}
	c.s.Settings = config
	if ready, _ := c.s.flags(); ready && config.AutoClean {
		c.s.Active.Cleanup = true
	}
	return c.save()
}

func (c *controller) setTransport(transport string) error {
	if transport != "wifi" && transport != "usb" {
		return errors.New("연결 방식을 확인해 주세요.")
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.s.Settings.Transport == transport {
		return nil
	}
	c.s.Settings.Transport = transport
	return c.save()
}

func (c *controller) addMore() error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.s.Settings.Root == "" {
		return errors.New("원본 폴더를 먼저 저장해 주세요.")
	}
	videos, skipped, err := scan(c.s.Settings.Root)
	if err != nil {
		return err
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
	return c.save()
}

func (c *controller) approve(number int) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	ready, _ := c.s.flags()
	if !ready || c.s.Active.ID != number {
		return errors.New("이 묶음의 백업 확인이 아직 끝나지 않았습니다.")
	}
	c.s.Active.Cleanup = true
	return c.save()
}

func (c *controller) settings() settings {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.s.Settings
}

func (c *controller) pairAddress(ip, mode string) (string, error) {
	if mode == "usb" {
		port := "tcp:" + strconv.Itoa(c.dataPort)
		cmd := exec.Command("adb", "-d", "reverse", port, port)
		hideConsole(cmd)
		if out, err := cmd.CombinedOutput(); err != nil {
			detail := strings.TrimSpace(string(out))
			if detail == "" {
				detail = err.Error()
			}
			return "", fmt.Errorf("ADB와 USB 디버깅을 확인해 주세요. %s", detail)
		}
		ip = "127.0.0.1"
	} else {
		valid := false
		for _, address := range localIPs() {
			if address == ip {
				valid = true
				break
			}
		}
		if !valid {
			return "", errors.New("이 PC의 네트워크 주소를 선택해 주세요.")
		}
	}
	if c.dataPort == 47626 {
		return ip, nil
	}
	return net.JoinHostPort(ip, strconv.Itoa(c.dataPort)), nil
}

type videoReader struct {
	file    *os.File
	c       *controller
	startID string
}

func (r videoReader) Seek(offset int64, whence int) (int64, error) {
	return r.file.Seek(offset, whence)
}

func (r videoReader) Read(data []byte) (int, error) {
	r.c.mu.Lock()
	allowed := r.c.s.Running && r.c.report.Accessible && r.c.s.StartID == r.startID
	r.c.mu.Unlock()
	if !allowed {
		return 0, errors.New("전송 일시 중지")
	}
	return r.file.Read(data)
}

func (c *controller) setRunning(enable bool) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if enable {
		if c.last.IsZero() || time.Since(c.last) > time.Duration(c.s.Settings.Interval*4)*time.Second {
			return errors.New("픽셀을 연결한 뒤 전송을 시작하세요.")
		}
		if !c.report.Accessible {
			return errors.New("픽셀에서 오토픽셀 접근성 서비스를 켜 주세요.")
		}
		p := c.s.progress(c.report)
		if p.TotalCount == 0 {
			return errors.New("전송할 영상이 없습니다. 백업 설정에서 폴더를 선택하세요.")
		}
		if !p.Active && p.TotalCount == p.VerifiedCount {
			return errors.New("모든 영상이 확인되었습니다. 새 파일을 검색하세요.")
		}
		if !c.s.Running {
			c.s.StartID = randomKey()
		}
	}
	c.s.Running = enable
	return c.save()
}

func (c *controller) phone() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("POST /v1/sync", func(w http.ResponseWriter, r *http.Request) {
		var p report
		if !body(w, r, &p) {
			return
		}
		if p.Version != 3 {
			http.Error(w, "픽셀의 오토픽셀 앱을 0.3 이상으로 업데이트하세요.", http.StatusUpgradeRequired)
			return
		}
		device := r.Header.Get("X-Device")
		if !validDevice(device) {
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
		newBatch, reason := false, ""
		if c.s.Running && p.Accessible {
			newBatch, reason = c.s.reserve(c.report.Free)
		}
		view := c.s.progress(c.report)
		if c.s.Running && !view.Active && view.TotalCount > 0 && view.TotalCount == view.VerifiedCount {
			c.s.Running = false
			changed = true
		}
		if changed || newBatch {
			if err := c.save(); err != nil {
				http.Error(w, err.Error(), 500)
				return
			}
		}
		writeJSON(w, map[string]any{"active": c.s.Active, "videos": c.s.activeFiles(), "interval": c.s.Settings.Interval,
			"reserve": c.s.Settings.ReserveGB * GB, "account": c.s.Settings.Account, "message": reason,
			"version": 3, "running": c.s.Running, "startId": c.s.StartID, "progress": view})
	})
	mux.HandleFunc("GET /v1/file/{id}", func(w http.ResponseWriter, r *http.Request) {
		c.mu.Lock()
		if c.s.Device == "" || c.s.Device != r.Header.Get("X-Device") {
			c.mu.Unlock()
			http.Error(w, "픽셀 연결을 확인해 주세요.", 403)
			return
		}
		if !c.s.Running || !c.report.Accessible {
			c.mu.Unlock()
			http.Error(w, "PC에서 전송이 대기 중입니다.", http.StatusLocked)
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
		root, startID := c.s.Settings.Root, c.s.StartID
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
		http.ServeContent(w, r, v.Name, time.Unix(0, v.ModTime), videoReader{file: f, c: c, startID: startID})
	})
	outer := http.NewServeMux()
	outer.HandleFunc("POST /v1/pair/start", c.beginPair)
	outer.HandleFunc("POST /v1/pair", c.completePair)
	outer.Handle("/", auth(c.key, mux))
	return outer
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
func run() error {
	dir, err := os.UserConfigDir()
	if err != nil {
		return err
	}
	data := flag.String("data", filepath.Join(dir, "PixelAutoBackup"), "app data directory")
	dataPort := flag.Int("port", 47626, "phone TLS port")
	flag.Parse()
	if *dataPort < 1 || *dataPort > 65535 {
		return errors.New("연결 포트가 올바르지 않습니다.")
	}
	if err := os.MkdirAll(*data, 0700); err != nil {
		return err
	}
	phoneListener, err := net.Listen("tcp", ":"+strconv.Itoa(*dataPort))
	if err != nil {
		return fmt.Errorf("연결 포트를 열 수 없습니다. 실행 중인 오토픽셀을 닫아 주세요. %w", err)
	}
	defer phoneListener.Close()
	cert, pin, err := credentials(*data)
	if err != nil {
		return err
	}
	k, err := token(*data)
	if err != nil {
		return err
	}
	path := filepath.Join(*data, "job.json")
	s, err := loadState(path)
	if err != nil {
		return err
	}
	c := controller{s: s, statePath: path, key: k, pin: pin, dataPort: *dataPort}
	server := http.Server{Handler: c.phone(), ReadHeaderTimeout: 10 * time.Second,
		TLSConfig: &tls.Config{Certificates: []tls.Certificate{cert}, MinVersion: tls.VersionTLS12}}
	defer server.Close()
	go server.ServeTLS(phoneListener, "", "")
	return runWindow(&c)
}
func main() {
	if err := run(); err != nil {
		fatalWindow(err)
	}
}
