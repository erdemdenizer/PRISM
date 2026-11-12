# Fix round H: görsel cila (polish) durumu

Dal `lab/h-polish`, taban `6955ae0` (inspector, pages ve titlebar birleşmiş). Bu bir yeniden tasarım değil: var olan tasarım sistemini inceltir. Yeni bağımlılık, yeni stil, gradyan, bulanıklık, gölge eklenmedi.

## Özet

| Durum | Sayı | Bulgular |
|---|---|---|
| FIXED | 4 | PR-H1, SH-09 (yazı ölçeği, bu alandaki kısım), OP-14 (`.panel`), PR-16 (belgelendi) |
| PARTIAL | 3 | PR-11, LS-15, OP-14 (shell.css kısmı öneri) |
| OPEN | 2 | OP-15 (bilinçli denge), PR-18 (görüntü düzeneği) |

## Bulgular

| ID | Durum | Karar | Ne değişti (dosya) | Nasıl doğrulandı | Kalan sınır |
|---|---|---|---|---|---|
| PR-H1 | FIXED | Gerçek kullanımı etkiliyor (sahibin ölçütü: tabloda kesilme kalmasın). Tablo genişlemeden çözüldü. | `ProcessTable`: her sütuna boş bir sıralama düğümü; skin'in 3 px'lik sıralama ızgarası artık yer almıyor. `table.css`: sıralanan başlık, ok tarafındaki 10 px dolgusunu oka veriyor; metin hücrelerle aynı yerde kalıyor. `ColumnWidths`: ok 10 px, boşluk 2 px; ölçüye 2 px eklendi. | `ProcessTableScaleTest.theHeaderOfTheSortedColumnIsNotCutAtAnyScale` (yeni): dokuz sütun, iki yön, ölçek 1,0 / 1,25 / 1,5 / 1,75. Eski kodda 4/4 düşüyor (Threads, Priority), yenide geçiyor. `ProcessTableWidthTest` (1320 dahil) ve `ProcessTableScaleTest` (1366×768 %150 dahil) değişmeden yeşil. | 12 px ok ve 4 px boşlukla denendi: Priority 4 px genişliyor ve 1366×768 %150'de tablo yana kayıyordu. Bu yüzden ok 10 px oldu (DESIGN_DECISIONS §7). Yedek yazı tipiyle (Plex yüklenmezse) sığma ölçülmedi. |
| SH-09 | FIXED (bu alan) | Görsel; belge ile kod çelişiyordu. | Ölçek 9 boyuttan 6'ya: 12 / 13 / 15 / 18 / 22 / 30. `limits.css` durum satırı 14 → 13 px SemiBold (bölüm başlığı rolü). `app.css` rol yorumu ölçeği yazıyor. `DESIGN_DECISIONS_TR.md` §4 Tipografi düzeltildi (eski metin "alçakta 19 px" diyordu). | Kod incelemesi; `LimitEditorFitSceneTest` (28) yeşil; Limits görüntüleri. | `shell.css` alçak başlık 19 px: öneri 18 px (koordinatör). Alçak penceredeki seri açıklaması 11 px (`shell.css`, OP-04/OP-06 için ayarlı) ve grafik eksen yazıları 11 px (Canvas, `graph.css`) ölçek dışında, bilinçli bırakıldı. |
| OP-14 | PARTIAL | `.panel` bu alanda: düzeltildi. `shell.css` değerleri öneri. | `app.css` `.panel` 14 16 → 12 16. | `OverviewFitSceneTest` (20), `OomPageLayoutSceneTest` (32), `SettingsLayoutSceneTest` (18) yeşil. | `shell.css`: `.shell:low .panel` 10 14 → 8 12 ve alçak başlık 19 → 18 öneri (rapordaki kurallar). İkisi bu çalışma ağacında geçici olarak uygulanıp on sahne testiyle (268 test: Overview, OOM, Settings, Limits sığma, kırılım, eylem erişimi) yeşil koşuldu, sonra geri alındı; görüntüde incelenmedi. Denetim iç ölçüleri (düğme 14 yatay dolgu, menü 5, onay kutusu kutusu) değiştirilmedi. |
| PR-11 | PARTIAL | Koyu temada panel ve tablo kenarı "yok" gibi okunuyordu. | Yeni belirteç `-srm-edge` (açık `#e1e4e9`, değişmedi; koyu `#363a43`). `.panel`, tablo çerçevesi (`table.css`), sayfa (`control.css .sheet`, `processes.css .process-sheet`). `hc.css` eşlemesi eklendi. | Koyu tuvalde 1,28 → 1,47 (`CONTRAST.md`, `tokens.py` yeni satırlar). `HighContrastTest` yeşil. Görüntüler: `processes-result-real-1366x768-dark-1352x723`, `overview-live-real-dark-1352x723`. | Kenar 3:1 değil (dekoratif). Tablo başlığının alt çizgisi `-srm-hairline`'da kaldı (koyu yüzeyde 1,14). Grafik ızgarası `-srm-border`'da (grafik alanı, testleri `#2d3138` bekliyor). İnceleyici kenarı G'deki `derive` ile kaldı. |
| PR-16 | FIXED (belge) | Bilinçli tasarım; kırmızı kılavuz belirtecine bağlı. | Kod değişmedi: Force kill `-srm-error` (UI_GUIDE'ın hata belirteci). DESIGN_DECISIONS §7'ye gerekçeli satır. | Karşıtlık: açık 6,14 / koyu 5,48; üzerine gelince 5,82 / 4,88. | End task onayındaki görünüm G'de LO-15 ile zaten ikincil anatomiye döndü; dolu kırmızı yok. |
| OP-15 | OPEN | Bilinçli denge, değiştirilmedi. | — | Inspector turunda ölçüldü: başlığın sağ kenarı birim kutusunun kenarında, birim metni kutuda sola yaslı. | Düzeltmek, birim türüne göre piksel yaması ya da rakam hizasını bozmak demek. |
| LS-15 | PARTIAL | Belge düzeltildi; ölçüler bilinçli. | DESIGN_DECISIONS §7: tablo ölçüleri satırı (30/32/10/10). | Kod incelemesi. | 10 px dolguyu 8 ya da 12'ye çekmek her tablonun her sütununu ve sığma testlerini etkiler; yapılmadı. |
| PR-18 | OPEN | Düşük öncelik; büyük olasılıkla görüntü düzeneği. | — | — | Inspector durumundaki açıklama geçerli. |

## Düğmeler, simgeler, yüzeyler (ID'siz kapsam)

- **Düğmeler.** Dört tür zaten tutarlıydı (birincil, ikincil, sessiz, yıkıcı; tablo DESIGN_DECISIONS'ta). Değişen tek şey sessiz düğmenin üzerine gelme yüzeyi:
  - koyu yükseltilmiş yüzeyde 1,05:1'di, sayfanın kapatma düğmesinde görünmüyordu; şimdi `-srm-control-hover` (1,24);
  - sayfa başlığı bandında `-srm-nav-hover` (açıkta denetim rengi bantla 1,02:1'di).
- **Simgeler.** Sayfanın kapatma düğmesindeki X 14 → 16 px (ölçek: 10 sıralama, 12 ok ve temizle, 16 denetim, 20 ray). Yalnızca simgeli denetimlerin hepsinde ipucu ve erişilebilir ad vardı; eksik bulunmadı.
- **Hareket.** Değişmedi; `Motion.java` okunmadı ve değiştirilmedi.

## Gerçek pencerede bakılacaklar

1. Processes: Threads ve Priority'ye tıklayınca başlık tam ("Threads ˄", "Priority ˄"), %125 ve %150.
2. Sayfanın (dar pencere inceleyicisi) kapatma düğmesi ve katlanmış filtre: koyu ve açıkta üzerine gelme yüzeyi görünüyor mu.
3. Koyu tema: Overview panelleri ve tablo çerçevesi sessiz ama seçik mi; ağır mı.
4. Limits düzenleyicisindeki durum satırı (13 px SemiBold) bölüm başlıklarıyla uyumlu mu.

## Doğrulama (kapı)

- `clean verify`: 2421 test, 1 başarısız, 37 atlandı, BUILD FAILURE. Tek başarısız: `CpuLimitIntegrationTest.aBusyProcessIsHeldNearItsLimit…`, "the machine was too busy to measure the workload unlimited" (diğer süreçler %34-47 CPU; dört ajan aynı anda derliyor). Tek başına yeniden koşuldu: aynı neden, yine başarısız (diğerleri %26-53). Platform testi; bu daldaki değişiklikler (CSS, iki UI sınıfı, belgeler) ona dokunmuyor. Boş makinede koordinatör koşmalı.
- Testsiz `verify`: enforcer, Spotless ve SpotBugs (BugInstance 0) geçti, BUILD SUCCESS.
- `-Pui-snapshots test`: 2314 test, 0 başarısız, BUILD SUCCESS (başlangıçta 2310; +4 yeni ölçek testi).
- Ara bir koşuda (`verify`, CpuLimit hariç) `InstanceLockTest` geçici dizini silemedi (Windows dosya kilidi, `app` alanı); ilk `clean verify`'da geçmişti.
