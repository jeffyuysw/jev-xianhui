"""由 app_icon/icon.png 生成双端图标资源。

源图特征（实测）：
  - 1920x1920 RGBA，但四角为白色 (254,254,254) 且**零透明像素**
  - 蓝色圆角方块，圆角半径约 300px（约 15.6%），符合超椭圆/squircle 风格
处理策略：
  - 把圆角外的浅色区域抠成透明 -> 得到干净的透明圆角图标
  - Windows: 导出多尺寸 .ico（16/24/32/48/64/128/256）
  - Android: 导出 mdpi~xxxhdpi 五档方形 PNG + 自适应图标前景层（留安全边距）
"""

from __future__ import annotations

import os
from pathlib import Path

from PIL import Image, ImageDraw

# 仓库根目录 = 本脚本所在目录（tools/）的上一级，无需改路径即可 clone 后直接跑
ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "icon.png"

WIN_DIR = ROOT / "jev-xianhui-windows" / "assets"
AND_RES = ROOT / "jev-xianhui" / "app" / "src" / "main" / "res"


def load_cutout() -> Image.Image:
    """抠掉圆角外的白色背景，返回透明圆角图标。"""
    im = Image.open(SRC).convert("RGBA")
    W, H = im.size

    radius = int(W * 0.156)  # ≈ 300，实测圆角比例
    mask = Image.new("L", (W, H), 0)
    d = ImageDraw.Draw(mask)
    d.rounded_rectangle([(0, 0), (W - 1, H - 1)], radius=radius, fill=255)

    out = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    out.paste(im, (0, 0), mask)
    return out


def load_fullbleed() -> Image.Image:
    """自适应图标前景层专用：把**圆角外**的浅色区域填成品牌蓝，铺满方形。

    注意：不能用"颜色接近白"来判定，否则图标内部的白色气泡与文字会被一并误伤。
    正确做法是用几何蒙版：只有位于圆角矩形之外的像素才填充为蓝色。
    """
    import numpy as np

    im = Image.open(SRC).convert("RGBA")
    W, H = im.size

    # 圆角矩形蒙版：内部 255（保留原图像素），外部 0（需填充蓝色）
    radius = int(W * 0.156)
    mask = Image.new("L", (W, H), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        [(0, 0), (W - 1, H - 1)], radius=radius, fill=255
    )
    inside = np.array(mask) > 128  # True = 圆角内，保留

    arr = np.array(im)
    arr[~inside] = (2, 81, 253, 255)  # 圆角外统一填品牌蓝
    return Image.fromarray(arr, "RGBA")


def save_windows_ico(icon: Image.Image) -> Path:
    WIN_DIR.mkdir(parents=True, exist_ok=True)
    sizes = [16, 24, 32, 48, 64, 128, 256]
    # Pillow 的 ICO 保存支持多尺寸，内置高质量缩放
    base = icon.resize((256, 256), Image.LANCZOS)
    dst = WIN_DIR / "app.ico"
    base.save(dst, format="ICO", sizes=[(s, s) for s in sizes])
    print(f"[win] {dst}  sizes={sizes}")
    return dst


def save_windows_png(icon: Image.Image) -> Path:
    """同时导出一张 256 PNG，供 PyInstaller / 文档使用。"""
    WIN_DIR.mkdir(parents=True, exist_ok=True)
    dst = WIN_DIR / "app_icon_256.png"
    icon.resize((256, 256), Image.LANCZOS).save(dst, "PNG")
    print(f"[win] {dst}")
    return dst


# ---------- Android ----------
# 传统 mipmap 各密度尺寸（方形图标）
DENSITIES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}

# 自适应图标：前景层画布是 108dp，其中安全区 72dp（中心 66.7%）
ADAPTIVE = {
    "mdpi": 108,
    "hdpi": 162,
    "xhdpi": 216,
    "xxhdpi": 324,
    "xxxhdpi": 432,
}
SAFE_RATIO = 72 / 108  # 安全区占比


def save_android(icon: Image.Image) -> None:
    import numpy as np

    # 1) 传统方形图标 mipmap/ic_launcher.png
    for dpi, size in DENSITIES.items():
        d = AND_RES / f"mipmap-{dpi}"
        d.mkdir(parents=True, exist_ok=True)
        icon.resize((size, size), Image.LANCZOS).save(d / "ic_launcher.png", "PNG")
        # round 版：圆形裁切
        r = size
        m = Image.new("L", (r, r), 0)
        ImageDraw.Draw(m).ellipse([(0, 0), (r - 1, r - 1)], fill=255)
        rnd = Image.new("RGBA", (r, r), (0, 0, 0, 0))
        rnd.paste(icon.resize((r, r), Image.LANCZOS), (0, 0), m)
        rnd.save(d / "ic_launcher_round.png", "PNG")
    print("[android] mipmap 五档方形 + 圆形图标已生成")

    # 2) 自适应图标：前景层为满幅图案（无自带宽角），缩到安全区居中
    full = load_fullbleed()
    for dpi, canvas in ADAPTIVE.items():
        d = AND_RES / f"mipmap-{dpi}"
        d.mkdir(parents=True, exist_ok=True)
        inner = int(canvas * SAFE_RATIO)
        fg = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
        scaled = full.resize((inner, inner), Image.LANCZOS)
        off = (canvas - inner) // 2
        fg.paste(scaled, (off, off), scaled)
        fg.save(d / "ic_launcher_foreground.png", "PNG")

        # 背景层：纯品牌蓝 #0251FD
        bg = Image.new("RGBA", (canvas, canvas), (2, 81, 253, 255))
        bg.save(d / "ic_launcher_background.png", "PNG")
    print("[android] mipmap 五档自适应前景/背景层已生成")

    # 3) 自适应图标描述文件（API 26+）
    anydpi = AND_RES / "mipmap-anydpi-v26"
    anydpi.mkdir(parents=True, exist_ok=True)
    for fname in ("ic_launcher.xml", "ic_launcher_round.xml"):
        (anydpi / fname).write_text(
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
            '    <background android:drawable="@mipmap/ic_launcher_background" />\n'
            '    <foreground android:drawable="@mipmap/ic_launcher_foreground" />\n'
            "</adaptive-icon>\n",
            encoding="utf-8",
        )
    print("[android] mipmap-anydpi-v26 自适应图标 XML 已生成")


def main() -> None:
    icon = load_cutout()
    # 预览一张抠图结果，便于人工核对
    prev = ROOT / "jev-xianhui-windows" / "assets" / "_preview_cutout.png"
    prev.parent.mkdir(parents=True, exist_ok=True)
    icon.resize((512, 512), Image.LANCZOS).save(prev, "PNG")
    print(f"[preview] {prev}")

    save_windows_ico(icon)
    save_windows_png(icon)
    save_android(icon)
    print("\nOK 全部图标已生成")


if __name__ == "__main__":
    main()
