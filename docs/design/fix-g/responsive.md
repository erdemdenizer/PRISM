# Responsive doğrulama: gerçek Windows dış boyutu ve sahne boyutu

Önceki turların responsive PASS raporları kullanılmadı. Bu tablo, 18f9632 imajı için bu turda gerçek pencerede ölçülen değerlerdir.

## Nasıl ölçüldü

- **Dış boyut:** `SetWindowPos` ile verildi, Windows'un görünmez yeniden boyutlandırma kenarları dahil. Değer mantıksal boyut × ölçek.
- **Sahne:** pencerenin istemci alanı (`GetClientRect`) ölçeğe bölündü. Bu, JavaFX sahnesinin aldığı alandır.
- **Ortam:** DISPLAY1, DPI 120 (%125), Windows 11. Ölçüm betiği `kanit/betikler/run-scene.ps1`, kayıt `kanit/gercek-pencere/sahne-boyutlari.txt`.

## Sahne boyutları

| Dış boyut (mantıksal) | Pencere (px) | Görünür çerçeve (px) | İstemci (px) | Sahne (mantıksal) |
|---|---|---|---|---|
| 720×480 | 900×600 | 884×592 | 882×553 | 705,6×442,4 |
| 760×560 | 950×700 | 934×692 | 932×653 | 745,6×522,4 |
| 1100×700 | 1375×875 | 1359×867 | 1357×828 | 1085,6×662,4 |
| 1177×784 | 1471×980 | 1455×972 | 1453×933 | 1162,4×746,4 |
| 1366×768 | 1708×960 | 1692×952 | 1690×913 | 1352×730,4 |
| 1440×900 | 1800×1125 | 1784×1117 | 1782×1078 | 1425,6×862,4 |
| 1600×500 | 2000×625 | 1984×617 | 1982×578 | 1585,6×462,4 |
| 1920×1032 | 2400×1290 | 2384×1282 | 2382×1243 | 1905,6×994,4 |

Farklar:
- **%125:** sahne, dış boyuttan 14,4 mantıksal piksel dar ve 37,6 mantıksal piksel alçak.
- **%100:** yüksekliğin düzeltme turu brief'indeki gibi 45 piksel olduğu bu turda ölçülemedi; %100 monitör yoktu (BLOCKED).
- **Snapshot testleri:** brief'teki `-real` sahne boyutları (706×435, 746×515, …) %100 tahminine dayanıyor. %125'te sahne 7 piksel daha uzun. Genişlikler aynı, bu yüzden düzen modu aynı kalıyor; ekran dışı testler bu farkla geçerli.

## Kırılma noktaları ve gerçek sonuç (%125, 18f9632)

Düzen kuralları (`ui/Breakpoints.java`):
- **Gezinme:**
  - sahne < 820: çekmece;
  - 820–1459: ikon rayı;
  - ≥ 1460: adlı kenar çubuğu.
- **İnceleyici (sayfa boyutu):**
  - genişlik ≥ 1260: yanda;
  - genişlik ≥ 960 ve yükseklik ≥ 500: altta (ya da yükseklik ≥ 800 ve genişlik ≥ 640);
  - diğer durumlar: sayfa (sheet).

| Dış boyut | Sahne | Gezinme | Processes / Limits inceleyicisi | Görüntü | Sonuç |
|---|---|---|---|---|---|
| 720×480 | 706×442 | çekmece | sayfa + seçim satırı | `20-720x480-*`, `77-settings-720x480` | PASS |
| 760×560 | 746×522 | çekmece | sayfa + seçim satırı | `20-760x560-*`, `70…75` | PASS |
| 1100×700 | 1086×662 | ray | altta | `20-1100x700-*` | PASS |
| 1177×784 | 1162×746 | ray | altta | `20-1177x784-*`, `76-limits-1177x784` | PASS |
| 1366×768 | 1352×730 | ray | yanda | `20-1366x768-*` | PASS (uzun sonuç cümlesi "…" ile kesik, tamamı ipucunda) |
| 1440×900 | 1426×862 | ray | yanda | `20-1440x900-*` | PASS |
| 1600×500 | 1586×462 | adlı çubuk | yanda; eylemler kaydırmayla | `20-1600x500-*` | PARTIAL (PR-03, LO-06) |
| 1920×1032 | 1906×994 | adlı çubuk | yanda | `20-1920x1032-*` | PASS |

- **Aynı matris %125'te DISPLAY2'de** (iş alanı 1920×1020): 720×480 ile 1366×768 arası PASS (`80-*-125`, `81-dialog-*-125`, `82-rail-rest-125-*`). 1440×900 ve 1600×500 bu iş alanına sığmadığı için NOT TESTED.
- **Zorlanmış %100** (`-Dglass.win.uiScale=1.0`, aynı monitör): 86 görüntü, `kanit/gercek-pencere/ara-f4931df-zorlanmis-100/`.
  - Bu geçiş, son tembel sayfa değişikliğinden önceki f4931df imajıyla yapıldı.
  - Gerçek bir %100 monitör değildir; Windows başlık çubuğu %125'te kalır.
- **%150 ve %200:** NOT TESTED.
