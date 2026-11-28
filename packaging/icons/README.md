# PRISM's icon

A prism: one white equilateral triangle with rounded corners, centred on PRISM's indigo tile. It
is the application icon on every system. There is no word in it, because it must read at 16 px in
a taskbar.

| File | Use |
|---|---|
| `prism.svg` | Master: the indigo tile with the white prism, 256 units |
| `prism-small.svg` | The same prism, a little larger and rounder, drawn for 16 to 32 px |
| `prism-mark.svg` | The prism without the tile, in indigo, for documents |
| `prism-macos.svg` | The tile on Apple's 1024 grid (an 824 body, 100 on every side) |
| `prism.ico` | Windows (`jpackage --icon`): 16, 20, 24, 32, 40, 48, 64 and 256 px |
| `prism.png` | Linux (`jpackage --icon`): 512 px |
| `prism.icns` | macOS: 16 to 1024 px, from `prism-macos.svg` |
| `prism-logotype.svg` | The lockup for light backgrounds: the icon, PRISM, and the descriptor below it |
| `prism-logotype-dark.svg` | The same lockup for dark backgrounds (white and light grey text) |

The window icons are in `src/main/resources/prism/srm/app/icon/` (16 to 256 px).

**Construction.**
- The prism is an exact equilateral triangle: side 138 of 256, and 150 in the small drawing.
- Its three corners are rounded with one radius (16, and 20 in the small drawing), with the
  tangents at radius × √3 from each corner.
- Its visible box, from the top of the rounded apex to the base, is centred on the tile and
  raised by 3 units, because a triangle's mass sits low.
- The tile's corner radius is 58/256, close to the macOS app icon's proportion.

**Colours.**
- The tile is `#4f5bd5`, the application's own accent (`-srm-accent` in `app.css`).
- The prism is white.
- White on the accent has a contrast of 5.54:1, the same pair as the application's primary buttons.

**Logotype.**
- The lockup places the icon beside the word PRISM, and below the word the descriptor PROCESS &
  RESOURCE INTERACTIVE SYSTEM MANAGER.
- Both lines are in IBM Plex Sans, the application's typeface (SIL Open Font License): PRISM in
  SemiBold, tracked +5 %, the descriptor in Medium capitals, tracked +9 %.
- The letters are outlines shaped with the font's own kerning, so the files need no font.
- PRISM's cap height is 40 % of the icon, and the descriptor's 8.2 %. The two lines are centred
  on the icon as one block, 20 % of the icon away from it.
- Text colours: `#15181e` and `#5c6474` on light backgrounds; `#ffffff` and `#aab1c2` on dark
  ones.
- The lockup is for headers, documents and slides. Where space is small (a taskbar, a favicon),
  use the icon alone.

**Regenerating.** The PNG, ICO and ICNS files are rendered from the SVG masters at exact sizes.
The ICO and ICNS files hold PNG entries. When the design changes, render every size again; never
scale one PNG up or down.
