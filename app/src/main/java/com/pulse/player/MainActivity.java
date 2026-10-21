package com.pulse.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/* JADX INFO: loaded from: classes2.dex */
public final class MainActivity extends Activity {
    private static final String ASSET_HOST = "appassets.androidplatform.net";
    private static final String ASSET_ORIGIN = "https://appassets.androidplatform.net";
    private static final int REQUEST_IMPORT = 1001;
    private static final int REQUEST_MEDIA_PERMISSION = 1002;
    private static final String START_URL = "https://appassets.androidplatform.net/assets/index.html";
    private static final Pattern YT_ID = Pattern.compile("^[A-Za-z0-9_-]{11}$");
    private FrameLayout browserFrame;
    private TextView browserPrimary;
    private TextView browserFavorite;
    private TextView browserSecondary;
    private TextView browserTitle;
    private WebView browserView;
    private boolean browserVisible;
    private WebChromeClient.CustomViewCallback customCallback;
    private View customView;
    private SharedPreferences prefs;
    private FrameLayout root;
    private boolean scanning;
    private boolean videoActive;
    private FrameLayout videoFrame;
    private boolean videoPausedByLifecycle;
    private boolean videoPrepared;
    private TextView videoTitle;
    private VideoView videoView;
    private boolean webReady;
    private WebView webView;
    private String videoId = "";
    private String browserMode = "web";
    private String browserVideoId = "";
    private final Object libraryLock = new Object();
    private final ExecutorService metadataExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService netExecutor = Executors.newSingleThreadExecutor();
    private final Random random = new Random();

    @Override // android.app.Activity
    public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        this.prefs = getSharedPreferences("pulse", 0);
        // Previous OAuth credentials are not needed: Chrome owns account sign-in.
        if (this.prefs.contains("ytClientId") || this.prefs.contains("ytRefresh") ||
            this.prefs.contains("ytAccess") || this.prefs.contains("ytClientSecret")) {
            this.prefs.edit().remove("ytClientId").remove("ytClientSecret")
                .remove("ytClientType").remove("ytAccess").remove("ytRefresh")
                .remove("ytExpiry").remove("ytAccountName").remove("ytAccountThumb")
                .remove("ytLikes").remove("ytBackground").apply();
        }
        getWindow().setStatusBarColor(Color.rgb(11, 13, 21));
        getWindow().setNavigationBarColor(Color.rgb(11, 13, 21));
        createViews();
    }

    private void createViews() {
        this.root = new FrameLayout(this);
        this.root.setBackgroundColor(Color.rgb(11, 13, 21));
        if (Build.VERSION.SDK_INT >= 35) {
            this.root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() { // from class: com.pulse.player.MainActivity.1
                @Override // android.view.View.OnApplyWindowInsetsListener
                public WindowInsets onApplyWindowInsets(View view, WindowInsets windowInsets) {
                    MainActivity.this.root.setPadding(windowInsets.getSystemWindowInsetLeft(), windowInsets.getSystemWindowInsetTop(), windowInsets.getSystemWindowInsetRight(), windowInsets.getSystemWindowInsetBottom());
                    return windowInsets;
                }
            });
        }
        this.webView = new WebView(this);
        this.webView.setBackgroundColor(0);
        try {
            this.webView.setRendererPriorityPolicy(2, false);
        } catch (Exception e) {
        }
        WebSettings settings = this.webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        try {
            CookieManager.getInstance().setAcceptCookie(true);
            CookieManager.getInstance().setAcceptThirdPartyCookies(this.webView, true);
        } catch (Exception e2) {
        }
        this.webView.addJavascriptInterface(new PlayerBridge(), "Android");
        this.webView.setWebViewClient(new WebViewClient() { // from class: com.pulse.player.MainActivity.2
            @Override // android.webkit.WebViewClient
            public WebResourceResponse shouldInterceptRequest(WebView webView, WebResourceRequest webResourceRequest) {
                return MainActivity.this.serveAsset(webResourceRequest.getUrl());
            }

            @Override // android.webkit.WebViewClient
            public boolean shouldOverrideUrlLoading(WebView webView, WebResourceRequest webResourceRequest) {
                if (!webResourceRequest.isForMainFrame()) {
                    return false;
                }
                String string = webResourceRequest.getUrl().toString();
                if (string.startsWith("https://appassets.androidplatform.net/")) {
                    return false;
                }
                if (string.startsWith("http://") || string.startsWith("https://")) {
                    MainActivity.this.openBrowser(string, MainActivity.extractYouTubeId(string).isEmpty() ? "web" : "youtube");
                }
                return true;
            }

            @Override // android.webkit.WebViewClient
            public void onPageFinished(WebView webView, String str) {
                MainActivity.this.webReady = true;
                MainActivity.this.pushLibrary();
                MainActivity.this.pushState();
            }
        });
        this.webView.setWebChromeClient(new WebChromeClient() { // from class: com.pulse.player.MainActivity.3
            @Override // android.webkit.WebChromeClient
            public boolean onJsConfirm(WebView webView, String str, String str2, final JsResult jsResult) {
                new AlertDialog.Builder(MainActivity.this).setTitle("확인").setMessage(str2).setPositiveButton("확인", new DialogInterface.OnClickListener() { // from class: com.pulse.player.MainActivity.3.3
                    @Override // android.content.DialogInterface.OnClickListener
                    public void onClick(DialogInterface dialogInterface, int i) {
                        jsResult.confirm();
                    }
                }).setNegativeButton("취소", new DialogInterface.OnClickListener() { // from class: com.pulse.player.MainActivity.3.2
                    @Override // android.content.DialogInterface.OnClickListener
                    public void onClick(DialogInterface dialogInterface, int i) {
                        jsResult.cancel();
                    }
                }).setOnCancelListener(new DialogInterface.OnCancelListener() { // from class: com.pulse.player.MainActivity.3.1
                    @Override // android.content.DialogInterface.OnCancelListener
                    public void onCancel(DialogInterface dialogInterface) {
                        jsResult.cancel();
                    }
                }).show();
                return true;
            }

            @Override // android.webkit.WebChromeClient
            public void onShowCustomView(View view, WebChromeClient.CustomViewCallback customViewCallback) {
                MainActivity.this.showCustomView(view, customViewCallback);
            }

            @Override // android.webkit.WebChromeClient
            public void onHideCustomView() {
                MainActivity.this.hideCustomView();
            }
        });
        this.root.addView(this.webView, new FrameLayout.LayoutParams(-1, -1));
        this.videoFrame = new FrameLayout(this);
        this.videoFrame.setBackgroundColor(-16777216);
        this.videoFrame.setVisibility(8);
        this.videoView = new VideoView(this);
        this.videoFrame.addView(this.videoView, new FrameLayout.LayoutParams(-1, -1, 17));
        LinearLayout linearLayout = new LinearLayout(this);
        linearLayout.setOrientation(0);
        linearLayout.setGravity(16);
        linearLayout.setPadding(dp(12), dp(7), dp(10), dp(7));
        linearLayout.setBackgroundColor(Color.argb(190, 10, 10, 18));
        this.videoTitle = new TextView(this);
        this.videoTitle.setTextColor(-1);
        this.videoTitle.setTextSize(15.0f);
        this.videoTitle.setSingleLine(true);
        this.videoTitle.setEllipsize(TextUtils.TruncateAt.END);
        linearLayout.addView(this.videoTitle, new LinearLayout.LayoutParams(0, dp(46), 1.0f));
        linearLayout.addView(iconButton("❮", "이전 영상", new Runnable() { // from class: com.pulse.player.MainActivity.4
            @Override // java.lang.Runnable
            public void run() {
                MainActivity.this.skipVideo(-1, false);
            }
        }));
        linearLayout.addView(iconButton("❯", "다음 영상", new Runnable() { // from class: com.pulse.player.MainActivity.5
            @Override // java.lang.Runnable
            public void run() {
                MainActivity.this.skipVideo(1, false);
            }
        }));
        linearLayout.addView(iconButton("✕", "영상 닫기", new Runnable() { // from class: com.pulse.player.MainActivity.6
            @Override // java.lang.Runnable
            public void run() {
                MainActivity.this.closeVideo();
            }
        }));
        this.videoFrame.addView(linearLayout, new FrameLayout.LayoutParams(-1, -2, 48));
        this.root.addView(this.videoFrame, new FrameLayout.LayoutParams(-1, -1));
        setContentView(this.root);
        this.webView.loadUrl(START_URL);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public WebResourceResponse serveAsset(Uri uri) {
        if (uri == null || !ASSET_HOST.equals(uri.getHost())) {
            return null;
        }
        String path = uri.getPath();
        if (path == null || !path.startsWith("/assets/") || path.contains("..")) {
            return null;
        }
        String strSubstring = path.substring("/assets/".length());
        try {
            InputStream inputStreamOpen = getAssets().open(strSubstring);
            HashMap map = new HashMap();
            map.put("Cache-Control", "no-cache");
            map.put("Access-Control-Allow-Origin", ASSET_ORIGIN);
            return new WebResourceResponse(mimeFor(strSubstring), "utf-8", 200, "OK", map, inputStreamOpen);
        } catch (Exception e) {
            return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", null, null);
        }
    }

    private static String mimeFor(String str) {
        String lowerCase = str.toLowerCase(Locale.ROOT);
        if (lowerCase.endsWith(".html")) {
            return "text/html";
        }
        if (lowerCase.endsWith(".js")) {
            return "application/javascript";
        }
        if (lowerCase.endsWith(".css")) {
            return "text/css";
        }
        if (lowerCase.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (lowerCase.endsWith(".png")) {
            return "image/png";
        }
        return lowerCase.endsWith(".json") ? "application/json" : "application/octet-stream";
    }

    private TextView iconButton(String str, String str2, final Runnable runnable) {
        TextView textView = new TextView(this);
        textView.setText(str);
        textView.setTextSize(21.0f);
        textView.setTextColor(-1);
        textView.setGravity(17);
        textView.setContentDescription(str2);
        textView.setOnClickListener(new View.OnClickListener() { // from class: com.pulse.player.MainActivity.7
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                runnable.run();
            }
        });
        textView.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
        return textView;
    }

    private TextView pillButton(String str, final Runnable runnable) {
        TextView textView = new TextView(this);
        textView.setText(str);
        textView.setTextSize(13.0f);
        textView.setTextColor(Color.rgb(38, 22, 50));
        textView.setGravity(17);
        textView.setSingleLine(true);
        textView.setPadding(dp(12), 0, dp(12), 0);
        GradientDrawable gradientDrawable = new GradientDrawable();
        gradientDrawable.setCornerRadius(dp(11));
        gradientDrawable.setColor(Color.rgb(212, 172, 252));
        textView.setBackground(gradientDrawable);
        textView.setOnClickListener(new View.OnClickListener() { // from class: com.pulse.player.MainActivity.8
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                if (view.isEnabled()) {
                    runnable.run();
                }
            }
        });
        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(-2, dp(38));
        layoutParams.leftMargin = dp(6);
        textView.setLayoutParams(layoutParams);
        return textView;
    }

    private int dp(int i) {
        return Math.round(i * getResources().getDisplayMetrics().density);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public String readLibrary() {
        return this.prefs.getString("library", "[]");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public String readCollections() {
        return this.prefs.getString("collections", "{}");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void saveCollectionsJson(String str) {
        if (str != null && str.length() <= 3000000) {
            try {
                JSONObject jSONObject = new JSONObject(str);
                if ((jSONObject.opt("lists") instanceof JSONArray) && (jSONObject.opt("lyrics") instanceof JSONObject)) {
                    this.prefs.edit().putString("collections", jSONObject.toString()).apply();
                }
            } catch (Exception e) {
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public Set<String> readHidden() {
        HashSet hashSet = new HashSet();
        try {
            JSONArray jSONArray = new JSONArray(this.prefs.getString("hiddenDevice", "[]"));
            for (int i = 0; i < jSONArray.length(); i++) {
                hashSet.add(jSONArray.optString(i));
            }
        } catch (Exception e) {
        }
        return hashSet;
    }

    private void writeHidden(Set<String> set) {
        JSONArray jSONArray = new JSONArray();
        Iterator<String> it = set.iterator();
        while (it.hasNext()) {
            jSONArray.put(it.next());
        }
        this.prefs.edit().putString("hiddenDevice", jSONArray.toString()).apply();
    }

    private List<String> queueIds(String str) {
        JSONObject jSONObject = null;
        ArrayList arrayList = new ArrayList();
        JSONObject jSONObject2 = null;
        String strOptString = "";
        try {
            JSONArray jSONArray = new JSONArray(readLibrary());
            for (int i = 0; i < jSONArray.length(); i++) {
                JSONObject jSONObjectOptJSONObject = jSONArray.optJSONObject(i);
                if (jSONObjectOptJSONObject != null && str.equals(jSONObjectOptJSONObject.optString("type"))) {
                    arrayList.add(jSONObjectOptJSONObject.optString("id"));
                }
            }
            jSONObject = new JSONObject(readCollections());
            try {
                strOptString = jSONObject.optString("queueId");
            } catch (Exception e) {
                jSONObject2 = jSONObject;
                jSONObject = jSONObject2;
            }
        } catch (Exception e2) {
        }
        if (jSONObject == null || strOptString.isEmpty()) {
            return arrayList;
        }
        JSONArray jSONArrayOptJSONArray = jSONObject.optJSONArray("lists");
        if (jSONArrayOptJSONArray == null) {
            return arrayList;
        }
        for (int i2 = 0; i2 < jSONArrayOptJSONArray.length(); i2++) {
            JSONObject jSONObjectOptJSONObject2 = jSONArrayOptJSONArray.optJSONObject(i2);
            if (jSONObjectOptJSONObject2 != null && strOptString.equals(jSONObjectOptJSONObject2.optString("id"))) {
                JSONArray jSONArrayOptJSONArray2 = jSONObjectOptJSONObject2.optJSONArray("tracks");
                ArrayList arrayList2 = new ArrayList();
                if (jSONArrayOptJSONArray2 != null) {
                    for (int i3 = 0; i3 < jSONArrayOptJSONArray2.length(); i3++) {
                        String strOptString2 = jSONArrayOptJSONArray2.optString(i3);
                        if (arrayList.contains(strOptString2) && !arrayList2.contains(strOptString2)) {
                            arrayList2.add(strOptString2);
                        }
                    }
                    return arrayList2;
                }
                return arrayList2;
            }
        }
        return arrayList;
    }

    // A recovered-code regression used to return null for every song and video,
    // so library entries could be listed but no PLAY action ever reached MediaPlayer.
    private JSONObject findTrack(String id) {
        if (id == null || id.isEmpty()) return null;
        synchronized (this.libraryLock) {
            try {
                JSONArray library = new JSONArray(readLibrary());
                for (int i = 0; i < library.length(); i++) {
                    JSONObject item = library.optJSONObject(i);
                    if (item != null && id.equals(item.optString("id"))) {
                        return item;
                    }
                }
            } catch (Exception ignored) {
                // A corrupted library should not crash the player.
            }
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void js(String str) {
        if (this.webReady && this.webView != null) {
            this.webView.evaluateJavascript(str, null);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void jsOnUi(final String str) {
        runOnUiThread(new Runnable() { // from class: com.pulse.player.MainActivity.9
            @Override // java.lang.Runnable
            public void run() {
                MainActivity.this.js(str);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void pushLibrary() {
        if (this.webReady) {
            js("window.onNativeLibrary(" + readLibrary() + ");");
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void pushLibraryOnUi() {
        runOnUiThread(new Runnable() { // from class: com.pulse.player.MainActivity.10
            @Override // java.lang.Runnable
            public void run() {
                MainActivity.this.pushLibrary();
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void pushState() {
        JSONObject jSONObject;
        if (this.webReady) {
            AudioPlaybackService audioPlaybackService = AudioPlaybackService.instance;
            if (audioPlaybackService != null) {
                jSONObject = audioPlaybackService.snapshot();
            } else {
                jSONObject = new JSONObject();
                try {
                    jSONObject.put("id", this.prefs.getString("lastId", ""));
                    jSONObject.put("playing", false);
                    jSONObject.put("preparing", false);
                    jSONObject.put("position", 0);
                    jSONObject.put("duration", 0);
                    jSONObject.put("error", "");
                    jSONObject.put("shuffle", this.prefs.getBoolean("shuffle", false));
                    jSONObject.put("repeat", this.prefs.getInt("repeat", 0));
                    jSONObject.put("speed", this.prefs.getFloat("speed", 1.0f));
                    jSONObject.put("video", false);
                } catch (Exception e) {
                }
            }
            if (!this.videoActive && audioPlaybackService != null && !jSONObject.optBoolean("playing") && !jSONObject.optBoolean("preparing")) {
                String string = this.prefs.getString("lastId", "");
                if (!string.isEmpty() && !string.equals(jSONObject.optString("id"))) {
                    try {
                        jSONObject.put("id", string);
                        jSONObject.put("position", 0);
                        JSONObject jSONObjectFindTrack = findTrack(string);
                        jSONObject.put("duration", jSONObjectFindTrack != null ? jSONObjectFindTrack.optLong("duration", 0L) : 0L);
                    } catch (Exception e2) {
                    }
                }
            }
            if (this.videoActive) {
                try {
                    jSONObject.put("id", this.videoId);
                    jSONObject.put("playing", this.videoPrepared && this.videoView.isPlaying());
                    jSONObject.put("preparing", this.videoPrepared ? false : true);
                    jSONObject.put("position", this.videoPrepared ? this.videoView.getCurrentPosition() : 0);
                    jSONObject.put("duration", this.videoPrepared ? this.videoView.getDuration() : 0);
                    jSONObject.put("video", true);
                } catch (Exception e3) {
                }
            }
            js("window.onNativeState(" + jSONObject.toString() + ");");
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    private boolean hasAudioPermission() {
        return Build.VERSION.SDK_INT >= 33
            ? checkSelfPermission("android.permission.READ_MEDIA_AUDIO") == 0
            : checkSelfPermission("android.permission.READ_EXTERNAL_STORAGE") == 0;
    }

    private boolean hasMediaPermission() {
        if (hasAudioPermission()) return true;
        return Build.VERSION.SDK_INT >= 33 &&
            (checkSelfPermission("android.permission.READ_MEDIA_VIDEO") == 0 ||
            (Build.VERSION.SDK_INT >= 34 && checkSelfPermission("android.permission.READ_MEDIA_VISUAL_USER_SELECTED") == 0));
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void requestMediaPermission() {
        String[] strArr;
        if (Build.VERSION.SDK_INT >= 34) {
            strArr = new String[]{"android.permission.READ_MEDIA_AUDIO", "android.permission.READ_MEDIA_VIDEO", "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"};
        } else if (Build.VERSION.SDK_INT >= 33) {
            strArr = new String[]{"android.permission.READ_MEDIA_AUDIO", "android.permission.READ_MEDIA_VIDEO"};
        } else {
            strArr = new String[]{"android.permission.READ_EXTERNAL_STORAGE"};
        }
        requestPermissions(strArr, REQUEST_MEDIA_PERMISSION);
    }

    @Override // android.app.Activity
    public void onRequestPermissionsResult(int i, String[] strArr, int[] iArr) {
        super.onRequestPermissionsResult(i, strArr, iArr);
        if (i == REQUEST_MEDIA_PERMISSION) {
            boolean zHasMediaPermission = hasMediaPermission();
            boolean z = false;
            for (String str : strArr) {
                if (shouldShowRequestPermissionRationale(str)) {
                    z = true;
                }
            }
            js("window.onDevicePermission && window.onDevicePermission(" + zHasMediaPermission + "," + z + ");");
            if (zHasMediaPermission) {
                startDeviceScan(true);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void startDeviceScan(final boolean z) {
        if (!hasMediaPermission()) {
            jsOnUi("window.onDevicePermission && window.onDevicePermission(false,true);");
            return;
        }
        synchronized (this) {
            if (!this.scanning) {
                this.scanning = true;
                jsOnUi("window.onDeviceScan && window.onDeviceScan({state:'start'});");
                this.metadataExecutor.execute(new Runnable() { // from class: com.pulse.player.MainActivity.11
                    @Override // java.lang.Runnable
                    public void run() {
                        int[] iArrScanDeviceNow = {0, 0, 0};
                        try {
                            iArrScanDeviceNow = MainActivity.this.scanDeviceNow();
                        } catch (Exception e) {
                        }
                        synchronized (MainActivity.this) {
                            MainActivity.this.scanning = false;
                        }
                        MainActivity.this.prefs.edit().putLong("lastScan", System.currentTimeMillis()).apply();
                        final String str = "window.onDeviceScan && window.onDeviceScan({state:'done',audio:" + iArrScanDeviceNow[0] + ",video:" + iArrScanDeviceNow[1] + ",added:" + iArrScanDeviceNow[2] + ",announce:" + z + "});";
                        MainActivity.this.runOnUiThread(new Runnable() { // from class: com.pulse.player.MainActivity.11.1
                            @Override // java.lang.Runnable
                            public void run() {
                                MainActivity.this.pushLibrary();
                                MainActivity.this.js(str);
                            }
                        });
                    }
                });
            }
        }
    }

    // Preserve custom playlists, YouTube entries and favorites across library rescans.
    private int[] scanDeviceNow() {
        List<JSONObject> scanned = new ArrayList<>();
        int audioCount = queryStore(false, scanned);
        int videoCount = queryStore(true, scanned);
        synchronized (libraryLock) {
            try {
                JSONArray existing = new JSONArray(readLibrary());
                Set<String> hidden = readHidden();
                HashMap<String, JSONObject> oldDevice = new HashMap<>();
                Set<String> customFileKeys = new HashSet<>();
                JSONArray updated = new JSONArray();
                for (int i = 0; i < existing.length(); i++) {
                    JSONObject entry = existing.optJSONObject(i);
                    if (entry == null) continue;
                    if ("device".equals(entry.optString("source"))) {
                        oldDevice.put(entry.optString("id"), entry);
                    } else {
                        updated.put(entry);
                        if (!"youtube".equals(entry.optString("type"))) {
                            customFileKeys.add(entry.optString("fileName") + "|" + entry.optLong("size"));
                        }
                    }
                }
                int added = 0;
                for (JSONObject item : scanned) {
                    String id = item.optString("id");
                    if (hidden.contains(id) || customFileKeys.contains(item.optString("fileName") + "|" + item.optLong("size"))) continue;
                    JSONObject old = oldDevice.get(id);
                    if (old != null) {
                        item.put("favorite", old.optBoolean("favorite", false));
                    } else {
                        added++;
                    }
                    updated.put(item);
                }
                // Do not erase a previously scanned library following temporary permission loss.
                if (hasMediaPermission() || !scanned.isEmpty() || oldDevice.isEmpty()) {
                    prefs.edit().putString("library", updated.toString()).apply();
                    return new int[]{audioCount, videoCount, added};
                }
            } catch (Exception ignored) {
            }
        }
        return new int[]{audioCount, videoCount, 0};
    }

    private int queryStore(boolean video, List<JSONObject> output) {
        if (!video && !hasAudioPermission()) return 0;
        Uri baseUri = video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        List<String> projection = new ArrayList<>();
        String[] core = {"_id", "title", "_display_name", "_size", "duration", "date_added", "date_modified", "mime_type"};
        java.util.Collections.addAll(projection, core);
        if (!video) {
            projection.add("artist");
            projection.add("album");
        }
        if (Build.VERSION.SDK_INT >= 29) {
            projection.add("relative_path");
            projection.add("bucket_display_name");
        } else {
            projection.add("_data");
            if (video) projection.add("bucket_display_name");
        }
        String[] columns = projection.toArray(new String[0]);
        Cursor cursor = null;
        try {
            try {
                cursor = getContentResolver().query(baseUri, columns,
                    video ? null : "is_alarm = 0 AND is_notification = 0 AND is_ringtone = 0",
                    null, "date_added DESC");
            } catch (Exception e) {
                cursor = getContentResolver().query(baseUri, columns, null, null, null);
            }
            if (cursor == null) return 0;
            int idCol = cursor.getColumnIndex("_id");
            int titleCol = cursor.getColumnIndex("title");
            int fileCol = cursor.getColumnIndex("_display_name");
            int sizeCol = cursor.getColumnIndex("_size");
            int durationCol = cursor.getColumnIndex("duration");
            int addedCol = cursor.getColumnIndex("date_added");
            int changedCol = cursor.getColumnIndex("date_modified");
            int artistCol = cursor.getColumnIndex("artist");
            int albumCol = cursor.getColumnIndex("album");
            int pathCol = cursor.getColumnIndex("relative_path");
            int bucketCol = cursor.getColumnIndex("bucket_display_name");
            int legacyPathCol = cursor.getColumnIndex("_data");
            int count = 0;
            while (cursor.moveToNext() && count < 20000) {
                try {
                    if (idCol < 0 || cursor.isNull(idCol)) continue;
                    long duration = durationCol < 0 || cursor.isNull(durationCol) ? 0 : cursor.getLong(durationCol);
                    if (!video && duration > 0 && duration < 3000) continue;
                    long itemId = cursor.getLong(idCol);
                    String filename = str(cursor, fileCol);
                    String title = str(cursor, titleCol);
                    if (title.isEmpty()) title = filename.replaceFirst("(?i)\\.[^/.]+$", "");
                    if (title.isEmpty()) title = video ? "동영상" : "음악";
                    String artist = str(cursor, artistCol);
                    if ("<unknown>".equalsIgnoreCase(artist)) artist = "";
                    String folder = str(cursor, bucketCol);
                    if (folder.isEmpty()) {
                        String path = str(cursor, pathCol).replaceAll("/+$", "");
                        if (path.isEmpty()) path = str(cursor, legacyPathCol);
                        int slash = path.lastIndexOf('/');
                        folder = slash >= 0 ? path.substring(0, slash) : path;
                        int sub = folder.lastIndexOf('/');
                        if (sub >= 0) folder = folder.substring(sub + 1);
                    }
                    if (artist.isEmpty()) artist = folder.isEmpty() ? (video ? "내 영상" : "내 음악") : folder;
                    long added = addedCol < 0 || cursor.isNull(addedCol) ? 0 : cursor.getLong(addedCol);
                    long modified = changedCol < 0 || cursor.isNull(changedCol) ? 0 : cursor.getLong(changedCol);
                    JSONObject item = new JSONObject();
                    item.put("id", Uri.withAppendedPath(baseUri, String.valueOf(itemId)).toString());
                    item.put("name", title);
                    item.put("fileName", filename);
                    item.put("artist", artist);
                    String album = str(cursor, albumCol);
                    item.put("album", "<unknown>".equalsIgnoreCase(album) ? "" : album);
                    item.put("folder", folder);
                    item.put("type", video ? "video" : "audio");
                    item.put("duration", duration);
                    item.put("size", sizeCol < 0 || cursor.isNull(sizeCol) ? 0L : cursor.getLong(sizeCol));
                    item.put("favorite", false);
                    item.put("addedAt", added > 0 ? 1000L * added : (modified > 0 ? 1000L * modified : System.currentTimeMillis()));
                    item.put("modifiedAt", 1000L * modified);
                    item.put("source", "device");
                    output.add(item);
                    count++;
                } catch (Exception ignored) {
                    // Malformed metadata must not prevent scanning remaining media.
                }
            }
            return count;
        } catch (Exception e) {
            return 0;
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    private static String str(Cursor cursor, int i) {
        String string;
        return (i < 0 || cursor.isNull(i) || (string = cursor.getString(i)) == null) ? "" : string.trim();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void launchImport() {
        Intent intent = new Intent("android.intent.action.OPEN_DOCUMENT");
        intent.addCategory("android.intent.category.OPENABLE");
        intent.setType("*/*");
        intent.putExtra("android.intent.extra.MIME_TYPES", new String[]{"audio/*", "video/*"});
        intent.putExtra("android.intent.extra.ALLOW_MULTIPLE", true);
        intent.addFlags(65);
        try {
            startActivityForResult(intent, REQUEST_IMPORT);
        } catch (Exception e) {
            Toast.makeText(this, "파일 선택기를 실행할 수 없습니다.", 1).show();
        }
    }

    @Override // android.app.Activity
    protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);
        if (requestCode != REQUEST_IMPORT || resultCode != Activity.RESULT_OK || intent == null) return;
        LinkedHashSet<Uri> selected = new LinkedHashSet<>();
        if (intent.getData() != null) selected.add(intent.getData());
        ClipData clips = intent.getClipData();
        if (clips != null) {
            for (int i = 0; i < clips.getItemCount(); i++) {
                Uri uri = clips.getItemAt(i).getUri();
                if (uri != null) selected.add(uri);
            }
        }
        if (selected.isEmpty()) return;
        for (Uri uri : selected) {
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) {
                // Some document providers supply a session grant rather than a persistable grant.
            }
        }
        final List<Uri> selectedUris = new ArrayList<>(selected);
        metadataExecutor.execute(() -> {
            int added = 0;
            synchronized (libraryLock) {
                try {
                    JSONArray library = new JSONArray(readLibrary());
                    HashSet<String> known = new HashSet<>();
                    for (int i = 0; i < library.length(); i++) {
                        JSONObject item = library.optJSONObject(i);
                        if (item != null) known.add(item.optString("id"));
                    }
                    for (Uri uri : selectedUris) {
                        String id = uri.toString();
                        if (known.contains(id)) continue;
                        JSONObject metadata = metadata(uri);
                        if (metadata != null) {
                            library.put(metadata);
                            known.add(id);
                            added++;
                        }
                    }
                    prefs.edit().putString("library", library.toString()).apply();
                } catch (Exception ignored) {
                }
            }
            final int count = added;
            runOnUiThread(() -> {
                pushLibrary();
                Toast.makeText(MainActivity.this, count == 0
                    ? "새로 추가할 파일이 없습니다."
                    : count + "개 파일을 라이브러리에 추가했습니다.", Toast.LENGTH_SHORT).show();
            });
        });
    }

    private JSONObject metadata(Uri uri) {
        String filename = "미디어 파일";
        long size = 0L;
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{"_display_name", "_size"}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int fileColumn = cursor.getColumnIndex("_display_name");
                int sizeColumn = cursor.getColumnIndex("_size");
                if (fileColumn >= 0 && !cursor.isNull(fileColumn)) filename = cursor.getString(fileColumn);
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) size = cursor.getLong(sizeColumn);
            }
        } catch (Exception ignored) {
        }
        if (filename == null || filename.trim().isEmpty()) filename = "미디어 파일";
        String contentType = "";
        try {
            contentType = getContentResolver().getType(uri);
        } catch (Exception ignored) {
        }
        String mime = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        String lowerName = filename.toLowerCase(Locale.ROOT);
        boolean video = mime.startsWith("video/") || lowerName.matches(".*\\.(mp4|m4v|mkv|webm|3gp|mov|avi)$");
        boolean audio = mime.startsWith("audio/") || lowerName.matches(".*\\.(mp3|m4a|aac|wav|flac|ogg|opus|wma|amr)$");
        if (!audio && !video) return null;
        String title = filename.replaceFirst("(?i)\\.[^/.]+$", "");
        String artist = video ? "내 영상" : "내 음악";
        long duration = 0L;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(this, uri);
            String length = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (length != null) duration = Long.parseLong(length);
            String mediaTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
            String mediaArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
            if (mediaTitle != null && !mediaTitle.trim().isEmpty()) title = mediaTitle.trim();
            if (mediaArtist != null && !mediaArtist.trim().isEmpty()) artist = mediaArtist.trim();
        } catch (Exception ignored) {
        } finally {
            try { retriever.release(); } catch (Exception ignored) {}
        }
        try {
            JSONObject item = new JSONObject();
            item.put("id", uri.toString());
            item.put("name", title);
            item.put("fileName", filename);
            item.put("artist", artist);
            item.put("type", video ? "video" : "audio");
            item.put("duration", duration);
            item.put("size", size);
            item.put("favorite", false);
            item.put("addedAt", System.currentTimeMillis());
            item.put("source", "import");
            return item;
        } catch (Exception ignored) {
            return null;
        }
    }

    static String extractYouTubeId(String str) {
        if (str == null) {
            return "";
        }
        try {
            Uri uri = Uri.parse(str.trim());
            String lowerCase = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            List<String> pathSegments = uri.getPathSegments();
            String str2 = "";
            if (lowerCase.endsWith("youtu.be")) {
                if (!pathSegments.isEmpty()) {
                    str2 = pathSegments.get(0);
                }
            } else if (lowerCase.endsWith("youtube.com") || lowerCase.endsWith("youtube-nocookie.com")) {
                String queryParameter = uri.getQueryParameter("v");
                if (queryParameter != null) {
                    str2 = queryParameter;
                } else if (pathSegments.size() >= 2 && ("shorts".equals(pathSegments.get(0)) || "embed".equals(pathSegments.get(0)) || "live".equals(pathSegments.get(0)) || "v".equals(pathSegments.get(0)))) {
                    str2 = pathSegments.get(1);
                }
            }
            if (YT_ID.matcher(str2).matches()) {
                return str2;
            }
        } catch (Exception e) {
        }
        String strTrim = str.trim();
        return !YT_ID.matcher(strTrim).matches() ? "" : strTrim;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public String addYouTubeItem(String str, String str2, String str3, boolean z) {
        JSONObject jSONObject;
        boolean z2 = false;
        if (str == null || !YT_ID.matcher(str).matches()) {
            return "";
        }
        String str4 = "yt:" + str;
        synchronized (this.libraryLock) {
            try {
                JSONArray jSONArray = new JSONArray(readLibrary());
                int i = 0;
                while (true) {
                    if (i >= jSONArray.length()) {
                        jSONObject = null;
                        break;
                    }
                    jSONObject = jSONArray.optJSONObject(i);
                    if (jSONObject != null && str4.equals(jSONObject.optString("id"))) {
                        break;
                    }
                    i++;
                }
                if (jSONObject == null) {
                    jSONObject = new JSONObject();
                    jSONObject.put("id", str4);
                    jSONObject.put("videoId", str);
                    jSONObject.put("name", (str2 == null || str2.trim().isEmpty()) ? "YouTube 영상" : str2.trim());
                    jSONObject.put("artist", (str3 == null || str3.trim().isEmpty()) ? "YouTube" : str3.trim());
                    jSONObject.put("fileName", "youtube.com/watch?v=" + str);
                    jSONObject.put("type", "youtube");
                    jSONObject.put("source", "youtube");
                    jSONObject.put("duration", 0);
                    jSONObject.put("size", 0);
                    jSONObject.put("favorite", z);
                    jSONObject.put("addedAt", System.currentTimeMillis());
                    jSONObject.put("thumb", "https://i.ytimg.com/vi/" + str + "/mqdefault.jpg");
                    jSONArray.put(jSONObject);
                    this.prefs.edit().putString("library", jSONArray.toString()).apply();
                    z2 = true;
                }
            } catch (Exception e) {
                return "";
            }
        }
        if (z2 && (str2 == null || str2.trim().isEmpty() || str3 == null || str3.trim().isEmpty())) {
            fetchYouTubeMeta(str);
        }
        return jSONObject.toString();
    }

    private void fetchYouTubeMeta(final String str) {
        this.netExecutor.execute(new Runnable() { // from class: com.pulse.player.MainActivity.13
            @Override // java.lang.Runnable
            public void run() {
                HttpURLConnection httpURLConnection = null;
                try {
                    httpURLConnection = (HttpURLConnection) new URL("https://www.youtube.com/oembed?format=json&url=" + URLEncoder.encode("https://www.youtube.com/watch?v=" + str, "UTF-8")).openConnection();
                    httpURLConnection.setConnectTimeout(8000);
                    httpURLConnection.setReadTimeout(8000);
                    if (httpURLConnection.getResponseCode() == 200) {
                        InputStream inputStream = httpURLConnection.getInputStream();
                        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                        byte[] bArr = new byte[8192];
                        while (true) {
                            int i = inputStream.read(bArr);
                            if (i <= 0 || byteArrayOutputStream.size() >= 200000) {
                                break;
                            } else {
                                byteArrayOutputStream.write(bArr, 0, i);
                            }
                        }
                        inputStream.close();
                        JSONObject jSONObject = new JSONObject(byteArrayOutputStream.toString("UTF-8"));
                        if (MainActivity.this.updateTrackMeta("yt:" + str, jSONObject.optString("title", ""), jSONObject.optString("author_name", ""), -1L)) {
                            MainActivity.this.pushLibraryOnUi();
                        }
                        if (httpURLConnection != null) {
                            return;
                        } else {
                            return;
                        }
                    }
                } catch (Exception e) {
                } finally {
                    if (httpURLConnection != null) {
                        httpURLConnection.disconnect();
                    }
                }
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean updateTrackMeta(String str, String str2, String str3, long j) {
        boolean z;
        boolean z2 = true;
        synchronized (this.libraryLock) {
            try {
                JSONArray jSONArray = new JSONArray(readLibrary());
                for (int i = 0; i < jSONArray.length(); i++) {
                    JSONObject jSONObjectOptJSONObject = jSONArray.optJSONObject(i);
                    if (jSONObjectOptJSONObject != null && str.equals(jSONObjectOptJSONObject.optString("id"))) {
                        if (str2 == null || str2.trim().isEmpty() || str2.trim().equals(jSONObjectOptJSONObject.optString("name"))) {
                            z = false;
                        } else {
                            jSONObjectOptJSONObject.put("name", str2.trim());
                            z = true;
                        }
                        if (str3 != null && !str3.trim().isEmpty() && !str3.trim().equals(jSONObjectOptJSONObject.optString("artist"))) {
                            jSONObjectOptJSONObject.put("artist", str3.trim());
                            z = true;
                        }
                        if (j <= 0 || Math.abs(j - jSONObjectOptJSONObject.optLong("duration")) <= 1500) {
                            z2 = z;
                        } else {
                            jSONObjectOptJSONObject.put("duration", j);
                        }
                        if (z2) {
                            this.prefs.edit().putString("library", jSONArray.toString()).apply();
                        }
                        return z2;
                    }
                }
            } catch (Exception e) {
            }
            return false;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void editTrack(String str, boolean z, boolean z2) {
        boolean zEquals;
        boolean z3;
        synchronized (this.libraryLock) {
            try {
                JSONArray jSONArray = new JSONArray(readLibrary());
                JSONArray jSONArray2 = new JSONArray();
                zEquals = false;
                z3 = false;
                for (int i = 0; i < jSONArray.length(); i++) {
                    try {
                        JSONObject jSONObjectOptJSONObject = jSONArray.optJSONObject(i);
                        if (jSONObjectOptJSONObject != null) {
                            if (!str.equals(jSONObjectOptJSONObject.optString("id"))) {
                                jSONArray2.put(jSONObjectOptJSONObject);
                            } else if (z) {
                                try {
                                    zEquals = "device".equals(jSONObjectOptJSONObject.optString("source"));
                                    z3 = true;
                                } catch (Exception e) {
                                    z3 = true;
                                }
                            } else {
                                if (z2) {
                                    jSONObjectOptJSONObject.put("favorite", !jSONObjectOptJSONObject.optBoolean("favorite"));
                                    z3 = true;
                                }
                                jSONArray2.put(jSONObjectOptJSONObject);
                            }
                        }
                    } catch (Exception e2) {
                    }
                }
                if (z3) {
                    this.prefs.edit().putString("library", jSONArray2.toString()).apply();
                }
                if (z && zEquals) {
                    Set<String> hidden = readHidden();
                    hidden.add(str);
                    writeHidden(hidden);
                }
            } catch (Exception e3) {
                zEquals = false;
                z3 = false;
            }
        }
        if (z3) {
            if (z) {
                if (this.videoActive && this.videoId.equals(str)) {
                    closeVideo();
                }
                AudioPlaybackService audioPlaybackService = AudioPlaybackService.instance;
                if (audioPlaybackService != null && audioPlaybackService.snapshot().optString("id").equals(str)) {
                    audioIntent(AudioPlaybackService.ACT_STOP, null, 0);
                }
                if (this.prefs.getString("lastId", "").equals(str)) {
                    this.prefs.edit().remove("lastId").apply();
                }
                if (!zEquals && str.startsWith("content://")) {
                    try {
                        getContentResolver().releasePersistableUriPermission(Uri.parse(str), 1);
                    } catch (Exception e4) {
                    }
                }
            }
            pushLibrary();
            pushState();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void audioIntent(String action, String id, int position) {
        Intent intent = new Intent(this, AudioPlaybackService.class).setAction(action);
        if (id != null) intent.putExtra("id", id);
        intent.putExtra("position", position);
        try {
            if (AudioPlaybackService.ACT_PLAY.equals(action)) {
                // Required for Android 8+ and for consistent foreground playback.
                startForegroundService(intent);
            } else if (AudioPlaybackService.instance != null) {
                startService(intent);
            }
        } catch (Exception e) {
            String message = "오디오 재생 서비스를 시작하지 못했습니다 (" + e.getClass().getSimpleName() + ")";
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            js("window.onNativePlaybackFailure && window.onNativePlaybackFailure(" + JSONObject.quote(message) + ");");
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void play(String str, boolean z) {
        JSONObject jSONObjectFindTrack = findTrack(str);
        if (jSONObjectFindTrack == null) {
            String error = "선택한 음악·영상이 라이브러리에서 확인되지 않습니다. 다시 추가해 주세요.";
            Toast.makeText(this, error, Toast.LENGTH_LONG).show();
            js("window.onNativePlaybackFailure && window.onNativePlaybackFailure(" + JSONObject.quote(error) + ");");
            return;
        }
        String strOptString = jSONObjectFindTrack.optString("type");
        if (!"youtube".equals(strOptString)) {
            if ("video".equals(strOptString)) {
                showVideo(str);
                return;
            }
            if ("device".equals(jSONObjectFindTrack.optString("source")) && !hasAudioPermission()) {
                Toast.makeText(this, "음악 파일 접근 권한이 필요합니다. 권한을 허용한 뒤 다시 재생하세요.", Toast.LENGTH_LONG).show();
                requestMediaPermission();
                return;
            }
            if (this.videoActive) {
                closeVideo();
            }
            AudioPlaybackService audioPlaybackService = AudioPlaybackService.instance;
            if (!z || audioPlaybackService == null || !str.equals(audioPlaybackService.snapshot().optString("id"))) {
                audioIntent(AudioPlaybackService.ACT_PLAY, str, 0);
            } else {
                audioIntent(AudioPlaybackService.ACT_TOGGLE, null, 0);
            }
            pushState();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void skipAudio(int i) {
        if (AudioPlaybackService.instance != null) {
            audioIntent(i > 0 ? AudioPlaybackService.ACT_NEXT : AudioPlaybackService.ACT_PREVIOUS, null, 0);
            pushState();
            return;
        }
        List<String> listQueueIds = queueIds("audio");
        if (!listQueueIds.isEmpty()) {
            int iIndexOf = listQueueIds.indexOf(this.prefs.getString("lastId", ""));
            play(listQueueIds.get(iIndexOf < 0 ? 0 : ((iIndexOf + i) + listQueueIds.size()) % listQueueIds.size()), false);
        }
    }

    private void showVideo(String str) {
        JSONObject jSONObjectFindTrack = findTrack(str);
        if (jSONObjectFindTrack != null) {
            audioIntent(AudioPlaybackService.ACT_PAUSE, null, 0);
            js("window.onNativeVideoStart && window.onNativeVideoStart();");
            if (this.videoActive) {
                this.videoView.stopPlayback();
            }
            this.videoId = str;
            this.prefs.edit().putString("lastId", str).apply();
            this.videoPausedByLifecycle = false;
            this.videoPrepared = false;
            this.videoActive = true;
            this.videoFrame.setVisibility(0);
            this.videoFrame.bringToFront();
            if (this.browserFrame != null && this.browserVisible) {
                this.browserFrame.bringToFront();
            }
            this.videoTitle.setText(jSONObjectFindTrack.optString("name", "동영상"));
            setRequestedOrientation(4);
            getWindow().addFlags(128);
            final MediaController mediaController = new MediaController(this);
            mediaController.setAnchorView(this.videoView);
            this.videoView.setMediaController(mediaController);
            this.videoView.setOnPreparedListener(new MediaPlayer.OnPreparedListener() { // from class: com.pulse.player.MainActivity.14
                @Override // android.media.MediaPlayer.OnPreparedListener
                public void onPrepared(MediaPlayer mediaPlayer) {
                    MainActivity.this.videoPrepared = true;
                    MainActivity.this.videoView.start();
                    float f = MainActivity.this.prefs.getFloat("speed", 1.0f);
                    if (f != 1.0f) {
                        try {
                            mediaPlayer.setPlaybackParams(mediaPlayer.getPlaybackParams().setSpeed(f));
                        } catch (Exception e) {
                        }
                    }
                    mediaController.show(2500);
                    MainActivity.this.pushState();
                }
            });
            this.videoView.setOnCompletionListener(new MediaPlayer.OnCompletionListener() { // from class: com.pulse.player.MainActivity.15
                @Override // android.media.MediaPlayer.OnCompletionListener
                public void onCompletion(MediaPlayer mediaPlayer) {
                    if (MainActivity.this.prefs.getInt("repeat", 0) == 2) {
                        MainActivity.this.videoView.seekTo(0);
                        MainActivity.this.videoView.start();
                    } else {
                        MainActivity.this.skipVideo(1, true);
                    }
                }
            });
            this.videoView.setOnErrorListener(new MediaPlayer.OnErrorListener() { // from class: com.pulse.player.MainActivity.16
                @Override // android.media.MediaPlayer.OnErrorListener
                public boolean onError(MediaPlayer mediaPlayer, int i, int i2) {
                    Toast.makeText(MainActivity.this, "이 영상을 재생할 수 없습니다.", 1).show();
                    MainActivity.this.closeVideo();
                    return true;
                }
            });
            this.videoView.setVideoURI(Uri.parse(str));
            this.videoView.requestFocus();
            pushState();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void skipVideo(int i, boolean z) {
        int size = 0;
        List<String> listQueueIds = queueIds("video");
        if (listQueueIds.isEmpty()) {
            closeVideo();
            return;
        }
        int iIndexOf = listQueueIds.indexOf(this.videoId);
        if (this.prefs.getBoolean("shuffle", false) && listQueueIds.size() > 1) {
            size = this.random.nextInt(listQueueIds.size() - 1);
            if (iIndexOf >= 0 && size >= iIndexOf) {
                size++;
            }
        } else if (iIndexOf >= 0) {
            int i2 = iIndexOf + i;
            if (i2 < listQueueIds.size()) {
                size = i2;
            } else if (z && this.prefs.getInt("repeat", 0) != 1) {
                closeVideo();
                return;
            }
            if (size < 0) {
                size = listQueueIds.size() - 1;
            }
        }
        showVideo(listQueueIds.get(size));
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void closeVideo() {
        if (this.videoActive) {
            this.videoActive = false;
            this.videoPrepared = false;
            this.videoView.stopPlayback();
            this.videoFrame.setVisibility(8);
            this.videoId = "";
            setRequestedOrientation(-1);
            getWindow().clearFlags(128);
            pushState();
        }
    }

    private void ensureBrowser() {
        if (this.browserFrame == null) {
            this.browserFrame = new FrameLayout(this);
            this.browserFrame.setBackgroundColor(Color.rgb(11, 13, 21));
            this.browserFrame.setVisibility(8);
            LinearLayout linearLayout = new LinearLayout(this);
            linearLayout.setOrientation(1);
            LinearLayout linearLayout2 = new LinearLayout(this);
            linearLayout2.setOrientation(0);
            linearLayout2.setGravity(16);
            linearLayout2.setPadding(dp(4), dp(6), dp(8), dp(6));
            linearLayout2.setBackgroundColor(Color.rgb(28, 22, 40));
            linearLayout2.addView(iconButton("❮", "뒤로", new Runnable() { // from class: com.pulse.player.MainActivity.17
                @Override // java.lang.Runnable
                public void run() {
                    if (MainActivity.this.browserView.canGoBack()) {
                        MainActivity.this.browserView.goBack();
                    } else {
                        MainActivity.this.closeBrowser();
                    }
                }
            }));
            this.browserTitle = new TextView(this);
            this.browserTitle.setTextColor(Color.rgb(236, 226, 252));
            this.browserTitle.setTextSize(13.0f);
            this.browserTitle.setSingleLine(true);
            this.browserTitle.setEllipsize(TextUtils.TruncateAt.END);
            linearLayout2.addView(this.browserTitle, new LinearLayout.LayoutParams(0, -2, 1.0f));
            this.browserSecondary = pillButton("＋ 담기", new Runnable() { // from class: com.pulse.player.MainActivity.18
                @Override // java.lang.Runnable
                public void run() {
                    MainActivity.this.browserAction(false);
                }
            });
            this.browserPrimary = pillButton("▶ 앱에서 재생", new Runnable() { // from class: com.pulse.player.MainActivity.19
                @Override // java.lang.Runnable
                public void run() {
                    MainActivity.this.browserAction(true);
                }
            });
            linearLayout2.addView(this.browserSecondary);
            linearLayout2.addView(this.browserPrimary);
            this.browserFavorite = pillButton("♡", new Runnable() {
                @Override public void run() { MainActivity.this.browserFavoriteAction(); }
            });
            this.browserFavorite.setTextSize(18.0f);
            this.browserFavorite.setContentDescription("현재 YouTube 영상 즐겨찾기 추가 또는 해제");
            this.browserFavorite.setPadding(dp(8), 0, dp(8), 0);
            linearLayout2.addView(this.browserFavorite);
            linearLayout2.addView(iconButton("✕", "닫기", new Runnable() { // from class: com.pulse.player.MainActivity.20
                @Override // java.lang.Runnable
                public void run() {
                    MainActivity.this.closeBrowser();
                }
            }));
            linearLayout.addView(linearLayout2, new LinearLayout.LayoutParams(-1, -2));
            this.browserView = new WebView(this);
            WebSettings settings = this.browserView.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setAllowFileAccess(false);
            settings.setAllowContentAccess(false);
            settings.setMediaPlaybackRequiresUserGesture(false);
            settings.setLoadWithOverviewMode(true);
            settings.setUseWideViewPort(true);
            try {
                CookieManager.getInstance().setAcceptThirdPartyCookies(this.browserView, true);
            } catch (Exception e) {
            }
            this.browserView.setWebViewClient(new WebViewClient() { // from class: com.pulse.player.MainActivity.21
                @Override // android.webkit.WebViewClient
                public boolean shouldOverrideUrlLoading(WebView webView, WebResourceRequest webResourceRequest) {
                    String string = webResourceRequest.getUrl().toString();
                    if (string.startsWith("http://") || string.startsWith("https://")) {
                        Uri target = Uri.parse(string);
                        String host = target.getHost() == null ? "" : target.getHost().toLowerCase(Locale.ROOT);
                        // Browsing and playback are allowed in the app's YouTube WebView.
                        // Google account authentication must stay in Chrome: WebView
                        // cannot read Chrome login cookies or perform unsupported OAuth.
                        if (host.equals("accounts.google.com") || host.endsWith(".accounts.google.com")) {
                            MainActivity.this.openInChrome(string);
                            return true;
                        }
                        return false;
                    }
                    if (!string.startsWith("intent://")) {
                        return true;
                    }
                    try {
                        String stringExtra = Intent.parseUri(string, 1).getStringExtra("browser_fallback_url");
                        if (stringExtra == null || !stringExtra.startsWith("http")) {
                            return true;
                        }
                        webView.loadUrl(stringExtra);
                        return true;
                    } catch (Exception e2) {
                        return true;
                    }
                }

                @Override // android.webkit.WebViewClient
                public void doUpdateVisitedHistory(WebView webView, String str, boolean z) {
                    MainActivity.this.updateBrowserState(str);
                }

                @Override // android.webkit.WebViewClient
                public void onPageFinished(WebView webView, String str) {
                    MainActivity.this.updateBrowserState(str);
                }
            });
            this.browserView.setWebChromeClient(new WebChromeClient() { // from class: com.pulse.player.MainActivity.22
                @Override // android.webkit.WebChromeClient
                public void onReceivedTitle(WebView webView, String str) {
                    MainActivity.this.updateBrowserState(webView.getUrl());
                }

                @Override // android.webkit.WebChromeClient
                public void onShowCustomView(View view, WebChromeClient.CustomViewCallback customViewCallback) {
                    MainActivity.this.showCustomView(view, customViewCallback);
                }

                @Override // android.webkit.WebChromeClient
                public void onHideCustomView() {
                    MainActivity.this.hideCustomView();
                }
            });
            linearLayout.addView(this.browserView, new LinearLayout.LayoutParams(-1, 0, 1.0f));
            this.browserFrame.addView(linearLayout, new FrameLayout.LayoutParams(-1, -1));
            this.root.addView(this.browserFrame, new FrameLayout.LayoutParams(-1, -1));
        }
    }

    // Open the current video in YouTube's official app so that an eligible user
    // can use YouTube Premium offline downloads and ad-free playback there.
    private void openOfficialYouTubeVideo(String videoId) {
        if (videoId == null || !YT_ID.matcher(videoId).matches()) {
            Toast.makeText(this, "YouTube 영상 주소를 확인해 주세요.", Toast.LENGTH_SHORT).show();
            return;
        }
        Uri uri = Uri.parse("https://www.youtube.com/watch?v=" + videoId);
        Intent intent = new Intent(Intent.ACTION_VIEW, uri);
        intent.setPackage("com.google.android.youtube");
        try {
            startActivity(intent);
        } catch (Exception ignored) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, uri));
            } catch (Exception e) {
                Toast.makeText(this, "YouTube를 열 수 없습니다.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private static boolean isRestrictedVideoHost(String host) {
        if (host == null) return true;
        String h = host.toLowerCase(Locale.ROOT);
        String[] domains = {"youtube.com", "youtube-nocookie.com", "youtu.be", "googlevideo.com", "ytimg.com"};
        for (String domain : domains) {
            if (h.equals(domain) || h.endsWith("." + domain)) return true;
        }
        return false;
    }

    // Only user-supplied, HTTPS, directly hosted media files are downloaded.
    // This is deliberately not a YouTube downloader, ad interceptor or stream extractor.
    private void downloadAuthorizedMediaFile(String address) {
        try {
            if (address == null || address.length() > 2048) throw new IllegalArgumentException();
            Uri uri = Uri.parse(address.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null || isRestrictedVideoHost(uri.getHost())) {
                throw new IllegalArgumentException();
            }
            String rawName = uri.getLastPathSegment();
            if (rawName == null || rawName.length() < 5 || rawName.length() > 160) throw new IllegalArgumentException();
            if (!rawName.toLowerCase(Locale.ROOT).matches("^[^/]{1,160}\\.(mp4|m4v|mov|webm|mp3|m4a|aac|wav|flac|ogg|opus)$")) {
                throw new IllegalArgumentException();
            }
            String filename = System.currentTimeMillis() + "_" + rawName.replaceAll("[^\\p{L}\\p{N}._-]", "_");
            String ext = rawName.substring(rawName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
            String mime;
            switch (ext) {
                case "mp3": mime = "audio/mpeg"; break;
                case "m4a": mime = "audio/mp4"; break;
                case "aac": mime = "audio/aac"; break;
                case "wav": mime = "audio/wav"; break;
                case "flac": mime = "audio/flac"; break;
                case "ogg": mime = "audio/ogg"; break;
                case "opus": mime = "audio/opus"; break;
                case "webm": mime = "video/webm"; break;
                case "mov": mime = "video/quicktime"; break;
                default: mime = "video/mp4"; break;
            }
            DownloadManager.Request request = new DownloadManager.Request(uri);
            request.setTitle(rawName);
            request.setDescription("Pulse Player · 미디어 파일 저장");
            request.setMimeType(mime);
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            if (Build.VERSION.SDK_INT >= 29) {
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "PulsePlayer/" + filename);
            } else {
                request.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, filename);
            }
            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) throw new IllegalStateException();
            dm.enqueue(request);
            Toast.makeText(this, "다운로드를 시작했습니다. 알림에서 진행 상황을 확인하세요.", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "저장할 수 없습니다. HTTPS 직접 파일 주소인지 확인해 주세요.", Toast.LENGTH_LONG).show();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void openBrowser(String str, String str2) {
        if (str != null) {
            if (str.startsWith("https://") || str.startsWith("http://")) {
                Uri target = Uri.parse(str);
                String host = target.getHost() == null ? "" : target.getHost().toLowerCase(Locale.ROOT);
                if ("youtube".equals(str2) || host.equals("youtube.com") || host.endsWith(".youtube.com") || host.equals("youtu.be")) {
                    closeBrowser();
                    openInChrome(str);
                    return;
                }
                ensureBrowser();
                if (str2 == null) {
                    str2 = "web";
                }
                this.browserMode = str2;
                this.browserVideoId = "";
                if ("lyrics".equals(this.browserMode)) {
                    this.browserSecondary.setVisibility(8);
                    this.browserPrimary.setVisibility(0);
                    this.browserPrimary.setText("선택한 가사 가져오기");
                    this.browserFavorite.setVisibility(8);
                } else if ("youtube".equals(this.browserMode)) {
                    this.browserSecondary.setVisibility(0);
                    this.browserSecondary.setText("＋ 담기");
                    this.browserPrimary.setVisibility(0);
                    this.browserPrimary.setText("▶ 앱에서 재생");
                    this.browserFavorite.setVisibility(0);
                } else {
                    this.browserSecondary.setVisibility(8);
                    this.browserPrimary.setVisibility(8);
                    this.browserFavorite.setVisibility(8);
                }
                this.browserView.onResume();
                this.browserView.loadUrl(str);
                this.browserFrame.setVisibility(0);
                this.browserFrame.bringToFront();
                this.browserVisible = true;
                updateBrowserState(str);
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateBrowserState(String str) {
        if (this.browserView != null) {
            String title = this.browserView.getTitle();
            if (title == null || title.isEmpty() || title.startsWith("http")) {
                title = str == null ? "" : str;
            }
            if ("lyrics".equals(this.browserMode)) {
                title = "가사를 길게 눌러 선택 → 가져오기";
            }
            this.browserTitle.setText(title);
            if ("youtube".equals(this.browserMode) || "web".equals(this.browserMode)) {
                this.browserVideoId = extractYouTubeId(str);
                boolean z = !this.browserVideoId.isEmpty();
                if ("web".equals(this.browserMode) && z) {
                    this.browserMode = "youtube";
                    this.browserSecondary.setVisibility(0);
                    this.browserPrimary.setVisibility(0);
                    this.browserSecondary.setText("＋ 담기");
                    this.browserPrimary.setText("▶ 앱에서 재생");
                    this.browserFavorite.setVisibility(0);
                }
                this.browserFavorite.setEnabled(z);
                this.browserFavorite.setAlpha(z ? 1.0f : 0.35f);
                updateBrowserFavorite();
                this.browserPrimary.setEnabled(z);
                this.browserSecondary.setEnabled(z);
                this.browserPrimary.setAlpha(z ? 1.0f : 0.35f);
                this.browserSecondary.setAlpha(z ? 1.0f : 0.35f);
                return;
            }
            this.browserPrimary.setEnabled(true);
            this.browserPrimary.setAlpha(1.0f);
        }
    }

    private void updateBrowserFavorite() {
        if (this.browserFavorite == null) return;
        String video = this.browserVideoId;
        boolean favorite = false;
        if (video != null && !video.isEmpty()) {
            try {
                JSONArray entries = new JSONArray(readLibrary());
                for (int i=0; i<entries.length(); i++) {
                    JSONObject row = entries.optJSONObject(i);
                    if (row != null && ("yt:" + video).equals(row.optString("id"))) {
                        favorite = row.optBoolean("favorite");
                        break;
                    }
                }
            } catch (Exception ignored) { }
        }
        this.browserFavorite.setText(favorite ? "♥" : "♡");
        this.browserFavorite.setContentDescription(favorite ? "YouTube 즐겨찾기 해제" : "YouTube 즐겨찾기 추가");
    }

    private void browserFavoriteAction() {
        final String video = this.browserVideoId;
        if (video == null || video.isEmpty()) {
            Toast.makeText(this, "영상을 먼저 선택하세요.", Toast.LENGTH_SHORT).show();
            return;
        }
        final String rawTitle = this.browserView == null ? "" : this.browserView.getTitle();
        String title = rawTitle == null ? "" : rawTitle.replaceFirst("\\s*[-–]\\s*YouTube\\s*$", "").trim();
        addYouTubeItem(video, title, "", false);
        editTrack("yt:" + video, false, true);
        updateBrowserFavorite();
        js("window.onYouTubeAdded && window.onYouTubeAdded('yt:" + video + "', false);");
        Toast.makeText(this, this.browserFavorite.getText().toString().equals("♥")
            ? "YouTube 영상을 즐겨찾기에 저장했어요." : "즐겨찾기에서 제외했어요.", Toast.LENGTH_SHORT).show();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void browserAction(final boolean z) {
        if ("lyrics".equals(this.browserMode)) {
            this.browserView.evaluateJavascript("(function(){try{var s=window.getSelection?String(window.getSelection()):'';return s;}catch(e){return ''}})()", new ValueCallback<String>() { // from class: com.pulse.player.MainActivity.23
                @Override // android.webkit.ValueCallback
                public void onReceiveValue(String str) {
                    String strTrim = MainActivity.decodeJsString(str).trim();
                    if (!strTrim.isEmpty()) {
                        MainActivity.this.closeBrowser();
                        MainActivity.this.js("window.onLyricsPicked && window.onLyricsPicked(" + JSONObject.quote(strTrim) + ");");
                    } else {
                        Toast.makeText(MainActivity.this, "가져올 가사를 길게 눌러 먼저 선택하세요. (또는 복사 후 앱에서 붙여넣기)", 1).show();
                    }
                }
            });
            return;
        }
        final String str = this.browserVideoId;
        if (str.isEmpty()) {
            Toast.makeText(this, "재생 중인 YouTube 영상 화면에서 눌러 주세요.", 0).show();
        } else {
            this.browserView.evaluateJavascript("document.title", new ValueCallback<String>() { // from class: com.pulse.player.MainActivity.24
                @Override // android.webkit.ValueCallback
                public void onReceiveValue(String str2) {
                    String strTrim = MainActivity.decodeJsString(str2).replaceFirst("\\s*[-–]\\s*YouTube\\s*$", "").trim();
                    if (strTrim.equalsIgnoreCase("YouTube")) {
                        strTrim = "";
                    }
                    if (!MainActivity.this.addYouTubeItem(str, strTrim, "", false).isEmpty()) {
                        MainActivity.this.pushLibrary();
                        if (z) {
                            MainActivity.this.closeBrowser();
                            MainActivity.this.js("window.onYouTubeAdded && window.onYouTubeAdded('yt:" + str + "', true);");
                        } else {
                            MainActivity.this.js("window.onYouTubeAdded && window.onYouTubeAdded('yt:" + str + "', false);");
                            Toast.makeText(MainActivity.this, "YouTube 목록에 담았어요.", 0).show();
                        }
                    }
                }
            });
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static String decodeJsString(String str) {
        if (str == null || "null".equals(str)) {
            return "";
        }
        try {
            return new JSONArray("[" + str + "]").optString(0, "");
        } catch (Exception e) {
            return str;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void closeBrowser() {
        if (this.browserFrame != null && this.browserVisible) {
            this.browserVisible = false;
            try {
                this.browserView.evaluateJavascript("document.querySelectorAll('video,audio').forEach(function(m){try{m.pause()}catch(e){}})", null);
            } catch (Exception e) {
            }
            this.browserView.onPause();
            this.browserFrame.setVisibility(8);
            hideCustomView();
            js("window.onBrowserClosed && window.onBrowserClosed();");
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void showCustomView(View view, WebChromeClient.CustomViewCallback customViewCallback) {
        if (this.customView != null) {
            customViewCallback.onCustomViewHidden();
            return;
        }
        this.customView = view;
        this.customCallback = customViewCallback;
        view.setBackgroundColor(-16777216);
        this.root.addView(view, new FrameLayout.LayoutParams(-1, -1));
        view.bringToFront();
        setRequestedOrientation(4);
        getWindow().getDecorView().setSystemUiVisibility(4102);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void hideCustomView() {
        if (this.customView != null) {
            ViewGroup viewGroup = (ViewGroup) this.customView.getParent();
            if (viewGroup != null) {
                viewGroup.removeView(this.customView);
            }
            this.customView = null;
            WebChromeClient.CustomViewCallback customViewCallback = this.customCallback;
            this.customCallback = null;
            getWindow().getDecorView().setSystemUiVisibility(0);
            if (!this.videoActive) {
                setRequestedOrientation(-1);
            }
            if (customViewCallback != null) {
                try {
                    customViewCallback.onCustomViewHidden();
                } catch (Exception e) {
                }
            }
        }
    }

    // Chrome shares its own Google account session. No Google API project,
    // OAuth redirect server, API key, token storage or video iframe is used.
    private void openInChrome(String address) {
        if (address == null) return;
        Uri uri;
        try {
            uri = Uri.parse(address);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null ||
                !(host.equals("youtube.com") || host.endsWith(".youtube.com") ||
                  host.equals("youtu.be") || host.equals("accounts.google.com"))) {
                Toast.makeText(this, "YouTube/Google HTTPS 주소만 Chrome으로 열 수 있습니다.", Toast.LENGTH_SHORT).show();
                return;
            }
        } catch (Exception e) {
            Toast.makeText(this, "브라우저 주소가 잘못되었습니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_VIEW, uri);
        intent.addCategory(Intent.CATEGORY_BROWSABLE);
        intent.setPackage("com.android.chrome");
        try {
            startActivity(intent);
        } catch (Exception chromeUnavailable) {
            intent.setPackage(null);
            try {
                startActivity(intent);
            } catch (Exception noBrowser) {
                Toast.makeText(this, "Chrome 또는 브라우저를 설치해 주세요.", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override // android.app.Activity
    public void onBackPressed() {
        if (this.customView != null) {
            hideCustomView();
            return;
        }
        if (this.browserVisible) {
            if (!this.browserView.canGoBack()) {
                closeBrowser();
                return;
            } else {
                this.browserView.goBack();
                return;
            }
        }
        if (this.videoActive) {
            closeVideo();
        } else if (this.webReady && this.webView != null) {
            this.webView.evaluateJavascript("(window.onBackRequest && window.onBackRequest()) ? 1 : 0", new ValueCallback<String>() { // from class: com.pulse.player.MainActivity.29
                @Override // android.webkit.ValueCallback
                public void onReceiveValue(String str) {
                    if (!"1".equals(str)) {
                        MainActivity.super.onBackPressed();
                    }
                }
            });
        } else {
            super.onBackPressed();
        }
    }

    @Override // android.app.Activity
    protected void onPause() {
        if (this.videoActive && this.videoPrepared && this.videoView.isPlaying()) {
            this.videoView.pause();
            this.videoPausedByLifecycle = true;
        }
        if (this.browserVisible && this.browserView != null) {
            try {
                this.browserView.evaluateJavascript("document.querySelectorAll('video,audio').forEach(function(m){try{m.pause()}catch(e){}})", null);
            } catch (Exception e) {
            }
            this.browserView.onPause();
        }
        js("window.onAppVisibility && window.onAppVisibility(false);");
        super.onPause();
    }

    @Override // android.app.Activity
    protected void onResume() {
        super.onResume();
        if (this.videoActive && this.videoPausedByLifecycle) {
            this.videoPausedByLifecycle = false;
            if (this.videoPrepared) {
                this.videoView.start();
            }
        }
        if (this.browserVisible && this.browserView != null) {
            this.browserView.onResume();
        }
        js("window.onAppVisibility && window.onAppVisibility(true);");
        pushState();
    }

    @Override // android.app.Activity
    protected void onDestroy() {
        if (this.videoActive) {
            this.videoView.stopPlayback();
        }
        this.metadataExecutor.shutdown();
        this.netExecutor.shutdown();
        if (this.browserView != null) {
            this.browserView.destroy();
            this.browserView = null;
        }
        if (this.webView != null) {
            this.webReady = false;
            this.webView.destroy();
            this.webView = null;
        }
        super.onDestroy();
    }

    final class PlayerBridge {
        PlayerBridge() {
        }

        @JavascriptInterface
        public String getLibrary() {
            return MainActivity.this.readLibrary();
        }

        @JavascriptInterface
        public String getCollections() {
            return MainActivity.this.readCollections();
        }

        @JavascriptInterface
        public void saveCollections(String str) {
            MainActivity.this.saveCollectionsJson(str);
        }

        @JavascriptInterface
        public String getPreferences() {
            JSONObject jSONObject = new JSONObject();
            try {
                jSONObject.put("lastId", MainActivity.this.prefs.getString("lastId", ""));
                jSONObject.put("shuffle", MainActivity.this.prefs.getBoolean("shuffle", false));
                jSONObject.put("repeat", MainActivity.this.prefs.getInt("repeat", 0));
                jSONObject.put("speed", MainActivity.this.prefs.getFloat("speed", 1.0f));
                jSONObject.put("lastScan", MainActivity.this.prefs.getLong("lastScan", 0L));
                jSONObject.put("hiddenCount", MainActivity.this.readHidden().size());
                jSONObject.put("version", "1.3.5");
            } catch (Exception e) {
            }
            return jSONObject.toString();
        }

        @JavascriptInterface
        public void importFiles() {
            MainActivity.this.runOnUiThread(new Runnable() { // from class: com.pulse.player.MainActivity.PlayerBridge.1
                @Override // java.lang.Runnable
                public void run() {
                    MainActivity.this.launchImport();
                }
            });
        }

        @JavascriptInterface
        public void play(final String str) {
            MainActivity.this.runOnUiThread(new Runnable() { // from class: com.pulse.player.MainActivity.PlayerBridge.2
                @Override // java.lang.Runnable
                public void run() {
                    MainActivity.this.play(str, false);
                }
            });
        }

        @JavascriptInterface
        public void toggle(final String str) {
            MainActivity.this.runOnUiThread(new Runnable() { // from class: com.pulse.player.MainActivity.PlayerBridge.3
                @Override // java.lang.Runnable
                public void run() {
                    MainActivity.this.play(str, true);
                }
            });
        }

        @JavascriptInterface
        public void pause() {
            MainActivity.this.runOnUiThread(new Runnable() { // from class: com.pulse.player.MainActivity.PlayerBridge.4
                @Override // java.lang.Runnable
                public void run() {
                    MainActivity.this.audioIntent(AudioPlaybackService.ACT_PAUSE, null, 0);
                    MainActivity.this.pushState();
                }
            });
        }

        @JavascriptInterface
        public void pauseAll() {
            MainActivity.this.runOnUiThread(new Runnable() { // from class: com.pulse.player.MainActivity.PlayerBridge.5
                @Override // java.lang.Runnable
                public void run() {
                    MainActivity.this.audioIntent(AudioPlaybackService.ACT_STOP, null, 0);
                    MainActivity.this.closeVideo();
                }
            });
        }

        @JavascriptInterface
        public void next() {
            MainActivity.this.runOnUiThread(new Runnable() { // from class: com.pulse.player.MainActivity.PlayerBridge.6
                @Override // java.lang.Runnable
                public void run() {
                    MainActivity.this.skipAudio(1);
                }
            });
        }

        @JavascriptInterface
        public void previous() {
            MainActivity.this.runOnUiThread(new Runnable() { // from class: com.pulse.player.MainActivity.PlayerBridge.7
                @Override // java.lang.Runnable
                public void run() {
                    MainActivity.this.skipAudio(-1);
                }
            });
        }

        @JavascriptInterface
        public void seekTo(final int i) {
            MainActivity.this.runOnUiThread(new Runnable() { // from class: com.pulse.player.MainActivity.PlayerBridge.8
                @Override // java.lang.Runnable
                public void run() {
                    if (!MainActivity.this.videoActive || !MainActivity.this.videoPrepared) {
                        MainActivity.this.audioIntent(AudioPlaybackService.ACT_SEEK, null, i);
                    } else {
                        MainActivity.this.videoView.seekTo(Math.max(0, i));
                    }
                    MainActivity.this.pushState();
                }
            });
        }

        @JavascriptInterface
        public void requestState() {
            MainActivity.this.runOnUiThread(new Runnable() { // from class: com.pulse.player.MainActivity.PlayerBridge.9
                @Override // java.lang.Runnable
                public void run() {
                    MainActivity.this.pushState();
                }
            });
        }

        @JavascriptInterface
        public void setOptions(final boolean z, final int i, final double d) {
            MainActivity.this.runOnUiThread(new Runnable() { // from class: com.pulse.player.MainActivity.PlayerBridge.10
                @Override // java.lang.Runnable
                public void run() {
                    MainActivity.this.prefs.edit().putBoolean("shuffle", z).putInt("repeat", Math.max(0, Math.min(2, i))).putFloat("speed", (float) Math.max(0.5d, Math.min(2.0d, d))).apply();
                    MainActivity.this.audioIntent(AudioPlaybackService.ACT_OPTIONS, null, 0);
                    MainActivity.this.pushState();
                }
            });
        }

        @JavascriptInterface
        public void toggleFavorite(final String str) {
            MainActivity.this.runOnUiThread(new Runnable() { // from class: com.pulse.player.MainActivity.PlayerBridge.11
                @Override // java.lang.Runnable
                public void run() {
                    MainActivity.this.editTrack(str, false, true);
                }
            });
        }

        @JavascriptInterface
        public void remove(final String str) {
            MainActivity.this.runOnUiThread(new Runnable() { // from class: com.pulse.player.MainActivity.PlayerBridge.12
                @Override // java.lang.Runnable
                public void run() {
                    MainActivity.this.editTrack(str, true, false);
                }
            });
        }

        @JavascriptInterface
        public boolean hasMediaPermission() {
            return MainActivity.this.hasMediaPermission();
        }

        @JavascriptInterface
        public void scanDevice(final boolean z) {
            MainActivity.this.runOnUiThread(new Runnable() { // from class: com.pulse.player.MainActivity.PlayerBridge.13
                @Override // java.lang.Runnable
                public void run() {
                    if (MainActivity.this.hasMediaPermission()) {
                        MainActivity.this.startDeviceScan(z);
                    } else if (z) {
                        MainActivity.this.requestMediaPermission();
                    }
                }
            });
        }

        @JavascriptInterface
        public void openAppSettings() {
            MainActivity.this.runOnUiThread(new Runnable() { // from class: com.pulse.player.MainActivity.PlayerBridge.14
                @Override // java.lang.Runnable
                public void run() {
                    try {
                        MainActivity.this.startActivity(new Intent("android.settings.APPLICATION_DETAILS_SETTINGS", Uri.parse("package:" + MainActivity.this.getPackageName())));
                    } catch (Exception e) {
                    }
                }
            });
        }

        @JavascriptInterface
        public void restoreHidden() {
            MainActivity.this.prefs.edit().putString("hiddenDevice", "[]").apply();
            MainActivity.this.runOnUiThread(new Runnable() { // from class: com.pulse.player.MainActivity.PlayerBridge.15
                @Override // java.lang.Runnable
                public void run() {
                    MainActivity.this.startDeviceScan(true);
                }
            });
        }

        @JavascriptInterface
        public String addYouTube(String str, String str2, String str3) {
            String strAddYouTubeItem = MainActivity.this.addYouTubeItem(MainActivity.extractYouTubeId(str), str2, str3, false);
            if (!strAddYouTubeItem.isEmpty()) {
                MainActivity.this.pushLibraryOnUi();
            }
            return strAddYouTubeItem;
        }

        @JavascriptInterface
        public void updateYouTubeMeta(String str, String str2, String str3, double d) {
            if (str != null && str.startsWith("yt:") && MainActivity.this.updateTrackMeta(str, str2, str3, (long) d)) {
                MainActivity.this.pushLibraryOnUi();
            }
        }

        @JavascriptInterface
        public void openBrowser(final String str, final String str2) {
            MainActivity.this.runOnUiThread(new Runnable() { // from class: com.pulse.player.MainActivity.PlayerBridge.16
                @Override // java.lang.Runnable
                public void run() {
                    MainActivity.this.openBrowser(str, str2);
                }
            });
        }

        @JavascriptInterface
        public void openInYouTubeApp(final String videoId) {
            MainActivity.this.runOnUiThread(new Runnable() {
                @Override public void run() { MainActivity.this.openOfficialYouTubeVideo(videoId); }
            });
        }

        @JavascriptInterface
        public void downloadDirectMedia(final String url) {
            MainActivity.this.runOnUiThread(new Runnable() {
                @Override public void run() { MainActivity.this.downloadAuthorizedMediaFile(url); }
            });
        }

        @JavascriptInterface
        public void openExternal(final String str) {
            MainActivity.this.runOnUiThread(new Runnable() { // from class: com.pulse.player.MainActivity.PlayerBridge.17
                @Override // java.lang.Runnable
                public void run() {
                    if (str != null && str.startsWith("https://")) {
                        try {
                            MainActivity.this.startActivity(new Intent("android.intent.action.VIEW", Uri.parse(str)));
                        } catch (Exception e) {
                            Toast.makeText(MainActivity.this, "열 수 있는 앱이 없습니다.", 0).show();
                        }
                    }
                }
            });
        }

        @JavascriptInterface
        public void setKeepScreenOn(final boolean z) {
            MainActivity.this.runOnUiThread(new Runnable() { // from class: com.pulse.player.MainActivity.PlayerBridge.18
                @Override // java.lang.Runnable
                public void run() {
                    if (!z) {
                        if (!MainActivity.this.videoActive) {
                            MainActivity.this.getWindow().clearFlags(128);
                            return;
                        }
                        return;
                    }
                    MainActivity.this.getWindow().addFlags(128);
                }
            });
        }

        @JavascriptInterface
        public void openChrome(final String url) {
            MainActivity.this.runOnUiThread(new Runnable() {
                @Override public void run() { MainActivity.this.openInChrome(url); }
            });
        }

        @JavascriptInterface
        public String addYouTubeBatch(String str) {
            JSONArray jSONArray = new JSONArray();
            try {
                JSONArray jSONArray2 = new JSONArray(str);
                for (int i = 0; i < jSONArray2.length() && i < 2000; i++) {
                    JSONObject jSONObjectOptJSONObject = jSONArray2.optJSONObject(i);
                    if (jSONObjectOptJSONObject != null) {
                        String strAddYouTubeItem = MainActivity.this.addYouTubeItem(jSONObjectOptJSONObject.optString("videoId"), jSONObjectOptJSONObject.optString("title"), jSONObjectOptJSONObject.optString("author"), false);
                        if (!strAddYouTubeItem.isEmpty()) {
                            jSONArray.put(new JSONObject(strAddYouTubeItem));
                        }
                    }
                }
            } catch (Exception e) {
            }
            MainActivity.this.pushLibraryOnUi();
            return jSONArray.toString();
        }

        @JavascriptInterface
        public String getClipboard() {
            try {
                ClipboardManager clipboardManager = (ClipboardManager) MainActivity.this.getSystemService("clipboard");
                if (clipboardManager == null || !clipboardManager.hasPrimaryClip()) {
                    return "";
                }
                ClipData primaryClip = clipboardManager.getPrimaryClip();
                if (primaryClip == null || primaryClip.getItemCount() == 0) {
                    return "";
                }
                CharSequence charSequenceCoerceToText = primaryClip.getItemAt(0).coerceToText(MainActivity.this);
                return charSequenceCoerceToText == null ? "" : charSequenceCoerceToText.toString();
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public void copyText(String str) {
            try {
                ClipboardManager clipboardManager = (ClipboardManager) MainActivity.this.getSystemService("clipboard");
                if (clipboardManager != null) {
                    if (str == null) {
                        str = "";
                    }
                    clipboardManager.setPrimaryClip(ClipData.newPlainText("Pulse Player", str));
                }
            } catch (Exception e) {
            }
        }

        @JavascriptInterface
        public void shareText(final String str, final String str2) {
            MainActivity.this.runOnUiThread(new Runnable() { // from class: com.pulse.player.MainActivity.PlayerBridge.24
                @Override // java.lang.Runnable
                public void run() {
                    Intent intent = new Intent("android.intent.action.SEND");
                    intent.setType("text/plain");
                    intent.putExtra("android.intent.extra.SUBJECT", str2 == null ? "Pulse Player" : str2);
                    intent.putExtra("android.intent.extra.TEXT", str == null ? "" : str);
                    try {
                        MainActivity.this.startActivity(Intent.createChooser(intent, "백업 보내기"));
                    } catch (Exception e) {
                        Toast.makeText(MainActivity.this, "공유할 앱이 없습니다.", 0).show();
                    }
                }
            });
        }

        @JavascriptInterface
        public int restoreBackup(String str) {
            int size;
            try {
                JSONObject jSONObject = new JSONObject(str);
                JSONArray jSONArrayOptJSONArray = jSONObject.optJSONArray("youtube");
                if (jSONArrayOptJSONArray != null) {
                    size = 0;
                    for (int i = 0; i < jSONArrayOptJSONArray.length() && i < 5000; i++) {
                        JSONObject jSONObjectOptJSONObject = jSONArrayOptJSONArray.optJSONObject(i);
                        if (jSONObjectOptJSONObject != null) {
                            if (!MainActivity.this.addYouTubeItem(jSONObjectOptJSONObject.optString("videoId", jSONObjectOptJSONObject.optString("id").replaceFirst("^yt:", "")), jSONObjectOptJSONObject.optString("name"), jSONObjectOptJSONObject.optString("artist"), false).isEmpty()) {
                                size++;
                            }
                        }
                    }
                } else {
                    size = 0;
                }
                JSONArray jSONArrayOptJSONArray2 = jSONObject.optJSONArray("favorites");
                if (jSONArrayOptJSONArray2 != null) {
                    HashSet hashSet = new HashSet();
                    for (int i2 = 0; i2 < jSONArrayOptJSONArray2.length(); i2++) {
                        hashSet.add(jSONArrayOptJSONArray2.optString(i2));
                    }
                    synchronized (MainActivity.this.libraryLock) {
                        JSONArray jSONArray = new JSONArray(MainActivity.this.readLibrary());
                        for (int i3 = 0; i3 < jSONArray.length(); i3++) {
                            JSONObject jSONObjectOptJSONObject2 = jSONArray.optJSONObject(i3);
                            if (jSONObjectOptJSONObject2 != null && hashSet.contains(jSONObjectOptJSONObject2.optString("id"))) {
                                jSONObjectOptJSONObject2.put("favorite", true);
                            }
                        }
                        MainActivity.this.prefs.edit().putString("library", jSONArray.toString()).apply();
                    }
                    size += hashSet.size();
                }
                JSONObject jSONObjectOptJSONObject3 = jSONObject.optJSONObject("collections");
                if (jSONObjectOptJSONObject3 != null) {
                    MainActivity.this.saveCollectionsJson(jSONObjectOptJSONObject3.toString());
                }
                MainActivity.this.pushLibraryOnUi();
                return size;
            } catch (Exception e) {
                return -1;
            }
        }
    }
}
