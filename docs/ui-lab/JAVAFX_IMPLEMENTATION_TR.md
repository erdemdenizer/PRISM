# PRISM UI Lab: JavaFX uygulama notları

Bu belge, premium UI v2'nin JavaFX 25 ile nasıl kurulduğunu ve nelerin bilerek yapılmadığını anlatır. Platform sabittir: JDK 25.0.4.1, JavaFX 25.0.4, OSHI 7.7.0 (JNA), JNA 5.19.1. Laboratuvarda hiçbir bağımlılık eklenmedi ve hiçbir sürüm değiştirilmedi.

## 1. Katmanlar

`ArchitectureTest` değiştirilmedi ve laboratuvar kodu bu testi geçiyor. Katmanların yeni parçaları:

| Paket | Yeni ya da değişen | Görev |
|---|---|---|
| `platform` | `WindowChrome` (arayüz) | Pencere çerçevesinin renklerini uygular ya da sıfırlar; sonuç bir `Result` olarak döner |
| `platform.windows` | `WindowsWindowChrome` | DWM öznitelikleri, `VerifyVersionInfoW` ile build kapısı, `EnumThreadWindows` ile pencere arama |
| `app` | `TitleBarTint` | Tema ya da yüksek karşıtlık değiştiğinde başlığı yeniden renklendirir; sınırlı sayıda yeniden dener |
| `ui` | `Breakpoints`, `Motion`, `HighContrast`, `Stylesheets` | Kırılım noktaları, hareket süreleri, yüksek karşıtlık eşlemesi, stil sayfaları |
| `ui.control` | `SheetOverlay`, `DisabledReasons`, `PageHeader` | Ortak sayfa, pasif kontrol nedenleri, sabit araç çubuğu satırı |
| `ui.icon` | `Icon`, `IconView` | Çizgi kalınlığı ekran pikselinde sabit tutulan vektör simgeler |
| `ui.shell` | `Shell`, `Sidebar` | Ray, çekmece, sabit araç çubuğu, kısayollar |

## 2. Başlık çubuğu (Windows 11)

- `TitleBarTint` pencere gösterildiğinde (`onShown`) çalışır. Native başlık o anda henüz boştur, bu yüzden pencere bulunamazsa önce `runLater` ile 5 kez hızlı deneme yapar, ardından 50 ms aralıklı `PauseTransition` ile toplam 20 denemeye kadar devam eder.
- Pencere, şu üç koşulun hepsiyle bulunur ve tam olarak bir eşleşme istenir:
  - `EnumThreadWindows(GetCurrentThreadId())` ile o iş parçacığının pencereleri taranır,
  - sınıf öneki `GlassWndClass-GlassWindowClass-` olmalıdır,
  - başlık tam olarak eşleşmelidir.

  Birden fazla eşleşme `AMBIGUOUS`, hiç eşleşme olmaması `NOT_FOUND` sonucunu verir.
- Uygulanan DWM öznitelikleri: 20 (koyu mod), 35 (başlık rengi), 36 (başlık metni rengi), 34 (kenarlık rengi). COLORREF biçimi 0x00BBGGRR'dir. Sıfırlamada öznitelik 20 için 0, 35/36/34 için `DWMWA_COLOR_DEFAULT` (0xFFFFFFFF) yazılır.
- Build ≥ 22000 kapısı: `VerifyVersionInfoW`, uygulamanın manifestinde Windows 10/11 için `supportedOS` GUID'i (8e0f7a12…) olduğunda gerçek build numarasını görür. Hem jpackage başlatıcısında hem `java.exe`'de bu GUID ölçüldü.
- **İş parçacığı istisnası:** bu native çağrılar JavaFX iş parçacığında yapılır, çünkü pencerenin sahibi o iş parçacığıdır. Başka bir iş parçacığından yapılan `GetWindowText`, `WM_GETTEXT` mesajı göndererek FX iş parçacığını bekler. Bu istisna için ADR-A önerildi (`PRODUCTION_MIGRATION_PLAN_TR.md`).
- Native bağlamadan gelen her `RuntimeException`, istisna olarak yayılmak yerine `FAILED` sonucuna çevrilir. `Error` yakalanmaz.
- **Yüksek karşıtlık:** `Windows.SPI.HighContrast` platform tercihi dinlenir. Açık olduğunda başlık sistem renklerine bırakılır.

## 3. Stil sayfaları ve tema

- `app.css` belirteçleri tanımlar. Koyu tema, `Scene.Preferences` ile verilen renk şeması ve bir medya sorgusuyla seçilir. Sayfaya özgü stiller ayrı dosyalardadır: `shell`, `table`, `processes`, `limits`, `oom`, `metric`, `overview`, `performance`, `logs`, `settings`, `graph`, `control`.
- `hc.css` ve `HighContrast`: yüksek karşıtlık açıkken sahnenin köküne bir stil sınıfı eklenir ve belirteçler `Windows.SysColor.*` tercihlerine eşlenir (WINDOW, WINDOWTEXT, HIGHLIGHT, HIGHLIGHTTEXT, GRAYTEXT, BTNFACE, HOTLIGHT). Sınırlar 1 px WINDOWTEXT çizgisiyle çizilir; karartma katmanları opaktır. Eşleme bir birim testiyle doğrulanır. Gerçek bir yüksek karşıtlık oturumunda denenmedi.
- **Açılır pencereler** (menü, ipucu, iletişim penceresi) sahnenin renk şemasını devralmaz. Bu yüzden stil sayfaları her pencereye ayrıca uygulanır (`Stylesheets`).
- **Yazı tipi:** IBM Plex Sans gömülüdür. JavaFX ağırlıkları ayrı aileler olarak kaydeder ("IBM Plex Sans Medm", "IBM Plex Sans SmBld"); bu yüzden roller yüz adını doğrudan kullanır. `-fx-font-weight` bu ağırlıkları seçmez.

## 4. Yerleşim

- **Sabit araç çubuğu:** kabuk, her sayfanın başlık satırını (`PageHeader`) kaydırılan alanın üstündeki banda taşır. Sayfanın stil sayfaları başlık satırına da uygulanır.
- **İnceleyici:** konumu `Breakpoints.inspectorFor(width, height)` belirler; sonuç `SIDE`, `BOTTOM` ya da `SHEET` olur. Processes yan ve alt yerleşimler için `SplitPane` kullanır. Sayfa her iki ekranda da ortak `SheetOverlay` ile açılır.
- **Boyut dinleyicileri** sahneyle birlikte taşınır. Yalnızca bir `InvalidationListener` vardır; eski sahneden kaldırılır, yeni sahneye eklenir. Bu, mimari incelemedeki sızıntının düzeltmesidir.
- **Tablo:** satır yüksekliği `fixedCellSize` ile verilir; alçak modda 28 px'e düşer. Sütun genişlikleri bir kez ölçülür, sabit sütunlar kesilmez. Name sütunu 160 ile 480 px arasındadır.
- **Grafikler** Canvas üzerine çizilir; her örnek için düğüm yoktur. Geçmiş tamponları sınırlıdır.

## 5. Hareket

`Motion.of(node, ms)`, "hareketi azalt" açıksa `Duration.ZERO` döndürür. Süreler:

| Hareket | Süre |
|---|---|
| Sayfa geçişi | 160 ms |
| Kayma | 160 ms |
| Açılma | 140 ms |
| Panel | 200 ms |
| Çıkış | 120 ms |
| Çapraz geçiş | 120 ms |

Hiçbir animasyon süresiz değildir (`INDEFINITE` ya da `AnimationTimer` kullanılmaz) ve veri asla canlandırılmaz.

## 6. Bilerek yapılmayanlar

- `StageStyle.UNDECORATED`, `EXTENDED` ve `UNIFIED` kullanılmadı.
- AtlantaFX, ControlsFX, Ikonli ya da başka bir UI kütüphanesi eklenmedi. Gerekçe: `research/R2-libraries-fonts-skills.md`.
- Canlı veri gösteren kapsayıcılarda gölge ve efekt yok.
- `-Djavafx.enablePreview` kullanılmadı.

## 7. Testler

- Düz kapı (`./mvnw clean verify`) `ui-snapshot` etiketli sahne testlerini koşmaz. Bu testler `-Pui-snapshots` ile koşar ve PNG çıktıları `target/ui-snapshots/` klasörüne yazılır.
- Laboratuvarda eklenen testlerden bazıları:
  - `ShellDrawerSceneTest`, `ShellOomBadgeSceneTest`, `PageToolbarSceneTest`
  - `ProcessInspectorSceneTest`, `LimitsPanelSceneTest`, `BreakpointAgreementSceneTest`
  - `TitleBarTintTest`, `TitleBarTintSceneTest`, `WindowsWindowChromeTest`
  - `HighContrastTest`, `OverviewArrangementTest`
  - genişletilmiş `ProcessTableWidthTest` ve `KeyboardRoutesSceneTest`
- `NavComparisonProbe` bir araştırma ölçümüdür; varsayılan derlemede koşmaz.
