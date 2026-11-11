#!/usr/bin/env python3
"""Erzeugt Blu-ray-Bilduntertitel (PGS/.sup) aus Text, wie sie auf UHD-Remuxen vorkommen.

Aufruf: make-pgs.py ausgabe.sup  (englische Sätze, alle 4 s für 3 s, 120 s lang)
"""
import struct
import sys

from PIL import Image, ImageDraw, ImageFont

PHRASES = ["Where is my brother?", "Thank you for everything.", "We have to leave before it gets dark.",
           "Hello, how are you?", "I told you we should never have come here."]
FONT = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 46)
# Palette: 0 durchsichtig, 1 weiß (Schrift), 2 schwarz (Rand); Y, Cr, Cb, Alpha
PALETTE = [(0, 16, 128, 128, 0), (1, 235, 128, 128, 255), (2, 16, 128, 128, 255)]


def segment(pts, kind, data):
    return b"PG" + struct.pack(">IIBH", pts, 0, kind, len(data)) + data


def rle(pixels, width, height):
    out = bytearray()
    for y in range(height):
        row = pixels[y * width:(y + 1) * width]
        i = 0
        while i < width:
            c, j = row[i], i
            while j < width and row[j] == c and j - i < 16383:
                j += 1
            n = j - i
            if c != 0 and n < 3:
                out += bytes([c]) * n
            elif c == 0:
                out += bytes([0, n]) if n < 64 else bytes([0, 0x40 | (n >> 8), n & 0xFF])
            else:
                out += bytes([0, 0x80 | n, c]) if n < 64 else bytes([0, 0xC0 | (n >> 8), n & 0xFF, c])
            i = j
        out += b"\x00\x00"
    return bytes(out)


def bitmap(text):
    width = int(FONT.getlength(text)) + 24
    img = Image.new("RGBA", (width, 70), (0, 0, 0, 0))
    ImageDraw.Draw(img).text((12, 6), text, font=FONT, fill=(255, 255, 255, 255), stroke_width=3, stroke_fill=(0, 0, 0, 255))
    raw = img.tobytes()
    pixels = [0 if raw[i + 3] < 128 else (1 if (raw[i] + raw[i + 1] + raw[i + 2]) / 3 > 128 else 2) for i in range(0, len(raw), 4)]
    return width, 70, pixels


def main(path):
    out = bytearray()
    for i in range(30):
        start, end = (i * 4 + 1) * 90000, (i * 4 + 4) * 90000
        w, h, pixels = bitmap(PHRASES[i % len(PHRASES)])
        x, y = (1920 - w) // 2, 1080 - h - 60
        data = rle(pixels, w, h)
        assert len(data) + 11 < 65535, "Bild zu groß für ein ODS-Segment"
        pcs = struct.pack(">HHBHBBBB", 1920, 1080, 0x10, 2 * i, 0x80, 0, 0, 1) + struct.pack(">HBBHH", 0, 0, 0, x, y)
        wds = struct.pack(">B", 1) + struct.pack(">BHHHH", 0, x, y, w, h)
        pds = struct.pack(">BB", 0, 0) + b"".join(struct.pack(">BBBBB", *e) for e in PALETTE)
        ods = struct.pack(">HBB", 0, 0, 0xC0) + (len(data) + 4).to_bytes(3, "big") + struct.pack(">HH", w, h) + data
        out += segment(start, 0x16, pcs) + segment(start, 0x17, wds) + segment(start, 0x14, pds)
        out += segment(start, 0x15, ods) + segment(start, 0x80, b"")
        # Ausblenden: Komposition ohne Objekte
        clear = struct.pack(">HHBHBBBB", 1920, 1080, 0x10, 2 * i + 1, 0x00, 0, 0, 0)
        out += segment(end, 0x16, clear) + segment(end, 0x17, wds) + segment(end, 0x80, b"")
    open(path, "wb").write(out)


if __name__ == "__main__":
    main(sys.argv[1])
