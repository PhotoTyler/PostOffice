"""Builds the PostOffice resource pack (original pixel art + models) into ../resourcepack.

Run:  python tools/generate_pack.py
Only uses the Python standard library.
"""
import json
import os
import random
import struct
import zlib

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "resourcepack")
NS = os.path.join(ROOT, "assets", "postoffice")


# ---------------------------------------------------------------- PNG

def write_png(path, pixels, size=16):
    """pixels: list of rows, each a list of (r,g,b,a)."""
    os.makedirs(os.path.dirname(path), exist_ok=True)
    raw = b"".join(b"\x00" + b"".join(struct.pack("BBBB", *p) for p in row) for row in pixels)

    def chunk(kind, data):
        c = struct.pack(">I", len(data)) + kind + data
        return c + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    w, h = len(pixels[0]), len(pixels)
    png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(png)


def hexc(h, a=255):
    h = h.lstrip("#")
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a)


CLEAR = (0, 0, 0, 0)


def shade(c, f):
    return (max(0, min(255, int(c[0] * f))), max(0, min(255, int(c[1] * f))), max(0, min(255, int(c[2] * f))), c[3])


def from_map(rows, palette):
    assert len(rows) == 16, rows
    out = []
    for row in rows:
        assert len(row) == 16, row
        out.append([palette[ch] if ch != "." else CLEAR for ch in row])
    return out


def noisy(base, seed, spread=0.07, size=16):
    rnd = random.Random(seed)
    return [[shade(base, 1 + rnd.uniform(-spread, spread)) for _ in range(size)] for _ in range(size)]


def overlay(img, rows, palette):
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch != ".":
                img[y][x] = palette[ch]
    return img


def scale_up(img, f):
    out = []
    for row in img:
        big = [p for p in row for _ in range(f)]
        for _ in range(f):
            out.append(list(big))
    return out


# ---------------------------------------------------------------- item sprites

PAPER = {
    "#": hexc("8a7a63"), "w": hexc("f4efe1"), "s": hexc("ddd5c0"), "k": hexc("6b6b78"),
    "q": hexc("2b2b2b"), "f": hexc("e8e3d8"), "F": hexc("bfb8a8"),
}
LETTER = [
    "...........fF...",
    "..########fF#...",
    "..#wwwwwwfFww#..",
    "..#wkkkkfFwww#..",
    "..#wwwwfFwwww#..",
    "..#wkkkqkkkkw#..",
    "..#wwwqwwwwww#..",
    "..#wkkkkkkkkw#..",
    "..#wwwwwwwwww#..",
    "..#wkkkkkkkww#..",
    "..#wwwwwwwwww#..",
    "..#wkkkkkwwww#..",
    "..#wwwwwwwwww#..",
    "..#wwwwwwwwws#..",
    "..############..",
    "................",
]

ENV = {
    "#": hexc("7d6a4f"), "c": hexc("efe2c2"), "d": hexc("cdbb93"), "r": hexc("9e1b1b"),
    "R": hexc("d23a2a"), "h": hexc("f07a5a"), "s": hexc("d9caa4"),
}
SEALED = [
    "................",
    "................",
    "................",
    ".##############.",
    ".#dccccccccccd#.",
    ".#cdccccccccdc#.",
    ".#ccdccccccdcc#.",
    ".#cccdccccdccc#.",
    ".#ccccdrrdcccc#.",
    ".#cccccrhRcccc#.",
    ".#cccccRRrcccc#.",
    ".#ccccccrccccs#.",
    ".#cccccccccccs#.",
    ".##############.",
    "................",
    "................",
]
OPENED = {
    **ENV, "w": hexc("fbf8f0"), "k": hexc("8c8c96"),
}
OPEN_ENV = [
    "................",
    "...wwwwwwwwww...",
    "...wkkkkkkkkw...",
    "...wwwwwwwwww...",
    ".##wkkkkkkwww##.",
    ".#dwwwwwwwwwwd#.",
    ".#cdkkkkkkkkdc#.",
    ".#ccdwwwwwwdcc#.",
    ".#cccdwwwwdccc#.",
    ".#ccccdssdcccc#.",
    ".#cccccssccccc#.",
    ".#cccccccccccc#.",
    ".#cccccccccccs#.",
    ".##############.",
    "................",
    "................",
]

BOX = {
    "#": hexc("5c3b1e"), "b": hexc("b07d48"), "l": hexc("c99560"), "d": hexc("8e6236"),
    "t": hexc("e2c48f"), "T": hexc("c9a76d"), "s": hexc("e9e2cf"),
}
PARCEL = [
    "................",
    "................",
    "..############..",
    ".#lllllttlllll#.",
    ".#lllllttlllll#.",
    ".#lllllttlllll#.",
    ".##############.",
    ".#bbbbbTTbbbbd#.",
    ".#bbbbbTTbbbbd#.",
    ".#ssssssssssss#.",
    ".#bbbbbTTbbbbd#.",
    ".#bbbbbTTbbbbd#.",
    ".#bbbbbTTbbbbd#.",
    ".#ddddddddddd##.",
    "..############..",
    "................",
]
EMPTY = [
    "................",
    ".##..........##.",
    ".#l#........#l#.",
    "..#l#......#l#..",
    "...#l######l#...",
    "..#dddddddddd#..",
    ".#############..",
    ".#bbbbbbbbbbbd#.",
    ".#bbbbbbbbbbbd#.",
    ".#bbbbbbbbbbbd#.",
    ".#bbbbbbbbbbbd#.",
    ".#bbbbbbbbbbbd#.",
    ".#bbbbbbbbbbbd#.",
    ".#ddddddddddd##.",
    "..############..",
    "................",
]


# ---------------------------------------------------------------- block textures

def cardboard(seed, edges=True):
    img = noisy(hexc("b07d48"), seed, 0.06)
    rnd = random.Random(seed + 99)
    for _ in range(10):  # fibres
        x, y = rnd.randrange(16), rnd.randrange(16)
        img[y][x] = hexc("9a6a3a")
    if edges:
        for i in range(16):
            img[0][i] = img[15][i] = img[i][0] = img[i][15] = hexc("7a5230")
    return img


def tape_rows(img, rows):
    for y in rows:
        for x in range(16):
            img[y][x] = hexc("e2c48f") if y == rows[0] else hexc("d1b07a")
    return img


def painted(base, seed, spread=0.05):
    img = noisy(hexc(base), seed, spread)
    for i in range(16):
        img[0][i] = shade(img[0][i], 1.15)
        img[15][i] = shade(img[15][i], 0.8)
    return img


RED = "c8102e"
UK_FRONT_OVERLAY = {
    "k": hexc("111111"), "K": hexc("3a0a10"), "p": hexc("f1ead2"), "P": hexc("b9b19a"),
    "g": hexc("d4af37"),
}
UK_FRONT = [
    "................",
    "................",
    "................",
    "....KKKKKKKK....",
    "....kkkkkkkk....",
    "....KKKKKKKK....",
    "................",
    ".......gg.......",
    ".....pppppp.....",
    ".....pPPPPp.....",
    ".....pppppp.....",
    ".....pPPPPp.....",
    ".....pppppp.....",
    "................",
    "................",
    "................",
]
DROPOFF_FRONT = [
    "................",
    "................",
    "................",
    "................",
    ".....KKKKKK.....",
    ".....kkkkkk.....",
    ".....KKKKKK.....",
    "................",
    "......pppp......",
    "......pPPp......",
    "......pppp......",
    "......pPPp......",
    "......pppp......",
    "................",
    "................",
    "................",
]

BLUE = "1f4e9c"
US_FRONT_OVERLAY = {"o": hexc("163a75"), "w": hexc("e8eef8"), "h": hexc("c0c6cf"), "H": hexc("6c737d")}
US_FRONT = [
    "................",
    "................",
    "................",
    "....oooooooo....",
    "....o......o....",
    "....o.wwww.o....",
    "....o.whhw.o....",
    "....o.wwww.o....",
    "....o....hHo....",
    "....oooooooo....",
    "................",
    "................",
    "................",
    "................",
    "................",
    "................",
]

CAP_SIDE = [
    "nnnnnnnnnnnnnnnn",
    "nnnnnnnnnnnnnnnn",
    "nnnnnnnnnnnnnnnn",
    "nnnnnnnnnnnnnnnn",
    "nnnnnnnnnnnnnnnn",
    "nnnnnnnnnnnnnnnn",
    "nnnnnnnnnnnnnnnn",
    "nnnnnnnnnnnnnnnn",
    "nnnnnnnnnnnnnnnn",
    "nnnnnnnnnnnnnnnn",
    "nnnnnnnnnnnnnnnn",
    "nnnnnnnnnnnnnnnn",
    "nnnnnnnnnnnnnnnn",
    "nnnnnnnnnnnnnnnn",
    "gggggggggggggggg",
    "GGGGGGGGGGGGGGGG",
]
CAP_FRONT = [
    "nnnnnnnnnnnnnnnn",
    "nnnnnnnnnnnnnnnn",
    "nnnnnnnnnnnnnnnn",
    "nnnnnnnnnnnnnnnn",
    "nnnnnnnnnnnnnnnn",
    "nnnnnnnnnnnnnnnn",
    "nnnnnnnnnnnnnnnn",
    "nnnnnnnnnnnnnnnn",
    "nnnnnnnnnnnnnnnn",
    "nnnnnnnnnnnnnnnn",
    "nnnnnnnnnnnnnnnn",
    "nnnnnnnnnnnnnnnn",
    "nnnnnnnyynnnnnnn",
    "nnnnnnyGGynnnnnn",
    "gggggggggggggggg",
    "GGGGGGGGGGGGGGGG",
]
CAP_PAL = {"n": hexc("2a4d8f"), "g": hexc("d4af37"), "G": hexc("a8862a"), "y": hexc("f3d36b")}


# ---------------------------------------------------------------- Postman villager skin

def postman_skin():
    """64x64 villager profession overlay (replaces the nitwit outfit): navy uniform, red collar,
    gold buttons, brown belt and mail bag. Transparent areas show the villager's own skin."""
    img = [[CLEAR] * 64 for _ in range(64)]
    navy, navy_d = hexc("2a4d8f"), hexc("1f3a6e")
    gold, red = hexc("d4af37"), hexc("c8102e")
    brown, brown_d = hexc("8b5a2b"), hexc("5e3a1a")
    trousers, shoes = hexc("1c2c4f"), hexc("161616")
    rnd = random.Random(42)

    def rect(x0, y0, x1, y1, c, jitter=0.04):
        for y in range(y0, y1):
            for x in range(x0, x1):
                img[y][x] = shade(c, 1 + rnd.uniform(-jitter, jitter))

    # body (under the coat)
    rect(16, 20, 44, 38, navy)
    rect(16, 20, 22, 26, CLEAR, 0)
    rect(38, 20, 44, 26, CLEAR, 0)
    # coat (the villager "robe", 8x20x6 at 0,38)
    rect(6, 38, 22, 44, navy)            # top + bottom faces
    rect(0, 44, 28, 64, navy)            # all four sides
    rect(0, 44, 28, 46, red, 0.02)       # collar
    rect(0, 55, 28, 57, brown_d, 0.02)   # belt
    rect(9, 55, 11, 57, gold, 0)         # buckle
    for y in (47, 50, 53, 59):           # buttons down the front
        img[y][10] = gold
    for i in range(9):                   # bag strap across the chest
        x, y = 6 + i, 46 + i
        if x < 14:
            img[y][x] = brown_d
    rect(11, 57, 14, 63, brown)          # mail bag
    rect(11, 57, 14, 58, brown_d, 0)
    img[59][12] = gold
    rect(20, 63, 28, 64, navy_d, 0)
    rect(0, 63, 20, 64, navy_d, 0)
    # arms (sleeves); the folded middle piece (hands) stays bare
    rect(48, 22, 56, 26, navy)
    rect(44, 26, 60, 34, navy)
    rect(44, 32, 60, 34, gold, 0.02)     # cuffs
    # legs
    rect(4, 22, 12, 26, trousers)
    rect(8, 22, 12, 26, shoes, 0)
    rect(0, 26, 16, 38, trousers)
    rect(0, 36, 16, 38, shoes, 0.02)
    return img


PLATE_PAL = {"k": hexc("1a1a1a"), "w": hexc("f4f1e8"), "g": hexc("6e6e6e"), "r": hexc("c8102e")}
PLATE = [
    "kkkkkkkkkkkkkkkk",
    "kwwwwwwwwwwwwwwk",
    "kwggggggwwwwwwwk",
    "kwwwwwwwwwwwwwwk",
    "kwgggwggggggwwwk",
    "kwwwwwwwwwwwwwwk",
    "kwggggggwwwwwwwk",
    "kwwwwwwwwwwwwwwk",
    "kwgggwgggggwwwwk",
    "kwwwwwwwwwwwwwwk",
    "kwggggggggwwwwwk",
    "kwwwwwwwwwwwwwwk",
    "kwgggwggggggwwwk",
    "kwwwwwwwwwwwwwwk",
    "kwwwwwwwwwwwwwwk",
    "kkkkkkkkkkkkkkkk",
]
CROWN_PAL = {"r": hexc("c8102e"), "Y": hexc("e2b93b"), "d": hexc("a8862a"), "R": hexc("7a0b1c"), "h": hexc("f6dc7a")}
CROWN = [
    "rrrrrrrrrrrrrrrr",
    "rrrrrrrYYrrrrrrr",
    "rrrrrrYhYYrrrrrr",
    "rrrrrrrYYrrrrrrr",
    "rrYrrrrYYrrrrYrr",
    "rYhYrrYhYYrrYYYr",
    "rrYYrYYYYYYrYYrr",
    "rrYYYYYYYYYYYYrr",
    "rrYYYRYYYYRYYYrr",
    "rrYhYYYYYYYYYYrr",
    "rrrYYYYYYYYYYrrr",
    "rrrYddddddddYrrr",
    "rrrYYYYYYYYYYrrr",
    "rrrrrrrrrrrrrrrr",
    "rrrrrrrrrrrrrrrr",
    "rrrrrrrrrrrrrrrr",
]

def build_textures():
    t = os.path.join(NS, "textures")
    write_png(os.path.join(t, "item", "letter.png"), from_map(LETTER, PAPER))
    write_png(os.path.join(t, "item", "sealed_letter.png"), from_map(SEALED, ENV))
    write_png(os.path.join(t, "item", "letter_open.png"), from_map(OPEN_ENV, OPENED))
    write_png(os.path.join(t, "item", "parcel.png"), from_map(PARCEL, BOX))
    write_png(os.path.join(t, "item", "empty_box.png"), from_map(EMPTY, BOX))

    b = os.path.join(t, "block")
    write_png(os.path.join(b, "cardboard_side.png"), cardboard(1))
    write_png(os.path.join(b, "cardboard_top.png"), tape_rows(cardboard(2), [7, 8]))
    write_png(os.path.join(b, "cardboard_inside.png"), [[shade(p, 0.72) for p in row] for row in cardboard(3, False)])
    flap = cardboard(4)
    for x in range(16):  # tape along the edge where the two flaps meet
        flap[15][x] = hexc("e2c48f")
        flap[14][x] = hexc("d1b07a")
    write_png(os.path.join(b, "cardboard_flap.png"), flap)

    write_png(os.path.join(b, "red_paint.png"), painted(RED, 10))
    write_png(os.path.join(b, "red_paint_dark.png"), [[shade(p, 0.8) for p in row] for row in painted(RED, 11)])
    write_png(os.path.join(b, "uk_front.png"), overlay(painted(RED, 12), UK_FRONT, UK_FRONT_OVERLAY))
    write_png(os.path.join(b, "dropoff_front.png"), overlay(painted(RED, 13), DROPOFF_FRONT, UK_FRONT_OVERLAY))
    write_png(os.path.join(b, "black_paint.png"), painted("2a2a2e", 14, 0.08))
    write_png(os.path.join(b, "blue_paint.png"), painted(BLUE, 15))
    write_png(os.path.join(b, "us_front.png"), overlay(painted(BLUE, 16), US_FRONT, US_FRONT_OVERLAY))
    write_png(os.path.join(b, "metal.png"), painted("6d7178", 17, 0.08))
    write_png(os.path.join(b, "pillar_plate.png"), from_map(PLATE, PLATE_PAL))
    write_png(os.path.join(b, "pillar_crown.png"), from_map(CROWN, CROWN_PAL))
    write_png(os.path.join(b, "slot_black.png"), noisy(hexc("0e0e0e"), 21, 0.05))
    write_png(os.path.join(b, "flag_red.png"), painted("d0312d", 18))

    write_png(os.path.join(b, "cap_side.png"), from_map(CAP_SIDE, CAP_PAL))
    write_png(os.path.join(b, "cap_front.png"), from_map(CAP_FRONT, CAP_PAL))
    write_png(os.path.join(b, "cap_top.png"), noisy(CAP_PAL["n"], 19, 0.05))
    write_png(os.path.join(b, "cap_brim.png"), noisy(hexc("111522"), 20, 0.05))

    write_png(os.path.join(ROOT, "pack.png"), scale_up(from_map(SEALED, ENV), 8))

    # The Postman is a nitwit villager, so the nitwit outfit becomes the postal uniform.
    write_png(os.path.join(ROOT, "assets", "minecraft", "textures", "entity", "villager", "profession", "nitwit.png"),
              postman_skin())


# ---------------------------------------------------------------- models

def el(frm, to, tex, faces=("north", "south", "east", "west", "up", "down"), per_face=None, uv=None):
    """A cuboid. Pass uv for parts taller than 16 so each face maps the whole texture."""
    per_face = per_face or {}
    face = lambda f: {"texture": per_face.get(f, tex), **({"uv": uv} if uv else {})}
    return {"from": frm, "to": to, "faces": {f: face(f) for f in faces}}


BLOCK_DISPLAY = {
    "gui": {"rotation": [30, 45, 0], "translation": [0, 0, 0], "scale": [0.625, 0.625, 0.625]},
    "ground": {"rotation": [0, 0, 0], "translation": [0, 3, 0], "scale": [0.25, 0.25, 0.25]},
    "fixed": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [0.5, 0.5, 0.5]},
    "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.375, 0.375, 0.375]},
    "thirdperson_lefthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.375, 0.375, 0.375]},
    "firstperson_righthand": {"rotation": [0, 45, 0], "translation": [0, 0, 0], "scale": [0.4, 0.4, 0.4]},
    "firstperson_lefthand": {"rotation": [0, 225, 0], "translation": [0, 0, 0], "scale": [0.4, 0.4, 0.4]},
    "head": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [0.6, 0.6, 0.6]},
}


def tex(name):
    return "postoffice:block/" + name


def build_models():
    m = os.path.join(NS, "models", "item")
    models = {}

    for name in ("letter", "sealed_letter", "letter_open", "parcel", "empty_box"):
        models[name] = {"parent": "minecraft:item/generated", "textures": {"layer0": "postoffice:item/" + name}}

    models["block_base"] = {"display": BLOCK_DISPLAY}

    # UK wall box: flat back against the wall (north = back, south = front)
    models["mailbox_uk"] = {
        "parent": "postoffice:item/block_base",
        "textures": {"particle": tex("red_paint"), "red": tex("red_paint"), "dark": tex("red_paint_dark"),
                     "front": tex("uk_front"), "black": tex("black_paint")},
        "elements": [
            el([3, 3, 0], [13, 13, 6], "#red", per_face={"south": "#front"}),
            el([2.5, 13, 0], [13.5, 15, 7], "#dark"),
            el([3.5, 2, 0], [12.5, 3, 5], "#black"),
        ],
    }

    # US standing box on a post, with the little red flag
    models["mailbox_us"] = {
        "parent": "postoffice:item/block_base",
        "textures": {"particle": tex("blue_paint"), "blue": tex("blue_paint"), "front": tex("us_front"),
                     "metal": tex("metal"), "flag": tex("flag_red")},
        "elements": [
            el([5, 0, 5], [11, 1, 11], "#metal"),
            el([7, 1, 7], [9, 7, 9], "#metal"),
            el([4, 7, 2], [12, 13, 14], "#blue", per_face={"south": "#front"}),
            el([4.5, 13, 2], [11.5, 14.5, 14], "#blue", per_face={"south": "#front"}),
            el([5.5, 14.5, 2], [10.5, 15.5, 14], "#blue"),
            el([12, 10, 9], [12.5, 14, 10], "#flag"),
            el([12, 13, 10], [12.5, 14, 12.5], "#flag"),
        ],
    }

    # Public drop-off: a pillar box
    # Public drop-off: a round UK-style pillar box, two blocks tall (the plugin puts barriers in both
    # blocks). Each "round" layer is three overlapping boxes, which reads as a cylinder in pixel style.
    full = [0, 0, 16, 16]

    def round_layer(y0, y1, r, texture):
        return [
            el([8 - r, y0, 8 - r + 2], [8 + r, y1, 8 + r - 2], texture, uv=full),
            el([8 - r + 1, y0, 8 - r + 1], [8 + r - 1, y1, 8 + r - 1], texture, uv=full),
            el([8 - r + 2, y0, 8 - r], [8 + r - 2, y1, 8 + r], texture, uv=full),
        ]

    def decal(x0, y0, x1, y1, z, texture):
        return el([x0, y0, z], [x1, y1, z + 0.2], texture, faces=("south", "east", "west", "up", "down"), uv=full)

    pillar = []
    pillar += round_layer(0, 1, 6, "#black")       # foot
    pillar += round_layer(1, 5, 5.5, "#black")     # black base
    pillar += round_layer(5, 21, 5, "#red")        # body
    pillar += round_layer(21, 24, 5.5, "#red")     # band with the letter slot
    pillar += round_layer(24, 26, 5, "#red")
    pillar += round_layer(26, 27, 6, "#red")       # cap rim
    pillar += round_layer(27, 29, 5, "#red")       # dome
    pillar += round_layer(29, 30.5, 4, "#red")
    pillar += round_layer(30.5, 31.5, 2.5, "#red")
    pillar.append(el([5.5, 22, 13.5], [10.5, 23, 13.8], "#slot", faces=("south", "up", "down", "east", "west"), uv=full))
    pillar.append(decal(6, 15, 10, 19, 13, "#plate"))   # collection times plate
    pillar.append(decal(6, 10.5, 10, 13.5, 13, "#crown"))  # gold crown
    models["dropoff"] = {
        "parent": "postoffice:item/block_base",
        "textures": {"particle": tex("red_paint"), "red": tex("red_paint"), "black": tex("black_paint"),
                     "slot": tex("slot_black"), "plate": tex("pillar_plate"), "crown": tex("pillar_crown")},
        "elements": pillar,
        "display": {
            **BLOCK_DISPLAY,
            "gui": {"rotation": [30, 45, 0], "translation": [0, -3.5, 0], "scale": [0.36, 0.36, 0.36]},
            "ground": {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [0.18, 0.18, 0.18]},
            "fixed": {"rotation": [0, 0, 0], "translation": [0, -4, 0], "scale": [0.3, 0.3, 0.3]},
            "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 1, 0], "scale": [0.22, 0.22, 0.22]},
            "thirdperson_lefthand": {"rotation": [75, 45, 0], "translation": [0, 1, 0], "scale": [0.22, 0.22, 0.22]},
            "firstperson_righthand": {"rotation": [0, 45, 0], "translation": [0, -2, 0], "scale": [0.25, 0.25, 0.25]},
            "firstperson_lefthand": {"rotation": [0, 225, 0], "translation": [0, -2, 0], "scale": [0.25, 0.25, 0.25]},
        },
    }

    models["parcel_closed"] = {
        "parent": "postoffice:item/block_base",
        "textures": {"particle": tex("cardboard_side"), "side": tex("cardboard_side"), "top": tex("cardboard_top")},
        "elements": [el([2, 0, 2], [14, 10, 14], "#side", per_face={"up": "#top"})],
    }

    inside = {"particle": tex("cardboard_side"), "side": tex("cardboard_side"), "in": tex("cardboard_inside")}
    models["parcel_open"] = {
        "parent": "postoffice:item/block_base",
        "textures": inside,
        "elements": [
            el([2, 0, 2], [14, 1, 14], "#side", per_face={"up": "#in"}),
            el([2, 0, 2], [14, 10, 3], "#side", per_face={"south": "#in"}),
            el([2, 0, 13], [14, 10, 14], "#side", per_face={"north": "#in"}),
            el([2, 0, 3], [3, 10, 13], "#side", per_face={"east": "#in"}),
            el([13, 0, 3], [14, 10, 13], "#side", per_face={"west": "#in"}),
        ],
    }

    models["parcel_flap"] = {
        "parent": "postoffice:item/block_base",
        "textures": {"particle": tex("cardboard_side"), "flap": tex("cardboard_flap"), "in": tex("cardboard_inside")},
        "elements": [el([2, 10, 2], [14, 10.5, 8], "#flap", per_face={"down": "#in"})],
    }

    # Postman cap: the model's y = 8 sits on top of his head; the brim points forward (south)
    models["postman_cap"] = {
        "parent": "postoffice:item/block_base",
        "display": {
            **BLOCK_DISPLAY,
            # Worn: the crown fits over the top of the head (about 4 px down), peak at the forehead.
            "head": {"rotation": [0, 180, 0], "translation": [0, 0, 0], "scale": [1.75, 1.75, 1.75]},
            "gui": {"rotation": [30, 45, 0], "translation": [0, -4, 0], "scale": [1.1, 1.1, 1.1]},
            "ground": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [0.6, 0.6, 0.6]},
        },
        "textures": {"particle": tex("cap_side"), "side": tex("cap_side"), "front": tex("cap_front"),
                     "top": tex("cap_top"), "brim": tex("cap_brim")},
        "elements": [
            el([3.75, 8, 3.75], [12.25, 11, 12.25], "#side", per_face={"south": "#front", "up": "#top", "down": "#top"}),
            el([4.25, 11, 4.25], [11.75, 11.5, 11.75], "#top"),
            el([4.5, 8, 12.25], [11.5, 8.5, 15], "#brim"),
        ],
    }

    for name, model in models.items():
        path = os.path.join(m, name + ".json")
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as f:
            json.dump(model, f, indent=2)

    # Item model definitions (assets/<ns>/items/<id>.json), referenced by the item_model component.
    items_dir = os.path.join(NS, "items")
    os.makedirs(items_dir, exist_ok=True)
    for name in models:
        if name == "block_base":
            continue
        with open(os.path.join(items_dir, name + ".json"), "w", encoding="utf-8") as f:
            json.dump({"model": {"type": "minecraft:model", "model": "postoffice:item/" + name}}, f, indent=2)


# The Book & Quill screen is drawn by the game, so its buttons can't be removed or added.
# Instead the pack renames them: "Sign" becomes "Put in envelope" (the plugin seals the letter
# instead of signing it) and the title box asks for the name on the envelope.
BOOK_TEXT = {
    "book.signButton": "Put in envelope",
    "book.editTitle": "Enter your name:",
    "book.finalizeButton": "Seal envelope",
    "book.finalizeWarning": "You can open the envelope and reseal it any time.",
    "book.sign.titlebox": "Your name",
    # The sign screen shows "by <player>" under the name; hide it so any name can be used.
    "book.byAuthor": "",
    # The Postman (and village Postmen) are nitwit villagers wearing the postal uniform.
    "entity.minecraft.villager.nitwit": "Postman",
}


def build_lang():
    for lang in ("en_us", "en_gb"):
        path = os.path.join(ROOT, "assets", "minecraft", "lang", lang + ".json")
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as f:
            json.dump(BOOK_TEXT, f, indent=2)


def build_meta():
    meta = {"pack": {"description": "PostOffice: letters, parcels & mailboxes", "min_format": 88, "max_format": 1000}}
    with open(os.path.join(ROOT, "pack.mcmeta"), "w", encoding="utf-8") as f:
        json.dump(meta, f, indent=2)


if __name__ == "__main__":
    build_textures()
    build_models()
    build_lang()
    build_meta()
    print("Resource pack written to", os.path.normpath(ROOT))
