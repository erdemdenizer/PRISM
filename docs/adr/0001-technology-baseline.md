# 0001. Technology baseline

- Status: Accepted
- Date: 2026-10-05

## Context

The project is a cross-platform JavaFX desktop application that reads system state and
controls processes through native APIs. It is developed with heavy AI assistance, so the
toolchain has to be fixed and mechanically enforced: an assistant that upgrades a
dependency "because a newer one exists" can silently change behavior across three
operating systems. Stability and tooling compatibility matter more than novelty.

## Decision

The baseline is frozen as follows.

| Area | Choice | Pinned version |
|---|---|---|
| JDK | Eclipse Temurin 25 LTS | enforced range `[25,26)` |
| UI | JavaFX 25 | 25.0.4 |
| Build | Maven via Maven Wrapper | 3.10.0 (wrapper scripts 3.3.4, checksum-verified) |
| System information | OSHI, JNA backend (`oshi-core`) | 7.7.0 |
| Native access | JNA (`jna-jpms`, `jna-platform-jpms`) | 5.19.1, the version OSHI 7.7.0 is built against |
| Logging | SLF4J API + Logback | 2.0.20 / 1.5.38 |
| Tests | JUnit 5, AssertJ, ArchUnit | 5.14.4 / 3.27.7 / 1.5.1 |
| Formatting | Spotless with palantir-java-format | 3.10.3 / 2.101.0 |
| Static analysis | SpotBugs | plugin 4.10.4.1 |
| Coverage | JaCoCo | 0.8.15 |
| Packaging | jpackage (from the JDK) | wired in a later milestone |

All versions were verified as the current stable releases on Maven Central (and the
Adoptium API for the JDK) on the date above. No snapshot, milestone, RC, EA or beta
artifact is used.

Rules:

1. JDK 21, 26 and 27 are not used for this project.
2. OSHI stays on the JNA backend. The FFM backend (`oshi-core-ffm`) is not adopted unless
   a demonstrated technical requirement appears and a new ADR records it.
3. A pinned version is not upgraded automatically or opportunistically. A change requires
   a concrete reason, compatibility verification, and a passing full gate (`./mvnw verify`).
4. The application runs on the classpath, without `module-info.java`.

## Alternatives considered

- **JDK 21 LTS**: the previous LTS; rejected in favor of the current LTS, which the
  required tooling supports.
- **JDK 26/27**: non-LTS feature releases; rejected for stability.
- **OSHI FFM backend**: would mix two native-access mechanisms, since the project's own
  OS-specific code uses JNA. One mechanism keeps the native strategy consistent.
- **JPMS (modular application)**: adds friction for JNA reflection, tests and tooling,
  and is not required by jpackage.
- **google-java-format**: equally deterministic; palantir-java-format was chosen for its
  120-column layout and more readable lambda and call-chain formatting.

## Consequences

- The Maven Enforcer fails the build on any other JDK or Maven version, on snapshot
  dependencies, on missing plugin versions, and if the non-JPMS JNA artifacts or the
  OSHI FFM artifacts enter the dependency tree.
- Because JDK 24+ restricts native access, run configurations pass
  `--enable-native-access` (see `pom.xml`); packaging must do the same.
- Developers need a JDK 25 in `JAVA_HOME`; nothing else has to be installed, the wrapper
  downloads Maven.
- JavaFX artifacts are resolved per platform by classifier; Windows on ARM64 has no
  official JavaFX 25 artifacts on Maven Central and is not a supported build host.
- Upgrades are deliberate work items with their own verification, not side effects.
