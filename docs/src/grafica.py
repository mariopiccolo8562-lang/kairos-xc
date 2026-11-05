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
        img = blur(img, W / 320) * .86   # schermate interne: più scuro e calmo, si leggono bene i testi
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


def circle3d(S, focused):
    """Cerchio in rilievo: ombra sotto, bordo smussato illuminato dall'alto, cupola lucida, riflesso in alto."""
    yy, xx = np.mgrid[0:S, 0:S].astype(float)
    c = S / 2
    R = S * .40
    cy = c - S * .012
    dx, dy = (xx - c) / R, (yy - cy) / R
    r = np.hypot(dx, dy)
    rgba = np.zeros((S, S, 4))
    # ombra morbida sotto (dà lo stacco dal fondo)
    sh = (np.hypot((xx - c) / (R * 1.0), (yy - (cy + S * .05)) / (R * .98)) < 1).astype(float) * 255
    sh = blur(np.repeat(sh[..., None], 3, 2), S * .035)[..., 0] / 255 * .75
    # alone coi colori del logo (più forte se selezionato)
    gl = (r < 1.02).astype(float) * 255
    gl = blur(np.repeat(gl[..., None], 3, 2), S * (.06 if focused else .03))[..., 0] / 255
    diag = np.clip((xx + yy) / (2 * S), 0, 1)
    gcol = logo_grad(diag)
    glow_a = gl * (0.95 if focused else 0.35) * (r > .98)
    # luce: dall'alto a sinistra
    nz = np.sqrt(np.clip(1 - r ** 2, 0, 1))
    light = np.clip(.55 + .55 * (-dx * .35 - dy * .6 + nz * .7), .25, 1.35)
    rim_w = .075
    rim = (r <= 1) & (r > 1 - rim_w)
    face = r <= 1 - rim_w
    aa = np.clip((1 - r) * R, 0, 1)  # bordo senza scalini
    # bordo smussato: anello sfumato del logo, più chiaro in alto
    bevel = np.clip(.45 + .9 * (-dy * .8 - dx * .3), .25, 1.5)
    if focused:
        rim_col = (gcol * .55 + 255 * .45) * bevel[..., None]
        base = logo_grad(np.clip(diag * 1.5 - .2, 0, 1))
        face_col = base * light[..., None]
    else:
        rim_col = gcol * bevel[..., None]
        base = np.array([46, 20, 42.])
        face_col = base * (light ** 1.2)[..., None]
    # riflesso lucido in alto (vetro)
    spec = np.clip(1 - np.hypot(dx / .62, (dy + .48) / .32), 0, 1) ** 1.6 * (.55 if focused else .30)
    # ombra interna sotto il bordo
    inner = np.clip(1 - (1 - rim_w - r) / .12, 0, 1) * face * .35
    face_col = face_col * (1 - inner[..., None]) + 255 * spec[..., None]
    rgb = np.zeros((S, S, 3))
    rgb[face] = face_col[face]
    rgb[rim] = rim_col[rim]
    a_shape = np.where(r <= 1, aa, 0)
    # composizione dal basso: ombra, alone, cerchio
    def over(top_rgb, top_a, bot_rgb, bot_a):
        oa = top_a + bot_a * (1 - top_a)
        orgb = (top_rgb * top_a[..., None] + bot_rgb * (bot_a * (1 - top_a))[..., None]) / np.maximum(oa, 1e-6)[..., None]
        return orgb, oa
    sh_a = sh * (.35 if focused else 1.0)
    c_rgb, c_a = over(gcol, np.clip(glow_a, 0, 1), np.zeros((S, S, 3)), sh_a)
    c_rgb, c_a = over(rgb, a_shape, c_rgb, c_a)
    rgba[..., :3] = np.clip(c_rgb, 0, 255)
    rgba[..., 3] = np.clip(c_a, 0, 1) * 255
    return Image.fromarray(rgba.astype(np.uint8), "RGBA")

def stage(img):
    """Home: raggi di luce dal centro, faretto dietro i cerchi e linea d'orizzonte luminosa (più impatto)."""
    a = np.asarray(img).astype(float)
    H, W = a.shape[:2]
    yy, xx = np.mgrid[0:H, 0:W].astype(float)
    cx, cy = W * .5, H * .47
    ang = np.arctan2(yy - cy, xx - cx)
    d = np.hypot((xx - cx) / W, (yy - cy) / H)
    rays = (np.cos(ang * 14) * .5 + .5) ** 6 * np.clip(1 - d * 1.5, 0, 1) ** 1.5
    rays = blur(np.repeat(rays[..., None] * 255, 3, 2), W / 260)[..., 0] / 255
    spot = np.exp(-(((xx - cx) / (W * .34)) ** 2 + ((yy - cy) / (H * .30)) ** 2) * 1.6)
    hor_y = H * .80
    hor = np.exp(-((yy - hor_y) / (H * .012)) ** 2) * np.clip(1 - np.abs(xx - cx) / (W * .55), 0, 1) ** 1.2
    hor_glow = np.exp(-((yy - hor_y) / (H * .07)) ** 2) * np.clip(1 - np.abs(xx - cx) / (W * .6), 0, 1)
    col_c = logo_grad(np.clip(xx / W, 0, 1))
    a = a + (rays * 70)[..., None] * (O / 255) + (spot * 38)[..., None] * (P / 255 * .6 + O / 255 * .4)
    a = a + col_c * (hor * .95)[..., None] + col_c * (hor_glow * .30)[..., None]
    return Image.fromarray(np.clip(a, 0, 255).astype(np.uint8))

stage(background(1280, 720, False)).save(os.path.join(OUT, "home_bg.jpg"), "JPEG", quality=86, optimize=True)
background(960, 540, True).save(os.path.join(OUT, "app_bg.jpg"), "JPEG", quality=82, optimize=True)
circle3d(360, False).save(os.path.join(OUT, "circle_n.png"), optimize=True)
circle3d(360, True).save(os.path.join(OUT, "circle_f.png"), optimize=True)
