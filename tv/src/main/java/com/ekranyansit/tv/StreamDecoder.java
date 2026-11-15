package com.ekranyansit.tv;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.util.Log;
import android.view.Surface;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Donanım H.264 çözücüsü; kareler hazır olur olmaz (zaman damgası beklemeden) ekrana basılır.
 *
 * Asenkron MediaCodec: giriş tamponları kuyruğa düşer, ağ iş parçacığı onları doldurur;
 * çıkış tamponları geldiği anda render edilir. Ara tampon/jitter buffer yoktur.
 */
final class StreamDecoder {

    interface Callbacks {
        void onVideoSize(int width, int height);

        void requestKeyframe();
    }

    private static final String TAG = "EkranYansitTV";
    private static final String MIME = MediaFormat.MIMETYPE_VIDEO_AVC;

    private final Object lock = new Object();
    private final Callbacks callbacks;
    private final HandlerThread callbackThread;
    private final Handler callbackHandler;
    /** (nesil << 32) | tampon indeksi */
    private final LinkedBlockingQueue<Long> freeInputs = new LinkedBlockingQueue<>();

    private MediaCodec codec;
    private volatile int generation;
    private volatile boolean broken;
    private boolean waitingForKeyframe = true;
    private Surface surface;
    private int width, height;
    private byte[] csd;

    private final AtomicInteger framesRendered = new AtomicInteger();
    private final AtomicLong bytesReceived = new AtomicLong();

    StreamDecoder(Callbacks callbacks) {
        this.callbacks = callbacks;
        callbackThread = new HandlerThread("cozucu", Process.THREAD_PRIORITY_URGENT_DISPLAY);
        callbackThread.start();
        callbackHandler = new Handler(callbackThread.getLooper());
    }

    /** Yüzey oluştu/yok oldu (UI iş parçacığından). */
    void setSurface(Surface s) {
        boolean started;
        synchronized (lock) {
            surface = s;
            releaseCodecLocked();
            started = s != null && csd != null && startCodecLocked();
        }
        if (started) callbacks.requestKeyframe();
    }

    /** Yeni akış ayarı (SPS/PPS + boyut) geldi. */
    void onConfig(int w, int h, byte[] newCsd) {
        synchronized (lock) {
            if (codec != null && !broken && w == width && h == height && Arrays.equals(newCsd, csd)) {
                return; // aynı ayar tekrar gönderildi
            }
            width = w;
            height = h;
            csd = newCsd;
            releaseCodecLocked();
            if (surface != null) startCodecLocked();
        }
        callbacks.onVideoSize(w, h);
    }

    /** Ağ iş parçacığından: bir sıkıştırılmış kare. */
    void onFrame(byte[] data, int len, long ptsUs, boolean keyframe) {
        bytesReceived.addAndGet(len + 13);
        if (broken) recover();

        int gen;
        synchronized (lock) {
            if (codec == null) return;
            if (waitingForKeyframe) {
                if (!keyframe) return; // referanssız kareyi çözmek bozuk görüntü verir
                waitingForKeyframe = false;
            }
            gen = generation;
        }

        long slot = -1;
        for (int tries = 0; tries < 4 && slot < 0; tries++) {
            Long v;
            try {
                v = freeInputs.poll(250, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (generation != gen) return; // çözücü değişti
            if (v == null) continue;
            if ((int) (v >>> 32) != gen) {
                tries--; // eski nesilden kalan; sayma
                continue;
            }
            slot = v;
        }
        if (slot < 0) {
            // Çözücü 1 sn tampon vermedi: kareyi atıp temiz bir anahtar kareyle devam et.
            synchronized (lock) {
                waitingForKeyframe = true;
            }
            callbacks.requestKeyframe();
            return;
        }

        int index = (int) (slot & 0xFFFFFFFFL);
        boolean needKey = false;
        synchronized (lock) {
            if (generation != gen || codec == null) return;
            try {
                ByteBuffer in = codec.getInputBuffer(index);
                if (in == null || in.capacity() < len) {
                    codec.queueInputBuffer(index, 0, 0, ptsUs, 0);
                    waitingForKeyframe = true;
                    needKey = true;
                } else {
                    in.clear();
                    in.put(data, 0, len);
                    codec.queueInputBuffer(index, 0, len, ptsUs,
                            keyframe ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                }
            } catch (IllegalStateException e) {
                Log.w(TAG, "queueInputBuffer", e);
                broken = true;
            }
        }
        if (needKey) callbacks.requestKeyframe();
    }

    /** Bağlantı bitti. */
    void reset() {
        synchronized (lock) {
            releaseCodecLocked();
            csd = null;
            width = 0;
            height = 0;
        }
    }

    void release() {
        reset();
        callbackThread.quitSafely();
    }

    int takeRenderedFrames() {
        return framesRendered.getAndSet(0);
    }

    long takeReceivedBytes() {
        return bytesReceived.getAndSet(0);
    }

    // ------------------------------------------------------------------ iç işler

    private void recover() {
        boolean started;
        synchronized (lock) {
            if (!broken) return;
            broken = false; // başlatma başarısız olsa bile her karede yeniden denemesin
            releaseCodecLocked();
            started = surface != null && csd != null && startCodecLocked();
        }
        if (started) callbacks.requestKeyframe();
    }

    private boolean startCodecLocked() {
        MediaFormat base = buildFormat(false);
        MediaCodec c = null;
        try {
            final int gen = ++generation;
            c = MediaCodec.createDecoderByType(MIME);
            c.setCallback(new CodecCallback(gen), callbackHandler);
            try {
                c.configure(buildFormat(true), surface, null, 0);
            } catch (RuntimeException e) {
                // Üreticiye özel anahtarları kabul etmeyen çözücü: sade formatla tekrar dene.
                Log.w(TAG, "configure (ek ayarlarla) olmadı, sade deneniyor", e);
                c.release();
                c = MediaCodec.createDecoderByType(MIME);
                c.setCallback(new CodecCallback(gen), callbackHandler);
                c.configure(base, surface, null, 0);
            }
            freeInputs.clear();
            c.start();
            codec = c;
            broken = false;
            waitingForKeyframe = true;
            Log.i(TAG, "çözücü " + c.getName() + " " + width + "x" + height);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "çözücü başlatılamadı", e);
            if (c != null) c.release();
            codec = null;
            return false;
        }
    }

    private MediaFormat buildFormat(boolean withVendorKeys) {
        MediaFormat f = MediaFormat.createVideoFormat(MIME, width, height);
        byte[][] split = splitSpsPps(csd);
        if (split != null) {
            f.setByteBuffer("csd-0", ByteBuffer.wrap(split[0]));
            f.setByteBuffer("csd-1", ByteBuffer.wrap(split[1]));
        } else {
            f.setByteBuffer("csd-0", ByteBuffer.wrap(csd));
        }
        f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, Math.max(width * height, 512 * 1024));
        f.setInteger(MediaFormat.KEY_PRIORITY, 0); // gerçek zamanlı
        if (Build.VERSION.SDK_INT >= 30) {
            f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1); // kareyi biriktirmeden çıkar
        }
        if (withVendorKeys) {
            // Android 11 öncesi Qualcomm/MediaTek çipler için düşük gecikme anahtarları.
            f.setInteger("vendor.qti-ext-dec-low-latency.enable", 1);
            f.setInteger("vendor.low-latency.enable", 1);
        }
        return f;
    }

    private void releaseCodecLocked() {
        generation++; // geç gelen geri çağrıları geçersiz kıl
        freeInputs.clear();
        if (codec != null) {
            try {
                codec.stop();
            } catch (RuntimeException ignored) {
            }
            try {
                codec.release();
            } catch (RuntimeException ignored) {
            }
            codec = null;
        }
    }

    /** Annex-B SPS+PPS'i csd-0 (SPS) ve csd-1 (PPS) olarak ayırır. */
    static byte[][] splitSpsPps(byte[] data) {
        if (data == null) return null;
        ByteArrayOutputStream sps = new ByteArrayOutputStream();
        ByteArrayOutputStream pps = new ByteArrayOutputStream();
        int i = 0;
        int n = data.length;
        int start = -1;
        while (i + 3 <= n) {
            boolean sc3 = data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1;
            if (sc3) {
                if (start >= 0) appendNal(data, start, (i > 0 && data[i - 1] == 0) ? i - 1 : i, sps, pps);
                start = i + 3;
                i += 3;
            } else {
                i++;
            }
        }
        if (start >= 0) appendNal(data, start, n, sps, pps);
        if (sps.size() == 0 || pps.size() == 0) return null;
        return new byte[][]{sps.toByteArray(), pps.toByteArray()};
    }

    private static void appendNal(byte[] d, int from, int to, ByteArrayOutputStream sps, ByteArrayOutputStream pps) {
        if (to <= from) return;
        int type = d[from] & 0x1F;
        ByteArrayOutputStream target = type == 7 ? sps : type == 8 ? pps : null;
        if (target == null) return;
        target.write(0);
        target.write(0);
        target.write(0);
        target.write(1);
        target.write(d, from, to - from);
    }

    private final class CodecCallback extends MediaCodec.Callback {
        private final int gen;

        CodecCallback(int gen) {
            this.gen = gen;
        }

        @Override
        public void onInputBufferAvailable(MediaCodec c, int index) {
            if (gen == generation) freeInputs.offer(((long) gen << 32) | (index & 0xFFFFFFFFL));
        }

        @Override
        public void onOutputBufferAvailable(MediaCodec c, int index, MediaCodec.BufferInfo info) {
            if (gen != generation) return;
            try {
                c.releaseOutputBuffer(index, info.size > 0); // hemen göster
                if (info.size > 0) framesRendered.incrementAndGet();
            } catch (IllegalStateException ignored) {
            }
        }

        @Override
        public void onError(MediaCodec c, MediaCodec.CodecException e) {
            Log.w(TAG, "çözücü hatası", e);
            if (gen == generation) broken = true;
        }

        @Override
        public void onOutputFormatChanged(MediaCodec c, MediaFormat format) {
        }
    }
}
