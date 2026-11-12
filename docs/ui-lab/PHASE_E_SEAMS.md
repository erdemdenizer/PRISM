# Phase E seams (left open by Phases C and D)

Phases C and D changed the shell and the design system and restyled the pages only as far as the
tokens and shared controls reach. What a page still needs, and where it plugs in:

| Area | What is open | Seam |
|---|---|---|
| Shell | Inspector docking from 1280 px | `Shell` sets the `docked` pseudo-class at ≥ 1280; no inspector region exists yet. |
| Shell | Ultrawide caps (≥ 2200) | `ultrawide` pseudo-class is set; no page caps its measure yet (tables and graphs stretch). |
| Shell | Full-bleed page toolbars | The page header is still `PageHeader` inside the page padding; a toolbar host above the scroll pane is not built. |
| Shell | Limits badge | `Sidebar.setBadge` exists; only OOM Guard uses it (memory pressure). No clean real-state signal for Limits was chosen (e.g. "a limit failed"). |
| Processes | Inspector instead of the bottom details panel; overlay at < 1280 | `ProcessesPage` details panel and action bar; the `docked` pseudo-class. |
| Processes | Column widths at 1920 px: columns stay at their preferred widths and leave the right third empty | `ProcessTable` column policy. |
| Limits | Editor as an inline panel instead of dialogs | `LimitsPage` + the limit dialogs in `ui.control`. |
| Performance / Overview | Per-metric data colors (spec §4.3): every graph is still in the accent | graph.css sets `-srm-graph-line: -srm-accent` for every graph; a per-panel class (e.g. `.metric-cpu`) can override it; tokens `-srm-data-*` do not exist yet. |
| Overview | Key readout 24 px (spec: 30) | `.text-key-readout` in app.css; the minimum window height is the limit. |
| All | Body text 13 px (spec: 13.5) | `.root` font size in app.css; 13.5 shifts every measured width (TextWidths, tables). |
| All | 3:1 boundaries for buttons (see CONTRAST.md) | Outer background layer of `.button` in app.css. |
| Title bar | Native-API review of `WindowsWindowChrome` by `native-api-reviewer` | Not run in Phase C/D (a fork may not start agents). |
| Title bar | ADR: DWM calls on the JavaFX Application Thread | The lookup must run on the thread that owns the windows; see the report of Phases C and D. |
