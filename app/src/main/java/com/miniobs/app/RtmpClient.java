package com.miniobs.app;

import android.util.Log;
import java.io.*;
import java.net.Socket;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Minimal pure-Java RTMP client.
 * Handles handshake, connect, createStream, publish, and video/audio chunk sending.
 * No external dependencies — compiles with standard Android SDK only.
 */
public class RtmpClient {

    private static final String TAG = "RtmpClient";
    private static final int RTMP_PORT = 1935;
    private static final int CHUNK_SIZE = 4096;

    // RTMP message type IDs
    private static final int MSG_SET_CHUNK_SIZE   = 1;
    private static final int MSG_WINDOW_ACK_SIZE  = 5;
    private static final int MSG_SET_PEER_BW      = 6;
    private static final int MSG_AUDIO            = 8;
    private static final int MSG_VIDEO            = 9;
    private static final int MSG_AMF0_CMD         = 20;

    private Socket socket;
    private DataOutputStream out;
    private DataInputStream  in;
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private int messageStreamId = 1;
    private int transactionId   = 1;

    public interface Callback {
        void onConnected();
        void onDisconnected();
        void onError(String reason);
    }

    private Callback callback;

    public void setCallback(Callback cb) { this.callback = cb; }

    // ── Public API ────────────────────────────────────────────────────────────

    public void connect(String rtmpUrl) {
        new Thread(() -> {
            try {
                String[] parts = parseUrl(rtmpUrl);
                String host   = parts[0];
                int    port   = Integer.parseInt(parts[1]);
                String app    = parts[2];
                String stream = parts[3];

                socket = new Socket(host, port);
                socket.setTcpNoDelay(true);
                out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 65536));
                in  = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 65536));

                handshake();
                sendConnect(app, rtmpUrl);
                readUntilResult("_result");
                sendCreateStream();
                readUntilResult("_result");
                sendPublish(stream);
                readUntilResult("onStatus");

                connected.set(true);
                Log.i(TAG, "RTMP connected to " + rtmpUrl);
                if (callback != null) callback.onConnected();

            } catch (Exception e) {
                Log.e(TAG, "Connection error: " + e.getMessage());
                if (callback != null) callback.onError(e.getMessage());
            }
        }, "RtmpConnectThread").start();
    }

    public void disconnect() {
        connected.set(false);
        try {
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (IOException ignored) {}
        if (callback != null) callback.onDisconnected();
    }

    public boolean isConnected() { return connected.get(); }

    /** Send an H.264 NAL unit as an RTMP video message. */
    public void sendVideo(byte[] data, boolean isKeyFrame, long timestampMs) {
        if (!connected.get() || data == null) return;
        try {
            // Build FLV video tag payload: 1 byte flags + data
            byte flags = isKeyFrame ? (byte) 0x17 : (byte) 0x27; // H.264
            byte[] payload = new byte[data.length + 5];
            payload[0] = flags;
            payload[1] = 0x01; // AVC NALU
            payload[2] = 0x00; payload[3] = 0x00; payload[4] = 0x00; // composition time
            System.arraycopy(data, 0, payload, 5, data.length);
            writeChunk(MSG_VIDEO, messageStreamId, (int) timestampMs, payload);
        } catch (IOException e) {
            Log.w(TAG, "sendVideo error: " + e.getMessage());
        }
    }

    /** Send raw AAC audio bytes as an RTMP audio message. */
    public void sendAudio(byte[] data, long timestampMs) {
        if (!connected.get() || data == null) return;
        try {
            // Build FLV audio tag payload: 1 byte flags + data
            byte[] payload = new byte[data.length + 2];
            payload[0] = (byte) 0xAF; // AAC, 44kHz, stereo
            payload[1] = 0x01;         // AAC raw
            System.arraycopy(data, 0, payload, 2, data.length);
            writeChunk(MSG_AUDIO, messageStreamId, (int) timestampMs, payload);
        } catch (IOException e) {
            Log.w(TAG, "sendAudio error: " + e.getMessage());
        }
    }

    // ── RTMP Handshake ────────────────────────────────────────────────────────

    private void handshake() throws IOException {
        // C0: version byte
        out.write(0x03);

        // C1: timestamp (4 bytes) + zeros (4 bytes) + random (1528 bytes)
        byte[] c1 = new byte[1536];
        long now = System.currentTimeMillis() / 1000;
        c1[0] = (byte) ((now >> 24) & 0xFF);
        c1[1] = (byte) ((now >> 16) & 0xFF);
        c1[2] = (byte) ((now >> 8)  & 0xFF);
        c1[3] = (byte) (now & 0xFF);
        new Random().nextBytes(c1); // fill the rest
        c1[0] = (byte) ((now >> 24) & 0xFF);
        c1[1] = (byte) ((now >> 16) & 0xFF);
        c1[2] = (byte) ((now >> 8)  & 0xFF);
        c1[3] = (byte) (now & 0xFF);
        c1[4] = 0; c1[5] = 0; c1[6] = 0; c1[7] = 0;
        out.write(c1);
        out.flush();

        // Read S0 + S1 + S2
        int s0 = in.read();
        byte[] s1 = new byte[1536];
        in.readFully(s1);
        byte[] s2 = new byte[1536];
        in.readFully(s2);

        // C2: echo back S1
        out.write(s1);
        out.flush();
        Log.d(TAG, "Handshake complete (S0=" + s0 + ")");
    }

    // ── AMF0 Commands ─────────────────────────────────────────────────────────

    private void sendConnect(String app, String tcUrl) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(buf);

        writeAmfString(d, "connect");
        writeAmfNumber(d, transactionId++);
        // Object
        d.write(0x03);
        writeAmfKeyValue(d, "app",     app);
        writeAmfKeyValue(d, "type",    "nonprivate");
        writeAmfKeyValue(d, "flashVer","FMLE/3.0");
        writeAmfKeyValue(d, "tcUrl",   tcUrl);
        d.write(0x00); d.write(0x00); d.write(0x09); // object end

        writeChunk(MSG_AMF0_CMD, 0, 0, buf.toByteArray());
    }

    private void sendCreateStream() throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(buf);
        writeAmfString(d, "createStream");
        writeAmfNumber(d, transactionId++);
        d.write(0x05); // null
        writeChunk(MSG_AMF0_CMD, 0, 0, buf.toByteArray());
    }

    private void sendPublish(String streamName) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(buf);
        writeAmfString(d, "publish");
        writeAmfNumber(d, transactionId++);
        d.write(0x05); // null
        writeAmfString(d, streamName);
        writeAmfString(d, "live");
        writeChunk(MSG_AMF0_CMD, messageStreamId, 0, buf.toByteArray());
    }

    // ── RTMP Chunk Writer ─────────────────────────────────────────────────────

    private void writeChunk(int msgType, int streamId, int timestamp, byte[] payload)
            throws IOException {
        int len = payload.length;
        int offset = 0;
        boolean first = true;

        while (offset < len) {
            int chunkLen = Math.min(CHUNK_SIZE, len - offset);

            if (first) {
                // Type 0 header (11 bytes)
                out.write(0x03); // chunk stream id 3, fmt=0
                // Timestamp (3 bytes)
                out.write((timestamp >> 16) & 0xFF);
                out.write((timestamp >> 8)  & 0xFF);
                out.write(timestamp & 0xFF);
                // Message length (3 bytes)
                out.write((len >> 16) & 0xFF);
                out.write((len >> 8)  & 0xFF);
                out.write(len & 0xFF);
                // Message type (1 byte)
                out.write(msgType);
                // Stream ID (4 bytes, little-endian)
                out.write(streamId & 0xFF);
                out.write((streamId >> 8)  & 0xFF);
                out.write((streamId >> 16) & 0xFF);
                out.write((streamId >> 24) & 0xFF);
                first = false;
            } else {
                // Type 3 continuation header (1 byte)
                out.write(0xC3);
            }
            out.write(payload, offset, chunkLen);
            offset += chunkLen;
        }
        out.flush();
    }

    // ── Read helper ───────────────────────────────────────────────────────────

    private void readUntilResult(String keyword) throws IOException {
        byte[] buf = new byte[4096];
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (in.available() > 0) {
                int n = in.read(buf, 0, Math.min(buf.length, in.available()));
                String s = new String(buf, 0, n);
                if (s.contains(keyword)) return;
            } else {
                try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            }
        }
        Log.w(TAG, "Timeout waiting for: " + keyword);
    }

    // ── AMF0 helpers ──────────────────────────────────────────────────────────

    private void writeAmfString(DataOutputStream d, String s) throws IOException {
        byte[] bytes = s.getBytes("UTF-8");
        d.write(0x02); // AMF0 string type
        d.writeShort(bytes.length);
        d.write(bytes);
    }

    private void writeAmfNumber(DataOutputStream d, double n) throws IOException {
        d.write(0x00); // AMF0 number type
        d.writeDouble(n);
    }

    private void writeAmfKeyValue(DataOutputStream d, String key, String value)
            throws IOException {
        byte[] kb = key.getBytes("UTF-8");
        d.writeShort(kb.length);
        d.write(kb);
        writeAmfString(d, value);
    }

    // ── URL parser ────────────────────────────────────────────────────────────

    /** Returns [host, port, app, streamKey] */
    private String[] parseUrl(String url) {
        // rtmp://host[:port]/app/streamKey
        String stripped = url.replaceFirst("rtmps?://", "");
        String[] hostAndPath = stripped.split("/", 2);
        String hostPort = hostAndPath[0];
        String path     = hostAndPath.length > 1 ? hostAndPath[1] : "";

        String host;
        String port;
        if (hostPort.contains(":")) {
            host = hostPort.split(":")[0];
            port = hostPort.split(":")[1];
        } else {
            host = hostPort;
            port = "1935";
        }

        int slash = path.lastIndexOf('/');
        String app    = slash >= 0 ? path.substring(0, slash) : path;
        String stream = slash >= 0 ? path.substring(slash + 1) : "";

        return new String[]{ host, port, app, stream };
    }
}
