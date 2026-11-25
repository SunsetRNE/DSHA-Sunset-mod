#!/usr/bin/env python3
"""Regenerate the web icons from the same DSHA prompt geometry as Android."""
import runpy
from pathlib import Path

from PIL import Image

TOOLS = Path(__file__).resolve().parent
ASSETS = TOOLS.parent / 'app/src/main/assets/web-brand'


def generate():
    native = runpy.run_path(str(TOOLS / 'prepare-app-icon.py'))
    tile = native['render_tile']()
    background = native['BACKGROUND']
    path = ' '.join('M' + ' L'.join(f'{x} {y}' for x, y in line) for line in native['STROKES'])
    svg = (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><rect width="24" '
           f'height="24" rx="5" fill="{background}"/><path d="{path}" fill="none" '
           f'stroke="white" stroke-width="{native["STROKE_WIDTH"]}" stroke-linecap="round" '
           'stroke-linejoin="round"/></svg>\n')
    ASSETS.mkdir(exist_ok=True)
    for name in ('favicon.svg', 'favicon-dark.svg'):
        (ASSETS / name).write_text(svg, encoding='utf-8', newline='\n')
    for name, size in [('apple-touch-icon.png', 180), ('icon-192.png', 192),
                       ('icon-512.png', 512), ('icon-maskable-512.png', 512)]:
        masked = 'maskable' in name
        width = round(size * 0.8) if masked else size
        symbol = tile.resize((width, width), Image.Resampling.LANCZOS)
        if masked or name.startswith('apple'):
            image = Image.new('RGB', (size, size), background)
            margin = (size - width) // 2
            image.paste(symbol, (margin, margin), symbol)
        else:
            image = symbol
        image.save(ASSETS / name, optimize=True)


if __name__ == '__main__':
    generate()
