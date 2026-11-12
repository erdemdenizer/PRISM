# Düzeltme turu H: performans (perf) durumu

- Alan: kabuk (`ui/shell`), `shell.css`, grafikler, yeniden boyutlandırma maliyeti.
- Dal: `lab/h-perf`, koordinatörün isteğiyle `lab/h-merged` (6955ae0) üstüne alındı.
- Ayrıntılar, sayılar ve yöntem: `PERF_DIAGNOSIS_TR.md`.
- Gerçek pencere ölçümü yapılmadı (brief gereği). Bütün sayılar ekran dışı yoklamadan (`BreakpointCrossingProbe`). Ölçü, 40 adımda FX iş parçacığı CPU süresi; dönüşümlü koşu medyanları.

## Sayılar

| Durum | Sayı | Bulgular |
|---|---|---|
| FIXED | 1 | PERF-H1 (inceleyici ajanının `b568e3b` değişikliğiyle; bu dalda kod yok) |
| PARTIAL | 0 | — |
| OPEN | 4 | PERF-H2, PERF-H3, PERF-H4, PERF-H5 |
| NOT VERIFIED | 1 | PERF-H6 (gerçek pencere) |

Ek teslim: yoklama aracı (`BreakpointCrossingProbe`, `lab-spikes/perf/breakpoint-probe.sh`). Bir bulgu değil.

## Bulgular

| ID | Durum | Karar | Ne değişti (dosya) | Nasıl doğrulandı | Kalan sınır |
|---|---|---|---|---|---|
| PERF-H1: eşik geçişinde tablo ve inceleyici sahneden çıkıp giriyor; hücre stilleri baştan kuruluyor | FIXED (başka ajanın commit'inde) | Gerçek kullanımı etkiliyor; 8824ada'da Processes geçiş maliyetinin ~2/3'ü. Dosyalar inceleyici ajanının. | Bu dalda yok. Neden JFR ile bulundu, prototiple ölçüldü (−%41) ve atıldı. Aynı nedeni inceleyici ajanı `b568e3b` ile çözdü (`ProcessDock`, `InspectorLayout`). | Processes crossing: 8824ada 1688 → merged 680, ürün temeli 313. Limits: 8824ada 547 → merged 282, ürün temeli 133. | — |
| PERF-H2: `wide/narrow/ultrawide` kök sözde-sınıfları tüm sahnede CSS yürüyüşü yaptırıyor | OPEN | Etki küçük: merged'de ~50 ms / 40 adım (~%7). Kapsamı daraltmak sayfa kenar boşluğunu CSS dışına taşımayı ve dört ajanın CSS'ini değiştirmeyi gerektirir. Kapsam taşması; bu tur yapılmadı. | — | Ölçüm için kapatıldı: merged 711 → 664 ms; aralıklar örtüşüyor. Yalnız yürüyüşün maliyeti (`:ultrawide` aç-kapa): Processes'te ~5–6 ms. | Her eşik geçişinde bir sahne yürüyüşü kalıyor. |
| PERF-H3: `low` geçişi tablo satırlarını yeniden kuruyor, iş ikinci pulse'a kalıyor | OPEN | Bilinçli tasarım (UI-8: alçak pencerede 28 px satır, 30 px kontrol). Sahip kararı. | — | Ölçüm için kapatıldı: ~150 ms / 40 adım. `parts`: `:low` 21–25 ms; 184 düğüm sonraki pulse'a kalıyor. | Gerçek kullanımda çizgi nadiren geçilir; ölçüm döngüsü 40 adımın 16'sında geçiyor. |
| PERF-H4: dar pencerede gezinme (rail) sahneden çıkarılıyor | OPEN (denendi, eklenmedi) | Kanıt yok: gizle/göster denemesi ölçülebilir kazanç vermedi. Test yapı beklentilerini değiştirecekti. Eklenmedi. | — (deneme yaması atıldı) | merged 640 [719 672 531 609] → deneme 610 [625 719 594 578]; Limits aynı. Eşzamanlı `resize` evresi 0,32 → 0,05 ms/adım (~11 ms / 40 adım). | Etkisi gürültünün altında. |
| PERF-H5: sayfaların kendi geçiş işi | OPEN (başka alan) | Ölçüldü. Dosyalar inceleyici ve sayfalar ajanlarının. | — | Kök sözde-sınıflar kapalıyken bile merged 516, ürün temeli 320. Overview crossing 63 → 219 ms (`OverviewPage` boyut dinleyicileri, `MetricPanel` yığılı geçişi `setAll`); Limits 133 → 282 ms. | Teşhis belgesindeki "Sayfalar" bölümü. |
| PERF-H6: gerçek pencerede doğrulama | NOT VERIFIED | Brief gerçek pencereyi yasaklıyor. | — | — | Koordinatör merged imajında resize-40'ı tekrarlamalı. Süreç CPU'su (render) ve UIA etkisi ekran dışında ölçülemedi. |

## Yoklama aracı (bulgu değil)

- Ne: tek sahnede gerçek %125 boyutları. Pulse, 1 piksellik snapshot ile taklit edildi: yalnız kirli düğümler işleniyor, zorlanmış `applyCss` yok. Evreler ayrı ölçülüyor; sayfa başına; ürün temeline karşı da koşuyor.
- Dosyalar: `src/test/java/prism/srm/ui/shell/BreakpointCrossingProbe.java`, `lab-spikes/perf/breakpoint-probe.sh`.
- Doğrulama: `./mvnw -o -Pui-snapshots test -Dtest=BreakpointCrossingProbe` (1 test, 0 hata). Betik 6955ae0 ve 19b7fbe'ye karşı koşuldu.
- Sınır: ekran dışı. Render iş parçacığı ve UIA ölçülmüyor.

## Kapı

Kapı `lab/h-merged` (6955ae0) ve probe commit'i üstünde koşuldu.

- `./mvnw -o clean verify` iki kez koşuldu. İkisinde de tek hata: `CpuLimitIntegrationTest.aBusyProcessIsHeldNearItsLimit…`.
  - Testin kendi ön koşulu "the machine was too busy to measure the workload unlimited" (diğer süreçler %52–62 CPU). Makine yükünden; bu daldaki değişiklik platform koduna dokunmuyor.
- Üçüncü koşu `./mvnw -o verify`: Tests run 2421, Failures 0, Errors 0, Skipped 37. SpotBugs BugInstance 0. BUILD SUCCESS.
- `./mvnw -o -Pui-snapshots test`: Tests run 2310, Failures 0, Errors 0. BUILD SUCCESS.
