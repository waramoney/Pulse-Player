# Pulse Player v1.3.7 — 개인용 Android 음악·영상 플레이어

Android 8.0(API 26) 이상, Android SDK 35 / Gradle 8.9 / Java 17. Firebase, Realtime DB, 유료 YouTube Data API, 별도 Google OAuth 클라이언트를 사용하지 않습니다.

## 이번 수정: YouTube는 앱에서, Chrome은 로그인만

- YouTube 검색·탐색: 모바일 YouTube 페이지를 **앱의 WebView**에서 열며, 일반 링크, 새 창(`target=_blank`), 싱글 페이지 앱 탐색 및 Android `intent://` 링크로 들어오는 영상 선택을 내부 재생으로 처리합니다. 영상 재생 도중 Chrome을 자동 실행하지 않습니다.
- 재생: 공식 YouTube 임베디드 플레이어를 앱에 표시하며, 임베드가 제한되거나 재생되지 않으면 **'앱 안에서 다른 재생'** 버튼으로 모바일 YouTube 페이지를 앱 내 WebView에서 엽니다. 이 경로도 Chrome을 자동 호출하지 않습니다.
- Google 계정 로그인만 명시적인 **Chrome에서 열기 / 로그인** 버튼을 사용합니다. Android WebView와 Chrome의 쿠키는 공유되지 않으므로, Chrome 로그인으로 앱 내부에서 로그인 전용 또는 연령 제한 영상을 자동 인증할 수는 없습니다.
- Chrome으로 직접 재생시키는 버튼은 일반 재생 화면에서 제거했습니다. 영상의 소유자가 임베드 자체를 금지한 경우 앱에서 강제 재생할 수는 없습니다.

## 저장 및 작은 창 재생

- YouTube 영상은 **내 영상 링크 보관함**, 즐겨찾기, 이름을 붙인 플레이리스트로 휴대폰에 저장할 수 있습니다. 저장되는 것은 URL·영상 메타데이터이지 MP4 파일이 아닙니다.
- **'작은 화면으로 계속 보기'** 버튼은 지원 Android 기기의 시스템 PiP를 요청합니다. Home 이동 시에도 PiP를 시도합니다. 이 기능은 영상이 화면에 보이는 재생이며, 화면을 끈 상태에서 YouTube 음성만 계속 재생되는 백그라운드 오디오를 보장하지 않습니다.
- YouTube **오프라인 저장**은 공식 YouTube 앱으로 이동하여 지원 영상·계정에 한해 사용합니다. YouTube가 다운로드한 영상은 암호화되어 공식 앱에서만 재생되며 Pulse Player의 로컬 영상 파일로 저장되지 않습니다.
- 직접 HTTPS로 제공되는 본인 소유/저장 허가 MP3·MP4 파일은 Android DownloadManager를 통해 저장할 수 있습니다. YouTube 영상 스트림 추출·광고 차단은 제공하지 않습니다.

## 로컬 음악 및 동영상

- MediaStore 기기 검색, SAF 파일 추가, 음악 백그라운드 재생(포그라운드 미디어 서비스, MediaSession 알림 제어 및 Wake Lock), 영상 재생, 재생목록/즐겨찾기/가사 편집/배속 드롭다운을 유지합니다.
- 로컬 음악은 화면이 꺼져도 오디오 서비스가 재생을 유지하도록 보완했습니다.
- 라이브러리에서 '숨기기'와 '휴대폰 원본 파일 삭제'는 별도 메뉴입니다. 원본 삭제는 Android 확인·권한 승인에 성공한 경우에만 목록과 관련 데이터를 정리합니다.

## 검증

```bash
node --check app/src/main/assets/app.js
node tests/contract.cjs
python tests/browser_integration.py
```

Android APK 빌드: GitHub Actions `Build Pulse Player APK` 실행. 위 테스트의 YouTube 외부 응답은 모의 처리되며, 실제 휴대폰에서의 YouTube 재생 성공과 PiP 동작은 별도 확인이 필요합니다.

## 설치 전 백업

기존 디버그 APK와 새로운 APK의 서명이 다른 경우 업데이트 설치에 실패할 수 있습니다. 기존 앱을 삭제하기 전 설정의 백업 내보내기로 플레이리스트, 가사 및 즐겨찾기를 저장해 주세요.
