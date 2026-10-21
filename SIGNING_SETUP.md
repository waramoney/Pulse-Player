# 개인용 Pulse Player APK 업데이트 서명 안내

APK 업데이트는 **같은 `applicationId` + 증가된 `versionCode` + 같은 개인 서명키**가 있어야 기존 플레이리스트와 가사를 유지하며 설치됩니다.

## 1. 안정적인 서명키 생성 (PC에서 한 번만)

**절대 공개 GitHub 저장소, 인터넷 게시판, 메시지에 비밀번호나 키 저장 파일을 올리지 마세요.** 별도 USB 또는 암호화된 로컬 폴더에 안전하게 백업합니다.

Android Studio의 `Build → Generate Signed Bundle / APK → APK → Create new`에서 개인 키스토어를 생성할 수 있습니다. 별도의 명령줄 도구가 있다면 아래처럼 PKCS#12 키스토어를 만들 수도 있습니다.

```sh
keytool -genkeypair -v -keystore pulseplayer-personal.p12 -storetype PKCS12 -alias pulseplayer -keyalg RSA -keysize 3072 -validity 10000
```

## 2. 해당 키로 반복 빌드

`app/build.gradle`은 개발자의 로컬 환경변수 `PULSE_KEYSTORE_PATH`, `PULSE_KEYSTORE_PASS`, `PULSE_KEY_PASS`, `PULSE_KEY_ALIAS`를 모두 지정한 경우 `release` 빌드에 해당 키를 사용합니다. 환경변수를 설정하지 않은 경우 새로 빌드된 debug APK는 **안정적인 업데이트용으로 간주할 수 없습니다.**

키 파일과 비밀번호를 다른 사람에게 공유하지 않은 상태에서 APK 빌더 PC의 Android Studio로 서명된 APK를 만들어 주세요. 이후부터는 키 파일을 바꾸지 않고 앱 버전코드만 올려 빌드하면 됩니다.

## 3. 휴대폰 업데이트

1. 안정적인 서명으로 만든 첫 APK를 설치합니다. 기존 앱과 서명이 달라 설치가 막히면 기존 앱에서 백업 내보내기를 하고 재설치해야 합니다.
2. **설정 → 자동 업데이트 폴더 지정**에서 원하는 하위 폴더를 선택합니다. Android 일부 기기는 Download 루트 폴더 선택을 제한하므로, 예를 들어 `문서/PulseUpdates` 폴더를 따로 만들어 선택하세요.
3. 이후 새 버전 APK를 그 폴더에 복사하세요 (파일명 `PulsePlayer-v1.3.9.apk` 같은 형식).
4. Pulse Player가 앱 시작 시 하루 한 번 새 버전을 확인해 설치를 제안합니다. 바로 확인하려면 **새 버전 자동 확인 · 설치**를 누르세요.
5. Android 시스템에서 업데이트 출처 허용 및 **설치**를 눌러야 합니다. 완전 무인 설치 기능은 제공하지 않습니다.

폴더가 필요 없다면 **설정 → 업데이트 APK 직접 선택**으로 단일 APK를 골라도 서명 검증 후 설치할 수 있습니다.

### 기존 테스트 APK 사용자

v1.3.7 이하 GitHub Actions 테스트 APK는 빌드마다 다른 디버그 키로 서명되었을 가능성이 있습니다. 이전 APK와 새 서명이 다르면 Android가 앱을 덮어쓰지 못합니다. 데이터 백업 없이 앱을 삭제하지 마세요.
