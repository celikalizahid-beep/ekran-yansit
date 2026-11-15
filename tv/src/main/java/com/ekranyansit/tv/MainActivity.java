package com.ekranyansit.tv;

import android.app.Activity;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity
        implements SurfaceHolder.Callback, StreamServer.Listener, StreamDecoder.Callbacks {

    private final Handler ui = new Handler(Looper.getMainLooper());

    private FrameLayout root;
    private SurfaceView surfaceView;
    private View idlePanel;
    private TextView idleInfo;
    private TextView idleStatus;
    private TextView statsText;

    private StreamDecoder decoder;
    private final AudioPlayer audio = new AudioPlayer();
    private StreamServer server;
    private NsdManager nsd;
    private NsdManager.RegistrationListener registration;
    private WifiManager.WifiLock wifiLock;

    private int videoW, videoH;
    private boolean statsVisible = true;
    private boolean connected;
    private String tvName;

    private final Runnable statsTick = new Runnable() {
        @Override
        public void run() {
            int frames = decoder.takeRenderedFrames();
            long bytes = decoder.takeReceivedBytes();
            if (connected && videoW > 0) {
                statsText.setText(String.format(Locale.US, "%d fps · %.1f Mbps · %d×%d · %s",
                        frames, bytes * 8 / 1_000_000.0, videoW, videoH,
                        audio.isActive() ? "ses açık" : "ses yok"));
            }
            ui.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        root = findViewById(R.id.root);
        surfaceView = findViewById(R.id.video);
        idlePanel = findViewById(R.id.idlePanel);
        idleInfo = findViewById(R.id.idleInfo);
        idleStatus = findViewById(R.id.idleStatus);
        statsText = findViewById(R.id.stats);

        tvName = deviceName();
        decoder = new StreamDecoder(this);
        surfaceView.getHolder().addCallback(this);
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) fitVideo();
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        connected = false;
        showIdle("Bağlantı bekleniyor…");
        server = new StreamServer(decoder, audio, this);
        server.start();
        registerNsd();
        acquireWifiLock();
        ui.post(statsTick);
    }

    @Override
    protected void onStop() {
        ui.removeCallbacks(statsTick);
        if (server != null) {
            server.stop();
            server = null;
        }
        unregisterNsd();
        if (wifiLock != null) {
            wifiLock.release();
            wifiLock = null;
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        decoder.release();
        audio.reset();
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_INFO) {
            statsVisible = !statsVisible;
            statsText.setVisibility(statsVisible && connected ? View.VISIBLE : View.GONE);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ------------------------------------------------------------------ yüzey

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        decoder.setSurface(holder.getSurface());
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        decoder.setSurface(null);
    }

    /** Görüntüyü en-boy oranını koruyarak ekrana sığdır. */
    private void fitVideo() {
        int pw = root.getWidth(), ph = root.getHeight();
        if (pw == 0 || ph == 0 || videoW == 0 || videoH == 0) return;
        int w = pw, h = (int) ((long) pw * videoH / videoW);
        if (h > ph) {
            h = ph;
            w = (int) ((long) ph * videoW / videoH);
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) surfaceView.getLayoutParams();
        if (lp.width != w || lp.height != h) {
            lp.width = w;
            lp.height = h;
            surfaceView.setLayoutParams(lp);
        }
    }

    // ------------------------------------------------------------------ sunucu/çözücü olayları

    @Override
    public void onServerError(String message) {
        ui.post(() -> showIdle(message));
    }

    @Override
    public void onClientConnected(String address) {
        ui.post(() -> {
            connected = true;
            idleStatus.setText("Telefon bağlandı (" + address + "), görüntü bekleniyor…");
        });
    }

    @Override
    public void onClientDisconnected() {
        ui.post(() -> {
            connected = false;
            videoW = videoH = 0;
            showIdle("Bağlantı kapandı. Yeniden bağlanmayı bekliyor…");
        });
    }

    @Override
    public void onVideoSize(int width, int height) {
        ui.post(() -> {
            videoW = width;
            videoH = height;
            fitVideo();
            idlePanel.setVisibility(View.GONE);
            statsText.setVisibility(statsVisible ? View.VISIBLE : View.GONE);
        });
    }

    @Override
    public void requestKeyframe() {
        StreamServer s = server;
        if (s != null) s.requestKeyframe();
    }

    private void showIdle(String status) {
        idlePanel.setVisibility(View.VISIBLE);
        statsText.setVisibility(View.GONE);
        List<String> ips = localIpv4();
        String ipText = ips.isEmpty() ? "ağ bağlantısı yok" : android.text.TextUtils.join("  ·  ", ips);
        idleInfo.setText("Telefonda \"Ekran Yansıt\" uygulamasını açıp bu televizyonu seçin.\n\n"
                + "TV adı:  " + tvName + "\nIP adresi:  " + ipText);
        idleStatus.setText(status);
    }

    // ------------------------------------------------------------------ ağ

    private void registerNsd() {
        nsd = getSystemService(NsdManager.class);
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName(tvName);
        info.setServiceType(Protocol.SERVICE_TYPE);
        info.setPort(Protocol.PORT);
        registration = new NsdManager.RegistrationListener() {
            @Override
            public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
            }

            @Override
            public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
            }

            @Override
            public void onServiceRegistered(NsdServiceInfo serviceInfo) {
            }

            @Override
            public void onServiceUnregistered(NsdServiceInfo serviceInfo) {
            }
        };
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration);
        } catch (RuntimeException e) {
            registration = null;
        }
    }

    private void unregisterNsd() {
        if (nsd != null && registration != null) {
            try {
                nsd.unregisterService(registration);
            } catch (RuntimeException ignored) {
            }
        }
        registration = null;
    }

    @SuppressWarnings("deprecation")
    private void acquireWifiLock() {
        WifiManager wm = getApplicationContext().getSystemService(WifiManager.class);
        if (wm == null) return;
        int mode = Build.VERSION.SDK_INT >= 29
                ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
        wifiLock = wm.createWifiLock(mode, "ekranyansit-tv");
        wifiLock.setReferenceCounted(false);
        wifiLock.acquire();
    }

    private String deviceName() {
        String name = null;
        if (Build.VERSION.SDK_INT >= 25) {
            name = Settings.Global.getString(getContentResolver(), Settings.Global.DEVICE_NAME);
        }
        if (name == null || name.trim().isEmpty()) name = Build.MODEL;
        return name;
    }

    private static List<String> localIpv4() {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) out.add(a.getHostAddress());
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }
}
