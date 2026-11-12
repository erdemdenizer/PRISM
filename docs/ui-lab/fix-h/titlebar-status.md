# Düzeltme turu H — başlık çubuğu durumu

Dal `lab/h-titlebar`, taban `8824ada`. Kapsam: mevcut native başlık çubuğunun görsel bütünlüğü (görev 1) ve tümleşik başlık çubuğu değerlendirmesi (görev 2, `TITLEBAR_STATUS_TR.md`).

Yapı değişmedi:
- pencere DECORATED;
- başlık düğmeleri Windows'un kendi düğmeleri;
- renkler DWM öznitelikleri 20, 35, 36 ve 34 ile veriliyor (ADR-A).

`platform/**`, `App.java`, `MainWindow.java` ve `ThemeController.java` değişmedi. Değişen tek üretim dosyası `app/TitleBarTint.java`.

Bu turda gerçek pencere açılmadı. Gerçek pencere doğrulaması koordinatörde; kontrol listesi `TITLEBAR_STATUS_TR.md` §3'te.

## Bulgular

| ID | Durum | Karar | Ne değişti | Nasıl doğrulandı | Kalan sınır |
|---|---|---|---|---|---|
| TB-H1 Pasif pencerede başlık metni soluklaşmıyor | NOT VERIFIED (kod bitti, gerçek pencere bekliyor) | Hemen düzelt. Gerçek kullanımı etkiliyor: bir diyalog açıkken ana pencere aktif gibi okunuyor. Windows kuralına aykırı. **Kanıt:** `lab-evidence/B-titlebar/dwm-dark-active.png` ile `dwm-dark-inactive.png` başlık metni pikselleri birebir aynı (236,238,242); G turunda `20-1100x700-processes.png` ile `60-dialog-Endtask.png` de aynı. Windows yalnızca düğme gliflerini soluklaştırıyor (255 → 115). Önceki doğrulama belgesindeki "metin soluklaşıyor" ifadesi metin için yanlıştı | `app/TitleBarTint.java`: her Stage'in `focusedProperty`'si izleniyor. Pasif pencerede başlık metni `-srm-text-subtle` oluyor: koyu #8F98A8 (6,1:1), açık #5C6474 (5,2:1). Başlık ve kenar rengi değişmiyor. Her pencere en son aldığı rengi hatırlıyor; görünümü değiştirmeyen odak değişimi native çağrı yapmıyor. Arama sürerken gelen değişimi o aramanın sonraki denemesi alıyor, ikinci arama başlamıyor. Bulunamayan pencere odak değişiminde değil, yalnızca tema değişiminde yeniden aranıyor | `TitleBarTintTest`: 12 test (CSS'ten okunan pasif token'lar, kontrast tabanı, `due` tablosu). `TitleBarTintSceneTest`: 8 test, yeni 3 tanesi odak, arama sırasında değişim ve kaybolan pencere | DWM metni pasif olunca hemen boyamıyor: Windows düğmeleri soluklaştırıyor, FX odak olayından sonra metin de soluklaşıyor. Arada bir kare fark olabilir. Gerçek pencerede bakılmalı |
| TB-H2 Başlık rengi ray, sayfa başlığı ve durum satırıyla aynı mı | FIXED (önceki turlar), bu turda yeniden ölçüldü | Değişiklik gerekmiyor. G turu gerçek pencere pikselleri: koyu başlık = ray = sayfa başlığı = (22,24,29) = `-srm-chrome` #16181D; açık (238,240,243) = #EEF0F3. Başlıkla içerik arasında çizgi yok | — | `theColorsAreTheTokensOfTheStylesheet` token kaymasını yakalıyor. Pikseller: `sonra-125/20-1100x700-processes.png` ve `90-light-1366x768-processes.png` | — |
| TB-H3 Başlık metni (kısa başlık ya da sayfa adı) | OPEN, bilinçli tasarım | Değiştirilmedi. **Gerekçeler:** <br>1. `lab-docs/LAB_DESIGN_SPEC.md` §9 başlığı "PRISM UI Lab — Process & Resource Interactive System Manager" olarak sabitliyor. <br>2. Sayfa adını başlığa koymak her gezinmede `setTitle` demek. Native başlık JavaFX'ten bir an sonra geldiği için tam başlıkla arama yeniden arama turu ister; betikler de başlığı tam eşleştiriyor. <br>3. Sayfa adı zaten büyük sayfa başlığında görünüyor. <br>4. 720 px dış genişlikte metin kesilmeden sığıyor (G turu görüntüleri) | — | `MainWindowTest` başlığı doğruluyor (değişmedi) | **Başlığı tam metinle eşleştirenler:** <br>- `packaging/windows/verify-image.ps1` (`$Title` satır 102; kullanıldığı satırlar 183, 416, 566, 569, 577, 893, 1308, 1312); <br>- `MainWindowTest`. <br>**`-like 'PRISM*'` ile eşleştirenler:** `lab-spikes/perf/perf-run.ps1:42`, `matrix-run.ps1:60`. <br>**Başlığa bakmayanlar:** `kanit/betikler/fix-verify.ps1` (`MainWindowHandle` kullanıyor), `lab-spikes/titlebar/*` (kendi spike başlığı). <br>Başlık değişirse ilk satırdakiler güncellenmeli |
| TB-H4 Kenar rengi iki temada | OPEN, bilinçli tasarım | Kenar iki temada ve iki durumda da `-srm-hairline`. Böylece kenar, rayın sağındaki çizgiyle aynı ve çerçeve tek parça okunuyor. Açık temada açık bir arka plan üzerinde ayrımı Windows'un gölgesi sağlıyor | — | Token testi | Açık temada beyaz bir pencerenin önünde pasif pencerenin kenarı zayıf görünebilir. Kontrol listesi madde 6 |
| TB-H5 Tema değişimi ve yüksek kontrast açılıp kapanınca yeniden boyama | FIXED (kod), gerçek yüksek kontrast NOT TESTED | Davranış korundu. Tema, sistem renk şeması ve yüksek kontrast değişimleri her pencereyi koşulsuz yeniden boyuyor; önbellek yalnızca odak değişimlerini atlıyor. Windows kendi tema değişiminde DWM rengini sıfırlarsa bile önbellek yeniden boyamayı engellemez | `TitleBarTint.refresh(frame, true)` | `aFocusChangeTintsOnlyWhenTheLookChanges` (tema değişiminde aynı görünüm yine uygulanıyor), `underHighContrastTheLookIsTheSystemsOwn` | Gerçek yüksek kontrast oturumu yok; kullanıcının ayarına dokunulmadı |
| TB-H6 İlk gösterimde bir karelik açık renk flaşı (E11) | OPEN | Kapsam dışı. Çözümü `DWMWA_CLOAK` ya da ilk gösterimde opaklık 0; bu bir platform değişikliği. Ölçülmedi | — | — | Yüksek hızlı yakalama gerekiyor |
| TB-H7 Snap Layouts bölgeleri ile en küçük genişlik | OPEN, bulgu kaydı | Microsoft Learn, Snap Layouts için en küçük genişliğin en çok 500 epx (tercihen 330) olmasını öneriyor. Laboratuvarın en küçük genişliği 720 (UI_GUIDE). 1920 px ekranda üçlü sütun bölgesi 640 px, pencere oraya sığmaz. Ürün kuralı olduğu için değiştirilmedi | — | Kaynak: learn.microsoft.com/en-us/windows/apps/desktop/modernize/ui/apply-snap-layout-menu | Kontrol listesi madde 9 |
| TB-H8 Tümleşik başlık çubuğu (başlık alanına uygulama içeriği) | OPEN, platform sınırı | JavaFX 25'te önerilmiyor: preview API, diyaloglar kapsanmıyor. Ayrıntı `TITLEBAR_STATUS_TR.md` | — | Yerel jar'lar `javap` ile incelendi, resmi kaynaklar okundu | Gelecek iş: JavaFX 27'ye geçiş ADR'si |

Sayılar: 1 kod düzeltmesi (TB-H1, gerçek pencerede NOT VERIFIED), 2 korunan ya da yeniden ölçülen davranış (TB-H2 FIXED, TB-H5 FIXED kod), 5 OPEN (TB-H3, H4, H6, H7, H8; gerekçeleri tabloda).

## Kapı

- `./mvnw -o clean verify`, ilk çalıştırma: 2421 test, 1 hata, BUILD FAILURE. Hata `CpuLimitIntegrationTest`: "the machine was too busy to measure the workload unlimited"; dört ajan aynı anda derliyordu. Bu turun değişikliğiyle ilgisi yok.
- Aynı test tek başına tekrar çalıştırıldı: PASS.
- `./mvnw -o clean verify`, ikinci çalıştırma: 2421 test, 0 hata, 37 atlanan, BUILD SUCCESS.
- `./mvnw -o -Pui-snapshots test`: 2110 test, 0 hata, BUILD SUCCESS.
