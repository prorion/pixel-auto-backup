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

var ink = walk.RGB(24, 36, 59)
var muted = walk.RGB(74, 92, 120)
var blue = walk.RGB(35, 87, 198)
var green = walk.RGB(0, 126, 103)
var paper = walk.RGB(241, 245, 250)
var white = walk.RGB(255, 255, 255)

func hideConsole(cmd *exec.Cmd) {
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: 0x08000000}
}
func fatalWindow(err error) { walk.MsgBox(nil, appTitle, err.Error(), walk.MsgBoxIconError) }

type windowStatus struct {
	progress       progress
	phone          report
	last           time.Time
	stale, running bool
	skipped        []string
	skippedCount   int
}

func (c *controller) windowStatus() windowStatus {
	c.mu.Lock()
	defer c.mu.Unlock()
	return windowStatus{progress: c.s.progress(c.report), phone: c.report, last: c.last, running: c.s.Running,
		stale:   c.last.IsZero() || time.Since(c.last) > time.Duration(c.s.Settings.Interval*4)*time.Second,
		skipped: append([]string(nil), c.s.Skipped[:min(30, len(c.s.Skipped))]...), skippedCount: len(c.s.Skipped)}
}

func gb(value int64) string { return fmt.Sprintf("%.2f GB", float64(value)/float64(GB)) }
func percent(value, total int64) int {
	if total <= 0 {
		return 0
	}
	return min(100, max(0, int(float64(value)/float64(total)*100)))
}
func font(size int, bold bool) d.Font { return d.Font{Family: "Segoe UI", PointSize: size, Bold: bold} }
func pad(n int) d.Margins             { return d.Margins{Left: n, Top: n, Right: n, Bottom: n} }
func label(text string, size int, bold bool, color walk.Color) d.Label {
	return d.Label{Text: text, Font: font(size, bold), TextColor: color, NoPrefix: true, EllipsisMode: d.EllipsisEnd}
}
func card(items ...d.Widget) d.Composite {
	return d.Composite{Background: d.SolidColorBrush{Color: white}, StretchFactor: 1,
		Layout: d.VBox{Margins: pad(18), Spacing: 9}, Children: items}
}
func row(items ...d.Widget) d.Composite {
	return d.Composite{Layout: d.HBox{MarginsZero: true, Spacing: 9}, Children: items}
}
func field(name string, input d.Widget) d.Composite {
	return d.Composite{StretchFactor: 1, Layout: d.VBox{MarginsZero: true, Spacing: 4},
		Children: []d.Widget{label(name, 11, true, muted), input}}
}
func button(ref **walk.PushButton, text string, click func()) d.PushButton {
	return d.PushButton{AssignTo: ref, Text: text, Font: font(11, true), MinSize: d.Size{Height: 38}, OnClicked: click}
}

func runWindow(c *controller) error {
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	var window *walk.MainWindow
	var tabs *walk.TabWidget
	var folder, account, pairCode *walk.LineEdit
	var batchSize, reserve, interval *walk.NumberEdit
	var pcState, badge, step, transferLabel, verifiedLabel, progressNote, notice, addressLabel, connectHint *walk.Label
	var transferBar, verifiedBar *walk.CustomWidget
	var autoClean *walk.CheckBox
	var save, pair, add, pick, start, pause, approve, refreshIPs, skipped *walk.PushButton
	var mode, addr *walk.ComboBox
	var busy bool
	var last windowStatus
	var codeUntil time.Time
	barTrack, err := walk.NewSolidColorBrush(walk.RGB(222, 231, 244))
	if err != nil {
		return err
	}
	defer barTrack.Dispose()
	barBlue, err := walk.NewSolidColorBrush(blue)
	if err != nil {
		return err
	}
	defer barBlue.Dispose()
	barGreen, err := walk.NewSolidColorBrush(green)
	if err != nil {
		return err
	}
	defer barGreen.Dispose()
	var transferValue, verifyValue int
	makeBar := func(ref **walk.CustomWidget, value *int, brush *walk.SolidColorBrush) d.CustomWidget {
		return d.CustomWidget{AssignTo: ref, PaintMode: d.PaintBuffered, InvalidatesOnResize: true,
			MinSize: d.Size{Width: 140, Height: 9}, MaxSize: d.Size{Height: 9},
			PaintPixels: func(canvas *walk.Canvas, bounds walk.Rectangle) error {
				if *ref == nil {
					return nil
				}
				area := (*ref).ClientBoundsPixels()
				if err := canvas.FillRectanglePixels(barTrack, area); err != nil {
					return err
				}
				area.Width = area.Width * *value / 100
				if area.Width > 0 {
					return canvas.FillRectanglePixels(brush, area)
				}
				return nil
			}}
	}
	model := c.settings()
	ips := localIPs()
	ipIndex := -1
	if len(ips) > 0 {
		ipIndex = 0
	}
	modeIndex := 0
	if model.Transport == "usb" {
		modeIndex = 1
	}
	readSettings := func() settings {
		transport := "wifi"
		if mode.CurrentIndex() == 1 {
			transport = "usb"
		}
		return settings{Root: folder.Text(), Account: account.Text(), BatchGB: int64(batchSize.Value()),
			ReserveGB: int64(reserve.Value()), Interval: int(interval.Value()), AutoClean: autoClean.Checked(), Transport: transport}
	}
	setNote := func(text string) {
		notice.SetText(text)
		notice.SetToolTipText(text)
	}
	apply := func(current windowStatus) {
		last = current
		p := current.progress
		view := "픽셀 연결 대기"
		hint := "픽셀 연결을 먼저 완료하세요."
		if !current.last.IsZero() {
			view = "픽셀 연결됨"
			switch {
			case current.stale:
				view = "픽셀 응답 지연"
				hint = "픽셀에서 오토픽셀 연결 상태를 확인하세요."
			case !current.phone.Accessible:
				hint = "픽셀에서 오토픽셀 접근성 서비스를 켜 주세요."
			case p.Cleaning:
				hint = "픽셀 복사본을 정리하는 중"
			case p.Ready:
				hint = "이 묶음의 원본 백업을 모두 확인했어요."
			case p.Active && p.BatchCopied == p.BatchCount:
				hint = "파일 전송 완료 · 구글 포토 원본 백업 확인 중"
			case current.running && p.Active:
				hint = "픽셀로 영상을 보내는 중"
			case current.running:
				hint = "다음 전송 묶음을 준비하는 중"
			case p.TotalCount > 0 && p.VerifiedCount == p.TotalCount:
				hint = "전체 원본 백업 확인 완료"
			case p.TotalCount == 0:
				hint = "백업 설정에서 영상이 담긴 폴더를 선택하세요."
			default:
				hint = "연결이 끝났어요. 전송 시작을 누르면 영상을 보내요."
			}
		}
		badge.SetText(view)
		step.SetText(hint)
		if current.last.IsZero() {
			pcState.SetText("아직 연결된 픽셀이 없어요.")
		} else {
			screen := "화면 켜짐"
			if current.phone.Locked {
				screen = "화면 잠김"
			}
			access := "접근성 켜짐"
			if !current.phone.Accessible {
				access = "접근성 꺼짐"
			}
			pcState.SetText(access + "  ·  " + screen + "  ·  마지막 응답 " + current.last.Format("15:04:05"))
		}
		transferLabel.SetText(fmt.Sprintf("%d / %d개   ·   %s / %s", p.BatchCopied, p.BatchCount, gb(p.BatchReceived), gb(p.BatchBytes)))
		verifiedLabel.SetText(fmt.Sprintf("%d / %d개   ·   %s / %s", p.VerifiedCount, p.TotalCount, gb(p.VerifiedBytes), gb(p.TotalBytes)))
		transferValue = percent(p.BatchReceived, p.BatchBytes)
		verifyValue = percent(p.VerifiedBytes, p.TotalBytes)
		transferBar.Invalidate()
		verifiedBar.Invalidate()
		details := []string{}
		if p.Active {
			details = append(details, fmt.Sprintf("%d번 묶음 · 백업 확인 %d / %d개", p.BatchNumber, p.BatchVerified, p.BatchCount))
		}
		if p.LastVerified > 0 {
			details = append(details, "마지막 백업 확인 "+time.Unix(p.LastVerified, 0).Format("15:04:05"))
		}
		if current.phone.Message != "" {
			details = append(details, current.phone.Message)
		}
		message := strings.Join(details, "   ·   ")
		progressNote.SetText(message)
		progressNote.SetToolTipText(message)
		start.SetEnabled(!busy && !current.stale && current.phone.Accessible && !current.running &&
			(p.TotalCount == 0 || p.Active || p.VerifiedCount < p.TotalCount))
		pause.SetEnabled(!busy && current.running)
		approve.SetEnabled(!busy && p.Ready && !p.Cleaning)
		skipped.SetText(fmt.Sprintf("제외 파일 %d개 보기", current.skippedCount))
		skipped.SetEnabled(current.skippedCount > 0)
		if !codeUntil.IsZero() && time.Now().After(codeUntil) {
			pairCode.SetText("")
			connectHint.SetText("연결 코드가 만료됐어요. 새 코드를 만들어 주세요.")
			codeUntil = time.Time{}
		}
	}
	action := func(work func() error, success func()) {
		if busy {
			return
		}
		busy = true
		for _, b := range []*walk.PushButton{save, pair, add, pick, refreshIPs} {
			b.SetEnabled(false)
		}
		apply(last)
		go func() {
			err := work()
			state := c.windowStatus()
			window.Synchronize(func() {
				busy = false
				for _, b := range []*walk.PushButton{save, pair, add, pick} {
					b.SetEnabled(true)
				}
				refreshIPs.SetEnabled(mode.CurrentIndex() != 1)
				apply(state)
				if err != nil {
					setNote(err.Error())
					return
				}
				success()
			})
		}()
	}
	modeChanged := func() {
		wifi := mode.CurrentIndex() != 1
		addressLabel.SetEnabled(wifi)
		addr.SetEnabled(wifi)
		refreshIPs.SetEnabled(wifi && !busy)
		pairCode.SetText("")
		codeUntil = time.Time{}
		if wifi {
			connectHint.SetText("픽셀에서 PC 주소와 연결 코드를 입력하세요.")
		} else {
			connectHint.SetText("픽셀에서 USB 연결을 켜고 코드를 입력하세요.")
		}
	}
	startSend := func() {
		next := readSettings()
		if next.Root == "" || strings.TrimSpace(next.Account) == "" {
			tabs.SetCurrentIndex(1)
			setNote("원본 폴더와 백업 계정을 설정해 주세요.")
			return
		}
		action(func() error {
			if err := c.saveSettings(next); err != nil {
				return err
			}
			return c.setRunning(true)
		}, func() {
			setNote("전송을 시작했어요. 픽셀에서 구글 포토 열기 안내가 표시돼요.")
		})
	}
	pairPhone := func() {
		transport := readSettings().Transport
		ip := addr.Text()
		var accountURL, code string
		var expires time.Time
		action(func() error {
			var err error
			accountURL, err = c.pairAddress(ip, transport)
			if err != nil {
				return err
			}
			code, expires, err = c.issuePair()
			return err
		}, func() {
			hint := "픽셀에 입력할 PC 주소  " + accountURL
			if transport == "usb" {
				hint = "픽셀에서 USB 연결 선택"
			}
			pairCode.SetText(code)
			connectHint.SetText(hint + "  ·  " + expires.Format("15:04") + "까지")
			codeUntil = expires
			setNote("픽셀에서 연결 코드를 입력하면 이 창에 연결 상태가 표시돼요.")
		})
	}

	cfg := func(ref **walk.NumberEdit, value, minValue, maxValue float64) d.NumberEdit {
		return d.NumberEdit{AssignTo: ref, Font: font(12, false), Value: value, MinValue: minValue, MaxValue: maxValue, MinSize: d.Size{Width: 125, Height: 32}}
	}
	overview := d.TabPage{Title: "진행 및 연결", Background: d.SolidColorBrush{Color: paper},
		Layout: d.VBox{Margins: pad(16), Spacing: 12}, Children: []d.Widget{
			card(d.Label{AssignTo: &step, Font: font(16, true), TextColor: ink, NoPrefix: true, EllipsisMode: d.EllipsisEnd, Text: "픽셀 연결 대기"},
				row(button(&start, "▶  전송 시작", startSend),
					button(&pause, "일시 중지", func() {
						action(func() error { return c.setRunning(false) }, func() { setNote("전송을 일시 중지했어요.") })
					}),
					d.HSpacer{},
					button(&approve, "확인된 묶음 정리", func() {
						bundle := last.progress.BatchNumber
						action(func() error { return c.approve(bundle) }, func() { setNote("픽셀 복사본 정리를 승인했어요.") })
					}),
				)),
			row(
				card(label("PC  →  픽셀 전송", 12, true, muted),
					d.Label{AssignTo: &transferLabel, Text: "0 / 0개", Font: font(17, true), TextColor: ink, EllipsisMode: d.EllipsisEnd},
					makeBar(&transferBar, &transferValue, barBlue)),
				card(label("구글 포토 원본 백업", 12, true, muted),
					d.Label{AssignTo: &verifiedLabel, Text: "0 / 0개", Font: font(17, true), TextColor: ink, EllipsisMode: d.EllipsisEnd},
					makeBar(&verifiedBar, &verifyValue, barGreen)),
			),
			d.Label{AssignTo: &progressNote, Text: "  ", Font: font(11, false), TextColor: muted, NoPrefix: true, EllipsisMode: d.EllipsisEnd},
			card(label("픽셀 연결", 16, true, ink),
				d.Label{AssignTo: &pcState, Text: "픽셀 연결 대기", Font: font(11, false), TextColor: muted, EllipsisMode: d.EllipsisEnd},
				row(label("연결 방식", 11, true, ink),
					d.ComboBox{AssignTo: &mode, Font: font(12, false), Model: []string{"Wi-Fi", "USB"}, CurrentIndex: modeIndex, MinSize: d.Size{Width: 125, Height: 32}},
					d.Label{AssignTo: &addressLabel, Text: "PC 주소", Font: font(11, true), TextColor: ink},
					d.ComboBox{AssignTo: &addr, Font: font(12, false), Model: ips, CurrentIndex: ipIndex, MinSize: d.Size{Width: 155, Height: 32}},
					button(&refreshIPs, "주소 갱신", func() {
						next := localIPs()
						addr.SetModel(next)
						if len(next) > 0 {
							addr.SetCurrentIndex(0)
						}
					}),
				),
				row(button(&pair, "새 연결 코드 만들기", pairPhone),
					d.LineEdit{AssignTo: &pairCode, ReadOnly: true, CueBanner: "6자 연결 코드", Font: d.Font{Family: "Consolas", PointSize: 18, Bold: true}, MinSize: d.Size{Height: 38}}),
				d.Label{AssignTo: &connectHint, Text: "  ", Font: font(11, false), TextColor: muted, EllipsisMode: d.EllipsisEnd}),
			d.VSpacer{},
		}}
	settingsPage := d.TabPage{Title: "백업 설정", Background: d.SolidColorBrush{Color: paper},
		Layout: d.VBox{Margins: pad(16), Spacing: 12}, Children: []d.Widget{
			card(label("원본과 백업 계정", 16, true, ink),
				label("PC 원본 폴더", 11, true, muted),
				row(d.LineEdit{AssignTo: &folder, Text: model.Root, ReadOnly: true, CueBanner: "영상이 있는 폴더를 선택하세요.", Font: font(12, false), MinSize: d.Size{Height: 37}},
					button(&pick, "폴더 선택…", func() {
						dlg := walk.FileDialog{Title: "홈캠 영상 폴더 선택"}
						ok, err := dlg.ShowBrowseFolder(window)
						if err != nil {
							setNote(err.Error())
							return
						}
						if ok {
							folder.SetText(dlg.FilePath)
						}
					})),
				field("구글 포토 계정", d.LineEdit{AssignTo: &account, Text: model.Account, CueBanner: "픽셀에서 백업에 사용할 이메일", Font: font(12, false), MinSize: d.Size{Height: 33}})),
			card(label("전송과 정리", 16, true, ink),
				row(field("묶음 크기 (GB)", cfg(&batchSize, float64(model.BatchGB), 1, 1000)),
					field("남길 공간 (GB)", cfg(&reserve, float64(model.ReserveGB), 0, 1000)),
					field("상태 갱신 (초)", cfg(&interval, float64(model.Interval), 5, 120))),
				d.CheckBox{AssignTo: &autoClean, Text: "백업이 확인된 묶음 자동 정리", Font: font(12, false), Checked: model.AutoClean},
				label("새로 받는 영상은 픽셀의 DCIM / Camera에 저장돼요.", 11, false, muted)),
			row(button(&save, "설정 저장", func() {
				next := readSettings()
				action(func() error { return c.saveSettings(next) }, func() { setNote("설정을 저장했어요."); tabs.SetCurrentIndex(0) })
			}),
				button(&add, "새 파일 검색", func() { action(c.addMore, func() { setNote("원본 폴더를 다시 확인했어요.") }) }),
				d.HSpacer{},
				button(&skipped, "제외 파일 보기", func() {
					lines := strings.Join(last.skipped, "\r\n")
					if last.skippedCount > len(last.skipped) {
						lines += "\r\n(최대 30개 표시)"
					}
					walk.MsgBox(window, "제외 파일", lines, walk.MsgBoxIconInformation)
				})),
			d.VSpacer{},
		}}
	controls := d.MainWindow{AssignTo: &window, Title: appTitle, Font: font(12, false),
		Background: d.SolidColorBrush{Color: paper},
		Size:       d.Size{Width: 1000, Height: 770}, MinSize: d.Size{Width: 740, Height: 660},
		Layout: d.VBox{Margins: pad(16), Spacing: 10},
		Children: []d.Widget{
			d.Composite{Background: d.SolidColorBrush{Color: ink}, Layout: d.HBox{Margins: pad(16), Spacing: 12},
				Children: []d.Widget{
					label(appTitle, 25, true, white), d.HSpacer{},
					d.Label{AssignTo: &badge, Text: "픽셀 연결 대기", Font: font(13, true), TextColor: walk.RGB(147, 227, 211), NoPrefix: true, EllipsisMode: d.EllipsisEnd},
				}},
			d.TabWidget{AssignTo: &tabs, Font: font(12, true), Pages: []d.TabPage{overview, settingsPage}},
			d.Label{AssignTo: &notice, Text: " ", Font: font(11, false), TextColor: blue, NoPrefix: true, EllipsisMode: d.EllipsisEnd},
		}}
	if err := controls.Create(); err != nil {
		return err
	}
	mode.CurrentIndexChanged().Attach(modeChanged)
	modeChanged()
	apply(c.windowStatus())
	if model.Root == "" {
		setNote("백업 설정에서 원본 폴더와 계정을 선택하세요.")
	}
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
				current := c.windowStatus()
				window.Synchronize(func() { apply(current) })
			}
		}
	}()
	window.Run()
	return nil
}
