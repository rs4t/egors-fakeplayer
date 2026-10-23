"""Draws icon.png (512x512): a blocky player head with a glowing bot visor. Pixel art, so it stays crisp when tiny."""
from PIL import Image, ImageDraw, ImageFilter

GRID = [
    "................",
    ".......CC.......",
    ".......AA.......",
    "..HHHHHHHHHHHH..",
    "..HhHHhHHHHhHH..",
    "..HSSHSSSSSHSH..",
    "..SSSSSSSSSSSS..",
    "..VVVVVVVVVVVV..",
    "..VCCcVVVVcCCV..",
    "..VVVVVVVVVVVV..",
    "..SSSSSSSSSSSS..",
    "..SSSsMMMMsSSS..",
    "..sSSSMMMMSSSs..",
    "..ssssssssssss..",
    "................",
    "................",
]
COLORS = {
    "H": "#4a2f1b", "h": "#6b4426",            # hair
    "S": "#c99a6e", "s": "#ad7d52",            # skin, shade
    "V": "#151a24",                            # visor
    "C": "#4fe3ff", "c": "#1f9fc4",            # glow
    "M": "#7a4a2c",                            # mouth
    "A": "#8f99a5",                            # antenna
}
SIZE, PX = 512, 26
OX = (SIZE - 16 * PX) // 2
OY = OX + PX  # nudge down: the bottom rows are empty

def draw(img, only=None, dx=0, dy=0, color=None):
    d = ImageDraw.Draw(img)
    for y, row in enumerate(GRID):
        for x, ch in enumerate(row):
            if ch == "." or (only and ch not in only):
                continue
            x0, y0 = OX + x * PX + dx, OY + y * PX + dy
            d.rectangle([x0, y0, x0 + PX - 1, y0 + PX - 1], fill=color or COLORS[ch])

bg = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
mask = Image.new("L", (SIZE, SIZE), 0)
ImageDraw.Draw(mask).rounded_rectangle([0, 0, SIZE - 1, SIZE - 1], radius=96, fill=255)
grad = Image.new("RGBA", (SIZE, SIZE))
gd = ImageDraw.Draw(grad)
for y in range(SIZE):  # dark slate, slightly lighter at the top
    t = y / SIZE
    gd.line([(0, y), (SIZE, y)], fill=(int(40 - 18 * t), int(48 - 20 * t), int(64 - 24 * t), 255))
bg.paste(grad, (0, 0), mask)

shadow = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
draw(shadow, dx=10, dy=14, color=(0, 0, 0, 140))
bg.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(10)))

glow = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
draw(glow, only="Cc")
bg.alpha_composite(glow.filter(ImageFilter.GaussianBlur(22)))
bg.alpha_composite(glow.filter(ImageFilter.GaussianBlur(8)))

draw(bg)
bg.save("icon.png")
for s in (128, 64, 32):  # previews at listing sizes
    bg.resize((s, s), Image.LANCZOS).save(f"icon-{s}.png")
