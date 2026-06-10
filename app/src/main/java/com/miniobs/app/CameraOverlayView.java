package com.miniobs.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Size;
import android.view.*;
import android.widget.FrameLayout;
import androidx.annotation.NonNull;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;

public class CameraOverlayView extends FrameLayout {

    private static final String TAG = "CameraOverlayView";

    private TextureView textureView;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private CaptureRequest.Builder previewRequestBuilder;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    private Size previewSize;
    private boolean isRunning = false;

    // Drag & resize state
    private float lastTouchX, lastTouchY;
    private int lastAction;
    private static final int RESIZE_HANDLE_PX = 80;

    public CameraOverlayView(Context context) {
        super(context);
        init(context);
    }

    public CameraOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void init(Context context) {
        textureView = new TextureView(context);
        LayoutParams lp = new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        addView(textureView, lp);

        textureView.setSurfaceTextureListener(surfaceTextureListener);

        setOnTouchListener((v, event) -> {
            handleTouch(event);
            return true;
        });

        setElevation(20f);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void handleTouch(MotionEvent event) {
        float x = event.getRawX();
        float y = event.getRawY();

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastTouchX = x;
                lastTouchY = y;
                lastAction = MotionEvent.ACTION_DOWN;
                break;

            case MotionEvent.ACTION_MOVE:
                float dx = x - lastTouchX;
                float dy = y - lastTouchY;

                ViewGroup.MarginLayoutParams params =
                        (ViewGroup.MarginLayoutParams) getLayoutParams();
                if (params == null) break;

                boolean inResizeZone = (x - getLeft()) > (getWidth() - RESIZE_HANDLE_PX)
                        && (y - getTop()) > (getHeight() - RESIZE_HANDLE_PX);

                if (inResizeZone) {
                    int newW = Math.max(120, getWidth() + (int) dx);
                    int newH = Math.max(90, getHeight() + (int) dy);
                    params.width = newW;
                    params.height = newH;
                } else {
                    params.leftMargin += (int) dx;
                    params.topMargin += (int) dy;
                }

                setLayoutParams(params);
                requestLayout();

                lastTouchX = x;
                lastTouchY = y;
                lastAction = MotionEvent.ACTION_MOVE;
                break;

            case MotionEvent.ACTION_UP:
                lastAction = MotionEvent.ACTION_UP;
                break;
        }
    }

    public void startCamera(Context context, int facing) {
        startBackgroundThread();
        if (textureView.isAvailable()) {
            openCamera(context, facing);
        } else {
            textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(@NonNull SurfaceTexture st, int w, int h) {
                    openCamera(context, facing);
                }
                @Override
                public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture st, int w, int h) {}
                @Override
                public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture st) { return true; }
                @Override
                public void onSurfaceTextureUpdated(@NonNull SurfaceTexture st) {}
            });
        }
    }

    @SuppressLint("MissingPermission")
    private void openCamera(Context context, int facing) {
        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        try {
            String cameraId = null;
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics c = manager.getCameraCharacteristics(id);
                Integer f = c.get(CameraCharacteristics.LENS_FACING);
                if (f != null && f == facing) {
                    cameraId = id;
                    StreamConfigurationMap map = c.get(
                            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                    if (map != null) {
                        Size[] sizes = map.getOutputSizes(SurfaceTexture.class);
                        previewSize = chooseBestSize(sizes, 320, 240);
                    }
                    break;
                }
            }
            if (cameraId == null) {
                Log.e(TAG, "No camera found for facing=" + facing);
                return;
            }
            manager.openCamera(cameraId, stateCallback, backgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Cannot access camera", e);
        }
    }

    private Size chooseBestSize(Size[] sizes, int preferW, int preferH) {
        if (sizes == null || sizes.length == 0) return new Size(320, 240);
        return Collections.min(Arrays.asList(sizes), (a, b) -> {
            int da = Math.abs(a.getWidth() - preferW) + Math.abs(a.getHeight() - preferH);
            int db = Math.abs(b.getWidth() - preferW) + Math.abs(b.getHeight() - preferH);
            return da - db;
        });
    }

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            cameraDevice = camera;
            createCameraPreviewSession();
        }
        @Override
        public void onDisconnected(@NonNull CameraDevice camera) { camera.close(); cameraDevice = null; }
        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            camera.close(); cameraDevice = null;
            Log.e(TAG, "Camera error: " + error);
        }
    };

    private void createCameraPreviewSession() {
        try {
            SurfaceTexture texture = textureView.getSurfaceTexture();
            if (texture == null) return;
            if (previewSize != null) {
                texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            }
            Surface surface = new Surface(texture);
            previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewRequestBuilder.addTarget(surface);
            cameraDevice.createCaptureSession(
                    Arrays.asList(surface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            captureSession = session;
                            try {
                                previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                session.setRepeatingRequest(
                                        previewRequestBuilder.build(), null, backgroundHandler);
                                isRunning = true;
                            } catch (CameraAccessException e) {
                                Log.e(TAG, "setRepeatingRequest failed", e);
                            }
                        }
                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Log.e(TAG, "Camera session config failed");
                        }
                    },
                    backgroundHandler
            );
        } catch (CameraAccessException e) {
            Log.e(TAG, "createCaptureSession failed", e);
        }
    }

    public void stopCamera() {
        isRunning = false;
        if (captureSession != null) {
            try { captureSession.close(); } catch (Exception ignored) {}
            captureSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        stopBackgroundThread();
    }

    public SurfaceTexture getCameraSurface() {
        return textureView.getSurfaceTexture();
    }

    public boolean isRunning() { return isRunning; }

    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("CameraBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try { backgroundThread.join(); } catch (InterruptedException ignored) {}
            backgroundThread = null;
            backgroundHandler = null;
        }
    }

    private final TextureView.SurfaceTextureListener surfaceTextureListener =
            new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(@NonNull SurfaceTexture st, int w, int h) {}
        @Override
        public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture st, int w, int h) {}
        @Override
        public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture st) { return true; }
        @Override
        public void onSurfaceTextureUpdated(@NonNull SurfaceTexture st) {}
    };
}
