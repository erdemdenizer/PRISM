# 0018. The Linux application image

- Status: **Proposed**. The image is not accepted until the product owner accepts this record and
  `verify-image.sh` has passed on a desktop session.
- Date: 2026-11-26

## Context

DEL-1 asks for native packages built with jpackage separately on each system, nothing cross-built, and a package
that runs without a JDK. Until now only Windows had one ([ADR-0017](0017-windows-application-image.md)): an
application image in a ZIP, without an installer (decision K1), with the demo workload beside it as a launcher of its
own (K5).

ADR-0017 had to change the Windows launcher because it starts a second copy of itself in a Job Object, which broke
SAFE-2 and the native limits. Whether the Linux launcher does anything like that has to be found out, not assumed.

## Decision

### The same shape as on Windows

`./mvnw -Ppackage-image-linux -DskipTests package` on Linux builds, in `target/package`:

- an application image `PRISM/` with the launchers `bin/PRISM` and `bin/prism-demo`, the application in `lib/app` and
  the runtime in `lib/runtime`;
- `prism-<version>-linux-x64.tar.gz` of it, with its SHA-256 beside it. A tar keeps the launchers' execute bits and
  the runtime's links, which a ZIP would lose;
- `verification/` with `verify-image.sh` and `image-probe.jar`, which are never part of the archive.

No `.deb` or `.rpm`: no installer, as on Windows (K1). No desktop entry is installed; the README says how to start it.

`packaging/linux/PackageImage.java` is source-launched, like its Windows counterpart, and refuses to run anywhere but
Linux. The two do not share code: each is short, and a shared one would have to know both launchers.

The build host needs `objcopy` (GNU binutils): jpackage's default jlink options include `--strip-debug`, which on
Linux runs `objcopy` and fails without it ("Cannot run program "objcopy""). The hosted Ubuntu runners have it; a
desktop Ubuntu does not by default (`sudo apt install binutils`). The options stay the defaults, as on Windows.

The `build` workflow builds the image on `ubuntu-latest` and keeps the archive as an artifact. It does not verify
it: the verification needs a desktop session.

### Runtime

The modules are those of Windows (`packaging/linux/modules.txt`), `jdk.localedata` included for the same reason: without
it Turkish numbers format as `1,234,567`. The application runs from the class path, so the K16 claim of ADR-0017 holds
here too.

No JDK gate like `verified-jdk.txt`: that file guards an undocumented key of the Windows launcher (`win.norestart`),
and the Linux image uses none. The archive records the JDK it was built with in `BUILT_WITH.txt`, and the runtime says
it in `lib/runtime/release`.

### Verification

`verify-image.sh <archive> <results>` runs on the image, as an ordinary user in a desktop session, with the XDG state
and configuration folders redirected for each scenario. Its checks:

- `archive`: the SHA-256, the unpacked image, the launchers executable;
- `probe`: the image's own runtime runs `ImageProbe check` (modules, Turkish formats, JavaFX, Logback, the process
  listing through OSHI and JNA, and SAFE-2 refusing every action on PRISM's own process);
- `single-process`: the started launcher is the process that runs the JVM, has no child, and is the only process of
  the image; the log reports the native limits as not enabled (K16);
- `sigterm`: SIGTERM ends it within 20 s and leaves no process of the image. That is the shutdown hook's path
  (LIFE-5), which by design is not the controlled shutdown;
- `window-close`: closing the window runs the controlled shutdown (LIFE-1), seen as "Shutdown complete" in the log,
  and leaves no process. Closing a window is a desktop action the script cannot take on Wayland: it waits for a
  person, or a test driver, to close it, and reports `NOT TESTED` when nobody does;
- `demo`: `prism-demo --help` answers.

The verdict is as strict as on Windows: anything `FAIL` or `NOT TESTED` means `NOT ACCEPTED`.

## Consequences

- Linux gets a package that runs without a JDK, built and checked the same way as the Windows one.
- What the script does not check, a person has to: the window on the screen, its pages, and a start from a desktop
  launcher rather than a terminal.
- macOS still has no image.
