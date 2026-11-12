# Contrast of the v2 tokens (UI lab, Phases D to F)

WCAG 2.2 contrast ratios of the token pairs the v2 stylesheets actually draw, computed from the hex
values in `src/main/resources/prism/srm/ui/app.css` (relative luminance, sRGB). Thresholds:
4.5 for text (SC 1.4.3), 3.0 for UI components and graphics such as borders, focus rings and icons
(SC 1.4.11). Disabled controls are exempt from 1.4.3; they are still listed, on the backgrounds
they are really drawn on.

The tables are generated: `python -I lab-docs/contrast/tokens.py` reads the tokens from app.css and
prints them (exit status 1 if a pair with a need fails). Regenerated on 2026-11-09 for the navigation
tokens of fix round G (SH-05), and on 2026-11-11 for the surface edge and the quiet hover of fix round H. The values are for the opaque tokens; nothing in v2 draws text on a translucent
layer.

## Light

| Pair | Foreground | Background | Ratio | Need | |
|---|---|---|---|---|---|
| Body text on canvas | `-srm-text` #15181e | `-srm-bg` #f6f7f9 | 16.58 | 4.5 | pass |
| Body text on panel | `-srm-text` #15181e | `-srm-surface` #ffffff | 17.78 | 4.5 | pass |
| Body text on raised (inspector, dialogs, menus) | `-srm-text` #15181e | `-srm-surface-raised` #ffffff | 17.78 | 4.5 | pass |
| Body text on chrome (rail, toolbar, status line) | `-srm-text` #15181e | `-srm-chrome` #eef0f3 | 15.57 | 4.5 | pass |
| Secondary text on panel | `-srm-text-muted` #4a5262 | `-srm-surface` #ffffff | 7.85 | 4.5 | pass |
| Secondary text on raised | `-srm-text-muted` #4a5262 | `-srm-surface-raised` #ffffff | 7.85 | 4.5 | pass |
| Secondary text on canvas | `-srm-text-muted` #4a5262 | `-srm-bg` #f6f7f9 | 7.33 | 4.5 | pass |
| Secondary text on chrome (toolbar counts) | `-srm-text-muted` #4a5262 | `-srm-chrome` #eef0f3 | 6.88 | 4.5 | pass |
| Captions on panel | `-srm-text-subtle` #5c6474 | `-srm-surface` #ffffff | 5.95 | 4.5 | pass |
| Captions on chrome (lab mark) | `-srm-text-subtle` #5c6474 | `-srm-chrome` #eef0f3 | 5.21 | 4.5 | pass |
| Text on selection | `-srm-text` #15181e | `-srm-selection` #e6e8f9 | 14.62 | 4.5 | pass |
| Text on control (button label) | `-srm-text` #15181e | `-srm-control` #f3f4f7 | 16.16 | 4.5 | pass |
| Selected navigation icon | `-srm-accent` #4f5bd5 | `-srm-nav-selected` #d9def6 | 4.15 | 3.0 | pass |
| Selected navigation name | `-srm-text` #15181e | `-srm-nav-selected` #d9def6 | 13.32 | 4.5 | pass |
| Navigation selection bar on chrome (tells the selection, SH-05) | `-srm-accent` #4f5bd5 | `-srm-chrome` #eef0f3 | 4.85 | 3.0 | pass |
| Navigation selection bar on the drawer | `-srm-accent` #4f5bd5 | `-srm-surface-raised` #ffffff | 5.54 | 3.0 | pass |
| Navigation shortcut under the pointer | `-srm-text-subtle` #5c6474 | `-srm-nav-hover` #dfe2e8 | 4.58 | 4.5 | pass |
| Navigation hover on chrome | `-srm-nav-hover` #dfe2e8 | `-srm-chrome` #eef0f3 | 1.14 | – | exempt |
| Navigation selection surface on chrome | `-srm-nav-selected` #d9def6 | `-srm-chrome` #eef0f3 | 1.17 | – | exempt |
| Primary button label | `-srm-accent-text` #ffffff | `-srm-accent-fill` #4f5bd5 | 5.54 | 4.5 | pass |
| Primary button, hover | `-srm-accent-text` #ffffff | `-srm-accent-fill-hover` #5a66df | 4.77 | 4.5 | pass |
| Destructive confirm label | `-srm-danger-text` #ffffff | `-srm-danger-fill` #b0222a | 6.76 | 4.5 | pass |
| Destructive confirm label, hover | `-srm-danger-text` #ffffff | `-srm-danger-fill-hover` #bd2a32 | 5.93 | 4.5 | pass |
| OK text | `-srm-ok` #0e6b42 | `-srm-surface` #ffffff | 6.56 | 4.5 | pass |
| Warning text | `-srm-warning` #8a5100 | `-srm-surface` #ffffff | 6.45 | 4.5 | pass |
| Error text | `-srm-error` #b0222a | `-srm-surface` #ffffff | 6.76 | 4.5 | pass |
| Error text on selection (selected row) | `-srm-error` #b0222a | `-srm-selection` #e6e8f9 | 5.56 | 4.5 | pass |
| Error text on unfocused selection | `-srm-error` #b0222a | `-srm-selection-inactive` #e6e8ee | 5.51 | 4.5 | pass |
| Error text on control (Force kill) | `-srm-error` #b0222a | `-srm-control` #f3f4f7 | 6.14 | 4.5 | pass |
| Error text on control, hover (Force kill) | `-srm-error` #b0222a | `-srm-control-hover` #eceef2 | 5.82 | 4.5 | pass |
| Warning text on selection | `-srm-warning` #8a5100 | `-srm-selection` #e6e8f9 | 5.30 | 4.5 | pass |
| Info text | `-srm-info` #0b6e99 | `-srm-surface` #ffffff | 5.67 | 4.5 | pass |
| Input border (text field, check box, radio) | `-srm-border-strong` #7f8898 | `-srm-bg` #f6f7f9 | 3.33 | 3.0 | pass |
| Input border on raised | `-srm-border-strong` #7f8898 | `-srm-surface-raised` #ffffff | 3.57 | 3.0 | pass |
| 3:1 control edge on canvas | `-srm-control-edge` #7f8898 | `-srm-bg` #f6f7f9 | 3.33 | 3.0 | pass |
| 3:1 control edge on raised (checked box, dialogs) | `-srm-control-edge` #7f8898 | `-srm-surface-raised` #ffffff | 3.57 | 3.0 | pass |
| 3:1 control edge on chrome | `-srm-control-edge` #7f8898 | `-srm-chrome` #eef0f3 | 3.13 | 3.0 | pass |
| Segment thumb edge on its sunken track | `-srm-control-edge` #7f8898 | `-srm-surface-sunken` #eef0f3 | 3.13 | 3.0 | pass |
| Checked box fill on raised (edge carries it) | `-srm-accent-fill` #4f5bd5 | `-srm-surface-raised` #ffffff | 5.54 | – | exempt |
| Surface edge (panel, table frame, sheet) on canvas | `-srm-edge` #e1e4e9 | `-srm-bg` #f6f7f9 | 1.19 | – | exempt |
| Surface edge on the panel surface inside it | `-srm-edge` #e1e4e9 | `-srm-surface` #ffffff | 1.27 | – | exempt |
| Quiet button under the pointer on raised (sheet close) | `-srm-control-hover` #eceef2 | `-srm-surface-raised` #ffffff | 1.16 | – | exempt |
| Quiet button under the pointer on the chrome band (page header) | `-srm-nav-hover` #dfe2e8 | `-srm-chrome` #eef0f3 | 1.14 | – | exempt |
| Button border (soft, see notes) on canvas | `-srm-control-border` #c9ced6 | `-srm-bg` #f6f7f9 | 1.47 | – | exempt |
| Button border (soft) on chrome | `-srm-control-border` #c9ced6 | `-srm-chrome` #eef0f3 | 1.38 | – | exempt |
| Scroll thumb at rest on panel | `-srm-border-strong` #7f8898 | `-srm-surface` #ffffff | 3.57 | 3.0 | pass |
| Scroll thumb at rest on canvas | `-srm-border-strong` #7f8898 | `-srm-bg` #f6f7f9 | 3.33 | 3.0 | pass |
| Focus ring on canvas | `-srm-focus-ring` #4f5bd5 | `-srm-bg` #f6f7f9 | 5.17 | 3.0 | pass |
| Focus ring on selection (focused row) | `-srm-focus-ring` #4f5bd5 | `-srm-selection` #e6e8f9 | 4.56 | 3.0 | pass |
| Graph line | `-srm-accent` #4f5bd5 | `-srm-surface` #ffffff | 5.54 | 3.0 | pass |
| Data: CPU | `-srm-data-cpu` #4f5bd5 | `-srm-surface` #ffffff | 5.54 | 3.0 | pass |
| Data: CPU, processor grid on canvas | `-srm-data-cpu` #4f5bd5 | `-srm-bg` #f6f7f9 | 5.17 | 3.0 | pass |
| Data: memory | `-srm-data-memory` #0e7a66 | `-srm-surface` #ffffff | 5.26 | 3.0 | pass |
| Data: disk read | `-srm-data-disk-read` #b0306a | `-srm-surface` #ffffff | 6.04 | 3.0 | pass |
| Data: disk read on canvas | `-srm-data-disk-read` #b0306a | `-srm-bg` #f6f7f9 | 5.63 | 3.0 | pass |
| Data: disk read on chrome | `-srm-data-disk-read` #b0306a | `-srm-chrome` #eef0f3 | 5.29 | 3.0 | pass |
| Data: disk write (dashed) | `-srm-data-disk-write` #8c2a56 | `-srm-surface` #ffffff | 8.14 | 3.0 | pass |
| Data: disk write on canvas | `-srm-data-disk-write` #8c2a56 | `-srm-bg` #f6f7f9 | 7.59 | 3.0 | pass |
| Data: disk write on chrome | `-srm-data-disk-write` #8c2a56 | `-srm-chrome` #eef0f3 | 7.13 | 3.0 | pass |
| Data: network download | `-srm-data-net-down` #0b6e99 | `-srm-surface` #ffffff | 5.67 | 3.0 | pass |
| Data: network upload (dashed) | `-srm-data-net-up` #3a7ea3 | `-srm-surface` #ffffff | 4.47 | 3.0 | pass |
| Disabled label on a disabled button (control) | `-srm-text-disabled` #828a97 | `-srm-control` #f3f4f7 | 3.17 | – | exempt |
| Disabled label on raised | `-srm-text-disabled` #828a97 | `-srm-surface-raised` #ffffff | 3.48 | – | exempt |
| Disabled label on chrome | `-srm-text-disabled` #828a97 | `-srm-chrome` #eef0f3 | 3.05 | – | exempt |
| Disabled label on canvas | `-srm-text-disabled` #828a97 | `-srm-bg` #f6f7f9 | 3.25 | – | exempt |

## Dark

| Pair | Foreground | Background | Ratio | Need | |
|---|---|---|---|---|---|
| Body text on canvas | `-srm-text` #eceef2 | `-srm-bg` #1b1e24 | 14.37 | 4.5 | pass |
| Body text on panel | `-srm-text` #eceef2 | `-srm-surface` #20242b | 13.40 | 4.5 | pass |
| Body text on raised (inspector, dialogs, menus) | `-srm-text` #eceef2 | `-srm-surface-raised` #262b33 | 12.25 | 4.5 | pass |
| Body text on chrome (rail, toolbar, status line) | `-srm-text` #eceef2 | `-srm-chrome` #16181d | 15.29 | 4.5 | pass |
| Secondary text on panel | `-srm-text-muted` #a9b1be | `-srm-surface` #20242b | 7.21 | 4.5 | pass |
| Secondary text on raised | `-srm-text-muted` #a9b1be | `-srm-surface-raised` #262b33 | 6.59 | 4.5 | pass |
| Secondary text on canvas | `-srm-text-muted` #a9b1be | `-srm-bg` #1b1e24 | 7.73 | 4.5 | pass |
| Secondary text on chrome (toolbar counts) | `-srm-text-muted` #a9b1be | `-srm-chrome` #16181d | 8.22 | 4.5 | pass |
| Captions on panel | `-srm-text-subtle` #8f98a8 | `-srm-surface` #20242b | 5.36 | 4.5 | pass |
| Captions on chrome (lab mark) | `-srm-text-subtle` #8f98a8 | `-srm-chrome` #16181d | 6.11 | 4.5 | pass |
| Text on selection | `-srm-text` #eceef2 | `-srm-selection` #343b5a | 9.42 | 4.5 | pass |
| Text on control (button label) | `-srm-text` #eceef2 | `-srm-control` #2d323b | 11.08 | 4.5 | pass |
| Selected navigation icon | `-srm-accent` #7c8cff | `-srm-nav-selected` #343b5a | 3.67 | 3.0 | pass |
| Selected navigation name | `-srm-text` #eceef2 | `-srm-nav-selected` #343b5a | 9.42 | 4.5 | pass |
| Navigation selection bar on chrome (tells the selection, SH-05) | `-srm-accent` #7c8cff | `-srm-chrome` #16181d | 5.96 | 3.0 | pass |
| Navigation selection bar on the drawer | `-srm-accent` #7c8cff | `-srm-surface-raised` #262b33 | 4.78 | 3.0 | pass |
| Navigation shortcut under the pointer | `-srm-text-subtle` #8f98a8 | `-srm-nav-hover` #2a2e36 | 4.68 | 4.5 | pass |
| Navigation hover on chrome | `-srm-nav-hover` #2a2e36 | `-srm-chrome` #16181d | 1.30 | – | exempt |
| Navigation selection surface on chrome | `-srm-nav-selected` #343b5a | `-srm-chrome` #16181d | 1.62 | – | exempt |
| Primary button label | `-srm-accent-text` #ffffff | `-srm-accent-fill` #4f5ee3 | 5.19 | 4.5 | pass |
| Primary button, hover | `-srm-accent-text` #ffffff | `-srm-accent-fill-hover` #5564e8 | 4.80 | 4.5 | pass |
| Destructive confirm label | `-srm-danger-text` #ffffff | `-srm-danger-fill` #c93a3f | 5.05 | 4.5 | pass |
| Destructive confirm label, hover | `-srm-danger-text` #ffffff | `-srm-danger-fill-hover` #d24449 | 4.50 | 4.5 | pass |
| OK text | `-srm-ok` #3fcf8e | `-srm-surface` #20242b | 7.81 | 4.5 | pass |
| Warning text | `-srm-warning` #f2b34b | `-srm-surface` #20242b | 8.40 | 4.5 | pass |
| Error text | `-srm-error` #ff8585 | `-srm-surface` #20242b | 6.63 | 4.5 | pass |
| Error text on selection (selected row) | `-srm-error` #ff8585 | `-srm-selection` #343b5a | 4.66 | 4.5 | pass |
| Error text on unfocused selection | `-srm-error` #ff8585 | `-srm-selection-inactive` #30343d | 5.31 | 4.5 | pass |
| Error text on control (Force kill) | `-srm-error` #ff8585 | `-srm-control` #2d323b | 5.48 | 4.5 | pass |
| Error text on control, hover (Force kill) | `-srm-error` #ff8585 | `-srm-control-hover` #343a44 | 4.88 | 4.5 | pass |
| Warning text on selection | `-srm-warning` #f2b34b | `-srm-selection` #343b5a | 5.90 | 4.5 | pass |
| Info text | `-srm-info` #5bc8f5 | `-srm-surface` #20242b | 8.17 | 4.5 | pass |
| Input border (text field, check box, radio) | `-srm-border-strong` #6b7486 | `-srm-bg` #1b1e24 | 3.55 | 3.0 | pass |
| Input border on raised | `-srm-border-strong` #6b7486 | `-srm-surface-raised` #262b33 | 3.03 | 3.0 | pass |
| 3:1 control edge on canvas | `-srm-control-edge` #6b7486 | `-srm-bg` #1b1e24 | 3.55 | 3.0 | pass |
| 3:1 control edge on raised (checked box, dialogs) | `-srm-control-edge` #6b7486 | `-srm-surface-raised` #262b33 | 3.03 | 3.0 | pass |
| 3:1 control edge on chrome | `-srm-control-edge` #6b7486 | `-srm-chrome` #16181d | 3.78 | 3.0 | pass |
| Segment thumb edge on its sunken track | `-srm-control-edge` #6b7486 | `-srm-surface-sunken` #181b20 | 3.67 | 3.0 | pass |
| Checked box fill on raised (edge carries it) | `-srm-accent-fill` #4f5ee3 | `-srm-surface-raised` #262b33 | 2.74 | – | exempt |
| Surface edge (panel, table frame, sheet) on canvas | `-srm-edge` #363a43 | `-srm-bg` #1b1e24 | 1.47 | – | exempt |
| Surface edge on the panel surface inside it | `-srm-edge` #363a43 | `-srm-surface` #20242b | 1.37 | – | exempt |
| Quiet button under the pointer on raised (sheet close) | `-srm-control-hover` #343a44 | `-srm-surface-raised` #262b33 | 1.24 | – | exempt |
| Quiet button under the pointer on the chrome band (page header) | `-srm-nav-hover` #2a2e36 | `-srm-chrome` #16181d | 1.30 | – | exempt |
| Button border (soft, see notes) on canvas | `-srm-control-border` #3a3f49 | `-srm-bg` #1b1e24 | 1.58 | – | exempt |
| Button border (soft) on chrome | `-srm-control-border` #3a3f49 | `-srm-chrome` #16181d | 1.68 | – | exempt |
| Scroll thumb at rest on panel | `-srm-border-strong` #6b7486 | `-srm-surface` #20242b | 3.31 | 3.0 | pass |
| Scroll thumb at rest on canvas | `-srm-border-strong` #6b7486 | `-srm-bg` #1b1e24 | 3.55 | 3.0 | pass |
| Focus ring on canvas | `-srm-focus-ring` #8e9bff | `-srm-bg` #1b1e24 | 6.59 | 3.0 | pass |
| Focus ring on selection (focused row) | `-srm-focus-ring` #8e9bff | `-srm-selection` #343b5a | 4.32 | 3.0 | pass |
| Graph line | `-srm-accent` #7c8cff | `-srm-surface` #20242b | 5.23 | 3.0 | pass |
| Data: CPU | `-srm-data-cpu` #7c8cff | `-srm-surface` #20242b | 5.23 | 3.0 | pass |
| Data: CPU, processor grid on canvas | `-srm-data-cpu` #7c8cff | `-srm-bg` #1b1e24 | 5.61 | 3.0 | pass |
| Data: memory | `-srm-data-memory` #3ccfb4 | `-srm-surface` #20242b | 7.99 | 3.0 | pass |
| Data: disk read | `-srm-data-disk-read` #f07aa6 | `-srm-surface` #20242b | 5.96 | 3.0 | pass |
| Data: disk read on canvas | `-srm-data-disk-read` #f07aa6 | `-srm-bg` #1b1e24 | 6.39 | 3.0 | pass |
| Data: disk read on chrome | `-srm-data-disk-read` #f07aa6 | `-srm-chrome` #16181d | 6.80 | 3.0 | pass |
| Data: disk write (dashed) | `-srm-data-disk-write` #c8648c | `-srm-surface` #20242b | 4.19 | 3.0 | pass |
| Data: disk write on canvas | `-srm-data-disk-write` #c8648c | `-srm-bg` #1b1e24 | 4.50 | 3.0 | pass |
| Data: disk write on chrome | `-srm-data-disk-write` #c8648c | `-srm-chrome` #16181d | 4.78 | 3.0 | pass |
| Data: network download | `-srm-data-net-down` #5bc8f5 | `-srm-surface` #20242b | 8.17 | 3.0 | pass |
| Data: network upload (dashed) | `-srm-data-net-up` #4a9fcb | `-srm-surface` #20242b | 5.27 | 3.0 | pass |
| Disabled label on a disabled button (control) | `-srm-text-disabled` #7a8292 | `-srm-control` #2d323b | 3.33 | – | exempt |
| Disabled label on raised | `-srm-text-disabled` #7a8292 | `-srm-surface-raised` #262b33 | 3.68 | – | exempt |
| Disabled label on chrome | `-srm-text-disabled` #7a8292 | `-srm-chrome` #16181d | 4.60 | – | exempt |
| Disabled label on canvas | `-srm-text-disabled` #7a8292 | `-srm-bg` #1b1e24 | 4.32 | – | exempt |

## Notes

- The tightest passing pairs: white on the dark danger fill under the pointer, 4.503 (`#d24449`);
  the dark error text on the focused selection, 4.66; the light primary button under the pointer,
  4.77 (dark 4.80); the dark 3:1 edge (and input border) on the raised surface, 3.03; the light
  3:1 edge on the chrome and on the sunken track of a segmented control, 3.13 each. Do not lighten
  the accent or danger fills, lighten the dark selection, or darken `-srm-border-strong` /
  `-srm-control-edge` without measuring again.
- The accent is never used as text (StylesheetsTest); as an icon on the selection it is a graphic
  (3.0 needed).
- Fix round H (PR-11): `-srm-edge` is the edge of a surface on the canvas (a panel, the frame of a
  table, a sheet). Light it is the border (1.19 on the canvas); dark it is `#363a43`, 1.47 on the
  canvas where the border was 1.28 and read as no edge. Decorative, not held to 3.0. The graph grid
  stays on `-srm-border`.
- Fix round H: a quiet button under the pointer takes `-srm-control-hover` (it took
  `-srm-surface-hover`, 1.05 on the dark raised surface: no visible hover on the close button of a
  sheet), and on the chrome band of a page header `-srm-nav-hover` (the light control hover is
  1.02 on the light chrome).
- `-srm-hairline` and `-srm-border` are decorative separators, not component boundaries; they are
  not held to 3.0. Text fields, check boxes and radio buttons draw their edge in
  `-srm-border-strong`.
- Two edges for controls (Phase F, critique H4). `-srm-control-edge` (the value of
  `-srm-border-strong`) stays where the edge is what identifies the control or its state: text
  fields, check boxes and radio buttons (checked too: the accent fill alone is 2.74 on the dark
  raised surface, so the edge stays around it), and the thumb of a segmented control, which alone
  says which entry is chosen (it was 1.04 light / 1.34 dark against its track with a hairline).
  Buttons, menu buttons, toggle buttons and the track of a segmented control draw the softer
  `-srm-control-border` (light `#c9ced6`, dark `#3a3f49`; 1.4 to 1.7 against what surrounds them).
  The 3:1 outline made every button read as a classic, boxed control. A button with words is
  identified by its words, which are text at 4.5:1 or more; WCAG's Understanding document for
  SC 1.4.11 says a button's boundary is not required to contrast when its text identifies it.
  Icon-only buttons are quiet (no outline at rest) and carry an accessible name and a tooltip, as
  before. The keyboard focus is the 2 px ring in every case.
- Disabled buttons are drawn on the control surface under a hairline (they were on `-srm-bg`, a
  dark hole on raised surfaces). `-srm-text-disabled` was raised to `#7a8292` dark and `#828a97`
  light so a disabled label is at least 3:1 on the control surface, the raised surface, the canvas
  and the chrome (it was 2.36 dark and 2.71 light, measured then on `-srm-control` although
  disabled buttons were drawn on `-srm-bg`). Disabled stays visibly weaker than secondary text
  (5.96 dark on the control surface).
- Dark `-srm-error` is `#ff8585` (was `#ff6b6b`: 3.94 on the focused selection, 4.49 unfocused,
  4.12 for Force kill under the pointer). `-srm-danger-fill` is a separate token and unchanged.
- The row the keyboard is on has a 2 px ring in `-srm-focus-ring` (was 1 px), distinct from the
  selection fill and its 3 px bar; the table frame draws no ring of its own.
- The scroll thumb is opaque at rest (it was at 70 %, 2.2 to 2.4 against the surfaces).
- The notes under the emergency actions of the OOM management dialog stay `-srm-text-muted` while
  the actions are disabled ("Not in effect: …" measured 2.98 light / 2.61 dark in the disabled
  color); only the radio buttons are dimmed.
- Per-metric data colors (spec section 4.3): each graph draws its line in the token of its metric
  through a class of the panel (`metric-cpu`, `metric-memory`, `metric-disk`, `metric-network`),
  both on Overview and on Performance; the processor grid takes the CPU color. Disk moved from
  violet to rose in Phase F (critique M12): violet was the CPU and the accent hue, so the disk
  graph read as a second CPU graph. The second series of a panel (disk write, network upload) is a
  dashed line (4 px drawn, 3 left out) and its legend swatch is a two-dash mark, so the two series
  stay apart without color. The area under a line is a translucent tint of the line; it carries no
  information of its own.
- The title bar (Windows 11): caption `-srm-chrome`, caption text `-srm-text` (15.57 light, 15.29
  dark), border `-srm-hairline`.
