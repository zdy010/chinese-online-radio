#!/usr/bin/env python3
"""把矢量启动图标渲染成 mipmap 各密度的旧版 PNG。

为什么还要留一份位图：minSdk 26 之后启动器都走 adaptive icon，但车机上不少第三方
launcher 只认 android:icon 指向的位图，遇到 adaptive-only 会直接显示系统默认图标。
所以 res/drawable/ic_launcher_*.xml 是真相，本脚本按同一套几何把 PNG 刷出来，
改完矢量记得跑一次：

    python tools/gen_launcher_pngs.py

几何参数与 ic_launcher_foreground.xml 一一对应（108 画布坐标），改一处要改两处时
以矢量为准。
"""
import math
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app" / "src" / "main" / "res"

# 与 ic_launcher_background.xml 同一条对角渐变：起点 (6,102) 终点 (102,6)
STOPS = [(0.0, (0x2B, 0x1E, 0x78)), (0.48, (0x7A, 0x2B, 0xE0)), (1.0, (0xFF, 0x2E, 0x88))]
AXIS_P0 = (6.0, 102.0)
AXIS_P1 = (102.0, 6.0)

# 与 ic_launcher_foreground.xml 同一套图形
STEM = ((46.2, 67.5), (46.2, 44.0), 4.5)
HEAD = (40.0, 68.0, 7.6, 6.2, -22.0)          # cx, cy, rx, ry, SVG 旋转角
ARCS = [                                        # 圆心 (40,68)，半径 22/31/40，跨 15°~75°
    (22.0, 5.5, (255, 255, 255, 255)),
    (31.0, 5.0, (255, 255, 255, 245)),
    (40.0, 4.5, (255, 255, 255, 224)),
]
ARC_FROM, ARC_TO = -75.0, -15.0

DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
# 前景层旧尺寸：自适应图标图层按 108dp 出图（mipmap-*dpi/ic_launcher_foreground.png）。
# 当前 adaptive-icon 已改指 @drawable 矢量，这批 PNG 仅供只认位图的第三方 launcher 兜底，
# 但仍得跟着重渲染——留着上一版红收音机比留着冗余文件更坑。
FOREGROUND_DENSITIES = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}
SUPER = 4          # 先按 4 倍画再缩小，代替抗锯齿


def ramp_color(t: float):
    for (o0, c0), (o1, c1) in zip(STOPS, STOPS[1:]):
        if t <= o1:
            k = 0.0 if o1 == o0 else max(0.0, min(1.0, (t - o0) / (o1 - o0)))
            return tuple(round(c0[i] + (c1[i] - c0[i]) * k) for i in range(3))
    return STOPS[-1][1]


def background(side_px: int) -> Image.Image:
    """沿 (6,102)->(102,6) 的线性渐变：先画水平色带，再按轴方向旋转后取中心区。"""
    diag = int(math.ceil(side_px * math.sqrt(2))) + 2
    strip = Image.new("RGB", (diag, 1))
    for x in range(diag):
        strip.putpixel((x, 0), ramp_color(x / (diag - 1)))
    strip = strip.resize((diag, diag), Image.NEAREST)
    # 渐变 x 轴要落到屏幕方向 (1,-1)/√2 上 => PIL 逆时针 45°
    rotated = strip.rotate(45, resample=Image.BICUBIC, expand=True)
    left = (rotated.width - side_px) // 2
    top = (rotated.height - side_px) // 2
    return rotated.crop((left, top, left + side_px, top + side_px)).convert("RGBA")


def foreground(side_px: int) -> Image.Image:
    s = side_px / 108.0
    layer = Image.new("RGBA", (side_px, side_px), (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer)

    def dot(cx, cy, w, fill):
        r = w / 2.0
        draw.ellipse([(cx - r) * s, (cy - r) * s, (cx + r) * s, (cy + r) * s], fill=fill)

    (x0, y0), (x1, y1), w = STEM
    draw.line([x0 * s, y0 * s, x1 * s, y1 * s], fill=(255, 255, 255, 255), width=round(w * s))
    dot(x0, y0, w, (255, 255, 255, 255))   # 圆头端点：PIL 的 line 是平头，补两个点还原 strokeLineCap=round
    dot(x1, y1, w, (255, 255, 255, 255))

    cx, cy, rx, ry, rot = HEAD
    box = max(rx, ry) + 2
    side = round(box * 2 * s)
    head = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    hd = ImageDraw.Draw(head)
    # 局部坐标里把椭圆中心放到画布正中，旋转才不会把符头甩出图外
    hd.ellipse(
        [(box - rx) * s, (box - ry) * s, (box + rx) * s, (box + ry) * s],
        fill=(255, 255, 255, 255),
    )
    # SVG 正角是顺时针，PIL 正角是逆时针，所以取反
    head = head.rotate(-rot, resample=Image.BICUBIC, expand=False)
    layer.alpha_composite(head, (round((cx - box) * s), round((cy - box) * s)))

    for r, w, color in ARCS:
        bbox = [(cx - r) * s, (cy - r) * s, (cx + r) * s, (cy + r) * s]
        draw.arc(bbox, ARC_FROM, ARC_TO, fill=color, width=round(w * s))
        for ang in (ARC_FROM, ARC_TO):   # 圆头端点
            a = math.radians(ang)
            dot(cx + r * math.cos(a), cy + r * math.sin(a), w, color)
    return layer


def mask(shape: str, side_px: int) -> Image.Image:
    m = Image.new("L", (side_px, side_px), 0)
    d = ImageDraw.Draw(m)
    if shape == "circle":
        d.ellipse([0, 0, side_px - 1, side_px - 1], fill=255)
    else:
        d.rounded_rectangle([0, 0, side_px - 1, side_px - 1], radius=side_px * 0.18, fill=255)
    return m


def write(shape: str, name: str):
    for dens, px in DENSITIES.items():
        big = px * SUPER
        img = background(big)
        img = Image.composite(img, Image.new("RGBA", (big, big), (0, 0, 0, 0)), mask(shape, big))
        img.alpha_composite(foreground(big))
        out = img.resize((px, px), Image.LANCZOS)
        folder = RES / f"mipmap-{dens}"
        folder.mkdir(parents=True, exist_ok=True)
        out.save(folder / name)
        print(f"  mipmap-{dens}/{name}  {px}x{px}")


def write_foreground():
    for dens, px in FOREGROUND_DENSITIES.items():
        big = px * SUPER
        out = foreground(big).resize((px, px), Image.LANCZOS)
        folder = RES / f"mipmap-{dens}"
        folder.mkdir(parents=True, exist_ok=True)
        out.save(folder / "ic_launcher_foreground.png")
        print(f"  mipmap-{dens}/ic_launcher_foreground.png  {px}x{px}")


def main():
    print("生成旧版启动图标 PNG（矢量源：res/drawable/ic_launcher_*.xml）")
    write("square", "ic_launcher.png")
    write("circle", "ic_launcher_round.png")
    write_foreground()


if __name__ == "__main__":
    main()
