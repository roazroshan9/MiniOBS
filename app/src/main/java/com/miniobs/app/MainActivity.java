package com.miniobs.app;

import android.Manifest;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;
import android.view.View;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.miniobs.app.databinding.ActivityMainBinding;

public class MainActivity extends AppCompatActivity
        implements StreamService.StreamStateListener,
                   RtmpReceiverService.ConnectionStateCallback {

    private static final int PERMISSIONS_REQUEST = 100;
    private static final String[] REQUIRED_PERMISSIONS = {
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.INTERNET
    };

    private ActivityMainBinding binding;
    private StreamService streamService;
    private boolean streamBound = false;
    private RtmpReceiverService receiverService;
    private boolean receiverBound = false;
    private SceneManager sceneManager;
    private CameraOverlayView cameraOverlay;

    private final ServiceConnection streamConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder b) {
            streamService = ((StreamService.LocalBinder) b).getService();
            streamService.setStateListener(MainActivity.this);
            streamBound = true;
        }
        @Override public void onServiceDisconnected(ComponentName n) { streamBound = false; }
    };

    private final ServiceConnection receiverConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder b) {
            receiverService = ((RtmpReceiverService.LocalBinder) b).getService();
            receiverService.setConnectionCallback(MainActivity.this);
            receiverService.setDataCallback(new RtmpReceiverService.ReceivedDataCallback() {
                @Override public void onVideoData(byte[] data, int length, long ts) {
                    if (streamBound && streamService != null && streamService.isStreaming())
                        streamService.sendVideoData(data, length, ts);
                }
                @Override public void onAudioData(byte[] data, int length, long ts) {
                    if (streamBound && streamService != null && streamService.isStreaming())
                        streamService.sendAudioData(data, length, ts);
                }
            });
            receiverBound = true;
        }
        @Override public void onServiceDisconnected(ComponentName n) { receiverBound = false; }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        setupSceneManager();
        setupCameraOverlay();
        setupAudioSliders();
        setupButtons();

        if (!hasPermissions()) {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSIONS_REQUEST);
        } else {
            bindServices();
            startCamera();
        }
    }

    private void setupSceneManager() {
        sceneManager = new SceneManager(this);
        sceneManager.attachViews(
                binding.viewFlipper,
                binding.sceneStartingSoon,
                binding.sceneGameplay,
                binding.sceneBrb
        );
        binding.btnSceneStarting.setOnClickListener(v ->
                sceneManager.switchScene(SceneManager.SceneType.STARTING_SOON));
        binding.btnSceneGameplay.setOnClickListener(v ->
                sceneManager.switchScene(SceneManager.SceneType.GAMEPLAY));
        binding.btnSceneBrb.setOnClickListener(v ->
                sceneManager.switchScene(SceneManager.SceneType.BRB));
        sceneManager.addListener(scene -> runOnUiThread(() ->
                binding.tvCurrentScene.setText("Scene: " + sceneManager.getSceneLabel(scene))));
    }

    private void setupCameraOverlay() {
        cameraOverlay = new CameraOverlayView(this);
        android.widget.FrameLayout.LayoutParams lp =
                new android.widget.FrameLayout.LayoutParams(240, 180);
        lp.leftMargin = 20;
        lp.topMargin = 20;
        binding.overlayContainer.addView(cameraOverlay, lp);
    }

    private void setupAudioSliders() {
        binding.sliderMic.setValue(100);
        binding.sliderGame.setValue(100);

        binding.sliderMic.addOnChangeListener((slider, value, fromUser) -> {
            binding.tvMicLabel.setText("Mic: " + (int) value + "%");
            if (streamBound && streamService != null)
                streamService.setVolumes(value / 100f, binding.sliderGame.getValue() / 100f);
        });

        binding.sliderGame.addOnChangeListener((slider, value, fromUser) -> {
            binding.tvGameLabel.setText("Game: " + (int) value + "%");
            if (streamBound && streamService != null)
                streamService.setVolumes(binding.sliderMic.getValue() / 100f, value / 100f);
        });
    }

    private void setupButtons() {
        binding.btnStartStream.setOnClickListener(v -> {
            if (streamBound && streamService != null && streamService.isStreaming()) {
                stopStream();
            } else {
                showStreamSetup();
            }
        });
        binding.btnSettings.setOnClickListener(v ->
                startActivity(new Intent(this, StreamSetupActivity.class)));
        binding.btnToggleCamera.setOnClickListener(v -> toggleCamera());
        binding.btnFlipCamera.setOnClickListener(v -> flipCamera());
    }

    private void showStreamSetup() {
        if (StreamConfig.getInstance().getFullRtmpUrl().isEmpty()) {
            startActivity(new Intent(this, StreamSetupActivity.class));
            Toast.makeText(this, "Configure RTMP URL first", Toast.LENGTH_SHORT).show();
            return;
        }
        startStream();
    }

    private void startStream() {
        Intent intent = new Intent(this, StreamService.class);
        intent.setAction(StreamService.ACTION_START);
        intent.putExtra(StreamService.EXTRA_RTMP_URL,
                StreamConfig.getInstance().getFullRtmpUrl());
        ContextCompat.startForegroundService(this, intent);
        binding.btnStartStream.setText("⏹ Stop Stream");
        binding.btnStartStream.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(getColor(R.color.color_stop)));
    }

    private void stopStream() {
        if (streamBound && streamService != null) streamService.stopStreaming();
        binding.btnStartStream.setText("▶ Go Live");
        binding.btnStartStream.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(getColor(R.color.color_start)));
    }

    private void startCamera() {
        cameraOverlay.startCamera(this, StreamConfig.getInstance().getCameraFacing());
    }

    private void toggleCamera() {
        if (cameraOverlay.isRunning()) {
            cameraOverlay.stopCamera();
            cameraOverlay.setVisibility(View.INVISIBLE);
            binding.btnToggleCamera.setText("Show Cam");
        } else {
            cameraOverlay.setVisibility(View.VISIBLE);
            startCamera();
            binding.btnToggleCamera.setText("Hide Cam");
        }
    }

    private void flipCamera() {
        cameraOverlay.stopCamera();
        int next = (StreamConfig.getInstance().getCameraFacing()
                == android.hardware.Camera.CameraInfo.CAMERA_FACING_FRONT)
                ? android.hardware.Camera.CameraInfo.CAMERA_FACING_BACK
                : android.hardware.Camera.CameraInfo.CAMERA_FACING_FRONT;
        StreamConfig.getInstance().setCameraFacing(next);
        cameraOverlay.startCamera(this, next);
    }

    private void bindServices() {
        bindService(new Intent(this, StreamService.class),
                streamConnection, BIND_AUTO_CREATE);
        ContextCompat.startForegroundService(this, new Intent(this, RtmpReceiverService.class));
        bindService(new Intent(this, RtmpReceiverService.class),
                receiverConnection, BIND_AUTO_CREATE);
    }

    private boolean hasPermissions() {
        for (String p : REQUIRED_PERMISSIONS)
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED)
                return false;
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int code, @NonNull String[] perms,
                                           @NonNull int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == PERMISSIONS_REQUEST) {
            boolean all = true;
            for (int r : results) if (r != PackageManager.PERMISSION_GRANTED) { all = false; break; }
            if (all) { bindServices(); startCamera(); }
            else Toast.makeText(this, "Permissions required", Toast.LENGTH_LONG).show();
        }
    }

    // ── StreamService.StateListener ──────────────────────────────────────────

    @Override public void onStreamStarted() {
        runOnUiThread(() -> {
            binding.tvStreamStatus.setText("🔴 LIVE");
            binding.tvStreamStatus.setTextColor(getColor(R.color.color_stop));
        });
    }

    @Override public void onStreamStopped() {
        runOnUiThread(() -> {
            binding.tvStreamStatus.setText("● Offline");
            binding.tvStreamStatus.setTextColor(getColor(android.R.color.darker_gray));
            binding.btnStartStream.setText("▶ Go Live");
            binding.btnStartStream.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(getColor(R.color.color_start)));
        });
    }

    @Override public void onConnectionFailed(String message) {
        runOnUiThread(() -> {
            binding.tvStreamStatus.setText("⚠ Error");
            new AlertDialog.Builder(this)
                    .setTitle("Stream Error")
                    .setMessage(message)
                    .setPositiveButton("OK", null)
                    .show();
        });
    }

    @Override public void onBitrateChanged(long bitrate) {
        runOnUiThread(() ->
                binding.tvBitrate.setText((bitrate / 1024) + " Kbps"));
    }

    // ── RtmpReceiverService.ConnectionCallback ───────────────────────────────

    @Override public void onClientConnected(String address) {
        runOnUiThread(() ->
                binding.tvReceiverStatus.setText("📡 Receiving from " + address));
    }

    @Override public void onClientDisconnected() {
        runOnUiThread(() ->
                binding.tvReceiverStatus.setText("📡 Waiting for game stream…"));
    }

    @Override public void onError(String message) {
        runOnUiThread(() ->
                binding.tvReceiverStatus.setText("⚠ Receiver: " + message));
    }

    @Override
    protected void onDestroy() {
        if (streamBound) { unbindService(streamConnection); streamBound = false; }
        if (receiverBound) { unbindService(receiverConnection); receiverBound = false; }
        if (cameraOverlay != null) cameraOverlay.stopCamera();
        super.onDestroy();
    }
}
