# Düzeltme turu G: proclimits durumu

Alan: Processes (PR-01…PR-20), Limits (LO-01, 02, 05, 06, 07, 08, 09, 21, 23, 25) ve sahip kararı K2.
Dal: `lab/g-proclimits`, temel `5565a77`.

Doğrulama komutları:

- Odaklı sahne testleri: `./mvnw -o -Pui-snapshots test -Dtest=...`
- Bütün `ui-snapshot` takımı: `./mvnw -o -Pui-snapshots test`
- Tam kapı: `./mvnw -o clean verify`

Görüntüler `target/ui-snapshots/` altında, tam boyutta incelendi. Gerçek pencere ve kesirli DPI denenmedi.

## K2 nasıl uygulandı

Seçilen yol D + E + B'nin bir kısmı oldu.

**Ortak çerçeve.** `InspectorFrame` adında yeni bir denetim var. Üstte sabit bir parça duruyor: ad, eylemler, gerekçeler ve sonuç satırı. Altında tek bir kaydırma alanı var. Kaydırma çubuğu panelin içinde, görünür bir izin üzerinde. Görülmeyen içerik varsa kenarda 1 px çizgi beliriyor. Pencere sabit parçaya ve 72 px ayrıntıya yetmeyecek kadar alçaksa sabit parça da ayrıntılarla birlikte kayıyor. İç içe ScrollPane yok.

**Mod seçimi.** Mod artık pencereye göre değil, sayfanın gerçek boyutuna göre seçiliyor (`Breakpoints.inspectorFor(pageW, pageH)`):

- Genişlik 1260 ve üstündeyse SIDE.
- Genişlik 960 ve üstü, yükseklik 500 ve üstüyse BOTTOM.
- Genişlik 640 ve üstü, yükseklik 800 ve üstüyse dar BOTTOM.
- Bunların dışında SHEET.

**BOTTOM yerleşimi.** Panel içeriği kadar yükseliyor. Yer varsa tablo en az 5 satır gösteriyor; sabit parça ve 72 px ayrıntı her zaman önce geliyor ve tabloya en az 2 satır kalıyor. Süzgeç tek satır bıraktığında panel yerin tamamını alıyor.

**SHEET yerleşimi.** Tablonun altında bir `SelectionLine` duruyor: seçili sürecin adı, PID'si ve son sonuç. Böylece sheet kapalıyken de sonuç kaybolmuyor.

## Kapı sonucu

- Bütün `ui-snapshot` takımı: 1555 test, hepsi geçti.
- `./mvnw -o clean verify`, HEAD `194be2e` üzerinde: `Tests run: 2398, Failures: 1, Errors: 0, Skipped: 37`, BUILD FAILURE.
- Başarısız olan tek test `CpuLimitIntegrationTest.aBusyProcessIsHeldNearItsLimit…`. Testin kendi koruması "the machine was too busy to measure the workload unlimited" diyor: diğer ajanların derlemeleri aynı anda çalışıyordu. Test tek başına da yeniden çalıştırıldı ve aynı nedenle başarısız oldu.
- Bu test `platform` paketinde; bu dal ona dokunmuyor. Makine boşken yeniden çalıştırılmalı.
- İlk kapı denemesinde StylesheetsTest'in yakaladığı onaltılık renk `194be2e` ile düzeltildi.

## Bulgular

| ID | Durum | Ne değişti | Nasıl doğrulandı | Kalan sınır |
|---|---|---|---|---|
| K2 | FIXED | Yukarıdaki D + E + B yapısı | ProcessInspectorSceneTest, ProcessActionsSceneTest, InspectorFrameSceneTest; gerçek boyut görüntüleri | 1586×455 gibi alçak ve geniş pencerelerde sabit parça kayıyor (PR-03'e bakın) |
| PR-01 | FIXED | Eylemler sabit parçada. Kaydırma izi görünür, kenar çizgisi var. | InspectorFrameSceneTest; 1086×655 ve 1352×723 görüntüleri | — |
| PR-02 | FIXED | Gerekçe ve sonuç satırı sabit parçada. SHEET'te tablonun altında SelectionLine var. | `underTheTableTheLineOfAResultIsPinnedInTheInspectorsView`; `processes-sheet-real-*` görüntüleri | — |
| PR-03 | PARTIAL | 1352×723 SIDE'da End task ve Force kill kaydırmadan görünüyor. | `processes-result-real-1366x768-*` görüntüsü | 1586×455'te sabit parça kayıyor ve End process kat çizgisinin altında kalıyor. Kenar çizgisi ve iz bunu belli ediyor. |
| PR-04 | FIXED | Sabit %40 payı kaldırıldı. Panel içeriğine göre boyutlanıyor; tablo 5 satır ya da en az 2 satır koruyor. | `underTheTableTheInspectorFitsItsContentAndTheTableKeepsItsRows` | 1026×579'da (yalnız sayfa) tablo 2 ile 5 satır arasında kalıyor. |
| PR-05 | FIXED | `InspectorRealSizeSnapshotTest` eklendi: Processes ve Limits, 8 gerçek sahne boyutunda, iki temada, 112 görüntü. | 112 görüntü üretildi ve incelendi. | — |
| PR-06 | FIXED | Details düğmesi artık kesilmiyor (en küçük genişliği tercih genişliği). Süzgeç, satırın üst öğesinin genişliğine göre katlanıyor. | `inANarrowTitleLineTheFilterFoldsIntoItsButton`; PageToolbarSceneTest | Kurtarma özeti ve süzgeç ipucunun kesilmesine dokunulmadı. |
| PR-07 | PARTIAL | SHEET'te seçim tablonun altındaki satırda görünüyor. 900×1200 artık dar BOTTOM. | `aTallNarrowWindowDocksBothInspectorsUnderTheTable` | Details düğmesinin "quiet" görünümü ve simgesizliği aynı kaldı. Icon kümesine dokunulmadı. |
| PR-08 | FIXED | Panel kendi kenarlığını çiziyor ve kaydırma panelin içinde. Panel tabloyla aynı genişlikte ve alt kenarı her zaman var. | 1086×655 görüntüleri | — |
| PR-09 | FIXED | Sütunlu düzende başlıklar tek hizada. Kısmi üst çizgiler kaldırıldı; sabit parçanın altında tek bir çizgi var. | `underTheTableTheSectionsStandInColumnsAndDetailsIsNotThere`; görüntü | — |
| PR-10 | FIXED | LimitsSection'ın genişlik hesabı durum ve gerekçe metnini dışarıda bırakıyor. | `underTheTableTheColumnsOfTheActionsDoNotMoveWithTheSelection` | — |
| PR-11 | PARTIAL | Koyu temada `-srm-inspector-rule` ve `-srm-inspector-edge` mevcut belirteçlerden `derive` ile türetiliyor ve yalnız Processes ile Limits'te kullanılıyor. Ölçülen değerler: çizgi #383d46, yükseltilmiş yüzeyde yaklaşık 1,3:1; kenar #3b3f47, pencerede yaklaşık 1,6:1. | Koyu görüntülerde piksel ölçümü; StylesheetsTest | Onay diyaloğu ayırıcısı oom alanında. CONTRAST.md yeniden üretilmedi. |
| PR-12 | FIXED | Dar düzende 400 px'in altında Now tek sütun oluyor. | `besideTheTableNowIsOneColumnAndNoValueIsCut` | — |
| PR-13 | OPEN | `Math.floor` ve 1 px pay denendi. Tam dolum testlerini (ProcessTableWidthTest) bozdu ve geri alındı. | — | Kesirli DPI, test düzeneğinde üretilemiyor; gerçek %125 pencere gerekiyor. |
| PR-14 | OPEN | Değişiklik yok. | — | JavaFX ScrollBarSkin en kısa başparmak uzunluğunu kalınlıktan türetiyor; CSS `min-height` ile çözülemiyor (olası, doğrulanmadı). |
| PR-15 | PARTIAL | Seçim yokken "Not managed by Prism." yerine "–" gösteriliyor. | `withNoSelectionTheManagedLineSaysNothingAboutAProcess` | İzleme durduğunda inceleyicinin tek satıra inmesi yapılmadı (UI_GUIDE kuralından sapma kararı gerekiyor). |
| PR-16 | OPEN | Değişiklik yok. | — | Sahip kararı ve DESIGN_DECISIONS §7 satırı gerekiyor. |
| PR-17 | PARTIAL | SHEET'te seçili sürecin adı tablonun altında görünüyor. | Görüntü | Name sütunu sabitlenmedi (efor L). |
| PR-18 | OPEN | Değişiklik yok. | — | Düşük öncelik. |
| PR-19 | OPEN | Değişiklik yok. | — | Biçim kararı gerekiyor. |
| PR-20 | OPEN | Değişiklik yok. | — | Menü yapısı değişikliği kapsam dışında kaldı. |
| LO-01 | FIXED | Gruplar `ActionGroup`'ta: en küçük genişlik en geniş düğme kadar. Değer sütunu en az 160 px ve büyüyor. | `LimitEditorFitSceneTest`: 28 boyut, iki durum. Test temelde (`5565a77`) 17 boyutta başarısız oluyor, burada geçiyor. | — |
| LO-02 | FIXED | Düğmeler FlowPane'de sarılıyor; üst üste binmiyor. | `LimitEditorFitSceneTest` | Örtüşme test düzeneğinde temelde de üretilemedi; gerçek 1177×784 pencerede bakılmalı. |
| LO-05 | FIXED | Mod sayfa boyutuna göre seçiliyor. Gerçek 1086×655 BOTTOM ve eylemler görünüyor. | BreakpointAgreementSceneTest; `limits-*-real-1100x700-*` görüntüleri | — |
| LO-06 | PARTIAL | Sabit parça ya da kaydırma alanı var. Kesik düğme kalmadı; kenar çizgisi ve iz görünüyor. | Görüntüler (706×435, 1586×455) | Alçak pencerelerde eylemler kaydırma ile ulaşılıyor. |
| LO-07 | FIXED | DisabledReasons `maxWidth = MAX_VALUE`. | `theReasonsOfAGroupTakeItsWidth` | — |
| LO-08 | FIXED | Her grubun kendi gerekçe satırı, kendi düğmelerinin altında. | Görüntüler; LimitsPanelSceneTest | — |
| LO-09 | FIXED | Sonuç satırı panelin içinde, grupların altında. SHEET'te SelectionLine'da. | Görüntü (`limits-result-real-*`) | — |
| LO-21 | FIXED | "Seçim yok" cümlesi en çok 300 px genişlikte. | LimitsSnapshotTest görüntüleri | Ayrı bir test yazılmadı. |
| LO-23 | FIXED | 900×1200'de iki sayfa da dar BOTTOM. | `aTallNarrowWindowDocksBothInspectorsUnderTheTable` | — |
| LO-25 | FIXED | Sheet'te başlık çubuğu yok. Kapat düğmesi adın satırında ve ilk odak durağı. | `theSheetHasNoBarAndItsCloseButtonIsItsFirstStop` | — |

## Sahiplik dışında değişen dosyalar

- **`Breakpoints.java`.** Kabuk sabitleri (`NARROW_BELOW`, `WIDE_FROM`, `ULTRAWIDE_FROM`, `LOW_BELOW`) aynı kaldı. Değişen yalnız inceleyici sabitleri ve `inspectorFor`'un anlamı (artık sayfa boyutu alıyor).
- **Kabuk test paketindeki dosyalar.**
  - `BreakpointAgreementSceneTest`: beklenen mod sayfa boyutundan okunuyor; uzun ve dar pencere testi eklendi.
  - `ProcessesSnapshotTest`: eylem aramasında sınıf adı güncellendi.
  - Yeni testler: `InspectorRealSizeSnapshotTest` ve `LimitEditorFitSceneTest`.
- **`Shell.java`'ya dokunulmadı.** Ancak Shell, gezinme düğmesini `getCenter().getBoundsInParent()` ile konumluyor. Bu sınırlar, henüz yerleşmemiş bir düğmenin odak halkası payını da içerebiliyor. Bu yüzden sayfa bu düğmeyi gösterdiği anda başlık satırını hemen yerleştiriyor. Kabuk ajanı `getLayoutY` kullanmayı düşünebilir.
