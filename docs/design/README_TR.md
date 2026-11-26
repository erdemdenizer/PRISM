# PRISM arayüzünün yeniden tasarımı: tasarım belgeleri

Bu klasör, PRISM'in kullanıcı arayüzünün yeniden tasarımına ait belgeleri toplar. Amaç, ürünün işlevlerini ve güvenlik kurallarını değiştirmeden arayüzü masaüstü sınıfı, sakin ve yoğun bir kaliteye çıkarmaktı. Yeniden tasarım ayrı bir dalda geliştirildi ve kurs sürümüne alındı ([COURSE_RELEASE.md](../COURSE_RELEASE.md)). Koddaki tasarım yorumları bu klasördeki belgelere, en çok `DESIGN_SPEC.md` dosyasına gönderme yapar.

## Geliştirme sırasındaki yalıtım

- Yeniden tasarım, geliştirme deposunun `12c49b6` commit'inden ayrılan bir dalda yapıldı. O sırada asıl ürün koduna hiçbir şey yazılmadı; bu, çalışma öncesinde ve sonrasında izlenen 991 dosyanın sha256 özetleri karşılaştırılarak denetlendi.
- Geliştirme derlemesi çalışma zamanında da üründen ayrıydı: kendi veri klasörleri (günlükler, ayarlar, kilit, kurtarma kayıtları), kendi pencere başlığı ve app-image adı vardı. Bu yalıtım yalnızca geliştirme içindi ve kurs sürümünde kaldırıldı; ürün, ADR-0007 ve ADR-0013'teki klasörleri ve tek kopya kuralını kullanır.

## Durum özeti

Ham kanıtlar (kapı günlükleri, önce/sonra görüntüleri, performans ölçümleri) bu depoda değildir; sonuçları aşağıdaki belgelerde özetlenmiştir.

- **Kapı:** `./mvnw -o clean verify` ve `-Pui-snapshots` geçti.
- **Performans:** boşta CPU, heap kararlılığı, açılış ve satır yenileme baseline ile eşit; MON-12 PASS. Kırılım geçişleri pahalı (`fix-h/PERF_DIAGNOSIS_TR.md`).
- **Duyarlı düzen:** gerçek pencerede %100 ve %125, ekran dışında tüm boyutlar. %150 ve %200 yalnızca benzetimle; gerçek Windows ölçeği NOT TESTED (`RESPONSIVE_MATRIX_TR.md`).
- **Başlık çubuğu:** `TITLEBAR_VALIDATION_TR.md`.

## Çalıştırma

| Amaç | Komut (Git Bash, `JAVA_HOME` = JDK 25) |
|---|---|
| Uygulama | `./mvnw javafx:run` |
| Tam kapı | `./mvnw -o clean verify` |
| UI görüntüleri | `./mvnw -o -Pui-snapshots test` (çıktı `target/ui-snapshots/`) |
| App-image | `./mvnw -o -Ppackage-image -DskipTests package` (çıktı `target/package/`, ADR-0017) |
| Karşıtlık tabloları | `python -I docs/design/contrast/tokens.py` (depo kökünden) |

Kontrol denemeleri yalnızca imajla gelen `prism-demo.exe` üzerinde yapılır; gerçek kullanıcı süreçlerine dokunulmaz.

## Belgeler

| Belge | İçerik |
|---|---|
| `DESIGN_SPEC.md` | Tasarım sözleşmesi: düzen, belirteçler, kırılım noktaları |
| `TITLEBAR_VALIDATION_TR.md` | Başlık çubuğu seçenekleri ve gerçek Windows üzerindeki kabul matrisi |
| `DESIGN_DECISIONS_TR.md` | Tasarım kararları ve sapmalar |
| `JAVAFX_IMPLEMENTATION_TR.md` | JavaFX uygulama notları |
| `RESPONSIVE_MATRIX_TR.md` | Boyut ve DPI matrisi (gerçek pencere ve ekran dışı) |
| `PRODUCTION_MIGRATION_PLAN_TR.md` | Ürüne geçiş planı ve ADR önerileri |
| `NAV_COMPARISON.md`, `CONTRAST.md`, `contrast/`, `PHASE_E_SEAMS.md`, `research/` | Destekleyici çalışmalar |
| `fix-g/`, `fix-h/` | G ve H düzeltme turlarının bulgu ve durum kayıtları |
