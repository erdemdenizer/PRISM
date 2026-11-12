# Başlık çubuğu, tur H: durum, tümleşik başlık değerlendirmesi ve gerçek pencere kontrol listesi

**Tarih:** 2026-11-11

**Dal:** `lab/h-titlebar`, taban `8824ada`.

Bulgu tablosu `titlebar-status.md` belgesindedir.

## 1. Bu turda ne değişti (görev 1)

**Yapı aynı:**
- DECORATED pencere, Windows'un kendi başlık düğmeleri.
- DWM öznitelikleri 20, 35, 36 ve 34.
- Snap, boyutlandırma, çift tık, sistem menüsü ve DPI davranışı Windows'ta.
- `platform/**` değişmedi; yeni native çağrı ya da sabit yok.

**Yeni: pasif pencere görünümü (TB-H1).**
- Ölçüm: Windows, uygulamanın `DWMWA_TEXT_COLOR` ile verdiği metin rengini pasif pencerede soluklaştırmıyor; yalnızca düğme gliflerini soluklaştırıyor.
  - Glifler: koyu temada 255 → 115.
  - Metin: aktif ve pasifte aynı (236,238,242).
  - Kanıt: `lab-evidence/B-titlebar/dwm-dark-*.png` ve G turu gerçek pencere görüntüleri.
- Sonuç olarak bir diyalog açıkken arkadaki ana pencere aktif gibi okunuyordu.
- Çözüm `TitleBarTint` içinde: pencerenin `focused` özelliği false olunca başlık metni `-srm-text-subtle` oluyor; başlık ve kenar rengi aynı kalıyor.

| Tema | Başlık | Metin (aktif) | Metin (pasif) | Kenar |
|---|---|---|---|---|
| Koyu | #16181D | #ECEEF2 (15,3:1) | #8F98A8 (6,1:1) | #2A2E36 |
| Açık | #EEF0F3 | #15181E (15,6:1) | #5C6474 (5,2:1) | #D5D9E0 |

**Gereksiz iş yok:**
- Her pencere en son aldığı görünümü hatırlıyor; odak değişimi görünümü değiştirmiyorsa native çağrı yapılmıyor.
- Bir arama sürerken gelen değişim ikinci bir arama başlatmıyor; sürmekte olan aramanın sonraki denemesi yeni rengi okuyor.
- Bulunamayan pencere, her odak değişiminde 20 kez yeniden aranmıyor.
- Tema, sistem renk şeması ve yüksek kontrast değişimleri ise eskisi gibi her pencereyi koşulsuz yeniden boyuyor.

**Native çağrılar:** önceki gibi FX iş parçacığında, kısa ve beklemesiz (`EnumThreadWindows` + 4 × `DwmSetWindowAttribute`); yalnızca odak gerçekten değişince.

**Bilinçli olarak değişmeyenler:**
- Başlık metni (TB-H3);
- kenar (TB-H4);
- ilk gösterim flaşı (TB-H6).

Gerekçeler `titlebar-status.md` belgesinde.

## 2. Tümleşik başlık çubuğu değerlendirmesi (görev 2)

**Soru:** JavaFX 25'te uygulama içeriğini başlık alanına koyup bütün gerçek pencere davranışlarını koruyan güvenli ve bakımı kolay bir yol var mı?

### 2.1 Kullanılan JavaFX 25.0.4 jar'larında doğrulananlar

İnceleme `javap` ile yapıldı. Jar'lar: `javafx-graphics-25.0.4-win.jar`, `javafx-base-25.0.4-win.jar`.

**API var, ama preview:**
- `javafx.stage.StageStyle.EXTENDED` ve `javafx.scene.layout.HeaderBar` (`leading`/`center`/`trailing`, `setButtonType`, `setDragType`, `setPrefButtonHeight`) mevcut.
- `StageStyle.EXTENDED` `@Deprecated(since="25")` taşıyor. PRISM'in `-Xlint:all -Werror` derlemesinde uyarı verir, yani `@SuppressWarnings("deprecation")` gerekir.

**Çalışma zamanı kapısı:**
- `com.sun.javafx.PreviewFeature` enum'u `STAGE_STYLE_EXTENDED` ve `HEADER_BAR` sabitlerini içeriyor; özellikler `javafx.enablePreview` ve `javafx.suppressPreviewWarning`.
- `Stage` ve `HeaderBar` bytecode'u `PreviewFeature.checkEnabled()` çağırıyor. Bayrak yoksa kullanım çalışma zamanında reddediliyor.

**Başlık düğmelerini JavaFX çiziyor:**
- `com.sun.glass.ui.win.WinHeaderButtonOverlay` + `WindowDecoration.css`: düğme 46 px genişlik, 29 px yükseklik, close hover `#c42b1c`.
- Glif rengi `Scene.getFill()` parlaklığından seçiliyor (`HeaderButtonOverlay.isDarkBackground`), sahnenin renk şemasından değil.
- Overlay sınıflarında erişilebilirlik rolü ya da özniteliği yok (bytecode'da `accessib` geçmiyor). Ekran okuyucunun bu düğmeleri nasıl gördüğü **DOĞRULANMADI**.

**Hit-test gerçek Win32:**
- `WinWindow.nonClientHitTest` düğmeler için `HTMINBUTTON`, `HTMAXBUTTON`, `HTCLOSE`, sürükleme alanı için `HTCAPTION` döndürüyor.
- Microsoft'un Snap Layouts için koşulu tam olarak bu: özel başlıkta maximize düğmesi için `WM_NCHITTEST` → `HTMAXBUTTON`.
- Önceki turun gerçek pencere ölçümünde EXT için Snap Layouts flyout'u açıldı (`TITLEBAR_VALIDATION_TR.md`, E3).

### 2.2 Resmi kaynaklar

**JavaFX 25 release notes** (github.com/openjdk/jfx, `doc-files/release-notes-25.md`):
- JDK-8313424 "JavaFX controls in the title bar (Preview)";
- JDK-8349373 "Support JavaFX preview features".

HeaderBar javadoc 25 (openjfx.io/javadoc/25): "This is a preview feature which may be changed or removed in a future release." Snap, erişilebilirlik ya da diyaloglardan söz etmiyor.

**JavaFX 26 release notes:**
- JDK-8369836 "Update HeaderBar API" (API kırılıyor);
- JDK-8370446 "Support dialogs with StageStyle.EXTENDED (Preview)": diyaloglar ancak 26'da ve yine preview;
- JDK-8362091 "Window title bar should reflect scene color scheme": DECORATED başlığın sahnenin renk şemasına uyması.

**JavaFX 27 release notes:**
- JDK-8386617 "JavaFX controls in the title bar", preview etiketi olmadan yeni özellik.
- JDK-8376601: "JavaFX 27 requires JDK 25 or later".
- HeaderBar javadoc 27'de "Since: 27"; `leading`/`trailing` yerine `left`/`right`; `systemButtonHeight`, `leftSystemInset` ve `systemColorScheme` Stage'e bağlı statik özellikler.
- Maven Central'da `javafx-graphics` 27 ve 27.0.1 yayımlanmış (maven-metadata.xml).

**Microsoft Learn** (learn.microsoft.com/en-us/windows/apps/desktop/modernize/ui/apply-snap-layout-menu):
- Özel başlıkta Snap Layouts için `WM_NCHITTEST` → `HTMAXBUTTON` gerekiyor.
- En küçük genişliğin en çok 500 epx olması öneriliyor.

**Okunamayanlar:** bugs.openjdk.org sayfaları 403 döndü, JBS içerikleri doğrudan okunamadı. Yukarıdaki bilgiler release notes, javadoc ve jar'dan geliyor.

### 2.3 Değerlendirme

| Gereksinim | DECORATED + DWM (bugün) | EXTENDED + HeaderBar (JavaFX 25) |
|---|---|---|
| Snap Layouts (maximize hover) | Windows'un kendisi; PASS | `HTMAXBUTTON` dönüyor; önceki turda PASS |
| Kenar boyutlandırma, çift tık, sürükleme | Windows; PASS | `DefWindowProc` önce çağrılıyor; önceki turda PASS |
| Sistem menüsü (sağ tık, SC_KEYMENU) | PASS | PASS. Alt+Space bu makinede PowerToys yüzünden BLOCKED |
| Başlık düğmelerinin erişilebilirliği | Windows'un kendi düğmeleri | JavaFX overlay'i, erişilebilirlik rolü yok; **DOĞRULANMADI** |
| DPI değişimi | Windows | Önceki turda %100 ↔ %125 PASS. JDK-8371302 (ikincil ekranda koordinat hatası) 26'da düzeldi, 25'e backport yok |
| Açık/koyu | DWM 20/35/36/34 | Glif rengi `Scene.fill`'den; uygulamanın fill'i yönetmesi gerekir |
| Diyaloglar | Aynı DWM renkleri; PASS | 25'te EXTENDED olamaz; açık sistem başlığıyla ayrışıyor (önceki turda FAIL) |
| Build ve çalışma koşulu | Ek bir şey yok | Preview bayrağı launcher'a, surefire'a ve `javafx:run`'a ayrı ayrı; lint bastırma |
| API ömrü | Belgelenmiş DWM öznitelikleri | 26'da ve 27'de kırılıyor |

**Sonuç: JavaFX 25 üzerinde tümleşik başlık çubuğu laboratuvar için ÖNERİLMEZ.** Native çözüm kalır. Gerekçeler:
- preview API ve çalışma zamanı bayrağı;
- lint bastırma;
- iki API kırılması;
- diyalogların uyumsuz kalması;
- JavaFX'in çizdiği başlık düğmelerinin erişilebilirliğinin belirsiz olması;
- 25'e geri taşınmayan DPI düzeltmesi.

**Görsel kazanç sınırlı.** Bugünkü DWM çözümü başlık ile ray ve sayfa başlığını zaten tek renk yapıyor (TB-H2). Tümleşik başlığın tek gerçek kazancı, başlık satırına içerik (örneğin arama) koyabilmek.

**Bu turda spike yazılmadı:** `lab-spikes/titlebar/TitlebarSpike.java` içindeki `extended` modu aynı API'yi zaten kullanıyor ve önceki turda gerçek pencerede ölçüldü.

### 2.4 Gelecek iş tanımı (öneri)

**"JavaFX 27'ye geçiş ve final HeaderBar"**, ayrı bir iş kalemi:

1. ADR: ADR-0001 teknoloji tabanını JavaFX 25'ten 27'ye taşımak. JDK 25 kalır; JavaFX 27 JDK 25 istiyor. Sahibin kararı gerekir.
2. Önce yalnızca yükseltme. JDK-8362091 ile DECORATED başlık sahnenin renk şemasını kendisi izleyebilir. DWM kodunun ne kadarının gereksizleştiği gerçek pencerede ölçülmeli: özel başlık rengi 35 belki yine gerekir.
3. Sonra, kapalı bir bayrak arkasında `StageStyle.EXTENDED` + `HeaderBar` (final API) ve diyaloglar için `Dialog.headerBar`.
4. Kabul matrisi `TITLEBAR_VALIDATION_TR.md` E1–E14'ün tamamı:
   - Snap Layouts hover;
   - UIA ağacında başlık düğmeleri (E13);
   - %150 ve %200;
   - iki monitörde DPI;
   - yüksek kontrast;
   - Windows 10.
5. Varsayılan ancak matris tümüyle PASS olursa ve sahip karar verirse değişir.

## 3. Gerçek pencere kontrol listesi (koordinatör için)

Uygulama imajı bu dalın derlemesiyle üretilir; %125'te, boşta bir makinede.

| # | Ne | Beklenen | Nasıl |
|---|---|---|---|
| 1 | Koyu tema, aktif ana pencere | Başlık, ray ve sayfa başlığı (22,24,29); başlık metni (236,238,242); çizgi yok | Piksel |
| 2 | Koyu tema, ana pencere pasif (başka bir uygulamaya geç) | Başlık (22,24,29) aynı; metin ≈ (143,152,168) #8F98A8; Windows glifleri soluk | Piksel. Metin aktiften belirgin soluk olmalı |
| 3 | Koyu tema, bir onay diyaloğu açık (End task → Esc ile iptal) | Ana pencere metni soluk, diyalog metni parlak; Esc'ten sonra ana pencere metni yeniden parlak | Görüntü ve piksel, diyalog açıkken ve kapandıktan sonra |
| 4 | Açık tema: 1–3'ün tekrarı | Başlık (238,240,243); aktif metin (21,24,30); pasif ≈ (92,100,116) #5C6474 | Piksel |
| 5 | Odak değişiminde titreme | Yalnızca metin rengi değişir; başlık arka planı ya da kenar yanıp sönmez. Düğmelerle metin arasında bir karelik gecikme kabul | Hızlı ardışık görüntü (5–10 kare) |
| 6 | Kenar | Koyu #2A2E36, açık #D5D9E0; aktif ve pasifte aynı. Açık temada beyaz bir pencerenin önünde pencere kenarı okunuyor mu (gölge dahil) | Piksel ve göz |
| 7 | Uygulama içi tema değişimi (Ayarlar: Light ↔ Dark), pencere aktif | Başlık hemen yeni temada, metin aktif rengi | Piksel |
| 8 | Tema değiştir, sonra odağı kaybet | Pasif metin yeni temanın pasif rengi | Piksel |
| 9 | Snap Layouts (maximize üzerinde bekleme), Win+Z, çift tık, kenar boyutlandırma, sürükleme, sağ tıkla sistem menüsü | Hepsi Windows davranışı, önceki turla aynı. Not: 1920 px ekranda üçlü sütun bölgesi (640 px) 720 px en küçük genişlikten dar | Gerçek giriş, önceki turun `probe-titlebar.ps1` yöntemiyle |
| 10 | Başlık metni | "PRISM UI Lab — Process & Resource Interactive System Manager", 720×480'de kesilmeden | Görüntü |
| 11 | `verify-image.ps1` | Başlıkla pencere bulma adımları PASS (başlık değişmedi) | Betik |
| 12 | Yüksek kontrast (yalnızca sahip izin verirse) | Açıkken başlık Windows'un kendi renkleri, odak değişiminde bile değişmez; kapatınca tema renkleri geri gelir | Göz |
| 13 | Günlük | `Title bar:` satırı yok (her pencere bulundu) | `%LOCALAPPDATA%\Prism\SystemResourceManager-UILab\logs` (yalnızca okuma) |
