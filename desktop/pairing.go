package main

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"math/big"
	"net/http"
	"regexp"
	"strings"
	"time"
)

const codeAlphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
const pairDuration = 5 * time.Minute
const srpPrime = "AC6BDB41324A9A9BF166DE5E1389582FAF72B6651987EE07FC3192943DB56050A37329CBB4A099ED8193E0757767A13DD52312AB4B03310DCD7F48A9DA04FD50E8083969EDB767B0CF6095179A163AB3661A05FBD5FAAAE82918A9962F0B93B855F97993EC975EEAA80D740ADBF4FF747359D041D5C33EA71D281E446B14773BCA97B43A23FB801676BD207A436C6481F1D2B9078717461A5B9D32E688F87748544523B524B0D57D5EA77A2775D2ECFA032CFBDBF52FB3786160279004E57AE6AF874E7303CE53299CCC041C7BC308D82A5698F3A8D0C38271AE35F8E9DBFBB694B5C803D89F7AE435DE236D525F54759B65E372FCD68EF20FA7111F9E4AFF73"

var devicePattern = regexp.MustCompile(`^[A-Za-z0-9_-]{16,100}$`)
var srpN, _ = new(big.Int).SetString(srpPrime, 16)
var srpG = big.NewInt(2)
var srpK = srpInteger(srpHash(srpPad(srpN), srpPad(srpG)))

type pairing struct {
	code     string
	until    time.Time
	attempts int
	session  *pairSession
}

type pairSession struct {
	id, device string
	key        []byte
}

func validDevice(device string) bool { return devicePattern.MatchString(device) }

func (c *controller) issuePair() (string, time.Time, error) {
	b := make([]byte, 6)
	if _, err := rand.Read(b); err != nil {
		return "", time.Time{}, err
	}
	for i := range b {
		b[i] = codeAlphabet[b[i]&31]
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	c.pair = pairing{code: string(b), until: time.Now().Add(pairDuration)}
	return c.pair.code[:3] + "-" + c.pair.code[3:], c.pair.until, nil
}

func srpHash(parts ...[]byte) []byte {
	h := sha256.New()
	for _, part := range parts {
		h.Write(part)
	}
	return h.Sum(nil)
}

func srpInteger(b []byte) *big.Int { return new(big.Int).SetBytes(b) }
func srpPad(n *big.Int) []byte     { return n.FillBytes(make([]byte, 256)) }

func pairProof(key []byte, role, pin, id, device, token string) []byte {
	mac := hmac.New(sha256.New, key)
	mac.Write([]byte(strings.Join([]string{"autopixel-pair-srp6a", role, pin, id, device, token}, "\n")))
	return mac.Sum(nil)
}

// SRP-6a uses the RFC 5054 2048-bit group with SHA-256.
func (c *controller) beginPair(w http.ResponseWriter, r *http.Request) {
	var request struct{ Device, A string }
	if !body(w, r, &request) {
		return
	}
	aBytes, err := hex.DecodeString(request.A)
	if !validDevice(request.Device) || err != nil || len(aBytes) != 256 {
		http.Error(w, "연결 정보 오류", 400)
		return
	}
	a := srpInteger(aBytes)
	if a.Sign() <= 0 || a.Cmp(srpN) >= 0 {
		http.Error(w, "연결 공개값 오류", 400)
		return
	}

	c.mu.Lock()
	defer c.mu.Unlock()
	if c.pair.code == "" || !time.Now().Before(c.pair.until) || c.pair.attempts >= 8 {
		http.Error(w, "PC에서 새 연결 코드를 만들어 주세요.", 410)
		return
	}
	c.pair.attempts++
	secret, salt := make([]byte, 32), make([]byte, 16)
	if _, err = rand.Read(secret); err != nil {
		http.Error(w, "연결 재시도 필요", 500)
		return
	}
	if _, err = rand.Read(salt); err != nil {
		http.Error(w, "연결 재시도 필요", 500)
		return
	}
	b := srpInteger(secret)
	x := srpInteger(srpHash(salt, srpHash([]byte("autopixel:"+c.pair.code))))
	v := new(big.Int).Exp(srpG, x, srpN)
	public := new(big.Int).Exp(srpG, b, srpN)
	public.Add(public, new(big.Int).Mul(srpK, v)).Mod(public, srpN)
	u := srpInteger(srpHash(srpPad(a), srpPad(public)))
	if public.Sign() == 0 || u.Sign() == 0 {
		http.Error(w, "연결 재시도 필요", 500)
		return
	}
	base := new(big.Int).Exp(v, u, srpN)
	base.Mul(base, a).Mod(base, srpN)
	shared := new(big.Int).Exp(base, b, srpN)
	session := &pairSession{id: randomKey(), device: request.Device, key: srpHash(srpPad(shared))}
	c.pair.session = session
	writeJSON(w, map[string]string{"id": session.id, "salt": hex.EncodeToString(salt), "b": hex.EncodeToString(srpPad(public))})
}

func (c *controller) completePair(w http.ResponseWriter, r *http.Request) {
	var request struct{ Device, ID, Proof string }
	if !body(w, r, &request) {
		return
	}
	proof, err := hex.DecodeString(request.Proof)
	if !validDevice(request.Device) || err != nil || len(proof) != sha256.Size {
		http.Error(w, "연결 인증값 오류", 400)
		return
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	session := c.pair.session
	if session == nil || session.id != request.ID || session.device != request.Device || !time.Now().Before(c.pair.until) {
		http.Error(w, "PC에서 새 연결 코드를 만들어 주세요.", 410)
		return
	}
	c.pair.session = nil
	if !hmac.Equal(proof, pairProof(session.key, "phone", c.pin, session.id, session.device, "")) {
		http.Error(w, "연결 코드가 일치하지 않습니다.", 401)
		return
	}
	if c.s.Device != "" && c.s.Device != request.Device {
		http.Error(w, "다른 픽셀과 연결된 프로필입니다.", 409)
		return
	}
	if c.s.Device == "" {
		c.s.Device = request.Device
		if err = c.save(); err != nil {
			http.Error(w, err.Error(), 500)
			return
		}
	}
	c.pair = pairing{}
	writeJSON(w, map[string]string{"token": c.key,
		"proof": hex.EncodeToString(pairProof(session.key, "pc", c.pin, session.id, session.device, c.key))})
}
