package com.miniobs.app;

import android.os.Bundle;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.miniobs.app.databinding.ActivityStreamSetupBinding;

public class StreamSetupActivity extends AppCompatActivity {

    private ActivityStreamSetupBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityStreamSetupBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        StreamConfig cfg = StreamConfig.getInstance();
        binding.etRtmpUrl.setText(cfg.getRtmpUrl());
        binding.etStreamKey.setText(cfg.getStreamKey());

        binding.btnSaveConfig.setOnClickListener(v -> saveConfig());
        binding.btnCancel.setOnClickListener(v -> finish());

        binding.tvLocalIp.setText("Your IP: " + getLocalIpAddress());
        binding.tvReceiverPort.setText("Receiver port: " + StreamConfig.RTMP_RECEIVE_PORT);
    }

    private void saveConfig() {
        String url = binding.etRtmpUrl.getText().toString().trim();
        String key = binding.etStreamKey.getText().toString().trim();

        if (url.isEmpty()) {
            binding.etRtmpUrl.setError("RTMP URL is required");
            return;
        }
        if (!url.startsWith("rtmp://") && !url.startsWith("rtmps://")) {
            binding.etRtmpUrl.setError("URL must start with rtmp:// or rtmps://");
            return;
        }

        StreamConfig.getInstance().setRtmpUrl(url);
        StreamConfig.getInstance().setStreamKey(key);

        Toast.makeText(this, "Settings saved!", Toast.LENGTH_SHORT).show();
        finish();
    }

    private String getLocalIpAddress() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> interfaces =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                java.net.NetworkInterface ni = interfaces.nextElement();
                java.util.Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress addr = addrs.nextElement();
                    if (!addr.isLoopbackAddress() && addr instanceof java.net.Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            return "unknown";
        }
        return "unknown";
    }
}
