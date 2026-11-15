package com.ekranyansit.tv;

import android.os.Process;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * TCP sunucusu: telefonun bağlanmasını bekler, gelen paketleri çözücüye verir.
 * Aynı anda tek telefon; yeni bağlanan eskisini devralır.
 */
final class StreamServer {

    interface Listener {
        void onServerError(String message);

        void onClientConnected(String address);

        void onClientDisconnected();
    }

    private static final String TAG = "EkranYansitTV";
    private static final int MAX_PACKET = 16 * 1024 * 1024;

    private final StreamDecoder decoder;
    private final AudioPlayer audio;
    private final Listener listener;
    private volatile boolean running;
    private ServerSocket serverSocket;
    private Socket current;
    private volatile OutputStream currentOut;

    StreamServer(StreamDecoder decoder, AudioPlayer audio, Listener listener) {
        this.decoder = decoder;
        this.audio = audio;
        this.listener = listener;
    }

    void start() {
        running = true;
        Thread t = new Thread(this::acceptLoop, "sunucu");
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        running = false;
        ServerSocket ss;
        Socket c;
        synchronized (this) {
            ss = serverSocket;
            c = current;
        }
        closeQuietly(ss);
        closeQuietly(c);
    }

    /** Telefondan anında anahtar kare iste (çözücü yeniden kurulduğunda). */
    void requestKeyframe() {
        OutputStream out = currentOut;
        if (out == null) return;
        new Thread(() -> {
            try {
                synchronized (out) {
                    out.write(Protocol.CMD_REQUEST_KEYFRAME);
                    out.flush();
                }
            } catch (IOException ignored) {
            }
        }, "anahtar-kare-istegi").start();
    }

    private void acceptLoop() {
        try (ServerSocket ss = new ServerSocket()) {
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(Protocol.PORT));
            synchronized (this) {
                serverSocket = ss;
            }
            if (!running) return;
            while (running) {
                Socket s = ss.accept();
                Socket old;
                synchronized (this) {
                    old = current;
                    current = s;
                }
                closeQuietly(old);
                Thread t = new Thread(() -> serve(s), "istemci");
                t.setDaemon(true);
                t.start();
            }
        } catch (IOException e) {
            if (running) {
                Log.e(TAG, "sunucu", e);
                listener.onServerError("Sunucu başlatılamadı (port " + Protocol.PORT + "): " + e.getMessage());
            }
        }
    }

    private void serve(Socket s) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);
        String addr = s.getInetAddress() == null ? "?" : s.getInetAddress().getHostAddress();
        try {
            s.setTcpNoDelay(true);
            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 64 * 1024));
            decoder.reset(); // yeni akış: yeni CONFIG bekle
            audio.reset();
            currentOut = s.getOutputStream();
            listener.onClientConnected(addr);

            byte[] buf = new byte[512 * 1024];
            while (running) {
                int len = in.readInt();
                byte type = in.readByte();
                long pts = in.readLong();
                if (len < 0 || len > MAX_PACKET) throw new IOException("geçersiz paket boyu " + len);
                if (buf.length < len) buf = new byte[len + len / 2];
                in.readFully(buf, 0, len);

                if (type == Protocol.TYPE_CONFIG) {
                    if (len < 8) continue;
                    ByteBuffer bb = ByteBuffer.wrap(buf, 0, len);
                    int w = bb.getInt();
                    int h = bb.getInt();
                    decoder.onConfig(w, h, Arrays.copyOfRange(buf, 8, len));
                } else if (type == Protocol.TYPE_KEYFRAME || type == Protocol.TYPE_FRAME) {
                    decoder.onFrame(buf, len, pts, type == Protocol.TYPE_KEYFRAME);
                } else if (type == Protocol.TYPE_AUDIO) {
                    audio.write(buf, len);
                } else if (type == Protocol.TYPE_AUDIO_CONFIG) {
                    if (len < 8) continue;
                    ByteBuffer bb = ByteBuffer.wrap(buf, 0, len);
                    audio.configure(bb.getInt(), bb.getInt());
                }
            }
        } catch (IOException e) {
            Log.i(TAG, "istemci ayrıldı: " + e.getMessage());
        } finally {
            boolean wasCurrent;
            synchronized (this) {
                wasCurrent = current == s;
                if (wasCurrent) current = null;
            }
            closeQuietly(s);
            if (wasCurrent) {
                currentOut = null;
                decoder.reset();
                audio.reset();
                listener.onClientDisconnected();
            }
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) {
        }
    }
}
