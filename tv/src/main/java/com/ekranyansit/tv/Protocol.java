package com.ekranyansit.tv;

/**
 * Telefon → TV akış protokolü (TCP, Nagle kapalı).
 *
 * Her paket: int uzunluk | byte tür | long zamanDamgasıUs | uzunluk kadar veri
 * CONFIG verisi: int genişlik | int yükseklik | SPS+PPS (Annex-B)
 * Ses: AUDIO_CONFIG ardından AUDIO paketleri (sıkıştırılmamış PCM = sıfır kodlama gecikmesi)
 * TV → telefon: tek bayt komutlar (CMD_*).
 *
 * Telefon uygulamasındaki Protocol.java ile birebir aynı olmalı.
 */
final class Protocol {
    static final int PORT = 47800;
    static final String SERVICE_TYPE = "_ekranyansit._tcp";

    static final byte TYPE_CONFIG = 1;
    static final byte TYPE_KEYFRAME = 2;
    static final byte TYPE_FRAME = 3;
    /** int örnekleme hızı | int kanal sayısı (PCM 16 bit) */
    static final byte TYPE_AUDIO_CONFIG = 4;
    /** Ham PCM 16 bit little-endian, ~10 ms */
    static final byte TYPE_AUDIO = 5;
    /** Telefon teşhisi, saniyede bir: int kodlanan fps | int ağa yazarken bekleme (ms) */
    static final byte TYPE_STATS = 6;

    static final int CMD_REQUEST_KEYFRAME = 1;

    private Protocol() {
    }
}
