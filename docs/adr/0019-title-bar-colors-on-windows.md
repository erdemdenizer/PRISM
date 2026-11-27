# 0019. Title bar colors on Windows 11, set on the JavaFX Application Thread

- Status: Accepted
- Date: 2026-11-27

## Context

In the redesign, PRISM colors the frame Windows draws around its windows (the caption bar, the caption
text and the one-pixel border) with the colors of its theme. The window stays `DECORATED`, so Windows
still draws and handles the caption: dragging, snap, the system menu and the caption buttons
([R1-titlebar.md](../design/research/R1-titlebar.md),
[TITLEBAR_VALIDATION_TR.md](../design/TITLEBAR_VALIDATION_TR.md)). JavaFX 25 has no API for these colors.
`StageStyle.EXTENDED` with `HeaderBar` is a preview in 25. Its dialogs cannot use it, and the later
releases that develop it would change the frozen baseline (ADR-0001). The colors are therefore set
through the Desktop Window Manager. This shipped with the redesign (`WindowsWindowChrome`, `TitleBarTint`)
without a decision record and without the native-API review the project requires. This ADR records both.

The project's rule is that the JavaFX Application Thread makes no native call (CLAUDE.md,
[ARCHITECTURE.md](../ARCHITECTURE.md) §Threading). This feature cannot follow it entirely, and the
exception is stated here.

## Decision

1. **Values.** `DwmSetWindowAttribute` sets four attributes, each a 4-byte value, in this order:
   - `DWMWA_USE_IMMERSIVE_DARK_MODE` (20), a BOOL;
   - `DWMWA_CAPTION_COLOR` (35), a COLORREF `0x00BBGGRR`;
   - `DWMWA_TEXT_COLOR` (36), a COLORREF;
   - `DWMWA_BORDER_COLOR` (34), a COLORREF.

   Every HRESULT is checked. A reset sets the dark mode off and `DWMWA_COLOR_DEFAULT` (0xFFFFFFFF) for
   the three colors. Microsoft documents all four as "supported starting with Windows 11 Build 22000".
   Sources: the
   [DWMWINDOWATTRIBUTE](https://learn.microsoft.com/en-us/windows/win32/api/dwmapi/ne-dwmapi-dwmwindowattribute),
   [DwmSetWindowAttribute](https://learn.microsoft.com/en-us/windows/win32/api/dwmapi/nf-dwmapi-dwmsetwindowattribute)
   and [COLORREF](https://learn.microsoft.com/en-us/windows/win32/gdi/colorref) pages. `jna-platform`
   5.19.1 has no `dwmapi` mapping, so this one function is mapped in the class.
2. **Build gate.** `VerifyVersionInfoW` checks `VER_BUILDNUMBER >= 22000` once, and nothing is set below
   that build.
   - The function is deprecated and depends on the executable's manifest: without the Windows 10
     compatibility GUID, the system reports itself as Windows 8, the gate fails, and the frame keeps the
     system's colors, which is the safe side.
   - The Temurin 25 `java.exe` declares the GUID. The jpackage launcher of the image is not checked yet.
   - `ERROR_OLD_WIN_VERSION` is told apart from any other error in the logged reason.
   - Windows Server builds of 22000 or later pass the gate, although the attributes are documented for
     Windows 11 only. A refused value is a FAILED result there, and nothing breaks.
3. **Window identification.** The native window of a stage is the window of the calling thread
   (`EnumThreadWindows` with `GetCurrentThreadId`) whose class name begins with
   `GlassWndClass-GlassWindowClass-` and whose text is exactly the stage's title.
   - Exactly one match is changed. None gives NOT_FOUND, and more than one gives AMBIGUOUS; neither
     changes anything.
   - The class name is an implementation detail of JavaFX 25, measured on build 26200.
     `WindowsWindowChromeOnTheRunningSystemTest` (UI snapshots, Windows only) shows a stage and colors
     it through the real calls, so a rename shows as a failing test. It must be rerun after a JavaFX
     upgrade.
   - Titles of stages shown at the same time must be distinct. With two stages of one title, the one
     whose native title is set first could be colored for the other. The effect is cosmetic and stays
     inside PRISM.
4. **The exception to the threading rule.** Only these calls run on the JavaFX Application Thread, per
   window and per change of theme, focus or contrast:
   - `GetCurrentThreadId`, `EnumThreadWindows`, `GetClassNameW`, `GetWindowTextLengthW`, `GetWindowTextW`;
   - `DwmSetWindowAttribute`.

   The reasons:
   - The lookup needs the identity of the thread that owns the windows.
   - Only that thread can destroy them
     ([DestroyWindow](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-destroywindow)),
     so the handle cannot go stale between the lookup and the set.
   - `GetWindowText` sends `WM_GETTEXT` to a window of this process. From another thread that send
     waits for the JavaFX Application Thread
     ([GetWindowText](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-getwindowtextw),
     [SendMessage](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-sendmessagew)),
     which is worse than running on it. On the owning thread the window procedure is called directly.
   - Stage properties belong to that thread anyway.

   Loading the libraries (JNA's dispatch library and `dwmapi`) and the build check do not need that
   thread. They run once in `App.init()`, on the JavaFX launcher thread, before the first window.

   Bounds and measurements:
   - An attempt makes 2 + N + 2k + 4 calls (N windows of the thread, k of them stage windows).
   - A stage is tried at most 20 times on later turns after it is shown, then once per change.
   - Measured on Windows 11 build 26300: the first attempt on a shown stage took 4.9 ms, and a reset 0.8 ms.
   - How long `DwmSetWindowAttribute` can take is not documented.
5. **Failure policy.**
   - Failures are results, never exceptions. A binding that cannot be loaded (`LinkageError` included,
     for example JNA unable to unpack its library into a temporary folder a policy blocks) makes the
     feature unavailable. PRISM still starts.
   - A refused color ends the attempt, and the system's own colors are then asked back, best-effort.
   - A reset tries all four values whatever each answers, so that a high-contrast theme never keeps a
     custom caption because an earlier value was refused.
   - The one report of a run is logged from a thread of its own (`srm-title-bar-log`), because a log line
     is a file write.
   - The user sees nothing of a failure: the frame keeps the system's colors.
6. **Accessibility.** While a Windows high-contrast theme is on, the frame is reset to the system's
   colors. The border keeps one color whether or not the window is active. The DWMWA_BORDER_COLOR note
   leaves activation states to the application, and the inactive caption is told apart by its dimmed
   text.

## Alternatives considered

- **`StageStyle.EXTENDED` with `HeaderBar`.** A preview in JavaFX 25. Dialogs cannot use it, and adopting
  the releases that finish it would change the frozen baseline (ADR-0001).
- **The whole lookup on a background thread** (`EnumWindows` filtered by process). It still reads the
  title, which waits for the JavaFX Application Thread, or it uses `InternalGetWindowText`, which
  Microsoft says "may be altered or unavailable". The handle could also be destroyed between the lookup
  and the set.
- **The handle from Glass.** It needs JavaFX internals or reflection, which the project forbids.
- **No title bar colors.** The redesign's frame would then stay light beside a dark window. The feature
  is cosmetic, so this remains the fallback whenever anything fails.

## Consequences

- One documented exception to the threading rule, limited to the calls listed in decision 4. Every
  other native call stays off the JavaFX Application Thread.
- The class name is watched by a test that runs with the UI snapshots on Windows. A JavaFX upgrade
  requires that run.
- Not verified:
  - Windows 10, where the gate makes the feature unavailable;
  - a real high-contrast session;
  - "Show accent color on title bars";
  - a live change of the system theme;
  - the color flash on first show;
  - a real 150 % or 200 % display;
  - Windows Server;
  - the jpackage launcher's manifest.
- To revisit when JavaFX offers frame colors for `DECORATED` stages, or when the baseline moves to a
  release with a final `HeaderBar`.
