package com.pulse.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Color;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Offline HTML/JavaScript interface + native Android media library/playback.
 * Only files explicitly selected with the Android system picker are accessible.
 */
public final class MainActivity extends Activity {
    private static final int REQUEST_IMPORT = 1001;
    private final Object libraryLock = new Object();
    private final ExecutorService metadataExecutor = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private WebView webView;
    private FrameLayout videoFrame;
    private VideoView videoView;
    private TextView videoTitle;
    private String videoId = "";
    private boolean videoPrepared;
    private boolean webReady;
    private boolean videoActive;
    private boolean videoPausedByLifecycle;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("pulse", MODE_PRIVATE);
        getWindow().setStatusBarColor(Color.rgb(11, 13, 21));
        getWindow().setNavigationBarColor(Color.rgb(11, 13, 21));
        createViews();
    }

    private void createViews() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(11, 13, 21));
        // Android 15+ enforces edge-to-edge. Keep controls clear of system bars.
        if (Build.VERSION.SDK_INT >= 35) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                root.setPadding(insets.getSystemWindowInsetLeft(),
                        insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(),
                        insets.getSystemWindowInsetBottom());
                return insets;
            });
        }
        webView = new WebView(this);
        webView.setBackgroundColor(Color.TRANSPARENT);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true); // Only for bundled file:///android_asset/ resources.
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        webView.addJavascriptInterface(new PlayerBridge(), "Android");
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // Never allow untrusted remote pages to access the JS bridge.
                return !request.getUrl().toString().startsWith("file:///android_asset/");
            }
            @Override public void onPageFinished(WebView view, String url) {
                webReady = true;
                pushLibrary();
                pushState();
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onJsConfirm(WebView view, String url,
                                                 String message, JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("확인")
                        .setMessage(message)
                        .setPositiveButton("삭제", (dialog, which) -> result.confirm())
                        .setNegativeButton("취소", (dialog, which) -> result.cancel())
                        .setOnCancelListener(dialog -> result.cancel())
                        .show();
                return true;
            }
        });
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        videoFrame = new FrameLayout(this);
        videoFrame.setBackgroundColor(Color.BLACK);
        videoFrame.setVisibility(View.GONE);
        videoView = new VideoView(this);
        FrameLayout.LayoutParams videoLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER);
        videoFrame.addView(videoView, videoLp);

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(12), dp(7), dp(10), dp(7));
        toolbar.setBackgroundColor(Color.argb(190, 10, 10, 18));
        videoTitle = new TextView(this);
        videoTitle.setTextColor(Color.WHITE);
        videoTitle.setTextSize(15);
        videoTitle.setSingleLine(true);
        videoTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        toolbar.addView(videoTitle, new LinearLayout.LayoutParams(0, dp(46), 1f));
        toolbar.addView(videoButton("❮", () -> skipVideo(-1, false)));
        toolbar.addView(videoButton("❯", () -> skipVideo(1, false)));
        toolbar.addView(videoButton("✕", this::closeVideo));
        videoFrame.addView(toolbar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));
        root.addView(videoFrame, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        webView.loadUrl("file:///android_asset/index.html");
    }

    private TextView videoButton(String label, Runnable action) {
        TextView button = new TextView(this);
        button.setText(label);
        button.setTextSize(21);
        button.setTextColor(Color.WHITE);
        button.setGravity(Gravity.CENTER);
        button.setContentDescription(label.equals("✕") ? "영상 닫기" : "다음 또는 이전 영상");
        button.setOnClickListener(view -> action.run());
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
        return button;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private String readLibrary() { return prefs.getString("library", "[]"); }

    private JSONObject findTrack(String id) {
        synchronized (libraryLock) {
            try {
                JSONArray list = new JSONArray(readLibrary());
                for (int i = 0; i < list.length(); i++) {
                    JSONObject row = list.optJSONObject(i);
                    if (row != null && id.equals(row.optString("id"))) return row;
                }
            } catch (Exception ignored) { }
        }
        return null;
    }

    private void pushLibrary() {
        if (!webReady) return;
        js("window.onNativeLibrary(" + readLibrary() + ");");
    }

    private void js(String code) {
        if (webReady && webView != null) webView.evaluateJavascript(code, null);
    }

    private void pushState() {
        if (!webReady) return;
        JSONObject out;
        AudioPlaybackService service = AudioPlaybackService.instance;
        if (service != null) out = service.snapshot();
        else {
            out = new JSONObject();
            try {
                out.put("id", prefs.getString("lastId", ""));
                out.put("playing", false);
                out.put("preparing", false);
                out.put("position", 0);
                out.put("duration", 0);
                out.put("error", "");
                out.put("shuffle", prefs.getBoolean("shuffle", false));
                out.put("repeat", prefs.getInt("repeat", 0));
                out.put("speed", prefs.getFloat("speed", 1f));
                out.put("video", false);
            } catch (Exception ignored) { }
        }
        if (!videoActive && service != null && !out.optBoolean("playing")
                && !out.optBoolean("preparing")) {
            String selected = prefs.getString("lastId", "");
            if (!selected.isEmpty() && !selected.equals(out.optString("id"))) {
                try {
                    out.put("id", selected);
                    out.put("position", 0);
                    JSONObject lastTrack = findTrack(selected);
                    out.put("duration", lastTrack == null ? 0 : lastTrack.optLong("duration", 0));
                } catch (Exception ignored) { }
            }
        }
        if (videoActive) {
            try {
                out.put("id", videoId);
                out.put("playing", videoPrepared && videoView.isPlaying());
                out.put("preparing", !videoPrepared);
                out.put("position", videoPrepared ? videoView.getCurrentPosition() : 0);
                out.put("duration", videoPrepared ? videoView.getDuration() : 0);
                out.put("video", true);
            } catch (Exception ignored) { }
        }
        js("window.onNativeState(" + out.toString() + ");");
    }

    private void launchImport() {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        picker.setType("*/*");
        picker.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"audio/*", "video/*"});
        picker.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { startActivityForResult(picker, REQUEST_IMPORT); }
        catch (Exception e) { Toast.makeText(this, "파일 선택기를 실행할 수 없습니다.", Toast.LENGTH_LONG).show(); }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_IMPORT || resultCode != RESULT_OK || data == null) return;
        Set<Uri> unique = new LinkedHashSet<>();
        if (data.getData() != null) unique.add(data.getData());
        ClipData clip = data.getClipData();
        if (clip != null) for (int i = 0; i < clip.getItemCount(); i++) {
            Uri item = clip.getItemAt(i).getUri();
            if (item != null) unique.add(item);
        }
        if (unique.isEmpty()) return;
        final List<Uri> uris = new ArrayList<>(unique);
        for (Uri uri : uris) {
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) { }
        }
        metadataExecutor.execute(() -> {
            int added = 0;
            synchronized (libraryLock) {
                try {
                    JSONArray old = new JSONArray(readLibrary());
                    Set<String> existing = new HashSet<>();
                    for (int i = 0; i < old.length(); i++) existing.add(old.optJSONObject(i).optString("id"));
                    for (Uri uri : uris) {
                        String id = uri.toString();
                        if (existing.contains(id)) continue;
                        JSONObject item = metadata(uri);
                        if (item == null) continue;
                        old.put(item);
                        existing.add(id);
                        added++;
                    }
                    prefs.edit().putString("library", old.toString()).apply();
                } catch (Exception ignored) { }
            }
            final int count = added;
            runOnUiThread(() -> {
                pushLibrary();
                Toast.makeText(this, count == 0 ? "새로 추가할 파일이 없습니다." :
                        count + "개 파일을 라이브러리에 추가했습니다.", Toast.LENGTH_SHORT).show();
            });
        });
    }

    private JSONObject metadata(Uri uri) {
        String name = "미디어 파일";
        long size = 0;
        try (Cursor cursor = getContentResolver().query(uri, new String[]{
                OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (nameIdx >= 0 && !cursor.isNull(nameIdx)) name = cursor.getString(nameIdx);
                if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) size = cursor.getLong(sizeIdx);
            }
        } catch (Exception ignored) { }
        String mime;
        try { mime = getContentResolver().getType(uri); }
        catch (Exception ignored) { mime = ""; }
        mime = mime == null ? "" : mime.toLowerCase(java.util.Locale.ROOT);
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        boolean video = mime.startsWith("video/") || lower.matches(".*\\.(mp4|m4v|mkv|webm|3gp|mov|avi)$");
        boolean audio = mime.startsWith("audio/") || lower.matches(".*\\.(mp3|m4a|aac|wav|flac|ogg|opus|wma|amr)$");
        if (!video && !audio) return null;
        String title = name.replaceFirst("(?i)\\.[^/.]+$", "");
        String artist = video ? "내 영상" : "내 음악";
        long duration = 0;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(this, uri);
            String d = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (d != null) duration = Long.parseLong(d);
            String t = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
            String a = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
            if (t != null && !t.trim().isEmpty()) title = t.trim();
            if (a != null && !a.trim().isEmpty()) artist = a.trim();
        } catch (Exception ignored) { }
        finally { try { retriever.release(); } catch (Exception ignored) { } }
        JSONObject out = new JSONObject();
        try {
            out.put("id", uri.toString());
            out.put("name", title);
            out.put("fileName", name);
            out.put("artist", artist);
            out.put("type", video ? "video" : "audio");
            out.put("duration", duration);
            out.put("size", size);
            out.put("favorite", false);
            out.put("addedAt", System.currentTimeMillis());
        } catch (Exception ignored) { }
        return out;
    }

    private void editTrack(String id, boolean remove, boolean toggleFavorite) {
        boolean changed = false;
        synchronized (libraryLock) {
            try {
                JSONArray old = new JSONArray(readLibrary()), next = new JSONArray();
                for (int i = 0; i < old.length(); i++) {
                    JSONObject item = old.optJSONObject(i);
                    if (item == null) continue;
                    if (!id.equals(item.optString("id"))) { next.put(item); continue; }
                    if (remove) { changed = true; continue; }
                    if (toggleFavorite) {
                        item.put("favorite", !item.optBoolean("favorite"));
                        changed = true;
                    }
                    next.put(item);
                }
                if (changed) prefs.edit().putString("library", next.toString()).apply();
            } catch (Exception ignored) { }
        }
        if (!changed) return;
        if (remove) {
            if (videoActive && videoId.equals(id)) closeVideo();
            AudioPlaybackService service = AudioPlaybackService.instance;
            if (service != null && service.snapshot().optString("id").equals(id)) {
                audioIntent(AudioPlaybackService.ACT_STOP, null, 0);
            }
            if (prefs.getString("lastId", "").equals(id)) prefs.edit().remove("lastId").apply();
            try { getContentResolver().releasePersistableUriPermission(Uri.parse(id),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (Exception ignored) { }
        }
        pushLibrary();
        pushState();
    }

    private void audioIntent(String action, String id, int position) {
        Intent intent = new Intent(this, AudioPlaybackService.class).setAction(action);
        if (id != null) intent.putExtra("id", id);
        intent.putExtra("position", position);
        try {
            if (AudioPlaybackService.ACT_PLAY.equals(action) && AudioPlaybackService.instance == null) {
                startForegroundService(intent);
            } else if (AudioPlaybackService.instance != null) {
                startService(intent);
            }
        } catch (Exception e) {
            Toast.makeText(this, "재생 서비스를 시작할 수 없습니다.", Toast.LENGTH_SHORT).show();
        }
    }

    private void play(String id, boolean toggle) {
        JSONObject item = findTrack(id);
        if (item == null) {
            Toast.makeText(this, "파일을 찾을 수 없습니다. 다시 추가해 주세요.", Toast.LENGTH_SHORT).show();
            return;
        }
        if ("video".equals(item.optString("type"))) {
            showVideo(id);
            return;
        }
        if (videoActive) closeVideo();
        AudioPlaybackService service = AudioPlaybackService.instance;
        if (toggle && service != null && id.equals(service.snapshot().optString("id"))) {
            audioIntent(AudioPlaybackService.ACT_TOGGLE, null, 0);
        } else {
            audioIntent(AudioPlaybackService.ACT_PLAY, id, 0);
        }
        pushState();
    }

    private void skipAudio(int direction) {
        if (AudioPlaybackService.instance != null) {
            audioIntent(direction > 0 ? AudioPlaybackService.ACT_NEXT :
                    AudioPlaybackService.ACT_PREVIOUS, null, 0);
            pushState();
            return;
        }
        List<String> ids = new ArrayList<>();
        try {
            JSONArray library = new JSONArray(readLibrary());
            for (int i = 0; i < library.length(); i++) {
                JSONObject item = library.optJSONObject(i);
                if (item != null && "audio".equals(item.optString("type")))
                    ids.add(item.optString("id"));
            }
        } catch (Exception ignored) { }
        if (ids.isEmpty()) return;
        int current = ids.indexOf(prefs.getString("lastId", ""));
        int next = current < 0 ? 0 : (current + direction + ids.size()) % ids.size();
        play(ids.get(next), false);
    }

    private void showVideo(String id) {
        JSONObject track = findTrack(id);
        if (track == null) return;
        audioIntent(AudioPlaybackService.ACT_PAUSE, null, 0);
        if (videoActive) videoView.stopPlayback();
        videoId = id;
        prefs.edit().putString("lastId", id).apply();
        videoPausedByLifecycle = false;
        videoPrepared = false;
        videoActive = true;
        videoFrame.setVisibility(View.VISIBLE);
        videoFrame.bringToFront();
        videoTitle.setText(track.optString("name", "동영상"));
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR);
        MediaController controller = new MediaController(this);
        controller.setAnchorView(videoView);
        videoView.setMediaController(controller);
        videoView.setOnPreparedListener(mp -> {
            videoPrepared = true;
            videoView.start();
            float speed = prefs.getFloat("speed", 1f);
            if (speed != 1f) {
                try { mp.setPlaybackParams(mp.getPlaybackParams().setSpeed(speed)); }
                catch (Exception ignored) { }
            }
            controller.show(2500);
            pushState();
        });
        videoView.setOnCompletionListener(mp -> {
            if (prefs.getInt("repeat", 0) == 2) {
                videoView.seekTo(0);
                videoView.start();
            } else {
                skipVideo(1, true);
            }
        });
        videoView.setOnErrorListener((mp, what, extra) -> {
            Toast.makeText(this, "이 영상을 재생할 수 없습니다.", Toast.LENGTH_LONG).show();
            closeVideo();
            return true;
        });
        videoView.setVideoURI(Uri.parse(id));
        videoView.requestFocus();
        pushState();
    }

    private void skipVideo(int direction, boolean automatic) {
        List<String> items = new ArrayList<>();
        try {
            JSONArray lib = new JSONArray(readLibrary());
            for (int i = 0; i < lib.length(); i++) {
                JSONObject item = lib.optJSONObject(i);
                if (item != null && "video".equals(item.optString("type"))) items.add(item.optString("id"));
            }
        } catch (Exception ignored) { }
        if (items.isEmpty()) { closeVideo(); return; }
        int current = items.indexOf(videoId);
        int next = current + direction;
        if (next >= items.size()) {
            if (automatic && prefs.getInt("repeat", 0) != 1) { closeVideo(); return; }
            next = 0;
        }
        if (next < 0) next = items.size() - 1;
        showVideo(items.get(next));
    }

    private void closeVideo() {
        if (!videoActive) return;
        videoActive = false;
        videoPrepared = false;
        videoView.stopPlayback();
        videoFrame.setVisibility(View.GONE);
        videoId = "";
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        pushState();
    }

    @Override public void onBackPressed() {
        if (videoActive) { closeVideo(); return; }
        super.onBackPressed();
    }

    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
    }

    @Override protected void onPause() {
        if (videoActive && videoPrepared && videoView.isPlaying()) {
            videoView.pause();
            videoPausedByLifecycle = true;
        }
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        // Screen-off audio is handled by AudioPlaybackService, not this Activity.
        if (videoActive && videoPausedByLifecycle) {
            videoPausedByLifecycle = false;
            if (videoPrepared) videoView.start();
        }
        pushState();
    }

    @Override protected void onDestroy() {
        if (videoActive) videoView.stopPlayback();
        metadataExecutor.shutdown();
        if (webView != null) {
            webReady = false;
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    private final class PlayerBridge {
        @JavascriptInterface public String getLibrary() { return readLibrary(); }
        @JavascriptInterface public String getPreferences() {
            JSONObject out = new JSONObject();
            try {
                out.put("lastId", prefs.getString("lastId", ""));
                out.put("shuffle", prefs.getBoolean("shuffle", false));
                out.put("repeat", prefs.getInt("repeat", 0));
                out.put("speed", prefs.getFloat("speed", 1f));
            } catch (Exception ignored) { }
            return out.toString();
        }
        @JavascriptInterface public void importFiles() { runOnUiThread(MainActivity.this::launchImport); }
        @JavascriptInterface public void play(String id) { runOnUiThread(() -> MainActivity.this.play(id, false)); }
        @JavascriptInterface public void toggle(String id) { runOnUiThread(() -> MainActivity.this.play(id, true)); }
        @JavascriptInterface public void pause() { runOnUiThread(() -> {
            audioIntent(AudioPlaybackService.ACT_PAUSE, null, 0); pushState();
        }); }
        @JavascriptInterface public void next() {
            runOnUiThread(() -> skipAudio(1));
        }
        @JavascriptInterface public void previous() {
            runOnUiThread(() -> skipAudio(-1));
        }
        @JavascriptInterface public void seekTo(int pos) { runOnUiThread(() -> {
            audioIntent(AudioPlaybackService.ACT_SEEK, null, pos); pushState();
        }); }
        @JavascriptInterface public void requestState() { runOnUiThread(MainActivity.this::pushState); }
        @JavascriptInterface public void setOptions(boolean shuffle, int repeat, double speed) {
            runOnUiThread(() -> {
                prefs.edit().putBoolean("shuffle", shuffle)
                        .putInt("repeat", Math.max(0, Math.min(2, repeat)))
                        .putFloat("speed", (float)Math.max(.5, Math.min(2, speed))).apply();
                audioIntent(AudioPlaybackService.ACT_OPTIONS, null, 0);
                pushState();
            });
        }
        @JavascriptInterface public void toggleFavorite(String id) {
            runOnUiThread(() -> editTrack(id, false, true));
        }
        @JavascriptInterface public void remove(String id) {
            runOnUiThread(() -> editTrack(id, true, false));
        }
    }
}
