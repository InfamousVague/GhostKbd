#!/usr/bin/env python3
"""
Ghost KBD's app icon from one square picture (art/app-icon.png).

Matt, 2026-11-13: "update and release the new app icons" (the white keycap with the ghost's eyes, on black,
~/Desktop/GhostKBD.png).

    python3 tool/app_icon.py ~/Desktop/GhostKBD.png     copies it to art/app-icon.png, then writes every icon
    python3 tool/app_icon.py                            writes every icon from art/app-icon.png

Android's adaptive icon (minSdk 26, so no legacy PNGs): the foreground is the picture shrunk so the keycap's corners sit
inside the mask's 66dp safe circle, its black carried on past the edges to the full 108dp, over a black background;
the monochrome layer is the keycap alone (its brightness as alpha, so the shading stays), for themed icons. iOS: the
full square, without alpha.
"""

import os
import shutil
import sys

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ART = os.path.join(ROOT, 'art', 'app-icon.png')
RES = os.path.join(ROOT, 'android', 'app', 'src', 'main', 'res')
IOS = os.path.join(ROOT, 'ios', 'Runner', 'Assets.xcassets', 'AppIcon.appiconset')
# The keycap's farthest corner is ~46dp from the centre at full size; 0.7 brings it to ~32dp, inside 33.
SCALE = 0.7
BACKGROUND = (0, 0, 0)
DENSITIES = {'mdpi': 108, 'hdpi': 162, 'xhdpi': 216, 'xxhdpi': 324, 'xxxhdpi': 432}
LANCZOS = Image.Resampling.LANCZOS


def shrunk(picture, size, mode):
    inner = round(size * SCALE)
    small = picture.resize((inner, inner), LANCZOS)
    offset = ((size - inner) // 2, (size - inner) // 2)
    if mode == 'foreground':
        out = Image.new('RGB', (size, size), BACKGROUND)
        out.paste(small, offset)
        return out
    alpha = Image.new('L', (size, size), 0)
    alpha.paste(small.convert('L'), offset)
    out = Image.new('RGBA', (size, size), (255, 255, 255, 0))
    out.putalpha(alpha)
    return out


def main():
    if len(sys.argv) > 1:
        shutil.copyfile(os.path.expanduser(sys.argv[1]), ART)
    picture = Image.open(ART).convert('RGB')
    for density, size in DENSITIES.items():
        folder = os.path.join(RES, 'mipmap-' + density)
        shrunk(picture, size, 'foreground').save(os.path.join(folder, 'ic_launcher_foreground.png'), optimize=True)
        shrunk(picture, size, 'monochrome').save(os.path.join(folder, 'ic_launcher_monochrome.png'), optimize=True)
    for name in sorted(os.listdir(IOS)):
        if name.endswith('.png'):
            path = os.path.join(IOS, name)
            with Image.open(path) as old:
                size = old.width
            picture.resize((size, size), LANCZOS).save(path, optimize=True)
    print('icons written')


if __name__ == '__main__':
    main()
