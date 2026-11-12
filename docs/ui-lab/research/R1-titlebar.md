# R1: Başlık çubuğu araştırması (JavaFX 25.0.4, Windows 11)

Bu not 2026-11-08'de bir araştırma ajanının raporuna dayanır. Ajan hiçbir şey değiştirmedi ve Maven çalıştırmadı. Yerel jar'lar salt okunur olarak `javap` ve `unzip` ile incelendi: `javafx-graphics-25.0.4-win.jar`, `javafx-base-25.0.4-win.jar`, `jna-platform-jpms-5.19.1.jar`.

Aşağıdaki maddeler araştırma bulgusudur. Gerçek Windows üzerinde ölçülmüş sonuçlar `TITLEBAR_VALIDATION_TR.md` belgesindedir. Doğrulanmamış iddialar **DOĞRULANMADI** diye işaretlidir.

## 1. StageStyle.EXTENDED ve HeaderBar (25.0.4)

**API (jar'dan okundu):**
- `StageStyle.EXTENDED` mevcut.
- `javafx.scene.layout.HeaderBar extends Region`:
  - kurucular `()` ve `(Node, Node, Node)`;
  - özellikler `leading`, `center`, `trailing`, `leadingSystemPadding`, `trailingSystemPadding`;
  - salt okunur `leftSystemInset`, `rightSystemInset` (`Dimension2D`) ve `minSystemHeight`;
  - statik `setDragType`, `setButtonType`, `setPrefButtonHeight(Stage,double)`, `setAlignment`, `setMargin`.
- Enum'lar `HeaderButtonType {ICONIFY, MAXIMIZE, CLOSE}` ve `HeaderDragType {NONE, DRAGGABLE, DRAGGABLE_SUBTREE}`.

**Preview olarak işaretli:**
- Dördü de `@Deprecated(since="25")` (forRemoval yok). JavaFX preview API'leri böyle işaretler.
- PRISM'in `-Xlint:all` + `failOnWarning` ayarıyla her kullanım `deprecation` uyarısı verir ve build'i kırar.
- En dar çözüm, yalnızca bu API'ye dokunan metoda `@SuppressWarnings("deprecation")` koymak.

**Çalışma zamanı kapısı:**
- `-Djavafx.enablePreview=true` yoksa `initStyle(EXTENDED)` ve `new HeaderBar()` `RuntimeException` fırlatır.
- Bayrak varken stderr'e bir kez "Note:" yazılır; `-Djavafx.suppressPreviewWarning=true` bunu susturur.
- Bayrak launcher'a, surefire'a ve `javafx:run`'a ayrı ayrı verilmek zorunda.

**Başlık düğmelerini Windows değil JavaFX çizer:**
- Sınıf `com.sun.glass.ui.win.WinHeaderButtonOverlay`, stili `WindowDecoration.css`: genişlik 46, yükseklik 29, close hover `#c42b1c`.
- Glif rengi koyu ya da açık temaya göre **`Scene.fill` parlaklığından** seçilir, `Scene.Preferences.colorScheme`'den değil.
- PRISM fill ayarlamadığı için varsayılan beyaz fill koyu temada yanlış glif verir.

**Hit-test gerçek Win32:**
- `WinWindow.nonClientHitTest` HTMINBUTTON 8, HTMAXBUTTON 9, HTCLOSE 20, HTCAPTION 2, HTCLIENT 1 döner.
- Native `GlassWindow.cpp`:
  - önce `DefWindowProc` çağrılır, böylece kenar resize'ları native kalır;
  - çift tık DefWindowProc'a düşer;
  - sağ tık `GetSystemMenu` + `TrackPopupMenu` ile sistem menüsünü açar;
  - maximize durumunda WM_NCCALCSIZE padding'i hesaplar.
- Snap Layouts için Microsoft'un kuralı HTMAXBUTTON döndürmektir; JavaFX bunu yapıyor. Gerçek davranış **DOĞRULANMADI**.
- glass.dll içinde dark mode DWM çağrısı yok.

**Bilinen 25.x sorunları:**

| Kayıt | Durum |
|---|---|
| JDK-8368526: Windows 10'da vurgu renkli başlıkta üst kenar eksik | açık |
| JDK-8371302: ekran ayarı değişince ikincil ekranda koordinat hatası | jfx26'da düzeldi, 25'e backport yok |
| JDK-8368021: RTL'de düğmeler yanlış tarafta | 25.0.2'de düzeldi |

**Diyaloglar 25'te EXTENDED olamaz.** `DialogPane.headerBar` jfx26'da preview olarak geldi. Ürünün 9 diyaloğu DECORATED kalır.

**API 26 ve 27'de iki kez kırılıyor:**
- jfx26: `leading`/`trailing` → `left`/`right`; inset'ler `Stage` üzerinde statik özelliklere taşınıyor.
- jfx27: API final oluyor; `prefButtonHeight` → `systemButtonHeight`; `systemColorScheme` ekleniyor.
- JavaFX 27 GA'nın çıkıp çıkmadığı **DOĞRULANMADI**.

## 2. StageStyle.UNIFIED (25.0.4)

- Yalnızca frame ile client arasındaki sınırı kaldırır ve şeffaf Scene ister.
- Başlığa içerik koymayı sağlamaz.
- Bazı GPU'larda boş render hatası (JDK-8154847) yalnızca jfx26.0.1/27'de düzeldi; jfx25u backport PR #47 birleştirilmeden kapandı.
- **Kullanılamaz.**

## 3. DECORATED + DWM öznitelikleri

| Öznitelik | Değer | Destek |
|---|---|---|
| `DWMWA_CAPTION_BUTTON_BOUNDS` | 5 | yalnız okuma |
| `DWMWA_USE_IMMERSIVE_DARK_MODE` | 20 | build 22000+ |
| `DWMWA_WINDOW_CORNER_PREFERENCE` | 33 | build 22000+ |
| `DWMWA_BORDER_COLOR` | 34 | build 22000+ |
| `DWMWA_CAPTION_COLOR` | 35 | build 22000+ |
| `DWMWA_TEXT_COLOR` | 36 | build 22000+ |
| `DWMWA_SYSTEMBACKDROP_TYPE` | 38 | build 22621+ |

Kaynak: learn.microsoft.com/en-us/windows/win32/api/dwmapi/ne-dwmapi-dwmwindowattribute. COLORREF `0x00bbggrr` biçimindedir; `DWMWA_COLOR_DEFAULT` = 0xFFFFFFFF.

**Belgelenmemiş, ölçülmesi gereken noktalar** (hepsi **DOĞRULANMADI**):
- pasif pencerede caption renginin davranışı;
- "başlık çubuğunda vurgu rengi" ayarıyla etkileşim;
- canlı tema değişiminde caption'ın yeniden boyanması;
- ilk gösterimde bir karelik açık renk flaşı.

**JNA:**
- jna-platform 5.19.1'de Dwmapi bağlaması yok. `platform.windows` içinde küçük bir arayüz gerekir.
- `User32` içinde `EnumThreadWindows`, `GetClassName`, `GetWindowText`, `GetWindowThreadProcessId` ve `SetWindowPos` var.
- `GetDpiForWindow` ve `GetSystemMenu` yok.

**HWND bulma (iç API kullanmadan):**
- Glass sınıf adı `GlassWndClass-GlassWindowClass-<n>`.
- FX thread'inde `show()` sonrası şu sırayla aranır: `GetCurrentThreadId` → `EnumThreadWindows` → sınıf öneki + tam başlık eşleşmesi.
- Tam olarak bir eşleşme yoksa sonuç `UNAVAILABLE` olur.
- Temaya geçişte ve her diyalog gösteriminde yeniden uygulanır.

**Ücretsiz alternatif:** JDK-8362091 (jfx26) DECORATED başlığı Scene renk şemasına göre native olarak temalıyor. 25'e backport yok.

## 4. Windows App SDK ilkeleri

**Taşınabilenler:**
- Başlık yüksekliği 32 px; etkileşimli içerik varsa 48 px.
- İkon 16 px, kenardan 16 px; başlık metni ikondan 16 px sonra.
- Bütün boş alan sürüklenebilir; en az 48 px'lik bir sürükleme sütunu kalır.
- Sağ tık sistem menüsünü, çift tık maximize'ı açar.
- Caption düğmeleri alanının altına etkileşimli öğe konmaz.
- Pasif pencerede metin ve düğmeler soluklaşır.
- High contrast ile açık/koyu temaya uyulur.

**Taşınmayanlar:** `AppWindowTitleBar` renk özellikleri, Mica, `.ico` ile ikon ayarı. DECORATED + DWM'de yalnızca caption, metin ve kenar renkleri kontrol edilir; yerleşim native kalır.

Kaynaklar:
- learn.microsoft.com/en-us/windows/apps/develop/title-bar
- learn.microsoft.com/en-us/windows/apps/design/basics/titlebar-design

## 5. Öneri (araştırma aşaması)

1. **Varsayılan: DECORATED + DWM 20/35/36/34.**
   - JNA ile `platform.windows` içinde uygulanır.
   - Bütün native davranışlar yapı gereği korunur.
   - Windows 10'da dürüstçe `UNAVAILABLE`.
2. **Stratejik yol: JavaFX 27 + JDK 25.** Final HeaderBar sunuyor; renk şemasıyla başlık teması 26'da gelmişti. Ancak dondurulmuş tabanı (ADR-0001) değiştirir; ADR ve sahibin kararı gerekir.
3. **EXTENDED + HeaderBar (25):** yalnızca laboratuvar deneyi, kapalı bayrak arkasında.
   - Preview bayrağı gerektirir.
   - Lint bastırma gerekir.
   - API iki kez kırılacak.
   - Düğmeleri JavaFX çiziyor.
   - Diyaloglar kapsanmıyor.
4. **UNIFIED ve UNDECORATED reddedildi.**

## 6. Deney listesi (gerçek Windows'ta)

| Kod | Deney |
|---|---|
| E1 | Stage başına tam bir HWND; diyalog sahipliği (`GW_OWNER`) |
| E2 | `WM_NCHITTEST`: düğme merkezlerinde 8/9/20, sürükleme alanında 2, kenarlarda 10–17, etkileşimli öğede 1 |
| E3 | Snap Layouts (maximize üzerinde bekleme, Win+Z), gözle |
| E4 | `DWMWA_CAPTION_BUTTON_BOUNDS` |
| E5 | Çift tık; `WM_SYSCOMMAND` ile min, max, restore, close |
| E6 | Sürükleme, Aero Snap, maximize'dan sürükleyerek geri alma |
| E7 | Kenar resize ve minimum boyut |
| E8 | Sistem menüsü, Alt+Space, Alt+F4 |
| E9 | İki monitörde DPI (`GetDpiForWindow`) ve E2'nin tekrarı |
| E10 | Tema pikselleri: açık/koyu, canlı geçiş, pasif durum, vurgu rengi ayarı |
| E11 | İlk gösterim flaşı |
| E12 | Pencere stilleri ve görev çubuğu |
| E13 | UIA ağacı |
| E14 | Windows 10 (bu makinede yok, **TEST EDİLMEDİ**) |

## 7. Önceki notların eleştirisi

`r5-javafx-visual.md` değerlerde ve UNIFIED konusunda büyük ölçüde doğru. Ancak caption renginin "vurgu ayarını ezdiği" iddiası Microsoft belgelerinde yok.

`JAVAFX_FEASIBILITY_TR.md` dört şeyi kaçırmış:
- 25'te EXTENDED caption düğmelerini JavaFX çizer;
- bu düğmelerin glif rengi `Scene.fill`'e bağlıdır;
- diyaloglar 25'te EXTENDED olamaz;
- JavaFX 26, DECORATED başlığı zaten native olarak temalıyor.
