package main

import (
	"fmt"
	"os/exec"
	"runtime"
	"strings"
	"syscall"
	"time"

	"github.com/lxn/walk"
	d "github.com/lxn/walk/declarative"
)

const appTitle = "오토픽셀"

func hideConsole(cmd *exec.Cmd) {
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: 0x08000000}
}

func fatalWindow(err error) {
	walk.MsgBox(nil, appTitle, err.Error(), walk.MsgBoxIconError)
}

type windowStatus struct {
	progress     progress
	phone        report
	last         time.Time
	stale        bool
	skipped      []string
	skippedCount int
}

func (c *controller) windowStatus() windowStatus {
	c.mu.Lock()
	defer c.mu.Unlock()
	p := c.s.progress(c.report)
	return windowStatus{
		progress: p, phone: c.report, last: c.last,
		stale:        c.last.IsZero() || time.Since(c.last) > time.Duration(c.s.Settings.Interval*4)*time.Second,
		skipped:      append([]string(nil), c.s.Skipped[:min(30, len(c.s.Skipped))]...),
		skippedCount: len(c.s.Skipped),
	}
}

func gb(value int64) string { return fmt.Sprintf("%.2f GB", float64(value)/float64(GB)) }

func percent(value, total int64) int {
	if total <= 0 {
		return 0
	}
	return min(100, max(0, int(float64(value)/float64(total)*100)))
}

func runWindow(c *controller) error {
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	config := c.settings()
	ips := localIPs()
	ipIndex := -1
	if len(ips) > 0 {
		ipIndex = 0
	}
	modeIndex := 0
	if config.Transport == "usb" {
		modeIndex = 1
	}
	var window *walk.MainWindow
	var folder, account, pairCode *walk.LineEdit
	var batchSize, reserve, interval *walk.NumberEdit
	var phone, transferLabel, verifiedLabel, detail, notice, addressLabel, pairAddress, skippedLabel *walk.Label
	var watchedTransfer, watchedVerified *walk.ProgressBar
	var autoClean *walk.CheckBox
	var save, pair, add, pick, approve, refreshIPs, skipped *walk.PushButton
	var mode, addr *walk.ComboBox
	var busy bool
	var last windowStatus
	var codeUntil time.Time

	readSettings := func() settings {
		connection := "wifi"
		if mode.CurrentIndex() == 1 {
			connection = "usb"
		}
		return settings{Root: folder.Text(), Account: account.Text(), BatchGB: int64(batchSize.Value()),
			ReserveGB: int64(reserve.Value()), Interval: int(interval.Value()), Transport: connection, AutoClean: autoClean.Checked()}
	}
	apply := func(view windowStatus) {
		last = view
		p := view.progress
		if view.last.IsZero() {
			phone.SetText("픽셀 연결 대기 중")
		} else {
			connection := "픽셀 연결됨"
			if view.stale {
				connection = "픽셀 상태 수신 지연"
			}
			screen := "화면 켜짐"
			if view.phone.Locked {
				screen = "화면 잠김 또는 꺼짐"
			}
			power := "충전기 연결 없음"
			if view.phone.Charging {
				power = "충전 중"
			}
			phone.SetText(strings.Join([]string{connection, screen, power, "마지막 수신 " + view.last.Format("15:04:05")}, "  ·  "))
		}
		if p.Active {
			transferLabel.SetText(fmt.Sprintf("%d번째 묶음 전송  %s / %s  ·  %d / %d개", p.BatchNumber, gb(p.BatchReceived), gb(p.BatchBytes), p.BatchCopied, p.BatchCount))
		} else {
			transferLabel.SetText("현재 묶음 없음")
		}
		watchedTransfer.SetValue(percent(p.BatchReceived, p.BatchBytes))
		verifiedLabel.SetText(fmt.Sprintf("전체 원본 백업 확인  %s / %s  ·  %d / %d개", gb(p.VerifiedBytes), gb(p.TotalBytes), p.VerifiedCount, p.TotalCount))
		watchedVerified.SetValue(percent(p.VerifiedBytes, p.TotalBytes))
		parts := []string{}
		if p.Active {
			parts = append(parts, fmt.Sprintf("묶음 백업 확인 %d / %d개", p.BatchVerified, p.BatchCount))
		}
		if p.Cleaning {
			parts = append(parts, "픽셀 복사본 정리 중")
		}
		if p.LastVerified > 0 {
			parts = append(parts, "마지막 확인 "+time.Unix(p.LastVerified, 0).Format("15:04"))
		}
		if view.phone.Message != "" {
			parts = append(parts, view.phone.Message)
		}
		summary := strings.Join(parts, "  ·  ")
		detail.SetText(summary)
		detail.SetToolTipText(summary)
		approve.SetEnabled(!busy && p.Ready && !p.Cleaning)
		skipped.SetEnabled(view.skippedCount > 0)
		skippedLabel.SetText(fmt.Sprintf("제외 파일 %d개", view.skippedCount))
		if !codeUntil.IsZero() && time.Now().After(codeUntil) {
			pairCode.SetText("")
			codeUntil = time.Time{}
		}
	}
	setBusy := func(value bool) {
		busy = value
		for _, button := range []*walk.PushButton{save, pair, add, pick} {
			button.SetEnabled(!value)
		}
		refreshIPs.SetEnabled(!value && mode.CurrentIndex() != 1)
		approve.SetEnabled(!value && last.progress.Ready && !last.progress.Cleaning)
	}
	action := func(work func() error, success func()) {
		if busy {
			return
		}
		setBusy(true)
		go func() {
			err := work()
			view := c.windowStatus()
			window.Synchronize(func() {
				setBusy(false)
				apply(view)
				if err != nil {
					notice.SetText(err.Error())
					return
				}
				success()
			})
		}()
	}
	modeChanged := func() {
		useWiFi := mode.CurrentIndex() != 1
		addressLabel.SetEnabled(useWiFi)
		addr.SetEnabled(useWiFi)
		refreshIPs.SetEnabled(useWiFi)
		if useWiFi {
			pairAddress.SetText("픽셀에서 PC 주소와 연결 코드를 입력하세요.")
		} else {
			pairAddress.SetText("픽셀에서 USB 모드를 고르고 연결 코드를 입력하세요.")
		}
		pairCode.SetText("")
		codeUntil = time.Time{}
	}
	controls := d.MainWindow{
		AssignTo: &window, Title: appTitle,
		Size: d.Size{Width: 760, Height: 680}, MinSize: d.Size{Width: 650, Height: 630},
		Layout: d.VBox{Spacing: 10, Margins: d.Margins{Left: 16, Top: 16, Right: 16, Bottom: 16}},
		Children: []d.Widget{
			d.GroupBox{Title: "백업 진행", Layout: d.VBox{Spacing: 6}, Children: []d.Widget{
				d.Label{AssignTo: &phone, Text: "픽셀 연결 대기 중"},
				d.Label{AssignTo: &transferLabel, Text: "현재 묶음 없음"},
				d.ProgressBar{AssignTo: &watchedTransfer, MinValue: 0, MaxValue: 100},
				d.Label{AssignTo: &verifiedLabel, Text: "전체 원본 백업 확인"},
				d.ProgressBar{AssignTo: &watchedVerified, MinValue: 0, MaxValue: 100},
				d.Label{AssignTo: &detail, Text: " ", EllipsisMode: d.EllipsisEnd},
				d.Composite{Layout: d.HBox{MarginsZero: true}, Children: []d.Widget{
					d.PushButton{AssignTo: &approve, Text: "확인된 묶음 정리", Enabled: false, OnClicked: func() {
						number := last.progress.BatchNumber
						action(func() error { return c.approve(number) }, func() { notice.SetText("픽셀 복사본 정리를 승인했어요.") })
					}},
					d.HSpacer{},
					d.Label{AssignTo: &skippedLabel, Text: "제외 파일 0개"},
					d.PushButton{AssignTo: &skipped, Text: "목록 보기", Enabled: false, OnClicked: func() {
						text := strings.Join(last.skipped, "\r\n")
						if last.skippedCount > len(last.skipped) {
							text += "\r\n(최대 30개 표시)"
						}
						walk.MsgBox(window, "제외 파일", text, walk.MsgBoxIconInformation)
					}},
				}},
			}},
			d.GroupBox{Title: "백업 설정", Layout: d.VBox{Spacing: 6}, Children: []d.Widget{
				d.Composite{Layout: d.HBox{MarginsZero: true}, Children: []d.Widget{
					d.Label{Text: "원본 폴더"},
					d.LineEdit{AssignTo: &folder, Text: config.Root, ReadOnly: true, CueBanner: "폴더를 선택하세요."},
					d.PushButton{AssignTo: &pick, Text: "폴더 선택…", OnClicked: func() {
						dialog := walk.FileDialog{Title: "홈캠 영상 폴더 선택"}
						ok, err := dialog.ShowBrowseFolder(window)
						if err != nil {
							notice.SetText(err.Error())
							return
						}
						if ok {
							folder.SetText(dialog.FilePath)
						}
					}},
				}},
				d.Composite{Layout: d.HBox{MarginsZero: true}, Children: []d.Widget{
					d.Label{Text: "구글 포토 계정"},
					d.LineEdit{AssignTo: &account, Text: config.Account, CueBanner: "백업에 사용할 이메일 주소"},
				}},
				d.Composite{Layout: d.HBox{MarginsZero: true}, Children: []d.Widget{
					d.Label{Text: "묶음 크기 (GB)"},
					d.NumberEdit{AssignTo: &batchSize, Value: float64(config.BatchGB), MinValue: 1, MaxValue: 1000},
					d.Label{Text: "남길 공간 (GB)"},
					d.NumberEdit{AssignTo: &reserve, Value: float64(config.ReserveGB), MinValue: 0, MaxValue: 1000},
					d.Label{Text: "갱신 간격 (초)"},
					d.NumberEdit{AssignTo: &interval, Value: float64(config.Interval), MinValue: 5, MaxValue: 120},
				}},
				d.CheckBox{AssignTo: &autoClean, Text: "백업이 확인된 묶음 자동 정리", Checked: config.AutoClean},
				d.Composite{Layout: d.HBox{MarginsZero: true}, Children: []d.Widget{
					d.PushButton{AssignTo: &save, Text: "설정 저장", OnClicked: func() {
						next := readSettings()
						action(func() error { return c.saveSettings(next) }, func() { notice.SetText("설정을 저장했어요.") })
					}},
					d.PushButton{AssignTo: &add, Text: "새 파일 검색", OnClicked: func() {
						action(c.addMore, func() { notice.SetText("원본 폴더를 다시 확인했어요.") })
					}},
					d.HSpacer{},
				}},
			}},
			d.GroupBox{Title: "픽셀 연결", Layout: d.VBox{Spacing: 6}, Children: []d.Widget{
				d.Composite{Layout: d.HBox{MarginsZero: true}, Children: []d.Widget{
					d.Label{Text: "연결 방식"},
					d.ComboBox{AssignTo: &mode, Model: []string{"Wi-Fi", "USB"}, CurrentIndex: modeIndex},
					d.Label{AssignTo: &addressLabel, Text: "PC 주소"},
					d.ComboBox{AssignTo: &addr, Model: ips, CurrentIndex: ipIndex},
					d.PushButton{AssignTo: &refreshIPs, Text: "주소 갱신", OnClicked: func() {
						next := localIPs()
						addr.SetModel(next)
						if len(next) > 0 {
							addr.SetCurrentIndex(0)
						}
					}},
				}},
				d.Composite{Layout: d.HBox{MarginsZero: true}, Children: []d.Widget{
					d.PushButton{AssignTo: &pair, Text: "새 연결 코드 만들기", OnClicked: func() {
						next := readSettings()
						ip := addr.Text()
						var code, address string
						var expires time.Time
						action(func() error {
							var err error
							address, err = c.pairAddress(ip, next.Transport)
							if err != nil {
								return err
							}
							if err = c.saveSettings(next); err != nil {
								return err
							}
							code, expires, err = c.issuePair()
							return err
						}, func() {
							if next.Transport == "usb" {
								address = "USB"
							}
							pairAddress.SetText("픽셀에서 입력할 연결 주소  " + address + "  ·  " + expires.Format("15:04") + "까지")
							pairCode.SetText(code)
							codeUntil = expires
							notice.SetText("픽셀의 오토픽셀 앱에 연결 코드를 입력하세요.")
						})
					}},
					d.LineEdit{AssignTo: &pairCode, ReadOnly: true, CueBanner: "12자 연결 코드", Font: d.Font{Family: "Consolas", PointSize: 15, Bold: true}},
				}},
				d.Label{AssignTo: &pairAddress, Text: " "},
			}},
			d.Label{AssignTo: &notice, Text: " ", EllipsisMode: d.EllipsisEnd},
			d.VSpacer{},
		},
	}
	if err := controls.Create(); err != nil {
		return err
	}
	mode.CurrentIndexChanged().Attach(modeChanged)
	modeChanged()
	apply(c.windowStatus())
	done := make(chan struct{})
	defer close(done)
	go func() {
		tick := time.NewTicker(time.Second)
		defer tick.Stop()
		for {
			select {
			case <-done:
				return
			case <-tick.C:
				view := c.windowStatus()
				window.Synchronize(func() { apply(view) })
			}
		}
	}()
	window.Run()
	return nil
}
