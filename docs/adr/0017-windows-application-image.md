# 0017. The Windows application image, its launchers and what guards them

- Status: **Proposed** (milestone 13). Decisions K1, K5, K6 and K11 of the product owner are recorded in
  [evidence/m13-decisions.md](../evidence/m13-decisions.md); the acceptance of the milestone is still open.
- Date: 2026-11-07

## Context

DEL-1 asks for a package that runs without a JDK. Decision K1 makes the Windows delivery a jpackage application
image in a ZIP, without an installer, and K5 puts the demo workload beside it as a launcher of its own.

The feasibility round of milestone 13 measured the default jpackage launcher on the running product:

- The launcher starts a second copy of itself, in a Job Object with `KILL_ON_JOB_CLOSE` and
  `SILENT_BREAKAWAY_OK`, and waits for it. Two processes run for one PRISM.
- The Prism that runs is the child. SAFE-2 protects the process it knows as its own, so the launcher parent appeared
  as an ordinary row with Suspend, End task and Force kill enabled, and ending it ended PRISM.
- A demo in such a job refused the native CPU limit and offered no native memory limit, because Prism does not take a
  process another program's job holds (ADR-0012).

## Decision

### Launcher: `win.norestart=true`

`PackageImage` adds `win.norestart=true` to the `[Application]` section of `app\PRISM.cfg` and `app\prism-demo.cfg`.
The launcher then runs the JVM in its own process, in no job of its own.

**This is undocumented JDK behaviour.** The key is not in the jpackage documentation. It was found in the OpenJDK
source of the launcher (`CfgFile.cpp`) and in the launcher binary of the JDK in use. A later JDK may
change or remove it without notice, and nothing in the build would fail by itself. The owner accepted this with K11, on
these conditions, which are binding:

1. `verify-image.ps1` proves on the image: one process model, the self-protection of PRISM, the native CPU and memory
   limits on the demo launcher, and the safety of the shutdown. Results of security-relevant actions count, not the
   state of a button.
2. **If a mandatory check is not PASS, the package is not accepted.**
3. **A change of JDK makes the checks mandatory again.** `packaging/windows/verified-jdk.txt` names the JDK last
   verified. `PackageImage` refuses any other until it is run with `-Dprism.package.unverifiedJdk=true`, which marks the
   output as not deliverable. The file is changed only after `verify-image.ps1` has passed on an image of that JDK.
4. `PackageImage` stops if the launcher binary no longer names the key.

The other way, a wrapper or a launcher of our own, would add native code and a signing surface for what one key does.

### Runtime

jlink modules are in `packaging/windows/modules.txt`, and `PackagingConfigTest` pins them in `verify`:
`java.base`, `java.desktop`, `java.logging`, `java.management`, `java.xml`, `jdk.management`, `jdk.unsupported`,
`jdk.localedata`.

`jdk.localedata` stays although nothing fails without it. Without it, Turkish numbers silently format as
`1,234,567` (measured, also with the 51 modules jpackage takes by default). The image is about 120 MiB and the ZIP
about 50 MiB with it.

The application runs from the class path as it does in development, not from a module path. The Linux claim of K16
depends on there being no `module-info`.

JavaFX and JNA keep extracting their native libraries at first start: JavaFX under
`%USERPROFILE%\.openjfx\cache`, JNA under `%TEMP%`. Both are outside the application folder. This is reported, not
changed in this round. JavaFX also logs `Unsupported JavaFX configuration` on the class path; no effect on the application
was seen in the runs of this round.

### Build

`./mvnw -Ppackage-image -DskipTests package` in `target/package`. Not part of `verify`, which stays free of a packaging
step and runs on every system. Two plugins are added for this profile only, pinned to released versions:
`maven-dependency-plugin` and `exec-maven-plugin`. `PackageImage.java` is source-launched and uses the JDK's own
`jpackage` and `jar` through `ToolProvider`, so nothing of it reaches the product.

The JavaFX jars without a platform classifier are empty stubs and stay out of the image.

### Verification

`verify-image.ps1` runs on the image in two kinds of environment, whose results are kept apart: a clean one (Windows
Sandbox, K6, no JDK, Turkish culture) and the real machine. A property of one is not assumed of the other. In particular,
Disk and Network readings missing in the Sandbox are reported as an observation and not classified as a success or a
failure of the product by themselves.

The verdict is strict. A check that was not carried out refuses the image as a failed one does: anything `FAIL`, `BLOCKED`
or `NOT TESTED` means `IMAGE NOT ACCEPTED`. A host run may leave open only what it does on purpose not do to a real machine,
opening folder windows and changing the Windows theme. `IMAGE ACCEPTED` comes only from a clean run; a host run that
passes says `HOST CHECKS PASSED`, because it has a JDK beside it.

What an allocation does under a native memory limit is measured on the `prism-probe` copy, the same launcher binary and
runtime with its own main class: the delivered demo allocates at start and cannot be told when to go on. On the delivered
demo itself, the memory limit is proved by the report and the log, not by a refused allocation.

Some checks need what the delivered launchers do not offer. They use test copies made by the script, never delivered:

| Copy | Why |
|---|---|
| `prism-probe` with `image-probe.jar` | Runs on the image's own runtime: the runtime's modules, and SAFE-2 as a result of the real runtime, without a button |
| `prism-demo-in-job`, without the key | A process native limits are refused on, to reach the emulated limit |
| `prism-console`, PRISM behind a console launcher | Ctrl+C and a closed console reach the JVM only through a console (LIFE-5) |

## Consequences

- One process, no job, so PRISM protects the whole of itself and the demo takes native limits.
- The product depends on an undocumented key. The guards above turn a silent change into a refused package. They do not
  remove the dependency.
- Without the launcher's job, nothing ends the JVM when a launcher parent dies, because there is no parent.
- Not covered: the executables are not code-signed, so SmartScreen may warn. There is no icon of the product yet, because
  branding is separate work. Linux and macOS packaging are outside milestone 13 (K7).
