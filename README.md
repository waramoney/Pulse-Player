# Pulse Player v1.3.3 — Android music, video and YouTube

모바일용 음악·동영상 플레이어. 사용자가 제공한 **v1.3.1 APK**에서 기존 Android 기능을 복구한 뒤 원본 소스 파일을 직접 교체했습니다.

## 주요 기능
- 휴대폰 음악·영상 검색 및 로컬 재생, 이름 지정 플레이리스트, 가사 입력/편집(LRC 포함)
- YouTube 공식 IFrame 플레이어 재생 시도 및 임베드 제한 영상의 YouTube/Chrome 열기
- YouTube 검색 결과와 탐색 화면에서 하트 즐겨찾기(기기 내부 영구 저장)
- 음악/로컬 영상/YouTube 재생 속도 **드롭다운** (0.5, 0.75, 1, 1.25, 1.5, 1.75, 2.0배)
- Google OAuth 연결은 **Chrome 브라우저에서만** 진행 (TV·device-code 로그인 제거)

## 개인 사용: 공식 YouTube 재생과 파일 다운로드
- **YouTube 앱에서 오프라인 저장**: Pulse Player의 YouTube 영상 메뉴 또는 상단 버튼에서 공식 YouTube 앱으로 이동합니다. Premium 회원 및 오프라인 저장이 지원되는 영상은 YouTube 앱의 **다운로드** 버튼으로 보관할 수 있습니다. 보관 파일은 YouTube 앱 내부 암호화 저장소에 있으며 Pulse Player에서 MP4로 추출할 수 없습니다.
- **권한 있는 직접 파일 다운로드**: `.mp4`, `.webm`, `.mp3`, `.flac` 등 HTTPS 직접 미디어 파일 주소를 입력하면 Android DownloadManager가 내려받습니다. Android 10 이상은 `Download/PulsePlayer/`, Android 8~9는 앱 별도 다운로드 폴더를 사용합니다. YouTube URL은 이 기능의 대상이 아닙니다.
- **광고**: Pulse Player 자체 광고는 없습니다. 공식 YouTube 재생의 광고는 변경하거나 차단하지 않습니다. YouTube Premium을 이용하는 경우 공식 앱에서 광고 없이 시청할 수 있습니다.
- 파일 다운로드는 사용자 버튼 클릭 때에만 시작하며, 백그라운드 폴링·Firebase·Realtime DB를 추가하지 않습니다.

## Chrome YouTube 계정 동기화
Google 개인 재생목록·구독채널을 YouTube Data API로 동기화하려면 **Google Cloud OAuth 데스크톱 앱 클라이언트** 및 YouTube Data API v3 활성화가 필요합니다. 공식 Google 로그인 페이지가 Chrome에서 열리고 권한 동의가 끝나면 앱으로 복귀합니다. PKCE + CSRF state + 단기간 127.0.0.1 callback을 사용합니다. 사용자 ID/암호는 앱에 입력하지 않습니다. 데스크톱 클라이언트 유형은 Chrome에서 계정 권한에 동의하기 위한 준비 단계이며, Android용 공식 OAuth SDK 통합과는 별개입니다.

본 소스에는 OAuth 클라이언트 ID나 토큰이 내장되어 있지 않습니다. **사용자가 발급한 클라이언트 설정이 없으면 개인 YouTube 계정 API 동기화는 작동하지 않습니다.** 공개 영상은 해당 설정 없이 검색 링크/붙여넣기로 저장·재생할 수 있습니다.

## 영상 재생 안내
YouTube의 임베드 정책/저작권·연령·지역 제한으로 일부 영상은 앱 내에서 재생되지 않을 수 있습니다. 실패 시 공식 YouTube 앱/Chrome으로 연결하는 안전한 대체 경로를 제공합니다. YouTube 영상이나 오디오는 비공식 방법으로 다운로드/추출하지 않습니다. 공식 정책상 앱 내 재생 성공을 모든 영상에 보장하지 않습니다.

## 빌드 / 다운로드
Actions → **Build Pulse Player APK** → 최신 빌드 → `PulsePlayer-Android-APK` 결과물의 APK. 공개 GitHub 저장소는 기본 호스팅 러너 무료이며, 직접 다운로드 경로 `downloads/PulsePlayer-v1.3.3.apk`는 검증된 빌드 후 업로드합니다.

## 앱 데이터와 서명
같은 앱 ID를 쓰더라도 신규 APK의 서명이 기존 v1.3.1과 다르면 기존 앱에 덮어쓰기 설치가 안 됩니다. 새로 설치하기 전에 기존 앱에서 **설정 → 백업 내보내기**로 플레이리스트·가사·즐겨찾기 백업을 만든 후 새 버전에서 가져오세요. 설치 해제 시 기존 앱 내부 데이터가 삭제될 수 있습니다.

## 개발 원칙
기존 소스를 덧붙이지 않고 직접 교체, 충돌하는 TV 로그인 제거, 저장은 기기 내부 우선, 지속 네트워크 감시·Firebase·Realtime DB 사용 없음.
