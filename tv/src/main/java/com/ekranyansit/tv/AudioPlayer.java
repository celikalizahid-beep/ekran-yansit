package com.ekranyansit.tv;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Build;
import android.util.Log;

/**
 * Telefondan gelen ham PCM sesi düşük gecikmeyle çalar.
 *
 * Gecikme kontrolü:
 *  - Başta ve her kesintiden (underrun) sonra az miktar sessizlik eklenir → cızırtı olmaz.
 *  - Çalınmayı bekleyen ses MAX_QUEUE_MS'i aşarsa gelen paket atlanır → ses görüntüden geri kalmaz.
 *  - Yazma bloklanmaz; ağ iş parçacığı hiç beklemez.
 */
final class AudioPlayer {

    private static final String TAG = "EkranYansitTV";
    private static final int PREBUFFER_MS = 40;
    private static final int UNDERRUN_PAD_MS = 20;
    private static final int MAX_QUEUE_MS = 120;

    private AudioTrack track;
    private int sampleRate;
    private int channels;
    private int bytesPerFrame;
    /** Yazılan kare sayısı (32 bit'te sarar; çalma konumuyla aynı aritmetik). */
    private long framesWritten;
    private int lastUnderruns;
    private volatile boolean active;

    boolean isActive() {
        return active;
    }

    synchronized void configure(int rate, int ch) {
        if (track != null && rate == sampleRate && ch == channels) return;
        releaseLocked();
        if (rate < 8000 || rate > 192000 || (ch != 1 && ch != 2)) {
            Log.w(TAG, "desteklenmeyen ses biçimi " + rate + "/" + ch);
            return;
        }
        sampleRate = rate;
        channels = ch;
        bytesPerFrame = 2 * ch;
        int mask = ch == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
        int min = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT);
        int bufBytes = Math.max(min, rate * bytesPerFrame * 3 / 10); // 300 ms kapasite (tavan MAX_QUEUE_MS)
        try {
            AudioTrack.Builder b = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(mask)
                            .build())
                    .setBufferSizeInBytes(bufBytes)
                    .setTransferMode(AudioTrack.MODE_STREAM);
            if (Build.VERSION.SDK_INT >= 26) {
                b.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
            }
            track = b.build();
            framesWritten = 0;
            lastUnderruns = 0;
            writeSilenceLocked(PREBUFFER_MS);
            track.play();
            active = true;
        } catch (RuntimeException e) {
            Log.e(TAG, "ses çalıcı açılamadı", e);
            releaseLocked();
        }
    }

    synchronized void write(byte[] data, int len) {
        if (track == null || len <= 0) return;
        try {
            long head = track.getPlaybackHeadPosition() & 0xFFFFFFFFL;
            long queued = (framesWritten - head) & 0xFFFFFFFFL;
            if (queued > (long) sampleRate * MAX_QUEUE_MS / 1000) {
                return; // ses birikti: bu paketi atla, gecikme sınırlı kalsın
            }
            if (Build.VERSION.SDK_INT >= 24) {
                int u = track.getUnderrunCount();
                if (u != lastUnderruns) {
                    lastUnderruns = u;
                    writeSilenceLocked(UNDERRUN_PAD_MS); // ağ dalgalanmasına küçük pay
                }
            }
            int usable = len - (len % bytesPerFrame);
            int w = track.write(data, 0, usable, AudioTrack.WRITE_NON_BLOCKING);
            if (w > 0) framesWritten = (framesWritten + w / bytesPerFrame) & 0xFFFFFFFFL;
        } catch (RuntimeException e) {
            Log.w(TAG, "ses yazılamadı", e);
        }
    }

    synchronized void reset() {
        releaseLocked();
    }

    private void writeSilenceLocked(int ms) {
        int frames = sampleRate * ms / 1000;
        byte[] zero = new byte[frames * bytesPerFrame];
        int w = track.write(zero, 0, zero.length, AudioTrack.WRITE_NON_BLOCKING);
        if (w > 0) framesWritten = (framesWritten + w / bytesPerFrame) & 0xFFFFFFFFL;
    }

    private void releaseLocked() {
        active = false;
        if (track != null) {
            try {
                track.pause();
                track.flush();
            } catch (RuntimeException ignored) {
            }
            track.release();
            track = null;
        }
        sampleRate = 0;
        channels = 0;
    }
}
