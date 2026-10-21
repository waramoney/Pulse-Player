# Pulse Player v1.3.5 — Android music and YouTube playback repair

## Verified root cause and changes
- **Critical music bug:** `MainActivity.findTrack()` searched the library but returned `null` unconditionally. Fixed to return the matching track, preventing "registered but won't play" for both local music and video.
- Playback controls now issue `PLAY` for a tapped local library row rather than toggling a stale track ID, and the audio service no longer calls `MediaPlayer.start()` twice.
- Local file access uses read-only persisted document URIs for imported music and `READ_MEDIA_AUDIO` for scanned device files. The app reports errors rather than silently failing.
- Saved YouTube videos open an **official YouTube embedded iframe in Pulse Player** on demand; heart/playlist controls are in a separate accessible toolbar. Closing the viewer unloads the iframe and stops playback (no floating mini-player).
- YouTube searching and browsing uses YouTube's normal mobile website inside the app, **without a paid YouTube Data API or OAuth client**. Its toolbar can save the selected URL to the library or toggle a heart. Chrome remains a separate login/fallback path; Chrome cookies cannot be accessed from an Android WebView.
- YouTube official iframe playback includes a referring HTTPS app origin to meet player-identity requirements. Some videos restrict embedding, require age verification, or block autoplay. For those, use **Chrome에서 재생**.
- Existing playlists, lyrics, favorites, local media and authorized direct-file downloads retain their storage keys.
- No Firebase, realtime database or background internet polling was added. YouTube pages only load on user action.

## Tests
- `node --check app/src/main/assets/app.js`
- `node tests/contract.cjs`
- `python tests/browser_integration.py` (where Playwright Chromium is available)
- GitHub Actions Android 35 + Java 17 APK build.

## Real Android device acceptance checks
1. Back up playlist/lyrics before replacing an APK signed with a different debug key.
2. Tap a local MP3 in the library and verify its playback state and audible sound; then test imported MP3 and a second format such as M4A.
3. YouTube → paste or enter a valid YouTube URL → verify the official inline player appears, tap play if autoplay is blocked. Check heart, playlist and close; confirm no floating player remains.
4. Browse/search YouTube in app; when a video URL is selected tap the browser toolbar ♡ to save. Chrome button signs into Google separately.
5. For YouTube 153 or unembeddable errors, use Chrome fallback; this version does not bypass restrictions or remove ads.

## Privacy
The app never collects or stores Google credentials. All playlist, lyric and favorite data remains local. YouTube iframe playback is governed by YouTube policies and network conditions.
