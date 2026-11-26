package main

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"net/http"
	"regexp"
	"strings"
	"time"
)

const codeAlphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
const pairDuration = 5 * time.Minute

var devicePattern = regexp.MustCompile(`^[A-Za-z0-9_-]{16,100}$`)

type pairing struct {
	code, nonce string
	until       time.Time
	attempts    int
}

func validDevice(device string) bool {
	return devicePattern.MatchString(device)
}

func (c *controller) issuePair() (string, time.Time, error) {
	b := make([]byte, 12)
	if _, err := rand.Read(b); err != nil {
		return "", time.Time{}, err
	}
	for i := range b {
		b[i] = codeAlphabet[b[i]&31]
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	c.pair = pairing{code: string(b), nonce: randomKey(), until: time.Now().Add(pairDuration)}
	return c.pair.code[:4] + "-" + c.pair.code[4:8] + "-" + c.pair.code[8:], c.pair.until, nil
}

func pairProof(code, role, pin, nonce, device, token string) []byte {
	mac := hmac.New(sha256.New, []byte(code))
	mac.Write([]byte(strings.Join([]string{"pab-pair-v2", role, pin, nonce, device, token}, "\n")))
	return mac.Sum(nil)
}

func (c *controller) challenge(w http.ResponseWriter, r *http.Request) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.pair.code == "" || !time.Now().Before(c.pair.until) {
		http.Error(w, "PC에서 새 연결 코드를 만들어 주세요.", http.StatusGone)
		return
	}
	writeJSON(w, map[string]string{"nonce": c.pair.nonce})
}

func (c *controller) completePair(w http.ResponseWriter, r *http.Request) {
	var request struct{ Device, Nonce, Proof string }
	if !body(w, r, &request) {
		return
	}
	if !validDevice(request.Device) {
		http.Error(w, "픽셀 식별 정보 오류", http.StatusBadRequest)
		return
	}
	proof, err := hex.DecodeString(request.Proof)
	if err != nil || len(proof) != sha256.Size {
		http.Error(w, "연결 인증값 오류", http.StatusBadRequest)
		return
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	active := c.pair
	if active.code == "" || active.nonce != request.Nonce || !time.Now().Before(active.until) {
		http.Error(w, "PC에서 새 연결 코드를 만들어 주세요.", http.StatusGone)
		return
	}
	if !hmac.Equal(proof, pairProof(active.code, "phone", c.pin, active.nonce, request.Device, "")) {
		c.pair.attempts++
		if c.pair.attempts >= 8 {
			c.pair = pairing{}
		}
		http.Error(w, "연결 코드가 일치하지 않습니다.", http.StatusUnauthorized)
		return
	}
	if c.s.Device != "" && c.s.Device != request.Device {
		http.Error(w, "다른 픽셀과 연결된 프로필입니다.", http.StatusConflict)
		return
	}
	if c.s.Device == "" {
		c.s.Device = request.Device
		if err := c.save(); err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
	}
	c.pair = pairing{}
	writeJSON(w, map[string]string{"token": c.key,
		"proof": hex.EncodeToString(pairProof(active.code, "pc", c.pin, active.nonce, request.Device, c.key))})
}
