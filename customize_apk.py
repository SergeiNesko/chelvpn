#!/usr/bin/env python3
"""Patch decoded v2rayNG APK resources for ChelVPN rebranding.

Usage:
    python3 customize_apk.py \
        --decoded-dir decoded \
        --app-name ChelVPN \
        --scheme chelvpn \
        --icon-src path/to/icon.png
"""

import argparse
import os
import re


def patch_strings(decoded_dir: str, app_name: str):
    path = os.path.join(decoded_dir, "res/values/strings.xml")
    if not os.path.exists(path):
        print(f"  ⚠ {path} not found")
        return
    with open(path, "r", encoding="utf-8") as f:
        content = f.read()
    patched = re.sub(
        r'(<string name="app_name">)[^<]*(</string>)',
        rf'\g<1>{app_name}\g<2>',
        content,
    )
    with open(path, "w", encoding="utf-8") as f:
        f.write(patched)
    print(f"  ✓ app_name → {app_name}")


def patch_manifest(decoded_dir: str, scheme: str):
    path = os.path.join(decoded_dir, "AndroidManifest.xml")
    if not os.path.exists(path):
        print(f"  ⚠ {path} not found")
        return
    with open(path, "r", encoding="utf-8") as f:
        content = f.read()
    if f'android:scheme="{scheme}"' in content:
        print(f"  ⚠ scheme {scheme}:// already present, skipping")
        return
    patched = content.replace(
        'android:scheme="v2rayng"',
        f'android:scheme="v2rayng"/>\n                    <data android:scheme="{scheme}"',
        1,
    )
    if patched == content:
        print(f"  ⚠ v2rayng:// not found in manifest — scheme not added")
    else:
        with open(path, "w", encoding="utf-8") as f:
            f.write(patched)
        print(f"  ✓ scheme {scheme}:// added to manifest")


def copy_icons(decoded_dir: str, icon_src: str):
    if not icon_src or not os.path.exists(icon_src):
        print(f"  ⚠ icon not found: {icon_src!r} — keeping original")
        return
    try:
        from PIL import Image, ImageDraw
    except ImportError:
        print("  ⚠ Pillow not installed, skipping icons")
        return
    densities = {
        "mipmap-mdpi": 48,
        "mipmap-hdpi": 72,
        "mipmap-xhdpi": 96,
        "mipmap-xxhdpi": 144,
        "mipmap-xxxhdpi": 192,
    }
    img = Image.open(icon_src).convert("RGBA")
    placed = 0
    for density, size in densities.items():
        dst_dir = os.path.join(decoded_dir, f"res/{density}")
        if not os.path.exists(dst_dir):
            continue
        resized = img.resize((size, size), Image.LANCZOS)
        resized.save(os.path.join(dst_dir, "ic_launcher.png"))
        mask = Image.new("L", (size, size), 0)
        draw = ImageDraw.Draw(mask)
        draw.ellipse((0, 0, size, size), fill=255)
        round_icon = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        round_icon.paste(resized, mask=mask)
        round_icon.save(os.path.join(dst_dir, "ic_launcher_round.png"))
        placed += 1
    print(f"  ✓ icons placed in {placed} density buckets")


def main():
    parser = argparse.ArgumentParser(description="Patch decoded v2rayNG for ChelVPN")
    parser.add_argument("--decoded-dir", required=True)
    parser.add_argument("--app-name", default="ChelVPN")
    parser.add_argument("--scheme", default="chelvpn")
    parser.add_argument("--icon-src", default="")
    args = parser.parse_args()

    print(f"\n{'='*50}")
    print(f"  Branding: {args.app_name}  |  Scheme: {args.scheme}://")
    print(f"{'='*50}\n")

    patch_strings(args.decoded_dir, args.app_name)
    patch_manifest(args.decoded_dir, args.scheme)
    copy_icons(args.decoded_dir, args.icon_src)

    print(f"\n✅ Customization complete → {args.app_name}")


if __name__ == "__main__":
    main()
