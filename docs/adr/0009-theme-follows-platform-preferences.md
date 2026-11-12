# 0009. The theme follows the platform through Scene preferences

- Status: Accepted
- Date: 2026-10-07

## Context

Milestone 3 decided that the window opens in the light theme and that the choice of theme belongs
to the Settings page (UI-7); the dark theme existed as the `theme-dark` style class on the scene
root, which only the snapshot harness ever set. Milestone 5.5 asks for the appearance of a
modern desktop utility: light or dark as the operating system prefers, following a change of
that preference while the application runs, with a forced light or dark mode that the Settings
page will expose and persist in milestone 13.

JavaFX 25 supplies this without platform code: `Platform.Preferences` reports the color scheme
of the operating system and updates when it changes; a stylesheet can hold rules under `@media
(prefers-color-scheme: dark)`, which JavaFX evaluates against the scene; and `Scene.Preferences`
lets a scene override the platform's scheme with `setColorScheme(LIGHT | DARK)`, or follow it
again with `null`.

Measured with JavaFX 25.0.4 on Windows 11 while planning milestone 5.5: the media query resolves
the dark tokens when the scene is set to `DARK` and the light ones when set to `LIGHT`, whatever
the platform prefers; but the scene of a popup (a tooltip, a context menu) does not take the
override of the scene of its owner window, so with a forced mode a popup would follow the
platform instead.

## Decision

- The appearance is a value of its own, `prism.srm.domain.ThemeMode { SYSTEM, LIGHT, DARK }`,
  default `SYSTEM`. It is a domain type because every layer names it: the composition root
  applies it, the Settings page will show it and the persisted settings will store it.
- `app.css` holds the light tokens in `.root` and the dark tokens in `@media
  (prefers-color-scheme: dark) { .root { … } }`. The `theme-dark` style class is gone.
- `prism.srm.app.ThemeController` applies the mode: `SYSTEM` sets no scheme on the scene, so the
  window follows the operating system; `LIGHT` and `DARK` set one. It also sets the same scheme
  on the scene of every window that opens while the application runs, and on all open windows
  when the mode changes, so that popups and later dialogs match the window. The controller is
  created in `App.start()` for the scene of the main window and closed in `App.stop()`.
- The snapshot harness renders a theme by setting the scheme on its scene, so a render is the
  same on a machine in light mode and on one in dark mode.
- No switch for the mode exists in the user interface until the Settings page (milestone 13).

This supersedes the milestone 3 decision that the window opens light.

## Alternatives considered

- **Reading the theme of the operating system natively** (Windows registry, GTK settings,
  macOS appearance) through JNA or polling: platform code and a timer for what the toolkit
  already reports and observes.
- **Keeping the `theme-dark` style class and toggling it from a `Platform.Preferences`
  listener:** a second mechanism next to the one the stylesheet already has, and it would need
  the same listener for every popup root.
- **Two stylesheets, swapped on change:** the same as the media query with more files and a
  re-parse on every change.

## Consequences

- No native code and no timer. A change of the system appearance is a CSS pass of the scene,
  once.
- The push of the scheme to every window is a workaround for the measured behavior of popups;
  when a JavaFX release lets a popup take the scheme of its owner, the window listener in
  `ThemeController` can go. `ThemeControllerSceneTest` checks that a tooltip gets the scheme of
  the window and that a forced scheme is kept whatever the platform prefers.
- A scene that is in no window still resolves the media query (the harness depends on it).
- The platform preference depends on the operating system and its version; where it is not
  reported JavaFX gives `LIGHT`, which is the look the application had before.
