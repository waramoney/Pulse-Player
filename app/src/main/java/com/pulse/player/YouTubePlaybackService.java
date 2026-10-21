package com.pulse.player;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/* JADX INFO: loaded from: classes2.dex */
public final class YouTubePlaybackService extends Service implements AudioManager.OnAudioFocusChangeListener {
    public static final String ACT_CLOSE = "com.pulse.player.YT_CLOSE";
    public static final String ACT_NEXT = "com.pulse.player.YT_NEXT";
    public static final String ACT_PREV = "com.pulse.player.YT_PREV";
    public static final String ACT_TOGGLE = "com.pulse.player.YT_TOGGLE";
    public static final String ACT_UPDATE = "com.pulse.player.YT_UPDATE";
    private static final String CHANNEL_ID = "pulse_youtube_playback";
    private static final int NOTIFICATION_ID = 43;
    public static YouTubePlaybackService instance;
    public static Listener listener;
    private Bitmap art;
    private AudioManager audioManager;
    private long duration;
    private AudioFocusRequest focusRequest;
    private boolean foreground;
    private boolean noisyRegistered;
    private boolean pausedByFocus;
    private boolean playing;
    private long position;
    private MediaSession session;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private final Handler main = new Handler(Looper.getMainLooper());
    private String title = "YouTube";
    private String artist = "";
    private String thumb = "";
    private String loadedThumb = "";
    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() { // from class: com.pulse.player.YouTubePlaybackService.1
        @Override // android.content.BroadcastReceiver
        public void onReceive(Context context, Intent intent) {
            if ("android.media.AUDIO_BECOMING_NOISY".equals(intent.getAction()) && YouTubePlaybackService.this.playing) {
                YouTubePlaybackService.this.send("pause");
            }
        }
    };

    public interface Listener {
        void onYouTubeCommand(String str);
    }

    @Override // android.app.Service
    public void onCreate() {
        super.onCreate();
        instance = this;
        this.audioManager = (AudioManager) getSystemService("audio");
        NotificationChannel notificationChannel = new NotificationChannel(CHANNEL_ID, "YouTube 재생", 2);
        notificationChannel.setDescription("YouTube 백그라운드 재생 컨트롤");
        notificationChannel.setShowBadge(false);
        ((NotificationManager) getSystemService("notification")).createNotificationChannel(notificationChannel);
        this.session = new MediaSession(this, "PulseYouTube");
        this.session.setCallback(new MediaSession.Callback() { // from class: com.pulse.player.YouTubePlaybackService.2
            @Override // android.media.session.MediaSession.Callback
            public void onPlay() {
                YouTubePlaybackService.this.send("play");
            }

            @Override // android.media.session.MediaSession.Callback
            public void onPause() {
                YouTubePlaybackService.this.send("pause");
            }

            @Override // android.media.session.MediaSession.Callback
            public void onSkipToNext() {
                YouTubePlaybackService.this.send("next");
            }

            @Override // android.media.session.MediaSession.Callback
            public void onSkipToPrevious() {
                YouTubePlaybackService.this.send("prev");
            }

            @Override // android.media.session.MediaSession.Callback
            public void onSeekTo(long j) {
                YouTubePlaybackService.this.send("seek:" + j);
            }

            @Override // android.media.session.MediaSession.Callback
            public void onStop() {
                YouTubePlaybackService.this.send("pause");
            }
        });
        this.session.setActive(true);
        try {
            this.wakeLock = ((PowerManager) getSystemService("power")).newWakeLock(1, "PulsePlayer:youtube");
            this.wakeLock.setReferenceCounted(false);
        } catch (Exception e) {
        }
        try {
            this.wifiLock = ((WifiManager) getApplicationContext().getSystemService("wifi")).createWifiLock(3, "PulsePlayer:youtube");
            this.wifiLock.setReferenceCounted(false);
        } catch (Exception e2) {
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void send(final String str) {
        this.main.post(new Runnable() { // from class: com.pulse.player.YouTubePlaybackService.3
            @Override // java.lang.Runnable
            public void run() {
                if (YouTubePlaybackService.listener != null) {
                    YouTubePlaybackService.listener.onYouTubeCommand(str);
                }
            }
        });
    }

    @Override // android.app.Service
    public int onStartCommand(Intent intent, int i, int i2) {
        ensureForeground();
        String action = intent == null ? null : intent.getAction();
        if (ACT_UPDATE.equals(action)) {
            if (!intent.getBooleanExtra("active", true)) {
                shutdown();
            } else {
                this.title = nonEmpty(intent.getStringExtra("title"), "YouTube");
                this.artist = nonEmpty(intent.getStringExtra("artist"), "");
                String strNonEmpty = nonEmpty(intent.getStringExtra("thumb"), "");
                boolean booleanExtra = intent.getBooleanExtra("playing", false);
                this.position = intent.getLongExtra("position", 0L);
                this.duration = intent.getLongExtra("duration", 0L);
                if (booleanExtra) {
                    this.pausedByFocus = false;
                }
                setPlaying(booleanExtra);
                if (!strNonEmpty.equals(this.thumb)) {
                    this.thumb = strNonEmpty;
                    loadArt(this.thumb);
                }
                refresh();
            }
        } else if (ACT_TOGGLE.equals(action)) {
            send(this.playing ? "pause" : "play");
        } else if (ACT_NEXT.equals(action)) {
            send("next");
        } else if (ACT_PREV.equals(action)) {
            send("prev");
        } else if (ACT_CLOSE.equals(action)) {
            send("close");
            shutdown();
        }
        return 2;
    }

    private static String nonEmpty(String str, String str2) {
        return (str == null || str.trim().isEmpty()) ? str2 : str.trim();
    }

    private void ensureForeground() {
        if (!this.foreground) {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, buildNotification(), 2);
            } else {
                startForeground(NOTIFICATION_ID, buildNotification());
            }
            this.foreground = true;
        }
    }

    private void setPlaying(boolean z) {
        this.playing = z;
        if (z) {
            requestFocus();
            if (!this.noisyRegistered) {
                registerReceiver(this.noisyReceiver, new IntentFilter("android.media.AUDIO_BECOMING_NOISY"));
                this.noisyRegistered = true;
            }
            try {
                if (this.wakeLock != null && !this.wakeLock.isHeld()) {
                    this.wakeLock.acquire(14400000L);
                }
                if (this.wifiLock == null || this.wifiLock.isHeld()) {
                    return;
                }
                this.wifiLock.acquire();
                return;
            } catch (Exception e) {
                return;
            }
        }
        releaseLocks();
    }

    private void releaseLocks() {
        try {
            if (this.wakeLock != null && this.wakeLock.isHeld()) {
                this.wakeLock.release();
            }
            if (this.wifiLock == null || !this.wifiLock.isHeld()) {
                return;
            }
            this.wifiLock.release();
        } catch (Exception e) {
        }
    }

    private void requestFocus() {
        if (this.focusRequest == null) {
            try {
                this.focusRequest = new AudioFocusRequest.Builder(1).setAudioAttributes(new AudioAttributes.Builder().setUsage(1).setContentType(2).build()).setOnAudioFocusChangeListener(this).setWillPauseWhenDucked(false).build();
                this.audioManager.requestAudioFocus(this.focusRequest);
            } catch (Exception e) {
            }
        }
    }

    private void abandonFocus() {
        if (this.focusRequest != null) {
            try {
                this.audioManager.abandonAudioFocusRequest(this.focusRequest);
            } catch (Exception e) {
            }
            this.focusRequest = null;
        }
    }

    @Override // android.media.AudioManager.OnAudioFocusChangeListener
    public void onAudioFocusChange(int i) {
        if (i == -1) {
            abandonFocus();
            if (this.playing) {
                send("pause");
                return;
            }
            return;
        }
        if (i == -2) {
            if (this.playing) {
                this.pausedByFocus = true;
                send("pause");
                return;
            }
            return;
        }
        if (i == 1 && this.pausedByFocus) {
            this.pausedByFocus = false;
            send("play");
        }
    }

    private void loadArt(final String str) {
        this.art = null;
        if (!str.isEmpty() && str.startsWith("https://")) {
            new Thread(new Runnable() { // from class: com.pulse.player.YouTubePlaybackService.4
                @Override // java.lang.Runnable
                public void run() throws Throwable {
                    Throwable th;
                    HttpURLConnection httpURLConnection;
                    HttpURLConnection httpURLConnection2;
                    try {
                        httpURLConnection2 = (HttpURLConnection) new URL(str).openConnection();
                        try {
                            httpURLConnection2.setConnectTimeout(8000);
                            httpURLConnection2.setReadTimeout(8000);
                            InputStream inputStream = httpURLConnection2.getInputStream();
                            final Bitmap bitmapDecodeStream = BitmapFactory.decodeStream(inputStream);
                            inputStream.close();
                            YouTubePlaybackService.this.main.post(new Runnable() { // from class: com.pulse.player.YouTubePlaybackService.4.1
                                @Override // java.lang.Runnable
                                public void run() {
                                    if (str.equals(YouTubePlaybackService.this.thumb) && bitmapDecodeStream != null) {
                                        YouTubePlaybackService.this.art = bitmapDecodeStream;
                                        YouTubePlaybackService.this.loadedThumb = str;
                                        YouTubePlaybackService.this.refresh();
                                    }
                                }
                            });
                            if (httpURLConnection2 != null) {
                                httpURLConnection2.disconnect();
                            }
                        } catch (Exception e) {
                            if (httpURLConnection2 != null) {
                                httpURLConnection2.disconnect();
                            }
                        } catch (Throwable th2) {
                            th = th2;
                            httpURLConnection = httpURLConnection2;
                            if (httpURLConnection != null) {
                                httpURLConnection.disconnect();
                                throw th;
                            }
                            throw th;
                        }
                    } catch (Exception e2) {
                        httpURLConnection2 = null;
                    } catch (Throwable th3) {
                        th = th3;
                        httpURLConnection = null;
                    }
                }
            }).start();
        }
    }

    private PendingIntent action(String str, int i) {
        return PendingIntent.getService(this, i, new Intent(this, (Class<?>) YouTubePlaybackService.class).setAction(str), 201326592);
    }

    private Notification buildNotification() {
        int i = android.R.drawable.ic_media_play;
        PendingIntent activity = PendingIntent.getActivity(this, 10, new Intent(this, (Class<?>) MainActivity.class).addFlags(603979776), 201326592);
        int identifier = getResources().getIdentifier("ic_notification", "drawable", getPackageName());
        if (identifier == 0) {
            identifier = 17301540;
        }
        Notification.Builder builderAddAction = new Notification.Builder(this, CHANNEL_ID).setSmallIcon(identifier).setContentTitle(this.title).setContentText((this.artist.isEmpty() ? "YouTube" : this.artist) + " · Pulse Player").setContentIntent(activity).setVisibility(1).setOnlyAlertOnce(true).setShowWhen(false).setOngoing(this.playing).setDeleteIntent(action(ACT_CLOSE, 25)).addAction(android.R.drawable.ic_media_previous, "이전", action(ACT_PREV, 21));
        if (this.playing) {
            i = android.R.drawable.ic_media_pause;
        }
        Notification.Builder builderAddAction2 = builderAddAction.addAction(i, this.playing ? "일시정지" : "재생", action(ACT_TOGGLE, 22)).addAction(android.R.drawable.ic_media_next, "다음", action(ACT_NEXT, 23)).addAction(android.R.drawable.ic_menu_close_clear_cancel, "닫기", action(ACT_CLOSE, 24));
        if (this.art != null) {
            builderAddAction2.setLargeIcon(this.art);
        }
        if (this.session != null) {
            builderAddAction2.setStyle(new Notification.MediaStyle().setMediaSession(this.session.getSessionToken()).setShowActionsInCompactView(0, 1, 2));
        }
        return builderAddAction2.build();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void refresh() {
        if (this.session != null) {
            this.session.setPlaybackState(new PlaybackState.Builder().setActions(823L).setState(this.playing ? 3 : 2, this.position, this.playing ? 1.0f : 0.0f, SystemClock.elapsedRealtime()).build());
            MediaMetadata.Builder builderPutLong = new MediaMetadata.Builder().putString("android.media.metadata.TITLE", this.title).putString("android.media.metadata.ARTIST", this.artist.isEmpty() ? "YouTube" : this.artist).putLong("android.media.metadata.DURATION", this.duration);
            if (this.art != null) {
                builderPutLong.putBitmap("android.media.metadata.ALBUM_ART", this.art);
            }
            this.session.setMetadata(builderPutLong.build());
        }
        if (this.foreground) {
            ((NotificationManager) getSystemService("notification")).notify(NOTIFICATION_ID, buildNotification());
        }
    }

    private void shutdown() {
        releaseLocks();
        abandonFocus();
        this.foreground = false;
        stopForeground(true);
        stopSelf();
    }

    @Override // android.app.Service
    public void onTaskRemoved(Intent intent) {
        shutdown();
        super.onTaskRemoved(intent);
    }

    @Override // android.app.Service
    public void onDestroy() {
        releaseLocks();
        abandonFocus();
        if (this.noisyRegistered) {
            try {
                unregisterReceiver(this.noisyReceiver);
            } catch (Exception e) {
            }
            this.noisyRegistered = false;
        }
        if (this.session != null) {
            this.session.setActive(false);
            this.session.release();
            this.session = null;
        }
        instance = null;
        super.onDestroy();
    }

    @Override // android.app.Service
    public IBinder onBind(Intent intent) {
        return null;
    }
}
