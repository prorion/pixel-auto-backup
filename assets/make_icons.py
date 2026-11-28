"""Render the AutoPixel icon (same geometry as assets/icon.svg) for Windows.

    python assets/make_icons.py

Writes desktop/app.ico (16-256 px, used for the .exe) and desktop/icon.png
(256 px, embedded for the window icon). Needs Pillow.
"""
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
SS = 8  # supersampling factor

TOP, BOTTOM = (0x1B, 0x2D, 0x57), (0x0E, 0x1A, 0x33)
WHITE, CHECK = (255, 255, 255), (0x12, 0xB8, 0x86)
PIXELS = [  # x, y, size, radius, colour  (108-unit grid)
    (30.0, 71.5, 5.0, 1.0, (0x3B, 0x82, 0xF6)),
    (36.5, 64.5, 6.5, 1.3, (0x22, 0xB8, 0xD6)),
]
CLOUD_CIRCLES = [(40, 51, 11), (55, 42, 16), (69, 52, 10)]  # cx, cy, r
CLOUD_BASE = (40, 46, 69, 62)  # x0, y0, x1, y1
CHECK_POINTS = [(46.5, 50.5), (53, 57), (64.5, 45)]
CHECK_WIDTH = 5.2


def render(size: int) -> Image.Image:
    n = size * SS
    k = n / 108
    img = Image.new("RGBA", (n, n), (0, 0, 0, 0))

    gradient = Image.new("RGBA", (1, n))
    for y in range(n):
        t = y / (n - 1)
        gradient.putpixel((0, y), tuple(round(a + (b - a) * t) for a, b in zip(TOP, BOTTOM)) + (255,))
    mask = Image.new("L", (n, n), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, n - 1, n - 1), radius=24 * k, fill=255)
    img.paste(gradient.resize((n, n)), (0, 0), mask)

    draw = ImageDraw.Draw(img)
    for x, y, s, r, colour in PIXELS:
        draw.rounded_rectangle((x * k, y * k, (x + s) * k, (y + s) * k), radius=r * k, fill=colour)
    for cx, cy, r in CLOUD_CIRCLES:
        draw.ellipse(((cx - r) * k, (cy - r) * k, (cx + r) * k, (cy + r) * k), fill=WHITE)
    x0, y0, x1, y1 = CLOUD_BASE
    draw.rectangle((x0 * k, y0 * k, x1 * k, y1 * k), fill=WHITE)
    points = [(x * k, y * k) for x, y in CHECK_POINTS]
    width = round(CHECK_WIDTH * k)
    draw.line(points, fill=CHECK, width=width, joint="curve")
    for x, y in (points[0], points[-1]):  # round caps
        draw.ellipse((x - width / 2, y - width / 2, x + width / 2, y + width / 2), fill=CHECK)
    return img.resize((size, size), Image.LANCZOS)


def main() -> None:
    big = render(256)
    big.save(ROOT / "desktop" / "icon.png")
    sizes = [16, 20, 24, 32, 40, 48, 64, 128, 256]
    frames = [render(s) for s in sizes]
    frames[-1].save(ROOT / "desktop" / "app.ico", sizes=[(s, s) for s in sizes], append_images=frames[:-1])


if __name__ == "__main__":
    main()
