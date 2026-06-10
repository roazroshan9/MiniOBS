package com.miniobs.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Listens on TCP port 1935 for raw RTMP data from another device on the local network.
 * Exposes received bytes via a callback so StreamService can feed them into the encoder.
 */
public class RtmpReceiverService extends Service {

    private static final String TAG = "RtmpReceiverService";
    private static final String CHANNEL_ID = "rtmp_receiver_channel";
    private static final int NOTIFICATION_ID = 2;

    private final IBinder binder = new LocalBinder();
    private ServerSocket serverSocket;
    private Socket clientSocket;
    private Thread acceptThread;
    private Thread readThread;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ReceivedDataCallback dataCallback;
    private ConnectionStateCallback connCallback;

    public interface ReceivedDataCallback {
        void onVideoData(byte[] data, int length, long timestamp);
        void onAudioData(byte[] data, int length, long timestamp);
    }

    public interface ConnectionStateCallback {
        void onClientConnected(String address);
        void onClientDisconnected();
        void onError(String message);
    }

    public class LocalBinder extends Binder {
        public RtmpReceiverService getService() { return RtmpReceiverService.this; }
    }

    @Override
    public IBinder onBind(Intent intent) { return binder; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Mini OBS – Receiver")
                .setContentText("Waiting for game stream on port " + StreamConfig.RTMP_RECEIVE_PORT)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
        startForeground(NOTIFICATION_ID, notification);
        startListening();
        return START_STICKY;
    }

    public void setDataCallback(ReceivedDataCallback cb) { dataCallback = cb; }
    public void setConnectionCallback(ConnectionStateCallback cb) { connCallback = cb; }

    public void startListening() {
        if (running.get()) return;
        running.set(true);

        acceptThread = new Thread(() -> {
            try {
                serverSocket = new ServerSocket(StreamConfig.RTMP_RECEIVE_PORT);
                Log.i(TAG, "Listening on port " + StreamConfig.RTMP_RECEIVE_PORT);

                while (running.get()) {
                    Log.i(TAG, "Waiting for connection...");
                    clientSocket = serverSocket.accept();
                    String addr = clientSocket.getInetAddress().getHostAddress();
                    Log.i(TAG, "Client connected: " + addr);
                    if (connCallback != null) connCallback.onClientConnected(addr);
                    readFromClient(clientSocket);
                }
            } catch (IOException e) {
                if (running.get()) {
                    Log.e(TAG, "Server socket error", e);
                    if (connCallback != null) connCallback.onError(e.getMessage());
                }
            }
        }, "RtmpAcceptThread");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    private void readFromClient(Socket socket) {
        readThread = new Thread(() -> {
            byte[] buffer = new byte[65536];
            try (InputStream in = socket.getInputStream()) {
                while (running.get() && !socket.isClosed()) {
                    int bytesRead = in.read(buffer);
                    if (bytesRead == -1) break;
                    if (bytesRead > 0 && dataCallback != null) {
                        byte[] chunk = new byte[bytesRead];
                        System.arraycopy(buffer, 0, chunk, 0, bytesRead);
                        long now = System.currentTimeMillis();
                        // Simple heuristic: all received bytes treated as mixed AV data.
                        // A production implementation would parse the RTMP protocol here.
                        dataCallback.onVideoData(chunk, bytesRead, now);
                    }
                }
            } catch (IOException e) {
                if (running.get()) Log.w(TAG, "Client read error: " + e.getMessage());
            }
            Log.i(TAG, "Client disconnected");
            if (connCallback != null) connCallback.onClientDisconnected();
        }, "RtmpReadThread");
        readThread.setDaemon(true);
        readThread.start();
    }

    public void stopListening() {
        running.set(false);
        try {
            if (clientSocket != null) { clientSocket.close(); }
            if (serverSocket != null) { serverSocket.close(); }
        } catch (IOException ignored) {}
        if (acceptThread != null) { acceptThread.interrupt(); }
        if (readThread != null) { readThread.interrupt(); }
    }

    public boolean isListening() { return running.get(); }

    @Override
    public void onDestroy() {
        stopListening();
        super.onDestroy();
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "RTMP Receiver", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Receives game stream over local WiFi");
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(channel);
    }
}
