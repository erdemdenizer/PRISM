# Third-party notices

## Libraries

The libraries below are declared in `pom.xml` and downloaded by Maven; each is distributed under
its own license, as declared in the library's own POM metadata. Nothing from them is modified.
The Windows application image (`packaging/windows`) bundles them, unmodified, together with a Java
runtime made by `jlink` from the JDK; that runtime carries its own legal notices in its `legal` folder.

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

## Bundled assets

Assets bundled with PRISM (Process & Resource Interactive System Manager), with the notices their licenses require.
Library dependencies are declared in `pom.xml` and carry their own licenses.

## IBM Plex Sans

Three faces of the IBM Plex Sans typeface (Regular, Medium, SemiBold) are bundled as
`src/main/resources/prism/srm/ui/fonts/IBMPlexSans-*.ttf`, taken unmodified from the
`@ibm/plex-sans` 1.1.0 release of https://github.com/IBM/plex.

Copyright © 2017 IBM Corp. with Reserved Font Name "Plex".

This Font Software is licensed under the SIL Open Font License, Version 1.1. The license text is
bundled next to the font files as `src/main/resources/prism/srm/ui/fonts/LICENSE.txt` and is
available with a FAQ at https://openfontlicense.org. The font software is not sold by itself and
is not modified; it is bundled with the application as the license permits.

## Lucide

The icons the application draws are a subset of Lucide (https://lucide.dev), embedded as the path
data of their strokes in `src/main/java/prism/srm/ui/icon/Icon.java`.

ISC License

Copyright (c) 2026 Lucide Icons and Contributors

Permission to use, copy, modify, and/or distribute this software for any purpose with or without
fee is hereby granted, provided that the above copyright notice and this permission notice appear
in all copies.

THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES WITH REGARD TO THIS
SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE
AUTHOR BE LIABLE FOR ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN ACTION OF CONTRACT,
NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION WITH THE USE OR PERFORMANCE
OF THIS SOFTWARE.

Of the icons used, `check`, `chevron-down`, `chevron-up`, `search` and `x` are among those Lucide derived from Feather, whose license applies to them as well:

The MIT License (MIT)

Copyright (c) 2013-present Cole Bemis

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
associated documentation files (the "Software"), to deal in the Software without restriction,
including without limitation the rights to use, copy, modify, merge, publish, distribute,
sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or
substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT
NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT
OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
