# 첫 실기기 시험

시험판은 Windows 10/11 x64와 Android 10 이상을 대상으로 빌드했다. 윈도우 실행 파일을 열면 기본 브라우저에 로컬 진행 화면이 뜬다. 윈도우 프로그램 창을 열어 두면 픽셀과 연결이 유지된다. 픽셀 APK를 사이드로드할 때 Android가 설치 권한을 요청할 수 있다.

## Wi-Fi 연결

1. 접근이 가능한 폴더에 홈캠 영상 2~3개를 복사해 둔다. 윈도우 실행 파일에서 이 시험 폴더와 픽셀의 구글 포토 백업 계정을 입력한다. 자동 정리는 기본적으로 꺼져 있다.
2. Wi-Fi 연결을 선택하고 PC 네트워크 주소를 고른 뒤 페어링 코드 또는 파일을 만든다. Windows 방화벽이 묻는다면 집 네트워크에서 연결을 허용한다.
3. 픽셀 앱에 코드를 붙여넣거나 페어링 파일을 가져온다. 픽셀이 파일을 받으면 윈도우 진행 화면의 전송량이 바뀐다.
4. 픽셀 앱에서 접근성 설정을 열어 **Pixel Auto Backup · 포토 확인**을 켠다.
5. 구글 포토에서 **PixelAutoBackup** 기기 폴더의 백업을 켠다. 구글 포토의 **설정 → 백업**에서 대상 계정이 표시된 화면을 연다.
6. 픽셀 앱의 **다음 영상부터 연속 확인**을 누른다. 영상 세부정보가 자동으로 열리지 않으면 포토에서 대상 영상을 열고 위로 쓸어올린다. 픽셀 앱으로 돌아오면 파일·백업 상태·원본 화질이 접근성 화면에 노출됐는지 진단을 볼 수 있다.
7. 윈도우 화면에서 묶음 전체가 확인되면 같은 계정의 픽셀 8 프로에서 영상이 올라왔는지 확인한다. **확인된 묶음 정리**를 누른 뒤 PC 원본과 포토 백업본을 다시 확인한다.

## USB 연결

[Android SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools)를 설치하고 ADB를 Windows PATH에 둔다. 픽셀에서 USB 디버깅을 활성화하고 데이터 케이블 연결을 승인한다. 윈도우 화면에서 USB를 선택하고 페어링 코드를 다시 만든다. 픽셀 앱에서 새 코드로 연결한다. 같은 PC와 픽셀을 사용하면 기존 작업을 이어서 진행한다. 구글 포토 업로드에는 픽셀의 인터넷 연결이 필요하다.

## 확인 결과를 공유할 때

- 기기와 구글 포토 버전
- PC 화면의 복사 완료 수와 원본 백업 확인 수
- 픽셀 앱의 진단 한 줄
- 문제가 난 단계와 구글 포토 세부정보에서 실제로 보인 상태 문구

구글 포토가 필요한 상태를 접근성 서비스에 노출하지 않으면 시험판은 묶음을 픽셀에 보관한다. 화면이 잠겨 있는 동안에는 마지막 백업 확인 수가 유지된다. 화면을 끈 채 전송할 때는 픽셀을 충전기에 연결한다.

## 빌드

윈도우 실행 파일은 Go 1.24 이상에서 아래 명령으로 빌드한다.

```sh
cd desktop
CGO_ENABLED=0 GOOS=windows GOARCH=amd64 go build -trimpath -o ../out/pixel-auto-backup-windows-x64.exe .
go test ./...
```

APK는 JDK 또는 Eclipse Java Compiler, Android API 29의 android.jar, Android Build-Tools와 테스트 키 저장소 경로를 사용한다.

```sh
ANDROID_JAR=/path/to/android-29/android.jar \
ANDROID_BUILD_TOOLS=/path/to/build-tools/34.0.0 \
PAB_SIGNING_KEY=/outside/repo/pab-test.jks android/build.sh
```

첫 빌드에서 시험용 키를 만든다. 같은 APK를 업데이트하려면 같은 키가 필요하다. Google API 키와 OAuth 클라이언트는 필요 없다. PC 시작 시 페어링 토큰과 HTTPS 키를 로컬에서 생성한다.
