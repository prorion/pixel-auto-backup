package main

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

const GB int64 = 1_000_000_000

type settings struct {
	Root      string `json:"root"`
	BatchGB   int64  `json:"batchGB"`
	ReserveGB int64  `json:"reserveGB"`
	Interval  int    `json:"interval"`
	AutoClean bool   `json:"autoClean"`
	Account   string `json:"account"`
	Transport string `json:"transport"`
}
type video struct {
	ID         string `json:"id"`
	Path       string `json:"path"`
	Name       string `json:"name"`
	Size       int64  `json:"size"`
	ModTime    int64  `json:"modTime"`
	SHA        string `json:"sha,omitempty"`
	Stage      int    `json:"stage"`
	VerifiedAt int64  `json:"verifiedAt,omitempty"`
}

const (
	queued = iota
	copied
	verified
	cleaned
)

type batch struct {
	ID      int      `json:"id"`
	IDs     []string `json:"ids"`
	Cleanup bool     `json:"cleanup"`
}
type state struct {
	Settings settings `json:"settings"`
	Files    []*video `json:"files"`
	Skipped  []string `json:"skipped"`
	Device   string   `json:"device"`
	Active   *batch   `json:"active"`
	Sequence int      `json:"sequence"`
}
type report struct {
	Batch    int            `json:"batch"`
	Files    map[string]int `json:"files"`
	Current  string         `json:"current"`
	Bytes    int64          `json:"bytes"`
	Free     int64          `json:"free"`
	Locked   bool           `json:"locked"`
	Charging bool           `json:"charging"`
	Message  string         `json:"message"`
}
type progress struct {
	TotalCount    int    `json:"totalCount"`
	TotalBytes    int64  `json:"totalBytes"`
	VerifiedCount int    `json:"verifiedCount"`
	VerifiedBytes int64  `json:"verifiedBytes"`
	BatchCount    int    `json:"batchCount"`
	BatchBytes    int64  `json:"batchBytes"`
	BatchCopied   int    `json:"batchCopied"`
	BatchReceived int64  `json:"batchReceived"`
	BatchVerified int    `json:"batchVerified"`
	Ready         bool   `json:"ready"`
	Active        bool   `json:"active"`
	Cleaning      bool   `json:"cleaning"`
	BatchNumber   int    `json:"batchNumber"`
	LastVerified  int64  `json:"lastVerified"`
	Current       string `json:"current"`
}

var videoExt = map[string]bool{
	".mpg": true, ".mod": true, ".mmv": true, ".tod": true, ".wmv": true, ".asf": true,
	".avi": true, ".divx": true, ".mov": true, ".m4v": true, ".3gp": true, ".3g2": true,
	".mp4": true, ".m2t": true, ".m2ts": true, ".mts": true, ".mkv": true,
}

func newState() state {
	return state{Settings: settings{BatchGB: 50, ReserveGB: 10, Interval: 15, Transport: "wifi"}}
}
func loadState(path string) (state, error) {
	s := newState()
	data, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return s, nil
	}
	if err != nil {
		return s, err
	}
	err = json.Unmarshal(data, &s)
	return s, err
}
func store(path string, s state) error {
	f, err := os.CreateTemp(filepath.Dir(path), ".pab-state-")
	if err != nil {
		return err
	}
	defer os.Remove(f.Name())
	defer f.Close()
	if err = json.NewEncoder(f).Encode(s); err != nil {
		return err
	}
	if err = f.Sync(); err != nil {
		return err
	}
	if err = f.Close(); err != nil {
		return err
	}
	return os.Rename(f.Name(), path)
}
func scan(root string) ([]*video, []string, error) {
	var videos []*video
	var skipped []string
	err := filepath.WalkDir(root, func(path string, d fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if d.IsDir() {
			return nil
		}
		rel, err := filepath.Rel(root, path)
		if err != nil {
			return err
		}
		info, err := d.Info()
		if err != nil {
			return err
		}
		ext := strings.ToLower(filepath.Ext(rel))
		if !info.Mode().IsRegular() || !videoExt[ext] || info.Size() <= 0 || info.Size() > 10*GB {
			skipped = append(skipped, rel)
			return nil
		}
		id := fmt.Sprintf("%x", sha256.Sum256([]byte(fmt.Sprintf("%s\n%s\n%d\n%d", root, rel, info.Size(), info.ModTime().UnixNano()))))
		stem := strings.TrimSuffix(filepath.Base(rel), filepath.Ext(rel))
		runes := []rune(stem)
		if len(runes) > 48 {
			stem = string(runes[:48])
		}
		name := stem + "_" + id[:16] + ext
		videos = append(videos, &video{ID: id, Path: rel, Name: name, Size: info.Size(), ModTime: info.ModTime().UnixNano()})
		return nil
	})
	sort.Strings(skipped)
	return videos, skipped, err
}
func source(root string, v *video) (*os.File, error) {
	// Root.Open keeps admitted paths within the selected directory even after a link changes.
	dir, err := os.OpenRoot(root)
	if err != nil {
		return nil, err
	}
	defer dir.Close()
	f, err := dir.Open(v.Path)
	if err != nil {
		return nil, err
	}
	st, err := f.Stat()
	if err == nil && st.Mode().IsRegular() && st.Size() == v.Size && st.ModTime().UnixNano() == v.ModTime {
		return f, nil
	}
	f.Close()
	return nil, fmt.Errorf("목록 작성 후 원본이 바뀌었습니다 (%s)", v.Path)
}
func hashFile(f *os.File) (string, error) {
	h := sha256.New()
	if _, err := io.Copy(h, f); err != nil {
		return "", err
	}
	if _, err := f.Seek(0, io.SeekStart); err != nil {
		return "", err
	}
	return hex.EncodeToString(h.Sum(nil)), nil
}
func (s *state) activeFiles() []*video {
	if s.Active == nil {
		return nil
	}
	set := make(map[string]bool, len(s.Active.IDs))
	for _, id := range s.Active.IDs {
		set[id] = true
	}
	files := make([]*video, 0, len(set))
	for _, v := range s.Files {
		if set[v.ID] {
			files = append(files, v)
		}
	}
	return files
}
func (s *state) reserve(free int64) (bool, string) {
	if s.Active != nil {
		return false, ""
	}
	left := min(s.Settings.BatchGB*GB, free-s.Settings.ReserveGB*GB)
	ids := make([]string, 0)
	pending := false
	for _, v := range s.Files {
		if v.Stage != queued {
			continue
		}
		pending = true
		if v.Size <= left {
			ids = append(ids, v.ID)
			left -= v.Size
		}
	}
	if len(ids) == 0 {
		if pending {
			return false, "묶음 크기 또는 픽셀의 여유 공간을 확인해 주세요."
		}
		return false, ""
	}
	s.Sequence++
	s.Active = &batch{ID: s.Sequence, IDs: ids}
	return true, ""
}
func (s *state) flags() (ready, allClean bool) {
	if s.Active == nil {
		return false, false
	}
	ready, allClean = true, true
	for _, v := range s.activeFiles() {
		ready = ready && v.Stage >= verified
		allClean = allClean && v.Stage == cleaned
	}
	return
}
func (s *state) apply(r report) bool {
	if s.Active == nil || r.Batch != s.Active.ID {
		return false
	}
	changed := false
	for _, v := range s.activeFiles() {
		next := r.Files[v.ID]
		if next <= v.Stage || next > cleaned || (next == cleaned && (!s.Active.Cleanup || v.Stage < verified)) {
			continue
		}
		v.Stage = next
		if next == verified {
			v.VerifiedAt = time.Now().Unix()
		}
		changed = true
	}
	ready, allClean := s.flags()
	if allClean {
		s.Active = nil
		return true
	}
	if ready && s.Settings.AutoClean && !s.Active.Cleanup {
		s.Active.Cleanup = true
		return true
	}
	return changed
}
func (s *state) progress(r report) progress {
	p := progress{BatchNumber: s.Sequence, Active: s.Active != nil}
	for _, v := range s.Files {
		p.TotalCount++
		p.TotalBytes += v.Size
		if v.Stage >= verified {
			p.VerifiedCount++
			p.VerifiedBytes += v.Size
			p.LastVerified = max(p.LastVerified, v.VerifiedAt)
		}
	}
	if s.Active == nil {
		return p
	}
	p.Ready, _ = s.flags()
	p.Cleaning = s.Active.Cleanup
	for _, v := range s.activeFiles() {
		p.BatchCount++
		p.BatchBytes += v.Size
		if v.Stage >= copied {
			p.BatchCopied++
			p.BatchReceived += v.Size
		} else if r.Batch == s.Active.ID && r.Current == v.ID {
			p.BatchReceived += max(0, min(v.Size, r.Bytes))
			p.Current = v.Path
		}
		if v.Stage >= verified {
			p.BatchVerified++
		}
	}
	return p
}
func validSettings(c settings) bool {
	return c.BatchGB >= 1 && c.BatchGB <= 1000 && c.ReserveGB >= 0 && c.ReserveGB <= 1000 &&
		c.Interval >= 5 && c.Interval <= 120 && (c.Transport == "wifi" || c.Transport == "usb") &&
		strings.Contains(c.Account, "@") && !strings.ContainsAny(c.Account, " \r\n\t")
}
