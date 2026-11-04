# Sfondo della Home di Kairos XC (960x540, JPEG): onde di luce arancio/magenta/viola e griglia in prospettiva.
# Rigenera con:  python3 docs/src/sfondo.py app/src/main/res/drawable-nodpi/home_bg.jpg
import math, random, sys
from PIL import Image, ImageDraw, ImageFilter, ImageChops
W, H = 1920, 1080  # disegnato in grande e poi ridotto: linee più morbide
random.seed(7)
bg = Image.new("RGB", (W, H), (6, 6, 11))
px = bg.load()
# sfumatura di fondo: un po' di viola in alto a sinistra, blu notte in basso a destra
for y in range(H):
    for x in range(0, W, 2):
        d1 = math.hypot(x - W * 0.18, y - H * 0.12) / W
        d2 = math.hypot(x - W * 0.9, y - H * 0.95) / W
        r = 6 + int(40 * max(0, 0.75 - d1) ** 2) + int(4 * max(0, 0.6 - d2))
        g = 6 + int(6 * max(0, 0.75 - d1) ** 2) + int(10 * max(0, 0.6 - d2))
        b = 11 + int(46 * max(0, 0.75 - d1) ** 2) + int(34 * max(0, 0.6 - d2))
        px[x, y] = (r, g, b)
        if x + 1 < W: px[x + 1, y] = (r, g, b)

def lerp(a, b, t): return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))
STOPS = [(255, 194, 51), (255, 106, 31), (255, 45, 111), (139, 92, 246)]
def grad(t):
    t = max(0, min(0.999, t)) * (len(STOPS) - 1)
    i = int(t)
    return lerp(STOPS[i], STOPS[i + 1], t - i)

# griglia in prospettiva in basso (stile «futuristico»)
grid = Image.new("RGB", (W, H), (0, 0, 0))
gd = ImageDraw.Draw(grid)
hor = int(H * 0.66)
for i in range(-14, 15):
    x0 = W / 2 + i * 70
    x1 = W / 2 + i * 420
    gd.line([(x0, hor), (x1, H)], fill=(70, 40, 110), width=2)
y = hor; step = 6
while y < H:
    k = (y - hor) / (H - hor)
    gd.line([(0, y), (W, y)], fill=(int(60 + 40 * k), int(30 + 20 * k), int(100 + 40 * k)), width=2)
    step *= 1.28; y += int(step)
mask = Image.new("L", (W, H), 0)
md = ImageDraw.Draw(mask)
for yy in range(hor, H):
    md.line([(0, yy), (W, yy)], fill=int(150 * ((yy - hor) / (H - hor)) ** 0.8))
bg = Image.composite(ImageChops.add(bg, grid), bg, mask)

# onde di luce
waves = Image.new("RGB", (W, H), (0, 0, 0))
wd = ImageDraw.Draw(waves)
for n in range(22):
    amp = 120 + n * 9
    ph = n * 0.17
    yb = H * 0.62 - n * 7
    pts = []
    for x in range(-40, W + 40, 8):
        t = x / W
        yv = yb + amp * math.sin(t * 5.2 + ph) * (0.55 + 0.45 * math.sin(t * 2.1 + n * 0.3)) - 260 * t
        pts.append((x, yv))
    for i in range(len(pts) - 1):
        c = grad(pts[i][0] / W)
        f = 0.35 + 0.65 * (1 - n / 22)
        wd.line([pts[i], pts[i + 1]], fill=tuple(int(v * f) for v in c), width=2)
waves = waves.point(lambda v: min(255, int(v * 1.6)))
glow = waves.filter(ImageFilter.GaussianBlur(18))
bg = ImageChops.add(bg, glow)
bg = ImageChops.add(bg, waves.filter(ImageFilter.GaussianBlur(1.2)))
# particelle
pd = ImageDraw.Draw(bg)
for _ in range(140):
    x, y = random.randrange(W), random.randrange(int(H * 0.7))
    r = random.choice([1, 1, 2, 2, 3])
    c = grad(random.random())
    pd.ellipse([x - r, y - r, x + r, y + r], fill=tuple(int(v * random.uniform(0.3, 0.8)) for v in c))
# vignetta: centro un po' più scuro dietro i cerchi, bordi scuri
vig = Image.new("L", (W, H), 0)
vd = ImageDraw.Draw(vig)
for i in range(60):
    a = int(255 * (i / 60) ** 2)
    vd.ellipse([-W * 0.25 + i * 12, -H * 0.3 + i * 7, W * 1.25 - i * 12, H * 1.3 - i * 7], outline=a, width=14)
vig = vig.filter(ImageFilter.GaussianBlur(40))
dark = Image.new("RGB", (W, H), (4, 4, 8))
bg = Image.composite(bg, dark, vig.point(lambda v: 90 + int(v * 0.65)))
bg = bg.resize((960, 540), Image.LANCZOS)
bg.save(sys.argv[1], "JPEG", quality=84, optimize=True, progressive=False)
