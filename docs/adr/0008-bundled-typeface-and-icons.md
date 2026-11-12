# 0008. Bundled typeface and icon set

- Status: Accepted
- Date: 2026-10-07

## Context

Until milestone 5.5 the user interface used the system font (Segoe UI on Windows, whatever the
system has elsewhere) in its regular and bold faces, and no icons. The visual foundation of
milestone 5.5 (docs/UI_GUIDE.md) asks for one typeface with three weights on every operating
system, so that the type hierarchy and the measured column widths are the same everywhere, and
for one outline icon family for the navigation and a few controls. Both have to work without
network access, load once, and cost nothing at run time beyond their drawing.

The technology baseline ([ADR-0001](0001-technology-baseline.md)) forbids a dependency without a
concrete reason, and the architecture forbids global mutable state. A font or an icon library
would be a dependency; files bundled as resources are not.

Measured with JavaFX 25.0.4 on Windows 11: `Font.loadFont` registers the Medium and the SemiBold
face of IBM Plex Sans under the family names their files carry, "IBM Plex Sans Medm" and "IBM
Plex Sans SmBld", and a request for the family "IBM Plex Sans" with a weight of 500 or 600 falls
back to the Regular face. A stylesheet therefore cannot select the faces by weight.

## Decision

- **Typeface:** IBM Plex Sans, Regular, Medium and SemiBold, bundled as TrueType files under
  `src/main/resources/prism/srm/ui/fonts/` with the SIL Open Font License 1.1 next to them. No
  italic, no other weight, no IBM Plex Mono: a monospaced face was considered for the path and
  the command line of a process and not taken, because a single cut line gains little from it and
  it would add a fourth file and a fourth family name.
- `prism.srm.ui.Typefaces` loads the three files once, in `App.init()`, and checks that each
  face was registered under the family name the stylesheets use. If a file is missing, fails to
  load, or is registered under another name, the typeface is not relied on at all: the scene gets
  `fonts-fallback.css`, which puts every type role on the system font, and the cause is logged.
  The stylesheets name the faces directly (`-fx-font-family: "IBM Plex Sans SmBld"`), never by
  `-fx-font-weight`.
- **Icons:** a subset of Lucide (ISC License; the icons it took from Feather also under the MIT
  License), embedded as the path data of their strokes in `prism.srm.ui.icon.Icon` and drawn by
  `IconView` as a few `SVGPath` nodes scaled from the 24 unit grid. No icon library, no icon font.
- The notices both licenses require are in `THIRD-PARTY-NOTICES.md` at the root of the
  repository and in the header of `Icon.java`.

## Alternatives considered

- **Keep the system font.** Three weights are not available through JavaFX from the system font
  (a weight resolves to regular or bold), and widths differ per system, so column widths and
  the type scale could not be designed once.
- **An icon library (Ikonli) or an icon font.** A dependency and a font file of thousands of
  glyphs for about a dozen icons; the embedded path data is a few hundred bytes.
- **Selecting faces by `-fx-font-weight`.** Does not work for loaded faces that are not
  regular or bold (measured); a face named by its registered family does.
- **Loading the fonts lazily or per scene.** The registry is process-wide; loading once at
  start is the only place that cannot load twice.

## Consequences

- The jar grows by about 0.6 MB; the fonts are read once at start (measured in milestone 5.5,
  docs/PERFORMANCE.md).
- The family names are a fact of JavaFX and of the font files. They were measured on Windows;
  on Linux and macOS they are expected to be the same, because JavaFX reads the files with its
  own parser, but this is unverified. A mismatch is reported and falls back; it never renders a
  wrong face silently. `TypefacesTest` checks the files are bundled and, with the toolkit, the
  registered names.
- `ArchitectureTest` allows `Font.loadFont` in `Typefaces` only, so the fonts cannot be loaded a
  second time elsewhere.
- Column widths and other measured sizes are derived from the loaded font at run time where they
  matter (the process table), so the fallback font does not break the layout.
