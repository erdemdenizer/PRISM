# R2: Kütüphaneler, fontlar ve tasarım skill'leri

Bu not 2026-11-08'de bir araştırma ajanının raporuna dayanır. Ajan hiçbir şey kurmadı ve hiçbir şey çalıştırmadı; okuduğu kaynaklar her maddede belirtilmiştir.

PRISM'in mevcut durumu (laboratuvar kopyasından okundu):
- JavaFX 25.0.4.
- `module-info.java` yok; uygulama classpath üzerinden çalışır.
- 14 stil dosyası, yaklaşık 1.810 satır CSS. Modena'nın lookup değişkenlerini değiştirerek çalışır.
- 13 Lucide ikonu, `Icon.java` içinde 3,7 KB'lık path verisi olarak tutulur. `IconView` bu ikonları SVGPath ile çizer.
- Font olarak IBM Plex Sans gömülüdür (ADR-0008).

## JavaFX kütüphaneleri

| Kütüphane | Ne sağlar | JavaFX 25 uyumu | Lisans ve bakım | Karar |
|---|---|---|---|---|
| **AtlantaFX** | Modena'nın yerini alan temalar (Primer, Nord, Cupertino, Dracula) ve ek kontroller | **3.0.0 (2026-09-24) "Java 25+ ve JavaFX 27+" istiyor; dondurulmuş tabanı bozar.** 2.1.0 (2025-07) JavaFX 22'ye göre derlenmiş; 25'te çalışıp çalışmadığı doğrulanmadı | MIT; tek bakımcı; 15 açık issue | **Yalnızca referans.** Bağımlılık eklenmez; Primer'in token ölçeği ve kontrol durumları PRISM'in kendi CSS'ine fikir olarak alınır. Eklenirse ürünün 1,8 bin satırlık Modena tabanlı CSS'i yeniden yazılmak zorunda kalır |
| **GemsFX** | Yaklaşık 60 kontrol: tarih seçici, arama, çekmece, iskelet yükleme vb. | Parent POM `org.openjfx:*` sürümünü 27'ye sabitliyor; validatorfx, jsvg, `javafx-swing` ve Ikonli'yi de beraberinde getiriyor | Apache-2.0; aktif (4.7.0, 2026-11-05) | **Reddedildi.** PRISM'e katkısı yok denecek kadar az, bağımlılık riski yüksek |
| **Ikonli** | İkon fontları (`FontIcon`, bir `Text` alt sınıfı) | JavaFX 11+ | Apache-2.0; 12.4.0 (2025-04); tek bakımcı | **Reddedildi.** **Lucide ve Phosphor paketi yok.** Her paket bütün bir fontu (0,8–1,1 MB) getiriyor. ADR-0008 bunu daha önce zaten reddetmişti. `IconView` korunur; netlik sorunu varsa çizimde düzeltilir |

## Fontlar

**Segoe UI Variable**
- Lisansı dağıtıma izin vermiyor (Microsoft font FAQ). Paketin içine gömülemez.
- Windows 11'de sistem fontu olarak adıyla kullanılabilir.
- Windows 10, Linux ve macOS'ta yok.
- JavaFX OpenType eksenlerini (wght, opsz) desteklemiyor; ilgili kayıtlar JDK-8092264, JDK-8090423 ve JDK-8344037 hâlâ açık.
- **Karar:** Ana font olarak reddedildi. Laboratuvarda yalnızca bir ölçüm olarak denenir.

**Inter**
- Lisansı OFL.
- Varsayılan rakamları orantılı. Sabit genişlikli rakamlar için `tnum` özelliği gerekiyor ve JavaFX bunu açamıyor. Canlı değişen sayılarda rakamlar titrer.
- **Karar:** Yalnızca referans.

**IBM Plex Sans**
- Rakamları üç ağırlıkta da 600/1000 em, yani tabular. Bu, font dosyası ölçülerek doğrulandı (önceki çalışmadaki R5 notu).
- **Karar:** Korunur.

## Claude Code skill'leri ve eklentileri

| Kaynak | İçerik ve güvenlik incelemesi | Karar |
|---|---|---|
| **ui-ux-pro-max** | Upstream'de bir `javafx.csv` var (75 satır, "javafx 26" etiketli). Bu satırların 8'i AtlantaFX ve Ikonli'yi öneriyor; bu, ADR'lerle çelişiyor. **Bu ortamda kurulu kopya bayat ve bozuk:** `data` ve `scripts` Windows'ta kırılmış symlink kalıntıları. SKILL.md, Python'u winget, brew ya da apt ile kurmayı söylüyor | Yalnızca referans. CLI çalıştırılmadı, hiçbir şey kurulmadı |
| **frontend-design** (Anthropic) | Yalnızca bir SKILL.md. Hook ya da betik yok. Web odaklı; "cesur estetik" ve yoğun animasyonu öneriyor, bu PRISM'in ölçülü stiliyle çelişiyor | Yalnızca referans. Projenin `javafx-ui-craft` skill'i ve UI_GUIDE önceliklidir |
| **vercel-labs/agent-skills** | `react-best-practices` yalnızca React için. `web-design-guidelines` her incelemeden önce sabitlenmemiş bir kuralı ağdan çekiyor; bu prompt-injection yüzeyi demek. `deploy-to-vercel` ağa yükleme yapıyor | Reddedildi. Erişilebilirlik ilkeleri elle okunmuş haliyle referans olarak kullanılabilir |
| **superpowers** | Genel bir geliştirme yöntemi; UI tasarımıyla ilgili bir skill'i yok. Her oturum başında `<EXTREMELY_IMPORTANT>` metni enjekte eden bir SessionStart hook'u var. Tanıtım bileşeni ağa telemetri gönderiyor | Reddedildi. Projenin kendi iş akışıyla çatışıyor |

## Sonuç

Hiçbir bağımlılık eklenmeyecek. Premium görünüm PRISM'in kendi bileşenleriyle elde edilecek:
- tasarım tokenları ve CSS;
- Plex fontu;
- `IconView` (çizim kalitesi iyileştirilerek).

AtlantaFX/Primer ölçüleri yalnızca referans olarak kullanılacak.

Laboratuvarda ölçülecekler:
- `IconView`'un 16 px'teki netliği;
- "Segoe UI Variable" adının JavaFX 25'te çözülüp çözülmediği.

Kaynak URL'leri ajanın raporundadır:
- repo1.maven.org (atlantafx-base, gemsfx 4.7.0 POM, ikonli-javafx)
- github.com/mkpaz/atlantafx/releases/tag/v3.0.0
- learn.microsoft.com/en-us/typography/fonts/font-faq
- bugs.openjdk.org/browse/JDK-8344037
- github.com/rsms/inter/releases
- raw.githubusercontent.com/obra/superpowers/main/hooks/hooks.json
- github.com/anthropics/claude-plugins-official/tree/main/plugins/frontend-design
