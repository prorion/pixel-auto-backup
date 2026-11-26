//go:build !windows

package main

import (
	"errors"
	"log"
	"os/exec"
)

func runWindow(*controller) error {
	return errors.New("오토픽셀 데스크톱 창은 Windows에서 실행할 수 있습니다")
}

func fatalWindow(err error) { log.Fatal(err) }

func hideConsole(*exec.Cmd) {}
