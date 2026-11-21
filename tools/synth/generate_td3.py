#!/usr/bin/env python3
"""Synthetic TD3 MRZ generator.

Produces ICAO 9303 compliant two-line MRZs with valid check digits, drawn from
realistic distributions of issuing states, name lengths, filler patterns and
unknown-date cases, and optionally renders them in an OCR-B font onto a plain or
template background with augmentation.

Usage:
    generate_td3.py strings --count 1000 --out mrz.tsv
    generate_td3.py render  --count 1000 --font /path/to/OCRB.ttf --out-dir out/ [--templates dir/]

The TSV columns are `id, line1, line2`. Rendered output is one PNG per sample plus a
`labels.tsv` with the same columns and the band corners `x0,y0,...,x3,y3`.

OCR-B fonts are not included in this repository; several free OCR-B TrueType files
exist (search for "OCR-B TTF"). Use more than one variant when training.
"""

from __future__ import annotations

import argparse
import csv
import os
import random
import string
import sys

ALPHABET = string.digits + string.ascii_uppercase + "<"
WEIGHTS = (7, 3, 1)

# ISO alpha-3 plus ICAO specials, weighted roughly by how often an airline sees them.
STATES = (
    ["IND"] * 20 + ["GBR"] * 12 + ["USA"] * 12 + ["D<<"] * 8 + ["FRA"] * 6 + ["ARE"] * 6 + ["SAU"] * 5
    + ["PAK"] * 5 + ["BGD"] * 4 + ["NPL"] * 4 + ["LKA"] * 4 + ["CHN"] * 4 + ["JPN"] * 3 + ["AUS"] * 3
    + ["CAN"] * 3 + ["ITA"] * 3 + ["ESP"] * 3 + ["NLD"] * 2 + ["SGP"] * 2 + ["MYS"] * 2 + ["THA"] * 2
    + ["PHL"] * 2 + ["IDN"] * 2 + ["NGA"] * 2 + ["KEN"] * 2 + ["ZAF"] * 2 + ["BRA"] * 2 + ["RUS"] * 2
    + ["TUR"] * 2 + ["EGY"] * 2 + ["QAT", "KWT", "OMN", "BHR", "IRN", "IRQ", "AFG", "XXA", "XXB", "GBD", "GBN", "UNO", "RKS"]
)

SURNAMES = ["SHARMA", "PATEL", "SMITH", "JOHNSON", "MUELLER", "GARCIA", "KHAN", "NGUYEN", "KIM", "SILVA",
            "ROSSI", "IVANOV", "YAMADA", "VAN<DER<BERG", "O<BRIEN", "AL<RASHID", "PAPADOPOULOS", "KOWALSKI",
            "ERIKSSON", "DUBOIS", "ABDULLAH", "RAHMAN", "FERNANDEZ", "SANTOS", "CHEN", "WANG", "SINGH"]
GIVEN = ["ANNA", "MARIA", "JOHN", "DAVID", "PRIYA", "AMIT", "MOHAMMED", "FATIMA", "WEI", "LI", "JOSE", "LUIS",
         "SOPHIE", "HANS", "YUKI", "KENJI", "OLGA", "DMITRI", "SARA", "AHMED", "GRACE", "ETHAN", "AISHA",
         "RAVI", "DEEPA", "NOOR", "LEILA", "ARJUN"]


def check_digit(data: str) -> int:
    total = 0
    for i, c in enumerate(data):
        if c.isdigit():
            v = int(c)
        elif c == "<":
            v = 0
        else:
            v = ord(c) - 55
        total += v * WEIGHTS[i % 3]
    return total % 10


def rand_doc_number(rng: random.Random) -> str:
    style = rng.random()
    if style < 0.5:
        return rng.choice(string.ascii_uppercase) + "".join(rng.choices(string.digits, k=8))
    if style < 0.8:
        return "".join(rng.choices(string.digits, k=9))
    if style < 0.95:
        return "".join(rng.choices(string.ascii_uppercase, k=2)) + "".join(rng.choices(string.digits, k=7))
    return ("".join(rng.choices(string.ascii_uppercase + string.digits, k=rng.randint(6, 8)))).ljust(9, "<")


def rand_date(rng: random.Random, start: int, end: int) -> str:
    y = rng.randint(start, end)
    m = rng.randint(1, 12)
    d = rng.randint(1, 28)
    return f"{y % 100:02d}{m:02d}{d:02d}"


def rand_names(rng: random.Random) -> str:
    primary = rng.choice(SURNAMES)
    if rng.random() < 0.15:
        primary += "<" + rng.choice(SURNAMES)
    n_given = rng.choices([0, 1, 2, 3], weights=[2, 60, 30, 8])[0]
    given = "<".join(rng.choice(GIVEN) for _ in range(n_given))
    field = primary + "<<" + given if n_given else primary
    field = field[:39]
    return field.ljust(39, "<")


def generate(rng: random.Random) -> tuple[str, str]:
    state = rng.choice(STATES)
    nationality = state if rng.random() < 0.9 else rng.choice(STATES)
    subtype = "<" if rng.random() < 0.92 else rng.choice("ADOS")
    line1 = "P" + subtype + state + rand_names(rng)

    doc = rand_doc_number(rng)
    dob = rand_date(rng, 1935, 2024)
    if rng.random() < 0.01:  # unknown birth digits
        dob = dob[:2] + "<<<<"
    sex = rng.choices("MF<", weights=[49, 49, 2])[0]
    exp = rand_date(rng, 2026, 2036)
    if rng.random() < 0.6:
        opt = "<" * 14
    else:
        opt = "".join(rng.choices(string.ascii_uppercase + string.digits, k=rng.randint(6, 14))).ljust(14, "<")
    opt_check = str(check_digit(opt))
    if opt == "<" * 14 and rng.random() < 0.3:
        opt_check = "<"
    body = f"{doc}{check_digit(doc)}{nationality}{dob}{check_digit(dob)}{sex}{exp}{check_digit(exp)}{opt}{opt_check}"
    composite = check_digit(body[0:10] + body[13:20] + body[21:43])
    line2 = body + str(composite)
    assert len(line1) == 44 and len(line2) == 44, (line1, line2)
    return line1, line2


def cmd_strings(args: argparse.Namespace) -> None:
    rng = random.Random(args.seed)
    with open(args.out, "w", newline="") as f:
        w = csv.writer(f, delimiter="\t")
        w.writerow(["id", "line1", "line2"])
        for i in range(args.count):
            l1, l2 = generate(rng)
            w.writerow([f"syn{i:07d}", l1, l2])
    print(f"wrote {args.count} MRZs to {args.out}")


def cmd_render(args: argparse.Namespace) -> None:
    try:
        from PIL import Image, ImageDraw, ImageFilter, ImageFont
    except ImportError:
        sys.exit("render requires Pillow: pip install pillow")
    rng = random.Random(args.seed)
    font = ImageFont.truetype(args.font, args.font_size)
    os.makedirs(args.out_dir, exist_ok=True)
    templates = []
    if args.templates and os.path.isdir(args.templates):
        templates = [os.path.join(args.templates, n) for n in os.listdir(args.templates) if n.lower().endswith((".png", ".jpg"))]
    with open(os.path.join(args.out_dir, "labels.tsv"), "w", newline="") as f:
        w = csv.writer(f, delimiter="\t")
        w.writerow(["id", "line1", "line2", "x0", "y0", "x1", "y1", "x2", "y2", "x3", "y3"])
        for i in range(args.count):
            l1, l2 = generate(rng)
            sid = f"syn{i:07d}"
            if templates:
                page = Image.open(rng.choice(templates)).convert("L").resize((args.page_width, int(args.page_width * 88 / 125)))
            else:
                page = Image.new("L", (args.page_width, int(args.page_width * 88 / 125)), rng.randint(200, 245))
            draw = ImageDraw.Draw(page)
            line_h = args.font_size
            x = int(page.width * 0.04)
            y = int(page.height * 0.76)
            draw.text((x, y), l1, font=font, fill=rng.randint(0, 40))
            draw.text((x, y + int(line_h * 1.25)), l2, font=font, fill=rng.randint(0, 40))
            text_w = draw.textlength(l2, font=font)
            corners = [(x, y), (x + text_w, y), (x + text_w, y + line_h * 2.3), (x, y + line_h * 2.3)]

            # Augmentation: blur, noise, glare streak, perspective, brightness.
            img = page
            if rng.random() < 0.5:
                img = img.filter(ImageFilter.GaussianBlur(rng.uniform(0.3, 1.8)))
            if rng.random() < 0.3:
                g = ImageDraw.Draw(img)
                gy = rng.randint(int(y), int(y + line_h * 2))
                g.rectangle([x, gy, x + text_w, gy + rng.randint(2, int(line_h / 2))], fill=255)
            if rng.random() < 0.5:
                img = img.point(lambda p: max(0, min(255, int(p * rng.uniform(0.7, 1.2) + rng.uniform(-20, 20)))))
            if rng.random() < 0.6:
                wpx, hpx = img.size
                dx = rng.uniform(-0.05, 0.05) * wpx
                dy = rng.uniform(-0.05, 0.05) * hpx
                src = [(0, 0), (wpx, 0), (wpx, hpx), (0, hpx)]
                dst = [(dx, dy), (wpx - dx, -dy), (wpx + dx, hpx - dy), (-dx, hpx + dy)]
                coeffs = _perspective_coeffs(dst, src)
                img = img.transform(img.size, Image.PERSPECTIVE, coeffs, Image.BILINEAR)
                corners = [_apply_perspective(_perspective_coeffs(src, dst), c) for c in corners]
            img.save(os.path.join(args.out_dir, sid + ".png"))
            w.writerow([sid, l1, l2] + [f"{v:.1f}" for c in corners for v in c])
    print(f"rendered {args.count} samples into {args.out_dir}")


def _perspective_coeffs(pa, pb):
    import numpy as np
    matrix = []
    for p1, p2 in zip(pa, pb):
        matrix.append([p1[0], p1[1], 1, 0, 0, 0, -p2[0] * p1[0], -p2[0] * p1[1]])
        matrix.append([0, 0, 0, p1[0], p1[1], 1, -p2[1] * p1[0], -p2[1] * p1[1]])
    A = np.array(matrix, dtype=float)
    B = np.array(pb).reshape(8)
    res = np.linalg.solve(A, B)
    return tuple(res)


def _apply_perspective(c, p):
    x, y = p
    d = c[6] * x + c[7] * y + 1
    return ((c[0] * x + c[1] * y + c[2]) / d, (c[3] * x + c[4] * y + c[5]) / d)


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("strings")
    s.add_argument("--count", type=int, default=1000)
    s.add_argument("--seed", type=int, default=1)
    s.add_argument("--out", default="mrz.tsv")
    s.set_defaults(func=cmd_strings)
    r = sub.add_parser("render")
    r.add_argument("--count", type=int, default=1000)
    r.add_argument("--seed", type=int, default=1)
    r.add_argument("--font", required=True, help="path to an OCR-B .ttf")
    r.add_argument("--font-size", type=int, default=28)
    r.add_argument("--page-width", type=int, default=1250)
    r.add_argument("--templates", help="directory of inpainted data-page template images")
    r.add_argument("--out-dir", default="out")
    r.set_defaults(func=cmd_render)
    args = ap.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
