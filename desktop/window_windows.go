package main

import (
	"bytes"
	_ "embed"
	"fmt"
	"image/png"
	"os/exec"
	"runtime"
	"strings"
	"syscall"
	"time"

	"github.com/lxn/walk"
	d "github.com/lxn/walk/declarative"
)

const appTitle = "오토픽셀"

//go:embed icon.png
var iconPNG []byte

var ink = walk.RGB(20, 33, 61)
var muted = walk.RGB(91, 107, 132)
var faint = walk.RGB(152, 162, 179)
var navy = walk.RGB(27, 45, 87)
var blue = walk.RGB(59, 130, 246)
var green = walk.RGB(18, 184, 134)
var amber = walk.RGB(217, 138, 0)
var paper = walk.RGB(244, 246, 250)
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
	paired         bool
	transport      string
	skipped        []string
	skippedCount   int
}

func (c *controller) windowStatus() windowStatus {
	c.mu.Lock()
	defer c.mu.Unlock()
	return windowStatus{progress: c.s.progress(c.report), phone: c.report, last: c.last, running: c.s.Running,
		stale:  c.last.IsZero() || time.Since(c.last) > time.Duration(c.s.Settings.Interval*4)*time.Second,
		paired: c.s.Device != "", transport: c.s.Settings.Transport,
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
		Layout: d.VBox{Margins: d.Margins{Left: 20, Top: 16, Right: 20, Bottom: 16}, Spacing: 8}, Children: items}
}
func row(items ...d.Widget) d.Composite {
	return d.Composite{Layout: d.HBox{MarginsZero: true, Spacing: 8}, Children: items}
}
func button(ref **walk.PushButton, text string, click func()) d.PushButton {
	return d.PushButton{AssignTo: ref, Text: text, Font: font(10, true), MinSize: d.Size{Width: 96, Height: 34}, OnClicked: click}
}

// stateText is the one-line answer to "what is happening / what should I do next?".
func stateText(current windowStatus) (chip string, chipColor walk.Color, step string) {
	p := current.progress
	switch {
	case !current.paired && current.last.IsZero():
		return "픽셀 연결 필요", faint, "아래에서 연결 코드를 만들어 픽셀과 연결하세요."
	case current.last.IsZero():
		return "픽셀 연결 대기", faint, "픽셀에서 오토픽셀을 열어 주세요."
	case current.stale:
		return "픽셀 응답 없음", amber, "픽셀에서 오토픽셀 연결 상태를 확인하세요."
	case !current.phone.Accessible:
		return "접근성 꺼짐", amber, "픽셀에서 오토픽셀 접근성 서비스를 켜 주세요."
	case p.Cleaning:
		return "정리 중", green, "백업이 확인된 픽셀 복사본을 정리하고 있어요."
	case p.Ready:
		return "확인 완료", green, "이 묶음의 원본 백업을 모두 확인했어요."
	case p.Active && p.BatchCopied == p.BatchCount:
		return "백업 확인 중", green, "전송 완료 · 픽셀에서 구글 포토 원본 백업을 확인하는 중"
	case current.running && p.Active:
		return "전송 중", green, "픽셀로 영상을 보내는 중"
	case current.running:
		return "준비 중", green, "다음 전송 묶음을 준비하는 중"
	case p.TotalCount > 0 && p.VerifiedCount == p.TotalCount:
		return "모두 완료", green, "모든 영상의 원본 백업을 확인했어요."
	case p.TotalCount == 0:
		return "픽셀 연결됨", green, "설정에서 영상이 담긴 폴더를 선택하세요."
	default:
		return "픽셀 연결됨", green, "전송 시작을 누르면 영상을 보내요."
	}
}

func runWindow(c *controller) error {
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	var window *walk.MainWindow
	var chip *walk.Composite
	var chipLabel, step, detail, transferCount, transferBytes, verifiedCount, verifiedBytes *walk.Label
	var phoneLine, connectHint, notice *walk.Label
	var transferBar, verifiedBar *walk.CustomWidget
	var pairPanel *walk.Composite
	var pairCode *walk.LineEdit
	var toggle, approve, settingsButton, linkButton, pair, refreshIPs *walk.PushButton
	var mode, addr *walk.ComboBox
	var addressLabel *walk.Label
	var busy, pairOpen bool
	var last windowStatus
	var codeUntil time.Time

	brushes := map[walk.Color]*walk.SolidColorBrush{}
	brush := func(color walk.Color) *walk.SolidColorBrush {
		if b, ok := brushes[color]; ok {
			return b
		}
		b, err := walk.NewSolidColorBrush(color)
		if err != nil {
			return nil
		}
		brushes[color] = b
		return b
	}
	defer func() {
		for _, b := range brushes {
			b.Dispose()
		}
	}()
	tint := func(color walk.Color) walk.Color { // the colour at ~14% over white
		r, g, b := int(color.R()), int(color.G()), int(color.B())
		mix := func(v int) byte { return byte(255 - (255-v)*14/100) }
		return walk.RGB(mix(r), mix(g), mix(b))
	}

	var transferValue, verifyValue int
	makeBar := func(ref **walk.CustomWidget, value *int, color walk.Color) d.CustomWidget {
		return d.CustomWidget{AssignTo: ref, PaintMode: d.PaintBuffered, InvalidatesOnResize: true,
			MinSize: d.Size{Width: 140, Height: 8}, MaxSize: d.Size{Height: 8},
			PaintPixels: func(canvas *walk.Canvas, bounds walk.Rectangle) error {
				if *ref == nil {
					return nil
				}
				area := (*ref).ClientBoundsPixels()
				if err := canvas.FillRectanglePixels(brush(walk.RGB(230, 235, 242)), area); err != nil {
					return err
				}
				area.Width = area.Width * *value / 100
				if area.Width > 0 {
					return canvas.FillRectanglePixels(brush(color), area)
				}
				return nil
			}}
	}

	var icon *walk.Icon
	var logo *walk.Bitmap
	if img, err := png.Decode(bytes.NewReader(iconPNG)); err == nil {
		icon, _ = walk.NewIconFromImageForDPI(img, 96)
		logo, _ = walk.NewBitmapFromImageForDPI(img, 96)
	}
	if logo != nil {
		defer logo.Dispose()
	}

	ips := localIPs()
	ipIndex := -1
	if len(ips) > 0 {
		ipIndex = 0
	}
	modeIndex := 0
	if c.settings().Transport == "usb" {
		modeIndex = 1
	}
	transport := func() string {
		if mode.CurrentIndex() == 1 {
			return "usb"
		}
		return "wifi"
	}
	setNote := func(text string) {
		notice.SetText(text)
		notice.SetToolTipText(text)
	}

	apply := func(current windowStatus) {
		last = current
		p := current.progress
		chipText, chipColor, hint := stateText(current)
		chipLabel.SetText("●  " + chipText)
		chipLabel.SetTextColor(chipColor)
		chip.SetBackground(brush(tint(chipColor)))
		chipLabel.SetBackground(brush(tint(chipColor)))
		step.SetText(hint)

		details := []string{}
		if p.Active {
			details = append(details, fmt.Sprintf("%d번 묶음", p.BatchNumber))
		}
		if p.LastVerified > 0 {
			details = append(details, "마지막 확인 "+time.Unix(p.LastVerified, 0).Format("15:04"))
		}
		if current.phone.Message != "" && !current.stale {
			details = append(details, "픽셀: "+current.phone.Message)
		}
		message := strings.Join(details, "  ·  ")
		detail.SetText(message)
		detail.SetToolTipText(message)

		transferCount.SetText(fmt.Sprintf("%d / %d개", p.BatchCopied, p.BatchCount))
		transferBytes.SetText(gb(p.BatchReceived) + " / " + gb(p.BatchBytes) + " · 이번 묶음")
		verifiedCount.SetText(fmt.Sprintf("%d / %d개", p.VerifiedCount, p.TotalCount))
		verifiedBytes.SetText(gb(p.VerifiedBytes) + " / " + gb(p.TotalBytes) + " · 전체")
		transferValue = percent(p.BatchReceived, p.BatchBytes)
		verifyValue = percent(p.VerifiedBytes, p.TotalBytes)
		transferBar.Invalidate()
		verifiedBar.Invalidate()

		if current.running {
			toggle.SetText("❚❚  일시 중지")
			toggle.SetEnabled(!busy)
		} else {
			toggle.SetText("▶  전송 시작")
			toggle.SetEnabled(!busy && !current.stale && current.phone.Accessible &&
				(p.TotalCount == 0 || p.Active || p.VerifiedCount < p.TotalCount))
		}
		approve.SetVisible(p.Ready && !p.Cleaning)
		approve.SetEnabled(!busy)

		if current.last.IsZero() {
			phoneLine.SetText("연결된 픽셀이 없어요.")
		} else {
			how := "Wi-Fi"
			if current.transport == "usb" {
				how = "USB"
			}
			screen := "화면 켜짐"
			if current.phone.Locked {
				screen = "화면 잠김"
			}
			phoneLine.SetText(how + "  ·  " + screen + "  ·  마지막 응답 " + current.last.Format("15:04:05"))
		}
		open := pairOpen || (!current.paired && current.last.IsZero())
		pairPanel.SetVisible(open)
		if open {
			linkButton.SetText("닫기")
		} else {
			linkButton.SetText("연결 설정")
		}
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
		for _, b := range []*walk.PushButton{pair, settingsButton, refreshIPs} {
			b.SetEnabled(false)
		}
		apply(last)
		go func() {
			err := work()
			state := c.windowStatus()
			window.Synchronize(func() {
				busy = false
				for _, b := range []*walk.PushButton{pair, settingsButton} {
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

	var openSettings func()
	openSettings = func() {
		model := c.settings()
		var dlg *walk.Dialog
		var folder, account *walk.LineEdit
		var batchSize, reserve *walk.NumberEdit
		var autoClean *walk.CheckBox
		var ok, cancel, pick, scan, skipped *walk.PushButton
		number := func(ref **walk.NumberEdit, value, minValue, maxValue float64) d.NumberEdit {
			return d.NumberEdit{AssignTo: ref, Font: font(11, false), Value: value, MinValue: minValue, MaxValue: maxValue,
				Suffix: " GB", MinSize: d.Size{Width: 120, Height: 30}}
		}
		field := func(name string, input d.Widget) d.Composite {
			return d.Composite{Layout: d.VBox{MarginsZero: true, Spacing: 4}, Children: []d.Widget{label(name, 10, true, muted), input}}
		}
		form := d.Dialog{AssignTo: &dlg, Title: "설정", Icon: icon, Font: font(11, false), DefaultButton: &ok, CancelButton: &cancel,
			Background: d.SolidColorBrush{Color: paper}, MinSize: d.Size{Width: 580, Height: 470}, Size: d.Size{Width: 580, Height: 470},
			Layout: d.VBox{Margins: pad(16), Spacing: 12},
			Children: []d.Widget{
				card(
					field("PC 원본 폴더", row(
						d.LineEdit{AssignTo: &folder, Text: model.Root, ReadOnly: true, CueBanner: "영상이 있는 폴더를 선택하세요.", MinSize: d.Size{Height: 30}},
						button(&pick, "선택…", func() {
							browse := walk.FileDialog{Title: "영상 폴더 선택"}
							if chosen, err := browse.ShowBrowseFolder(dlg); err == nil && chosen {
								folder.SetText(browse.FilePath)
							}
						}))),
					field("구글 포토 백업 계정", d.LineEdit{AssignTo: &account, Text: model.Account, CueBanner: "픽셀 구글 포토의 백업 계정 이메일", MinSize: d.Size{Height: 30}}),
				),
				card(
					row(field("한 번에 보낼 양", number(&batchSize, float64(model.BatchGB), 1, 1000)),
						field("픽셀에 남길 공간", number(&reserve, float64(model.ReserveGB), 0, 1000)), d.HSpacer{}),
					row(d.CheckBox{AssignTo: &autoClean, Text: "백업이 확인되면 픽셀 복사본 자동 정리", Checked: model.AutoClean}, d.HSpacer{}),
				),
				row(
					button(&scan, "새 파일 검색", func() {
						dlg.Accept()
						action(c.addMore, func() { setNote("원본 폴더를 다시 확인했어요.") })
					}),
					button(&skipped, fmt.Sprintf("제외 파일 %d개", last.skippedCount), func() {
						lines := strings.Join(last.skipped, "\r\n")
						if last.skippedCount > len(last.skipped) {
							lines += "\r\n(최대 30개 표시)"
						}
						walk.MsgBox(dlg, "제외 파일", lines, walk.MsgBoxIconInformation)
					}),
					d.HSpacer{},
					button(&ok, "저장", func() {
						next := settings{Root: folder.Text(), Account: account.Text(), BatchGB: int64(batchSize.Value()),
							ReserveGB: int64(reserve.Value()), Interval: model.Interval, AutoClean: autoClean.Checked(), Transport: model.Transport}
						dlg.Accept()
						action(func() error { return c.saveSettings(next) }, func() { setNote("설정을 저장했어요.") })
					}),
					button(&cancel, "취소", func() { dlg.Cancel() }),
				),
			}}
		if err := form.Create(window); err != nil {
			setNote(err.Error())
			return
		}
		skipped.SetEnabled(last.skippedCount > 0)
		dlg.Run()
	}

	toggleSend := func() {
		if last.running {
			action(func() error { return c.setRunning(false) }, func() { setNote("전송을 일시 중지했어요.") })
			return
		}
		cfg := c.settings()
		if cfg.Root == "" || strings.TrimSpace(cfg.Account) == "" {
			setNote("원본 폴더와 백업 계정을 먼저 설정해 주세요.")
			openSettings()
			return
		}
		action(func() error { return c.setRunning(true) }, func() {
			setNote("전송을 시작했어요. 픽셀에 구글 포토 열기 안내가 표시돼요.")
		})
	}
	modeChanged := func() {
		wifi := mode.CurrentIndex() != 1
		addressLabel.SetVisible(wifi)
		addr.SetVisible(wifi)
		refreshIPs.SetVisible(wifi)
		refreshIPs.SetEnabled(!busy)
		pairCode.SetText("")
		codeUntil = time.Time{}
		if wifi {
			connectHint.SetText("픽셀에서 PC 주소와 연결 코드를 입력하세요.")
		} else {
			connectHint.SetText("USB 디버깅을 켠 픽셀에서 ‘USB 케이블로 연결’을 켜고 코드를 입력하세요.")
		}
	}
	pairPhone := func() {
		chosen := transport()
		ip := addr.Text()
		var address, code string
		var expires time.Time
		action(func() error {
			if err := c.setTransport(chosen); err != nil {
				return err
			}
			var err error
			if address, err = c.pairAddress(ip, chosen); err != nil {
				return err
			}
			code, expires, err = c.issuePair()
			return err
		}, func() {
			hint := "픽셀에 입력할 PC 주소  " + address
			if chosen == "usb" {
				hint = "픽셀에서 ‘USB 케이블로 연결’ 선택"
			}
			pairCode.SetText(code)
			connectHint.SetText(hint + "  ·  " + expires.Format("15:04") + "까지 유효")
			codeUntil = expires
			setNote("픽셀에 연결 코드를 입력하면 이 창에 연결 상태가 표시돼요.")
		})
	}

	header := d.Composite{Layout: d.HBox{MarginsZero: true, Spacing: 12}, Children: []d.Widget{
		d.ImageView{Image: logo, Mode: d.ImageViewModeZoom, MinSize: d.Size{Width: 40, Height: 40}, MaxSize: d.Size{Width: 40, Height: 40}},
		label(appTitle, 20, true, ink),
		d.HSpacer{},
		d.Composite{AssignTo: &chip, Layout: d.HBox{Margins: d.Margins{Left: 14, Top: 6, Right: 14, Bottom: 6}},
			Children: []d.Widget{d.Label{AssignTo: &chipLabel, Font: font(10, true), TextColor: faint, NoPrefix: true}}},
	}}
	status := card(
		d.Label{AssignTo: &step, Font: font(14, true), TextColor: ink, NoPrefix: true, EllipsisMode: d.EllipsisEnd},
		d.Label{AssignTo: &detail, Text: " ", Font: font(10, false), TextColor: muted, NoPrefix: true, EllipsisMode: d.EllipsisEnd},
		d.VSpacer{Size: 4},
		row(button(&toggle, "▶  전송 시작", toggleSend),
			button(&approve, "복사본 정리", func() {
				bundle := last.progress.BatchNumber
				action(func() error { return c.approve(bundle) }, func() { setNote("픽셀 복사본 정리를 승인했어요.") })
			}),
			d.HSpacer{},
			button(&settingsButton, "설정", func() { openSettings() }),
		),
	)
	metric := func(title string, count, size **walk.Label, bar **walk.CustomWidget, value *int, color walk.Color) d.Composite {
		return card(label(title, 10, true, muted),
			d.Label{AssignTo: count, Text: "0 / 0개", Font: font(22, true), TextColor: ink, NoPrefix: true},
			makeBar(bar, value, color),
			d.Label{AssignTo: size, Text: " ", Font: font(10, false), TextColor: faint, NoPrefix: true, EllipsisMode: d.EllipsisEnd})
	}
	phone := card(
		row(label("픽셀 연결", 12, true, ink), d.HSpacer{},
			button(&linkButton, "연결 설정", func() { pairOpen = !pairOpen; apply(last) })),
		d.Label{AssignTo: &phoneLine, Text: "연결된 픽셀이 없어요.", Font: font(10, false), TextColor: muted, NoPrefix: true},
		d.Composite{AssignTo: &pairPanel, Layout: d.VBox{Margins: d.Margins{Top: 8}, Spacing: 8}, Children: []d.Widget{
			row(d.ComboBox{AssignTo: &mode, Font: font(11, false), Model: []string{"Wi-Fi", "USB"}, CurrentIndex: modeIndex, MinSize: d.Size{Width: 100, Height: 30}},
				d.Label{AssignTo: &addressLabel, Text: "PC 주소", Font: font(10, true), TextColor: muted},
				d.ComboBox{AssignTo: &addr, Font: font(11, false), Model: ips, CurrentIndex: ipIndex, MinSize: d.Size{Width: 150, Height: 30}},
				button(&refreshIPs, "새로고침", func() {
					next := localIPs()
					addr.SetModel(next)
					if len(next) > 0 {
						addr.SetCurrentIndex(0)
					}
				}),
				d.HSpacer{}),
			row(button(&pair, "새 연결 코드", pairPhone),
				d.LineEdit{AssignTo: &pairCode, ReadOnly: true, CueBanner: "ABC-123", Font: d.Font{Family: "Consolas", PointSize: 16, Bold: true}, MinSize: d.Size{Width: 160, Height: 34}, MaxSize: d.Size{Width: 200}},
				d.HSpacer{}),
			d.Label{AssignTo: &connectHint, Text: " ", Font: font(10, false), TextColor: muted, NoPrefix: true, EllipsisMode: d.EllipsisEnd},
		}},
	)
	controls := d.MainWindow{AssignTo: &window, Title: appTitle, Icon: icon, Font: font(11, false),
		Background: d.SolidColorBrush{Color: paper},
		Size:       d.Size{Width: 820, Height: 600}, MinSize: d.Size{Width: 640, Height: 520},
		Layout: d.VBox{Margins: d.Margins{Left: 20, Top: 16, Right: 20, Bottom: 12}, Spacing: 12},
		Children: []d.Widget{
			header,
			status,
			row(metric("픽셀로 전송", &transferCount, &transferBytes, &transferBar, &transferValue, blue),
				metric("구글 포토 원본 백업 확인", &verifiedCount, &verifiedBytes, &verifiedBar, &verifyValue, green)),
			phone,
			d.VSpacer{},
			d.Label{AssignTo: &notice, Text: " ", Font: font(10, false), TextColor: navy, NoPrefix: true, EllipsisMode: d.EllipsisEnd},
		}}
	if err := controls.Create(); err != nil {
		return err
	}
	mode.CurrentIndexChanged().Attach(modeChanged)
	modeChanged()
	apply(c.windowStatus())
	if c.settings().Root == "" {
		setNote("설정에서 원본 폴더와 백업 계정을 선택하세요.")
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
