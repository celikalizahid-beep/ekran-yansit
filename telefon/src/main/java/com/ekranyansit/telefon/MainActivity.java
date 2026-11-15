package com.ekranyansit.telefon;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity implements ScreenCastService.StateListener {

    private static final int REQ_CAPTURE = 10;
    private static final int REQ_NOTIF = 11;

    private TextView statusText;
    private TextView searchingText;
    private LinearLayout tvList;
    private EditText ipInput;
    private RadioGroup qualityGroup;
    private CheckBox audioBox;
    private Button stopButton;
    private SharedPreferences prefs;

    private NsdManager nsd;
    private NsdManager.DiscoveryListener discovery;
    private WifiManager.MulticastLock multicastLock;
    private final Map<String, NsdServiceInfo> found = new LinkedHashMap<>();
    private final ArrayDeque<NsdServiceInfo> resolveQueue = new ArrayDeque<>();
    private boolean resolving;

    private String pendingHost;
    private int pendingPort;
    private String pendingName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.status);
        searchingText = findViewById(R.id.searching);
        tvList = findViewById(R.id.tvList);
        ipInput = findViewById(R.id.ipInput);
        qualityGroup = findViewById(R.id.quality);
        stopButton = findViewById(R.id.stopButton);
        prefs = getSharedPreferences("ayarlar", MODE_PRIVATE);

        ipInput.setText(prefs.getString("ip", ""));
        qualityGroup.check(prefs.getBoolean("net", false) ? R.id.q1080 : R.id.q720);
        qualityGroup.setOnCheckedChangeListener((g, id) ->
                prefs.edit().putBoolean("net", id == R.id.q1080).apply());

        audioBox = findViewById(R.id.audio);
        if (Build.VERSION.SDK_INT >= 29) {
            audioBox.setChecked(prefs.getBoolean("ses", true));
            audioBox.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean("ses", on).apply());
        } else {
            audioBox.setChecked(false);
            audioBox.setEnabled(false);
            audioBox.setText("Ses aktarımı için Android 10 veya üstü gerekir");
        }

        Button connectIp = findViewById(R.id.connectIp);
        connectIp.setOnClickListener(v -> {
            String text = ipInput.getText().toString().trim();
            if (text.isEmpty()) {
                Toast.makeText(this, "TV ekranındaki IP adresini girin", Toast.LENGTH_SHORT).show();
                return;
            }
            String host = text;
            int port = Protocol.PORT;
            int colon = text.lastIndexOf(':');
            if (colon > 0) {
                host = text.substring(0, colon);
                try {
                    port = Integer.parseInt(text.substring(colon + 1));
                } catch (NumberFormatException e) {
                    port = Protocol.PORT;
                }
            }
            prefs.edit().putString("ip", text).apply();
            startCast(host, port, host);
        });

        stopButton.setOnClickListener(v ->
                startService(new Intent(this, ScreenCastService.class).setAction(ScreenCastService.ACTION_STOP)));
    }

    @Override
    protected void onStart() {
        super.onStart();
        ScreenCastService.setStateListener(this);
        boolean running = ScreenCastService.isRunning();
        onStateChanged(running, running ? ScreenCastService.lastMessage() : "Bir televizyon seçin");
        startDiscovery();
    }

    @Override
    protected void onStop() {
        ScreenCastService.setStateListener(null);
        stopDiscovery();
        super.onStop();
    }

    @Override
    public void onStateChanged(boolean running, String message) {
        statusText.setText(message);
        stopButton.setVisibility(running ? View.VISIBLE : View.GONE);
    }

    // ------------------------------------------------------------------ yansıtmayı başlat

    private void startCast(String host, int port, String name) {
        if (ScreenCastService.isRunning()) {
            Toast.makeText(this, "Önce mevcut yansıtmayı durdurun", Toast.LENGTH_SHORT).show();
            return;
        }
        pendingHost = host;
        pendingPort = port;
        pendingName = name;
        List<String> needed = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (audioBox.isChecked()
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO);
        }
        if (!needed.isEmpty()) {
            requestPermissions(needed.toArray(new String[0]), REQ_NOTIF);
            return; // sonuç ne olursa olsun devam edeceğiz (izin yoksa sessiz yansıtma)
        }
        requestCapture();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_NOTIF || pendingHost == null) return;
        if (audioBox.isChecked()
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Ses izni verilmedi, yalnızca görüntü aktarılacak", Toast.LENGTH_LONG).show();
        }
        requestCapture();
    }

    private void requestCapture() {
        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        Intent i;
        if (Build.VERSION.SDK_INT >= 34) {
            // Tek uygulama yerine tüm ekranı paylaş.
            i = mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay());
        } else {
            i = mpm.createScreenCaptureIntent();
        }
        startActivityForResult(i, REQ_CAPTURE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAPTURE) return;
        if (resultCode != RESULT_OK || data == null || pendingHost == null) {
            Toast.makeText(this, "Ekran paylaşımına izin verilmedi", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean net = qualityGroup.getCheckedRadioButtonId() == R.id.q1080;
        Intent svc = new Intent(this, ScreenCastService.class)
                .setAction(ScreenCastService.ACTION_START)
                .putExtra(ScreenCastService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(ScreenCastService.EXTRA_RESULT_DATA, data)
                .putExtra(ScreenCastService.EXTRA_HOST, pendingHost)
                .putExtra(ScreenCastService.EXTRA_PORT, pendingPort)
                .putExtra(ScreenCastService.EXTRA_TV_NAME, pendingName)
                .putExtra(ScreenCastService.EXTRA_MAX_SIDE, net ? 1920 : 1280)
                .putExtra(ScreenCastService.EXTRA_BITRATE, net ? 20_000_000 : 10_000_000)
                .putExtra(ScreenCastService.EXTRA_AUDIO, audioBox.isChecked());
        startForegroundService(svc);
        pendingHost = null;
    }

    // ------------------------------------------------------------------ TV bulma (mDNS)

    private void startDiscovery() {
        nsd = getSystemService(NsdManager.class);
        WifiManager wm = getApplicationContext().getSystemService(WifiManager.class);
        if (wm != null) {
            multicastLock = wm.createMulticastLock("ekranyansit");
            multicastLock.setReferenceCounted(false);
            multicastLock.acquire();
        }
        found.clear();
        resolveQueue.clear();
        resolving = false;
        renderList();

        discovery = new NsdManager.DiscoveryListener() {
            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                runOnUiThread(() -> searchingText.setText("Otomatik arama başlatılamadı. IP adresiyle bağlanın."));
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
            }

            @Override
            public void onDiscoveryStarted(String serviceType) {
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
            }

            @Override
            public void onServiceFound(NsdServiceInfo info) {
                runOnUiThread(() -> {
                    resolveQueue.add(info);
                    resolveNext();
                });
            }

            @Override
            public void onServiceLost(NsdServiceInfo info) {
                runOnUiThread(() -> {
                    found.remove(info.getServiceName());
                    renderList();
                });
            }
        };
        try {
            nsd.discoverServices(Protocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery);
        } catch (RuntimeException e) {
            discovery = null;
            searchingText.setText("Otomatik arama başlatılamadı. IP adresiyle bağlanın.");
        }
    }

    private void stopDiscovery() {
        if (nsd != null && discovery != null) {
            try {
                nsd.stopServiceDiscovery(discovery);
            } catch (RuntimeException ignored) {
            }
        }
        discovery = null;
        resolveQueue.clear();
        if (multicastLock != null) {
            multicastLock.release();
            multicastLock = null;
        }
    }

    /** NsdManager aynı anda tek çözümleme yapabildiği için sırayla çözümlenir. */
    @SuppressWarnings("deprecation")
    private void resolveNext() {
        if (resolving || resolveQueue.isEmpty() || nsd == null || discovery == null) return;
        resolving = true;
        NsdServiceInfo next = resolveQueue.poll();
        nsd.resolveService(next, new NsdManager.ResolveListener() {
            @Override
            public void onResolveFailed(NsdServiceInfo info, int errorCode) {
                runOnUiThread(() -> {
                    resolving = false;
                    resolveNext();
                });
            }

            @Override
            public void onServiceResolved(NsdServiceInfo info) {
                runOnUiThread(() -> {
                    resolving = false;
                    if (discovery != null) {
                        found.put(info.getServiceName(), info);
                        renderList();
                    }
                    resolveNext();
                });
            }
        });
    }

    @SuppressWarnings("deprecation")
    private static String hostOf(NsdServiceInfo info) {
        if (Build.VERSION.SDK_INT >= 34) {
            List<InetAddress> all = info.getHostAddresses();
            for (InetAddress a : all) {
                if (a instanceof Inet4Address) return a.getHostAddress();
            }
            if (!all.isEmpty()) return all.get(0).getHostAddress();
            return null;
        }
        InetAddress a = info.getHost();
        return a == null ? null : a.getHostAddress();
    }

    private void renderList() {
        tvList.removeAllViews();
        for (NsdServiceInfo info : found.values()) {
            final String host = hostOf(info);
            if (host == null) continue;
            final String name = info.getServiceName();
            final int port = info.getPort();
            Button b = new Button(this);
            b.setAllCaps(false);
            b.setText("📺  " + name + "\n" + host);
            b.setOnClickListener(v -> startCast(host, port, name));
            tvList.addView(b, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        searchingText.setVisibility(tvList.getChildCount() == 0 ? View.VISIBLE : View.GONE);
        searchingText.setText("Televizyon aranıyor… TV'de \"Ekran Yansıt TV\" uygulaması açık olmalı.");
    }
}
