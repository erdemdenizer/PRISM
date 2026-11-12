# Fix H — sayfalar (pages) durum dosyası

- Dal: `lab/h-pages`, taban `8824ada`. Kapsam: OOM Guard, Settings, Overview, Performance, Logs, metrik bileşenleri ve CSS'leri.
- Yeni test yardımcısı: `src/test/java/prism/srm/ui/shell/ScaledScenes.java`. Bir sahneyi, render ölçeği ayarlı (hiç gösterilmeyen) bir `Stage` içinde ve gerçek %125 pencerelerinin kesirli sahne boyutlarında (K8, ör. 705,6×442,4) yerleştirir. Böylece JavaFX dolguları ve boyutları %125'teki gibi aygıt pikseline yuvarlanır. Gerçek pencerede ölçüm değildir; ekran dışı yerleşimdir.
- `-real125` görüntüleri: %125 sahne boyutları tam piksele yuvarlanıp %100'de çizilir (`target/ui-snapshots/*-real125-*`).

| ID | Durum | Karar | Ne değişti (dosya) | Nasıl doğrulandı | Kalan sınır |
|---|---|---|---|---|---|
| LO-26 | FIXED | Şimdi düzeltildi; gerçek kullanımı etkiliyor (sahip gerçek pencerede gördü); görsel. Kök neden: %125'te başlığın 10 px dolgusu aygıt pikseline yuvarlanıp 10,4 px oluyor; "Status" metni yüzde bir piksel hassasiyetle ölçülmüş sütuna sığmıyordu. | `OomGuardPage.measured`: ölçülen sütunlar (Emergency action, Status, Priority, Time, State, Action) 4 px ızgarasına yuvarlanmadan önce her yanda 1 px yuvarlama payı alır (`SNAPPED_PADDING = 2`). | `OomHeadersScaleSceneTest` (45 durum: %100–%200, üç sahne, üç durum; her başlık ve ölçülen her hücre tam çiziliyor mu). 8824ada'da %125'te 6 durum "Stat... ≠ Status" ile düşüyor. | Aynı yuvarlama `TextWidths.column` kullanan diğer tablolarda da olabilir (Processes, Limits; inspector ajanının alanı). `TextWidths.cell` içine aynı 2 px payın eklenmesi önerilir; ben `control/` paketine dokunmadım. |
| LS-17 | FIXED | Şimdi düzeltildi; görsel/yerleşim; özellik değil. Kural sabit piksel değil: bölümler alt alta kaydırma gerektiriyorsa VE her sütun okunabilir kalıyorsa (özetin "How it works" sütunu en az 45 karakter, yazı tipinden ölçülür) iki sütun. | `SettingsPage.SectionColumns` (alt alta ya da yan yana yerleşim), `settings.css` (`.settings-column`, 880 px okuma genişliği iki biçimde de). Klavye ve ekran okuyucu sırası değişmedi. | `SettingsLayoutSceneTest` (18 durum: 8 gerçek %100 + 10 gerçek %125 sahne): 1600×500'de özet yan sütunda ve başlıkları kaydırmadan görünür (8824ada'da düşer); 900×1200 ve 1920'de tek sütun; hücre kesilmesi yok; sağa taşma yok. Görüntüler: `settings-all-real125-*` (1586×462: tümü görünür; 1352×730: iki sütun; 1086×662: tek sütun, kaydırır). | 1100×700'de (1086 sahne) iki okunabilir sütuna yer yok: eskisi gibi kaydırır (bilinçli). Uzun yol iki sütunda harf ortasından sarabilir ("settin/gs"); LS-03'ten beri böyle, kesilmez. |
| LS-06 | PARTIAL | Makul maliyetle düzeltildi; ekran okuyucu. Gerçek tablo rolü (`TABLE_VIEW`) bilinçli yapılmadı: JavaFX bu rolü satır/sütun/seçim sorularını yanıtlayan bir kontrole verir; elle yazılmış bir özetin bunu doğru yaptığı bu turda Narrator olmadan doğrulanamaz. `TableView` round G'de reddedildi. | `SettingsPage`: özet artık her işlev için bir satır (`HBox`, ızgaradaki ölçülmüş sabit genişliklerle; görünüm aynı). Her satır tek bir `AccessibleRole.TEXT` öğesi; metni sütun adlarını söyler: "End task. Level: Native soft. How it works: …". Başlık satırı: "8 functions, each with its level and how it works". | `SettingsPageSceneTest.eachRowOfTheSummaryIsOneSentenceThatNamesItsColumns`; mevcut `theSummaryShows…` (24/3/15/9) yeşil. | Satırın içindeki etiketler UIA ağacında hâlâ ayrı metin olarak var (JavaFX'te gizlemenin güvenilir yolu yok). Narrator ile gerçek okuma doğrulanmadı. |
| OP-08 | FIXED (ekran dışı) | Şimdi düzeltildi; gerçek kullanımı etkiliyor; yerleşim kuralıyla, piksel yamasıyla değil. shell.css değişmedi: mevcut `.shell .page:compact` kuralı kullanıldı. | `OverviewPage`: sayfa, olağan aralıklarla ihtiyaç duyduğundan fazla yeri yoksa `:compact` (OOM sayfasının kuralı; karar olağan aralıklı yükseklikten verildiği için kendini besleyemez). `overview.css`: compact'ta sayfa ve metrik satır aralıkları 8. `MetricPanel`: izleme çalışmıyorken grafiğin en küçük yüksekliği 64 yerine 32 (çizilecek geçmiş yok); yer varsa yine tüm alanı alır. | `OverviewFitSceneTest` (15 durum: 705,6×442,4 @1,25; 706×435; 704×441 × canlı, hata, sistem/süreç/hepsi durmuş): dikey kaydırma çubuğu yok, alt olgular görünür. 8824ada'da 13/15 düşüyor. Görüntüler `overview-*-real125-*-706x442`. `OverviewSnapshotTest.aPageThatDoesNotFit…` artık 4 kat uzun klasör yolu kullanıyor (eski yol artık sığıyor). | Gerçek %125 pencerede kontrol edilmeli. Performance bölümleri de `MetricPanel` kullanır: izleme durunca orada da grafik en az 32 px olabilir (sayfa yine kaydırır). |
| OP-17 | PARTIAL | (A) round G'de yapıldı; bu turda %125 boyutlarında yeniden bakıldı: 128 işlemcide hücreler 64 px'te kalıyor, toplam payını koruyor, sığmayan kısım kayıyor; "kırılma" görmedim, değişiklik yapılmadı. (B) sekmelerde güncel değer: OPEN. K7 yeni özellik/metriği yasaklıyor; değer zaten var ama sekme çubuğuna saniyede bir güncellenen dört yeni okuma eklemek yeni bir arayüz işlevidir ve sunumdan öte bir karar; Overview dört değeri zaten birlikte gösteriyor. | `PerformanceRealSizeSnapshotTest.rendersOneHundredTwentyEightProcessorsAt125` (yalnız görüntü). | `performance-cpu-128-processors-real125-dark-*` (10 boyut) tam boy incelendi (1586×462, 886×1162). | (B) sahip kararı. "Küçük alanda kırılma" bulgusunun hangi görüntüye dayandığını bulamadım; koordinatör örnek gösterirse bakılır. |
| OP-14 | OPEN | Benim alanım değil (app.css/shell.css); polish aşamasına liste. Çoğu kontrol iç ölçüsü; aşağıda hangilerinin gerçekten ölçek dışı olduğu ayrıldı. | — | — | Liste aşağıda. |
| LO-19 | PARTIAL | OOM/diyalog alanımda kalan ölçek dışı değer yok: `oom.css`'teki `0 0 1 0` dolgular LO-13 taban çizgisi hizasıdır (OomPageLayoutSceneTest ölçer), bilinçli. Diyalog dolgusu round G'de 24. | Değişiklik yok. | `OomPageLayoutSceneTest` (32) yeşil. | `limits.css` (inspector alanı): `.limit-editor-key { -fx-padding: 2 0 0 0 }` taban çizgisi yaması ve anahtar/değer 1 px kayması; öneri `GridPane` hücrelerinde `VPos.BASELINE`. |
| KB-2 | FIXED (ekran dışı) | Koordinatörün klavye kabul bulgusu; gerçek kullanımı etkiliyor. Tab durağı olan tablo, odaklı satır yokken (boş tablo ya da odak modeli -1 iken girilen Logs tablosu) hiçbir şey çizmiyordu. Davranış değişmedi: tabloya satır odaklanmıyor, seçilmiyor. | `prism.srm.ui.TableFocus` (yeni): satır odağı yokken tabloya `:no-focused-row`. `oom.css` (`.oom-table`, `.oom-recent`) ve `logs.css` (`.logs-table`): o durumda çerçevenin 2 px dışında 2 px odak halkası (her denetimdeki halka; her durumda var olan saydam kenarlık, hiçbir şey kaymaz). Satır odaklanınca halka satıra geçer (table.css). `OomGuardPage`, `LogsPage` iki satırla bağlandı. | `TableFocusRingSceneTest` (iki tema; OOM yönetilen boş, OOM son eylemler boş, Logs satır odaksız): odakla tablo çevresindeki pikseller değişiyor (>1000), satır odaklanınca çerçeve halkası yok. CSS kuralları olmadan 3 test düşüyor. Görüntüler `kb2-focus-*`. | Aynı kural her tablo için `table.css`'e taşınmalı (koordinatör uygular, rapora yazıldı). Gerçek pencerede Tab ile doğrulanmadı. |

## OP-14: polish aşaması için ölçek dışı değerler (4, 8, 12, 16, 24 dışında)

Gerçekten ölçek dışı ve görünür olanlar (audit OP-14'ün kendisi):
- `app.css` `.panel { -fx-padding: 14 16 14 16 }` → 12 16 (audit A). Panelli her sayfayı etkiler; değişirse `OverviewFitSceneTest`, `OomPageLayoutSceneTest`, `SettingsLayoutSceneTest` yeniden koşulmalı.
- `shell.css` `.shell:low .panel { -fx-padding: 10 14 10 14 }` → 8 12.
- `shell.css` `.shell:low .text-page-title { -fx-font-size: 19px }` → 20 px.

Ölçek dışı ama kontrol iç ölçüsü (değiştirmeden önce ayrı bakılmalı):
- `app.css` `.toggle-button { -fx-padding: 0 14 0 14; -fx-graphic-text-gap: 6 }`
- `app.css` `.context-menu { -fx-padding: 5 }`, `.context-menu .menu-item { -fx-padding: 5 14 5 10 }`
- `app.css` `.segment { -fx-padding: 0 10 0 10 }`, `.text-field { -fx-padding: 0 10 0 10 }` (tablo hücresinin 10 px dolgusuyla aynı; `TextWidths.CELL_PADDING` buna dayanıyor)
- `app.css` `.filter-field > .text-field { -fx-padding: 0 30 0 32 }` (simge + temizle düğmesi payı)
- `app.css` `.radio-button > .radio { 3 }`, `.check-box > .box { 4 3 4 3 }`, `.check-box > .box > .mark { 4 5 4 5 }`, kaydırma çubuğu düğmeleri `6` (kutu/işaret geometrisi)
- `shell.css` `.nav-rail { -fx-padding: 10; -fx-spacing: 6 }`, `.nav-rail:labelled .nav-item { -fx-padding: 0 10 0 10 }` (`Breakpoints.SIDEBAR_WIDTH` hesabı 10 px'e dayanıyor)
- `shell.css` `.shell:narrow .page-header { 0 0 4 44 }`, `.shell:low:narrow .page-header { 0 0 0 44 }` (44 = 32 px çekmece düğmesi + 12)

Benim alanımda bilinçli olanlar: `metric.css` `.shell:low .metric-panel .metric-values { 3 0 0 0 }` (OP-06 ad hizası), `oom.css` üç `0 0 1 0` (LO-13 taban çizgisi).

## table.css için önerilen kural (KB-2, koordinatör uygular)

```css
/* KB-2: a table with no focused row draws the focus ring of every control around its frame (TableFocus). */
.table-view {
  -fx-border-color: transparent;
  -fx-border-width: 2;
  -fx-border-insets: -4;
  -fx-border-radius: 14;
}

.table-view:focus-visible:no-focused-row {
  -fx-border-color: -srm-focus-ring;
}
```
Her tablo `prism.srm.ui.TableFocus.track(table)` ile bağlanmalı (Limits, Processes). Kural `table.css`'e girince `oom.css` ve `logs.css`'teki kopyalar silinebilir.

## Gerçek pencerede bakılacaklar
1. OOM Guard, 1366×768 %125: "Status" başlığı tam; 720×480 ve 1600×500'de de.
2. Settings 1600×500 ve 1366×768 %125: özet sağ sütunda, kaydırmadan görünür; 1920×1080 ve 900×1200'de tek sütun.
3. Overview 720×480 %125: canlıda kaydırma çubuğu yok; izleme durdurulunca (ör. hata durumu) alt olgular görünür.
4. KB-2: OOM Guard (yönetilen süreç yokken) iki tabloya ve Logs tablosuna Tab ile girince çerçevede odak halkası; Logs'ta ↓ ile satıra geçince halka satırda.
5. Narrator: Settings özetinde bir satıra gelince tek cümle okunuyor mu.
