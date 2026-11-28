# PRISM's icon

The cut prism: a prism with one clean cut, the system split so that each part can be seen. It is
the application icon on every system. There is no word in it, because it must read at 16 px in a
taskbar.

| File | Use |
|---|---|
| `prism.svg` | Master: the indigo tile with the white prism, 256 units |
| `prism-small.svg` | The same, drawn for 16 to 32 px: a wider cut and rounder corners, so the cut survives |
| `prism-mark.svg` | The prism without the tile, for documents |
| `prism-macos.svg` | The tile on Apple's 1024 grid (an 824 body, 100 on every side) |
| `prism.ico` | Windows (`jpackage --icon`): 16, 20, 24, 32, 40, 48, 64 and 256 px |
| `prism.png` | Linux (`jpackage --icon`): 512 px |
| `prism.icns` | macOS: 16 to 1024 px, from `prism-macos.svg` |

The window icons are in `src/main/resources/prism/srm/app/icon/` (16 to 256 px).

**Construction.**
- The prism is an exact equilateral triangle. Its three corners are rounded with one radius,
  with the tangents at radius × √3 from each corner.
- The cut is a straight gap parallel to the right side, at one width all along it.
- The mark sits a little above the tile's centre, so that it reads centred.
- The tile's corner radius is 58/256, close to the macOS app icon's proportion.

**Colours.**
- The tile is `#4f5bd5`, the application's own accent (`-srm-accent` in `app.css`).
- The prism is white, and its cut-off facet is `#c9cef7`.
- White on the accent has a contrast of 5.54:1, the same pair as the application's primary buttons.

**Regenerating.** The PNG, ICO and ICNS files are rendered from the SVG masters at exact sizes.
The ICO and ICNS files hold PNG entries. When the design changes, render every size again; never
scale one PNG up or down.
