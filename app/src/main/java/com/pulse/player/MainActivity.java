package com.pulse.player;

import android.app.Activity;
import android.app.AlertDialog;
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
import android.provider.MediaStore;
import android.text.Html;
import android.text.TextUtils;
import android.util.Base64;
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
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.security.SecureRandom;
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
public final class MainActivity extends Activity implements YouTubePlaybackService.Listener {
    private static final String ASSET_HOST = "appassets.androidplatform.net";
    private static final String ASSET_ORIGIN = "https://appassets.androidplatform.net";
    private static final int REQUEST_IMPORT = 1001;
    private static final int REQUEST_MEDIA_PERMISSION = 1002;
    private static final String START_URL = "https://appassets.androidplatform.net/assets/index.html";
    private static final Pattern YT_ID = Pattern.compile("^[A-Za-z0-9_-]{11}$");
    private static final String YT_SCOPE = "https://www.googleapis.com/auth/youtube.readonly";
    private FrameLayout browserFrame;
    private TextView browserPrimary;
    private TextView browserFavorite;
    private TextView browserSecondary;
    private TextView browserTitle;
    private WebView browserView;
    private boolean browserVisible;
    private WebChromeClient.CustomViewCallback customCallback;
    private View customView;
    private volatile ServerSocket loopbackServer;
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
    private KeepAliveWebView webView;
    private boolean ytActive;
    private boolean backgroundPlay = true;
    private String videoId = "";
    private String browserMode = "web";
    private String browserVideoId = "";
    private final Object libraryLock = new Object();
    private final ExecutorService metadataExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService netExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService apiExecutor = Executors.newFixedThreadPool(2);
    private final Random random = new Random();
    private volatile String lastAuthUrl = "";
    private final Object tokenLock = new Object();

    static final class KeepAliveWebView extends WebView {
        boolean keepAlive;

        KeepAliveWebView(Context context) {
            super(context);
        }

        @Override // android.webkit.WebView, android.view.View
        protected void onWindowVisibilityChanged(int i) {
            if (this.keepAlive && i != 0) {
                super.onWindowVisibilityChanged(0);
            } else {
                super.onWindowVisibilityChanged(i);
            }
        }
    }

    @Override // android.app.Activity
    public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        this.prefs = getSharedPreferences("pulse", 0);
        this.backgroundPlay = this.prefs.getBoolean("ytBackground", true);
        YouTubePlaybackService.listener = this;
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
        this.webView = new KeepAliveWebView(this);
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

    private JSONObject findTrack(String str) {
        JSONObject jSONObjectOptJSONObject;
        synchronized (this.libraryLock) {
            try {
                JSONArray jSONArray = new JSONArray(readLibrary());
                for (int i = 0; i < jSONArray.length(); i++) {
                    jSONObjectOptJSONObject = jSONArray.optJSONObject(i);
                    if (jSONObjectOptJSONObject == null || !str.equals(jSONObjectOptJSONObject.optString("id"))) {
                    }
                }
            } catch (Exception e) {
            }
            jSONObjectOptJSONObject = null;
        }
        return jSONObjectOptJSONObject;
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
    public boolean hasMediaPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            return checkSelfPermission("android.permission.READ_MEDIA_AUDIO") == 0 || checkSelfPermission("android.permission.READ_MEDIA_VIDEO") == 0 || (Build.VERSION.SDK_INT >= 34 && checkSelfPermission("android.permission.READ_MEDIA_VISUAL_USER_SELECTED") == 0);
        }
        return checkSelfPermission("android.permission.READ_EXTERNAL_STORAGE") == 0;
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
    public void audioIntent(String str, String str2, int i) {
        Intent action = new Intent(this, (Class<?>) AudioPlaybackService.class).setAction(str);
        if (str2 != null) {
            action.putExtra("id", str2);
        }
        action.putExtra("position", i);
        try {
            if (AudioPlaybackService.ACT_PLAY.equals(str) && AudioPlaybackService.instance == null) {
                startForegroundService(action);
            } else if (AudioPlaybackService.instance != null) {
                startService(action);
            }
        } catch (Exception e) {
            Toast.makeText(this, "재생 서비스를 시작할 수 없습니다.", 0).show();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void play(String str, boolean z) {
        JSONObject jSONObjectFindTrack = findTrack(str);
        if (jSONObjectFindTrack == null) {
            Toast.makeText(this, "파일을 찾을 수 없습니다. 다시 추가해 주세요.", 0).show();
            return;
        }
        String strOptString = jSONObjectFindTrack.optString("type");
        if (!"youtube".equals(strOptString)) {
            if ("video".equals(strOptString)) {
                showVideo(str);
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
            settings.setMediaPlaybackRequiresUserGesture(true);
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

    /* JADX INFO: Access modifiers changed from: private */
    public void openBrowser(String str, String str2) {
        if (str != null) {
            if (str.startsWith("https://") || str.startsWith("http://")) {
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

    private static final class HttpResult {
        String body;
        int code;

        private HttpResult() {
            this.body = "";
        }
    }

    public static HttpResult http(String method, String url, String body, String bearer) {
        HttpResult result = new HttpResult();
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(12000);
            connection.setReadTimeout(15000);
            connection.setRequestMethod(method);
            connection.setRequestProperty("Accept", "application/json");
            if (bearer != null && !bearer.isEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer " + bearer);
            }
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                byte[] encodedBody = body.getBytes("UTF-8");
                connection.setFixedLengthStreamingMode(encodedBody.length);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(encodedBody);
                }
            }
            result.code = connection.getResponseCode();
            InputStream stream = result.code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (stream != null) {
                try (InputStream input = stream; ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
                    byte[] chunk = new byte[8192];
                    int count;
                    while (buffer.size() < 4000000 && (count = input.read(chunk)) > 0) {
                        buffer.write(chunk, 0, Math.min(count, 4000000 - buffer.size()));
                    }
                    result.body = buffer.toString("UTF-8");
                }
            }
        } catch (Exception ex) {
            result.code = -1;
            result.body = "{\"error\":\"network\",\"error_description\":" + JSONObject.quote(String.valueOf(ex.getMessage())) + "}";
        } finally {
            if (connection != null) connection.disconnect();
        }
        return result;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static String enc(String str) {
        if (str == null) {
            str = "";
        }
        try {
            return URLEncoder.encode(str, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void authEvent(JSONObject jSONObject) {
        jsOnUi("window.onYtAuth && window.onYtAuth(" + jSONObject.toString() + ");");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void authError(String str) {
        try {
            JSONObject jSONObject = new JSONObject();
            jSONObject.put("state", "error");
            jSONObject.put("message", str);
            authEvent(jSONObject);
        } catch (Exception e) {
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public JSONObject accountJson() {
        JSONObject jSONObject = new JSONObject();
        try {
            jSONObject.put("configured", !this.prefs.getString("ytClientId", "").isEmpty());
            jSONObject.put("signedIn", this.prefs.getString("ytRefresh", "").isEmpty() ? false : true);
            jSONObject.put("name", this.prefs.getString("ytAccountName", ""));
            jSONObject.put("thumb", this.prefs.getString("ytAccountThumb", ""));
            String string = this.prefs.getString("ytClientId", "");
            if (string.length() > 14) {
                string = string.substring(0, 10) + "…";
            }
            jSONObject.put("clientHint", string);
            jSONObject.put("clientType", clientType());
            jSONObject.put("chrome", isChromeInstalled());
        } catch (Exception e) {
        }
        return jSONObject;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static String describeOAuthError(JSONObject jSONObject) {
        String strOptString = jSONObject.optString("error");
        String strOptString2 = jSONObject.optString("error_description");
        if ("access_denied".equals(strOptString)) {
            return "로그인을 거부했어요.";
        }
        if ("expired_token".equals(strOptString)) {
            return "코드가 만료됐어요. 다시 시도해 주세요.";
        }
        if ("invalid_client".equals(strOptString) || "unauthorized_client".equals(strOptString)) {
            return "Google OAuth 클라이언트는 '데스크톱 앱' 유형이어야 합니다. 클라이언트 ID와 보안 비밀을 확인하세요.";
        }
        if ("invalid_scope".equals(strOptString)) {
            return "YouTube 권한을 요청할 수 없어요. 클라이언트 유형을 확인하세요.";
        }
        if ("network".equals(strOptString)) {
            return "인터넷 연결을 확인하세요.";
        }
        if ("redirect_uri_mismatch".equals(strOptString)) {
            return "Chrome 인증 리디렉션이 거부되었습니다. Google Cloud의 데스크톱 앱 OAuth 클라이언트 ID를 사용하세요.";
        }
        StringBuilder sbAppend = new StringBuilder().append("로그인 실패: ");
        if (!strOptString2.isEmpty()) {
            strOptString = strOptString2;
        }
        return sbAppend.append(strOptString).toString();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public String clientType() {
        return "desktop";
    }

    private boolean isChromeInstalled() {
        try {
            getPackageManager().getPackageInfo("com.android.chrome", 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String randomToken(int i) {
        byte[] bArr = new byte[i];
        new SecureRandom().nextBytes(bArr);
        return Base64.encodeToString(bArr, 11);
    }

    private static String pkceChallenge(String str) throws Exception {
        return Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(str.getBytes("US-ASCII")), 11);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void closeLoopback() {
        ServerSocket serverSocket = this.loopbackServer;
        this.loopbackServer = null;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (Exception e) {
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void openInChrome(String str) {
        Intent intent = new Intent("android.intent.action.VIEW", Uri.parse(str));
        Bundle bundle = new Bundle();
        bundle.putBinder("android.support.customtabs.extra.SESSION", null);
        intent.putExtras(bundle);
        intent.putExtra("android.support.customtabs.extra.TOOLBAR_COLOR", Color.rgb(28, 22, 40));
        intent.putExtra("android.support.customtabs.extra.TITLE_VISIBILITY", 1);
        if (isChromeInstalled()) {
            intent.setPackage("com.android.chrome");
        }
        try {
            startActivity(intent);
        } catch (Exception e) {
            intent.setPackage(null);
            try {
                startActivity(intent);
            } catch (Exception e2) {
                authError("로그인 페이지를 열 브라우저가 없어요.");
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void startChromeSignIn() {
        final String string = this.prefs.getString("ytClientId", "");
        final String string2 = this.prefs.getString("ytClientSecret", "");
        if (string.isEmpty()) {
            authError("먼저 OAuth 클라이언트 ID를 설정하세요.");
            return;
        }
        closeLoopback();
        try {
            final ServerSocket serverSocket = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
            serverSocket.setSoTimeout(600000);
            this.loopbackServer = serverSocket;
            final String str = "http://127.0.0.1:" + serverSocket.getLocalPort();
            final String strRandomToken = randomToken(48);
            final String strRandomToken2 = randomToken(16);
            try {
                String str2 = "https://accounts.google.com/o/oauth2/v2/auth?response_type=code&client_id=" + enc(string) + "&redirect_uri=" + enc(str) + "&scope=" + enc(YT_SCOPE) + "&code_challenge=" + enc(pkceChallenge(strRandomToken)) + "&code_challenge_method=S256&state=" + enc(strRandomToken2) + "&access_type=offline&prompt=" + enc("select_account consent");
                new Thread(new Runnable() { // from class: com.pulse.player.MainActivity.26
                    @Override // java.lang.Runnable
                    public void run() {
                        JSONObject jSONObject;
                        while (MainActivity.this.loopbackServer == serverSocket) {
                            try {
                                try {
                                    Socket socketAccept = serverSocket.accept();
                                    try {
                                        try {
                                            socketAccept.setSoTimeout(10000);
                                            String line = new BufferedReader(new InputStreamReader(socketAccept.getInputStream(), "UTF-8")).readLine();
                                            if (line == null || !line.startsWith("GET ")) {
                                                MainActivity.respond(socketAccept, 400, "잘못된 요청");
                                                try {
                                                    socketAccept.close();
                                                } catch (Exception e) {
                                                }
                                            } else {
                                                Uri uri = Uri.parse("http://127.0.0.1" + line.split(" ")[1]);
                                                if (uri.getQueryParameter("code") == null && uri.getQueryParameter("error") == null) {
                                                    MainActivity.respond(socketAccept, 404, "");
                                                    try {
                                                        socketAccept.close();
                                                    } catch (Exception e2) {
                                                    }
                                                } else {
                                                    if (strRandomToken2.equals(uri.getQueryParameter("state"))) {
                                                        String queryParameter = uri.getQueryParameter("error");
                                                        if (queryParameter != null) {
                                                            MainActivity.this.respondDone(socketAccept, false, "access_denied".equals(queryParameter) ? "로그인을 취소했어요." : "로그인 실패: " + queryParameter);
                                                            MainActivity.this.closeLoopback();
                                                            MainActivity.this.authError("access_denied".equals(queryParameter) ? "로그인을 취소했어요." : "로그인 실패: " + queryParameter);
                                                            try {
                                                                socketAccept.close();
                                                                return;
                                                            } catch (Exception e3) {
                                                                return;
                                                            }
                                                        }
                                                        HttpResult httpResultHttp = MainActivity.http("POST", "https://oauth2.googleapis.com/token", "code=" + MainActivity.enc(uri.getQueryParameter("code")) + "&client_id=" + MainActivity.enc(string) + (string2.isEmpty() ? "" : "&client_secret=" + MainActivity.enc(string2)) + "&redirect_uri=" + MainActivity.enc(str) + "&grant_type=authorization_code&code_verifier=" + MainActivity.enc(strRandomToken), null);
                                                        try {
                                                            jSONObject = new JSONObject(httpResultHttp.body);
                                                        } catch (Exception e4) {
                                                            jSONObject = new JSONObject();
                                                        }
                                                        if (httpResultHttp.code == 200 && jSONObject.has("access_token")) {
                                                            MainActivity.this.saveTokens(jSONObject);
                                                            MainActivity.this.fetchAccountInfo();
                                                            MainActivity.this.respondDone(socketAccept, true, MainActivity.this.prefs.getString("ytAccountName", "YouTube") + " 계정으로 로그인했어요.");
                                                            MainActivity.this.closeLoopback();
                                                            JSONObject jSONObjectAccountJson = MainActivity.this.accountJson();
                                                            jSONObjectAccountJson.put("state", "done");
                                                            MainActivity.this.authEvent(jSONObjectAccountJson);
                                                            MainActivity.this.bringToFront();
                                                        } else {
                                                            String strDescribeOAuthError = MainActivity.describeOAuthError(jSONObject);
                                                            MainActivity.this.respondDone(socketAccept, false, strDescribeOAuthError);
                                                            MainActivity.this.closeLoopback();
                                                            MainActivity.this.authError(strDescribeOAuthError);
                                                        }
                                                        try {
                                                            socketAccept.close();
                                                            return;
                                                        } catch (Exception e5) {
                                                            return;
                                                        }
                                                    }
                                                    MainActivity.respond(socketAccept, 400, "보안 확인(state)에 실패했어요. 앱에서 다시 로그인해 주세요.");
                                                    try {
                                                        socketAccept.close();
                                                    } catch (Exception e6) {
                                                    }
                                                }
                                            }
                                        } catch (IOException e7) {
                                            if (MainActivity.this.loopbackServer != serverSocket) {
                                                try {
                                                    socketAccept.close();
                                                    return;
                                                } catch (Exception e8) {
                                                    return;
                                                }
                                            }
                                            try {
                                                socketAccept.close();
                                            } catch (Exception e9) {
                                            }
                                        }
                                    } catch (Throwable th) {
                                        try {
                                            socketAccept.close();
                                        } catch (Exception e10) {
                                        }
                                        MainActivity.this.authError("크롬 로그인 인증에 실패했습니다. 다시 시도해 주세요.");
                                        return;
                                    }
                                } catch (Exception e11) {
                                    return;
                                }
                            } catch (Exception e12) {
                                MainActivity.this.closeLoopback();
                                MainActivity.this.authError("Chrome 인증을 완료하지 못했어요. 다시 시도해 주세요.");
                                return;
                            }
                        }
                    }
                }).start();
                try {
                    JSONObject jSONObject = new JSONObject();
                    jSONObject.put("state", "browser");
                    jSONObject.put("chrome", isChromeInstalled());
                    authEvent(jSONObject);
                } catch (Exception e) {
                }
                this.lastAuthUrl = str2;
                openInChrome(str2);
            } catch (Exception e2) {
                closeLoopback();
                authError("로그인 준비에 실패했어요.");
            }
        } catch (Exception e3) {
            authError("로그인 준비에 실패했어요: " + e3.getMessage());
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void respond(Socket socket, int i, String str) throws Exception {
        byte[] bytes = ("<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width'><p style='font:16px sans-serif;padding:24px'>" + str + "</p>").getBytes("UTF-8");
        String str2 = "HTTP/1.1 " + i + " OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + bytes.length + "\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n";
        OutputStream outputStream = socket.getOutputStream();
        outputStream.write(str2.getBytes("US-ASCII"));
        outputStream.write(bytes);
        outputStream.flush();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void respondDone(Socket socket, boolean z, String str) throws Exception {
        String str2 = "intent://auth#Intent;scheme=pulseplayer;package=" + getPackageName() + ";end";
        byte[] bytes = ("<!doctype html><html lang=ko><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'><title>Pulse Player</title><body style='margin:0;background:#0b0d15;color:#f2eaff;font-family:sans-serif;display:flex;min-height:100vh;align-items:center;justify-content:center;text-align:center'><div style='padding:28px'><div style='font-size:46px'>" + (z ? "✅" : "⚠️") + "</div><h2 style='margin:14px 0 8px'>" + (z ? "로그인 완료" : "로그인 실패") + "</h2><p style='color:#b9aecb;line-height:1.6'>" + Html.escapeHtml(str) + "</p><a href='" + str2 + "' style='display:inline-block;margin-top:14px;padding:13px 22px;border-radius:12px;background:#d4acfc;color:#25152b;font-weight:800;text-decoration:none'>Pulse Player로 돌아가기</a></div><script>setTimeout(function(){location.href=" + JSONObject.quote(str2) + "},600)</script></body></html>").getBytes("UTF-8");
        String str3 = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + bytes.length + "\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n";
        OutputStream outputStream = socket.getOutputStream();
        outputStream.write(str3.getBytes("US-ASCII"));
        outputStream.write(bytes);
        outputStream.flush();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void bringToFront() {
        runOnUiThread(new Runnable() { // from class: com.pulse.player.MainActivity.27
            @Override // java.lang.Runnable
            public void run() {
                try {
                    MainActivity.this.startActivity(new Intent(MainActivity.this, (Class<?>) MainActivity.class).addFlags(603979776));
                } catch (Exception e) {
                }
            }
        });
    }

    @Override // android.app.Activity
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        Uri data = intent == null ? null : intent.getData();
        if (data != null && "pulseplayer".equals(data.getScheme())) {
            js("window.onYtReturn && window.onYtReturn();");
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void saveTokens(JSONObject jSONObject) {
        SharedPreferences.Editor editorEdit = this.prefs.edit();
        editorEdit.putString("ytAccess", jSONObject.optString("access_token"));
        editorEdit.putLong("ytExpiry", System.currentTimeMillis() + (jSONObject.optLong("expires_in", 3600L) * 1000));
        String strOptString = jSONObject.optString("refresh_token", "");
        if (!strOptString.isEmpty()) {
            editorEdit.putString("ytRefresh", strOptString);
        }
        editorEdit.apply();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void fetchAccountInfo() throws Throwable {
        String strAccessToken = accessToken();
        if (strAccessToken != null) {
            HttpResult httpResultHttp = http("GET", "https://www.googleapis.com/youtube/v3/channels?part=snippet,contentDetails&mine=true", null, strAccessToken);
            String strOptString = "YouTube 계정";
            String strOptString2 = "";
            String strOptString3 = "LL";
            try {
                JSONArray jSONArrayOptJSONArray = new JSONObject(httpResultHttp.body).optJSONArray("items");
                if (jSONArrayOptJSONArray != null && jSONArrayOptJSONArray.length() > 0) {
                    JSONObject jSONObject = jSONArrayOptJSONArray.getJSONObject(0);
                    JSONObject jSONObjectOptJSONObject = jSONObject.optJSONObject("snippet");
                    if (jSONObjectOptJSONObject != null) {
                        strOptString = jSONObjectOptJSONObject.optString("title", "YouTube 계정");
                        JSONObject jSONObjectOptJSONObject2 = jSONObjectOptJSONObject.optJSONObject("thumbnails");
                        JSONObject jSONObjectOptJSONObject3 = jSONObjectOptJSONObject2 == null ? null : jSONObjectOptJSONObject2.optJSONObject("default");
                        if (jSONObjectOptJSONObject3 != null) {
                            strOptString2 = jSONObjectOptJSONObject3.optString("url", "");
                        }
                    }
                    JSONObject jSONObjectOptJSONObject4 = jSONObject.optJSONObject("contentDetails");
                    JSONObject jSONObjectOptJSONObject5 = jSONObjectOptJSONObject4 != null ? jSONObjectOptJSONObject4.optJSONObject("relatedPlaylists") : null;
                    if (jSONObjectOptJSONObject5 != null) {
                        strOptString3 = jSONObjectOptJSONObject5.optString("likes", "LL");
                    }
                }
            } catch (Exception e) {
            }
            this.prefs.edit().putString("ytAccountName", strOptString).putString("ytAccountThumb", strOptString2).putString("ytLikes", strOptString3).apply();
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public String accessToken() {
        String strAccessTokenLocked;
        synchronized (this.tokenLock) {
            strAccessTokenLocked = accessTokenLocked();
        }
        return strAccessTokenLocked;
    }

    private String accessTokenLocked() {
        String string = this.prefs.getString("ytAccess", "");
        long j = this.prefs.getLong("ytExpiry", 0L);
        if (string.isEmpty() || j - 60000 <= System.currentTimeMillis()) {
            String string2 = this.prefs.getString("ytRefresh", "");
            if (string2.isEmpty()) {
                return null;
            }
            HttpResult httpResultHttp = http("POST", "https://oauth2.googleapis.com/token", "client_id=" + enc(this.prefs.getString("ytClientId", "")) + (this.prefs.getString("ytClientSecret", "").isEmpty() ? "" : "&client_secret=" + enc(this.prefs.getString("ytClientSecret", ""))) + "&refresh_token=" + enc(string2) + "&grant_type=refresh_token", null);
            try {
                JSONObject jSONObject = new JSONObject(httpResultHttp.body);
                if (httpResultHttp.code == 200 && jSONObject.has("access_token")) {
                    saveTokens(jSONObject);
                    return jSONObject.optString("access_token");
                }
                if ("invalid_grant".equals(jSONObject.optString("error"))) {
                    clearTokens();
                    JSONObject jSONObjectAccountJson = accountJson();
                    jSONObjectAccountJson.put("state", "expired");
                    authEvent(jSONObjectAccountJson);
                }
                return null;
            } catch (Exception e) {
                return null;
            }
        } else {
            return string;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void clearTokens() {
        this.prefs.edit().remove("ytAccess").remove("ytRefresh").remove("ytExpiry").remove("ytAccountName").remove("ytAccountThumb").remove("ytLikes").apply();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void ytApiCall(final String str, final String str2) {
        this.apiExecutor.execute(new Runnable() { // from class: com.pulse.player.MainActivity.28
            @Override // java.lang.Runnable
            public void run() {
                String str3;
                String string;
                boolean z = false;
                if (str != null && !str.startsWith("/") && !str.contains("://") && !str.contains("..")) {
                    String strAccessToken = MainActivity.this.accessToken();
                    if (strAccessToken != null) {
                        HttpResult httpResultHttp = MainActivity.http("GET", "https://www.googleapis.com/youtube/v3/" + str, null, strAccessToken);
                        if (httpResultHttp.code == 401) {
                            MainActivity.this.prefs.edit().putLong("ytExpiry", 0L).apply();
                            String strAccessToken2 = MainActivity.this.accessToken();
                            if (strAccessToken2 != null) {
                                httpResultHttp = MainActivity.http("GET", "https://www.googleapis.com/youtube/v3/" + str, null, strAccessToken2);
                            }
                        }
                        z = httpResultHttp.code == 200;
                        str3 = httpResultHttp.body.isEmpty() ? "{}" : httpResultHttp.body;
                    } else {
                        str3 = "{\"error\":{\"message\":\"로그인이 필요해요.\",\"code\":401}}";
                    }
                } else {
                    str3 = "{\"error\":{\"message\":\"bad path\"}}";
                }
                try {
                    string = new JSONObject(str3).toString();
                } catch (Exception e) {
                    string = "{\"error\":{\"message\":\"invalid response\"}}";
                }
                MainActivity.this.jsOnUi("window.onYtApi && window.onYtApi(" + JSONObject.quote(str2) + "," + z + "," + string + ");");
            }
        });
    }

    @Override // com.pulse.player.YouTubePlaybackService.Listener
    public void onYouTubeCommand(String str) {
        js("window.onYtCommand && window.onYtCommand(" + JSONObject.quote(str) + ");");
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateYouTubeService(boolean z, boolean z2, String str, String str2, String str3, long j, long j2) {
        this.ytActive = z;
        this.webView.keepAlive = z && this.backgroundPlay;
        if (this.backgroundPlay || YouTubePlaybackService.instance != null) {
            Intent intentPutExtra = new Intent(this, (Class<?>) YouTubePlaybackService.class).setAction(YouTubePlaybackService.ACT_UPDATE).putExtra("active", z && this.backgroundPlay).putExtra("playing", z2).putExtra("title", str).putExtra("artist", str2).putExtra("thumb", str3).putExtra("position", j).putExtra("duration", j2);
            try {
                if (YouTubePlaybackService.instance != null) {
                    startService(intentPutExtra);
                } else if (z && this.backgroundPlay && z2) {
                    startForegroundService(intentPutExtra);
                }
            } catch (Exception e) {
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
        if (YouTubePlaybackService.listener == this) {
            YouTubePlaybackService.listener = null;
        }
        if (YouTubePlaybackService.instance != null) {
            try {
                startService(new Intent(this, (Class<?>) YouTubePlaybackService.class).setAction(YouTubePlaybackService.ACT_UPDATE).putExtra("active", false));
            } catch (Exception e) {
            }
        }
        if (this.videoActive) {
            this.videoView.stopPlayback();
        }
        closeLoopback();
        this.metadataExecutor.shutdown();
        this.netExecutor.shutdown();
        this.apiExecutor.shutdown();
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
                jSONObject.put("version", "1.3.2");
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
        public void ytState(final boolean z, final boolean z2, final String str, final String str2, final String str3, final double d, final double d2) {
            MainActivity.this.runOnUiThread(new Runnable() { // from class: com.pulse.player.MainActivity.PlayerBridge.19
                @Override // java.lang.Runnable
                public void run() {
                    MainActivity.this.updateYouTubeService(z, z2, str, str2, str3, (long) d, (long) d2);
                }
            });
        }

        @JavascriptInterface
        public void setBackgroundPlay(final boolean z) {
            MainActivity.this.runOnUiThread(new Runnable() { // from class: com.pulse.player.MainActivity.PlayerBridge.20
                @Override // java.lang.Runnable
                public void run() {
                    MainActivity.this.backgroundPlay = z;
                    MainActivity.this.prefs.edit().putBoolean("ytBackground", z).apply();
                    MainActivity.this.webView.keepAlive = MainActivity.this.ytActive && z;
                    if (!z && YouTubePlaybackService.instance != null) {
                        MainActivity.this.startService(new Intent(MainActivity.this, (Class<?>) YouTubePlaybackService.class).setAction(YouTubePlaybackService.ACT_UPDATE).putExtra("active", false));
                    }
                }
            });
        }

        @JavascriptInterface
        public boolean getBackgroundPlay() {
            return MainActivity.this.backgroundPlay;
        }

        @JavascriptInterface
        public String getYtAccount() {
            JSONObject jSONObjectAccountJson = MainActivity.this.accountJson();
            try {
                jSONObjectAccountJson.put("likes", MainActivity.this.prefs.getString("ytLikes", "LL"));
            } catch (Exception e) {
            }
            return jSONObjectAccountJson.toString();
        }

        @JavascriptInterface
        public void setYtClient(String str, String str2) {
            String strTrim = str == null ? "" : str.trim();
            String strTrim2 = str2 == null ? "" : str2.trim();
            boolean z = !strTrim.equals(MainActivity.this.prefs.getString("ytClientId", ""));
            MainActivity.this.prefs.edit().putString("ytClientId", strTrim).putString("ytClientSecret", strTrim2).putString("ytClientType", "desktop").apply();
            if (z) {
                MainActivity.this.clearTokens();
            }
        }

        @JavascriptInterface
        public void ytSignIn() {
            MainActivity.this.runOnUiThread(new Runnable() { // from class: com.pulse.player.MainActivity.PlayerBridge.21
                @Override // java.lang.Runnable
                public void run() {
                    MainActivity.this.startChromeSignIn();
                }
            });
        }

        @JavascriptInterface
        public void ytReopenBrowser() {
            MainActivity.this.runOnUiThread(new Runnable() { // from class: com.pulse.player.MainActivity.PlayerBridge.22
                @Override // java.lang.Runnable
                public void run() {
                    if (MainActivity.this.loopbackServer == null || MainActivity.this.lastAuthUrl.isEmpty()) {
                        MainActivity.this.startChromeSignIn();
                    } else {
                        MainActivity.this.openInChrome(MainActivity.this.lastAuthUrl);
                    }
                }
            });
        }

        @JavascriptInterface
        public void ytCancelSignIn() {
            MainActivity.this.closeLoopback();

        }

        @JavascriptInterface
        public void ytSignOut() {
            final String string = MainActivity.this.prefs.getString("ytRefresh", "");
            MainActivity.this.clearTokens();
            if (!string.isEmpty()) {
                MainActivity.this.netExecutor.execute(new Runnable() { // from class: com.pulse.player.MainActivity.PlayerBridge.23
                    @Override // java.lang.Runnable
                    public void run() {
                        MainActivity.http("POST", "https://oauth2.googleapis.com/revoke", "token=" + MainActivity.enc(string), null);
                    }
                });
            }
        }

        @JavascriptInterface
        public void ytApi(String str, String str2) {
            MainActivity.this.ytApiCall(str, str2);
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
