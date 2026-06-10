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
import androidx.core.app.NotificationCompat;

public class StreamService extends Service {

    private static final String TAG = "StreamService";
    private static final String CHANNEL_ID = "stream_service_channel";
    private static final int NOTIFICATION_ID = 1;

    public static final String ACTION_START   = "com.miniobs.app.START_STREAM";
    public static final String ACTION_STOP    = "com.miniobs.app.STOP_STREAM";
    public static final String EXTRA_RTMP_URL = "rtmp_url";

    private final IBinder binder = new LocalBinder();
    private RtmpClient rtmpClient;
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

        rtmpClient = new RtmpClient();
        rtmpClient.setCallback(new RtmpClient.Callback() {
            @Override public void onConnected() {
                streaming = true;
                updateNotification("🔴 Streaming live");
                if (stateListener != null) stateListener.onStreamStarted();
            }
            @Override public void onDisconnected() {
                streaming = false;
                updateNotification("Stream stopped");
                if (stateListener != null) stateListener.onStreamStopped();
            }
            @Override public void onError(String reason) {
                streaming = false;
                audioMixer.stop();
                updateNotification("Connection failed");
                if (stateListener != null) stateListener.onConnectionFailed(reason);
            }
        });

        audioMixer = new AudioMixer();
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MiniOBS::StreamWakeLock");
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
        if (wakeLock != null && !wakeLock.isHeld())
            wakeLock.acquire(10 * 60 * 60 * 1000L);
        audioMixer.start();
        rtmpClient.connect(rtmpUrl);
        Log.i(TAG, "Connecting → " + rtmpUrl);
    }

    public void stopStreaming() {
        if (!streaming) return;
        rtmpClient.disconnect();
        audioMixer.stop();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        streaming = false;
        updateNotification("Stream stopped");
        if (stateListener != null) stateListener.onStreamStopped();
    }

    public void sendVideoData(byte[] data, int length, long timestamp) {
        if (!streaming) return;
        byte[] chunk = new byte[length];
        System.arraycopy(data, 0, chunk, 0, length);
        rtmpClient.sendVideo(chunk, true, timestamp);
    }

    public void sendAudioData(byte[] data, int length, long timestamp) {
        if (!streaming) return;
        byte[] chunk = new byte[length];
        System.arraycopy(data, 0, chunk, 0, length);
        rtmpClient.sendAudio(chunk, timestamp);
    }

    public void setVolumes(float mic, float game) {
        audioMixer.setMicVolume(mic);
        audioMixer.setGameVolume(game);
    }

    public boolean isStreaming() { return streaming; }
    public void setStateListener(StreamStateListener l) { stateListener = l; }
    public AudioMixer getAudioMixer() { return audioMixer; }

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
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(ch);
    }

    @Override
    public void onDestroy() {
        stopStreaming();
        super.onDestroy();
    }
}
