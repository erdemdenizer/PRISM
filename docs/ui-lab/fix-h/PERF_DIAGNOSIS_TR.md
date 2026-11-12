# Yeniden boyutlandırma maliyeti: teşhis (tur H, perf)

## Kısa sonuç

- Gerçek pencerede fark (G turu, %125, resize-40, Processes): süreç CPU'su 4,06 s / 2,98 s, FX iş parçacığı 0,78 s / 0,31 s (UI-LAB / ürün temeli).
- Ekran dışı yoklama, gerçek pencereyle aynı sahne boyutlarında aynı farkı veriyor. Fark neredeyse tamamen **düzen eşiği geçişlerinden** ve **Processes sayfasından** geliyor. Tek düzen içinde boyutlandırmada UI-LAB ürünle aynı.
- **En büyük neden:** her eşik geçişinde tablo ve inceleyici sahneden çıkıp geri giriyordu. Satırlar ve hücreler CSS'lerini baştan kuruyordu. 8824ada'da Processes geçiş maliyetinin yaklaşık 2/3'ü buydu.
  - Bunu inceleyici ajanı `b568e3b` ile düzeltti (`ProcessDock`, düğüm taşınmıyor). Bu değişiklik `lab/h-merged` (6955ae0) içinde.
  - Benim bağımsız prototipim de aynı yerde aynı kazancı verdi (−%41). İkinci kez yapılmadı ve commit edilmedi.
- **Birleşik sürümde kalan fark** (`lab/h-merged`, Processes, eşik geçişi): ürün temeli ~313 ms, merged ~680 ms (FX CPU / 40 adım).
  - Kök sözde-sınıflar (`.shell:low/wide/narrow/ultrawide`): ~195 ms.
    - Bunun ~150 ms'si `low`. Bu bilinçli bir tasarım: alçak pencerede 28 px satır ve 30 px kontroller (UI-8).
    - `wide/narrow/ultrawide`: ~50 ms.
  - Sayfaların kendi işi: ~200 ms (inceleyici yerleşimi, sayfa dinleyicileri).
- **Kabuk tarafında commit edilen bir kod düzeltmesi yok.** Gezinmeyi (rail) sahneden çıkarmak yerine gizleme denendi, ölçülebilir kazanç vermedi (aşağıda 5). Kanıtı olmayan bir değişiklik eklenmedi.
- Teslim edilenler:
  - Yeniden koşulabilir yoklama: `BreakpointCrossingProbe` ve `lab-spikes/perf/breakpoint-probe.sh`.
  - Bu teşhis belgesi.
- Gerçek pencere ölçümü yapılmadı (brief gereği). Koordinatör boş makinede yapmalı.

## Nasıl ölçüldü

### Yoklama

`src/test/java/prism/srm/ui/shell/BreakpointCrossingProbe.java` (etiket `ui-snapshot`; adı `*Probe` olduğu için hiçbir derlemede koşmaz).

- **Tek sahne.** Dolu bir Shell (`KeyboardRoutesSceneTest.populated()`: 40 süreç, limitler, OOM, etkinlik) tek bir sahnede kalıyor. Yalnızca kökün boyutu değişiyor. G turu yoklaması her adımda yeni bir Scene kuruyordu.
- **Boyutlar.** Gerçek "resize-40" ölçümünün %125 sahne boyutları kullanıldı: 1352×730,4 → 1085,6×662,4 → 785,6×562,4 → 1425,6×862,4 → 705,6×442,4, döngüyle 40 adım. Her adım en az bir eşiği geçiyor.
  - "still" dizisi aynı düzende kalır: 1330–1426 genişlik, rail, inceleyici yanda.
- **Adımın evreleri:**
  - `resize`: kök yeniden boyutlanır ve boyut dinleyicileri çalışır. Gerçek pencerede de bu iş pulse'tan önce ve eşzamanlı yapılır.
  - `pulse`: 1 piksellik bir düğümün `snapshot`'ı. Bu, sahnenin kendi CSS geçişini ve layout geçişini çalıştırır, sahneyi eşitler (sync). Pulse gibi yalnızca kirli düğümleri işler; çizdiği alan 1 pikseldir.
  - `more`: iki ek geçiş, yani bir sonraki pulse'a kalan iş.
  - İsteğe bağlı `render`: tüm sahnenin yazılımla çizimi. Bu bir üst sınırdır; gerçek pencere yalnızca kirli bölgeyi çizer.
- **Ölçü.** 40 adımda FX iş parçacığının CPU süresi (`ThreadMXBean`); her koşu 3 ısınma turu ve 3 ölçüm turu, koşu medyanı alınır. Bir JVM'de bir ağaç ölçülür. Ağaçlar dönüşümlü koşulur.
- **Ek diziler:**
  - `count`: adım başına yeni yapılan ve ebeveyn değiştiren düğümler.
  - `dirty`: ilk pulse'tan sonra layout bekleyen düğümler.
  - `parts`: tek mekanizmanın tek boyutta maliyeti (kök sözde-sınıfı aç-kapa, tabloyu ya da gezinmeyi çıkar-tak) ve sonraki pulse'a bıraktığı düğüm sayısı.
- **Profil.** JFR, `method-profiling=max`, `jdk.ExecutionSample`. Dahil edilen (inclusive) örnek sayıları `jfr print` çıktısından sayıldı.

### Yöntem uyarısı (G turu yoklaması)

- `Node.applyCss()`, çağrıldığı düğümün altındaki **her düğüme** CSS UPDATE uygulatır. Bir pulse bunu yapmaz.
- G yoklaması ve `SnapshotHarness` her geçişte `shell.applyCss()` çağırıyordu. Bu yüzden boş bir pulse bile 12–21 ms görünüyordu; doğru pulse taklidiyle 1,3–4 ms.
- G'deki "hep aynı boyut ~1050–1300 ms" sayıları bu nedenle büyük çıkmıştı. Yön doğruydu, oranlar değil.

### Makine

- Diğer üç ajan aynı anda derleme yaptı. Bütün karşılaştırmalar aynı zaman aralığında, dönüşümlü koşuldu.
- Sayılar mutlak değil, göreli okunmalı. Aynı ağacın koşudan koşuya aralığı %20–40 olabildi.

## Ölçümler

Birim: 40 adımda FX iş parçacığı CPU süresi (ms). Köşeli parantezde dönüşümlü koşuların medyanları, önce medyan.

### 1. 8824ada (tur başı), Processes

| Ağaç | crossing | still |
|---|---|---|
| ürün temeli 19b7fbe | 313 [266 297 313 375 391 313 422 375 406] | 297 [266 313 297] |
| UI-LAB 8824ada | 1688 [1703 1453 1609 1750 2406 1656 1750 1688 1594] | 516 [578 578 484 516 500 484 516] |

- Tek düzen içinde ("still") fark küçük. Fark eşik geçişlerinde.
- Diğer sayfalarda crossing medyanları:

| Sayfa | ürün temeli | 8824ada |
|---|---|---|
| Limits | 125–234 | 500–578 |
| Overview | 16–47 | 109–141 |
| Performance | 16–31 | 63–94 |

### 2. Nedenler, 8824ada (JFR, Processes crossing; 7 282 adım örneği = %100)

| Sıra | Neden | Pay | Dosya ve sahip |
|---|---|---|---|
| 1 | Tablo sahneden çıkıp giriyor. `VirtualFlow.setCellIndex` → `reapplyCss` → `createStyleHelper`: satır ve hücre stilleri baştan kuruluyor. | %52 | `ProcessesPage.arrange`: `split.getItems().clear()` ve `getChildren().setAll(...)`, her geçişte. İnceleyici ajanı. |
| 2 | Aynı taşımanın eşzamanlı kısmı (`setScenes`, `SplitPaneSkin` içerik yıkımı) | %17 | aynı yer |
| 3 | CSS UPDATE yürüyüşü (`transitionToState`): kök sözde-sınıfları ve inceleyici sözde-sınıfları | %26 | `Shell.adapt` (kabuk) ve `ProcessDetailsPane.setNarrow` |
| 4 | `ProcessDetailsPane.arrange`: dar/geniş geçişinde ayrıntı düğümleri yeniden diziliyor | %3 | İnceleyici ajanı |

- Ölçülen mekanizma maliyetleri (`parts`, tek değişim ve pulse):
  - tabloyu çıkar-tak: 12–15 ms;
  - `:low` aç-kapa: 15–28 ms;
  - `:wide` aç-kapa: 11–21 ms;
  - yalnız yürüyüş (`:ultrawide`, Processes'te hiçbir kural etkilemiyor): 5–7 ms, boş pulse 2 ms.
  - Ürün temelinde bu sözde-sınıflara bağlı kural yok; aynı değişimler orada boş pulse kadar (1,4–2,3 ms).

### 3. Prototip, yalnız ölçüm için (commit edilmedi)

- Tablo hep split içinde kaldı, sayfa çocukları `setAll` yerine yalnızca değişen düğüm taşınarak güncellendi. Limits'te `InspectorLayout.setMode` ve `LimitsPage.arrange` için de aynısı yapıldı.
- Processes crossing: 8824ada 1750 [1750 2406 1656] → prototip 1031 [1141 1031 922] (**−%41**). Ürün temeli aynı pencerede 375.
- Limits crossing: 547 → 422.
- "still" dizisinde gerileme yok: 8824ada [516 500 484 516], prototip [531 500 516 469].
- İnceleyici ajanının `b568e3b` değişikliği aynı nedeni daha kapsamlı çözüyor (ayrıntı paneli de taşınmıyor). Prototip atıldı; bu ağaçta iz yok.

### 4. Birleşik sürüm lab/h-merged (6955ae0), dönüşümlü, iki ayrı pencere

| Sayfa | ürün temeli | merged |
|---|---|---|
| Processes crossing | 313 [328 250 281 281 406 313 313 328] | 680 [719 672 531 609 688 641 734 938] |
| Limits crossing | 133 [109 156 125 141] | 282 [328 266 250 297] |
| Overview crossing | 63 [31 78 63] | 219 [219 141 219] |
| Performance crossing | 16 [16 16 47] | 63 [63 63 94] |
| Overview / Performance still | 16–47 | 16–63 (aynı) |

- Kalan farkın dağılımı. Processes crossing, aynı pencerede, 4 koşu. Kök sözde-sınıflar yalnız ölçüm için kapatıldı:

| Ağaç | crossing | Not |
|---|---|---|
| merged | 711 [688 641 734 938] | |
| merged, `wide/narrow/ultrawide` kapalı (`low` açık) | 664 [672 625 719 656] | |
| merged, kök sözde-sınıfların hepsi kapalı | 516 [516 500 578 516] | |
| ürün temeli | 320 [406 313 313 328] | |

- Okuma:
  - Kök sözde-sınıflar ~195 ms; bunun ~150 ms'si `low`, ~50 ms'si `wide/narrow/ultrawide`.
  - Kalan ~200 ms sayfaların işi.
- `parts` (merged, Processes):
  - `:low` aç-kapa 21–25 ms; **184 düğümü bir sonraki pulse'a bırakıyor**. `-fx-fixed-cell-size: 28` değişince satırlar ve hücreler ikinci kez yerleşiyor.
  - `:wide` 15–20 ms (22 düğüm), `:narrow` 8 ms, yalnız yürüyüş (`:ultrawide`) 9 ms; boş pulse 3–4 ms.
- `dirty` (merged): her adım işin bir kısmını sonraki pulse'a bırakıyor. Hücreler (`HBox quantity`, `NumberCell`) yalnızca kök sözde-sınıflar açıkken kalıyor; kapatınca ürün temeli kadar.

### 5. Gezinme (rail) gizle/göster denemesi (kabuk, commit edilmedi)

- **Ne denendi:** dar pencerede `setLeft(null)` ve geniş pencerede `setLeft(sidebar)` yerine, rail hep sol düğüm olarak kaldı. Dar pencerede `visible=false, managed=false` yapıldı. Menü düğmesi hep çocuktu, yalnızca görünürlüğü değişti. Çekmece aynı düğümü gösterdi.
- **Processes crossing:**

| Ölçüm | Önce | Sonra |
|---|---|---|
| 8824ada + prototip üstünde | 953 [844 953 1156 953 906] | 906 [875 984 938 906 906] |
| merged üstünde | 640 [719 672 531 609] | 610 [625 719 594 578] |
| Limits, merged üstünde | aynı | aynı |

- Aralıklar örtüşüyor; kazanç kanıtlanamadı.
- Yalnız eşzamanlı `resize` evresi adım başına 0,32 ms'den 0,05 ms'ye indi: 40 adımda ~11 ms, gürültünün altında.
- `parts`ta "gezinmeyi çıkar-tak" ürün temelinde de 11 ms. Bu maliyet düğüm taşımasından değil, sayfanın genişliğinin değişmesinden geliyor; o da kaçınılmaz.
- Testlerde yapı beklentileri (`getLeft()`, "navigation shell'in çocuğu değil") değişmek zorunda kalacaktı. Bu yüzden değişiklik eklenmedi.

## Kalan maliyet: kimin dosyasında

### Kabuk ve gezinme (perf alanı)

- **`wide/narrow/ultrawide` kök sözde-sınıfları:** ~50 ms / 40 adım (merged farkının ~%14'ü).
  - Neden: `Shell.adapt` sözde-sınıfı `Shell` köküne koyuyor. JavaFX bu durumda sahnedeki her düğümde CSS UPDATE yürütüyor (Processes'te ~5–6 ms). Tablo hücreleri bu kurallardan etkilenmese de dolaşılıyor.
  - Hedef kurallar yalnızca `.page`, `.page-header`, `.page-toolbar`, `.page > ...` ve `.logs-detail-message`. `.page` tablonun atası olduğu için kapsamı daraltmak yürüyüşü kısaltmıyor; sayfa kenar boşluğunu CSS dışına taşımak gerekir.
  - Kazanç küçük, risk ve kapsam büyük (dört ajanın CSS'i). Bu tur yapılmadı.
- **Gezinmenin sahneden çıkması:** ölçülebilir değil (yukarıda 5).
- **Grafikler:** Canvas yalnız boyut değişince yeniden çiziliyor (`CanvasGraph.layoutChildren`). Performance sayfasında crossing farkı 16 → 63 ms; büyük kısmı kök yürüyüşü. Düzeltme gerekmedi.

### `low` durumu (kabuk CSS'i, ama bilinçli tasarım)

- `shell.css` içinde `.shell:low .process-table { -fx-fixed-cell-size: 28 }`, satırlar 28 px ve kontroller 30 px (UI-8).
- Maliyet ~150 ms / 40 adım. Her `low` geçişinde tablo satırları yeniden kuruluyor ve iş ikinci bir pulse'a kalıyor.
- Gerçek kullanımda pencere bu çizgiyi nadiren ve bir kez geçer. Ölçümdeki döngü 40 adımın 16'sında geçiyor.
- Sahip kararı gerekir: ya yoğun satırlar kalır ve bu maliyet kabul edilir, ya da alçak pencerede satır yüksekliği değişmez.

### Sayfalar (inceleyici ve sayfalar ajanları)

Merged farkının ~200 ms'si:

- **Processes:** `ProcessDock` mod değiştirirken düğüm taşımıyor, ama dar ve geniş inceleyici yerleşimi ile sütunların yeniden oturması hâlâ layout işi. `layOutNow` ile `openDetails.applyCss()` zorlanıyor (küçük).
- **Limits:** crossing 133 → 282 ms.
- **Overview:** crossing 63 → 219 ms.
  - `OverviewPage`'in boyut dinleyicileri: `widthProperty`, `metrics.widthProperty`, `heightProperty` ve `metrics.heightProperty`, hepsi `resized`'e bağlı.
  - `MetricPanel`'in yığılı/yan yana geçişi: `header.getChildren().setAll(...)`, `valuesLine.getChildren().setAll(...)`.
  - Sayfalar ajanının alanı; bu tur ölçüldü, değiştirilmedi.

## Koordinatörün gerçek pencerede bakması gerekenler

1. **lab/h-merged imajıyla G'deki `perf-run.ps1` resize-40'ı tekrarla.**
   - Aynı koşullarda: %125, boşta makine, ürün temeliyle dönüşümlü, 3 tur.
   - Ekran dışı yoklama FX'te merged ≈ 2,2 × ürün temeli diyor; 8824ada'da ~5,4 × idi. Gerçek pencerede 0,78 s'nin 0,4–0,5 s civarına inmesi beklenir. Bu bir tahmin, ölçüm değil.
2. **Süreç CPU'su** (render iş parçacığı, QuantumRenderer) ekran dışında ölçülemiyor. Yoklamanın tam sahne render'ı her üç ağaçta da ~7 ms; ayırt edici değil.
3. **UI Otomasyonu.** `perf-run.ps1` sayfaya UIA ile gidiyor; bu JavaFX erişilebilirliğini açar. Düğüm ekleyip çıkarmak orada ek olay üretebilir. Ekran dışında ölçülemedi.

## Yoklamayı yeniden koşmak

- **Maven ile, yalnız bu ağaç:**

  ```
  ./mvnw -o -Pui-snapshots test -Dtest=BreakpointCrossingProbe -Dprobe.pages=Processes -Dprobe.sequences=crossing,still
  ```

  - `-Dprobe.rounds=N`, `-Dprobe.select=true` ve `-Dprobe.render=true` ek seçeneklerdir.
  - Diziler: `crossing,still,count,dirty,parts`.
  - Görüntü (display) gerekir.
- **Ağaçları dönüşümlü karşılaştırmak** (ürün temeli dahil; temel ağaçta yoklama olmasa da çalışır):

  ```
  export JAVA_HOME=%USERPROFILE%/.jdks/temurin-25   # Git Bash'te /c/Users/<ad>/.jdks/temurin-25
  ./mvnw -o -q test-compile                          # bu ağaç; diğer ağaçlar da derlenmiş olmalı
  lab-spikes/perf/breakpoint-probe.sh "$TEMP/probe" 3 Processes,Limits crossing . ../baseline
  ```

  - Betik her ağacın `target/classes` ve `target/test-classes`'ını geçici klasöre kopyalar. Bu ağacın yoklama kaynağını onlara karşı derler ve ağaçları tur tur dönüşümlü koşar. Ağaçlara bir şey yazmaz.
  - JFR için: `PROBE_JAVA_OPTS="-XX:StartFlightRecording=settings=<jfc>,filename=<dosya>.jfr"`.
  - `jfr configure method-profiling=max --output <jfc>` ile 1 ms örnekleme alınır.
  - Ardından: `jfr print --events jdk.ExecutionSample --stack-depth 300`.
