# UI guide

The design language of PRISM (Process & Resource Interactive System Manager), "Quiet Precision". Token values here are
implemented in `src/main/resources/prism/srm/ui/app.css`; the two must stay in agreement.
Screens and their content are defined by [PRODUCT_SPEC.md](PRODUCT_SPEC.md), not here. The
typeface and the icons are bundled assets ([ADR-0008](adr/0008-bundled-typeface-and-icons.md));
the theme follows the platform ([ADR-0009](adr/0009-theme-follows-platform-preferences.md)).

## Character

A professional cross-platform desktop system utility: quiet, precise, dense, highly legible and
clearly designed. It should sit comfortably next to the best macOS and Windows utilities and
professional developer tools without imitating any of them: no Windows Settings clone, no
Activity Monitor clone, no SaaS dashboard, no "AI" look. The data is the interface; the chrome
supports it.

Principles:

1. **Quiet hierarchy.** Hierarchy comes from the weight of the type, from spacing, grouping and
   the contrast of surfaces, never from decoration added to look designed.
2. **Premium restraint.** It looks expensive because the details are resolved: aligned baselines,
   tabular digits, one border weight, a consistent 4 px grid.
3. **Dense, not cramped.** 28 px rows, 30 px controls, 12 px page margins. Breathing room comes
   from alignment, not from inflation toward mobile sizes.
4. **Data first.** Tables and graphs are primary; the shell stays in the tone of the window.
5. **Modern desktop, not web dashboard.** Sidebar navigation, page headers, a segmented
   selector, inspectors. No card grids, hero areas, chips or mobile patterns.
6. **Cross-platform identity.** The same typeface, icons and tokens on every operating system.
7. **Performance is part of visual quality.** Motion is short, finite and never applied to data;
   nothing draws on a timer.

Never use: gradients, glassmorphism or acrylic, blur, neon or glow, giant dashboard cards, cards
inside cards, excessive pills or chips, hero typography, decorative charts, fake status
indicators, continuous or decorative animation, heavy or layered shadows, purple/blue "AI"
palettes, emoji, excessive whitespace, per-row decorative icons, effects that need a timer, a
custom frameless window or title bar. Shadows exist only on popups, small and single-layer.

The window keeps the native chrome of the platform: its title bar, caption buttons, resizing,
snapping and shadows are the operating system's. A custom frameless window is not the design
direction. In a final build the native chrome matches the effective appearance of the window
(milestone 13): a light Windows title bar above a dark Prism window is a defect, not a look.

## Color

Neutral surfaces, one blue accent for interaction and the primary data series, semantic colors
only where they mean something. The light tokens are the default; the dark tokens apply under
`@media (prefers-color-scheme: dark)`, which the scene evaluates against the platform or against
the scheme the application forces (see Theme).

| Token | Light | Dark | Use |
|---|---|---|---|
| `-srm-bg` | `#f4f5f7` | `#1b1e22` | the window: page background, status bar, table header |
| `-srm-surface` | `#ffffff` | `#23272c` | content surfaces: panels, table body, graphs, inputs, popups |
| `-srm-surface-sunken` | `#eceef1` | `#15181b` | the navigation column, wells |
| `-srm-surface-hover` | `#eef0f2` | `#2b3036` | hover of a navigation entry, a segment, a table row |
| `-srm-border` | `#d9dde2` | `#353a41` | panel and table borders, separators, graph grid |
| `-srm-border-strong` | `#b7bec7` | `#4b525b` | input, segmented selector and popup borders, scroll thumb |
| `-srm-text` | `#1a1d21` | `#e6e8eb` | primary text |
| `-srm-text-muted` | `#5a6370` | `#b0b6be` | secondary text, captions, column headers, units, placeholders |
| `-srm-text-disabled` | `#7f8791` | `#6f7781` | disabled text and icons |
| `-srm-accent` | `#2b6fe8` | `#6ea8ff` | focus ring, selection bar, the icon of the selected destination, the primary series |
| `-srm-accent-text` | `#ffffff` | `#0b1a33` | text on the accent |
| `-srm-selection` | `#dbe7fb` | `#2a4472` | the selected entry, segment or row while its control has the focus |
| `-srm-selection-inactive` | `#dadfe6` | `#353b44` | the selected row while the table does not have the focus |
| `-srm-icon` | contextual | contextual | the color of an icon, set by its container |
| `-srm-ok` | `#2e7d32` | `#6fbf73` | a confirmed healthy state, when stating it matters |
| `-srm-warning` | `#a65400` | `#e0a040` | a condition approaching a limit |
| `-srm-error` | `#c4281c` | `#f0736a` | a failure or a limit exceeded |
| `-srm-shadow` | `rgba(0,0,0,.14)` | `rgba(0,0,0,.5)` | the one shadow, on tooltips and menus |

Rules:

- The accent marks interaction and the primary data series. It is never used as text (on the
  window background it falls short of the contrast text needs; `StylesheetsTest` enforces it)
  and never tints panels or headings.
- Warning and error colors appear only on the value or row they describe, driven by a real
  condition. Never color a healthy value green just because it is healthy.
- Never encode meaning by color alone: pair it with weight, an icon, a shape or a position.
- Body and muted text contrast is at least 4.5:1 against every surface they stand on. Disabled
  text is at least 3:1 on `-srm-bg`, `-srm-surface` and `-srm-surface-sunken`.
- Colors come from tokens. No hex values in Java code, inline styles, or component CSS.

## Theme

The appearance is one of `SYSTEM`, `LIGHT` and `DARK` (`prism.srm.domain.ThemeMode`), default
`SYSTEM`: the window is light or dark as the operating system prefers at the moment, and follows
a change of that preference while the application runs. `LIGHT` and `DARK` force a scheme.
`prism.srm.app.ThemeController` applies the mode through `Scene.Preferences` to the window and to
every popup, which has a scene of its own. The Settings page (UI-7) exposes the three modes and
persists the choice. The snapshot harness renders
each theme by setting the scheme on its scene. The scheme applies to the scene; the native
title bar above it is Windows' own and, until milestone 13 colors it through the platform
layer, can stay light above a dark window.

## Typography

One typeface, IBM Plex Sans, bundled in three faces: Regular, Medium, SemiBold. JavaFX registers
the Medium and the SemiBold face under the family names "IBM Plex Sans Medm" and "IBM Plex Sans
SmBld"; the type roles name those families directly, never `-fx-font-weight`. If the typeface
fails to load, `fonts-fallback.css` puts every role on the system font, bold where a heavier
face is needed. The faces are loaded once, in `App.init()`; no other code loads a font.

| Role | Class | Face | Size | Use |
|---|---|---|---|---|
| Caption | `text-caption` | Regular | 11 px | graph axis captions and scales, fact captions, the legend of the navigation |
| Small | `text-small` | Regular | 12 px | status bar, column headers (Medium), the number of processes, PID beside a name, tooltips |
| Body | (default) | Regular | 13 px | all text, table cells, controls, menus |
| Body emphasized | `text-medium` | Medium | 13 px | the values of a two-series panel, the sorted column header (12 px) |
| Section heading | `text-section-title` | SemiBold | 13 px | panel names, section headings, the name of the selected process; the selected destination and segment |
| Page title | `text-page-title` | SemiBold | 20 px | the title of a page, once per page |
| Key readout | `text-key-readout` | SemiBold | 22 px | the one current value of a metric panel |

Nothing larger than 22 px. Plex Sans digits are tabular, so numbers line up without any help;
numbers in tables and readouts are right-aligned with a fixed number of decimals per column, and
units go in the header or in muted text. Sentence case for labels, headers and buttons; no
all-caps except established abbreviations (CPU, PID, RAM). The processor cells of the
Performance page draw their 11 px text on the Canvas in the same face.

## Spacing and shape

- Spacing scale: 4, 8, 12, 16, 24 px. Nothing off the scale.
  - 4: inside compact controls, between an icon and a chevron and its text, between navigation entries
  - 8: between related controls, padding of toolbars and cells, between an icon and its label
  - 12: padding of pages and panels, between the parts of a page
  - 16: between groups
  - 24: between the facts of a facts panel
- Corner radius: 4 px for controls, navigation entries, processor cells and tooltips; 6 px for
  panels and menus; 8 px maximum (dialogs). Tables, the segmented selector's entries (3 px inside
  its 4 px frame), toolbars and the status bar have square or minimal corners.
- Borders are 1 px in `-srm-border`; input, selector and popup borders in `-srm-border-strong`.
  Separate regions with a border or with spacing, not both, and never with shadows.
- A **panel** (`.panel`) is a content surface: `-srm-surface`, 1 px `-srm-border`, 6 px radius,
  12 px padding (8 px vertical for the compact variant), no shadow. Panels are used only where a
  group benefits from a surface of its own: the four metric panels, the facts, the process
  inspector. Not every section is a panel, and a panel never holds another panel; a graph inside
  a panel has no frame of its own.
- Align to a shared grid: left edges of labels, baselines of text in a row, right edges of
  numeric columns.

## Controls

- Compact desktop sizing: 30 px high text fields and buttons; 28 px navigation entries and
  segmented selector; 8 px horizontal padding (12 px for text buttons); 20 px for the small
  icon-only buttons inside a field.
- A text field is flat: `-srm-surface` with a 1 px border in `-srm-border-strong` and a 4 px
  radius; the border takes `-srm-text-muted` under the pointer and the accent with the keyboard
  focus, with a 1 px halo of the accent at 40 % outside it. Both are background layers, so focus
  moves nothing.
- The filter field has the search icon at its left, inside the field, and a 20 px clear button
  at its right while it holds text; Escape clears it from the keyboard, so the button is not a
  Tab stop.
- The **segmented selector** (Performance) is one 1 px `-srm-border-strong` frame with a 4 px
  radius around a row of 28 px entries with 1 px dividers; a `-srm-selection` surface marks the
  selected entry, whose text is SemiBold, and slides to the entry chosen. It is the one
  pill-like control of the application and stays compact; nothing else takes this shape.
- A tooltip is `-srm-surface` with a 1 px `-srm-border-strong` border, a 4 px radius, 4/8 px
  padding, 12 px text and the one shadow. A context menu is the same surface with a 6 px radius
  and 4 px padding; items are compact lines, and the item the keyboard is on has `-srm-selection`.
- One default (accent) button per dialog or view at most. Other actions are plain buttons.
  Destructive actions are plain buttons with explicit wording and a confirmation.
- Every interactive element is keyboard reachable in a logical order and has a visible focus
  ring: 1 px in `-srm-accent`, drawn as a border or background layer that is always there and
  takes its color only with the keyboard focus (`:focus-visible`). Focus is never animated and
  never hidden because a pointer screenshot looks cleaner.
- A button and a menu button with the keyboard focus take the accent border and the 1 px halo
  of a focused text field, so the focus is never mistaken for the default button of a dialog,
  which has the accent border alone; the default button with the keyboard focus takes the halo
  too.
- Disabled controls use `-srm-text-disabled` at full opacity and explain why when the reason is
  not obvious (tooltip or adjacent text), for example a capability that is `UNAVAILABLE`. This
  holds for disabled menu items and the label of a disabled menu button, which the stock
  stylesheet would dim to 40 %. A menu item that states a fact rather than offering a choice
  (the priority a process has when it is none of the levels) is disabled, so the keyboard
  passes it, and drawn in `-srm-text-muted`.
- **Icons** are Lucide, 16 px in navigation and controls (12 px for the chevrons of the sort
  mark and the clear button), monochrome in the color the container sets through `-srm-icon`:
  muted at rest, `-srm-accent` on the selected destination, `-srm-text-disabled` when disabled.
  One family, never mixed, never large, never decorative, never per row.
- Scroll bars are flat: a thumb in `-srm-border-strong` with a 4 px radius, 2 px inside a 12 px
  track in `-srm-bg`, darker under the pointer; no gradient, no outline, no arrow buttons.
- A check box and a radio button are 16 px boxes (the radio round) on 28 px lines, 8 px from
  their words. Their layers are those of a text field: the border, the color under the pointer,
  and the focus ring with its halo.
  - Checked takes the accent, with the mark or dot in `-srm-accent-text`.
  - Disabled, the box is drawn in `-srm-text-disabled`, so that it stays visible on the dark
    surface.
  - The color changes in 100 ms, only while motion is not reduced.

## States and motion

Every view defines these deliberately:

- **Loading**: structure visible at once, with a short muted text such as "Reading…"; no spinners,
  no skeleton shimmer.
- **Empty**: one muted sentence saying what is absent and, if applicable, why.
- **Error**: what failed and what the user can do, in plain words, next to the thing that failed.
- **Disabled / unavailable**: visible but inactive, with the reason on the page (a metric panel
  says it in its axis line), not only in a tooltip.
- **Extreme values**: 100 % CPU, very large memory figures, thousands of rows, very long names
  must not break layout.

Micro-motion is allowed where it clarifies a change the user caused, and only then:

| Where | What | Duration |
|---|---|---|
| a page coming into the page area | fade in while rising 6 px | 160 ms |
| the selected surface of the segmented selector | slides to the entry chosen | 160 ms |
| the process inspector when a process is shown after none was | fade in while rising 4 px | 140 ms |
| a metric panel changing state ("Reading…" to its values) | crossfade | 120 ms |
| the result of an action appearing beside the actions | fade in, nothing moves | 120 ms |
| navigation entries, segments, text fields under the pointer | CSS transition of the surface or border color | 100–120 ms |

Every motion is finite and quick, with no bounce or spring. The keyboard focus ring appears at
once. Nothing of the monitoring is animated: no row or cell animation on a refresh, no sort or
reorder animation, no counting of a number, no tweening of a graph sample. No `AnimationTimer`
or indefinite `Timeline` exists for appearance, and no transition is applied inside a table,
whose cells change state while they are reused. When the user prefers reduced motion
(`Platform.Preferences`, or the scene's own preference), every duration is zero and the CSS
transitions, which sit under `@media (prefers-reduced-motion: no-preference)`, are off.

## Shell

- Navigation is a fixed 176 px column at the left on `-srm-surface-sunken`, separated from the
  page by one border: the seven destinations as a list of 28 px entries, 4 px apart, each a
  16 px Lucide icon and its name, 8 px between them. The selected entry has the `-srm-selection`
  surface, its name in SemiBold and its icon in `-srm-accent`; its name stays `-srm-text`. The
  keyboard focus is a separate 1 px accent ring. The list is one stop in the Tab order; the
  arrow keys move the selection.
- Icons of the destinations: Overview `layout-dashboard`, Processes `list`, Performance
  `activity`, Limits `sliders-horizontal`, OOM Guard `shield-alert`, Logs `scroll-text`,
  Settings `settings`.
- A destination without a page is listed, disabled, in `-srm-text-disabled`. One caption line
  at the bottom of the column says what the dimmed entries mean.
- The status bar is one 24 px line of small muted text at the bottom, square, with a single
  border toward the page. It states real monitoring state in words and nothing else: no
  indicators, no chips. A failure takes the error color. Memory pressure from OOM Guard is
  stated in the same line, in words, in the warning or error color.
  - It sits at the right end, whole; the monitoring words at the left are cut first, with a
    tooltip.
  - It appears only from Warning on, and only while monitoring runs.
- Every page begins with a **page header**: its title in the page-title role on a 28 px line,
  with 12 px below it, and at the right what belongs to the page as a whole (the number of
  processes on Processes; nothing on Overview). No subtitles. The Performance selector sits on
  its own line below the header, not inside it.
- A page has 12 px padding and 12 px between its parts. The page fills its area; if the window
  is too small for the least a page needs, the area scrolls, and the navigation and the status
  bar stay where they are.

## Metric panels and graphs

- A metric panel is a panel with a 30 px header line (its name in the section-heading role at
  the left; at the right the key readout with its unit in small muted text, or the two values of
  a two-series metric in Medium under their 11 px legends), an 18 px axis line (the caption at the
  left, the scale at the right, both captions; while the value is unavailable the caption says
  why), and the graph.
- 60-second histories are drawn on a Canvas or custom Region from a bounded buffer. One Node per
  sample is forbidden.
- The primary series is a 1.5 px line in `-srm-accent` with a flat fill of the same color at 8 %
  opacity; a second series is a 1 px line in `-srm-text-muted` without fill. Never a gradient.
- The grid is 1 px in `-srm-border`: the ends and the quarters of the scale, and the quarters of
  the time window. Inside a panel the graph has no frame; the lines at the ends of the scale
  bound it. The scale is stated in the axis line and is stable; an auto-scaled axis shows its
  current maximum.
- No animation or easing between samples. No decorative graphs.
- Minimum height 64 px; graphs stretch with the window.
- Facts (the system facts of the Overview, the processor and memory facts of the Performance
  page) stand in a compact facts panel without a title: each fact its 11 px muted caption above
  its 13 px value, 24 px between the facts.

## Tables

- Row height 28 px; header 30 px on `-srm-bg` with one border below; 8 px horizontal cell
  padding; 13 px text; square corners and a 1 px frame that takes the accent with the keyboard
  focus.
- Column headers are 12 px Medium in `-srm-text-muted`; the header of the column the rows are
  sorted by is in `-srm-text` and carries a 12 px chevron right beside its text, after the text
  in a column of text and before it in a column of numbers, up for smallest first and down for
  largest first. The stock mark of the skin is hidden; sorting itself is unchanged.
- No zebra striping. A row under the pointer takes `-srm-surface-hover`; a selected row keeps
  its selection surface under the pointer.
- The selected row carries a 2 px bar in `-srm-accent` at its left edge whether the table has the
  focus or not, so the row the page describes is found without color; its surface is
  `-srm-selection` while the table has the focus and `-srm-selection-inactive` while it does not.
  The row the keyboard is on has a 1 px accent ring; with the selection, the ring and the bar
  share the row. Bars and rings are background layers: the cells never move.
- Text columns left-aligned, numeric columns right-aligned; headers align with their content. A
  value the system does not report is an en dash in `-srm-text-muted`, in `-srm-text` on the
  selected row, as are units.
- Long names and paths are truncated with an ellipsis and available in full via tooltip. They
  never wrap and never push other columns.
- Values update in place without flicker; a changed value is not animated.

## Performance page

- The page shows one resource at a time, chosen in the segmented selector below the page
  header. The row is one stop in the Tab order; the arrow keys move the selection.
- A section begins with the metric panel of the Overview and continues with what belongs to the
  resource, 12 px apart. The name of the panel says what its value is the value of: "Total CPU",
  "Physical memory", "Physical disks, total", "Physical adapters, total".
- The logical processors are a grid of small histories on one Canvas under a section heading
  ("Logical processors", the caption and the scale at its right). A cell is a framed box on
  `-srm-surface` with 4 px corners and one line of caption text, the number of the processor
  muted at the left and its current value in `-srm-text` at the right, and the history below with
  one grid line at half the scale. Cells are 4 px apart, at least 64 px high, and at least as
  wide as the longest label and the widest value need. One row of eight fits the smallest
  window; with more rows the page scrolls.
- File systems and interfaces are tables with the metrics above, under a section heading with
  a muted note at its right. A cell with a number sets the number against one edge and its unit,
  muted, in a part of a fixed width beside it. A value that is missing is an en dash. A table is
  never higher than its rows need; the room it leaves goes to the graph above it.

## Processes page

- From top to bottom: the page header with the number of processes at its right ("394
  processes", or "12 of 394 processes" while a filter is set); the filter; the process table,
  which takes the room that is left; the inspector of the selected process. 12 px between them.
- The filter is 260 px wide, with the search icon and the prompt "Filter by name, PID or user".
  It filters the rows the table holds as the user types; case is ignored the same way in every
  locale. Escape and the clear button empty it.
- The table has the nine columns of the specification in its order. The widths of the fixed
  columns are measured from the loaded font when the table is built (`ColumnWidths`): wide
  enough for their widest value ("9999999", "100.0 %", "1023.9 MiB", "1023.9 KiB/s", "99999",
  "-20", the longest state word) and for their header, on the 4 px grid. Name is the one
  flexible column: at least 160 px, it takes the room that is left up to 520 px, beyond which
  the room stays empty after the last column, so that related columns stay close together in a
  wide window. User follows the widest user name on screen, between a floor (wide enough for
  "SYSTEM") and a ceiling of 160 px, beyond which a name is cut with a tooltip; it changes only
  when the set of names changes.
- The table opens sorted by name. A click on a header sorts by that column, a second click turns
  the order around, a third takes the column out; without a column the rows are in the order of
  their PIDs. The same is done without a mouse through the context menu of the table (Shift+F10
  or the Menu key). A process without a value in the sorted column comes last in either
  direction.
- In a window too narrow for all columns the table scrolls sideways. The first four columns
  (name, PID, CPU, memory) fit the smallest window whole; the right and left arrow keys bring
  the last and the first columns into view. Scrolled as far as it goes, the table begins with a whole column: the last column is
  widened by what that takes.
- The selected row keeps its bar and its inactive surface while the table does not have the
  focus, as while the user types in the filter: the inspector below describes that row.
- The inspector is a compact panel: on its first line the name of the process in the
  section-heading role with "PID n" in small muted text beside it and, at the right, the user it
  runs as; below, the values that have no column (start time, parent PID, private memory,
  virtual size, I/O read, I/O write) as cells of one width, 140 px, each with its 11 px caption
  above its value, six on a line in the default window and three in the smallest; then a 1 px
  separator and the path and the command line, each on one 20 px line with a 96 px caption. The
  panel has the same lines whether a process is selected or not: selecting one moves nothing.
  A path is cut in its middle, a command line at its end; both have a tooltip with the whole
  text while they are cut.
- Text that stands in for a value is muted: "Reading…", the reason a value cannot be shown, an
  en dash, "No process selected". When the selected process has exited, its name stays, muted,
  with "PID n has exited" beside it.
- While the listing shows the selected process as suspended by Prism, "Suspended by Prism"
  stands after its PID in the heading, small and muted, the words of the State column; no badge.
- The actions on the selected process are on the last line of the inspector, plain 30 px
  buttons 8 px apart, in the order of their consequences: Suspend, Resume, Priority, then, 16 px
  apart from them, End task… and Force kill…. Force kill is told apart by its place, its words and
  its confirmation, never by color. The context menu of the table has the same order, with a
  separator between the two groups.
- The rest of that line says one thing at a time, in this order of precedence: the action on its
  way ("Suspending java (PID 4242)…", muted); the result of the last action on the selected
  process as a sentence in the tone of the result (the text color for a success, the warning
  color for a refusal, the error color for a failure of the system) followed by a muted
  explanation of what the sentence does not say; or why the actions are disabled, muted. A result
  belongs to its process: another selection clears it, a later listing does not, and nothing
  clears it on a timer. Where the buttons leave it less than 280 px, the line goes 4 px under
  the buttons and takes the whole width of the inspector; the place depends on the width alone,
  so a result that arrives never moves the buttons. Only where the area is then too low for the
  usual spacing (the smallest window) does the page make up the height, bringing its groups 8 px
  apart, and 8 px from the top and bottom of the area, instead of 12; its header then stands
  4 px higher than on the other pages.
  Where even that is too narrow, the explanation goes first, then the sentence is cut; a tooltip
  carries the whole line while any of it is cut.
- The Priority menu, in the inspector and in the context menu, marks the level the last listing
  shows; it moves only when a listing shows the new level, never when one is asked for. A
  priority that stands for none of the five levels marks none, and a disabled line above them
  says which number it is. The current level is also in what a screen reader and the tooltip of
  the control say ("Priority, current: Normal").
- A confirmation names the process on a line of its own under the question: the name, cut with
  an ellipsis where it is long, and "PID n" beside it, never cut. Cancel is the default button,
  drawn with a 2 px accent border; the act is a plain button with its words in the danger color,
  and an act that cannot be undone (Force kill) has a 1 px border in that color too.

## Limits page

- The table lists the managed processes: Name, PID, CPU limit, Status, Implementation,
  Memory limit. Name, PID, CPU limit and Status stay whole in the smallest window beside the
  vertical scroll bar; the rest scroll. Status speaks of the CPU limit only, and says "No CPU
  limit" where only a memory limit is set. Implementation says how the limit is applied
  ("Native hard", "Emulated", "Emulated (chosen)") and its tooltip names the mechanism.
- Status words claim no more than was measured or read back: "Enforced" only after the system
  read back what was asked; "Not applied, emulated offered", "Failed: limit differs", "Failed:
  threads still held", "Capped by an earlier session" otherwise, in the warning or error tone,
  with the whole explanation in the tooltip.
- Above the table, one line per notice, each in its own tone: a failure of Prism (limits off,
  the ledger) in the error color; a limit that may still be in force where Prism cannot reach
  it (an exited process's job, an earlier session's cap) in the warning color; a fact of the
  system muted. Notices are 4 px apart.
- The actions on the selected process stand in three groups, 8 px apart within a group and
  16 px between groups: the CPU limit (Set CPU limit…, Remove CPU limit, Use emulated limit…),
  the memory limit (Set memory limit…, Remove memory limit), the process (Disable or Enable,
  Unmanage). A narrow window wraps between groups, never inside one. A disabled action says why
  in its tooltip; nothing is hidden.
- The memory limit dialog says that the limit is on committed memory, not on what is in RAM,
  and that a process that reaches it may fail; the fallback dialog says why the native limit
  was not applied, that the emulated limit is approximate, what it costs, and how many may run.
  Cancel is the default button in both.

- After a hard kill and a restart, a process an earlier session may still cap shows "Capped by an
  earlier session" with the recorded values, and the notice above the table says the limit lasts
  until the process exits and that no second limiter is offered (ADR-0012). While a ledger of an
  earlier session is not settled, a warning notice says so, and the emulated limit is not offered
  for processes that ran while it was written.
- A second launch for the same user shows one dialog, "Prism does not start", with the reason and
  one button, Close (ADR-0013); nothing else of Prism appears.

## OOM Guard page

The order of the page, top to bottom:
1. **The header**, with the number of processes under OOM management at the right.
2. **Three facts**: State, Available, Since. Only the value of the state takes a color: warning
   for Warning and Critical, error for Emergency, muted for Off.
3. **The monitoring line**: the switch, the thresholds in muted words, and "Change…".
4. **Automatic intervention**, set apart by space and its heading, not by a frame:
   - the heading, with "Off by default…" beside it;
   - three switches;
   - one muted line that names this system's mechanisms. A switch that cannot act here is
     disabled, and that line says why. Until the first view it says "Reading…".
5. **The managed processes**:
   - notices above the table, one line each, cut with their tooltip. The switches that are
     off are said in one muted line; a journal that cannot be written is said in the error
     color.
   - the table: Name, PID, Emergency action, Status, Priority, Memory. The fixed columns are
     measured from their longest words.
   - below the table, "OOM management…" and "Release OOM Guard hold", with the result line.
6. **Recent automatic actions**, never higher than its rows. Only Process and Result take the
   room of a wide window.

Further rules:
- **Color** is for results only. "Suspend, not in effect" and "Off" are muted, because a
  configuration is never colored. A failure is in the error color, and "Suspended, release
  incomplete" in the warning color.
- **The smallest window.** In a low area the page is compact, as on the Processes page: 8 px
  apart, the switches on tighter lines. The managed table keeps a header and two rows above
  its sideways scroll bar, and its actions stay above the fold. The recent actions scroll
  with the page.
- **Dialogs.** OOM management shows its choices disabled, never hidden, while management is
  off. Choosing Terminate renames the button "Allow termination" and makes Cancel the default.
  The switch for termination asks once, with Cancel as the default. In the thresholds dialog,
  Enter sets valid thresholds and Escape cancels, from any field. A dialog whose content grows or
  shrinks while it is open (the consequence sentence of Terminate, the hint for invalid numbers)
  resizes its window, so its buttons never fall off the bottom; a button renamed while it shows is
  sized by its own words. The CPU and memory limit dialogs follow the same rule. Their window grows
  and shrinks with the hint for an invalid value and with the memory dialog's "Press Set limit" line
  (maintenance after milestone 11; `LimitDialogsSizingSceneTest`).
- **On the Limits page** a watched memory limit is "256 MiB (watched)". It is never called a
  cap: its dialog and its tooltip say that Prism watches the resident memory. On a system with
  a native cap, "Watch resident memory…" is always in its place, disabled until a native limit
  was not applied for the process.

## Window and layout behavior

- Default window 1100 x 700, minimum 720 x 480, in JavaFX units on every monitor. Every view is
  usable at the minimum: the Overview fits without scrolling; the Processes page fits without
  scrolling and shows at least two rows (three until milestone 7 added the actions to the
  inspector; three rows and the actions do not fit 480 px together); the CPU section of the Performance page scrolls from a second row of processor
  cells on.
- Resizing gives space to data (table, graphs), not to padding or to the name column beyond its
  ceiling.

## Review checklist

Before a screen is called complete, render it with the snapshot harness
([TESTING.md](TESTING.md)) in both themes and check the PNGs:

- [ ] several window sizes including the minimum and a wide one (1600 or 1920)
- [ ] alignment and spacing on the 4/8 px scale; panel insets; the 28/30 px table metrics
- [ ] table density, column sizing, numeric alignment, the sort mark beside the header text
- [ ] empty, loading, error, and disabled states; the reason of an unavailable value on the page
- [ ] keyboard focus visible and separate from the selection
- [ ] the selected row found while the table has no focus
- [ ] long process names; 100% CPU and very large memory values
- [ ] light and dark themes, from the same scene preference
- [ ] nothing from the "never use" list; no motion on data; reduced motion honored
- [ ] hover, popups, tooltips, transitions and the system theme switch checked in the running
      app (not visible in snapshots)
