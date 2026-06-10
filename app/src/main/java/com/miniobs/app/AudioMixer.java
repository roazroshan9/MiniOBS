package com.miniobs.app;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.util.Log;
import java.util.concurrent.atomic.AtomicBoolean;

public class AudioMixer {

    private static final String TAG = "AudioMixer";
    private static final int SAMPLE_RATE = StreamConfig.AUDIO_SAMPLE_RATE;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_STEREO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    private float micVolume = 1.0f;
    private float gameVolume = 1.0f;

    private AudioRecord micRecorder;
    private Thread mixThread;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private MixedAudioCallback callback;

    public interface MixedAudioCallback {
        void onMixedAudio(byte[] pcmData, int length);
    }

    public void setCallback(MixedAudioCallback cb) {
        this.callback = cb;
    }

    public void setMicVolume(float v) {
        micVolume = Math.max(0f, Math.min(2f, v));
        StreamConfig.getInstance().setMicVolume(micVolume);
    }

    public void setGameVolume(float v) {
        gameVolume = Math.max(0f, Math.min(2f, v));
        StreamConfig.getInstance().setGameVolume(gameVolume);
    }

    public float getMicVolume() { return micVolume; }
    public float getGameVolume() { return gameVolume; }

    public void start() {
        if (running.get()) return;

        int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        if (bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Invalid audio record parameters");
            return;
        }
        bufferSize = Math.max(bufferSize, 4096);

        try {
            micRecorder = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
            );
            if (micRecorder.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize");
                return;
            }
            micRecorder.startRecording();
        } catch (SecurityException e) {
            Log.e(TAG, "Mic permission denied", e);
            return;
        }

        running.set(true);
        final int finalBufferSize = bufferSize;

        mixThread = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
            byte[] micBuffer = new byte[finalBufferSize];

            while (running.get()) {
                int read = micRecorder.read(micBuffer, 0, finalBufferSize);
                if (read > 0) {
                    byte[] mixed = applyVolume(micBuffer, read, micVolume);
                    if (callback != null) callback.onMixedAudio(mixed, mixed.length);
                }
            }
        }, "AudioMixerThread");
        mixThread.setDaemon(true);
        mixThread.start();
    }

    /**
     * Mix an incoming PCM game audio buffer with microphone.
     * In a full implementation this would blend both sources.
     * Here we expose it so RtmpReceiverService can feed game audio.
     */
    public byte[] mixWithGame(byte[] gameBuffer, int gameLen) {
        if (gameBuffer == null || gameLen <= 0) return new byte[0];
        return applyVolume(gameBuffer, gameLen, gameVolume);
    }

    private byte[] applyVolume(byte[] input, int len, float volume) {
        byte[] out = new byte[len];
        for (int i = 0; i + 1 < len; i += 2) {
            short sample = (short) ((input[i] & 0xFF) | (input[i + 1] << 8));
            float scaled = sample * volume;
            if (scaled > Short.MAX_VALUE) scaled = Short.MAX_VALUE;
            if (scaled < Short.MIN_VALUE) scaled = Short.MIN_VALUE;
            short result = (short) scaled;
            out[i] = (byte) (result & 0xFF);
            out[i + 1] = (byte) ((result >> 8) & 0xFF);
        }
        return out;
    }

    public void stop() {
        running.set(false);
        if (micRecorder != null) {
            try {
                micRecorder.stop();
                micRecorder.release();
            } catch (Exception e) {
                Log.w(TAG, "Error stopping AudioRecord", e);
            }
            micRecorder = null;
        }
        if (mixThread != null) {
            try { mixThread.join(1000); } catch (InterruptedException ignored) {}
            mixThread = null;
        }
    }

    public boolean isRunning() { return running.get(); }
}
