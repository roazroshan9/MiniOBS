package com.miniobs.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCharacteristics;
import android.os.Binder;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import android.view.Surface;
import androidx.core.app.NotificationCompat;
import com.pedro.encoder.input.video.CameraHelper;
import com.pedro.rtmp.rtmp.RtmpClient;
import com.pedro.rtmp.utils.ConnectCheckerRtmp;
import java.nio.ByteBuffer;

public class StreamService extends Service implements ConnectCheckerRtmp {

    private static final String TAG = "StreamService";
    private static final String CHANNEL_ID = "stream_service_channel";
    private static final int NOTIFICATION_ID = 1;

    public static final String ACTION_START  = "com.miniobs.app.START_STREAM";
    public static final String ACTION_STOP   = "com.miniobs.app.STOP_STREAM";
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
        void onNewBitrateRtmp(long bitrate);
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
        rtmpClient = new RtmpClient(this);
        audioMixer = new AudioMixer();

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MiniOBS::StreamWakeLock");
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_START.equals(intent.getAction())) {
            String url = intent.getStringExtra(EXTRA_RTMP_URL);
            startForeground(NOTIFICATION_ID, buildNotification("Connecting..."));
            startStreaming(url);
        } else if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopStreaming();
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    public void startStreaming(String rtmpUrl) {
        if (streaming) return;
        if (rtmpUrl == null || rtmpUrl.isEmpty()) {
            Log.e(TAG, "RTMP URL is empty");
            return;
        }

        StreamConfig cfg = StreamConfig.getInstance();

        rtmpClient.setAudio(cfg.AUDIO_BITRATE, cfg.AUDIO_SAMPLE_RATE, cfg.AUDIO_STEREO);
        rtmpClient.setVideo(
                cfg.VIDEO_WIDTH,
                cfg.VIDEO_HEIGHT,
                cfg.VIDEO_FPS,
                cfg.VIDEO_BITRATE
        );

        rtmpClient.connect(rtmpUrl);
        audioMixer.start();

        if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire(10 * 60 * 60 * 1000L);
        streaming = true;
        updateNotification("🔴 Streaming live");
        Log.i(TAG, "Stream started → " + rtmpUrl);
    }

    public void stopStreaming() {
        if (!streaming) return;
        rtmpClient.disconnect();
        audioMixer.stop();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        streaming = false;
        updateNotification("Stream stopped");
        if (stateListener != null) stateListener.onStreamStopped();
        Log.i(TAG, "Stream stopped");
    }

    /** Feed a raw H.264 NAL unit (or mixed video bytes) coming from the game phone. */
    public void sendVideoData(byte[] data, int length, long timestamp) {
        if (!streaming || rtmpClient == null) return;
        ByteBuffer bb = ByteBuffer.wrap(data, 0, length);
        rtmpClient.sendVideo(bb, false, timestamp);
    }

    /** Feed mixed PCM audio into the RTMP encoder. */
    public void sendAudioData(byte[] data, int length, long timestamp) {
        if (!streaming || rtmpClient == null) return;
        ByteBuffer bb = ByteBuffer.wrap(data, 0, length);
        rtmpClient.sendAudio(bb, timestamp);
    }

    public void setVolumes(float mic, float game) {
        audioMixer.setMicVolume(mic);
        audioMixer.setGameVolume(game);
    }

    public boolean isStreaming() { return streaming; }
    public void setStateListener(StreamStateListener l) { stateListener = l; }
    public AudioMixer getAudioMixer() { return audioMixer; }

    // ── ConnectCheckerRtmp callbacks ──────────────────────────────────────────

    @Override
    public void onConnectionSuccessRtmp() {
        Log.i(TAG, "RTMP connected");
        updateNotification("🔴 Live on YouTube");
        if (stateListener != null) stateListener.onStreamStarted();
    }

    @Override
    public void onConnectionFailedRtmp(String reason) {
        Log.e(TAG, "RTMP connection failed: " + reason);
        updateNotification("Connection failed");
        streaming = false;
        audioMixer.stop();
        if (stateListener != null) stateListener.onConnectionFailed(reason);
    }

    @Override
    public void onNewBitrateRtmp(long bitrate) {
        if (stateListener != null) stateListener.onNewBitrateRtmp(bitrate);
    }

    @Override
    public void onDisconnectRtmp() {
        Log.i(TAG, "RTMP disconnected");
        streaming = false;
        if (stateListener != null) stateListener.onStreamStopped();
    }

    @Override
    public void onAuthErrorRtmp() { Log.e(TAG, "RTMP auth error"); }

    @Override
    public void onAuthSuccessRtmp() { Log.i(TAG, "RTMP auth success"); }

    // ── Notification helpers ──────────────────────────────────────────────────

    private Notification buildNotification(String text) {
        Intent stopIntent = new Intent(this, StreamService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 0, stopIntent,
                PendingIntent.FLAG_IMMUTABLE);

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
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Stream Service", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Controls the live stream");
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(channel);
    }

    @Override
    public void onDestroy() {
        stopStreaming();
        super.onDestroy();
    }
}
