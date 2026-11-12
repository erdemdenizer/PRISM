# PRISM UI Lab → PRISM-internal geçiş planı

Durum: **öneri**. Geçiş kararını proje sahibi verir. Bu belge, kararın olumlu çıkması durumunda yapılacakları sıralar. Laboratuvar dalı (`lab/premium-ui-v2`) olduğu gibi birleştirilmez.

## 1. İlke

- Laboratuvar bağımsız bir klondur: uzak depo yoktur, push kapalıdır. Kodlar PRISM-internal'a **yeniden uygulanarak** taşınır, yani dal birleştirilmez. Her adım kendi küçük commit'i ve tam kapı (`./mvnw clean verify`) ile gelir.
- Laboratuvarda yalnızca Windows 11 (25H2, build 26200) üzerinde çalıştırılan kod doğrulanmıştır. Linux ve macOS için her UI adımı Hyper-V Linux VM'inde ve macOS test makinesinde (yerel kullanıcı: kerem) yeniden doğrulanmalıdır.
- Arka uç güvenlik kuralları (K16, CAP-2, onay pencereleri, Esc ve odak davranışı, OOM Guard varsayılanları) laboratuvarda değiştirilmedi. Geçişte de değişmemelidir.

## 2. Taşınmayacaklar

| Laboratuvar değişikliği | Neden |
|---|---|
| `19b7fbe` veri klasörlerinin `-UILab` adı ve pencere başlığındaki "PRISM UI Lab" | Yalnızca laboratuvar yalıtımı içindir |
| `ba052df` app-image ve başlatıcı adı `PRISM-UI-Lab` | Yalnızca laboratuvar içindir |
| Durum çubuğundaki laboratuvar işareti | Yalnızca laboratuvar içindir |
| `lab-spikes/`, `lab-evidence/`, `lab-docs/` | Kanıt ve araçlardır, ürün kodu değildir |
| `5409131` ilk açılışta koyu tema (`AppSettings.FIRST_RUN`) | ADR-0014'teki varsayılanı değiştirir; istenirse önce ADR-0014 düzeltilir (bkz. §4, ADR-F) |

## 3. Geçiş sırası (önerilen)

1. **Tasarım belirteçleri v2** (`cd2b1f5`, `caec44b` ve inceleme düzeltmeleri): `app.css` belirteçleri, kontrol durumları, veri renkleri, kesikli ikinci seri. Bu adımla `UI_GUIDE.md` ve `CONTRAST.md` güncellenir. ADR-C.
2. **Simgeler** (`8f0c858`): çizgisi ekran pikselinde sabit tutulan simge kümesi. Lisans notu `Icon.java` içindedir.
3. **Kabuk v2** (`2a1288e`, ad ipucu düzeltmesi `f4fc73b`): 60 px simge rayı, dar pencerede çekmece, sabit araç çubuğu, kısayollar. ADR-B. Klavye rotası testleri birlikte taşınır.
4. **Sayfalar**: Overview düzeni, Performance sekmeleri, Logs filtresi, Settings satırları, OOM durum bandı. Sayfa başına bir commit atılır.
5. **İnceleyici ve sayfa** (`7428746`, `2317345`, `SheetOverlay`, kırılım sınıfı ve alt inceleyicinin %40 sınırı `6075cd1`): Processes ve Limits için aynı kural uygulanır. ADR-D. Taşımadan önce kırılım geçişinin maliyeti düşürülmelidir (§6).
6. **Başlık çubuğu** (`9703adc`, `4ec929c`): `WindowChrome`, `WindowsWindowChrome`, `TitleBarTint`. ADR-A zorunludur; native çağrılar JavaFX iş parçacığında çalışır.
7. **Yüksek karşıtlık ve erişilebilirlik düzeltmeleri.** ADR-G gerekiyorsa o da bu adımda.
8. Her adımdan sonra `verify-image.ps1` paketlenmiş imajda çalıştırılır. Kontrol denemeleri yalnızca `prism-demo` ile yapılır.

## 4. Gerekli ADR önerileri

- **ADR-A: Windows 11 başlık çubuğu renkleri.**
  - Pencere `DECORATED` kalır; DWM öznitelikleri 20/35/36/34 uygulanır.
  - Build ≥ 22000 kapısı `VerifyVersionInfoW` ile denetlenir.
  - Pencere `EnumThreadWindows` ile, sınıf öneki ve tam başlık eşleşmesiyle bulunur.
  - **Mimari istisna:** bu sınıf, native çağrılarını JavaFX iş parçacığında yapar. Pencere o iş parçacığına aittir ve başka bir iş parçacığından yapılan `GetWindowText` çağrısı bu iş parçacığını bekler. Pencere başına en çok 20 deneme yapılır.
  - Bu ADR, ARCHITECTURE.md'deki iş parçacığı tablosuna `srm-title-bar-log` girdisini (ya da sahibi belli bir executor'ı) ekler.
  - Yüksek karşıtlıkta sistem renkleri geri verilir.
  - Kanıt: `TITLEBAR_VALIDATION_TR.md`.
- **ADR-B: simge rayı ve dar pencere çekmecesi.** 176 px kenar çubuğunun yerini alır. Gerekçe: `NAV_COMPARISON.md` (üst gezinme 741 px ister; ray 60 px yer kaplar).
- **ADR-C: tasarım sistemi v2.** Belirteçler, satır yüksekliği, kaynak başına veri rengi, kontrol kenarı ve 3:1 sınırı. `UI_GUIDE.md` ile çelişen maddeler §5'te listelenmiştir.
- **ADR-D: duyarlı inceleyici.** Kırılım noktaları tek sınıfta tutulur. İnceleyici yanda, altta ya da sayfa olarak görünür; Processes ile Limits aynı kuralı izler. Odak geri dönüşü ve Tab tuzağı tanımlanır.
- **ADR-E: ekran okuyucu duyuruları (açık konu).** JavaFX 25'te canlı bölge yoktur. `UiaRaiseNotificationEvent` için `platform.windows` içinde bir native köprü gerekir; laboratuvarda yapılmadı.
- **ADR-F (isteğe bağlı): ilk açılışta koyu tema.** ADR-0014 düzeltilir.
- **ADR-0013 notu:** laboratuvar ayrı kilit klasörü kullandığı için ürünle aynı anda çalışabilir. Bu yalnızca laboratuvara özgüdür ve ürüne taşınmaz. İki kopyanın aynı anda çalıştırılmaması laboratuvar README'sinde yazılıdır.

## 5. UI_GUIDE ile çelişen maddeler

Bu maddeler geçiş sırasında `docs/UI_GUIDE.md` güncellenerek ya da ilgili değişiklik geri alınarak çözülmelidir. Ayrıntılı liste `DESIGN_DECISIONS_TR.md` §Sapmalar bölümündedir.

## 6. Riskler

- **Platformlar arası doğrulama.** Görsel değişiklikler yalnızca Windows'ta çalıştırıldı. Linux ve macOS yazı tipi ölçümleri, çekmece ve sayfa davranışları doğrulanmadı.
- **Başlık çubuğu.** Windows 10, %150/%200 gerçek ölçek, sistem temasının çalışma sırasında değişmesi ve ilk gösterimdeki renk sıçraması test edilmedi (`TITLEBAR_VALIDATION_TR.md`).
- **Ekran okuyucu ve gerçek yüksek karşıtlık.** Narrator, NVDA ve gerçek yüksek karşıtlık temaları test edilmedi.
- **Kırılım geçişlerinin maliyeti.** Düzen modu değiştiğinde laboratuvar düzeni yeniden kuruyor; sürekli kırılım geçen bir boyutlandırmada baseline'dan 2–5 kat pahalı. Boşta CPU, heap ve satır yenileme baseline ile eşit (MON-12 PASS). Ölçüm: `lab-evidence/F-final/PERFORMANCE_TR.md`.
- **Bellek.** 10 dakikanın sonunda private bytes laboratuvarda 424 MB, baseline'da 365 MB; heap büyümedi. Daha uzun koşu yapılmadı.
- **Görsel test yükü.** `ui-snapshot` etiketli sahne testleri düz kapıda koşmaz; geçişte `-Pui-snapshots` koşusu da kabul ölçütüne eklenmelidir.
