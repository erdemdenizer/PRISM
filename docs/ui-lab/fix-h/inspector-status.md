# Düzeltme turu H: inceleyici (inspector) durumu

Alan: Processes ve Limits inceleyicisi, `ui/processes`, `ui/limits`, `ui/control`, tablo CSS'i.
Dal: `lab/h-inspector`, temel `8824ada`.

Doğrulama:

- Odaklı testler: `./mvnw -o -Pui-snapshots test -Dtest=...`
- Tam kapı: `./mvnw -o clean verify` ve `./mvnw -o -Pui-snapshots test`
- Görüntüler `target/ui-snapshots/` altında. Tam boyutta, PIL kırpmalarıyla incelendi.

Gerçek pencere, gerçek fare ve paketlenmiş uygulama bu turda denenmedi; brief bunu yasaklıyor. Aşağıdaki "doğrulandı" ifadeleri ekran dışı sahne testleri ve görüntüler içindir. Kesirli ölçek (%125, %150) gösterilmeyen bir `Stage` üzerinde render ölçeği verilerek denendi; gerçek DPI penceresi değil.

## Sayılar

| Durum | Sayı | Bulgular |
|---|---|---|
| FIXED | 7 | PR-03, LO-06, RZ, PR-13, PR-20, KB-1, PR-07 |
| PARTIAL | 6 | PR-14, PR-17, PR-15, PR-11, SH-06, LS-15 |
| OPEN | 5 | OP-15, PR-16, PR-18, PR-19, PR-H1 (bu turda bulunan) |
| NOT VERIFIED | 0 | — |

## Bulgular

| ID | Durum | Karar | Ne değişti (dosya) | Nasıl doğrulandı | Kalan sınır |
|---|---|---|---|---|---|
| PR-03 | FIXED | Hemen düzelt. Gerçek kullanımı etkiliyor: alçak pencerede End process kaydırma ardında kalıyordu. Görsel değil, erişim sorunu. | `InspectorFrame`'e `Compactable` ve sıkışık sabit parça eklendi. Sabit parça yetmeyince Processes eylem çubuğu sıkışık düzene geçiyor. Bu düzende Control başlığı gizleniyor. Limits & protection, etiketli bir "Limits & protection" menü düğmesine iniyor. Durum satırı ve gerekçeler görünür kalıyor. En az ayrıntı payı içerik kadar (en çok 72 px). Dosyalar: `InspectorFrame`, `ProcessActionBar`, `LimitsSection`, `ProcessDetailsPane`, `ProcessesPage`. | Yeni test: `InspectorActionReachSceneTest`, 60 durum. 720×480, 900×1200, 1100×700, 1366×768 ve 1600×500 boyutları, %125 ve %100 sahne ölçüleriyle denendi. Her eylem sahnede, görünür alanda ve kırpılmamış. Örtüşme yok. Gerekçe ve sonuç satırı görünür. Testin 28 durumu `8824ada`'da başarısız oluyordu. Görüntüler: `processes-reach-*-real-*`. | Sıkışık düzende Manage, Set CPU limit… ve OOM eylemlerine menüden ikinci bir adımla ulaşılıyor. Pasif menü öğesinin gerekçesi menüde değil, menü düğmesinin yanındaki durum satırında. |
| LO-06 | FIXED | Hemen düzelt. Gerçek kullanımı etkiliyor. | Limits düzenleyicisi aynı çerçeveyle sıkışık düzene geçiyor: başlık solda bir sütunda, düğmeler aynı satırda, gerekçeler 4 px altta. Kısa etiketler var (Set…, Remove, Emulated…, Watch…); erişilebilir metin tam ad. Yan panel ve sheet 360'tan 400 px'e çıktı, üçüncü düğme alta kaymasın diye. Dosyalar: `LimitEditorPanel`, `LimitsPage`, `InspectorLayout` (`PANEL_WIDTH`), `SheetOverlay` (`WIDTH`), `limits.css`. | `InspectorActionReachSceneTest` (offers, result ve none durumları). `InspectorFrameSceneTest` iki yeni durum. Görüntüler: `limits-reach-*`. | Kısa etiketin tam adı ekranda değil; erişilebilir metinde ve başlık sütununda. |
| RZ (yeniden boyutlandırma maliyeti) | FIXED | Hemen düzelt. Gerçek kullanımı etkiliyor: pencere boyutu değişirken kasma oluyordu. | Mod değişiminde düğüm taşınmıyor. Processes'te yeni `ProcessDock` var: tablo, ayırıcı ve ayrıntı kalıcı çocuklar, mod yalnız yerleşimi değiştiriyor. `InspectorLayout` paneli gizleyip gösteriyor. `ProcessDetailsPane` düğümleri bir kez yerleştiriyor, sonra yalnız görünürlüğü ve ızgara konumunu değiştiriyor. Sheet içeriği yalnız açılışta alıyor. Tablo skin'i ve hücreleri yaşamaya devam ediyor. | Ekran dışı yoklama (`ResizeCostProbeH`, commit edilmedi). Temel ile benimki aynı zaman aralığında, sırayla, 3 tur × 7 ölçüm alındı. Ölçü, 40 adımda FX iş parçacığı CPU süresi (ms). Sonuçlar tablonun altında. Mevcut sahne testleri yeni yapıya güncellendi. | Bu ölçüm gerçek pencere değil. Gerçek boyutlandırma ölçümünü koordinatör boş makinede yapmalı. |
| PR-13 | FIXED | Hemen düzelt. Gerçek kullanımı etkiliyor (%125). Önceki `Math.floor` denemesi yanlış yerdeydi. | Gerçek neden bulundu. `fitColumns` sabit 2 + 12 px düşüyordu. JavaFX ise çerçeveyi ve kaydırma çubuğunu cihaz pikseline yaslıyor: %125'te çerçeve kenar başına 0,8 px, çubuk 12,8 px. Satır genişliği de yukarı yuvarlanıyor. Bu yüzden sütunlar 0,4–0,7 px fazla kalıyor ve yatay çubuk açılıyordu. Artık oda, yaslanmış iç boşluklar ve skin'deki dikey çubuğun yaslanmış genişliğiyle hesaplanıyor (`ProcessTable`). Aşağı yuvarlama yok. | `ProcessTableScaleTest`: %125'te 6 boyut ve %150'de 2 boyut. Değişiklikten önce 9 durumun 8'i başarısızdı. `ProcessTableWidthTest` (%100'de tam dolum) yeşil. | Gerçek %125 pencerede bakılmadı. Gösterilmeyen `Stage` ile render ölçeği kullanıldı. |
| PR-14 | PARTIAL | Hafif CSS düzeltmesi yapıldı. Ağır özel skin kapsam dışı (platform sınırı). | `table.css`: tablo çubuğunun başparmağı yalnız enine 3 px içeride. Önce 6×12 px'lik bir noktaydı, şimdi en az 6×18 px. Üzerine gelince 8 px. | `ProcessTableScaleTest.theThumbOfALongListShowsItsWholeLength` (1352×730, 705×442, 1585×462). Eski CSS ile üç durumun üçü de başarısız. | 18 px, ScrollBarSkin'in alt sınırı (çubuk genişliğinin 1,5 katı). Daha uzun başparmak için özel skin ya da daha geniş çubuk gerekir; yapılmadı. |
| PR-07 | FIXED | Bu turda kod değişmedi. G'de açık kalan "quiet Details" sınırı artık geçerli değil. | — | Details artık çerçeveli ikincil düğme. SHEET'te seçim satırı ad ve PID gösteriyor (`matrix-selected-*-720x480`). 900×1200 dar BOTTOM. Sheet eylemleri `InspectorActionReachSceneTest` ile doğrulandı. | Simge eklenmedi; metin etiketi yeterli görüldü. |
| PR-15 | PARTIAL | Kalan kısım bilinçli bir tasarım kararı gerektiriyor (UI_GUIDE "aynı satırlar" kuralı). | — | G'deki test yeşil. | İzleme durduğunda inceleyici tek satıra inmiyor. Sahip kararı gerekiyor. |
| PR-17 | PARTIAL | Hafif yol zaten var. Name sütununu sabitlemek JavaFX'te pahalı (L) ve kapsam dışı. | — | Süreç kimliği her modda görünür: SHEET'te seçim satırında, BOTTOM ve SIDE'da inceleyici başlığında. | Yatay kaydırmada Name sütunu kayıyor. Son sütun kenarda kesik görünebilir. |
| OP-15 | OPEN | Bilinçli denge. Ölçüldü, düzeltilmedi. | — | Processes'te ölçüldü: başlığın sağ kenarı birim metninin bittiği yerden I/O'da 4, CPU'da 3, Memory'de 2 px sağda. Neden şu: başlık, sabit genişlikli birim kutusunun kenarına hizalı; birim metni ise kutunun içinde sola yaslı, böylece rakamlar hizalı kalıyor. | Düzeltmek için ya birim türüne göre piksel ayarlı başlık dolgusu gerekir (yazı tipine bağlı bir yama), ya da başlığı rakamların kenarına hizalamak gerekir (tasarım kararı). DeviceTable, Performance alanında. |
| LS-15 | PARTIAL | Yorum düzeltildi. Ölçü kararı sahibin. | `table.css` yorumu artık gerçek 10 px dolguyu söylüyor. | Kod incelemesi. | 30 px satır ve 10 px dolgu, UI_GUIDE'daki 28 ve 8 ile çelişiyor. Bütün tabloları ve sütun ölçümünü etkileyen bir karar. |
| PR-11 | PARTIAL | Bu alandaki kısım G'de yapılmıştı; kalan kısım başka alanda. | — | Koyu görüntülerde (`matrix-selected-dark-1600x500`) bölüm çizgileri ve sıkışık düzen okunuyor. | Onay diyaloğu ayırıcısı OOM alanında. `CONTRAST.md` yeniden üretilmedi. |
| SH-06 | PARTIAL | Bilinçli tasarım (A-L6) ve kabuk alanı. Kapsam dışı. | — | `ProcessInspectorSceneTest.aPressOutsideTheSheetClosesItAndGoesNoFurther` ve `aWheelTurnOnTheScrimDoesNotScrollThePage` yeşil. | Karartma araç çubuğunu ve rayı örtmüyor. Dışarıdaki ilk tıklama sheet'i kapatıyor ve hedefine geçmiyor; bu A-L6 kararı. Karartmayı kabuk düzeyine taşımak `ui/shell` işi. |
| PR-16 | OPEN | Kod değişmedi. Stil, G onay diyaloğuyla zaten tutarlı. Belge ve sahip kararı gerekiyor. | — | İnceleyicide Force kill ikincil düğme, tehlike renginde metin, dolgusuz. Onayda tehlike metni var; geri alınamaz eylemde 1 px tehlike kenarı da var. | UI_GUIDE "renkle değil" diyor. DESIGN_DECISIONS §7'de bu sapmanın satırı yok. |
| PR-19 | OPEN | Bilinçli tasarım. | — | PID bir kimlik: gruplanmıyor, filtre girdisi ve Görev Yöneticisi gibi. Threads bir sayım: gruplanıyor. Bayt değerinde ondalık yok ("12 B/s"), çünkü kesirli bayt yok. Rakamların sağ kenarı yine aynı hizada. | Sahip başka bir biçim isterse `ValueFormatter` değişikliği gerekir. Bütün sayfaları etkiler. |
| PR-20 | FIXED | Hemen düzelt (S). | Sütun başlığında sağ tık artık yalnız sıralama grubunu açıyor. Satırda ve klavyede (Shift+F10, Menü tuşu) menü değişmedi; milestone 7 klavye yolu korunuyor. Dosyalar: `SortMenu`, `ProcessTable`. | `SortMenuSceneTest.onTheHeadersTheMenuIsTheSortGroupAlone`. | Ascending ve Descending kökte kaldı; mevcut klavye testleri bunu bekliyor. |
| PR-18 | OPEN | Düşük öncelik. Neden kısmen bulundu. | — | Görüntüde ölçüldü. 1920×1080'de başlık çizgisiyle ilk satır arasında 7 px, 900×1200'de 2 px var. Bu boşluk, bir önceki satırın alt kısmı. Liste kesirli bir konumda duruyor. | Olası neden: görüntü düzeneği seçimi ilk yerleşimden önce yapıyor, VirtualFlow konumu korunuyor. Gerçek kullanımda seçim, görünen bir satıra tıklamayla yapılıyor. Satır sınırına yuvarlamak özel VirtualFlow gerektirir; yapılmadı. |
| KB-1 | FIXED | Hemen düzelt (koordinatör). | `table.css`: boş tablo bir Tab durağıysa, yer tutucu alanı aynı 2 px odak halkasını çiziyor. Durak trafikten çıkarılmadı. | `LimitsPageSceneTest.anEmptyTableWithTheKeyboardFocusShowsTheFocusRing` (kenar rengi ve piksel). Eski `table.css` ile başarısız. | Pages ajanı aynı sorunu başka bir yolla çözdü (`TableFocus`, `:no-focused-row`, tablonun kenarı). İkisi birlikte birleşirse boş tabloda iki halka çıkar; birleştirmede tek yol seçilmeli. |
| PR-H1 (yeni) | OPEN | Bu turda bulundu. Sütunu genişletmek kabul edilmiş bir düzeni bozuyor; karar gerekiyor. | — | Tablo bu sütunlardan birine göre sıralanınca "Threads" ve "Priority" başlıkları "Thr…" ve "Pr…" diye kesiliyor; %100 dahil her ölçekte. Nedeni: sıralama okunun 12 + 4 px'i ve skin'in 3 px'lik sıralama bölgesi ölçüme dahil değil. | Bunlar için yer açmak tabloyu yaklaşık 44 px genişletiyor. Bu da 1320 px'te inceleyicinin yanında bütün sütunların sığmasını bozuyor (`ProcessTableWidthTest`). |

## Yeniden boyutlandırma ölçümü (RZ)

Ölçü: 40 adımda FX iş parçacığının CPU süresi (ms). Temel = `8824ada`, `git archive` kopyası. Benimki = perf commit'i `b568e3b`. İkisi aynı zaman aralığında sırayla ölçüldü: 3 tur × 7 ölçüm, n = 21. Makine yüklüydü (dört ajan aynı anda çalışıyordu); ikinci temel turu belirgin biçimde gürültülü.

| Yoklama | Temel medyan (IQR, min–maks) | Benimki medyan (IQR, min–maks) |
|---|---|---|
| Processes, eşik geçen boyutlar | 1688 (1524–1790, 1328–2281) | 406 (391–454, 359–594) |
| Limits, eşik geçen boyutlar | 469 (406–797, 359–953) | 219 (196–234, 141–453) |
| Processes, yalnız SIDE (eşik geçmeyen) | 484 (446–766, 375–1000) | 391 (352–454, 313–688) |
| Processes, aynı boyut | 203 (172–227, 141–281) | 125 (125–156, 109–172) |

JFR dökümü (temel): eşik geçişinde zamanın yaklaşık %43'ü taşınan alt ağaçların `setScenes` ve hücre CSS yeniden uygulamasında, %20'si `arrange`'da. Değişiklik bu iki kalemi hedefliyor.

## Koordinatör notu: `TextWidths` ve %125

`ProcessTableScaleTest.noHeaderAndNoWidestValueIsCutAtAFractionalScale` testi Processes tablosunu açılış sırasıyla 1.0, 1.25, 1.5 ve 1.75 ölçeklerinde denedi. Kesik başlık ya da değer bulmadı; 4 px ızgarası payı yetiyor.

`TextWidths.cell`'e 2 px'lik cihaz pikseli payı eklemek denendi ve geri alındı. Neden: Processes sütunlarını yaklaşık 20 px genişletiyor ve 1320 px yan yerleşimi bozuyor (`ProcessTableWidthTest`). Ayrıca bu değişiklik Logs, Settings ve OOM'u da etkiler; OOM'u pages ajanı ayrıca düzeltti.

Genel pay istenirse yalnız `TextWidths.column`'a (OOM, Logs, Settings) uygulanabilir.

## Sahibin alanı dışında değişen dosyalar

- `src/test/java/prism/srm/ui/shell/`:
  - `InspectorActionReachSceneTest` (yeni)
  - `InspectorRealSizeSnapshotTest` (yardımcılar)
  - `KeyboardRoutesSceneTest` (sıkışık sheet'te Tab yolu menüye göre güncellendi)
- `table.css`: KB-1 ve PR-14 kuralları, bütün tablolara uygulanıyor (OOM ve Logs dahil).

## Kapı

- `./mvnw -o clean verify`: Tests run: 2416, Failures: 0, Errors: 0, Skipped: 37, BUILD SUCCESS.
- `./mvnw -o -Pui-snapshots test`: Tests run: 2187, Failures: 0, Errors: 0, Skipped: 0, BUILD SUCCESS.
- Önceki iki koşu:
  - İlk koşu SpotBugs'ta durdu (EI_EXPOSE_REP2, `InspectorFrame.setCompactable`). Düzeltme commit `7cee35a`'da.
  - İkinci koşuda `CpuLimitIntegrationTest` bir kez "the machine was too busy to measure" diyerek düştü. O sırada dört ajan aynı anda derleme yapıyordu. Bu bir platform testi; bu alanın kodu değil. Üçüncü koşuda geçti.
