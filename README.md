# Ekran Yansıt

Android telefonun ekranını **ve sesini** Wi-Fi üzerinden Android TV'ye düşük gecikmeyle yansıtır.

- **telefon/** — "Ekran Yansıt": ekranı yakalar, H.264 ile sıkıştırıp TV'ye gönderir (Android 8+).
  Telefonda çalan sesi de gönderir (Android 10+).
- **tv/** — "Ekran Yansıt TV": görüntüyü alır, çözer ve tam ekran gösterir (Android TV / Google TV, Android 6+).
  Tablet ya da başka bir telefon da alıcı olarak kullanılabilir.

## Kurulum

Hazır APK'lar bu deponun **Releases** (Sürümler) sayfasındadır:

| Cihaz   | Dosya |
|---------|-------|
| Telefon | `EkranYansit-Telefon.apk` |
| TV      | `EkranYansit-TV.apk` |

**Telefona:** APK'yı telefona atın (USB, Drive, Telegram vb.) ve açın. "Bilinmeyen kaynaklardan yükleme" iznini verin.

**TV'ye:** En kolay yollardan biri:
1. TV'de *Ayarlar → Cihaz tercihleri → Hakkında* bölümüne gidin ve *Yapı*ya 7 kez basarak geliştirici modunu açın.
   Ardından *Geliştirici seçenekleri → USB hata ayıklama / Ağ hata ayıklama* seçeneğini açın.
2. Bilgisayardan şunu çalıştırın (TV'nin IP adresi TV'nin ağ ayarlarında yazar):
   ```
   adb connect 192.168.1.XX
   adb install EkranYansit-TV.apk
   ```
   Alternatif: APK'yı USB belleğe kopyalayıp TV'de bir dosya yöneticisiyle ya da "Send Files to TV" gibi bir uygulamayla açın.

## Kullanım

1. TV'de **Ekran Yansıt TV** uygulamasını açın. Ekranda TV'nin adı ve IP adresi görünür.
2. Telefonda **Ekran Yansıt** uygulamasını açın. TV listede otomatik çıkar, üzerine dokunun.
   Listede çıkmazsa TV ekranındaki IP adresini yazıp *Bağlan*a basın.
3. İlk seferde ses izni istenir (aşağıya bakın), ardından "Ekranınızı paylaşın" iznini onaylayın.
4. Durdurmak için uygulamadaki *Yansıtmayı durdur* düğmesine ya da bildirimdeki *Durdur*a basın.

TV kumandasında **OK** tuşu sağ üstteki istatistikleri açıp kapatır.

### İstatistikleri okumak (fps neden düşük?)

```
Telefon 28 · Alınan 28 · Gösterilen 28 fps
6.3 Mbps · ağ beklemesi 0 ms/sn · 1280×576 · ses açık
▶ Kaynak sınırı: telefondaki uygulama bu hızda çiziyor
```

- **Telefon:** Telefonun saniyede kodladığı kare sayısı. **Alınan:** TV'ye ulaşan kare sayısı. **Gösterilen:** Ekrana basılan kare sayısı.
- **Ağ beklemesi:** Telefonun Wi-Fi'ye yazarken saniyede kaç ms beklediği. 0'a yakın olması iyidir.
- Son satır, yavaşlığın nereden kaynaklandığını söyler:
  - **Kaynak sınırı:** Telefon ekranı zaten bu hızda değişiyor. Videolar genelde 24–30 fps'tir, birçok oyun da
    30 fps'e kilitlidir. Bu durumda TV'de 30 fps görmek normaldir ve aktarım tarafından iyileştirilemez.
    Oyunun ayarlarında "60 FPS / Yüksek kare hızı" seçeneği varsa açın.
  - **Wi-Fi yetişmiyor:** 5 GHz ağa geçin, modeme yaklaşın ya da **Hızlı (720p)** moda geçin.
  - **TV çözücüsü:** TV görüntüyü çözmekte zorlanıyor. **Hızlı (720p)** mod yardımcı olur.

## Ses aktarımı

- Telefondaki **"Sesi de aktar"** kutusu varsayılan olarak işaretlidir. Kutuyu kaldırırsanız yalnızca görüntü gider.
- Android 10 ve üstü gerekir. Daha eski telefonlarda kutu devre dışıdır.
- Android bu özellik için **"Ses kaydetme / Mikrofon"** izni ister. Uygulama mikrofonu **dinlemez**,
  yalnızca diğer uygulamaların çaldığı sesi (YouTube, Netflix, müzik, oyun vb.) yakalar.
  İzni vermezseniz yansıtma sessiz devam eder.
- Ses sıkıştırılmadan gönderilir (48 kHz stereo, yaklaşık 1,5 Mbps). Bu sayede kodlama gecikmesi sıfırdır.
- Ses telefonun hoparlöründe de çalmaya devam eder. İsterseniz telefonun sesini kısın.
- Bazı uygulamalar (örneğin bazı bankacılık uygulamaları ve korumalı içerik sunan bazı uygulamalar) seslerinin yakalanmasını
  engeller. Bu Android'in kuralıdır; o uygulamaların sesi TV'ye gitmez.
- Telefon görüşmesi sesleri ve bildirim sesleri aktarılmaz.

## Gecikmeyi en aza indirmek için

- **Hızlı (720p)** modunu kullanın. Kodlama ve çözme süresi çözünürlükle birlikte artar.
- Telefon ve TV aynı modeme bağlı olsun, mümkünse **5 GHz** Wi-Fi bandına. 2.4 GHz bant hem yavaş hem kalabalıktır.
- TV'yi **Ethernet kablosuyla** modeme bağlamak, kablosuz bağlantının yarısını ortadan kaldırır.
- TV'de "Oyun modu" ya da "Düşük gecikme modu" varsa açın. TV'nin görüntü işleme özellikleri tek başına 50–100 ms ekleyebilir.

## Neden Bluetooth değil?

Bluetooth canlı ekran görüntüsü için çok yavaştır: pratikte yaklaşık 1–2 Mbps taşır, akıcı bir 720p görüntü ise
4–6 Mbps ister. Bu yüzden uygulama yerel Wi-Fi ağını kullanır.

## Teknik özet

- Yakalama: `MediaProjection` → `VirtualDisplay` → donanım kodlayıcının giriş yüzeyi (bellek kopyası yok).
- Kodlama: H.264, 60 fps, B-kare yok, `latency=1`, gerçek zamanlı öncelik. Ekran değişmese bile 100 ms'de bir kare üretilir.
  Bit hızı 720p'de 10 Mbps, 1080p'de 20 Mbps. Kodlayıcı açıkça VBR moduna alınır, çünkü bazı çiplerde varsayılan
  CBR modu zor sahnelerde kare atlar. Ayrıca `operating-rate=120` ile kodlayıcının saat hızı yüksek tutulur;
  bunu kabul etmeyen kodlayıcıda sade ayarlara geri dönülür.
- Taşıma: TCP + `TCP_NODELAY`, küçük gönderme tamponu ve Wi-Fi düşük gecikme kilidi (`WIFI_MODE_FULL_LOW_LATENCY`).
  Ağ yavaşlarsa kareler birikmez, eskileri atılır.
- Çözme: asenkron `MediaCodec` ve `KEY_LOW_LATENCY`. Kareler zaman damgası beklenmeden anında ekrana basılır.
- Bulma: mDNS / NSD (`_ekranyansit._tcp`), port 47800.
- Telefon döndürülünce kodlayıcı yeni boyutla yeniden başlar. TV görüntüyü en-boy oranını koruyarak sığdırır.
- Ses: `AudioPlaybackCaptureConfiguration` (media, game ve unknown kullanım türleri) → `AudioRecord` → 10 ms'lik ham
  PCM paketleri, görüntüyle aynı TCP bağlantısında. TV'de `AudioTrack` düşük gecikme modunda çalar: başta 40 ms tampon
  kullanılır, kesintiden sonra 20 ms pay eklenir, biriken ses 120 ms'yi aşarsa eski paketler atılır.
  Böylece ses görüntüden geri kalmaz.

## Sürümler

- **1.2:** Hareketli sahnelerde kare düşüşüne karşı yüksek bit hızı, VBR ve kodlayıcı hızlandırma eklendi.
  TV istatistiklerine darboğaz teşhisi eklendi.
- **1.1:** Ses aktarımı eklendi.
- **1.0:** İlk sürüm (yalnızca görüntü).

## Derleme

Android Studio'da bu klasörü açın ya da komut satırından:

```
gradlew assembleRelease
```

APK'lar `telefon/build/outputs/apk/release/` ve `tv/build/outputs/apk/release/` klasörlerine çıkar.
Release sürümü kişisel kullanım için debug anahtarıyla imzalanır.
