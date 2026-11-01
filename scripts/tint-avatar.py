#!/usr/bin/env python3
"""Recolor the default agent (the little dragon in res/drawable-nodpi/nm_avatar_*.webp) from
nanoMuse's butter yellow to Nano Agent violet. Run ONCE on freshly imported upstream images:
it shifts hue, so running it twice shifts twice. Needs Pillow + numpy.

    python3 scripts/tint-avatar.py
"""
from pathlib import Path

import numpy as np
from PIL import Image

RES = Path(__file__).resolve().parents[1] / "android/src/android/app/src/main/res/drawable-nodpi"
SHIFT = 152  # in PIL's 0..255 hue: yellow (~38) -> violet (~190)

for f in sorted(RES.glob("nm_avatar_*.webp")):
    im = Image.open(f).convert("RGBA")
    hsv = np.array(im.convert("RGB").convert("HSV")).astype(np.float32)
    h, s, v = hsv[..., 0], hsv[..., 1], hsv[..., 2]
    m = (h > 15) & (h < 55) & (s > 25)
    h[m] = (h[m] + SHIFT) % 256
    s[m] = np.minimum(255, s[m] * 1.1)
    out = Image.fromarray(np.stack([h, s, v], -1).astype("uint8"), "HSV").convert("RGB")
    out.putalpha(im.getchannel("A"))
    out.save(f, "WEBP", quality=90, method=6)
    print("tinted", f.name)
