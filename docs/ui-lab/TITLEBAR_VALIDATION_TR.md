# Başlık çubuğu: gerçek Windows doğrulaması

**Tarih:** 2026-11-08

**Makine:** Windows 11 Pro 10.0.26200, iki monitör.

| Monitör | Çözünürlük | Ölçek | Konum | DPI |
|---|---|---|---|---|
| DISPLAY2 (birincil) | 1920×1080 | %100 | 0,0 | 96 |
| DISPLAY1 | 1920×1080 | %125 | 1920,0 | 120 |

**Yazılım:** JavaFX 25.0.4, JDK 25.0.4.1 (Temurin), JNA 5.19.1.

**Deney programı:** `lab-spikes/titlebar/TitlebarSpike.java`, ürün kodundan ayrı bir laboratuvar deneyi.

**Ölçüm aracı:** `probe-titlebar.ps1` ve `Win32Probe.cs`.
- Pencereyi uygulamanın dışından, Win32 API'leriyle ölçer.
- Gerçek fare ve klavye girişini `SendInput` ile yapar.

Bütün betikler `run-all.ps1` ile tekrar çalıştırılabilir.

**Kanıtlar** `lab-evidence/B-titlebar/` altında:
- `*-results.txt`: her denetimin satırı;
- `*.png`: ekran görüntüleri;
- `run1/`: ilk tur, ölçüm hataları düzeltilmeden önce;
- kök klasör: 7 çalıştırmalık ikinci tur;
- `run3/`: Alt+Space ve SC_KEYMENU için son tur.

**Kullanıcı güvenliği:**
- Gerçek giriş bloğu yalnızca kullanıcı en az 180 saniyedir boştayken çalıştı.
- Zincirdeki sonraki çalıştırmalarda ölçüt "önceki çalıştırmadan beri kullanıcı girişi yok" oldu.
- Kullanıcının ekran ayarlarına, temasına ve "başlık çubuğunda vurgu rengi" ayarına dokunulmadı.

## Denenen yöntemler

| Kod | Yöntem | Nasıl |
|---|---|---|
| **DEC** | `StageStyle.DECORATED` (ürünün bugünkü hâli) | Windows'un kendi başlığı, rengine dokunulmaz |
| **DWM** | DECORATED + DWM öznitelikleri | `DwmSetWindowAttribute`: 20 (koyu mod), 35 (başlık rengi = uygulama krom rengi), 36 (başlık metni), 34 (kenar). Ana pencereye ve her diyaloğa uygulanır, tema değişince yeniden uygulanır |
| **EXT** | `StageStyle.EXTENDED` + `HeaderBar` | JavaFX 25 **preview** API'si, `-Djavafx.enablePreview=true` ister. Başlık düğmelerini JavaFX çizer |
| **UNI** | `StageStyle.UNIFIED` | Şeffaf Scene dolgusu |

UNDECORATED ve sahte başlık düğmesi yöntemleri **denenmedi**. Bunları sahip yasakladı; ayrıca Windows davranışlarını kaybettirirler.

## Kabul matrisi

Hücrelerdeki değerler PASS, FAIL, BLOCKED ya da NOT TESTED; "–" o yöntemde anlamsız demektir. Satır kodları araştırma notundaki deney listesine karşılık gelir (`R1-titlebar.md` §6). Her hücrenin dayandığı satır ilgili `*-results.txt` dosyasındadır.

| # | Davranış | DEC | DWM | EXT | UNI | Kanıt / not |
|---|---|---|---|---|---|---|
| E1 | Stage başına tek native pencere (HWND) | PASS | PASS | PASS | PASS | `E1.one-hwnd` |
| E1 | Uygulama içinden HWND bulma (`EnumThreadWindows` + sınıf öneki + tam başlık) | PASS | PASS | PASS | PASS | **Bulgu:** `onShown` anında native başlık hâlâ boş. Arama bir sonraki FX turunda tekrarlanınca ikinci denemede buluyor |
| E1 | Diyalog sahipliği (`GW_OWNER` = ana pencere) | PASS | PASS | PASS | PASS | `E1.dialog-owner` |
| E12 | Pencere stilleri: SYSMENU, THICKFRAME, MIN/MAXBOX, CAPTION; görev çubuğu düğmesi | PASS | PASS | PASS | PASS | `style=0x16cf0000` |
| E8 | Sistem menüsü var (`GetSystemMenu`, 7 öğe) | PASS | PASS | PASS | PASS | |
| E8 | Başlığa sağ tıkla sistem menüsü (gerçek giriş) | PASS | PASS | PASS | PASS | `#32768` menü penceresi görüldü |
| E8 | Alt+Space | BLOCKED | BLOCKED | BLOCKED | BLOCKED | **Bu makinede PowerToys Run Alt+Space'i sistem genelinde yakalıyor**; tuş hiçbir pencereye ulaşmıyor. Aynı sonuç Notepad'de de alındı. Kanıt: `run3/*-results.txt` |
| E8 | Klavye sistem menüsü yolu (`WM_SYSCOMMAND SC_KEYMENU ' '`, Alt+Space'in tetiklediği komut) | PASS | PASS | PASS | NOT TESTED | `run3/*-keymenu.png`. UNI son turda çalıştırılmadı |
| E8 | Alt+F4 kapatır; uygulamanın kapanış yolu (closeRequest) çalışır | PASS | PASS | PASS | PASS | |
| E8 | `SC_CLOSE` kapatır | PASS | PASS | PASS | PASS | |
| E5 | Görünen kapat düğmesine gerçek tıklama | PASS | PASS | PASS | NOT TESTED | Açık tema turlarında. UNI yalnızca koyu temada çalıştı |
| E5 | Görünen maximize ve restore düğmelerine gerçek tıklama | PASS | PASS | PASS | PASS | |
| E5 | Görünen minimize düğmesine gerçek tıklama | PASS | PASS | PASS | PASS | |
| E5 | Başlıkta çift tık: maximize, sonra restore | PASS | PASS | PASS | PASS | |
| E5 | `SC_MAXIMIZE`, `SC_MINIMIZE`, `SC_RESTORE`; JavaFX Stage özellikleri native durumu izliyor | PASS | PASS | PASS | PASS | |
| E5 | Maximize'da pencere çalışma alanını tam dolduruyor (görev çubuğu açık) | PASS | PASS | PASS | PASS | frame = work area (0,0,1920,1032) |
| E5 | Maximize'dan restore aynı konum ve boyuta dönüyor | PASS | PASS | PASS | PASS | |
| E3 | Snap Layouts (maximize düğmesinde gerçek hover) | PASS | PASS | PASS | PASS | `*-snap-layouts-hover.png`: dört yöntemde de flyout açıldı ve vurgu doğru düğmede. Gözle değerlendirildi |
| E6 | Başlıktan sürükleme | PASS | PASS | PASS | PASS | (150,80) |
| E6 | Aero Snap: sol kenara sürükleme yarım ekran yapıyor | PASS | PASS | PASS | PASS | frame (0,0,960,1032) |
| E6 | Snap'ten sürükleyerek çıkınca eski boyut geri geliyor | PASS | PASS | PASS | PASS | |
| E6 | Maximize'dan aşağı sürükleme restore ediyor | PASS | PASS | PASS | PASS | |
| E7 | Sağ kenar ve köşeden boyutlandırma | PASS | PASS | PASS | PASS | Δ120 ve (−120,−60) |
| E7 | Minimum boyut (720 mantıksal px) korunuyor | PASS | PASS | PASS | PASS | |
| E2 | Hit-test: kenarlar ve köşeler (10–17), sürükleme alanı (2), içerik (1), kapat (20) | PASS | PASS | PASS | PASS | %100, %125 ve maximize durumunda |
| E2 | Hit-test: minimize ve maximize (mesajla gönderilen `WM_NCHITTEST`) | FAIL\* | FAIL\* | PASS | FAIL\* | Açıklama aşağıda |
| E9 | %125 monitöre taşıma: DPI 96→120, JavaFX ölçeği 1,25, hit-testler doğru; geri taşıyınca 96 | PASS | PASS | PASS | PASS | `*-monitor2-dpi120.png` |
| E9 | %150 ve %200 fiziksel ölçek | NOT TESTED | NOT TESTED | NOT TESTED | NOT TESTED | Böyle bir monitör yok; kullanıcının ekran ayarı değiştirilmedi |
| E10 | Başlık rengi uygulama kromuyla aynı (koyu #16181D, açık #EEF0F3) | – (sistem açık başlığı) | PASS | PASS | – | Piksel ölçümü |
| E10 | Uygulama içi canlı tema geçişi başlığı yeniden boyuyor (`SWP_FRAMECHANGED` gerekmeden) | – | PASS | PASS | – | |
| E10 | Pasif pencerede başlık rengi korunuyor; metin ve glifler soluklaşıyor | – | PASS | PASS (renk) | – | `dwm-dark-inactive.png` |
| E10 | Diyalog başlığı da aynı renkte | – | PASS | **FAIL** | – | EXT'te diyaloglar DECORATED kalıyor (JavaFX 25'te `DialogPane` HeaderBar'ı yok), açık sistem başlığıyla görünüyor |
| E10 | İşletim sistemi temasının canlı değişimi | NOT TESTED | NOT TESTED | NOT TESTED | NOT TESTED | Kullanıcı ayarına dokunulmadı |
| E10 | "Başlık çubuğunda vurgu rengi göster" açıkken | NOT TESTED | NOT TESTED | NOT TESTED | NOT TESTED | Kullanıcı ayarı |
| E11 | İlk gösterimde bir karelik açık renk flaşı | NOT TESTED | NOT TESTED | NOT TESTED | NOT TESTED | Yüksek hızlı yakalama yapılmadı. DWM rengi ilk gösterimden yaklaşık 50 ms sonra uygulanıyor (ikinci FX turu); kısa bir flaş olası |
| E13 | Başlık düğmelerinin UI Automation ağacında görünmesi | NOT TESTED | NOT TESTED | NOT TESTED | NOT TESTED | |
| E14 | Windows 10 | NOT TESTED | NOT TESTED | NOT TESTED | NOT TESTED | Makine yok. DWM 35/36/34 yalnızca build 22000+ |
| – | Yüksek kontrast teması | NOT TESTED | NOT TESTED | NOT TESTED | NOT TESTED | |
| – | Build ve çalışma koşulu | PASS | PASS | **FAIL**\*\* | PASS | Açıklama aşağıda |

**\* `WM_NCHITTEST` ölçüm notu.** Mesajla gönderilen `WM_NCHITTEST`, DECORATED pencerelerde minimize ve maximize düğmelerini görünen yerlerinden yaklaşık 20–30 px sağda ve 33 px genişlikte bildiriyor. Bu, `DefWindowProc`'un klasik başlık ölçülerini kullanmasından kaynaklanıyor. Kanıt `E2.scan` satırında: `[998..1030]=8 [1033..1065]=9 [1066..1100]=20`.

Windows 11'in gerçek fare işlemesi ise başka bir yoldan gidiyor ve doğru çalışıyor:
- gerçek tıklamalar görünen düğmelerin merkezinde doğru işi yaptı;
- Snap Layouts hover'ı doğru düğmede çıktı.

Aynı sapma ürünün bugünkü DEC penceresinde de var; yani DWM yönteminin getirdiği bir sorun değil. Bu yüzden kullanıcının gördüğü davranış PASS. Ham FAIL satırları kanıt dosyalarında olduğu gibi bırakıldı.

**\*\* EXT ön koşulları.**
- `-Djavafx.enablePreview=true` olmadan `RuntimeException` fırlatıyor.
- PRISM'in `-Xlint:all -Werror` derlemesinde `deprecation` uyarısı veriyor; `@SuppressWarnings` gerekiyor.
- API JavaFX 26 ve 27'de iki kez kırılıyor (`R1-titlebar.md` §1).
- Görev tanımı "preview API'yi kalıcı çözüm yapma" diyor. Bu yüzden kalıcı çözüm olarak FAIL.

## Görsel karşılaştırma (koyu tema, 1100×700, %100)

| Yöntem | Dosya | Gözlem |
|---|---|---|
| DEC | `decorated-dark-active.png` | Beyaz sistem başlığı ile koyu uygulama ayrışıyor. Sahibin "başlık uygulamadan kopuk" şikâyetinin kaynağı bu |
| DWM | `dwm-dark-active.png`, `dwm-dark-live-light.png` | Başlık rengi krom rengiyle aynı, başlık ve toolbar tek yüzey gibi okunuyor. Native düğmeler, simge, köşe yuvarlaması ve gölge Windows'un kendisi. Açık temada da aynı bütünlük var |
| EXT | `extended-dark-active.png` | İçerik başlık satırına taşınabiliyor (örneğin arama). Ancak düğmeleri JavaFX çiziyor, pencere simgesi yok ve diyaloglar açık başlıkla ayrışıyor |
| UNI | `unified-dark-active.png` | Bu makinede DEC ile görsel olarak aynı: beyaz başlık. Hiçbir kazanç yok, üstelik şeffaf Scene gerektiriyor |

## Karar (laboratuvar)

**Laboratuvarda varsayılan: DWM (DECORATED + DWM öznitelikleri).** Gerekçeler:
- Bu makinede test edilebilen her Windows davranışı PASS. Davranış ürünün bugünkü DEC penceresiyle birebir aynı, çünkü pencere hâlâ Windows'un kendi penceresi.
- Görsel bütünlük EXT'e çok yakın, ve buna diyaloglar da dahil.
- Preview API, bayrak ya da lint bastırma yok.
- Windows 10'da ve Windows 11 build 22000 öncesinde öznitelikler uygulanmaz ve durum dürüstçe "unavailable" olarak raporlanır. Pencere o zaman bugünkü hâliyle kalır.

**EXT** yalnızca laboratuvar deneyi olarak, kapalı bir bayrak arkasında kalır. Varsayılan olmaz.
- Başlık satırına arama gibi içerik koyabilmesi gerçek bir artı.
- Ancak preview bayrağı, diyalogların uyumsuzluğu ve yaklaşan API kırılmaları nedeniyle ürün için önerilmez.

**Stratejik seçenek:** JavaFX 27 ile final `HeaderBar` (`R1-titlebar.md` §5). Bu, dondurulmuş teknoloji tabanını değiştirir; ayrı bir ADR ve sahibin kararı gerekir.

**UNIFIED** reddedildi.

**Üretime geçiş sahibin ayrı teknik kararına bağlıdır.** Bu belge yalnızca laboratuvar sonucunu raporlar.

## Açık kalanlar

- E11 ilk gösterim flaşı: ölçülmeli. Gerekirse pencere ilk gösterimde `DWMWA_CLOAK` ile ya da opaklık 0 ile gizlenir.
- %150 ve %200 fiziksel ölçek: NOT TESTED. Laboratuvar yalnızca `-Dglass.win.uiScale` ile simüle edebilir; simülasyon ayrı işaretlenecek.
- Windows 10, yüksek kontrast, vurgu rengi ayarı ve işletim sistemi temasının canlı değişimi: NOT TESTED.
- Alt+Space: bu makinede PowerToys yüzünden BLOCKED. PowerToys kapalı bir makinede elle denenmeli.
