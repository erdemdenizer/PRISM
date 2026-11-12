# Duyarlı düzen ve DPI matrisi

Matris iki kaynaktan gelir. Hangi hücrenin hangi kaynaktan geldiği her satırda yazılıdır. Gerçek bir ekranın izin vermediği bir boyut gerçek pencerede denenmiş gibi gösterilmez; böyle hücreler **NOT POSSIBLE** ya da **NOT TESTED** olarak işaretlenir.

1. **Gerçek pencere:** laboratuvar app-image'i (`f4fc73b`) gerçek Windows 11 ekranlarında çalıştı. Görüntüler `lab-spikes/perf/matrix-run.ps1` ile alındı ve `lab-evidence/F-final/runtime/matrix/` klasöründedir.
   - Boyutlar pencerenin **dış** boyutudur, mantıksal piksel olarak verilir. İçerik (sahne) çerçeve kadar daha küçüktür: yaklaşık 14 px dar, 45 px kısa. Örneğin "1100×700" penceresinin içeriği 1086 px genişliğindedir ve 1100 px kırılımının altında kalır.
   - Her boyutta yedi sayfanın hepsi UI Automation ile açıldı ve tek tek kaydedildi.
2. **Ekran dışı:** `SizeMatrixSnapshotTest` ve sayfa testleri, sahneyi istenen boyutta, çerçevesiz olarak çizer. Çıktılar `target/ui-snapshots/matrix-*` dosyalarıdır; teslim ZIP'inde de vardır.

## Ekranlar

| Ekran | Fiziksel çözünürlük | Ölçek | Kullanılabilir alan |
|---|---|---|---|
| DISPLAY2 (birincil) | 1920×1080 | %100 (96 dpi) | 1920×1032 |
| DISPLAY1 | 1920×1080 | %125 (120 dpi) | 1920×1020 fiziksel, yani 1536×816 mantıksal |

## Boyut matrisi

| Boyut | Gerçek pencere, %100, koyu | Gerçek pencere, %100, açık | Gerçek pencere, %125 (DISPLAY1), koyu | Ekran dışı (koyu ve açık) | Düzen (içeriğe göre) |
|---|---|---|---|---|---|
| 720×480 | **PASS** (7 sayfa) | **PASS** | **PASS** (900×600 fiziksel) | **PASS** | Çekmece; inceleyici sayfa olarak |
| 800×600 | **PASS** | — | **PASS** (1000×750) | **PASS** | Çekmece; sayfa |
| 1100×700 | **PASS** | **PASS** | **PASS** (1375×875) | **PASS** | Gerçek pencerede ray ve sayfa (içerik 1086 px). Ekran dışında 1100'lük içerikle ray ve alt inceleyici |
| 1366×768 | **PASS** | **PASS** | **PASS** (1708×960) | **PASS** | Ray; yan inceleyici (≥1320) |
| 1440×900 | **PASS** | — | **NOT POSSIBLE**: 1800×1125 fiziksel, alana sığmaz | **PASS** | Ray; yan inceleyici |
| 1600×500 | **PASS** | — | — | **PASS** | Ray; yan inceleyici, içinde kaydırma; alçak ritim |
| 1920×1080 | **NOT POSSIBLE**: kullanılabilir alan 1920×1032 | **NOT POSSIBLE** | — | **PASS** | Ray; yan inceleyici |
| 2560×1440 | **NOT POSSIBLE**: ekran yok | — | — | **PASS** | Ultra geniş üst sınırlar |
| 900×1200 | **NOT POSSIBLE**: yükseklik sığmaz | — | — | **PASS** | Ray; ekran dışında 900 genişlikte inceleyici sayfa olarak |
| 3440×1440 | **NOT POSSIBLE**: ekran yok | — | — | **PASS** | Ultra geniş; Overview 2×2 kalır (bilinen açık) |

"PASS" şu anlama gelir: sayfa açıldı, görüntü alındı, hiçbir öğe üst üste binmedi ve sığmayan içerik kaydırmayla ulaşılabilir kaldı. 112 gerçek pencere görüntüsü dört temas sayfasında gözle incelendi.

Küçük boyutlarda görülen ve kusur sayılmayan davranışlar:

- **Dikey kaydırma.** 720×480 ve 800×600'de Settings, OOM Guard ve Performance sayfaları dikey kaydırma çubuğuyla gelir; alt bölümler kaydırınca görünür.
- **Üç noktayla kısaltma.** Dar genişlikte uzun metinler "…" ile kısalır: Logs mesaj sütunu, Overview'daki işletim sistemi adı ("Windows 11 (build 26…"), Settings'teki klasör yolları ve süreç adları. Pencere genişledikçe tam metin görünür (1366×768 ve üstündeki görüntüler).

**Yatay kaydırma.** 720 ve 800 genişlikte Processes tablosu yatay kaydırma çubuğuyla gelir. Sütunlar sabittir ve kesilmez. 900–1099 px'te de tablo yana kayar (`DESIGN_DECISIONS_TR.md` §8).

## DPI matrisi

| Ölçek | Yöntem | Boyutlar | Sonuç |
|---|---|---|---|
| %100 | Gerçek ekran (DISPLAY2) | Yukarıdaki tablo | **PASS** |
| %125 | Gerçek ekran (DISPLAY1), pencere ekrana taşındı ve DPI 120 olarak doğrulandı | 720×480, 800×600, 1100×700, 1366×768 | **PASS** |
| %150 | **Benzetim:** %100 ekranda `-Dglass.win.uiScale=1.5`; Windows'un ölçeği değil, JavaFX'in kendi ölçeği | 720×480, 800×600 | **PASS (benzetim)**. 1100×700 için 1650×1050 fiziksel gerekir; **NOT POSSIBLE** |
| %200 | **Benzetim:** `-Dglass.win.uiScale=2.0` | 720×480 | **PASS (benzetim)**. 800×600 için 1600×1200 gerekir; **NOT POSSIBLE** |
| %150 ve %200, gerçek Windows ölçeği | — | — | **NOT TESTED**: bu ölçekte bir ekran yok, sistem ölçeği de değiştirilmedi |

Benzetimde çerçeve ve başlık çubuğu Windows tarafından %100'de çizilir; yalnızca içerik ölçeklenir. Bu yüzden başlık çubuğunun %150 ve %200'deki görünümü ölçülmüş sayılmaz (`TITLEBAR_VALIDATION_TR.md`).

## Gerçek pencerede görülen ve düzeltilenler

- **Ray ad ipucu.** Klavyeyle ulaşılmadığı halde (pencere açılırken ya da UI Automation ile odak geldiğinde) görünüyordu ve pencere taşındığında yerinde kalıyordu. `f4fc73b` ile düzeltildi; ikinci geçişteki görüntülerde artık görünmüyor.
- **Ölçüm aracının kendi hataları.** Pencere başlığının boş okunması, aynı adı taşıyan bir etiketin düğme sanılması ve tema seçeneklerinin "Appearance: …" adıyla görünmesi. Hepsi düzeltildi; ilk geçiş `runtime/matrix-first-pass/` klasöründe saklandı.
