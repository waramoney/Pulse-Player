# Pulse Player v1.3.6 — Android 개인용 음악·영상 플레이어

Android 8.0(API 26) 이상. GitHub Actions에서 무료 공개 저장소의 일반 러너로 Debug APK를 빌드합니다.

## 재생·저장 기능

- 로컬 오디오·영상: 기기 검색(MediaStore), 파일 직접 추가(SAF), 재생목록 및 즐겨찾기, 가사 편집, 배속 드롭다운.
- YouTube: 검색·탐색은 앱 내 브라우저에서 수행하고, 결과 영상을 누르면 공식 임베디드 플레이어를 앱 안에서 실행. 영상 하트/플레이리스트 지원.
- Google 로그인은 별도의 **Chrome에서 열기 / 로그인** 버튼으로만 실행. Chrome의 로그인 쿠키는 앱 내부 YouTube 플레이어와 공유되지 않음.
- 유료 Google Cloud/YouTube Data API·OAuth 클라이언트 불필요. Firebase/Realtime DB 미사용. 영상 다운로드는 YouTube 공식 앱의 지원 범위 또는 직접 저장 권한이 있는 미디어 파일에 한정.

## 라이브러리 원본 삭제

음악·영상 파일 행의 **더보기** 메뉴에서 다음 동작을 구분합니다.

1. **라이브러리에서 숨기기**: 실제 파일은 삭제하지 않으며, 나중에 설정에서 다시 표시 가능(기기 검색 파일).
2. **휴대폰 원본 파일 삭제**: 안드로이드 앱에서 가져온 `content://` 음악·동영상에만 제공. 네이티브 확인 창을 띄운 뒤 Android 11+에서는 MediaStore의 공식 삭제 요청으로 사용자의 시스템 승인을 받습니다. Android 10에서는 필요시 복구 가능한 쓰기 승인 절차를 거칩니다. Android 8/9에서는 저장소 쓰기 권한을 요청합니다. 문서 선택기로 추가한 파일은 제공자가 삭제를 허용하는 경우에만 삭제됩니다.

삭제 승인 거절/실패 시 라이브러리·플레이리스트·가사 정보는 그대로 두고 안내합니다. 실제 삭제 성공 시에만 라이브러리 및 해당 플레이리스트/가사 참조를 정리합니다. YouTube 링크는 휴대폰 파일이 아니므로 원본 삭제 항목이 없습니다.

## v1.3.6 핵심 수정

- **Chrome 강제 이동 버그 수정:** `MainActivity.openBrowser()`에서 YouTube 주소를 무조건 Chrome으로 열던 분기 제거. YouTube 검색 결과를 선택했을 때 WebView 일반 탐색과 SPA(pushState) 모두 앱 내 공식 YouTube 임베디드 플레이어에 연결.
- Chrome 로그인 버튼 옆 장문 설명 삭제. **복사한 링크 추가** 버튼 아래 장문 안내 삭제.
- 로컬 미디어 원본 삭제의 OS별 승인 절차 및 취소/실패 안전 처리. 기존 숨기기 기능은 유지.
- 기존 음악 재생 검색·가사·플레이리스트·저장 키 유지. 기존 YouTube 우회 다운로드·광고 차단은 제공하지 않음.

## 검증 명령

```bash
node --check app/src/main/assets/app.js
node tests/contract.cjs
python tests/browser_integration.py
```

Android APK는 GitHub Actions의 Java 17, Android SDK 35, Gradle 8.9로 `:app:assembleDebug`를 실행합니다. 브라우저 모의 검사는 실제 YouTube 네트워크 재생 성공을 보장하지 않습니다. YouTube 영상의 임베드 금지·로그인 필요·자동재생 금지 등 플랫폼 제한이 있으면 Chrome 대체 재생을 이용합니다.

## 설치 주의

Debug APK의 서명이 이전 버전과 다르면 앱을 삭제한 뒤 설치해야 할 수 있습니다. 삭제하기 전에 앱에서 가사·플레이리스트·즐겨찾기를 백업하십시오.
