# Boyutlandırma performansı: önce, ara sürüm ve son sürüm

## Nasıl ölçüldü

Ölçüm gerçek Windows penceresinde, `lab-spikes/perf/perf-run.ps1` ile yapıldı. Ekran %125 ölçekte, makinede 16 mantıksal işlemci var.

Koşullar:
- Her tur kendi geçici `LOCALAPPDATA` klasörüyle çalıştı. Kullanıcının UI-LAB kaydına ve asıl PRISM verisine dokunulmadı.
- Her tur, kullanıcı 600 sn boyunca hiç girdi vermediyse başladı.
- İlk temel tur sırasında kullanıcı girdisi algılandı. O tur BLOCKED olarak atıldı ve baştan koşuldu.
- Önce ve sonra imajları dönüşümlü koşuldu: önce, sonra, önce, sonra, önce, sonra.

Ölçülen bölümler:
- **resize-40:** pencereyi 150 ms aralıkla 40 kez beş boyut arasında değiştirir: 1366×768, 1100×700, 800×600, 1440×900, 720×480. Neredeyse her adım bir düzen eşiğini geçer.
- **sweep 720→1880:** genişliği 40 adımda 720'den 1880'e çıkarır. Her eşiği bir kez geçer.
- **sweep 1400→1880:** aynı tek düzen içinde kalır; inceleyici hep yanda durur.
- **Değerler:** süreç CPU süresi ve JavaFX Application Thread CPU süresi (FX). İkisi de saniye, düşük olan iyi.

## Sonuç (medyan; köşeli parantez içinde tek tek turlar)

| İmaj | resize-40 CPU | resize-40 FX | sweep 720→1880 CPU / FX | sweep 1400→1880 CPU / FX | Tur |
|---|---|---|---|---|---|
| Ürün temeli (19b7fbe = PRISM 12c49b6 + yalnız veri klasörü ve başlık yalıtımı) | 2,98 | 0,31 | 1,47 / 0,20 | 1,45 / 0,22 | 1 |
| UI-LAB önce (5565a77) | 4,02 [4,02 3,92 4,20] | 0,75 [0,73 0,75 0,81] | 1,61 / 0,38 | 2,14 / 0,42 | 3 |
| UI-LAB sonra (18f9632) | 4,06 [3,78 4,58 4,06] | 0,78 [0,78 0,86 0,64] | 2,19 / 0,36 | 1,91 / 0,50 | 3 |
| Ara sürüm f4931df (tek tur, önceki ölçüm) | 5,28 | 1,11 | 2,45 / 0,45 | 1,77 / 0,50 | 1 |

## Yorum

1. **Gerileme bulundu ve giderildi.**
   - Birleştirilmiş düzeltmelerle (f4931df) resize-40, öncekine göre CPU'da +%26, FX'te +%17 kötüleşmişti (tek tur ölçümü).
   - Neden: Processes ve Limits, her dar mod geçişinde inceleyiciyi kapalı sayfanın (sheet) içine taşıyordu. Bu her geçişte gereksiz bir CSS ve düzen geçişi demekti.
   - Çözüm (18f9632): inceleyici sayfaya yalnızca sayfa açılınca konuyor.
   - Sonuç: resize-40 yeniden önceki düzeyde. CPU medyanı 4,06 / 4,02 s, FX medyanı 0,78 / 0,75 s; turların aralıkları birbirinin içinde.
2. **Tek düzen içindeki tarama** (1400→1880):
   - FX medyanı 0,50 / 0,42 s; aralıklar 0,41–0,52 ve 0,38–0,48, yani örtüşüyor.
   - Olası küçük bir artış var: inceleyicinin sabit eylem parçası ve kaydırma kenarı her genişlikte yeniden yerleşiyor.
   - Üç turla gürültüden ayrılamadı. Kötüleşme diye kanıtlanmadı; iyileşme diye de sunulmuyor.
3. **Eşik geçiş taraması** (720→1880):
   - CPU medyanı 2,19 / 1,61 s. FX medyanı 0,36 / 0,38 s, yani FX aynı.
   - Fark FX dışındaki iş parçacıklarında (çizim/QuantumRenderer).
   - Bu bir PARTIAL bulgu: daha çok turla ölçülmeli.
4. **Laboratuvar ürün temelinden hâlâ yavaş.**
   - Ürün temeli: resize-40 FX 0,31 s.
   - UI-LAB önce ve sonra: 0,75–0,78 s.
   - Bu fark bu turda doğmadı; Faz 3'ten beri açık kalem: düzen eşiği geçişinde sayfa yapısı yeniden kuruluyor.
   - Bu tur yalnızca yeni gerilemeyi geri aldı. Görünürlük açıp kapatarak geçiş yapmak daha büyük bir yapı değişikliği ister ve yapılmadı.
5. **Pencere hiçbir turda yanıt vermez duruma geçmedi** (`IsHungAppWindow`: False).
6. **Boşta tüketim** (Processes sayfası, 70 sn):
   - Makine CPU'su %0,45–0,52; özel bellek 361–383 MB.
   - Önce ve sonra arasında anlamlı fark yok.
   - Açılış süresi 1,7–2,4 s. after18-1 turundaki 2,37 s tek seferlik bir değer; diğer iki tur 1,84–1,87 s.

## Ekran dışı yoklama (ResizeCostProbe; yalnız yön göstergesi)

Dolu bir Shell, Processes sayfasında, eşik geçiren boyutlar arasında yeniden sahnelendi: 3 tur × 40 adım.

| Sürüm | Hep aynı boyut (ms) | Eşik geçen boyutlar (ms) |
|---|---|---|
| önce 5565a77 | ~1300 | ~1500 |
| birleşik f4931df | ~1350 | ~1690 |
| son 18f9632 | ~1050 | ~1345 |

Gerçek pencere ölçümü bu yönü doğruladı: gerileme geri alındı. Gerçek pencerede önceki sürümden belirgin bir iyileşme ise görülmedi.

## Ham veriler

`kanit/performans/`:
- `baseline-2`, `before-1..3`, `after18-1..3`: json, txt ve png.
- Önceki tek tur ölçümü: `ilk-olcum/`.
