# Pulse Player 1.1 — Android 음악·동영상 플레이어

HTML/CSS/JavaScript 화면을 **Android WebView**에 담고, Android 기본 API로
휴대폰의 실제 음악·동영상을 재생하는 오프라인 플레이어 프로젝트입니다.

- **음악**: MP3/M4A/AAC/WAV/FLAC/OGG 등 (기기 지원 코덱에 따라 다름)
- **영상**: MP4/WEBM/3GP 등 (기기 지원 코덱에 따라 다름), 전체 화면 VideoView
- **파일 추가**: Android 시스템 파일 선택기에서 여러 파일을 한번에 선택
- **라이브러리 저장**: 원본 파일은 복사하지 않고 Android 문서 URI와 접근 권한을 저장
- **재생 기능**: 재생/일시정지/이전/다음, 탐색, 셔플, 전체/한곡 반복, 배속
- **정리**: 음악/영상/즐겨찾기 필터, 검색, 즐겨찾기, 목록에서 제거
- **플레이리스트**: 여러 목록 생성, 이름 지정/변경/삭제, 여러 곡·영상 추가/제외, 개별 순서 변경,
  선택 목록부터 순서대로 재생. 음악 재생은 잠금화면/알림창에서도 선택 목록의 순서를 적용합니다.
- **가사**: 곡별 가사 직접 입력·붙여넣기·수정·삭제, 재생 화면 가사 탭 표시,
  일반 텍스트 및 `[mm:ss.xx]` LRC 타임스탬프 가사 하이라이트·탭하여 탐색.
- **모바일 탐색**: 음악 / 플레이리스트 / 재생·가사 탭과 미니 플레이어.
- **저장**: 플레이리스트·가사는 Android 내부 앱 저장소에만 보존 (인터넷·클라우드 없이 사용).
- **백그라운드 음악**: Android MediaSession 및 foreground `mediaPlayback` 서비스,
  알림창 재생·이전·다음 버튼 지원
- **프라이버시**: 로그인, Firebase, 사용량 분석, 서버, 네트워크 통신 없음.
  `READ_MEDIA_*` 권한 없이 선택한 파일만 접근합니다.

## APK 빌드하기 — Android Studio

1. ZIP 파일을 압축 해제합니다.
2. Android Studio의 **Open**에서 `PulsePlayer_Android` 폴더를 선택합니다.
3. SDK Platform **Android 35**, **JDK 17**, **Gradle 8.9**를 사용합니다.
   Gradle Sync에서 Gradle 다운로드/설치를 요구하면 허용하세요.
   AGP 버전은 **8.7.3**입니다. 최신 Android Studio는 프로젝트를 가져올 때
   `gradle/wrapper/gradle-wrapper.properties`의 배포 버전을 사용하거나
   Gradle을 별도 선택할 수 있습니다. 이 ZIP에는 공식 wrapper JAR가 포함되지
   않으므로 명령줄의 `./gradlew` 대신 Android Studio 또는 설치된 `gradle`을 사용하세요.
4. 메뉴 **Build → Build Bundle(s) / APK(s) → Build APK(s)** 를 클릭합니다.
5. 생성된 파일:
   `app/build/outputs/apk/debug/app-debug.apk`
6. APK를 휴대폰으로 옮겨 설치하세요. Android 8.0(API 26) 이상입니다.

### PC에 Android Studio가 없으면 — GitHub Actions

1. 새 GitHub 저장소에 **이 프로젝트 내부의 파일 전체를** 업로드합니다.
   `.github/workflows/build-android-apk.yml` 파일도 포함해야 합니다.
2. GitHub → **Actions** → **Build Pulse Player APK** → **Run workflow**.
3. 작업이 성공하면 해당 실행 페이지의 **Artifacts**에서
   `PulsePlayer-Android-APK`를 내려받습니다.
4. 압축을 풀면 `app-debug.apk`가 있습니다. APK는 테스트/개인 설치용입니다.

워크플로는 `main`/`master` 브랜치에 푸시할 때도 자동으로 실행됩니다.
APK는 테스트 서명 버전이며 Play Store 정식 배포에는 별도의 릴리즈 서명이 필요합니다.

**기존 1.0 APK와 주의점:** GitHub Actions의 기본 디버그 키는 빌드마다 새로 생성되어
기존 APK와 서명이 달라질 수 있습니다. 서명 충돌 시 기존 앱을 삭제해야 설치가 되며,
삭제 시 기존 앱에 저장된 파일 목록 등도 삭제됩니다. 이전에 설치했다면 바로 삭제하기 전에
파일 목록을 메모하고, 지속적인 업데이트를 위해 전용 서명 키를 GitHub Secrets로
설정한 릴리즈 빌드로 전환하는 것을 권장합니다. 본 작업은 기존 키를 접근/복구할 수 없습니다.

## HTML 미리보기

`app/src/main/assets/index.html`을 열어 UI를 볼 수 있습니다.
별도로 제공된 `PulsePlayer_Standalone.html`은 CSS/JS가 내장된 1개 HTML 파일입니다.
브라우저에서는 파일을 선택한 해당 탭에서만 파일이 유지됩니다. 목록·가사 메타데이터는
브라우저 로컬 저장소가 허용될 때 저장되지만 파일 선택 시 URI가 변경되어 연결이 끊길 수 있습니다.
**Android 앱에서만** 선택한 원본 파일의 URI 접근 권한 및 목록이 앱을 닫은 후에도 저장됩니다.

## 한계 / 개발 상태

- HTML/JS의 재생, 플레이리스트 생성·이름 변경·곡 선택/제외, 가사 입력·표시,
  모바일 UI를 Chromium 자동화에서 검증했습니다.
- Android 네이티브 소스는 GitHub Actions의 Android SDK 35/Gradle 빌드로 검증합니다.
  휴대폰 실기기의 코덱·백그라운드·선택 파일 권한은 별도로 확인해야 합니다.
- 영상은 앱 내부의 별도 전체 화면 Android `VideoView`에서 재생되며,
  백그라운드로 전환 시 일시정지됩니다. 음악은 화면을 꺼도 재생을 유지하도록
  MediaSession foreground service를 사용합니다.
- 오디오와 영상은 Android에서 재생 엔진이 분리되어 있어, 음악은 음악끼리,
  영상은 영상끼리 선택한 플레이리스트 안에서 다음/이전 항목으로 넘어갑니다.
- 가사는 온라인 사이트에서 자동으로 가져오지 않으며 사용자가 직접 입력한 텍스트만 저장합니다.
- 코덱 지원 여부는 Android 버전/제조사별로 달라질 수 있습니다.
- 라이브러리의 '제거' 버튼은 **앱 목록에서만 제거**하며 원본 파일은 삭제하지 않습니다.
- Android 파일 선택기에서 제공자별로 영구 접근 권한을 허용하지 않는 경우,
  앱 재시작 후 해당 파일의 접근이 안 될 수 있어 다시 추가해야 합니다.

## 소스 구성

```
app/src/main/assets/index.html       오프라인 UI
app/src/main/assets/app.css          모바일/PC 레이아웃
app/src/main/assets/app.js           재생 컨트롤·라이브러리 UI
app/src/main/java/com/pulse/player/MainActivity.java
                                    WebView, 문서 선택기, 영상 화면
app/src/main/java/com/pulse/player/AudioPlaybackService.java
                                    백그라운드 음악, MediaSession/알림
.github/workflows/build-android-apk.yml
                                    GitHub에서 APK 생성
```

개인정보나 파일 데이터는 원격 서버에 저장하지 않습니다.
