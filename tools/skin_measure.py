#!/usr/bin/env python3
"""Measures the holes of a skin frame and prints them as skin.json rects (fractions of 1280x720).

usage: skin_measure.py frame.png|webp [--min-area 1500]
Each fully transparent region that does not touch the border is a hole (flood fill, needs Pillow + numpy).
Holes are sorted top to bottom, left to right: assign them to slots (hero, rows, actions...) by hand.
"""
import collections, json, sys
from PIL import Image
import numpy as np

src = sys.argv[1]
min_area = int(sys.argv[sys.argv.index('--min-area') + 1]) if '--min-area' in sys.argv else 1500
im = Image.open(src).convert('RGBA').resize((1280, 720), Image.LANCZOS)
a = np.array(im)[:, :, 3] < 20
H, W = a.shape
seen = np.zeros_like(a)
holes = []
for y in range(0, H, 4):
    for x in range(0, W, 4):
        if a[y, x] and not seen[y, x]:
            q = collections.deque([(y, x)]); seen[y, x] = 1
            x0 = x1 = x; y0 = y1 = y; n = 0
            while q:
                cy, cx = q.popleft(); n += 1
                x0 = min(x0, cx); x1 = max(x1, cx); y0 = min(y0, cy); y1 = max(y1, cy)
                for ny, nx in ((cy + 1, cx), (cy - 1, cx), (cy, cx + 1), (cy, cx - 1)):
                    if 0 <= ny < H and 0 <= nx < W and a[ny, nx] and not seen[ny, nx]:
                        seen[ny, nx] = 1; q.append((ny, nx))
            touches = x0 == 0 or y0 == 0 or x1 == W - 1 or y1 == H - 1
            if n > min_area and not touches:
                holes.append((x0, y0, x1 - x0 + 1, y1 - y0 + 1))
for x, y, w, h in sorted(holes, key=lambda r: (r[1] // 40, r[0])):
    print(json.dumps([round(x / 1280, 3), round(y / 720, 3), round(w / 1280, 3), round(h / 720, 3)]))
print(f'{len(holes)} holes', file=sys.stderr)
