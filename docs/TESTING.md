# Testing and verification

Commands use `./mvnw` (Bash, macOS, Linux). In PowerShell use `.\mvnw.cmd`.
The build requires JDK 25 in `JAVA_HOME`; the enforcer stops with a message otherwise.

## Fast check (while iterating)

```
./mvnw -q compile                          # main sources, warnings are errors
./mvnw -q test -Dtest=RateCalculatorTest   # the tests for what you touched
./mvnw -q test -Dtest='Rate*Test,ArchitectureTest'
```

Seconds, not minutes. Use it after each small change. Compiler warnings are errors, and
`-q` hides their text: if the build fails with only "warnings found and -Werror
specified", rerun without `-q`.

A Stop hook (`.claude/hooks/fast-check.mjs`) runs an equivalent offline check
(`test-compile` + `spotless:check`, no tests) at the end of a Claude turn, but only when
files under `src/`, `.mvn/`, `config/` or `pom.xml` changed since the last check. It
does not replace the full gate.

## Full gate (before calling work done, before committing)

```
./mvnw spotless:apply     # only if formatting needs fixing
./mvnw verify
```

`verify` runs, in this order:

1. **Enforcer**: JDK 25, Maven 3.10.x, pinned plugin versions, no snapshot dependencies,
   upper-bound dependency versions, banned artifacts (non-JPMS JNA, OSHI FFM).
2. **Compile** with `-Xlint:all` (minus `serial`, `processing`, `path`, `this-escape`);
   any warning fails the build.
3. **Tests** (JUnit 5), including `ArchitectureTest`.
4. **Spotless check**: palantir-java-format for Java, sorted `pom.xml`.
5. **JaCoCo report**: `target/site/jacoco/index.html`. No coverage threshold is enforced
   yet; one will be set by ADR when there is real logic to measure.
6. **SpotBugs**: effort Max, threshold Medium; any finding fails the build.

Do not skip steps (`-DskipTests`, `-Dspotbugs.skip`, ...). A finding is fixed, not
suppressed; a SpotBugs exclusion needs a written reason in `config/spotbugs/exclude.xml`.

## What to test

| Layer | Approach |
|---|---|
| `domain` | Plain unit tests of records, validation, value arithmetic. |
| `application` | Unit tests against hand-written fakes of the `platform` interfaces: rate calculation (zero interval, counter reset and wrap, first sample), bounded histories, state machines (every transition, invalid transitions, failure mid-transition), polling that must not overlap, shutdown. |
| `platform.oshi`, `platform.<os>` | Unit-test what does not need the real OS: parsing of `/proc` and cgroup files from fixture text, error-code mapping, capability reporting. Real native calls are verified manually per OS and reported as such. |
| `ui` | View models, formatting, the graph arithmetic and the relay to the UI thread are plain unit tests: JavaFX properties need no running toolkit. Views hold layout and binding only; they are rendered as snapshots for visual inspection, and what needs a scene (a color the stylesheet resolved, what a control does with a key, the number of nodes) is asserted in tests tagged `ui-snapshot`. |
| `app` | The monitoring runtime against fake sources, with its real executors and threads: start-up, failures, shutdown in every state, the time bound. |
| Architecture | `ArchitectureTest` checks layer boundaries on every run. |

Rules:

- A test must be able to fail. No assertions on mocks alone, no tests that restate the
  implementation.
- Tests log to the console only (`src/test/resources/logback-test.xml`); a test run
  never writes into the log folder of the person running it.
- Prefer real objects and small fakes over mocking frameworks. Mockito is not a
  dependency; add it only by decision if a real need appears.
- Tests do not depend on the host: no real PIDs, no host locale or time zone, no
  `Thread.sleep` for synchronization. Locale-sensitive code is tested with explicit
  locales, including one where it matters (`tr-TR`: `"I".toLowerCase()` is not `"i"`).
- Code for an operating system it was never run on is unverified; say so in the report.

## UI snapshots

`SnapshotHarness` (test sources, package `prism.srm.ui.snapshot`) renders a node tree
with the application stylesheet to a PNG without launching the application.

```
./mvnw -q -Pui-snapshots test      # writes target/ui-snapshots/<name>-<theme>-<w>x<h>.png
```

```java
@Tag("ui-snapshot")
class ProcessTableSnapshotTest {
    @Test
    void emptyState() {
        SnapshotHarness.render("process-table-empty", Theme.LIGHT, 1100, 700, () -> buildView());
    }
}
```

- `SnapshotHarness.inspect` builds a scene the same way and hands its root to a
  function instead of writing an image, for assertions that need applied CSS or layout.
- `SnapshotHarness.render` takes an optional action that runs after the first layout,
  for a state a control can take only once it has its skin and its size, such as a table
  scrolled to a column.
- Tests tagged `ui-snapshot` are everything that needs the JavaFX toolkit, renders and
  assertions alike. They are excluded from the normal build and run only with the
  `ui-snapshots` profile. `./mvnw verify` therefore does not run them: a change to a
  view or to the shell is not verified until the profile has passed as well. Rules that
  can be stated without a scene are kept out of the views and tested in the normal
  build (for example which status the shell shows for which failure). Without the profile, `-Dtest=SomeSnapshotTest` runs zero tests
  and still reports success; add `-Pui-snapshots`.
- Add one snapshot per screen and per state (empty, loading, error, extreme values) at
  the default and the minimum window size, in both themes. Open the PNGs and compare
  against [UI_GUIDE.md](UI_GUIDE.md).
- Limits: needs a display (no headless rendering); renders at scale 1.0; captures one
  scene only, so popups, tooltips, hover states and window decorations are not in the
  image. Check those in the running app (`./mvnw javafx:run`).
- PNGs are build output. They are not committed and not compared automatically: fonts
  differ per operating system.

## Hook tests

```
node --test .claude/hooks/*.test.mjs
```

Run after any change to `.claude/hooks/`. The suite covers the guard's decisions for
Bash and PowerShell commands and file tools on Windows- and POSIX-shaped paths, and the
parsing helpers of the fast check.
