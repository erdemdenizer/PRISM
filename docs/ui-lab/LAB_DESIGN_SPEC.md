# PRISM UI Lab — Premium UI v2 design spec (build contract)

Isolated lab copy of PRISM (`DOU\PRISM-UI-LAB`, branch `lab/premium-ui-v2`, no remote). The real repo
`DOU\PRISM-internal` is read-only for everybody: never write, build, checkout, stash, commit or run tools
there. The course repo `DOU\PRISM` and any `branding/` content are out of bounds.

Owner's goal: the real JavaFX app, matured into a modern, premium, professional desktop product — Apple-level
discipline and refinement, Windows desktop behaviour preserved. Owner's complaints about the last HTML
prototype: still feels like a classic system tool; title bar disconnected from the app; buttons not refined;
icons weak; surfaces too flat and too dark; typography not strong enough; premium feel relied on glass.

## 0. Hard rules

1. **Presentation layer only.** Change `prism.srm.ui.**`, `prism.srm.app` wiring of the window/shell/theme,
   CSS and icons. Do not change business rules, identity/PID+start-time guards, limit/OOM behaviour, platform
   safety, protection rules, K16, messages that carry safety meaning (wording of results/confirmations stays;
   layout and typography of it may change). Nothing is added to or removed from the seven sections'
   functions (PRODUCT_SPEC). Exception: lab isolation (§9) and the title-bar platform code (§3).
2. **No new dependencies.** No AtlantaFX/GemsFX/Ikonli (research `lab-docs/research/R2-*.md`). JavaFX stays
   25.0.4, JDK 25; no preview API in the default build (see §3 for the lab experiment flag).
3. **Build discipline as in CLAUDE.md:** `./mvnw -o clean verify` must pass (lint -Werror, Spotless,
   SpotBugs, JaCoCo, ArchitectureTest). Never weaken or delete a test to pass; when a UI test encodes the old
   layout and the layout intentionally changes, update the test to assert the new intended behaviour and
   say so in the commit message. Keep keyboard/accessibility tests meaningful.
4. **Concurrency/performance rules unchanged:** nothing blocking on the FX thread; no per-sample nodes; graphs
   stay on Canvas; no effects (dropshadow/blur) on any ancestor of live, frequently updated content (re-renders
   the subtree each pulse and kills LCD text); no CSS transitions inside table cells; no indefinite animation;
   no snapshot-based "glass". Table virtualization and the identity-keyed row model stay.
5. **Commits:** small, conventional (`feat:`, `fix:`, `style:`, `test:`, `docs:`, `chore:`), on
   `lab/premium-ui-v2`, NO Co-Authored-By trailer (owner's rule). Never push (no remote exists anyway).
6. **Locale-safe** formatting, `Locale.ROOT` for identifiers.

## 1. Character

Calm, precise, tactile, lightly dimensional. A pro instrument: graphite surfaces that are *lifted*, not black;
clear tonal layering; one refined accent; beautiful numbers; crisp icons; buttons with real material
(tone + hairline + subtle top highlight); motion that confirms, never performs. No glow, neon, emoji, rainbow,
heavy blur, card stacks, hero type.

## 2. Window anatomy

```
┌ title bar (native caption, DWM-coloured to the app chrome; §3) ───────────────── _ □ × ┐
│ rail │ page toolbar: Title · meta        [search] [segmented] [actions]           │
│ 60px ├──────────────────────────────────────────────────────────────────────────────┤
│ icons│ content (opaque)                                     │ inspector (opaque)    │
│      │                                                      │ (Processes, Limits)   │
│      │                                                      │                       │
│ ⚙ ↓  ├──────────────────────────────────────────────────────────────────────────────┤
│      │ status line (24 px): monitoring state · failures · lab mark                 │
└──────┴──────────────────────────────────────────────────────────────────────────────┘
```

- **Navigation rail** (decision subject to the comparison in `lab-docs/NAV_COMPARISON.md`): 60 px wide, chrome
  tone (one step darker/receding than content). Top group: Overview, Processes, Performance; second group:
  Limits, OOM Guard; then Logs; Settings pinned to the bottom. Icon 20 px Lucide in a 40×40 hit target; selected =
  accent-tinted 40×40 rounded (8) pill + accent icon + 3 px accent indicator on the rail's left edge; hover =
  6–8 % tint. Tooltips (label + Ctrl+N shortcut) after 500 ms; accessible name = section name (+ badge state,
  e.g. "Limits, 2 warnings"). Badges: small count/dot only for real state (Limits warnings, OOM state ≠ Normal).
  No brand logo (branding undecided); no artificial filler content.
- **Page toolbar** (52 px, part of each page, same chrome-adjacent surface as content top): page title
  (22 px SemiBold) + quiet meta, then page tools on the right (search, segmented, primary action). Overflow
  behaviour at narrow widths: search collapses to an icon button that expands; secondary tools move to a
  "More" menu button — never clipped, never wrapping into two rows below 720.
- **Content canvas:** opaque, padding 24 (≥1100 wide), 16 (<1100), 12 (<800).
- **Inspector** (Processes; Limits editor): right panel, default 360 px (min 320, max 440), resizable via
  SplitPane divider at ≥1280; at 900–1279 fixed 320; < 900 wide OR window height < 560 the inspector becomes an
  overlay sheet sliding from the right over the table (scrim, Esc/× closes, focus moves in and returns to the
  row). In tall-narrow windows (aspect height > width) the inspector may dock at the bottom instead of overlay
  (decide by measuring 900×1200). Opening/closing animates translateX+opacity 200 ms; reduced motion → instant.
- **Status line:** 24 px, quiet; monitoring state on the left, failures/lab mark on the right
  ("UI LAB · isolated data" in text-2, neutral).
- **Narrow (< 820 px wide):** the same rail as a medium window, each icon with its name under it (owner
  decision after fix round H: names show at every width; the drawer and Ctrl+B were removed). Keyboard
  shortcuts Ctrl+1…7 work in every mode.
- **Low (height < 560):** status line hides its secondary items; toolbar stays one row; content scrolls.

## 3. Title bar (filled in from Phase B results — `lab-docs/TITLEBAR_*`)

Default lab build: native `StageStyle.DECORATED` caption with DWM attributes set from `platform.windows`:
immersive dark mode follows the scene's colour scheme; caption colour = rail/chrome colour; text colour =
title text colour; border colour = chrome border — so the native caption (with its real min/max/close,
Snap Layouts, system menu, resize, DPI handling) visually merges with the app chrome. Re-applied on theme
change and to every owned window (dialogs). Capability reported honestly (Windows 11 build ≥ 22000 for colours;
otherwise default caption). Experimental, OFF by default: JavaFX 25 preview `StageStyle.EXTENDED` + `HeaderBar`
behind a lab-only flag, validated separately; never the default unless the validation matrix is all PASS and
the owner decides.

## 4. Visual system v2 (tokens; dark is default on first launch)

Dark (lifted graphite, not black):
- chrome (title bar caption colour, rail, status line): #16181D
- canvas (content background): #1B1E24
- surface-1 (panels, table body): #20242B ; surface-2 (raised: inspector, popovers, dialogs): #262B33 ;
  surface-3 (controls at rest): #2D323B ; hover +4 % white ; pressed −3 %
- hairline rgba(255,255,255,.08); strong hairline rgba(255,255,255,.14); top highlight on raised/controls
  inset 1 px rgba(255,255,255,.06) (as a background layer, not an effect)
- text #ECEEF2 / text-2 #A9B1BE / text-3 #8F98A8 (≥4.5:1 on surface-2) / disabled #626A78
- accent (iris) #7C8CFF; accent fill for primary buttons #4F5EE3 (white text 5.19:1), hover #5B6AF0, pressed
  #4553D6; selection tint accent @ 22 %; focus ring #8E9BFF 2 px with 2 px gap
- ok #3FCF8E, warning #F2B34B, danger #FF6B6B (text); danger fill #C93A3F (white 5.05:1); info #5BC8F5
- data: CPU #7C8CFF, Memory #3CCFB4, Disk read #C084FC / write #8B6FD6 (dashed), Net down #5BC8F5 / up
  #3A8FB8 (dashed); thresholds warning/danger dashed
Light: chrome #EEF0F3, canvas #F6F7F9, surface-1 #FFFFFF, surface-2 #FFFFFF (+shadow on popups),
surface-3 #F3F4F7, hairlines rgba(15,20,30,.10/.16), text #15181E / #4A5262 / #5C6474, accent #4F5BD5,
ok #0E6B42, warning #8A5100, danger #B0222A. All text ≥ 4.5:1, UI parts ≥ 3:1 — compute and record.

Depth (no effects on live containers): tone steps + hairlines + 1 px top highlight layers; ONE soft
dropshadow only on popups/menus/tooltips/dialog roots/overlay sheet (they hold no 1 Hz content, or only
small text) — e.g. dropshadow(gaussian, rgba(0,0,0,.35), 18, 0, 0, 6) dark / .14 light. Corner radii:
controls 6, chips 5, panels 10, popovers/dialogs 12; concentric nesting.

Typography: IBM Plex Sans (bundled; Regular / "IBM Plex Sans Medm" / "IBM Plex Sans SmBld" — CSS must name the
family, weights fall back otherwise). Roles: display numerals 30 SmBld (tabular by default), page title 22 SmBld,
section 15 SmBld, body 13.5 Regular, table 13, caption 12 Medm. Units smaller and text-2 next to big numbers.
Measure Segoe UI Variable resolution in JavaFX 25 as an experiment only (record result); do not switch fonts.

Icons: Lucide (ISC) path data in `Icon.java`; `IconView` renders strokes with a **scale-independent stroke
width** (target 1.5 logical px at 16, 1.6 at 20), round caps/joins, snapped to whole logical pixels where the
layout allows; add the glyphs the new UI needs as Lucide paths (record names). Verify crispness at 100/125/150 %.

## 5. Components (all states: rest, hover, pressed, focus-visible, disabled-with-reason, busy)

Buttons 32 px high (compact 28), radius 6, 13.5 Medm, icon 16 + 6 gap:
- **Primary**: accent fill + top highlight layer + 1 px darker bottom hairline; one per view max.
- **Secondary**: surface-3 + hairline + top highlight; hover lighter; pressed darker, highlight off.
- **Quiet**: transparent, hover 6 % tint, pressed 10 %.
- **Destructive**: secondary anatomy with danger text/icon, also as the act of a destructive confirmation
  dialog, where the default Cancel carries a 2 px accent edge (CTL-8 unchanged; fix round G, LO-15: a filled
  danger act outweighed the default Enter presses). An act that cannot be undone (Force kill, allowing
  termination) adds a 1 px danger edge. Red never decorative.
- **Busy**: label becomes "Working…" (or existing pending text), disabled, no spinner, no indefinite animation.
- **Disabled**: content at reduced contrast but the focus ring stays full contrast; reason shown as text nearby
  (existing reason lines stay).
- Focus-visible: 2 px ring with 2 px gap via negative-inset background layers (same layer count in all states).
Inputs/search: 32 px, surface-1 fill, hairline (≥3:1), leading icon, clear button, focus ring.
Segmented (existing `SectionTabs` sliding selector generalised): track surface-1, thumb surface-3 with highlight.
Switch/CheckBox, ComboBox/MenuButton, ContextMenu, Tooltip, ScrollBar (thin, overlay-like, persistent when
`prefers-persistent-scrollbars`), TableView header/rows (34 px rows ⇒ ADR note; selected row accent tint +
3 px left bar; hover 4 %), Dialog panes (keep the 9 `Dialog` windows; restyle DialogPane: header, body,
button bar; destructive anatomy), chips/status (dot + word for neutral; tinted chip only for warning/danger),
banners, result lines.

Motion (CSS `transition` on background/border/text fill and JavaFX Timeline for layout): hover 120 ms, pressed
80 ms, segmented/switch 160 ms, menus/popovers 140 ms fade+scale .98, inspector/drawer 200 ms, page 160 ms
fade + 4 px rise with `Interpolator.SPLINE(.2,.8,.2,1)`; exits 120 ms ease-in. `prefers-reduced-motion` and the
existing `Motion` helper → instant. Data never animates.

## 6. Pages (keep every function, safety behaviour and existing view model)

- **Overview:** status line (dot + one sentence from existing states) → 4 metric tiles (big value, unit,
  sub-line, 60 s sparkline on Canvas; 4 across ≥ 1100, 2×2 below, 1 column < 640) → attention/notices only when
  present → lower row (existing panels: platform facts, managed, OOM, activity) balanced in 2–3 columns.
- **Processes:** toolbar = title + count, filter field, existing segments/sort menu, column menu; table full
  bleed; right inspector with sections Now / Control / Limits & protection / End process (danger zone at the
  bottom, separated); existing `ProcessDetailsPane` and `ProcessActionBar` logic re-hosted, not rewritten.
  Narrow: overlay sheet. Keep FEWEST_ROWS guarantee (at least two rows visible at 720×480) — recompute.
- **Performance:** keep `SectionTabs`/resource list + big Canvas chart + core grid; refined axis labels, honest
  scales; resize-stable.
- **Limits:** list + editor panel flow (existing actions re-hosted per selected entry; no scattered button row);
  native/emulated/unavailable chips neutral with words.
- **OOM Guard:** state header (calm state word, available memory, "Automatic intervention is off" safe-default
  chip), monitoring and intervention groups, managed table, recent actions; terminate stays off by default and
  behind the existing consent dialog.
- **Logs:** readable list, search, severity filter, existing keyboard behaviour.
- **Settings:** grouped rows: Appearance (System/Light/Dark), folders, platform facts, capability summary.

## 7. Adaptive layout (real JavaFX, not CSS shrink)

Breakpoints by window content size (listeners on the scene width/height, debounced to one layout pass per
pulse; no fixed pixel positioning):
- W ≥ 1280: rail + content + docked resizable inspector.
- 900 ≤ W < 1280: rail + content + fixed 320 inspector (Processes), Limits editor 320.
- 820 ≤ W < 900: rail + content; inspector overlay.
- W < 820: rail with names; inspector overlay; toolbar condensed.
- H < 560: compact vertical rhythm (toolbar 44, status 20, paddings −4).
- Tall (H > W·1.2): Overview tiles 2×2 + stacked lower row; Processes inspector docked bottom (if measured
  better than overlay).
- Ultrawide (W ≥ 2200): content max readable widths for text blocks (settings 880, logs message column),
  tables/graphs use the full width; overview tiles stay 4 across and do not stretch absurdly (cap 520 each,
  centred group or extra column for the lower row).

## 8. Verification (each stage)

`./mvnw -o clean verify` PASS; `-Pui-snapshots` snapshots reviewed by eye; real-window captures at the size
matrix (`lab-docs/RESPONSIVE_MATRIX_TR.md`), keyboard checks, title-bar matrix; MON-12 idle cost vs baseline.

## 9. Lab isolation

User data folders renamed for the lab: Windows `%LOCALAPPDATA%\Prism\SystemResourceManager-UILab\{logs,state,
settings}` (instance lock, suspension journal and settings follow), macOS `…PrismSystemResourceManager-UILab`,
Linux equivalent. Window title "PRISM UI Lab — Process & Resource Interactive System Manager". App-image name
"PRISM-UI-Lab". First launch theme DARK (only when no settings file exists; saved choice wins). Real control
tests only on the disposable `prism-demo`.
