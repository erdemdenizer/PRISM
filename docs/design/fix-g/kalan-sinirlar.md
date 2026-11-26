# Kalan başlık çubuğu ve görsel sınırlar

## Başlık çubuğu (bu turda bilerek değiştirilmedi)

- **Yapı:**
  - Pencere DECORATED; Windows'un kendi başlık çubuğu ve düğmeleri duruyor.
  - DWM öznitelikleri 20, 35, 36 ve 34 ile koyu ya da açık renge boyanıyor (ADR-A).
  - `WindowsWindowChrome`, `TitleBarTint`, `WindowChrome` ve `ChromeColors` bu turda hiç değişmedi.
- **Korunan pencere davranışları:**
  - Snap, sürükleme ve çift tıklayınca büyütme.
  - Sistem menüsü.
  - Gerçek küçültme, büyütme ve kapatma düğmeleri.
- **Bu tura ait gerçek pencere kanıtı:** 130 + 86 + 86 görüntünün tamamında başlık çubuğu Windows'un kendisi.
- **Ölçülmedi:**
  - Snap ve sistem menüsü bu turda ayrıca sınanmadı.
  - Bu davranışları etkileyecek kod da değişmedi.
  - Önceki turda Alt+Space, bu makinede PowerToys yüzünden BLOCKED kalmıştı. Durum değişmedi.
- **Sınırlar:**
  - Başlık çubuğu, uygulamanın kendi araç çubuğuyla birleşik değil.
  - Başlık metni kesilince yalnızca Windows kısaltıyor.
  - Bunlar native başlık çubuğunu yeniden yazmayı gerektirir. Kullanıcı bu turda bunu istemedi.

## Ölçek ve ekran

| Durum | Sonuç | Açıklama |
|---|---|---|
| %125 gerçek ekran | PASS | Bütün matris, iki monitörde (DISPLAY1 2560×1380, DISPLAY2 1920×1020 iş alanı) |
| %100 gerçek ekran | BLOCKED | Bu turda iki monitör de %125'e ayarlıydı; ayar değiştirmek kullanıcının masaüstünü değiştirir. Yerine `-Dglass.win.uiScale=1.0` ile **zorlanmış** %100 geçişi yapıldı; bu, gerçek bir %100 monitör değildir (Windows başlık çubuğu yine %125 çizilir). |
| %150 / %200 | NOT TESTED | Kullanıcı bu turda istemedi. |
| 1440×900 ve 1600×500, DISPLAY2 üzerinde %125 | NOT TESTED | 1800×1125 ve 2000×625 fiziksel piksel, 1920×1020 iş alanına sığmıyor. Aynı boyutlar DISPLAY1'de PASS. |
| 3440×1440 (OP-11, LO-24, LS-14) | NOT TESTED (gerçek) | Ekran 2560 px; yalnızca ekran dışı görüntüyle doğrulandı. |
| Yüksek karşıtlık (SH-08) | NOT VERIFIED | Yalnızca ekran dışı görüntü. |

## Açık ve kısmi kalan görsel noktalar

Ayrıntılar `bulgular-94.md` dosyasında. Gerçek pencerede görülenler:

- **LO-26:** OOM yönetilen süreçler tablosunda "Status" başlığı %125'te "Stat…" olarak kesiliyor. Sütun genişliği hücre metninden ölçülüyor, başlık metni hesaba katılmıyor. Küçük bir düzeltme ama bu turda yapılmadı.
- **PR-03 ve LO-06:** 1600×500 gibi alçak pencerelerde inceleyicinin sabit eylem parçası sığmıyor. Eylemlere kaydırmayla ulaşılıyor; kenar çizgisi ve kaydırma izi bunu gösteriyor.
- **OP-08:** 720×480 ve %125'te Overview birkaç piksel kayıyor.
- **PR-13:** %125'te gereksiz yatay kaydırma çubuğu. Kesirli DPI test düzeneğinde üretilemiyor.
- **PR-14:** Tablonun dikey kaydırma başparmağı küçük; JavaFX ScrollBarSkin sınırı.
- **1366×768 SIDE düzeninde sonuç satırı:** uzun cümle "…" ile kesiliyor. Tamamı ipucunda; Logs'ta da tam okunuyor.
- **1440×900 dış boyut:** %125'te sahne genişliği 1460'ın altında kalıyor, bu yüzden gezinme ikon rayı oluyor. Adlı çubuk ölçülmüş eşikten sonra geliyor (sahne ≥ 1460); bu bilinçli bir karar (K1).
- **SH-06:** Sayfanın karartması araç çubuğunu ve rayı örtmüyor. Dışarıya ilk tıklama sayfayı kapatıyor ve yutuluyor. Tekerlek sorunu giderildi.

## Doğrulama araçlarıyla ilgili sınırlar

- `packaging/windows/verify-image.ps1` hâlâ eski kenar çubuğunu sürüyor. Bu turda çalıştırılmadı: NOT TESTED. Yerine bu teslimdeki `fix-verify.ps1` kullanıldı.
- "Add to OOM management" tasarım gereği diyalogsuz çalışıyor; seçilen süreci "Notify only" ile ekliyor. Bu ürünün M11 davranışı. Betik düğmeye bastığında bu eylem, yalıtılmış veri klasöründeki geçici `prism-demo` üzerinde gerçekten uygulandı. Başka hiçbir süreçte kontrol ya da limit eylemi yapılmadı.
- "System" filtresi bir koşuda ilk satır olarak gerçek bir sistem sürecini (LenovoVantage) seçti. Yalnızca seçildi; bu süreçte hiçbir eylem yapılmadı.
