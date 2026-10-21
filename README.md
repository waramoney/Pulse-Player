# Pulse Player v1.3.4 — Chrome YouTube + local audio reliability

## Changes
- YouTube links and search open directly in **Chrome** using the device's Chrome Google session. No YouTube/Google Data API key, OAuth client ID, or authorization-code entry is required.
- YouTube playback occurs in Chrome, not inside Pulse Player's WebView or in an overlay; the old floating YouTube video dock and YouTube notification service have been removed.
- Video bookmarks and named playlists remain saved on-device; YouTube subscriptions, account likes and Chrome cookies are **not** imported into Pulse Player. These account features remain accessible in Chrome only.
- MP3/other on-device media remains playable in Pulse Player; audio access rights are checked separately from video rights, content URIs are opened via file descriptors and playback errors are reported to the user.
- Authorized standalone MP4/MP3 HTTPS downloads remain supported; YouTube Premium offline downloads stay inside the official YouTube app.
- The app does not block YouTube ads. If you have YouTube Premium, ad-free playback is governed by your Chrome/YouTube session and account eligibility.

## User test checklist
1. First back up playlists/lyrics in Settings. Updating may require reinstalling if debug signatures differ.
2. Grant **Music and audio** permission on Android 13+ (Settings > Apps > Pulse Player > Permissions). Add a local MP3, then verify playback, pause and next-track controls; repeat with an imported audio file.
3. YouTube tab > **Chrome에서 열기 / 로그인**; sign in directly in Chrome if necessary and play a video.
4. Return to Pulse Player. No floating YouTube panel or YouTube mini player should remain.
5. Save a YouTube URL via paste, set favorite, put it into a named playlist, and tap it to open the exact video in Chrome.

## Limitations
YouTube cannot be reliably embedded in an app with a Chrome login cookie via WebView: Android WebView and Chrome have separate cookies. Embedded player errors, including error 153 and uploader restrictions, cannot be circumvented by a separate login. Direct Chrome playback avoids the embed/player limitations, but does not grant Pulse Player access to Google account data.

## Build
GitHub Actions: `Build Pulse Player APK`. Package ID: `com.pulse.player`, version `1.3.4`.
