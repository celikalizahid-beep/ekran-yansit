package com.ekranyansit.telefon;

import android.Manifest;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Point;
import android.graphics.drawable.Icon;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import android.view.Display;
import android.view.Surface;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Ekranı yakalar, donanım H.264 kodlayıcıyla sıkıştırır ve TCP üzerinden TV'ye yollar.
 *
 * Gecikmeyi düşük tutan noktalar:
 *  - VirtualDisplay doğrudan kodlayıcının giriş yüzeyine çizer (kopya yok).
 *  - B-kare yok, "latency=1", gerçek zamanlı öncelik.
 *  - Ağ yavaşlarsa yazma bloklanır, kodlayıcı eski kareleri atar → birikme olmaz.
 *  - TCP_NODELAY + küçük gönderme tamponu + Wi-Fi düşük gecikme kilidi.
 */
public class ScreenCastService extends Service {

    static final String ACTION_START = "com.ekranyansit.telefon.START";
    static final String ACTION_STOP = "com.ekranyansit.telefon.STOP";
    static final String EXTRA_RESULT_CODE = "resultCode";
    static final String EXTRA_RESULT_DATA = "resultData";
    static final String EXTRA_HOST = "host";
    static final String EXTRA_PORT = "port";
    static final String EXTRA_TV_NAME = "tvName";
    static final String EXTRA_MAX_SIDE = "maxSide";
    static final String EXTRA_BITRATE = "bitrate";
    static final String EXTRA_AUDIO = "audio";

    private static final int AUDIO_RATE = 48_000;
    private static final int AUDIO_CHANNELS = 2;
    /** 10 ms'lik parça: 480 örnek × 2 kanal × 2 bayt */
    private static final int AUDIO_CHUNK = AUDIO_RATE / 100 * AUDIO_CHANNELS * 2;

    private static final String TAG = "EkranYansit";
    private static final String MIME = MediaFormat.MIMETYPE_VIDEO_AVC;
    private static final String CHANNEL_ID = "yansitma";
    private static final int NOTIF_ID = 1;

    /** Aktiviteye durum bildirmek için (aynı süreç). */
    interface StateListener {
        void onStateChanged(boolean running, String message);
    }

    private static volatile StateListener stateListener;
    private static volatile boolean running;
    private static volatile String lastMessage = "";

    static void setStateListener(StateListener l) {
        stateListener = l;
    }

    static boolean isRunning() {
        return running;
    }

    static String lastMessage() {
        return lastMessage;
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private Thread worker;
    private volatile Socket socket;
    private volatile boolean stopRequested;
    private volatile boolean restartRequested;
    private volatile boolean keyframeRequested;
    private volatile int captureW, captureH;
    private boolean stopped;
    private byte[] lastConfig;
    private WifiManager.WifiLock wifiLock;
    private DisplayManager.DisplayListener displayListener;
    private boolean audioEnabled;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopCasting("Yansıtma durduruldu");
            return START_NOT_STICKY;
        }
        if (worker != null) {
            return START_NOT_STICKY; // zaten çalışıyor
        }

        final String host = intent.getStringExtra(EXTRA_HOST);
        final int port = intent.getIntExtra(EXTRA_PORT, Protocol.PORT);
        final String tvName = intent.getStringExtra(EXTRA_TV_NAME);
        final int maxSide = intent.getIntExtra(EXTRA_MAX_SIDE, 1280);
        final int bitrate = intent.getIntExtra(EXTRA_BITRATE, 6_000_000);
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent data = getResultData(intent);
        // Uygulama sesi yakalama Android 10+ ve mikrofon izni ister (mikrofon kullanılmaz).
        audioEnabled = intent.getBooleanExtra(EXTRA_AUDIO, false)
                && Build.VERSION.SDK_INT >= 29
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;

        // Android 14+: getMediaProjection'dan ÖNCE mediaProjection türünde ön plan servisi olmalı.
        startForegroundCompat(tvName);

        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        try {
            projection = (data == null) ? null : mpm.getMediaProjection(resultCode, data);
        } catch (RuntimeException e) {
            Log.e(TAG, "getMediaProjection", e);
            projection = null;
        }
        if (projection == null) {
            stopCasting("Ekran kaydı izni alınamadı");
            return START_NOT_STICKY;
        }
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                stopCasting("Yansıtma durduruldu");
            }
        }, mainHandler);

        acquireWifiLock();
        registerDisplayListener();

        running = true;
        notifyState(true, "Bağlanıyor: " + tvName + " …");

        worker = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);
            runStream(host, port, tvName, maxSide, bitrate);
        }, "yansitma");
        worker.start();
        return START_NOT_STICKY;
    }

    @SuppressWarnings("deprecation")
    private static Intent getResultData(Intent intent) {
        if (Build.VERSION.SDK_INT >= 33) {
            return intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
        }
        return intent.getParcelableExtra(EXTRA_RESULT_DATA);
    }

    @Override
    public void onDestroy() {
        stopCasting("Yansıtma durduruldu");
        super.onDestroy();
    }

    // ------------------------------------------------------------------ akış

    private void runStream(String host, int port, String tvName, int maxSide, int bitrate) {
        String endMessage = "Yansıtma durduruldu";
        boolean connected = false;
        try {
            Socket s = new Socket();
            s.setTcpNoDelay(true);
            try {
                s.setTrafficClass(0xB8); // DSCP EF: destekleyen modemlerde öncelikli trafik
            } catch (Exception ignored) {
            }
            s.setSendBufferSize(128 * 1024); // büyük tampon = gizli gecikme
            socket = s;
            s.connect(new InetSocketAddress(host, port), 4000);
            connected = true;
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), 64 * 1024));
            startCommandReader(s);
            if (audioEnabled) startAudio(out);
            notifyState(true, "Yansıtılıyor → " + tvName + (audioEnabled ? " (sesli)" : " (sessiz)"));

            while (!stopRequested) {
                restartRequested = false;
                encodeSession(out, maxSide, bitrate);
            }
        } catch (IOException e) {
            if (!stopRequested) {
                endMessage = connected
                        ? "Bağlantı koptu"
                        : "TV'ye bağlanılamadı (" + host + "). TV'de uygulama açık mı, aynı Wi-Fi'de misiniz?";
            }
            Log.w(TAG, "akış bitti", e);
        } catch (RuntimeException e) {
            Log.e(TAG, "akış hatası", e);
            if (!stopRequested) endMessage = "Hata: " + e.getMessage();
        } finally {
            if (virtualDisplay != null) {
                try {
                    virtualDisplay.release();
                } catch (RuntimeException ignored) {
                }
                virtualDisplay = null;
            }
        }
        final String msg = endMessage;
        mainHandler.post(() -> stopCasting(msg));
    }

    /** Bir kodlayıcı oturumu: ekran boyutu değişince (döndürme) yeniden başlar. */
    private void encodeSession(DataOutputStream out, int maxSide, int bitrate) throws IOException {
        Point real = realScreenSize();
        captureW = real.x;
        captureH = real.y;
        int dpi = getResources().getDisplayMetrics().densityDpi;

        MediaCodec codec = null;
        Surface input = null;
        try {
            int[] size = new int[2];
            codec = createEncoder(real.x, real.y, maxSide, bitrate, size);
            input = codec.createInputSurface();
            codec.start();
            int w = size[0], h = size[1];
            Log.i(TAG, "kodlama " + w + "x" + h + " @" + bitrate / 1000 + "kbps");

            MediaProjection p = projection;
            if (p == null) throw new IOException("projection durdu");
            if (virtualDisplay == null) {
                virtualDisplay = p.createVirtualDisplay("EkranYansit", w, h, dpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, input, null, null);
            } else {
                virtualDisplay.resize(w, h, dpi);
                virtualDisplay.setSurface(input);
            }
            drain(codec, out, w, h);
        } finally {
            if (virtualDisplay != null) {
                try {
                    virtualDisplay.setSurface(null);
                } catch (RuntimeException ignored) {
                }
            }
            if (codec != null) {
                try {
                    codec.stop();
                } catch (RuntimeException ignored) {
                }
                codec.release();
            }
            if (input != null) input.release();
        }
    }

    private void drain(MediaCodec codec, DataOutputStream out, int w, int h) throws IOException {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        byte[] buf = new byte[256 * 1024];

        while (!stopRequested && !restartRequested) {
            if (keyframeRequested) {
                keyframeRequested = false;
                if (lastConfig != null) writePacket(out, Protocol.TYPE_CONFIG, 0, lastConfig, lastConfig.length);
                Bundle b = new Bundle();
                b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                try {
                    codec.setParameters(b);
                } catch (IllegalStateException ignored) {
                }
            }

            int idx = codec.dequeueOutputBuffer(info, 50_000);
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // Bazı kodlayıcılar SPS/PPS'i ayrı paket yerine çıkış formatında verir.
                MediaFormat f = codec.getOutputFormat();
                ByteBuffer c0 = f.getByteBuffer("csd-0");
                ByteBuffer c1 = f.getByteBuffer("csd-1");
                if (c0 != null) sendConfig(out, w, h, concat(c0, c1));
                continue;
            }
            if (idx < 0) continue;

            int size = 0;
            int flags = info.flags;
            long pts = info.presentationTimeUs;
            ByteBuffer ob = codec.getOutputBuffer(idx);
            if (ob != null && info.size > 0) {
                size = info.size;
                if (buf.length < size) buf = new byte[size + size / 2];
                ob.position(info.offset);
                ob.limit(info.offset + size);
                ob.get(buf, 0, size);
            }
            // Ağa yazmadan önce tamponu geri ver: kodlayıcı beklemesin.
            codec.releaseOutputBuffer(idx, false);

            if (size > 0) {
                if ((flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    sendConfig(out, w, h, Arrays.copyOf(buf, size));
                } else {
                    boolean key = (flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                    writePacket(out, key ? Protocol.TYPE_KEYFRAME : Protocol.TYPE_FRAME, pts, buf, size);
                }
            }
            if ((flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
        }
    }

    private void sendConfig(DataOutputStream out, int w, int h, byte[] csd) throws IOException {
        ByteBuffer bb = ByteBuffer.allocate(8 + csd.length);
        bb.putInt(w).putInt(h).put(csd);
        lastConfig = bb.array();
        writePacket(out, Protocol.TYPE_CONFIG, 0, lastConfig, lastConfig.length);
    }

    /** Görüntü ve ses iş parçacıkları aynı soketi paylaşır; paketler bölünmesin diye kilitli. */
    private static void writePacket(DataOutputStream out, byte type, long pts, byte[] data, int len) throws IOException {
        synchronized (out) {
            out.writeInt(len);
            out.writeByte(type);
            out.writeLong(pts);
            out.write(data, 0, len);
            out.flush();
        }
    }

    // ------------------------------------------------------------------ ses

    /**
     * Diğer uygulamaların çaldığı sesi (müzik, video, oyun) yakalar ve ham PCM olarak yollar.
     * Sıkıştırma yok: 48 kHz stereo ≈ 1,5 Mbps; yerel ağ için küçük, gecikmesi sıfır.
     */
    private void startAudio(DataOutputStream out) {
        if (Build.VERSION.SDK_INT < 29) return;
        Thread t = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
            runAudio(out);
        }, "yansitma-ses");
        t.setDaemon(true);
        t.start();
    }

    @SuppressLint("MissingPermission") // izin onStartCommand'da kontrol edildi
    @TargetApi(29)
    private void runAudio(DataOutputStream out) {
        MediaProjection p = projection;
        if (p == null) return;
        AudioRecord rec = null;
        try {
            AudioPlaybackCaptureConfiguration cfg = new AudioPlaybackCaptureConfiguration.Builder(p)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build();
            AudioFormat fmt = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(AUDIO_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build();
            int min = AudioRecord.getMinBufferSize(AUDIO_RATE, AudioFormat.CHANNEL_IN_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT);
            rec = new AudioRecord.Builder()
                    .setAudioFormat(fmt)
                    .setBufferSizeInBytes(Math.max(min, AUDIO_CHUNK * 4))
                    .setAudioPlaybackCaptureConfig(cfg)
                    .build();
            rec.startRecording();

            byte[] cfgPacket = ByteBuffer.allocate(8).putInt(AUDIO_RATE).putInt(AUDIO_CHANNELS).array();
            writePacket(out, Protocol.TYPE_AUDIO_CONFIG, 0, cfgPacket, cfgPacket.length);

            byte[] buf = new byte[AUDIO_CHUNK];
            while (!stopRequested) {
                int n = rec.read(buf, 0, buf.length); // ~10 ms bloklar
                if (n < 0) {
                    Log.w(TAG, "ses okuma hatası " + n);
                    break;
                }
                if (n > 0) writePacket(out, Protocol.TYPE_AUDIO, System.nanoTime() / 1000, buf, n);
            }
        } catch (IOException e) {
            // soket kapandı: görüntü iş parçacığı zaten durumu bildiriyor
        } catch (RuntimeException e) {
            Log.e(TAG, "ses yakalanamadı", e);
            notifyState(true, lastMessage + " — ses başlatılamadı");
        } finally {
            if (rec != null) {
                try {
                    rec.stop();
                } catch (RuntimeException ignored) {
                }
                rec.release();
            }
        }
    }

    private static byte[] concat(ByteBuffer a, ByteBuffer b) {
        a = a.duplicate();
        int la = a.remaining();
        int lb = (b == null) ? 0 : b.remaining();
        byte[] r = new byte[la + lb];
        a.get(r, 0, la);
        if (b != null) b.duplicate().get(r, la, lb);
        return r;
    }

    /** TV'den gelen komutları dinler (ör. "anahtar kare gönder"). */
    private void startCommandReader(Socket s) {
        Thread t = new Thread(() -> {
            try {
                InputStream in = s.getInputStream();
                int c;
                while ((c = in.read()) != -1) {
                    if (c == Protocol.CMD_REQUEST_KEYFRAME) keyframeRequested = true;
                }
            } catch (IOException ignored) {
            }
            if (!stopRequested) {
                mainHandler.post(() -> stopCasting("TV bağlantıyı kapattı"));
            }
        }, "yansitma-komut");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------------ kodlayıcı

    private MediaCodec createEncoder(int sw, int sh, int maxSide, int bitrate, int[] outSize) throws IOException {
        int[] sides = {maxSide, 1600, 1280, 1024, 800, 640};
        RuntimeException last = null;
        for (int side : sides) {
            if (side > maxSide) continue;
            double scale = Math.min(1.0, side / (double) Math.max(sw, sh));
            int w = align16(sw * scale);
            int h = align16(sh * scale);
            MediaCodec codec = MediaCodec.createEncoderByType(MIME);
            try {
                MediaCodecInfo.VideoCapabilities caps =
                        codec.getCodecInfo().getCapabilitiesForType(MIME).getVideoCapabilities();
                if (caps != null && !caps.isSizeSupported(w, h)) {
                    codec.release();
                    continue;
                }
                codec.configure(buildFormat(w, h, bitrate), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                outSize[0] = w;
                outSize[1] = h;
                return codec;
            } catch (RuntimeException e) {
                Log.w(TAG, "kodlayıcı " + w + "x" + h + " olmadı", e);
                last = e;
                codec.release();
            }
        }
        throw new IOException("Telefonun video kodlayıcısı uygun bir boyut kabul etmedi", last);
    }

    private static MediaFormat buildFormat(int w, int h, int bitrate) {
        MediaFormat f = MediaFormat.createVideoFormat(MIME, w, h);
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        f.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        f.setInteger(MediaFormat.KEY_FRAME_RATE, 60);
        // Kayıpsız TCP kullandığımız için sık anahtar kareye gerek yok; TV isterse anında üretilir.
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5);
        // Ekran değişmese de 100 ms'de bir kare üret (TV donmuş görünmesin).
        f.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000);
        f.setInteger("priority", 0);      // gerçek zamanlı
        f.setInteger("latency", 1);       // kare tutmadan çıkar
        f.setInteger("max-bframes", 0);   // B-kare = ek gecikme
        return f;
    }

    private static int align16(double v) {
        return Math.max(16, (int) Math.round(v / 16.0) * 16);
    }

    @SuppressWarnings("deprecation")
    private Point realScreenSize() {
        DisplayManager dm = getSystemService(DisplayManager.class);
        Display d = dm.getDisplay(Display.DEFAULT_DISPLAY);
        Point p = new Point();
        d.getRealSize(p);
        return p;
    }

    private void registerDisplayListener() {
        DisplayManager dm = getSystemService(DisplayManager.class);
        displayListener = new DisplayManager.DisplayListener() {
            @Override
            public void onDisplayAdded(int displayId) {
            }

            @Override
            public void onDisplayRemoved(int displayId) {
            }

            @Override
            public void onDisplayChanged(int displayId) {
                if (displayId != Display.DEFAULT_DISPLAY) return;
                Point p = realScreenSize();
                if (p.x != captureW || p.y != captureH) {
                    restartRequested = true; // telefon döndü → yeni boyutla kodla
                }
            }
        };
        dm.registerDisplayListener(displayListener, mainHandler);
    }

    // ------------------------------------------------------------------ yaşam döngüsü

    @SuppressWarnings("deprecation")
    private void acquireWifiLock() {
        WifiManager wm = getApplicationContext().getSystemService(WifiManager.class);
        if (wm == null) return;
        int mode = Build.VERSION.SDK_INT >= 29
                ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
        wifiLock = wm.createWifiLock(mode, "ekranyansit");
        wifiLock.setReferenceCounted(false);
        wifiLock.acquire();
    }

    private void stopCasting(String message) {
        if (stopped) return;
        stopped = true;
        stopRequested = true;

        Socket s = socket;
        if (s != null) {
            try {
                s.close(); // bloklanmış yazmayı da çözer
            } catch (IOException ignored) {
            }
        }
        if (displayListener != null) {
            getSystemService(DisplayManager.class).unregisterDisplayListener(displayListener);
            displayListener = null;
        }
        if (wifiLock != null) {
            wifiLock.release();
            wifiLock = null;
        }
        MediaProjection p = projection;
        projection = null;
        if (p != null) {
            try {
                p.stop();
            } catch (RuntimeException ignored) {
            }
        }
        running = false;
        notifyState(false, message);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void notifyState(boolean isRunning, String message) {
        lastMessage = message;
        mainHandler.post(() -> {
            StateListener l = stateListener;
            if (l != null) l.onStateChanged(isRunning, message);
        });
    }

    private void startForegroundCompat(String tvName) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "Ekran yansıtma",
                NotificationManager.IMPORTANCE_LOW));

        PendingIntent stopPi = PendingIntent.getService(this, 0,
                new Intent(this, ScreenCastService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent openPi = PendingIntent.getActivity(this, 1,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_cast)
                .setContentTitle("Ekran yansıtılıyor")
                .setContentText(tvName == null ? "" : tvName)
                .setOngoing(true)
                .setContentIntent(openPi)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_cast), "Durdur", stopPi).build())
                .build();

        if (Build.VERSION.SDK_INT >= 29) {
            int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
            if (audioEnabled && Build.VERSION.SDK_INT >= 30) {
                // Arka planda ses kaydına (AudioRecord) izin için.
                type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            }
            startForeground(NOTIF_ID, n, type);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }
}
