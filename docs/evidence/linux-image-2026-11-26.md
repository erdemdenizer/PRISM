# Linux application image: first verification, 2026-11-26

The first run of `verify-image.sh` ([ADR-0018](../adr/0018-linux-application-image.md)) on a Linux image of PRISM.
Verdict: **ACCEPTED**, 31 PASS, 0 FAIL, 0 NOT TESTED. Raw results: [linux-image-2026-11-26/](linux-image-2026-11-26/)
(`results.tsv`, `summary.txt`; the VM user's home folder is written as `~`).

This is evidence for ADR-0018, which is still Proposed: the owner's acceptance is open.

## What was built

| Fact | Value |
|---|---|
| Source | commit `c5845d7` (branch `fix/linux-device-layers`) with the files of `packaging/linux/` and the `package-image-linux` profile of the branch `feat/linux-app-image`, before they were committed |
| Command | `./mvnw -B -Ppackage-image-linux -DskipTests package` |
| Archive | `prism-0.1.0-linux-x64.tar.gz`, 54 MiB, SHA-256 `a50618828582ded2cd73f1e0104503846069250f7b798a1e39c05a3dd1cf8ffe` |
| JDK | Eclipse Adoptium Temurin 25.0.5.1 |

The first build failed at jlink: "Cannot run program "objcopy"" (jpackage's `--strip-debug` needs GNU binutils). The
VM's user had no root, so the Ubuntu packages of binutils 2.42 were unpacked into the user's folder
(`apt-get download`, `dpkg -x`) and put on the path for the build only. ADR-0018 records the requirement.

## Where it ran

Hyper-V Generation 2 VM, 8 GiB, 2 vCPUs. Ubuntu 24.04.5 LTS Desktop (official ISO, SHA-256 and signature checked),
kernel 7.0.0-44-generic, systemd 255.4-1ubuntu8.18, cgroup v2, GNOME on Wayland with Xwayland. The script ran over
SSH as the desktop user, with the session's display variables, and with **no Java on the path**: the image ran on its
own runtime.

## Results

- **archive** (4 PASS): the SHA-256 matches the file beside the archive; the image unpacks; both launchers are
  executable.
- **probe** (17 PASS, on the image's own runtime):
  - modules (`jdk.localedata` present, 10 modules);
  - Turkish formats: `1.234.567,5`, `%25`, `15 Ocak 2026`, `İ`;
  - the JavaFX toolkit starts; Logback is the logging backend;
  - the process listing through OSHI and JNA: 201 processes, PRISM's own row found;
  - SAFE-2: priority, suspend, end task, force kill and manage on PRISM's own process are all refused with "It is
    PRISM's own process", and the probe stays alive;
  - the real runtime shuts down.
- **single-process** (5 PASS):
  - monitoring 1.4 s after the start;
  - the started launcher is the process that runs the JVM (`/proc/<pid>/exe` is `bin/PRISM`), with no child, and is
    the only process of the image. So the Linux launcher does not do what ADR-0017 had to stop on Windows;
  - the log reports the native limits as not enabled (K16).
- **sigterm** (2 PASS): SIGTERM ends it within 20 s through the shutdown hook (LIFE-5), and no process of the image is
  left.
- **window-close** (2 PASS):
  - the window was closed with Alt+F4, typed into the VM through the Hyper-V keyboard after a screenshot showed the
    PRISM window in focus;
  - the controlled shutdown ran: "Shutdown started." and "Shutdown complete: monitoring and its executors are
    stopped." in the log, exit code 0, nothing left.
- **demo** (1 PASS): `prism-demo --help` prints its usage.

The first run of the script had a wrong check, not a product defect. It expected the controlled shutdown on SIGTERM,
which LIFE-5 does not promise. The script was corrected to check SIGTERM as LIFE-5 and the window close as LIFE-1
before this run.

## Not covered

- A start from a desktop launcher (the image installs none).
- The pages of the window beyond the Overview.
- An Ubuntu other than 24.04.5, or another distribution.
- A machine that is not a VM.
