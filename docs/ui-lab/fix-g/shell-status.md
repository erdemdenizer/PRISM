# Düzeltme turu G — kabuk (shell) durumu

Dal `lab/g-shell`, taban `5565a77`. Kapsam: SH-01 … SH-14, sahip kararları K1 ve K6.
Bütün boyutlar **sahne** boyutudur (K8); `-real` adlı görüntülerde dış pencere ≈14 px geniş, ≈45 px yüksektir.

## Ölçülen sayılar (K1)

| Ölçü | Değer | Nasıl |
|---|---|---|
| En geniş ad | "Performance", Plex SemiBold 13 px: 78,1 px (Regular 75,4) | `TextWidths.width`, geçici ölçüm testi |
| Kısayol metni | "Ctrl+7", Plex 12 px: 33,7 px | aynı |
| Adlı satır | 10 + simge 20 + 12 + ad 78 + 12 + kısayol 34 + 10 = 176 px → satır 184 px | |
| Adlı kenar çubuğu | 200 px (çizgi dahil; 8 px yan boşluk) = `Breakpoints.SIDEBAR_WIDTH` | `ShellNavigationLayoutSceneTest` |
| Ray | 60 px = `Breakpoints.RAIL_WIDTH` | aynı |
| Adlı gezinme eşiği | `LABELLED_NAV_FROM = SIDE_FROM + (200 − 60) = 1460` | aşağıdaki hesap |

Hesap: yan inceleyici için sayfa alanının en az 866 (tablo) + 12 (ayraç) + 320 (inceleyici) + 48 (kenar) = 1246 px olması gerekiyor. 1320'de rayla sayfa alanı 1260 px. Adlı çubuk 140 px daha geniş, bu yüzden aynı alan 1460'ta kalıyor. Eşik inceleyici sabitinden türetildi; proclimits `SIDE_FROM`'u değiştirirse eşik onunla birlikte kayar.

Gerçek boyutlarda sonuç (sahne → gezinme / inceleyici):

| Dış pencere | Sahne | Gezinme | İnceleyici |
|---|---|---|---|
| 720×480 | 706×435 | çekmece | sayfa |
| 760×560 | 746×515 | çekmece | sayfa |
| 1100×700 | 1086×655 | ray | sayfa (1086 < 1100) |
| 1177×784 | 1163×739 | ray | alt |
| 1366×768 | 1352×723 | ray | yan |
| 1440×900 | 1426×855 | ray | yan |
| 1600×500 | 1586×455 | adlı çubuk | yan |
| 1920×1032 | 1906×987 | adlı çubuk | yan |

1440×900 rayda kalıyor: orada adlı çubuk inceleyiciyi yandan alta düşürürdü (1426 − 200 = 1226 < 1246). Biçim değişirken düğüm kurulmuyor; yalnızca sözde sınıf ve içerik gösterimi değişiyor (`crossingTheLineOfTheNamesBuildsNothing`). Geçiş anlık, hareket yok; azaltılmış hareket tercihi bu yüzden etkilenmiyor.

## Bulgular

| ID | Durum | Ne değişti (dosya) | Nasıl doğrulandı | Kalan sınır |
|---|---|---|---|---|
| SH-01 | FIXED | `RailName.java` (yeni): kurulmamış tek ipucu, rayın sağ kenarı + 8 px, girdiye ortalı, `autoFix` kapalı, `CONTENT_TOP_LEFT`, gölgesiz. `Sidebar.java`: `setTooltip` kalktı. `app.css`: bütün ipuçlarından gölge kaldırıldı, `.tooltip.nav-name` | `RailNameSceneTest.theNameNeverCoversTheEntryUnderThePointerNorTheRail` (her girdi, 25 ad boyutu: ad rayla ve girdiyle hiç kesişmez), `theNameHasNoShadowIsPlacedByItsContentAndNoEntryHasATooltipOfItsOwn`; görüntü `shell-nav-real-*-1086x655.png` (yerine konan temsilî etiket) | Popup harness'ta açılamıyor: konum hesapla ve CSS ile sınandı. Gerçek pencerede duran imleçle kare dizisi koordinatörde (aşağıda). |
| SH-02 | FIXED | K1: `Sidebar.Mode` (RAIL / SIDEBAR / DRAWER), `shell.css` `:labelled`, `Shell.adapt`, `Breakpoints` (RAIL_WIDTH, SIDEBAR_WIDTH, LABELLED_NAV_FROM) | `ShellNavigationLayoutSceneTest` (12 genişlik, adlar kesilmiyor, düğüm sayısı), `BreakpointAgreementSceneTest` (+6 gerçek/eşik boyutu), `ProcessTableWidthTest`; `shell-nav-real-*-1586x455.png`, `-1906x987.png` | Adlı çubuk 1426 px sahnede (1440×900) yok; bilinçli, inceleyici için. |
| SH-03 | FIXED | `Sidebar`: ad yalnızca Tab/ok/Home/End ile gelinen odakta; her başka tuş (Ctrl+N dahil) ve fare basışı bayrağı siler. Fare ve klavye tek `RailName` nesnesini paylaşıyor | `RailNameSceneTest.aShortcutShowsNoNameAnArrowKeyDoesAndThereIsOnlyEverOne`, `KeyboardRoutesSceneTest` (A-M7 testleri değişmeden geçiyor) | — |
| SH-04 | FIXED | `Sidebar.EntryGraphic`: grafik simge boyutunda (`USE_PREF_SIZE`), nokta simgenin sağ üst köşesinde | `ShellNavigationLayoutSceneTest.theBadgeSitsOnTheCornerOfTheIconAndTakesTheToneOfTheState` (ray ve adlı çubuk); görüntüler | — |
| SH-05 | FIXED | `app.css`: `-srm-nav-hover`, `-srm-nav-selected` (açık #dfe2e8 / #d9def6; koyu değişmedi); `shell.css` bunları kullanıyor; adlı modda seçili ad SemiBold | `theSelectionIsToldAtThreeToOneAndTheHoverCanBeSeen` (iki tema; çubuk kromda 4,85 / 5,96, çekmecede 5,54 / 4,78); `lab-docs/CONTRAST.md` yeniden üretildi | Zemin farkları (1,14–1,17 açık) 3:1 gerektirmiyor; seçimi 3:1 ile çubuk taşıyor. |
| SH-06 | PARTIAL | Koordinatör (birleştirme sonrası, `094b8cb`): açık sayfanın karartması üzerinde tekerlek artık alttaki sayfayı kaydırmıyor (`SheetOverlay`, `ScrollEvent` tüketiliyor). | `ProcessInspectorSceneTest.aWheelTurnOnTheScrimDoesNotScrollThePage` | Karartma hâlâ araç çubuğunu ve rayı örtmüyor; dışarıdaki ilk tıklama sayfayı kapatıp yutuluyor (bilinçli: yarı modal sayfanın kapanma tıklaması). Karartmayı kabuk düzeyine taşımak kabuk mimarisini değiştirir; bu turda yapılmadı. |
| SH-07 | FIXED | `ShellNavSnapshotTest` (yeni, 18 görüntü), `OverviewSnapshotTest` odak görüntüsü 1100×700'e alındı | `shell-nav-real-{dark,light}-{1086x655,1352x723,1426x855,1586x455,1906x987}.png`, `shell-drawer-real-{dark,light}-{706x435,746x515}.png`, `shell-hc-{night-sky,desert}-real-dark-{1086x655,1586x455}.png`, `overview-focus-{dark,light}-1100x700.png`; tam boyut kırpılarak incelendi | Hover ve odak sözde sınıfla verildi; ray adı popup olduğu için temsilî etiketle çizildi. |
| SH-08 | FIXED | `hc.css`: seçili girdi highlight zemin + highlighttext simge/ad; hover hotlight halkası; odak highlight halkası | `shell-hc-night-sky-*`, `shell-hc-desert-*` görüntüleri | Gerçek bir Windows yüksek karşıtlık oturumunda denenmedi; Desert renkleri yaklaşık. |
| SH-09 | PARTIAL | İpucu dolgusu 5/9 → 4/8 (`app.css`); çekmece dolgusu 14/12 → 8 (`shell.css`); `DESIGN_DECISIONS_TR.md` başlık boyutları düzeltildi (22 / alçak 19) | belge, görüntüler | Sayfa genelindeki yazı ölçeği (9 boyut) ve 19 px alçak başlık bütün sayfaları etkiliyor; tek alanda güvenle değiştirilemez. Ray dolgusu 10 bilinçli: 40 px girdiyi 60 px'te ortalıyor. |
| SH-10 | PARTIAL | Rozet rengi duruma bağlı: Acil → `-srm-error` (`:badged-error`), çekmecede halka yükseltilmiş yüzey rengiyle | `theBadgeSitsOnTheCornerOfTheIconAndTakesTheToneOfTheState`; `shell-drawer-real-*` | Hamburger düğmesine nokta eklenmedi: aynı durumun ikinci gösterimi, sahibin kararı gerekiyor (denetim de bunu söylüyor). |
| SH-11 | FIXED | `Shell`: çekmece 120 ms ease-in ile kayarak kapanıyor, azaltılmış harekette anında; kapanırken yeniden açılırsa olduğu yerden döner; geniş pencereye geçişte anında biter | `ShellDrawerSceneTest.withMotionTheDrawerSlidesAwayAndCanBeOpenedAgainOnItsWay` | Çekmecede Tab hâlâ sessizce tutuluyor (liste tek durak; bilinçli). |
| SH-12 | FIXED | `Shortcuts.java` (yeni): metin `KeyCodeCombination(..., SHORTCUT_DOWN).getDisplayText()`; ad ve kısayol ayrı etiketler (ray adı, menü düğmesi ipucu, adlı satır, erişilebilir yardım) | `ShortcutsSceneTest.theKeysAreWrittenTheSameWayEverywhere` | `SheetOverlay` kapatma ipucu "Close    Esc" proclimits'in dosyasında. Kısayolların ürün tabanında olmaması: K6 ile sahip kabul etti. |
| SH-13 | FIXED | `shell.css`: halka 2 px, 2 px boşlukla (`-fx-border-insets: -4`, yarıçap 12 / adlı satırda 10); girdiler arası 6 px, halka komşuya değmiyor | `shell-nav-real-*` kırpıntıları | — |
| SH-14 | FIXED | Koordinatör (`3123d66`): Processes başlığındaki "Details" düğmesi `quiet` sınıfını bıraktı, `process-details-open` ile ikincil düğme anatomisinde (kenar + zemin) çiziliyor. Limits'teki "Details and actions" zaten düğmeydi. | Gerçek pencere 760×560 ve 720×480 (%125) görüntüleri `20-760x560-processes`, `70-processes-sheet-closed-760x560` | Simge eklenmedi. |

## K6 — kısayollar

| Durum | Davranış | Test |
|---|---|---|
| Ctrl+1…7, Ctrl+B | Yalnızca kısayol tuşu tek başına: Alt (AltGr) ya da Shift varsa çalışmaz | `onlyTheShortcutKeyAloneWithADigitChoosesADestination`, `controlAltThreeChoosesNothing` |
| Metin alanında | Hiçbir metin alanı bu tuşları kullanmıyor; her yerdeki gibi çalışır, metin değişmez | `inATextFieldTheKeysDoWhatTheyDoAnywhereAndLeaveTheTextAlone` |
| Modal iletişim kutusu açıkken | Kutu ayrı bir pencere; tuşlar kabuğa ulaşmaz, etkisi yok | kod incelemesi (harness'ta pencere yok) |
| Sayfa (sheet) açıkken | A-M3 korunuyor: Ctrl+N sayfayı değiştirir ve sheet kapanır; Ctrl+B çekmeceyi açar | `theShortcutsOfTheWindowCloseAnOpenSheet` |
| Çekmece açıkken | Ctrl+N bir seçimdir: sayfa değişir, çekmece kapanır | `controlSevenWhileTheDrawerIsOpenChoosesSettingsAndClosesIt` |
| Geniş pencerede Ctrl+B | Etkisiz | `controlBDoesNothingInAWideWindow` |
| Gösterim | Rayda ad etiketinde, adlı çubukta fare ya da klavye odağı satırdayken sağ uçta soluk; erişilebilir yardım | `ShortcutsSceneTest` |

## Kendi alanı dışında değişen dosyalar

- `app.css`: yalnızca ipucu bölümü (gölge kaldırıldı, dolgu, `.nav-name`) ve iki gezinme jetonu (açık/koyu kök bloklarında).
- `hc.css`: iki jeton eşlemesi ve gezinme kuralları.
- `Breakpoints.java`: yalnızca gezinme sabitleri.
- `BreakpointAgreementSceneTest`: 6 CSV satırı eklendi (proclimits de bu dosyaya dokunabilir).
- `OverviewSnapshotTest`: odak görüntüsünün boyutu.
- `lab-docs/CONTRAST.md`, `lab-docs/contrast/tokens.py`, `lab-docs/DESIGN_DECISIONS_TR.md`.

## Koordinatörün gerçek pencerede doğrulaması gerekenler

1. 1100×700 (ray): imleç bir girdinin ortasında 2 s kıpırdamadan dururken kare özeti değişmemeli; ad rayın sağında, girdiye ortalı durmalı.
2. Girdiden girdiye yavaş geçiş: ad beklemeden yeni girdiye geçmeli, çift ad olmamalı.
3. Tablo odaktayken Ctrl+3: ray adı açılmamalı; ardından ↓ ile ad görünmeli.
4. 1600×900 ve 1920×1032: adlı çubuk, hover'da sağda soluk "Ctrl+N"; 1440×900 ve 1366×768 rayda kalmalı.
5. Pencereyi 1459 ↔ 1460 sahne sınırında sürükleyerek boyutlandırma: titreme ya da gecikme olmamalı.
6. Dar pencerede çekmece kapanış hareketi (120 ms) ve azaltılmış harekette anında kapanma.
7. %125 / %150 ölçekte ad etiketinin rayla çakışmaması.
8. Gerçek yüksek karşıtlık teması (Gece gökyüzü, Çöl).

## Doğrulama komutları

- `./mvnw -o -Pui-snapshots test` (bütün ui-snapshot testleri, değişikliklerden sonra): 1459 test, 0 hata, BUILD SUCCESS. Son CSS ayarlarından sonra kabuk testleri yeniden: 55 test, 0 hata.
- `./mvnw -o clean verify` (tam kapı, makinede başka ajanların derlemeleri de çalışırken):
  - 1. koşu: 2398 test, 3 hata; hepsi UI dışı ve yüke duyarlı (`CpuLimitIntegrationTest` "the machine was too busy", `OomSignalValidationTest` bellek ölçümü, `ShutdownRealProcessTest`). Tek başına yeniden koşuda son ikisi geçti; `CpuLimitIntegrationTest` yine "makine çok meşgul" dedi (diğer süreçler %60–90 CPU).
  - 3. koşu (`CpuLimitIntegrationTest` hariç): 2390 test, 1 hata: `InstanceLockTest` geçici klasörü silinemedi (Windows dosya kilidi); tek başına yeniden koşuda 14/14 geçti.
  - `./mvnw -o verify -DskipTests`: Spotless temiz, SpotBugs 0 bulgu, BUILD SUCCESS.
  - Yani tek bir koşuda baştan sona yeşil kapı alınamadı; kalan hatalar `domain/application/platform` testlerinde ve bu dalda o katmanlara dokunulmadı. Makine boşken koordinatör yeniden koşmalı.
- Görüntüler tam boyut kırpılarak incelendi; kopyaları `.wt\g-shell-snapshots\` (laboratuvar kopyasının içinde, git dışında) (clean verify `target/`'ı sildiği için).
