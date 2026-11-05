# Grafica di Kairos XC, generata (nessuna immagine presa da internet), solo con i colori del logo:
# fondo prugna scuro, sfumatura giallo→arancio→rosa, bianco.
#   python3 docs/src/grafica.py <cartella res>
# Crea: drawable-nodpi/home_bg.jpg (Home), drawable-nodpi/app_bg.jpg (altre schermate),
#       drawable-nodpi/circle_n.png e circle_f.png (cerchi della Home, normale e selezionato)
import math, random, sys, os
import numpy as np
from PIL import Image, ImageFilter
RES = sys.argv[1]
OUT = os.path.join(RES, "drawable-nodpi"); os.makedirs(OUT, exist_ok=True)
Y, O, P = np.array([255, 194, 51.]), np.array([255, 106, 31.]), np.array([255, 45, 111.])
BG0, BG1 = np.array([42, 22, 48.]), np.array([8, 5, 10.])   # stessi colori del fondo dell'icona

def logo_grad(t):
    t = np.clip(t, 0, 1)[..., None]
    a = np.where(t < .5, Y + (O - Y) * (t / .5), O + (P - O) * ((t - .5) / .5))
    return a

def blur(a, r):
    im = Image.fromarray(np.clip(a, 0, 255).astype(np.uint8))
    return np.asarray(im.filter(ImageFilter.GaussianBlur(r))).astype(float)

def background(W, H, calm):
    yy, xx = np.mgrid[0:H, 0:W].astype(float)
    # fondo: prugna in alto a sinistra → quasi nero
    d = np.hypot((xx - W * .22) / W, (yy - H * .18) / H * .7)
    t = np.clip(d / 1.0, 0, 1) ** 0.9
    img = BG0 * (1 - t[..., None]) + BG1 * t[..., None]
    light = np.zeros((H, W, 3))
    # aloni morbidi arancio (in basso a sinistra) e rosa (in alto a destra)
    for cx, cy, r, col, k in [(.05, 1.1, .45, O, .26), (.97, .0, .42, P, .24), (.62, 1.0, .4, P, .12)]:
        g = np.exp(-(((xx - W * cx) / (W * r)) ** 2 + ((yy - H * cy) / (W * r)) ** 2) * 2.2)
        light += g[..., None] * col * k * (0.55 if calm else 1)
    # nastro di luce: tante linee sottili che formano una «seta» sfumata coi colori del logo
    rib = np.zeros((H, W, 3))
    xs = np.arange(W, dtype=float)
    N = 70
    for n in range(N):
        u = n / (N - 1)
        ph = u * 2.2
        base = H * (.74 - .22 * u)
        amp = H * (.07 + .09 * math.sin(u * 2.4))
        ycur = base + amp * np.sin(xs / W * 2 * math.pi * .85 + ph) - (xs / W) * H * .28 * (1 - .4 * u)
        # luminosità: più forte al centro del nastro, sfuma ai bordi
        wgt = (math.sin(u * math.pi) ** 1.6) * (0.35 if calm else 1.0)
        col = logo_grad(xs / W)
        yi = np.clip(ycur.round().astype(int), 0, H - 1)
        for dy, f in ((-1, .35), (0, 1), (1, .35)):
            yk = np.clip(yi + dy, 0, H - 1)
            rib[yk, np.arange(W)] += col * wgt * f * .42
    rib_glow = blur(np.clip(rib, 0, 255), W / 90)
    rib_soft = blur(np.clip(rib, 0, 255), 1.0)
    img = img + light + rib_glow * .9 + rib_soft * (1.0 if not calm else .5)
    # «bokeh»: cerchi sfocati
    random.seed(11)
    bok = np.zeros((H, W, 3))
    for _ in range(26 if not calm else 12):
        cx, cy = random.uniform(0, W), random.uniform(0, H * .75)
        r = random.uniform(W * .006, W * .03)
        col = logo_grad(np.array([random.random()]))[0]
        m = np.clip(1 - np.hypot(xx - cx, yy - cy) / r, 0, 1) ** .6
        bok += m[..., None] * col * random.uniform(.08, .22)
    img += blur(bok, W / 400)
    # vignetta
    v = np.clip(1 - (np.hypot((xx - W / 2) / W, (yy - H / 2) / H) * 1.05) ** 2.2, .35, 1)
    img *= v[..., None]
    if calm:
        img = blur(img, W / 320) * .78   # schermate interne: più scuro e calmo, si leggono bene i testi
    # un pizzico di grana per evitare le «bande» sulle TV
    img += np.random.default_rng(3).normal(0, 1.6, img.shape)
    return Image.fromarray(np.clip(img, 0, 255).astype(np.uint8))

def circle(S, focused):
    # immagine quadrata S×S: cerchio con alone (l'alone occupa il bordo esterno)
    yy, xx = np.mgrid[0:S, 0:S].astype(float)
    c = S / 2
    R = S * .41
    d = np.hypot(xx - c, yy - c)
    ang = (np.arctan2(yy - c, xx - c) + math.pi * .75) % (2 * math.pi) / (2 * math.pi)
    diag = np.clip((xx + yy) / (2 * S), 0, 1)   # sfumatura in diagonale come nel logo
    rgba = np.zeros((S, S, 4))
    ringw = S * (.022 if not focused else .03)
    ring = np.clip(1 - np.abs(d - R) / ringw, 0, 1) ** .8
    if focused:
        fill = (d < R).astype(float)
        fill = blur(np.repeat(fill[..., None], 3, 2) * 255, .8)[..., 0] / 255
        col = logo_grad(diag * 1.0)
        # luce morbida in alto (effetto vetro)
        hl = np.clip(1 - np.hypot((xx - c) / (R * 1.1), (yy - (c - R * .55)) / (R * .55)), 0, 1) ** 1.5 * .35
        rgb = col * (1 - hl[..., None]) + 255 * hl[..., None]
        glow = blur(np.repeat((d < R * 1.02)[..., None], 3, 2) * 255., S * .05)[..., 0] / 255
        a = np.maximum(fill * .97, glow * .75)
        gcol = logo_grad(diag)
        rgb = rgb * fill[..., None] + gcol * (1 - fill[..., None])
        # anello bianco sottile
        wr = np.clip(1 - np.abs(d - R) / (S * .012), 0, 1)
        rgb = rgb * (1 - wr[..., None]) + 255 * wr[..., None]
        a = np.maximum(a, wr)
    else:
        fill = (d < R).astype(float)
        fill = blur(np.repeat(fill[..., None], 3, 2) * 255, .8)[..., 0] / 255
        inner = np.array([24, 12, 26.]) * (1 - .35 * (d / R))[..., None]
        col = logo_grad(diag)
        glow = blur(np.repeat(ring[..., None], 3, 2) * 255., S * .025)[..., 0] / 255
        rgb = inner * fill[..., None]
        rgb = rgb * (1 - ring[..., None]) + col * ring[..., None]
        gl = glow * (1 - fill) * .9
        rgb = rgb + col * gl[..., None] * (1 - ring[..., None])
        a = np.clip(fill * .82 + ring + gl * .8, 0, 1)
    rgba[..., :3] = np.clip(rgb, 0, 255)
    rgba[..., 3] = np.clip(a, 0, 1) * 255
    return Image.fromarray(rgba.astype(np.uint8), "RGBA")

background(1280, 720, False).save(os.path.join(OUT, "home_bg.jpg"), "JPEG", quality=86, optimize=True)
background(960, 540, True).save(os.path.join(OUT, "app_bg.jpg"), "JPEG", quality=82, optimize=True)
circle(320, False).save(os.path.join(OUT, "circle_n.png"), optimize=True)
circle(320, True).save(os.path.join(OUT, "circle_f.png"), optimize=True)
