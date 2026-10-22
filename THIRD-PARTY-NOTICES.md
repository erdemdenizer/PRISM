# Third-party notices

PRISM bundles no third-party assets at this checkpoint (no fonts, icons or images). The
libraries below are declared in `pom.xml` and downloaded by Maven; each is distributed under
its own license, as declared in the library's own POM metadata. Nothing from them is modified
or redistributed by this repository.

| Library | Version | Scope | License declared by the library |
|---|---|---|---|
| OpenJFX (`javafx-controls`, with `javafx-graphics` and `javafx-base`) | 25.0.4 | runtime | GPL v2 with the Classpath Exception (`GPLv2+CE`) |
| OSHI (`oshi-core`, `oshi-common`) | 7.7.0 | runtime | MIT |
| JNA (`jna-jpms`, `jna-platform-jpms`) | 5.19.1 | runtime | Dual: LGPL-2.1-or-later or Apache-2.0 |
| SLF4J API | 2.0.20 | runtime | MIT |
| Logback (`logback-classic`, `logback-core`) | 1.5.38 | runtime | Dual: EPL-2.0 or LGPL-2.1 |
| JUnit Jupiter | 5.14.4 | test | Eclipse Public License v2.0 |
| AssertJ Core | 3.27.7 | test | Apache-2.0 |
| ArchUnit | 1.5.1 | test | Apache-2.0 (bundles ASM, BSD) |

Build-time tools (Maven plugins, Spotless with palantir-java-format, SpotBugs, JaCoCo) run on
the developer's machine and are not part of the application.

The license names above were read from the `<licenses>` element of each artifact's POM, or of
its parent POM, in the local Maven repository on 2026-10-22. Consult the libraries' own
distributions for the authoritative license texts.
