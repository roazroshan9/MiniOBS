package com.miniobs.app;

public class StreamConfig {
    public static final int RTMP_RECEIVE_PORT = 1935;
    public static final int VIDEO_WIDTH = 1280;
    public static final int VIDEO_HEIGHT = 720;
    public static final int VIDEO_BITRATE = 3000 * 1024;
    public static final int VIDEO_FPS = 30;
    public static final int AUDIO_BITRATE = 128 * 1024;
    public static final int AUDIO_SAMPLE_RATE = 44100;
    public static final boolean AUDIO_STEREO = true;

    private String rtmpUrl = "";
    private String streamKey = "";
    private float micVolume = 1.0f;
    private float gameVolume = 1.0f;
    private boolean cameraEnabled = true;
    private int cameraFacing = android.hardware.Camera.CameraInfo.CAMERA_FACING_FRONT;

    private static StreamConfig instance;

    public static StreamConfig getInstance() {
        if (instance == null) instance = new StreamConfig();
        return instance;
    }

    public String getFullRtmpUrl() {
        if (rtmpUrl.isEmpty()) return "";
        return rtmpUrl.endsWith("/") ? rtmpUrl + streamKey : rtmpUrl + "/" + streamKey;
    }

    public String getRtmpUrl() { return rtmpUrl; }
    public void setRtmpUrl(String url) { this.rtmpUrl = url; }

    public String getStreamKey() { return streamKey; }
    public void setStreamKey(String key) { this.streamKey = key; }

    public float getMicVolume() { return micVolume; }
    public void setMicVolume(float v) { this.micVolume = Math.max(0f, Math.min(2f, v)); }

    public float getGameVolume() { return gameVolume; }
    public void setGameVolume(float v) { this.gameVolume = Math.max(0f, Math.min(2f, v)); }

    public boolean isCameraEnabled() { return cameraEnabled; }
    public void setCameraEnabled(boolean e) { this.cameraEnabled = e; }

    public int getCameraFacing() { return cameraFacing; }
    public void setCameraFacing(int f) { this.cameraFacing = f; }
}
