#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"
mkdir -p ../out
go run github.com/akavel/rsrc@v0.10.2 -arch amd64 -manifest app.manifest -o rsrc_windows_amd64.syso
CGO_ENABLED=0 GOOS=windows GOARCH=amd64 go build -trimpath -ldflags='-H windowsgui -s -w' \
    -o ../out/pixel-auto-backup-windows-x64.exe .
