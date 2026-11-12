"""Contrast of the v2 token pairs, read from app.css (WCAG 2.2 relative luminance, sRGB).

Run from the repository root:  python -I lab-docs/contrast/tokens.py
Prints the two tables of lab-docs/CONTRAST.md and exits with 1 if a pair with a need fails.
"""
import re
import sys

CSS = 'src/main/resources/prism/srm/ui/app.css'


def lin(c):
    c /= 255
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def lum(h):
    h = h.lstrip('#')
    r, g, b = (int(h[i:i + 2], 16) for i in (0, 2, 4))
    return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)


def ratio(a, b):
    la, lb = lum(a), lum(b)
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)


def tokens():
    css = open(CSS, encoding='utf-8').read()
    dark_at = css.index('@media (prefers-color-scheme: dark)')
    pattern = re.compile(r'(-srm-[a-z0-9-]+):\s*(#[0-9a-fA-F]{6});')
    light = dict(pattern.findall(css[:dark_at]))
    dark = dict(light)
    dark.update(pattern.findall(css[dark_at:css.index('}', css.index('.root', dark_at))]))
    return {'Light': light, 'Dark': dark}


# (what, foreground token, background token, need); need 0 = recorded, not held to a threshold
PAIRS = [
    ('Body text on canvas', 'text', 'bg', 4.5),
    ('Body text on panel', 'text', 'surface', 4.5),
    ('Body text on raised (inspector, dialogs, menus)', 'text', 'surface-raised', 4.5),
    ('Body text on chrome (rail, toolbar, status line)', 'text', 'chrome', 4.5),
    ('Secondary text on panel', 'text-muted', 'surface', 4.5),
    ('Secondary text on raised', 'text-muted', 'surface-raised', 4.5),
    ('Secondary text on canvas', 'text-muted', 'bg', 4.5),
    ('Secondary text on chrome (toolbar counts)', 'text-muted', 'chrome', 4.5),
    ('Captions on panel', 'text-subtle', 'surface', 4.5),
    ('Captions on chrome (lab mark)', 'text-subtle', 'chrome', 4.5),
    ('Text on selection', 'text', 'selection', 4.5),
    ('Text on control (button label)', 'text', 'control', 4.5),
    ('Selected navigation icon', 'accent', 'nav-selected', 3.0),
    ('Selected navigation name', 'text', 'nav-selected', 4.5),
    ('Navigation selection bar on chrome (tells the selection, SH-05)', 'accent', 'chrome', 3.0),
    ('Navigation selection bar on the drawer', 'accent', 'surface-raised', 3.0),
    ('Navigation shortcut under the pointer', 'text-subtle', 'nav-hover', 4.5),
    ('Navigation hover on chrome', 'nav-hover', 'chrome', 0),
    ('Navigation selection surface on chrome', 'nav-selected', 'chrome', 0),
    ('Primary button label', 'accent-text', 'accent-fill', 4.5),
    ('Primary button, hover', 'accent-text', 'accent-fill-hover', 4.5),
    ('Destructive confirm label', 'danger-text', 'danger-fill', 4.5),
    ('Destructive confirm label, hover', 'danger-text', 'danger-fill-hover', 4.5),
    ('OK text', 'ok', 'surface', 4.5),
    ('Warning text', 'warning', 'surface', 4.5),
    ('Error text', 'error', 'surface', 4.5),
    ('Error text on selection (selected row)', 'error', 'selection', 4.5),
    ('Error text on unfocused selection', 'error', 'selection-inactive', 4.5),
    ('Error text on control (Force kill)', 'error', 'control', 4.5),
    ('Error text on control, hover (Force kill)', 'error', 'control-hover', 4.5),
    ('Warning text on selection', 'warning', 'selection', 4.5),
    ('Info text', 'info', 'surface', 4.5),
    ('Input border (text field, check box, radio)', 'border-strong', 'bg', 3.0),
    ('Input border on raised', 'border-strong', 'surface-raised', 3.0),
    ('3:1 control edge on canvas', 'control-edge', 'bg', 3.0),
    ('3:1 control edge on raised (checked box, dialogs)', 'control-edge', 'surface-raised', 3.0),
    ('3:1 control edge on chrome', 'control-edge', 'chrome', 3.0),
    ('Segment thumb edge on its sunken track', 'control-edge', 'surface-sunken', 3.0),
    ('Checked box fill on raised (edge carries it)', 'accent-fill', 'surface-raised', 0),
    ('Surface edge (panel, table frame, sheet) on canvas', 'edge', 'bg', 0),
    ('Surface edge on the panel surface inside it', 'edge', 'surface', 0),
    ('Quiet button under the pointer on raised (sheet close)', 'control-hover', 'surface-raised', 0),
    ('Quiet button under the pointer on the chrome band (page header)', 'nav-hover', 'chrome', 0),
    ('Button border (soft, see notes) on canvas', 'control-border', 'bg', 0),
    ('Button border (soft) on chrome', 'control-border', 'chrome', 0),
    ('Scroll thumb at rest on panel', 'border-strong', 'surface', 3.0),
    ('Scroll thumb at rest on canvas', 'border-strong', 'bg', 3.0),
    ('Focus ring on canvas', 'focus-ring', 'bg', 3.0),
    ('Focus ring on selection (focused row)', 'focus-ring', 'selection', 3.0),
    ('Graph line', 'accent', 'surface', 3.0),
    ('Data: CPU', 'data-cpu', 'surface', 3.0),
    ('Data: CPU, processor grid on canvas', 'data-cpu', 'bg', 3.0),
    ('Data: memory', 'data-memory', 'surface', 3.0),
    ('Data: disk read', 'data-disk-read', 'surface', 3.0),
    ('Data: disk read on canvas', 'data-disk-read', 'bg', 3.0),
    ('Data: disk read on chrome', 'data-disk-read', 'chrome', 3.0),
    ('Data: disk write (dashed)', 'data-disk-write', 'surface', 3.0),
    ('Data: disk write on canvas', 'data-disk-write', 'bg', 3.0),
    ('Data: disk write on chrome', 'data-disk-write', 'chrome', 3.0),
    ('Data: network download', 'data-net-down', 'surface', 3.0),
    ('Data: network upload (dashed)', 'data-net-up', 'surface', 3.0),
    ('Disabled label on a disabled button (control)', 'text-disabled', 'control', 0),
    ('Disabled label on raised', 'text-disabled', 'surface-raised', 0),
    ('Disabled label on chrome', 'text-disabled', 'chrome', 0),
    ('Disabled label on canvas', 'text-disabled', 'bg', 0),
]


def main():
    sys.stdout.reconfigure(encoding='utf-8')
    failed = False
    for mode, t in tokens().items():
        print(f'## {mode}\n')
        print('| Pair | Foreground | Background | Ratio | Need | |')
        print('|---|---|---|---|---|---|')
        for what, fg, bg, need in PAIRS:
            c = ratio(t['-srm-' + fg], t['-srm-' + bg])
            verdict = 'exempt' if need == 0 else ('pass' if c >= need else 'FAIL')
            failed |= verdict == 'FAIL'
            shown = f'{need:.1f}' if need else '–'
            print(f'| {what} | `-srm-{fg}` {t["-srm-" + fg]} | `-srm-{bg}` {t["-srm-" + bg]} '
                  f'| {c:.2f} | {shown} | {verdict} |')
        print()
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main())
