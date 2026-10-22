# UI guide

The design language of the System Resource Manager. Token values here are implemented in
`src/main/resources/prism/srm/ui/app.css`; the two must stay in agreement. Screens and
their content are defined by [PRODUCT_SPEC.md](PRODUCT_SPEC.md), not here.

## Character

A professional desktop system utility: restrained, dense but readable, functional,
clean, native-feeling, mature, precise. It should sit comfortably next to Windows Task
Manager, macOS Activity Monitor and professional developer tools, without copying any
of them. The data is the interface; chrome stays quiet.

It must not look like generic AI-generated dashboard software. Never use:

- gradients, glassmorphism, blur, neon or glowing borders
- giant rounded cards, card-heavy layouts, cards inside cards
- hero typography or oversized numbers used as decoration
- decorative badges, excessive pills or chips
- heavy or layered drop shadows
- animation that does not help the user understand a change
- purple/blue "AI" palettes
- emojis anywhere in the UI
- charts that decorate or show meaningless data

## Color

Neutral surfaces, one accent, semantic colors only where they mean something.

| Token | Light | Dark | Use |
|---|---|---|---|
| `-srm-surface` | `#ffffff` | `#1e2124` | content areas: table body, inputs, graph background |
| `-srm-surface-alt` | `#f6f7f8` | `#25292d` | window background, toolbars, status bar, table header |
| `-srm-surface-sunken` | `#eef0f2` | `#181a1d` | wells, inactive selection, track of meters |
| `-srm-border` | `#d5d9dd` | `#363b41` | separators, panel and table borders, graph grid |
| `-srm-border-strong` | `#b6bcc3` | `#4a5058` | input borders, emphasized dividers |
| `-srm-text` | `#1b1f23` | `#e4e7ea` | primary text |
| `-srm-text-muted` | `#5b636b` | `#a0a7ae` | secondary text, units, column headers, placeholders |
| `-srm-text-disabled` | `#899098` | `#6b727a` | disabled text; 3.0:1 on `-srm-surface-alt` in both themes |
| `-srm-accent` | `#0e7c86` | `#3fb3bd` | focus ring, default button, graph line, active tab indicator |
| `-srm-accent-text` | `#ffffff` | `#0b1f21` | text on accent |
| `-srm-selection` | `#d9ecee` | `#1f4347` | selected row/item background |
| `-srm-ok` | `#2e7d32` | `#6fbf73` | a confirmed healthy state, when stating it matters |
| `-srm-warning` | `#b35c00` | `#e0a040` | a condition approaching a limit |
| `-srm-error` | `#c4281c` | `#f0736a` | a failure or a limit exceeded |

Rules:

- The accent marks interaction and the primary data series. It is not decoration; do
  not tint panels or headings with it.
- Warning and error colors appear only on the value or row they describe, driven by a
  real condition. Never color a healthy value green just because it is healthy.
- Never encode meaning by color alone: pair it with text, an icon, or position.
- Body text contrast is at least 4.5:1 against its background; muted text included.
  Disabled text is the exception: 3.0:1 on `-srm-surface-alt` (see the table), so it is
  not placed on `-srm-surface-sunken`, where the light value falls below that.
- Colors come from tokens. No hex values in Java code, inline styles, or component CSS.
- The dark theme is the `theme-dark` style class on the scene root; both themes are
  designed together.

## Typography

The system font (JavaFX `System`: Segoe UI on Windows, the system font on macOS and
Linux). Two weights only: regular and semibold. JavaFX resolves a weight of the system
font to its regular or its bold face, so "semibold" is written `-fx-font-weight: bold`
and renders bold; the type roles are the `text-*` classes of `app.css`.

| Role | Size | Weight | Use |
|---|---|---|---|
| Caption | 11 px | regular | graph axis labels, units, dense metadata |
| Small | 12 px | regular | status bar, secondary lines, table column headers |
| Body | 13 px | regular | default text, table cells, controls |
| Section title | 13 px | semibold | group headings inside a view |
| View title | 16 px | semibold | title of a view or dialog |
| Key readout | 20 px | semibold | a single current value that is the point of a panel |

Nothing larger than 20 px. Hierarchy comes from weight, color and spacing before size.
Numbers in tables and readouts are right-aligned with a fixed number of decimals per
column so digits line up; units go in the header or in muted text, not repeated in bold.
Sentence case for labels, headers and buttons. No all-caps except established
abbreviations (CPU, PID, RAM).

## Spacing and shape

- Spacing scale: 4, 8, 12, 16, 24 px. Nothing off the scale.
  - 4: inside compact controls, between an icon and its label
  - 8: between related controls, padding of toolbars and cells
  - 12: padding of panels
  - 16: between groups
  - 24: between major regions (rare; this is a dense tool)
- Corner radius: 4 px for controls, 6 px for panels and popups, 8 px maximum (dialogs).
  Tables, toolbars and the status bar have square corners.
- Borders are 1 px in `-srm-border`. Separate regions with a border or with spacing,
  not both, and not with shadows. Shadows are allowed only on popups and dialogs, small
  and single-layer.
- Panels are flat regions divided by borders. Do not wrap every group in a card; never
  nest cards.
- Align to a shared grid: left edges of labels, baselines of text in a row, right edges
  of numeric columns.

## Controls

- Compact desktop sizing: 28 px high buttons, inputs and combo boxes; 8 px horizontal
  padding (12 px for text buttons); 24 px for icon-only toolbar buttons.
- A text field is flat: `-srm-surface` with a 1 px border in `-srm-border-strong` and a
  4 px radius. With the keyboard focus the border takes the accent color; it is a
  background layer, so focus moves nothing.
- A context menu is a flat popup: `-srm-surface` with a 1 px border in
  `-srm-border-strong`, a 6 px radius and a small single-layer shadow in the same color.
  Items are compact lines; the item the keyboard is on has `-srm-selection`.
- One default (accent) button per dialog or view at most. Other actions are plain
  buttons. Destructive actions are plain buttons with explicit wording and a
  confirmation, not red buttons everywhere.
- Every interactive element is keyboard reachable in a logical order, has a visible
  focus ring in the accent color, and has a tooltip if it is icon-only.
- Disabled controls use `-srm-text-disabled` and explain why when the reason is not
  obvious (tooltip or adjacent text), for example a capability that is `UNAVAILABLE`.
- Icons: one outline family at 16 px, monochrome in the current text color. Never mix
  families. The family is chosen by ADR when the first icon is needed.
- Scroll bars are flat: a thumb in `-srm-border-strong` with a 4 px radius, 2 px inside a
  12 px track in `-srm-surface-alt`, darker under the pointer; no gradient, no outline,
  no arrow buttons.

## The process table

The table is the primary interface.

- Row height 26 px; header 28 px; 8 px horizontal cell padding; 13 px text.
- No zebra striping by default; a 1 px bottom border in `-srm-border` at most. Selection
  uses `-srm-selection`; the focused row is distinguishable from merely selected rows.
- Text columns left-aligned, numeric columns right-aligned. Column headers align with
  their content.
- Long names and paths are truncated with an ellipsis and available in full via tooltip.
  They never wrap and never push other columns.
- Sorting by any column, with a clear indicator; sort order and selection survive
  refreshes; the scroll position does not jump when data updates.
- Columns have sensible minimum widths; the name column absorbs extra width on resize.
- Full keyboard operation: arrow keys, Home/End, Page Up/Down, type-to-find where the
  spec asks for it, a context menu reachable from the keyboard.
- Values update in place without flicker; a changed value is not animated.

## Graphs

- 60-second histories are lightweight line or area graphs drawn on a Canvas or custom
  Region from a bounded buffer. One Node per sample is forbidden.
- 1 px line in `-srm-accent` (a second series, if needed, in `-srm-text-muted`); optional
  flat fill of the same color at about 12% opacity; never a gradient.
- Grid lines in `-srm-border`, few and quiet. Axis labels in caption size, muted. The
  scale is stated and stable; an auto-scaled axis shows its current maximum.
- No animation or easing between samples. No decorative graphs: every graph shows real
  data that helps a decision.
- Minimum height 64 px; graphs stretch horizontally with the window.

## States

Every view defines these deliberately:

- **Loading**: structure visible at once, with a short muted text such as "Reading
  processes..."; no full-screen spinners, no skeleton shimmer.
- **Empty**: one muted sentence saying what is absent and, if applicable, why (for
  example a filter with no matches).
- **Error**: what failed and what the user can do, in plain words, next to the thing
  that failed. Permission problems say that access was denied, not that something broke.
- **Disabled / unavailable**: visible but inactive, with the reason.
- **Extreme values**: 100% CPU, very large memory figures, thousands of rows, very long
  names must not break layout.

## Shell

- Navigation is a fixed 160 px column at the left: the seven destinations as a list,
  28 px entries with 4 px between them, text only. The selected entry has the selection
  color and a 2 px accent bar at its left edge. The list is one stop in the Tab order;
  the arrow keys move the selection.
- A destination without a page is listed, disabled. One caption line at the bottom of
  the column says what the dimmed entries mean, because a disabled control shows no
  tooltip.
- The status bar is one line of small muted text at the bottom, square, with a single
  border toward the page. A failure is stated in words and takes the error color.
- A page has 12 px padding and no title of its own: the navigation names it. The page
  fills its area; if the window is too small for the least a page needs, the area
  scrolls, and the navigation and the status bar stay where they are.

## Performance page

- The page shows one resource at a time. A row of four entries at its top (CPU, Memory,
  Disk, Network) chooses it: entries like those of the navigation, 28 px high with 4 px
  between them, text only. The selected entry has the selection color and a 2 px accent
  bar at its bottom edge. The row is one stop in the Tab order; the arrow keys move the
  selection. There are 12 px between the row and the section.
- A section begins with the metric panel of the Overview (name, current value, 60-second
  graph) and continues with what belongs to the resource, set apart by 16 px. The name
  of the panel says what its value is the value of: "Total CPU", "Physical memory",
  "Physical disks, total", "Physical adapters, total".
- The logical processors are a grid of small histories on one Canvas. A cell is a framed
  box on `-srm-surface` with one line of caption text, the number of the processor muted
  at the left and its current value in `-srm-text` at the right, and the history below
  with one grid line at half the scale. Cells are 4 px apart, at least 64 px high, and
  at least as wide as the longest label and the widest value need. As many columns are
  used as fit; given more height, rows are added while the cells would be tall strips.
- File systems and interfaces are tables with the metrics of the process table. A cell
  with a number sets the number against one edge and its unit, muted, in a part of a
  fixed width beside it, so the digits of all rows line up whatever the units are. A
  value that is missing is an en dash. A table is never higher than its rows need; the
  room it leaves goes to the graph above it. An empty table says in one muted sentence
  why it is empty.
- A total that is missing says so where its value would be, and the reason, as far as it
  is known, stands in small muted text at the right of the heading of the table below.

## Processes page

- From top to bottom: one line with the filter at the left and the number of processes
  at the right, the process table, and the details of the selected process. 12 px
  between them, as on the Performance page; the table takes the room that is left.
- The filter is a text field, 260 px wide, with the prompt "Filter by name, PID or user".
  It filters the rows the table holds as the user types; case is ignored the same way in
  every locale. Escape clears it. The count says "394 processes", or "12 of 394
  processes" while a filter is set.
- The table has the nine columns of the specification in its order. Every column but
  the name has a fixed width, wide enough for its largest value, so digits do not move
  as values change; the name column takes the room that is left and is never narrower
  than 160 px. CPU, memory and I/O are a number with its unit, as in the tables of the
  Performance page. PID and priority are shown as the system numbers them, without
  grouping. A value the system does not report is an en dash, also in the State column.
- The table opens sorted by name. A click on a header sorts by that column, a second
  click turns the order around, a third takes the column out; without a column the rows
  are in the order of their PIDs. The same is done without a mouse through the context
  menu of the table: with the table focused, Shift+F10 or the Menu key opens it, the
  arrow keys move through it and Enter chooses. "Sort by" lists the nine columns;
  "Ascending" and "Descending" set the direction. A column chosen there is sorted
  smallest first, as by a first click. The menu shows the state of the header each time it
  opens; while the rows are unsorted no column is marked and the directions are disabled.
  A right click opens the same menu. The stock sort arrow marks the column, in
  `-srm-text-muted`. A process without a value in the sorted column comes last in either
  direction.
- In a window too narrow for all columns the table scrolls sideways. The first five
  columns (name, PID, CPU, memory, I/O) fit the smallest window; the right and left
  arrow keys bring the last and the first columns into view. Scrolled as far as it goes,
  the table begins with a whole column: the last column is widened by what that takes,
  because a number that lost its first digits still looks like a number.
- The selected row keeps `-srm-selection` while the table does not have the focus, as
  while the user types in the filter: the details below describe that row. The row the
  keyboard is on is told apart by its accent ring. On the selection the unit of a number
  is in `-srm-text`.
- A name or user that does not fit is cut with an ellipsis and has a tooltip with the
  whole text. A text that fits has no tooltip.
- The details are a flat region without a border: the name in the weight of a section
  title with "PID n" set back beside it; the path and the command line, each on one line
  with a muted caption; and the values that have no column (start time, parent PID,
  private memory, virtual size, I/O read, I/O write) as captions with their values, all
  of one width, so they stand in columns, three to a line at most. Every caption is as
  wide as the longest, so all values begin at one edge. The region has the same lines
  whether a process is selected or not: selecting one moves nothing.
- A path is cut in its middle, so that the file name stays readable; a command line at
  its end. Both have a tooltip with the whole text while they are cut; the tooltip shows
  at most 2,000 characters and says how many it left out.
- Text that stands in for a value is muted: "Reading…" while path and command line are
  being read, the reason when they cannot be shown, an en dash for a value the system
  does not report, and "No process selected". When the selected process has exited, its
  name stays, muted, with "PID n has exited" beside it; a long name is cut, the sentence
  is not. A sentence in the place of a path is cut at its end, like any sentence.

## Window and layout behavior

- Default window 1100 x 700, minimum 720 x 480. Every view is usable at the minimum.
  These are JavaFX units, which are pixels on a monitor at 100% and more pixels on a
  scaled one: the minimum is the same number of units on every monitor, also after the
  window has been moved from one to another.
- Resizing gives space to data (table, graphs), not to padding.
- No motion by default. If a transition clarifies a state change, it lasts 150 ms at
  most and never delays interaction.

## Review checklist

Before a screen is called complete, render it with the snapshot harness
([TESTING.md](TESTING.md)) and check the PNGs:

- [ ] several window sizes including the minimum
- [ ] alignment and spacing on the 4/8 px scale
- [ ] table density, column sizing, numeric alignment
- [ ] empty, loading, error, and disabled states
- [ ] keyboard focus visible
- [ ] long process names; 100% CPU and very large memory values
- [ ] light and dark themes
- [ ] nothing from the "never use" list
- [ ] hover, popups and tooltips checked in the running app (not visible in snapshots)
