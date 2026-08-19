#!/usr/bin/env python3
"""
生成 SushiVIP 的启动器图标（自适应图标 adaptive icon）。

用法：
    python3 tools/gen_launcher_icons.py                 # 用内置矢量复刻的 logo
    python3 tools/gen_launcher_icons.py doc/logo.png    # 用原始 logo 文件

关于自适应图标的安全区：
    前景层画布是 108x108dp，但各家启动器会用不同形状的遮罩去裁（圆形、
    方圆形、水滴形…），只有中心直径 66dp 的区域保证不被裁掉。
    所以 logo 必须缩到画布的约 61%，四周留白，否则边缘会被切掉。

依赖：pip install pillow numpy
"""
import math
import os
import sys

import numpy as np
from PIL import Image

RES = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res")

# 前景层各密度尺寸（对应 108dp）
FG_SIZES = {
    "mipmap-mdpi": 108,
    "mipmap-hdpi": 162,
    "mipmap-xhdpi": 216,
    "mipmap-xxhdpi": 324,
    "mipmap-xxxhdpi": 432,
}
# 传统方形图标各密度尺寸（48dp），给不读 adaptive icon 的第三方启动器兜底
LEGACY_SIZES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

BRAND_RED = (227, 30, 36)
# logo 在自适应图标前景画布中占的直径比例，必须 <= 66/108 ≈ 0.611
FG_LOGO_RATIO = 0.60
# 传统图标没有遮罩裁切，可以铺得满一些
LEGACY_LOGO_RATIO = 0.88


def draw_logo(size, diameter_ratio, ang_deg=52.0, d0_frac=1.45,
              bands=((0.72, 1.00), (0.44, 0.66), (0.17, 0.38), (0.00, 0.11))):
    """矢量复刻品牌 logo：红色圆盘被三条同心弧形缺口切成四块。

    弧的曲率中心 P 取在圆盘右下方远处，因此缺口沿左下→右上方向延伸、
    并向右下方凹 —— 与原始 logo 的走向一致。
    bands 是**红色**区域的 t 区间；t 沿远离 P 的方向从 0（右下极点）到 1（左上极点）。
    """
    ss = 4  # 4 倍超采样后再缩回来做抗锯齿
    S = size * ss
    yy, xx = np.mgrid[0:S, 0:S].astype(np.float64)
    c = (S - 1) / 2.0
    R = diameter_ratio / 2.0 * S

    a = math.radians(ang_deg)
    d0 = d0_frac * S
    px, py = c + d0 * math.cos(a), c + d0 * math.sin(a)

    inside = np.hypot(xx - c, yy - c) <= R
    t = ((d0 + R) - np.hypot(xx - px, yy - py)) / (2 * R)

    red = np.zeros_like(inside)
    for lo, hi in bands:
        red |= (t >= lo) & (t <= hi)

    img = np.zeros((S, S, 4), np.uint8)
    img[..., 0], img[..., 1], img[..., 2] = BRAND_RED
    img[..., 3] = (inside & red) * 255
    return Image.fromarray(img, "RGBA").resize((size, size), Image.LANCZOS)


def place(src, size, diameter_ratio):
    """把一张已有的 logo 图按比例居中放进指定尺寸的透明画布"""
    target = max(1, int(round(size * diameter_ratio)))
    im = src.copy()
    im.thumbnail((target, target), Image.LANCZOS)
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    canvas.paste(im, ((size - im.width) // 2, (size - im.height) // 2), im)
    return canvas


def main():
    src = None
    if len(sys.argv) > 1:
        src = Image.open(sys.argv[1]).convert("RGBA")
        print(f"使用原始 logo: {sys.argv[1]} ({src.width}x{src.height})")
    else:
        print("未指定 logo 文件，使用内置矢量复刻版本")

    def render(size, ratio):
        return place(src, size, ratio) if src else draw_logo(size, ratio)

    for folder, size in FG_SIZES.items():
        d = os.path.join(RES, folder)
        os.makedirs(d, exist_ok=True)
        render(size, FG_LOGO_RATIO).save(os.path.join(d, "ic_launcher_foreground.png"))

    for folder, size in LEGACY_SIZES.items():
        d = os.path.join(RES, folder)
        os.makedirs(d, exist_ok=True)
        icon = render(size, LEGACY_LOGO_RATIO)
        # 传统图标不支持透明背景遮罩，直接合成到品牌底色上
        bg = Image.new("RGBA", icon.size, (255, 255, 255, 255))
        bg.alpha_composite(icon)
        bg.save(os.path.join(d, "ic_launcher.png"))
        bg.save(os.path.join(d, "ic_launcher_round.png"))

    # Play 商店 / 侧载安装器用的 512 大图
    render(512, LEGACY_LOGO_RATIO).save(
        os.path.join(os.path.dirname(__file__), "..", "doc", "icon-512.png")
    )

    print("图标已写入 app/src/main/res/mipmap-*/")


if __name__ == "__main__":
    main()
