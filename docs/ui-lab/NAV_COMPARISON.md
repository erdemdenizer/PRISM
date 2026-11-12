# Navigation: icon rail or top bar (UI lab, Phase C)

Decision: **a 60 px icon rail**, Settings pinned at the bottom, Logs in a group of its own after the
acting pages. Below 820 px the rail becomes a drawer opened from a 32 px button in the page header.

## How it was compared

`NavComparisonProbe` (a probe run by hand, tag `ui-snapshot`, lab only) renders the real shell with the Overview
page twice per size and theme: once as the shell is (rail), once with the rail removed and a 44 px top
bar built around it with the same tokens. The top bar's entries are icon 16 + gap 8 + name, 12 px
padding on each side, 2 px apart, 16 px margins; entries that do not fit go into a 32 px "More" menu.
The test also measures each name with `Text` in **IBM Plex Sans Medm 13.5 px** and writes
`nav-measure.txt`.

Renders and the measurement file: `lab-evidence/C-nav/` (`nav-rail-*` and `nav-top-*`, dark and light,
720×480, 1100×700, 1600×500).

## Measurements

| Destination | Name (px) | Top-bar entry (px) |
|---|---|---|
| Overview | 58 | 106 |
| Processes | 64 | 112 |
| Performance | 81 | 129 |
| Limits | 38 | 86 |
| OOM Guard | 72 | 120 |
| Logs | 29 | 77 |
| Settings | 51 | 99 |
| **All seven** | | **741** |

| Window | Top bar: room | Fits | Top bar: vertical cost | Rail: horizontal cost | Rail: vertical cost |
|---|---|---|---|---|---|
| 720×480 | 688 px | 6 of 7 + More | 44 px (9.2 % of the height) | 0 (drawer; 32 px button inside the header row) | 0 |
| 1100×700 | 1068 px | 7 of 7 | 44 px (6.3 %) | 60 px (5.5 % of the width) | 0 |
| 1600×500 | 1568 px | 7 of 7 | 44 px (8.8 %) | 60 px (3.8 %) | 0 |

## Reading

- **Height is the scarce dimension.** Inside the Windows frame the minimum window leaves 704×441 for
  the scene, 417 px of it for the page above the status line, and the pages are tuned to fit that
  without scrolling (UI-8; Overview, Processes and OOM Guard fit only with the `:low` density rules of
  v2). A 44 px bar would leave 373 px, so those pages would scroll at the minimum size. A
  1600×500 window, a common side-by-side layout on a wide monitor, loses 8.8 % of a short height to a
  bar that spends most of its 1568 px on nothing (741 px used).
- **Width is cheap where the rail exists.** At 1100 px and wider the rail costs 3.8–5.5 % of the width;
  the graphs and the process table take extra width gracefully, and they do not take extra height
  well (table rows, graph detail).
- **At 720 px the top bar overflows** (Settings goes into "More"), and Settings is the one
  destination users look for in a fixed place. The rail variant has no overflow at any width: below
  820 px it becomes a drawer with full names.
- **Grouping reads better vertically.** The rail shows three groups with hairlines: watched
  (Overview, Processes, Performance), acting (Limits, OOM Guard), record (Logs). Logs follows the
  acting pages because it is read after an action. Settings stands apart at the bottom. A top bar
  can only separate groups with extra gaps, which cost width it does not have at 720 px.
- **Names.** The rail shows icons only; every entry has its name as accessible text and as a tooltip
  with its shortcut (`Overview    Ctrl+1`), and the drawer shows names. The top bar's advantage
  (names always visible) is real for a first session; the shortcuts, tooltips and the page title
  directly beside the rail compensate.
- **Badges.** A dot on a 20 px icon (OOM Guard under memory pressure) is easy to place on the rail;
  in a top bar it competes with the text.

## Result

Rail (implemented in `Shell`/`Sidebar`, `shell.css`). Behaviour:

- ≥ 820 px: rail, 60 px, chrome surface, 40×40 entries, selected entry = selection surface + accent
  icon + 3 px accent bar on the rail edge.
- < 820 px: no rail; a 32 px button at the top left of the page opens the drawer (232 px, raised
  surface, scrim, 200 ms slide, none with reduced motion). Focus goes into the drawer at the selected
  entry; Esc, a click on the scrim, a choice or Ctrl+B closes it and returns focus to the button.
  Tab does not leave the open drawer.
- Ctrl+1 … Ctrl+7 choose a destination at every width; Ctrl+B toggles the drawer when narrow.
