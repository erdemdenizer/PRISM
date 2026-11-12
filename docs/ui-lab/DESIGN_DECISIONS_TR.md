# PRISM UI Lab: tasarım kararları

Bu belge, `lab/premium-ui-v2` dalındaki premium UI v2'nin tasarım kararlarını, gerekçelerini ve ürün kurallarından sapmaları anlatır. Yapım sözleşmesi `LAB_DESIGN_SPEC.md`'dir. Ürünün gereksinimleri (`docs/PRODUCT_SPEC.md`) değişmedi: işlev eklenmedi ve çıkarılmadı.

## 1. Yön

- **Disiplin Apple'dan, davranış Windows'tan.** Sakin yüzeyler, sıkı tipografi ve az renk; buna karşın pencere çerçevesi, başlık çubuğu, Snap Layouts, sistem menüsü ve Alt+F4 Windows'un kendi davranışıdır.
- **Koyu tema varsayılan.** İlk açılışta, ayar dosyası yoksa koyu tema seçilir (`AppSettings.FIRST_RUN`, yalnızca laboratuvarda). Açık tema eşdeğer kalitededir; Settings'te Sistem, Açık ve Koyu seçilebilir.
- **Süsleme yok.** Gradyan, cam efekti, parıltı ve emoji kullanılmadı. Gölge yalnızca yüzen katmanlarda (menü, ipucu) var; canlı veri gösteren kapsayıcılarda gölge yok, çünkü her güncellemede efekt yeniden çizilir.

## 2. Başlık çubuğu

Ayrıntılar ve kabul matrisi `TITLEBAR_VALIDATION_TR.md` belgesindedir.

- **Seçilen yaklaşım:** pencere `DECORATED` kalır, Windows 11'de DWM öznitelikleri uygulanır: başlık rengi, metin rengi, kenarlık rengi ve koyu mod. Başlık, rayla ve araç çubuğu bandıyla aynı renktedir; koyu temada `#16181D`, açık temada `#EEF0F3`.
- **Reddedilenler:**
  - `UNDECORATED` ile çizilmiş sahte düğmeler: Snap, sistem menüsü ve erişilebilirlik kaybolur.
  - `EXTENDED`/HeaderBar: JavaFX 25'te önizleme aşamasındadır, uyarı üretir ve iletişim pencerelerini kapsamaz.
  - `UNIFIED`: bir kazanç getirmez.
- **Yüksek karşıtlık:** başlık sistem renklerine bırakılır. İçerik de sistem renklerine eşlenir (`hc.css`, `HighContrast`).
- **Windows 10 ve eski sürümler:** renklendirme yapılmaz, sistem çerçevesi kalır ve bu bir kez günlüğe yazılır.

## 3. Gezinme: uyarlanır (sahip kararı K1)

- Aynı liste, pencere genişliğine göre iki biçim alır (`Sidebar.Mode`); biçim değişirken düğüm yeniden kurulmaz, yalnızca sözde sınıflar ve girdilerin içerik gösterimi değişir. **Her genişlikte adlar görünür** (sahip kararı, H turu sonrası: "ad yalnızca üzerine gelince çıkıyorsa kullanıcı anlamaz"):
  - **Geniş (sahne ≥ 1460 px):** 200 px adlı kenar çubuğu. Simge 20 px, ad 13 px IBM Plex (seçili girdi SemiBold), satır 36 px.
  - **Orta ve dar (< 1460 px):** 80 px adlı ray. Simgenin altında adı, 11 px IBM Plex (seçili girdi SemiBold). Girdi 70 × 54 px; alçak pencerede (< 560 px) 70 × 44 px. Fare ya da klavye bir girdideyken rayın sağında ad ve kısayol etiketi de çıkar.
  - Dar penceredeki çekmece, menü düğmesi ve Ctrl+B kaldırıldı: dar pencerede de aynı adlı ray soldadır.
- **80 px ölçülerek bulundu.** En geniş ad "Performance" Plex SemiBold 11 px'te 66,0 px (yazı tipinin karakter genişliklerinden). Girdi 70 px (yan dolgu yok), liste solda 4, sağda 5 px, kılcal çizgi 1 px: 80 px. 1366×768 (sahne 1352) penceresinde sayfa 1272 px kalır; yan inceleyici 1260'tan başladığı için tablonun yanında kalır. 84 px denendi: kesirli ölçeklerde (%125, %150) Processes sütunları 0,4 px taştı ve tablo yana kaydı (`ProcessTableScaleTest`).
- **En küçük pencerede bedeli.** 720×480'de ray sayfadan 80 px alır. Overview metrikleri bu yüzden 560 px'ten dar alanda tek sütuna geçer (önce 640); 602 px'te ikişerli kalır ve sayfa kaydırmadan sığar.
- **1460 ölçülerek bulundu.** 200 px kenar çubuğu, G turundaki 60 px raydan 140 px geniştir. Yan inceleyici 1320'de rayla birlikte sayfaya 1260 px bırakır; 1320 + 140 = 1460'tan itibaren adlı çubukla da aynı alan kalır. Böylece hiçbir pencere adlar yüzünden daha kötü bir inceleyici düzenine düşmez. 1366×768 (sahne 1352) ve 1440×900 (sahne 1426) rayda kalır; 1600×500 (sahne 1586) ve 1920×1032 (sahne 1906) adlı çubuğu alır.
- **200 px ölçüldü.** En geniş ad "Performance" Plex SemiBold 13 px'te 78,1 px, "Ctrl+7" Plex 12 px'te 33,7 px. Satır: 10 + simge 20 + 12 + ad + 12 + kısayol + 10 = 176 px; liste iki yanda 8 px bırakır.
- Seçili girdi: hafif renkli zemin, vurgu renginde simge ve 3 px vurgu çubuğu. Çubuk zeminle en az 3:1 karşıtlıktadır (`ShellNavigationLayoutSceneTest`).
- Her girdinin erişilebilir adı sayfanın adıdır. OOM Guard girdisinde rozet varsa, rozetin durumu da ada eklenir. Kısayolu erişilebilir yardım metnidir.
- Ctrl+1…7 sayfalar arasında geçiş yapar (K6). Alt (AltGr) ya da Shift basılıyken kısayol çalışmaz. Kısayol metni sistemin yazdığı gibidir ("Ctrl+1"). Rayda ad etiketinde, adlı çubukta fare ya da klavye odağı satırdayken satırın sağ ucunda soluk renkte görünür.

## 4. Tasarım sistemi

### Renk belirteçleri (özet)

| Rol | Koyu | Açık |
|---|---|---|
| Krom (başlık, ray, bant) | #16181D | #EEF0F3 |
| Tuval | #1B1E24 | #F6F7F9 |
| Yüzey / yükseltilmiş yüzey | #20242B / #262B33 | #FFFFFF / #FFFFFF |
| Metin / soluk / pasif | #ECEEF2 / #A9B1BE / #7A8292 | #15181E / #4A5262 / #828A97 |
| Vurgu | #7C8CFF | #4F5BD5 |
| Hata / uyarı | #FF8585 / #F2B34B | #B0222A / #8A5100 |
| Veri: CPU, bellek, disk, ağ | #7C8CFF, #3CCFB4, #F07AA6 / #C8648C, #5BC8F5 / #4A9FCB | #4F5BD5, #0E7A66, #B0306A / #8C2A56, #0B6E99 / #3A7EA3 |

- Tüm metin çiftleri en az 4.5:1 karşıtlık sağlar. Kontrol sınırları ve veri renkleri en az 3:1 sağlar. Tablo `CONTRAST.md` dosyasındadır; `lab-docs/contrast/tokens.py` tabloyu `app.css` dosyasından yeniden üretir ve geçmeyen bir çift varsa 1 koduyla çıkar.
- Düğmeler yumuşak bir kenarla çizilir (`-srm-control-border`). 3:1'lik kenar (`-srm-control-edge`) metin alanlarında, onay kutularında ve radyo düğmelerinde kalır, çünkü WCAG 1.4.11 bunlar için sınır ister.
- İkinci veri serisi (disk yazma, ağ gönderme) kesikli çizilir; rengi ayırt edemeyen biri de serileri ayırabilir.

### Tipografi

- IBM Plex Sans uygulamayla birlikte gelir. Rakamları her ağırlıkta tablo hizalıdır: her rakam 0,6 em genişliğindedir (paketteki dosyalardan ölçüldü). JavaFX'te `font-feature-settings` yoktur; burada gerekmez.
- JavaFX 25, Medium ve SemiBold yüzlerini "IBM Plex Sans Medm" ve "IBM Plex Sans SmBld" aile adlarıyla kaydeder. Bu yüzden roller yüzü aile adıyla seçer; `-fx-font-weight` kullanılmaz.
- **Yazı ölçeği (H turu, SH-09).** Altı boyut var ve her birinin tek bir işi var. Önceden dokuz boyut vardı: 11, 12, 13, 14, 15, 18, 19, 22 ve 30.

| Boyut | Rol | Nerede |
|---|---|---|
| 12 px | Altyazı ve küçük metin | Altyazılar, sütun başlıkları (Medium), sayaçlar, ipuçları, çipler |
| 13 px | Gövde | Bütün metin, hücreler, denetimler (Medium); inceleyici bölüm başlıkları ve Limits düzenleyicisindeki durum satırı (SemiBold) |
| 15 px | Bölüm | Panel ve bölüm başlıkları, seçili sürecin adı (SemiBold); OOM durum bandındaki değerler |
| 18 px | Sıkı değer | Alçak pencerede sayfa başlığı (öneri: `shell.css` 19 → 18, koordinatör uygular); sıkı OOM bandındaki rakam |
| 22 px | Sayfa başlığı | Sayfa başlığı; OOM sayfasının rakamı; alçak pencerede büyük okuma değeri |
| 30 px | Büyük okuma değeri | Bir metrik panelinin tek güncel değeri |

- Ölçeğin dışında kalanlar:
  - Grafiklerin eksen yazıları 11 px'tir, ama metin düğümü değil, Canvas üzerine çizilir (`graph.css`, grafik alanı).
  - Alçak penceredeki seri açıklaması da 11 px'tir (`shell.css`). 12 px'e çekilmesi koordinatöre önerildi; satırın sığdığı doğrulanmadı.
- Metin düğümlerinin hiçbiri 12 px'in altında değildir. UI_GUIDE'ın en küçük boyutu 11 px'tir.
- Sayılar tablolarda ve olgu satırlarında sağa yaslıdır. Birimi olan bir sayının birimi sabit genişlikte bir kutudadır; böylece rakamlar alt alta hizalı kalır.

### Düğmeler (H turu)

Dört tür var. Hepsi 32 px yüksekliğinde (alçak pencerede ve dar inceleyicide 30 px), 6 px köşeli ve 13 px Medium yazılıdır. Simge 16 px'tir ve metinden 6 px uzaktadır.

| Tür | Dinlenme | Üzerinde | Basılı | Odak | Pasif | Nerede |
|---|---|---|---|---|---|---|
| Birincil | Vurgu dolgusu, beyaz metin, 1 px üst ışık | Dolgu bir ton açılır | Dolgu bir ton koyulaşır, ışık kalkar | 2 px halka, 2 px dışarıda | Denetim yüzeyi, pasif metin | Bir diyalogda tek varsayılan eylem (Set, Use, Watch) |
| İkincil | Denetim yüzeyi, yumuşak kenar, üst ışık | Yüzey açılır, simge metin rengine geçer | Yüzey koyulaşır, ışık kalkar | Aynı halka | Denetim yüzeyi, ince çizgi, pasif metin; nedeni ekranda yazılı | Varsayılan görünüm: Processes, Limits ve OOM Guard eylemleri |
| Sessiz | Yüzey yok | Denetimin üzerine gelme yüzeyi; sayfa başlığı bandında gezinmenin üzerine gelme rengi | Basılı yüzey | Aynı halka | Yüzey yok | Yalnızca simgeli düğmeler: sayfanın kapatma düğmesi, katlanmış filtre |
| Yıkıcı | İkincil anatomi; metin ve simge tehlike renginde | İkincil gibi | İkincil gibi | Aynı halka | İkincilin pasif hâli gibi | İnceleyicide Force kill…; onayda End task, Force kill ve Allow termination |

- Onay diyaloğunda varsayılan düğme Cancel'dır ve 2 px vurgu kenarı taşır. Geri alınamayan bir eylemin düğmesinde (Force kill, sonlandırmaya izin) 1 px tehlike kenarı da vardır.
- Meşgul durumda düğme pasifleşir ve ne yaptığını sözle söyler. Dönen simge ya da sonsuz animasyon yoktur.
- Karşıtlık (açık / koyu, hepsi `CONTRAST.md`'de):
  - Düğme metni denetim yüzeyinde 16,16 / 11,08.
  - Birincil düğme metni 5,54 / 5,19.
  - Force kill metni 6,14 / 5,48; üzerine gelince 5,82 / 4,88.
  - Odak halkası tuvalde 5,17 / 6,59; seçili satırda 4,56 / 4,32.
- Sessiz düğmenin üzerine gelme yüzeyi H turunda değişti:
  - Eskiden satırın üzerine gelme rengiydi. Koyu yükseltilmiş yüzeyde 1,05:1'de kaldığı için sayfanın kapatma düğmesinde görünmüyordu. Şimdi denetimin üzerine gelme yüzeyidir (1,24:1).
  - Sayfa başlığı bandında ise gezinmenin üzerine gelme rengidir (1,14 / 1,30). Açık temada denetimin üzerine gelme rengi bandın kendisiyle neredeyse aynıdır (1,02:1).

### Simgeler

- Tek bir aile kullanılır: Lucide alt kümesi (`ui.icon.Icon`). Yeni bağımlılık yoktur.
- Boyutlar:
  - 10 px: sıralama işareti;
  - 12 px: menü düğmesinin oku ve alan içindeki temizle düğmesi;
  - 16 px: denetimlerdeki simgeler;
  - 20 px: raydaki simgeler.
- Çizgi kalınlığı ekran biriminde sabittir: 12 px ve altında 1,25 px, 16 px'te 1,5 px, 20 px'te 1,6 px.
- H turunda sayfanın kapatma düğmesindeki X 14 px'ten 16 px'e çıktı; 14 px ölçeğin dışındaydı.
- Sıralama işareti 10 px'tir (H turu, PR-H1). 12 px başlık metninin yanında durur; bu oran, 13 px metnin yanındaki 12 px menü okuyla aynıdır. Çizgileri kutunun dörtte birini iki yanda boş bıraktığı için 2 px boşluk göze yaklaşık 4 px görünür.
- Yalnızca simgeden oluşan her denetimin bir ipucu ve erişilebilir adı vardır: kapatma (Close details), katlanmış filtre (Filter processes), filtreyi temizle (Clear filter), çekmece düğmesi (Show navigation). Kritik eylemlerin hiçbiri yalnızca simgeli değildir.

### Yüzeyler

- Derinlik ton adımlarından, ince çizgilerden ve 1 px üst ışıktan gelir. Gölge yalnızca menülerde ve açılır pencerelerde vardır. Canlı veri tutan ya da büyük kapsayıcılarda gölge ve bulanıklık yoktur.
- `-srm-edge` (H turu, PR-11), tuval üzerinde duran bir yüzeyin kenarıdır. Panel, tablo çerçevesi ve sayfa bu kenarı kullanır.
  - Açık temada değişmedi: `#e1e4e9`, tuvalde 1,19:1.
  - Koyu temada `#2d3138`'den `#363a43`'e çıktı; tuvaldeki karşıtlık 1,28'den 1,47'ye yükseldi.
  - Kenar sessizdir ve 3:1 aranmaz; grubu yüzeyin kendisi belirtir.
  - Grafik ızgarası `-srm-border`'da kaldı (grafik alanı).
- Panel dolgusu 14/16'dan 12/16'ya indi (H turu, OP-14). Alçak penceredeki 10/14 için `shell.css` önerisi koordinatöre verildi.

### Bileşen durumları

- Düğme, menü düğmesi, geçiş düğmesi, segment, onay kutusu, radyo, metin alanı ve tablo satırı için şu durumlar tanımlıdır: normal, üzerinde, basılı, odakta (2 px halka), seçili, pasif.
- Pasif bir düğme görünür kalır ve neden pasif olduğu ekranda yazılı durur (CAP-2, `DisabledReasons`).

### Hareket

| Tür | Süre |
|---|---|
| Üzerine gelme | 100–150 ms |
| Menü | 120–180 ms |
| Panel / sayfa | 160–240 ms |
| Sayfa geçişi | 120–200 ms |

- Sistem "hareketi azalt" isteğinde bulunduğunda tüm Java geçişleri sıfır süreyle çalışır; CSS geçişleri `prefers-reduced-motion: no-preference` koşulu altındadır.
- Veri asla canlandırılmaz. Grafikler Canvas üzerine çizilir; her örnek için ayrı bir düğüm yoktur.

## 5. Ekranlar

- **Overview.** 1100 px'ten itibaren dört metrik yan yana durur; her biri en çok 520 px genişliğindedir. Altta iki dengeli panel vardır: sistem bilgileri ve Prism bilgileri.
- **Processes.** Satır yüksekliği 30 px, alçak pencerede 28 px. Name sütunu en çok 480 px; kalan yeri State alır. İnceleyicinin yeri pencere boyutuna göre değişir (§6). Ctrl+F filtreye gider.
- **Performance.** Sekmeler sayfa araç çubuğundadır. Çift serili panellerde değerler sığmadığında başlığın altına iner.
- **Limits.** Processes ile aynı inceleyici kuralı geçerlidir. Satır çift tıklamayla açılır. Pasif eylemlerin nedenleri alttaki çizgide yazılıdır.
- **OOM Guard.** Durum bandında "Automatic intervention is off" etiketi görünür (güvenli varsayılan). Eşikler ve uyarılar kesilmez, en çok iki satıra kaydırılır.
- **Logs.** Filtre başlık satırındadır. Önem derecesine göre filtre eklenmedi, çünkü bu yeni bir işlev olurdu.
- **Settings.** Satırlar gruplanmıştır; tema seçimi segmentli bir denetimdir.
- **Araç çubuğu.** Sayfa başlığı ve sayfanın araçları kaydırılan alanın dışında, sabit bir banttadır: 52 px, alçak pencerede 40 px.

## 6. Duyarlı düzen

Kırılım noktalarının tamamı `Breakpoints.java` içindedir:

| Kırılım | Değer | Sonuç |
|---|---|---|
| Dar | < 820 | Ray yerine çekmece |
| Adlı gezinme | ≥ 1460 | 60 px ray yerine 200 px adlı kenar çubuğu (`LABELLED_NAV_FROM = SIDE_FROM + 140`) |
| Geniş | ≥ 1100 | 24 px kenar boşluğu, Overview'de dört metrik yan yana |
| Yan inceleyici | ≥ 1320 | İnceleyici tablonun yanında (her yükseklikte) |
| Alt inceleyici | ≥ 1100 ve yükseklik ≥ 560 | İnceleyici tablonun altında, bölümleri sütunlar halinde; en çok sayfa yüksekliğinin %40'ı, fazlası kendi içinde kayar |
| Sayfa | diğer durumlar | İnceleyici sağdan kayan bir sayfa olarak açılır |
| Alçak | < 560 yükseklik | Sıkı ritim, 28 px satır |
| Ultra geniş | ≥ 2200 | Metin blokları okunabilir genişlikte tutulur |

- **1320 ve 1100 ölçülerek belirlendi.** Spec 1280 ve 900 diyordu. Ancak tablonun 866 px'lik sütunları, 320 px inceleyici, 12 px ayraç, 60 px ray ve 48 px kenar boşluğu birlikte 1306 px eder; 1280'de sayfa yana kayardı. Bunu `ProcessTableWidthTest` ve `BreakpointAgreementSceneTest` doğrular.
- **Alt inceleyici %40 ile sınırlıdır** (`6075cd1`). Sınırsızken 1100×700'de tabloya yalnızca beş satır kalıyordu. Bir eylemin sonuç satırı inceleyicinin görünmeyen kısmına düşerse görünür alana kaydırılır (`ProcessActionsSceneTest`).
- **Rayın ad etiketi işaretçinin altına hiç girmez** (SH-01, SH-03). Eskiden standart ipucu imlecin 10 px sağında, 7 px altında açılıyordu; gölgesi popup penceresini büyütüp imleci örtünce girdi hover'ını kaybediyor, ipucu duran imleç altında yanıp sönüyordu. Şimdi tek bir etiket (`RailName`) rayın sağ kenarından 8 px ötede, girdiye ortalanmış durur; gölgesi yoktur, ekrana sığdırılmak için kaydırılmaz. Fare 500 ms durunca görünür, girdiden çıkınca gider; bir girdiden hemen ötekine geçince beklemeden gelir. Klavyede yalnızca Tab ya da ok tuşuyla gelinince görünür; Ctrl+1…7 ad açmaz. Pencere taşındığında, boyutu değiştiğinde ya da odağını kaybettiğinde gizlenir. Uygulamadaki bütün ipuçlarının gölgesi aynı nedenle kaldırıldı.
- **Sayfa ortak bir bileşendir** (`SheetOverlay`). Esc, sayfa dışına basma, odağın sayfadan çıkması ve pencere kısayolları sayfayı kapatır. Tab sayfanın içinde döner ve kapanınca odak sayfayı açan denetime döner.

## 7. Sapmalar (UI_GUIDE ve spec ile çelişenler)

| Konu | UI_GUIDE / spec | Laboratuvar | Gerekçe |
|---|---|---|---|
| Gezinme | 176 px kenar çubuğu | ≥ 1460 px 200 px adlı çubuk, altında her genişlikte 80 px adlı ray (simge üstte, ad altta) | K1; 200 px kısayolun da sığması için, 1460 inceleyici ölçüsünden; çekmece H turu sonrası sahip kararıyla kaldırıldı |
| Tablo satırı | UI_GUIDE değeri | 30 px (alçak pencerede 28 px) | 34 px denendi; görünür satır sayısı 13'ten 11'e düştüğü için 30 px'e çekildi |
| Name sütunu tavanı | 520 px | 480 px | Sayılar isimden kopmasın diye (eleştiri M5) |
| Tablo ölçüleri (LS-15) | Satır 28 px, hücre dolgusu 8 px, köşe yok ya da en az | Satır 30 px (alçak pencerede 28), başlık 32 px, hücre dolgusu 10 px, çerçeve köşesi 10 px | Dolgu, metin alanlarının 10 px iç dolgusuyla aynıdır. Sütun ölçümü (`TextWidths.CELL_PADDING`) ve bütün sığma testleri bu değere dayanır. 8 ya da 12 px'e çekmek her tablonun her sütununu değiştirirdi; bu yüzden H turunda değiştirilmedi. |
| Sıralama işareti (PR-H1) | 12 px ok, metinden 4 px | 10 px ok, metinden 2 px. Sıralanan sütunun başlığı, ok tarafındaki dolgusunu oka verir. | 12 px ok ve 4 px boşlukla Priority sütunu 4 px genişliyor, 1366×768 %150'de tablo yana kayıyordu. 10 px ile hiçbir sütun genişlemedi. |
| Force kill rengi (PR-16) | "Force kill is told apart by its place, its words and its confirmation, never by color" | İnceleyicideki Force kill… ikincil anatomidedir; metni ve simgesi tehlike rengindedir (`-srm-error`, UI_GUIDE'ın hata belirteci). | Renk tek başına anlam taşımaz: yer, sözcük ve onay aynen duruyor. Renk yalnızca geri alınamayan eylemi End task'tan bir adım ağır gösterir; Apple HIG ve Fluent 2'deki yıkıcı rol de böyledir. Metin her durumda 4,5:1'i geçer (açık 6,14, koyu 5,48; üzerine gelince 5,82 / 4,88). Yeni bir kırmızı eklenmedi; renk kılavuzun belirtecine bağlıdır. |
| İnceleyici kırılımları | spec: 1280 / 900 | 1320 / 1100 | Ölçüm; yukarıda açıklandı |
| İlk açılış teması | ADR-0014: Sistem | Koyu | Laboratuvar kararı; ürüne taşınırsa ADR gerekir |
| Overview durum cümlesi | lab spec önerir | Eklenmedi | PRODUCT_SPEC §11 / UI-1 ile çelişiyor; karar proje sahibinin |
| Native çağrılar | FX iş parçacığında yapılmaz | Başlık çubuğu çağrıları FX iş parçacığında | Pencerenin sahibi bu iş parçacığıdır; ADR-A önerisi |
| Veri klasörleri ve kilit | Tek Prism (ADR-0013) | `-UILab` klasörleri | Yalıtım; iki kopya aynı anda çalıştırılmamalı (README_TR) |

## 8. Bilinen açıklar

- **900–1099 px'te tablo yana kayar.** Bu genişlikte inceleyici sayfa olarak açılır ve tablo 808 px'e karşı 954 px sütun gerektirir. Eski 176 px kenar çubuğunda da kayıyordu.
- **3440×1440'ta Overview 2×2 kalır.** En-boy kuralı, metriklerin dördünün yan yana dizilmesine baskın gelir; grafikler çok geniş görünür.
- **Ekran okuyucu duyuruları yok.** JavaFX 25'te canlı bölge desteği yoktur. Gerekli native köprü ADR-E olarak önerildi.
- **Ray, alt düğüm listesinin sonuna geçiyor ama Tab sırası etkilenmiyor.** Dar pencereden geniş pencereye dönünce ray, BorderPane'in alt düğüm listesinde sona taşınıyor. `toBack()` ile öne almak BorderPane'in sol düğümü düşürmesine yol açtığından düzeltme yapılmadı. Bir test Tab sırasının doğru kaldığını ve rayın hâlâ sol düğüm olduğunu doğruluyor.
- **Kırılım geçişleri pahalı.** Her adımda düzen modunun değiştiği 40 adımlık boyutlandırmada laboratuvar baseline'dan yaklaşık 2–5 kat fazla CPU harcar (FX iş parçacığında adım başına ≈37 ms). Aynı düzen içinde sürüklemede fark yoktur. Öneri: kırılımda düğümleri yeniden kurmak yerine görünürlüklerini değiştirmek (`lab-evidence/F-final/PERFORMANCE_TR.md`).
- **10 dakikanın sonunda private bytes 424 MB'tır** (baseline 365 MB). Heap büyümedi; daha uzun koşu yapılmadı.
- **Hiçbiri gerçekte denenmedi:** Narrator/NVDA, gerçek yüksek karşıtlık teması, Linux, macOS.
