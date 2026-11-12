# Düzeltme turu G: Overview ve Performance durumu

Alan: `ui/graph/**`, `ui/metric/**`, `ui/overview/**`, `ui/performance/**` (dal `lab/g-overview`, temel `5565a77`).
Bulgular: `02-overview-performance.md` OP-01…OP-17, `06-prototype-gap.md` GP-15/16/22/44, sahip kararları K4 ve K5.

Durumlar: FIXED (düzeltildi ve testle ya da tam boy snapshot ile doğrulandı), PARTIAL (bir kısmı yapıldı),
OPEN (bilerek yapılmadı), NOT VERIFIED (kod var, gerçek pencerede görülmedi).

| Bulgu | Durum | Ne değişti (dosya) | Nasıl doğrulandı | Kalan sınır |
|---|---|---|---|---|
| OP-01, K4, GP-15, GP-22 | FIXED | Dört metrik paneli Performance'ı ilgili sekmesiyle açar; Processes ve Left suspended → Processes, Managed → Limits, OOM Guard → OOM Guard. Sistem olguları (OS, uptime) bir yere götürmez. Her parça bir düğme: odaklanır, Enter/Space, el imleci, AccessibleRole BUTTON, "Open Performance, Network" gibi metin, FIRE eylemi (`metric/OpenablePart.java`, `MetricPanel.java`, `FactView.java`, `overview/OverviewPage.java`). Shell'e tek bağlantı: `overview.setOnOpen(this::openFromOverview)` ve özel `openFromOverview` (`shell/Shell.java`). Performance'a `select(Section)` ve `selected()` (`performance/PerformancePage.java`, `SectionTabs.java`, `Section.java` public). | `OverviewLinksSceneTest`: her parçadan tıklama, Enter, Space, FIRE; sürükleme ve sağ tık bir şey yapmaz; düğme özellikleri. | Gerçek pencerede el imleci ve Narrator okuması görülmedi. Gezinme sonrası odak Shell'in davranışıyla gezinme başına gider. |
| GP-16 | FIXED | Panelde hover: güçlü kenar; olguda hover: yüzey; ikisinde de `:focus-visible` halkası (`metric.css`). "↗" işareti eklenmedi (dekoratif). | `overview-states-real` snapshot'ları, iki tema, sekiz gerçek boy, tam boy incelendi. | Gerçek fare ile hover görülmedi. |
| OP-02, K5, GP-44 | FIXED | Geçmiş okuması: ikinci Canvas'ta (overlay) dikey çizgi ve seri başına nokta; okuma panelin eksen satırında ("23 s ago 108.1 KiB/s 19.9 KiB/s"), çift seride iki değer işaretleriyle. Fare: hareket gösterir, çıkış gizler. Klavye: Sol/Sağ (Shift ile 10), Home/End, Esc temizler. Odak gidince imleç silinir. Ekran okuyucu metni geçmiş değeri de söyler. Birimler `ValueFormatter` ile (`graph/HistoryGraph.java`, `CanvasGraph.java`, `PlotGeometry.java`, `GraphData.java`, `metric/MetricPanel.java`, `MetricReadout.java`, `SystemReadouts.java`). Bağlama kuralı: imleç bir **yaşı** (en yeniden kaç örnek önce) tutar; 1 Hz güncellemede çizgi yerinde kalır ve altındaki daha yeni örneği okur. | `CursorMappingTest` (x → indeks, kenarlar, kapasiteden kısa geçmiş), `HistoryGraphCursorTest` (fare hareketi veri Canvas'ını yeniden çizmez: sayaç; tuşlar, Esc, boş grafik, piksel), `OverviewLinksSceneTest` (elle hesaplanmış okuma metinleri; Tab ile okuma biter), `performance-network-reading-real`. | Gerçek fare ile çizgi ve %125 DPI'da piksel hizası görülmedi. Overview'da grafik ayrı bir odak durağı değil (panel düğme); orada klavye okuması panel odaktayken oklarla. |
| OP-03 | FIXED | Overview parçaları Tab sırasında: CPU, Memory, Disk, Network, Processes, Managed, OOM Guard. Performance'ta grafik bir odak durağı. `overview-focus` snapshot'ı 720 yerine varsayılan boyda (720'de gezinme çekmece, giriş görünmüyor). | `OverviewLinksSceneTest` Tab sırası; `KeyboardRoutesSceneTest` rotaları güncellendi (1100×700 ve 704×441). | verify-image.ps1 klavye adımları yeni Tab sırasıyla değişebilir; koordinatör denetlemeli. |
| OP-04 | FIXED | Lejant etiketinin yüksekliği metninden kısaydı (normal 16/17, alçak 12/16): `.series` 35, `.series-legend` 17, alçak modda 16 (`metric.css`). | `OverviewLinksSceneTest`: sekiz gerçek boyda hiçbir lejant kesilmiyor (sınırlar); tam boy snapshot'lar. | — |
| OP-05 | FIXED | Alt satır GridPane: iç kenar metrik panellerinin gerçek `layoutX` değerine bağlı; dört sütunda ilk iki metrik, iki sütunda ilk metrik genişliğinde; tek sütunda %50/%50 (`OverviewPage.java`, `overview.css`). | `OverviewLinksSceneTest`: sekiz gerçek boyda olgu panelleri metrik kenarlarını paylaşıyor. | 706 genişlikte OS adı hâlâ kısalıyor; tam adı tooltip'te. |
| OP-06 | FIXED | Tek ve çift değerli panellerde başlık taban çizgileri eşit (normal 45/45, alçak 36/36) (`metric.css`). | `OverviewLinksSceneTest`: her satırda taban çizgileri eşit, sekiz gerçek boy. | — |
| OP-07 | FIXED | (a) Durmuş izlemede panel "Monitoring is not running" der, ayrıntı üstteki satıra gönderilir. (b) Eksen satırında kısa gerekçe, tamamı tooltip'te. (c) "Logical processors" gerekçesini söyler (`CpuModel.java`). (d) "for this cycle" kaldırıldı (`DiskSection.java`, `NetworkSection.java`). (e) Durmuş ya da başlatılamamış izlemede Managed ve OOM Guard "Reading…" yerine Unavailable (`OverviewViewModel.java`). | `PerformanceViewModelTest`, `ShellFailureTest` beklentileri bilerek güncellendi (7 → 9 Unavailable); `performance-*-real` snapshot'ları. | (e) gerçek uygulamada başarısız başlatmada görülmedi. |
| OP-08 | OPEN | — | 706×435 tam boy incelendi: canlı durumda birkaç piksellik kaydırma çubuğu var; durmuş izlemede iki satırlık hata metniyle alt olgular görünmüyor. | Alçak modda Overview'a `:compact` uygulamak shell'in sahip olduğu `shell.css`/`app.css` işi; %125 DPI gerçek pencere gerektirir. |
| OP-09 | FIXED | Matris Performance için `PerformanceSnapshots.live()` kullanır (`SizeMatrixSnapshotTest.java`); `PerformanceRealSizeSnapshotTest` dört sekmeyi gerçek boylarda çizer. Gerekçe metni varken hücre alanı en çok 64 px (`CpuSection.java`). | `performance-*-live-real` snapshot'ları tam boy incelendi. | — |
| OP-10 | FIXED | Sütun sayısı düzenli: 4 ya da daha az, 4'ün katı veya hücreleri tam satırlara bölen sayı; 128 işlemci 13 yerine 12'lik satırlarda (`graph/GridLayout.java`). | `GridLayoutTest` (yeni ve bilerek değişen durumlar), `performance-cpu-128-processors-real`. | — |
| OP-11 | FIXED | 2×2 grup en çok 2×960 px, ortalanır (`OverviewPage.java`). | `overview-live-real` 1906×987 ve matris. | 3440×1440 gerçek pencerede görülmedi. |
| OP-12 | FIXED | Hız ölçeği tam adımda ondalıksız ("500 KiB/s", "2 MiB/s"); bellek ölçeği değişmedi (`format/ValueFormatter.java` `rateScale`, `SystemReadouts.java`). | `ValueFormatterTest` (iki yerel ayar), `OverviewViewModelTest` ve `PerformanceViewModelTest` beklentileri bilerek güncellendi. | — |
| OP-13 | FIXED | Seçenek B: imleç okuması zamanı söyler ("Now", "N s ago"). | OP-02 testleri. | Eksen etiketi eklenmedi. |
| OP-14 | OPEN | — | — | Değerler `app.css` ve `shell.css`'te; başka ajanların alanı. Saf 1–4 px işi. |
| OP-15 | OPEN | — | — | Tablo CSS'i proclimits'in (`table.css`); 4 px hizalama. |
| OP-16 | FIXED | İkinci seri birinciden sonra, 1,25 px ve 5/3 kesikle çizilir (`HistoryGraph.java`). | `HistoryGraphRenderingTest`, snapshot'lar. | — |
| OP-17 | PARTIAL | (A) Total CPU paneli pencere yüksekliğinin en az %30'unu tutar, bu 160 px'ten büyükse (`CpuSection.java`). (B) Sekmelerde güncel değer yapılmadı: sahip onayı ister. | `performance-cpu-128-processors-real`. | B için sahip kararı. |

## Doğrulama

- `./mvnw -o clean verify` iki kez çalıştırıldı: 2413 testten 2412'si geçti. İkisinde de tek hata
  `CpuLimitIntegrationTest` idi ve test kendisi "the machine was too busy to measure the workload
  unlimited" diyor: diğer worktree'lerdeki derlemeler makineyi meşgul ediyordu. Bu dal platform
  koduna dokunmuyor.
- Aynı kapı, yalnızca bu test dışarıda (`-Dsurefire.excludesFile`): BUILD SUCCESS, 2405 test,
  0 hata, SpotBugs 0, Spotless ve JaCoCo geçti.
- `./mvnw -o -Pui-snapshots test`: tümü bir kez çalıştırıldı. Bilerek değişen beklentiler
  güncellendikten sonra `KeyboardRoutesSceneTest`, `ShellFailureTest`, `OverviewSnapshotTest`,
  `PerformanceSnapshotTest`, `PerformanceRealSizeSnapshotTest`, `SizeMatrixSnapshotTest` yeniden
  çalıştırıldı: 453 test, 0 hata. `OverviewLinksSceneTest` (45), `OverviewRealSizeSnapshotTest`
  (32) ve `HistoryGraphCursorTest` (7) ilk çalıştırmada geçti.
- Gerçek pencerede, gerçek fareyle, Narrator ile ve %125 DPI'da hiçbir şey görülmedi (NOT VERIFIED).
