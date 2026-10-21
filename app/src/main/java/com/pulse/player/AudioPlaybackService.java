package com.pulse.player;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import java.io.IOException;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.json.JSONArray;
import org.json.JSONObject;

/* JADX INFO: loaded from: classes2.dex */
public final class AudioPlaybackService extends Service implements AudioManager.OnAudioFocusChangeListener {
    public static final String ACT_NEXT = "com.pulse.player.NEXT";
    public static final String ACT_OPTIONS = "com.pulse.player.OPTIONS";
    public static final String ACT_PAUSE = "com.pulse.player.PAUSE";
    public static final String ACT_PLAY = "com.pulse.player.PLAY";
    public static final String ACT_PREVIOUS = "com.pulse.player.PREVIOUS";
    public static final String ACT_RESUME = "com.pulse.player.RESUME";
    public static final String ACT_SEEK = "com.pulse.player.SEEK";
    public static final String ACT_STOP = "com.pulse.player.STOP";
    public static final String ACT_TOGGLE = "com.pulse.player.TOGGLE";
    private static final String CHANNEL_ID = "pulse_audio_playback";
    private static final int NOTIFICATION_ID = 42;
    public static AudioPlaybackService instance;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private boolean foreground;
    private MediaSession mediaSession;
    private boolean playWhenPrepared;
    private MediaPlayer player;
    private SharedPreferences prefs;
    private boolean prepared;
    private boolean preparing;
    private int repeat;
    private boolean shuffle;
    private float speed;
    private final Random random = new Random();
    private String currentId = "";
    private String currentTitle = "Pulse Player";
    private String currentArtist = "내 음악";
    private String playbackError = "";

    private static final class Track {
        String artist;
        String id;
        String title;

        Track(String id, String title, String artist) {
            this.id = id;
            this.title = title;
            this.artist = artist;
        }
    }

    @Override // android.app.Service
    public void onCreate() {
        super.onCreate();
        instance = this;
        this.prefs = getSharedPreferences("pulse", 0);
        this.audioManager = (AudioManager) getSystemService("audio");
        readOptions();
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "음악 재생", 2);
        channel.setDescription("백그라운드 음악 재생 컨트롤");
        ((NotificationManager) getSystemService("notification")).createNotificationChannel(channel);
        this.mediaSession = new MediaSession(this, "PulsePlayback");
        this.mediaSession.setFlags(3);
        this.mediaSession.setCallback(new MediaSession.Callback() { // from class: com.pulse.player.AudioPlaybackService.1
            @Override // android.media.session.MediaSession.Callback
            public void onPlay() {
                AudioPlaybackService.this.resumeTrack();
            }

            @Override // android.media.session.MediaSession.Callback
            public void onPause() {
                AudioPlaybackService.this.pauseTrack();
            }

            @Override // android.media.session.MediaSession.Callback
            public void onSkipToNext() {
                AudioPlaybackService.this.skip(1, false);
            }

            @Override // android.media.session.MediaSession.Callback
            public void onSkipToPrevious() {
                AudioPlaybackService.this.skip(-1, false);
            }

            @Override // android.media.session.MediaSession.Callback
            public void onSeekTo(long pos) {
                AudioPlaybackService.this.seek((int) pos);
            }

            @Override // android.media.session.MediaSession.Callback
            public void onStop() {
                AudioPlaybackService.this.stopSelf();
            }
        });
        this.mediaSession.setActive(true);
        syncMediaSession();
    }

    private void readOptions() {
        this.shuffle = this.prefs.getBoolean("shuffle", false);
        this.repeat = this.prefs.getInt("repeat", 0);
        this.speed = Math.max(0.5f, Math.min(2.0f, this.prefs.getFloat("speed", 1.0f)));
    }

    /* JADX WARN: Can't fix incorrect switch cases order, some code will duplicate */
    @Override // android.app.Service
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return this.currentId.isEmpty() ? 2 : 1;
        }
        String action = intent.getAction();
        if (action == null) {
            return 2;
        }
        switch (action) {
            case "com.pulse.player.PLAY":
                String id = intent.getStringExtra("id");
                if (id != null && !id.isEmpty()) {
                    playTrack(id);
                }
                return 2;
            case "com.pulse.player.TOGGLE":
                if (isPlaying()) {
                    pauseTrack();
                } else {
                    resumeTrack();
                }
                return 2;
            case "com.pulse.player.PAUSE":
                pauseTrack();
                return 2;
            case "com.pulse.player.RESUME":
                resumeTrack();
                return 2;
            case "com.pulse.player.NEXT":
                skip(1, false);
                return 2;
            case "com.pulse.player.PREVIOUS":
                skip(-1, false);
                return 2;
            case "com.pulse.player.SEEK":
                seek(intent.getIntExtra("position", 0));
                return 2;
            case "com.pulse.player.OPTIONS":
                readOptions();
                applySpeed();
                refresh();
                return 2;
            case "com.pulse.player.STOP":
                stopSelf();
                return 2;
            default:
                return 2;
        }
    }

    private List<Track> allAudio() {
        List<Track> items = new ArrayList<>();
        try {
            JSONArray json = new JSONArray(this.prefs.getString("library", "[]"));
            for (int i = 0; i < json.length(); i++) {
                JSONObject row = json.optJSONObject(i);
                if (row != null && "audio".equals(row.optString("type"))) {
                    String id = row.optString("id");
                    if (id.isEmpty()) {
                        continue;
                    } else {
                        items.add(new Track(id, row.optString("name", "이름 없는 음악"), row.optString("artist", "내 음악")));
                    }
                }
            }
        } catch (Exception e) {
        }
        return items;
    }

    private List<Track> queue() {
        JSONArray lists;
        List<Track> library = allAudio();
        try {
            JSONObject data = new JSONObject(this.prefs.getString("collections", "{}"));
            String selectedId = data.optString("queueId");
            if (selectedId.isEmpty() || (lists = data.optJSONArray("lists")) == null) {
                return library;
            }
            for (int i = 0; i < lists.length(); i++) {
                JSONObject list = lists.optJSONObject(i);
                if (list != null && selectedId.equals(list.optString("id"))) {
                    JSONArray ids = list.optJSONArray("tracks");
                    List<Track> ordered = new ArrayList<>();
                    if (ids != null) {
                        for (int j = 0; j < ids.length(); j++) {
                            String id = ids.optString(j);
                            for (Track item : library) {
                                if (item.id.equals(id) && !ordered.contains(item)) {
                                    ordered.add(item);
                                    break;
                                }
                            }
                        }
                    }
                    return ordered;
                }
            }
        } catch (Exception e) {
        }
        return library;
    }

    private Track findTrack(String id) {
        for (Track item : allAudio()) {
            if (item.id.equals(id)) {
                return item;
            }
        }
        return null;
    }

    private void ensureForeground() {
        if (!this.foreground) {
            startForeground(NOTIFICATION_ID, notification());
            this.foreground = true;
        }
    }

    private void playTrack(String id) {
        Track track = findTrack(id);
        if (track == null) {
            this.currentId = id;
            this.playbackError = "곡 정보를 찾을 수 없습니다. 라이브러리를 새로고침하거나 파일을 다시 추가하세요.";
            this.preparing = false;
            this.prepared = false;
            refresh();
            return;
        }
        if (id.equals(this.currentId) && (this.prepared || this.preparing)) {
            resumeTrack();
            return;
        }
        releasePlayer();
        if (this.focusRequest != null) {
            this.audioManager.abandonAudioFocusRequest(this.focusRequest);
            this.focusRequest = null;
        }
        this.currentId = id;
        this.currentTitle = track.title;
        this.currentArtist = track.artist;
        this.prefs.edit().putString("lastId", id).apply();
        this.playbackError = "";
        this.preparing = true;
        this.playWhenPrepared = true;
        ensureForeground();
        try {
            AudioAttributes audioAttributes = new AudioAttributes.Builder().setUsage(1).setContentType(2).build();
            this.focusRequest = new AudioFocusRequest.Builder(1).setAudioAttributes(audioAttributes).setOnAudioFocusChangeListener(this).build();
            this.audioManager.requestAudioFocus(this.focusRequest);
            this.player = new MediaPlayer();
            this.player.setAudioAttributes(audioAttributes);
            // WAKE_LOCK is declared in AndroidManifest. Keep playback alive when
            // the screen locks; this is for local audio, not YouTube streams.
            this.player.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
            // Prefer a read-only descriptor for persisted picker and MediaStore
            // content URIs. This avoids provider-specific content URI failures.
            setTrackDataSource(this.player, Uri.parse(id));
            this.player.setOnPreparedListener(new MediaPlayer.OnPreparedListener() { // from class: com.pulse.player.AudioPlaybackService$$ExternalSyntheticLambda0
                @Override // android.media.MediaPlayer.OnPreparedListener
                public final void onPrepared(MediaPlayer mediaPlayer) {
                    AudioPlaybackService.this.lambda$playTrack$0(mediaPlayer);
                }
            });
            this.player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() { // from class: com.pulse.player.AudioPlaybackService$$ExternalSyntheticLambda1
                @Override // android.media.MediaPlayer.OnCompletionListener
                public final void onCompletion(MediaPlayer mediaPlayer) {
                    AudioPlaybackService.this.lambda$playTrack$1(mediaPlayer);
                }
            });
            this.player.setOnErrorListener(new MediaPlayer.OnErrorListener() { // from class: com.pulse.player.AudioPlaybackService$$ExternalSyntheticLambda2
                @Override // android.media.MediaPlayer.OnErrorListener
                public final boolean onError(MediaPlayer mediaPlayer, int i, int i2) {
                    return AudioPlaybackService.this.lambda$playTrack$2(mediaPlayer, i, i2);
                }
            });
            this.player.prepareAsync();
            refresh();
        } catch (SecurityException e) {
            this.playbackError = "음악 파일 접근이 차단되었습니다. 설정에서 음악 권한을 허용하거나 파일을 다시 추가하세요.";
            releasePlayer();
            refresh();
        } catch (Exception e) {
            this.playbackError = "파일 재생 실패 (" + e.getClass().getSimpleName() + "). 파일이 저장돼 있는지 또는 코덱을 확인하세요.";
            releasePlayer();
            refresh();
        }
    }

    private void setTrackDataSource(MediaPlayer mediaPlayer, Uri uri) throws IOException {
        if ("content".equalsIgnoreCase(uri.getScheme())) {
            // MediaPlayer.setDataSource(Context, Uri) does not work uniformly on
            // all Android content providers; a granted file descriptor is safer.
            try (AssetFileDescriptor descriptor = getContentResolver().openAssetFileDescriptor(uri, "r")) {
                if (descriptor == null) throw new IOException("Media content not found");
                long length = descriptor.getLength();
                if (length >= 0) {
                    mediaPlayer.setDataSource(descriptor.getFileDescriptor(), descriptor.getStartOffset(), length);
                } else {
                    mediaPlayer.setDataSource(descriptor.getFileDescriptor());
                }
            }
        } else {
            mediaPlayer.setDataSource(this, uri);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$playTrack$0(MediaPlayer mp) {
        this.preparing = false;
        this.prepared = true;
        if (this.playWhenPrepared) {
            try {
                mp.start();
                applySpeed();
            } catch (Exception e) {
                this.playbackError = "음악을 시작하지 못했습니다 (" + e.getClass().getSimpleName() + ").";
                releasePlayer();
            }
        }
        refresh();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$playTrack$1(MediaPlayer mp) {
        if (this.repeat == 2) {
            mp.seekTo(0);
            mp.start();
        } else {
            skip(1, true);
        }
        refresh();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ boolean lambda$playTrack$2(MediaPlayer mp, int what, int extra) {
        this.playbackError = "이 파일을 재생할 수 없습니다. 오류 코드: " + what + "/" + extra + ". 파일 권한·코덱을 확인하세요.";
        releasePlayer();
        refresh();
        return true;
    }

    private void releasePlayer() {
        this.prepared = false;
        this.preparing = false;
        if (this.player != null) {
            try {
                this.player.reset();
            } catch (Exception e) {
            }
            try {
                this.player.release();
            } catch (Exception e2) {
            }
            this.player = null;
        }
    }

    public boolean isPlaying() {
        if (!this.prepared || this.player == null) {
            return false;
        }
        try {
            return this.player.isPlaying();
        } catch (IllegalStateException e) {
            return false;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void pauseTrack() {
        this.playWhenPrepared = false;
        if (isPlaying()) {
            this.player.pause();
        }
        refresh();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void resumeTrack() {
        if (this.preparing) {
            this.playWhenPrepared = true;
            return;
        }
        if (this.prepared && this.player != null && !isPlaying()) {
            try {
                this.player.start();
                applySpeed();
                this.playbackError = "";
            } catch (IllegalStateException e) {
                this.playbackError = "일시정지된 파일을 다시 시작하지 못했습니다. 다시 선택해 주세요.";
                releasePlayer();
            }
        }
        refresh();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void seek(int pos) {
        if (this.prepared && this.player != null) {
            try {
                this.player.seekTo(Math.max(0, Math.min(pos, this.player.getDuration())));
            } catch (Exception e) {
            }
        }
        refresh();
    }

    private void applySpeed() {
        if (this.prepared && this.player != null && isPlaying()) {
            try {
                this.player.setPlaybackParams(this.player.getPlaybackParams().setSpeed(this.speed));
            } catch (Exception e) {
            }
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void skip(int direction, boolean automatic) {
        int next;
        List<Track> list = queue();
        if (list.isEmpty()) {
            stopSelf();
            return;
        }
        int current = -1;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(this.currentId)) {
                current = i;
                break;
            }
        }
        if (this.shuffle && list.size() > 1) {
            next = this.random.nextInt(list.size() - 1);
            if (current >= 0 && next >= current) {
                next++;
            }
        } else if (current == -1) {
            next = 0;
        } else {
            next = current + direction;
            if (next >= list.size()) {
                if (automatic && this.repeat != 1) {
                    stopSelf();
                    return;
                }
                next = 0;
            } else if (next < 0) {
                next = list.size() - 1;
            }
        }
        playTrack(list.get(next).id);
    }

    private PendingIntent actionIntent(String action, int requestCode) {
        Intent intent = new Intent(this, (Class<?>) AudioPlaybackService.class).setAction(action);
        return PendingIntent.getService(this, requestCode, intent, 201326592);
    }

    private Notification notification() {
        Intent launch = new Intent(this, (Class<?>) MainActivity.class).addFlags(603979776);
        PendingIntent openApp = PendingIntent.getActivity(this, 0, launch, 201326592);
        boolean playing = isPlaying() || (this.preparing && this.playWhenPrepared);
        int toggleIcon = playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play;
        String toggleText = playing ? "일시정지" : "재생";
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID).setSmallIcon(R.drawable.ic_notification).setContentTitle(this.currentTitle).setContentText(this.currentArtist + " · Pulse Player").setContentIntent(openApp).setVisibility(1).setOnlyAlertOnce(true).setOngoing(playing).setShowWhen(false).addAction(android.R.drawable.ic_media_previous, "이전", actionIntent(ACT_PREVIOUS, 1)).addAction(toggleIcon, toggleText, actionIntent(ACT_TOGGLE, 2)).addAction(android.R.drawable.ic_media_next, "다음", actionIntent(ACT_NEXT, 3));
        if (this.mediaSession != null) {
            builder.setStyle(new Notification.MediaStyle().setMediaSession(this.mediaSession.getSessionToken()).setShowActionsInCompactView(0, 1, 2));
        }
        return builder.build();
    }

    private void syncMediaSession() {
        int state;
        long position;
        long duration;
        if (this.mediaSession == null) {
            return;
        }
        if (this.preparing) {
            state = 6;
        } else {
            state = isPlaying() ? 3 : 2;
        }
        long position2 = 0;
        long duration2 = 0;
        if (this.prepared && this.player != null) {
            try {
                position2 = this.player.getCurrentPosition();
                duration2 = this.player.getDuration();
            } catch (Exception e) {
            }
            position = position2;
            duration = duration2;
        } else {
            position = 0;
            duration = 0;
        }
        PlaybackState playbackState = new PlaybackState.Builder().setActions(823L).setState(state, position, isPlaying() ? this.speed : 0.0f, SystemClock.elapsedRealtime()).build();
        this.mediaSession.setPlaybackState(playbackState);
        this.mediaSession.setMetadata(new MediaMetadata.Builder().putString("android.media.metadata.TITLE", this.currentTitle).putString("android.media.metadata.ARTIST", this.currentArtist).putLong("android.media.metadata.DURATION", duration).build());
    }

    private void refresh() {
        syncMediaSession();
        if (this.foreground) {
            ((NotificationManager) getSystemService("notification")).notify(NOTIFICATION_ID, notification());
        }
    }

    public JSONObject snapshot() {
        JSONObject out = new JSONObject();
        int position = 0;
        int duration = 0;
        try {
            if (this.prepared && this.player != null) {
                try {
                    position = this.player.getCurrentPosition();
                    duration = this.player.getDuration();
                } catch (Exception e) {
                }
            }
            out.put("id", this.currentId);
            out.put("playing", isPlaying());
            out.put("preparing", this.preparing);
            out.put("position", position);
            out.put("duration", duration);
            out.put("error", this.playbackError);
            out.put("shuffle", this.shuffle);
            out.put("repeat", this.repeat);
            out.put("speed", this.speed);
            out.put("video", false);
        } catch (Exception e2) {
        }
        return out;
    }

    @Override // android.media.AudioManager.OnAudioFocusChangeListener
    public void onAudioFocusChange(int focus) {
        if (focus == -1 || focus == -2) {
            pauseTrack();
            return;
        }
        if (focus == -3) {
            if (this.player != null) {
                this.player.setVolume(0.25f, 0.25f);
            }
        } else if (focus == 1 && this.player != null) {
            this.player.setVolume(1.0f, 1.0f);
        }
    }

    @Override // android.app.Service
    public void onDestroy() {
        this.foreground = false;
        releasePlayer();
        if (this.audioManager != null && this.focusRequest != null) {
            this.audioManager.abandonAudioFocusRequest(this.focusRequest);
        }
        if (this.mediaSession != null) {
            this.mediaSession.setActive(false);
            this.mediaSession.release();
        }
        instance = null;
        super.onDestroy();
    }

    @Override // android.app.Service
    public IBinder onBind(Intent intent) {
        return null;
    }
}
