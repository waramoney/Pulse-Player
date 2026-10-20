package com.pulse.player;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
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
import android.os.IBinder;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Native audio playback (including screen-off playback), without network or third-party SDKs.
 * Commands arrive from the local-only WebView bridge or Android media notification.
 */
public final class AudioPlaybackService extends Service implements AudioManager.OnAudioFocusChangeListener {
    public static final String ACT_PLAY = "com.pulse.player.PLAY";
    public static final String ACT_PAUSE = "com.pulse.player.PAUSE";
    public static final String ACT_RESUME = "com.pulse.player.RESUME";
    public static final String ACT_TOGGLE = "com.pulse.player.TOGGLE";
    public static final String ACT_NEXT = "com.pulse.player.NEXT";
    public static final String ACT_PREVIOUS = "com.pulse.player.PREVIOUS";
    public static final String ACT_SEEK = "com.pulse.player.SEEK";
    public static final String ACT_OPTIONS = "com.pulse.player.OPTIONS";
    public static final String ACT_STOP = "com.pulse.player.STOP";

    public static AudioPlaybackService instance;
    private static final String CHANNEL_ID = "pulse_audio_playback";
    private static final int NOTIFICATION_ID = 42;

    private final Random random = new Random();
    private MediaPlayer player;
    private MediaSession mediaSession;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private SharedPreferences prefs;
    private String currentId = "";
    private String currentTitle = "Pulse Player";
    private String currentArtist = "내 음악";
    private boolean prepared;
    private boolean preparing;
    private boolean playWhenPrepared;
    private boolean foreground;
    private boolean shuffle;
    private int repeat; // 0 none, 1 all, 2 one
    private float speed;
    private String playbackError = "";

    private static final class Track {
        String id;
        String title;
        String artist;
        Track(String id, String title, String artist) {
            this.id = id;
            this.title = title;
            this.artist = artist;
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        prefs = getSharedPreferences("pulse", MODE_PRIVATE);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        readOptions();
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "음악 재생", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("백그라운드 음악 재생 컨트롤");
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
        mediaSession = new MediaSession(this, "PulsePlayback");
        mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { resumeTrack(); }
            @Override public void onPause() { pauseTrack(); }
            @Override public void onSkipToNext() { skip(1, false); }
            @Override public void onSkipToPrevious() { skip(-1, false); }
            @Override public void onSeekTo(long pos) { seek((int) pos); }
            @Override public void onStop() { stopSelf(); }
        });
        mediaSession.setActive(true);
        syncMediaSession();
    }

    private void readOptions() {
        shuffle = prefs.getBoolean("shuffle", false);
        repeat = prefs.getInt("repeat", 0);
        speed = Math.max(.5f, Math.min(2f, prefs.getFloat("speed", 1f)));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return currentId.isEmpty() ? START_NOT_STICKY : START_STICKY;
        String action = intent.getAction();
        if (action == null) return START_NOT_STICKY;
        switch (action) {
            case ACT_PLAY:
                String id = intent.getStringExtra("id");
                if (id != null && !id.isEmpty()) playTrack(id);
                break;
            case ACT_TOGGLE:
                if (isPlaying()) pauseTrack(); else resumeTrack();
                break;
            case ACT_PAUSE: pauseTrack(); break;
            case ACT_RESUME: resumeTrack(); break;
            case ACT_NEXT: skip(1, false); break;
            case ACT_PREVIOUS: skip(-1, false); break;
            case ACT_SEEK: seek(intent.getIntExtra("position", 0)); break;
            case ACT_OPTIONS:
                readOptions();
                applySpeed();
                refresh();
                break;
            case ACT_STOP:
                stopSelf();
                break;
        }
        return START_NOT_STICKY;
    }

    private List<Track> queue() {
        List<Track> items = new ArrayList<>();
        try {
            JSONArray json = new JSONArray(prefs.getString("library", "[]"));
            for (int i = 0; i < json.length(); i++) {
                JSONObject row = json.optJSONObject(i);
                if (row == null || !"audio".equals(row.optString("type"))) continue;
                String id = row.optString("id");
                if (!id.isEmpty()) items.add(new Track(id, row.optString("name", "이름 없는 음악"),
                        row.optString("artist", "내 음악")));
            }
        } catch (Exception ignored) { }
        return items;
    }

    private Track findTrack(String id) {
        for (Track track : queue()) if (track.id.equals(id)) return track;
        return null;
    }

    private void ensureForeground() {
        if (!foreground) {
            startForeground(NOTIFICATION_ID, notification());
            foreground = true;
        }
    }

    private void playTrack(String id) {
        Track track = findTrack(id);
        if (track == null) return;
        if (id.equals(currentId) && (prepared || preparing)) { resumeTrack(); return; }
        releasePlayer();
        if (focusRequest != null) {
            audioManager.abandonAudioFocusRequest(focusRequest);
            focusRequest = null;
        }
        currentId = id;
        currentTitle = track.title;
        currentArtist = track.artist;
        prefs.edit().putString("lastId", id).apply();
        playbackError = "";
        preparing = true;
        playWhenPrepared = true;
        // Foreground service promotion precedes async media initialization.
        ensureForeground();
        try {
            AudioAttributes audioAttributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(audioAttributes)
                    .setOnAudioFocusChangeListener(this).build();
            audioManager.requestAudioFocus(focusRequest);
            player = new MediaPlayer();
            player.setAudioAttributes(audioAttributes);
            player.setDataSource(this, Uri.parse(id));
            player.setOnPreparedListener(mp -> {
                preparing = false;
                prepared = true;
                if (playWhenPrepared) {
                    mp.start();
                    applySpeed();
                }
                refresh();
            });
            player.setOnCompletionListener(mp -> {
                if (repeat == 2) {
                    mp.seekTo(0);
                    mp.start();
                } else {
                    skip(1, true);
                }
                refresh();
            });
            player.setOnErrorListener((mp, what, extra) -> {
                playbackError = "이 파일을 재생할 수 없습니다.";
                releasePlayer();
                refresh();
                return true;
            });
            player.prepareAsync();
            refresh();
        } catch (Exception e) {
            playbackError = "재생 실패: 파일 형식 또는 파일 접근 권한을 확인하세요.";
            releasePlayer();
            refresh();
        }
    }

    private void releasePlayer() {
        prepared = false;
        preparing = false;
        if (player != null) {
            try { player.reset(); } catch (Exception ignored) { }
            try { player.release(); } catch (Exception ignored) { }
            player = null;
        }
    }

    public boolean isPlaying() {
        if (!prepared || player == null) return false;
        try { return player.isPlaying(); } catch (IllegalStateException e) { return false; }
    }

    private void pauseTrack() {
        playWhenPrepared = false;
        if (isPlaying()) player.pause();
        refresh();
    }

    private void resumeTrack() {
        if (preparing) { playWhenPrepared = true; return; }
        if (prepared && player != null && !isPlaying()) {
            player.start();
            applySpeed();
        }
        refresh();
    }

    private void seek(int pos) {
        if (prepared && player != null) {
            try { player.seekTo(Math.max(0, Math.min(pos, player.getDuration()))); }
            catch (Exception ignored) { }
        }
        refresh();
    }

    private void applySpeed() {
        if (prepared && player != null && isPlaying()) {
            try { player.setPlaybackParams(player.getPlaybackParams().setSpeed(speed)); }
            catch (Exception ignored) { }
        }
    }

    /** Automatic completion respects off/all/one; manual skips always wrap. */
    private void skip(int direction, boolean automatic) {
        List<Track> list = queue();
        if (list.isEmpty()) { stopSelf(); return; }
        int current = -1;
        for (int i = 0; i < list.size(); i++) if (list.get(i).id.equals(currentId)) { current = i; break; }
        int next;
        if (shuffle && list.size() > 1) {
            next = random.nextInt(list.size() - 1);
            if (current >= 0 && next >= current) next++;
        } else if (current == -1) {
            next = 0;
        } else {
            next = current + direction;
            if (next >= list.size()) {
                if (automatic && repeat != 1) { stopSelf(); return; }
                next = 0;
            } else if (next < 0) {
                next = list.size() - 1;
            }
        }
        playTrack(list.get(next).id);
    }

    private PendingIntent actionIntent(String action, int requestCode) {
        Intent intent = new Intent(this, AudioPlaybackService.class).setAction(action);
        return PendingIntent.getService(this, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification notification() {
        Intent launch = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openApp = PendingIntent.getActivity(this, 0, launch,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        boolean playing = isPlaying() || (preparing && playWhenPrepared);
        int toggleIcon = playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play;
        String toggleText = playing ? "일시정지" : "재생";
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(currentTitle)
                .setContentText(currentArtist + " · Pulse Player")
                .setContentIntent(openApp)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setOngoing(playing)
                .setShowWhen(false)
                .addAction(android.R.drawable.ic_media_previous, "이전", actionIntent(ACT_PREVIOUS, 1))
                .addAction(toggleIcon, toggleText, actionIntent(ACT_TOGGLE, 2))
                .addAction(android.R.drawable.ic_media_next, "다음", actionIntent(ACT_NEXT, 3));
        if (mediaSession != null) {
            builder.setStyle(new Notification.MediaStyle()
                    .setMediaSession(mediaSession.getSessionToken())
                    .setShowActionsInCompactView(0, 1, 2));
        }
        return builder.build();
    }

    private void syncMediaSession() {
        if (mediaSession == null) return;
        int state = preparing ? PlaybackState.STATE_BUFFERING
                : isPlaying() ? PlaybackState.STATE_PLAYING
                : PlaybackState.STATE_PAUSED;
        long position = 0;
        long duration = 0;
        if (prepared && player != null) {
            try { position = player.getCurrentPosition(); duration = player.getDuration(); }
            catch (Exception ignored) { }
        }
        PlaybackState playbackState = new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT
                        | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO
                        | PlaybackState.ACTION_STOP)
                .setState(state, position, isPlaying() ? speed : 0f,
                        SystemClock.elapsedRealtime()).build();
        mediaSession.setPlaybackState(playbackState);
        mediaSession.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, currentTitle)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, currentArtist)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, duration)
                .build());
    }

    private void refresh() {
        syncMediaSession();
        if (foreground) {
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                    .notify(NOTIFICATION_ID, notification());
        }
    }

    /** Called on the Activity UI thread only. */
    public JSONObject snapshot() {
        JSONObject out = new JSONObject();
        try {
            int position = 0, duration = 0;
            if (prepared && player != null) {
                try { position = player.getCurrentPosition(); duration = player.getDuration(); }
                catch (Exception ignored) { }
            }
            out.put("id", currentId);
            out.put("playing", isPlaying());
            out.put("preparing", preparing);
            out.put("position", position);
            out.put("duration", duration);
            out.put("error", playbackError);
            out.put("shuffle", shuffle);
            out.put("repeat", repeat);
            out.put("speed", speed);
            out.put("video", false);
        } catch (Exception ignored) { }
        return out;
    }

    @Override public void onAudioFocusChange(int focus) {
        if (focus == AudioManager.AUDIOFOCUS_LOSS || focus == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            pauseTrack();
        } else if (focus == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            if (player != null) player.setVolume(.25f, .25f);
        } else if (focus == AudioManager.AUDIOFOCUS_GAIN && player != null) {
            player.setVolume(1f, 1f);
        }
    }

    @Override public void onDestroy() {
        foreground = false;
        releasePlayer();
        if (audioManager != null && focusRequest != null) audioManager.abandonAudioFocusRequest(focusRequest);
        if (mediaSession != null) { mediaSession.setActive(false); mediaSession.release(); }
        instance = null;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
