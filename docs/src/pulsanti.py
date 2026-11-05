# Pulsanti in rilievo (effetto 3D) di Kairos XC: genera i drawable XML in res/drawable.
#   python3 docs/src/pulsanti.py app/src/main/res/drawable
# Ogni pulsante = bordo inferiore scuro (lo «spessore»), faccia, luce dall'alto, filo di luce sul bordo.
# Premuto: la faccia scende e lo spessore sparisce. Solo forme XML: nessuna immagine, leggero.
import sys, os
OUT = sys.argv[1]
D = 3  # spessore in dp
HL = '<item android:bottom="{d}dp"><shape android:shape="rectangle"><gradient android:angle="270" android:startColor="#40FFFFFF" android:centerColor="#08FFFFFF" android:endColor="#26000000" /><corners android:radius="{r}dp" /></shape></item>'
RIM = '<item android:bottom="{d}dp"><shape android:shape="rectangle"><solid android:color="#00000000" /><stroke android:width="{w}dp" android:color="{c}" /><corners android:radius="{r}dp" /></shape></item>'

def face(fill, r, d=D, top=0):
    pos = f'android:top="{top}dp"' if top else f'android:bottom="{d}dp"'
    return f'<item {pos}><shape android:shape="rectangle">{fill}<corners android:radius="{r}dp" /></shape></item>'

def edge(color, r):
    return f'<item android:top="{D}dp"><shape android:shape="rectangle"><solid android:color="{color}" /><corners android:radius="{r}dp" /></shape></item>'

GRAD = '<gradient android:angle="0" android:startColor="@color/accent" android:endColor="@color/accent2" />'
def solid(c): return f'<solid android:color="{c}" />'
def vgrad(a, b): return f'<gradient android:angle="270" android:startColor="{a}" android:endColor="{b}" />'

def raised(fill, edge_c, r, rim="#59FFFFFF", rimw=1):
    return ('<layer-list>' + edge(edge_c, r) + face(fill, r) + HL.format(d=D, r=r)
            + RIM.format(d=D, w=rimw, c=rim, r=r) + '</layer-list>')

def pressed(fill, r):
    return '<layer-list>' + face(fill, r, top=D) + '</layer-list>'

def flat(fill, r, stroke=None):
    s = f'<stroke android:width="1dp" android:color="{stroke}" />' if stroke else ''
    return f'<shape android:shape="rectangle">{fill}{s}<corners android:radius="{r}dp" /></shape>'

def sel(name, comment, items):
    x = '<?xml version="1.0" encoding="utf-8"?>\n<!-- ' + comment + ' (generato da docs/src/pulsanti.py) -->\n'
    x += '<selector xmlns:android="http://schemas.android.com/apk/res/android" android:enterFadeDuration="0" android:exitFadeDuration="0">\n'
    for st, body in items:
        x += f'    <item{st}>{body}</item>\n'
    x += '</selector>\n'
    open(os.path.join(OUT, name + '.xml'), 'w').write(x)

F = ' android:state_focused="true"'
P = ' android:state_pressed="true"'
A = ' android:state_activated="true"'
EDGE_A = '#FF9A2A16'      # spessore sotto l'arancio/rosa
EDGE_N = '#FF0C050A'      # spessore sotto i pulsanti scuri
N_FACE = vgrad('#FF3A2336', '#FF241420')

def std(name, r, comment, normal_fill=None, normal_edge=EDGE_N, activated=None):
    items = [(P, pressed(GRAD, r)), (F, raised(GRAD, EDGE_A, r, rim='#B3FFFFFF', rimw=1.5))]  # premuto prima: con OK sul telecomando si vede scendere
    if activated: items.append((A, activated))
    items.append(('', raised(normal_fill or N_FACE, normal_edge, r)))
    sel(name, comment, items)

std('bg_btn', 14, 'pulsante normale in rilievo')
std('bg_btn_primary', 14, 'pulsante principale (Guarda, Riprendi) in rilievo', normal_fill=GRAD, normal_edge=EDGE_A)
std('bg_icon_btn', 22, 'pulsante tondo con icona in rilievo')
std('bg_tile', 20, 'riquadro in rilievo')
std('bg_hero_btn', 22, 'pulsante grande in rilievo')
std('bg_pill', 20, 'pillola (giorni, stagioni) in rilievo', activated=raised(vgrad('#FFFFFFFF', '#FFE6DDE3'), '#FF8A7A86', 20))
std('home_tile', 16, 'pulsanti piccoli della Home in rilievo', normal_fill=vgrad('#E6402638', '#E61E101A'))
# schede in alto: piatte finché non sono selezionate
sel('bg_tab', 'schede in alto: in rilievo quando selezionate', [
    (P, pressed(GRAD, 18)), (F, raised(GRAD, EDGE_A, 18, rim='#B3FFFFFF', rimw=1.5)),
    (A, flat(solid('#26FFFFFF'), 18, '#80FF6A1F')), ('', flat(solid('#00000000'), 18))])
# righe delle liste (canali, categorie): piatte, in rilievo solo la selezionata
sel('bg_row', 'righe delle liste: in rilievo la selezionata', [
    (P, pressed(vgrad('#FF4A2A40', '#FF331C2C'), 12)), (F, raised(vgrad('#FF4A2A40', '#FF331C2C'), EDGE_A, 12, rim='#CCFF6A1F', rimw=2)),
    (A, flat(solid('@color/surface2'), 12)), ('', flat(solid('#00000000'), 12))])
sel('bg_row_solid', 'righe piene: leggero rilievo, più alto la selezionata', [
    (P, pressed(vgrad('#FF4A2A40', '#FF331C2C'), 12)), (F, raised(vgrad('#FF4A2A40', '#FF331C2C'), EDGE_A, 12, rim='#CCFF6A1F', rimw=2)),
    (A, raised(solid('@color/surface2'), EDGE_N, 12)), ('', raised(vgrad('#F2301B2C', '#F2221320'), EDGE_N, 12, rim='#26FFFFFF'))])
# campi di testo: incavati (luce in basso, ombra in alto)
x = '''<?xml version="1.0" encoding="utf-8"?>
<!-- campi di testo incavati (generato da docs/src/pulsanti.py) -->
<selector xmlns:android="http://schemas.android.com/apk/res/android" android:enterFadeDuration="0" android:exitFadeDuration="0">
    <item android:state_focused="true"><layer-list>
        <item><shape android:shape="rectangle"><solid android:color="#FF120810" /><corners android:radius="14dp" /></shape></item>
        <item android:top="2dp"><shape android:shape="rectangle"><solid android:color="@color/surface2" /><stroke android:width="2dp" android:color="@color/accent" /><corners android:radius="14dp" /></shape></item>
    </layer-list></item>
    <item><layer-list>
        <item><shape android:shape="rectangle"><solid android:color="#FF0C050A" /><corners android:radius="14dp" /></shape></item>
        <item android:top="2dp"><shape android:shape="rectangle"><gradient android:angle="90" android:startColor="#FF2E1B2B" android:endColor="#FF20121D" /><stroke android:width="1dp" android:color="@color/line" /><corners android:radius="14dp" /></shape></item>
    </layer-list></item>
</selector>
'''
open(os.path.join(OUT, 'bg_input.xml'), 'w').write(x)
