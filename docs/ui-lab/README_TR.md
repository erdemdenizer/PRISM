# PRISM UI Lab: premium UI v2

PRISM'in kullanıcı arayüzü için bir laboratuvar kopyasıdır. Amaç, ürünün işlevlerini ve güvenlik kurallarını değiştirmeden arayüzü masaüstü sınıfı, sakin ve yoğun bir kaliteye çıkarmaktır. **Bu bir ürün sürümü değildir.** Ürüne geçiş kararını proje sahibi verir (`PRODUCTION_MIGRATION_PLAN_TR.md`).

## Kaynaktan bağımsızlık

- Laboratuvar, PRISM-internal'ın `12c49b6` commit'inden alınmış bağımsız bir klondur ve ayrı bir klasördedir.
- Uzak deposu yoktur, `push.default=nothing` ayarlıdır ve depo kancaları kapalıdır.
- Kaynak depoya hiçbir şey yazılmadı. Aktif dalı, HEAD'i, ref'leri, index'i ve izlenen 991 dosyanın sha256 özetleri çalışma öncesinde ve sonrasında karşılaştırıldı (`lab-evidence/F-final/SOURCE_UNCHANGED.md`).
- Çalışma zamanında da iki kopya birbirinden ayrıdır:
  - veri klasörü `%LOCALAPPDATA%\Prism\SystemResourceManager-UILab` (günlükler, ayarlar, kilit, kurtarma kayıtları),
  - pencere başlığı "PRISM UI Lab — …",
  - app-image ve başlatıcı adı `PRISM-UI-Lab`.
- **Uyarı:** laboratuvar ile PRISM aynı anda çalıştırılmamalıdır. Dosyaları birbirine karışmaz, ama iki kopya birbirinin limitlerinden, askıya aldığı süreçlerden ve OOM Guard'ından haberdar olmaz. Bu uyarı imajın `README.txt` dosyasında da yazılıdır.

## Durum özeti

- **Kapı:** `./mvnw -o clean verify` ve `-Pui-snapshots` sonuçları `lab-evidence/F-final/` günlüklerindedir.
- **Performans:** boşta CPU, heap kararlılığı, açılış ve satır yenileme baseline ile eşit; MON-12 PASS. Kırılım geçişleri pahalı (`lab-evidence/F-final/PERFORMANCE_TR.md`).
- **Duyarlı düzen:** gerçek pencerede %100 ve %125, ekran dışında tüm boyutlar. %150 ve %200 yalnızca benzetimle; gerçek Windows ölçeği NOT TESTED (`RESPONSIVE_MATRIX_TR.md`).
- **Başlık çubuğu:** `TITLEBAR_VALIDATION_TR.md`.

## Çalıştırma

| Amaç | Komut (Git Bash, `JAVA_HOME` = JDK 25) |
|---|---|
| Uygulama | `./mvnw javafx:run` |
| Tam kapı | `./mvnw -o clean verify` |
| UI görüntüleri | `./mvnw -o -Pui-snapshots test` (çıktı `target/ui-snapshots/`) |
| App-image | `./mvnw -o -Ppackage-image -DskipTests package` (çıktı `target/package/image/PRISM-UI-Lab/PRISM-UI-Lab.exe`) |
| Performans ve gerçek pencere matrisi | `lab-spikes/perf/run-phase-f.ps1` (yalnızca kullanıcı boştayken ve PRISM kapalıyken) |

Kontrol denemeleri yalnızca imajla gelen `prism-demo.exe` üzerinde yapılır; gerçek kullanıcı süreçlerine dokunulmaz.

## Belgeler

| Belge | İçerik |
|---|---|
| `LAB_DESIGN_SPEC.md` | Yapım sözleşmesi |
| `TITLEBAR_VALIDATION_TR.md` | Başlık çubuğu seçenekleri ve gerçek Windows üzerindeki kabul matrisi |
| `DESIGN_DECISIONS_TR.md` | Tasarım kararları ve sapmalar |
| `JAVAFX_IMPLEMENTATION_TR.md` | JavaFX uygulama notları |
| `RESPONSIVE_MATRIX_TR.md` | Boyut ve DPI matrisi (gerçek pencere ve ekran dışı) |
| `PRODUCTION_MIGRATION_PLAN_TR.md` | Geçiş planı ve ADR önerileri |
| `NAV_COMPARISON.md`, `CONTRAST.md`, `PHASE_E_SEAMS.md`, `research/` | Destekleyici çalışmalar |

## Kanıtlar

| Klasör | İçerik |
|---|---|
| `lab-evidence/A-baseline/` | Başlangıç kaydı |
| `lab-evidence/B-titlebar/` | Başlık çubuğu ölçümleri |
| `lab-evidence/C-nav/`, `D-design/`, `E-*` | Aşama görüntüleri |
| `lab-evidence/F-final/` | Kapı günlükleri, önce/sonra karşılaştırmaları, `runtime/` (performans ve gerçek pencere matrisi) |
