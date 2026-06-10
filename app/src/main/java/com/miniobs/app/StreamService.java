package com.miniobs.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import com.pedro.common.ConnectChecker;
import com.pedro.library.rtmp.RtmpStream;
import java.nio.ByteBuffer;

public class StreamService extends Service implements ConnectChecker {

    private static final String TAG = "StreamService";
    private static final String CHANNEL_ID = "stream_service_channel";
    private static final int NOTIFICATION_ID = 1;

    public static final String ACTION_START   = "com.miniobs.app.START_STREAM";
    public static final String ACTION_STOP    = "com.miniobs.app.STOP_STREAM";
    public static final String EXTRA_RTMP_URL = "rtmp_url";

    private final IBinder binder = new LocalBinder();
    private RtmpStream rtmpStream;
    private AudioMixer audioMixer;
    private PowerManager.WakeLock wakeLock;
    private boolean streaming = false;
    private StreamStateListener stateListener;

    public interface StreamStateListener {
        void onStreamStarted();
        void onStreamStopped();
        void onConnectionFailed(String message);
        void onBitrateChanged(long bitrate);
    }

    public class LocalBinder extends Binder {
        public StreamService getService() { return StreamService.this; }
    }

    @Override
    public IBinder onBind(Intent intent) { return binder; }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        rtmpStream = new RtmpStream(this, this);
        audioMixer = new AudioMixer();

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, "MiniOBS::StreamWakeLock");
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_START.equals(intent.getAction())) {
            String url = intent.getStringExtra(EXTRA_RTMP_URL);
            startForeground(NOTIFICATION_ID, buildNotification("Connecting…"));
            startStreaming(url);
        } else if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopStreaming();
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    public void startStreaming(String rtmpUrl) {
        if (streaming || rtmpUrl == null || rtmpUrl.isEmpty()) return;

        StreamConfig cfg = StreamConfig.getInstance();

        boolean audioReady = rtmpStream.prepareAudio(
                cfg.AUDIO_SAMPLE_RATE,
                cfg.AUDIO_STEREO,
                cfg.AUDIO_BITRATE
        );
        boolean videoReady = rtmpStream.prepareVideo(
                cfg.VIDEO_WIDTH,
                cfg.VIDEO_HEIGHT,
                cfg.VIDEO_FPS,
                cfg.VIDEO_BITRATE
        );

        if (!audioReady || !videoReady) {
            Log.e(TAG, "Failed to prepare stream (audio=" + audioReady + " video=" + videoReady + ")");
            return;
        }

        rtmpStream.startStream(rtmpUrl);
        audioMixer.start();

        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(10 * 60 * 60 * 1000L);
        }
        streaming = true;
        updateNotification("🔴 Streaming live");
        Log.i(TAG, "Stream started → " + rtmpUrl);
    }

    public void stopStreaming() {
        if (!streaming) return;
        rtmpStream.stopStream();
        audioMixer.stop();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        streaming = false;
        updateNotification("Stream stopped");
        if (stateListener != null) stateListener.onStreamStopped();
        Log.i(TAG, "Stream stopped");
    }

    /** Feed raw H.264 NAL units received from the game phone into the stream. */
    public void sendVideoData(byte[] data, int length, long timestamp) {
        if (!streaming) return;
        ByteBuffer bb = ByteBuffer.wrap(data, 0, length);
        rtmpStream.sendVideo(bb, true, timestamp);
    }

    /** Feed raw PCM audio bytes into the stream. */
    public void sendAudioData(byte[] data, int length, long timestamp) {
        if (!streaming) return;
        ByteBuffer bb = ByteBuffer.wrap(data, 0, length);
        rtmpStream.sendAudio(bb, timestamp);
    }

    public void setVolumes(float mic, float game) {
        audioMixer.setMicVolume(mic);
        audioMixer.setGameVolume(game);
    }

    public boolean isStreaming() { return streaming; }
    public void setStateListener(StreamStateListener l) { stateListener = l; }
    public AudioMixer getAudioMixer() { return audioMixer; }

    // ── ConnectChecker callbacks ──────────────────────────────────────────────

    @Override
    public void onConnectionStarted(@NonNull String url) {
        Log.i(TAG, "Connecting to " + url);
    }

    @Override
    public void onConnectionSuccess() {
        Log.i(TAG, "RTMP connected");
        updateNotification("🔴 Live on YouTube");
        if (stateListener != null) stateListener.onStreamStarted();
    }

    @Override
    public void onConnectionFailed(@NonNull String reason) {
        Log.e(TAG, "RTMP failed: " + reason);
        updateNotification("Connection failed");
        streaming = false;
        audioMixer.stop();
        if (stateListener != null) stateListener.onConnectionFailed(reason);
    }

    @Override
    public void onNewBitrate(long bitrate) {
        if (stateListener != null) stateListener.onBitrateChanged(bitrate);
    }

    @Override
    public void onDisconnect() {
        Log.i(TAG, "RTMP disconnected");
        streaming = false;
        if (stateListener != null) stateListener.onStreamStopped();
    }

    @Override
    public void onAuthError() { Log.e(TAG, "RTMP auth error"); }

    @Override
    public void onAuthSuccess() { Log.i(TAG, "RTMP auth success"); }

    // ── Notification ──────────────────────────────────────────────────────────

    private Notification buildNotification(String text) {
        Intent stopIntent = new Intent(this, StreamService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(
                this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Mini OBS")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .addAction(android.R.drawable.ic_media_pause, "Stop", stopPi)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(text));
    }

    private void createNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Stream Service", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Controls the live stream");
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(ch);
    }

    @Override
    public void onDestroy() {
        stopStreaming();
        super.onDestroy();
    }
}
