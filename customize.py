#!/usr/bin/env python3
"""Patch v2rayNG source code for ChelVPN rebranding.

Usage:
    python3 customize.py \
        --app-name ChelVPN \
        --app-id top.chelvp.vpn \
        --scheme chelvpn \
        --icon-src path/to/icon.png
"""

import argparse
import os
import re
import shutil

ORIG_PACKAGE = "com.v2ray.ang"
ORIG_SCHEME = "v2rayng"


def patch_strings(app_name: str):
    path = "app/src/main/res/values/strings.xml"
    print(f"[strings.xml] app_name → {app_name}")
    with open(path, "r", encoding="utf-8") as f:
        raw = f.read()
    patched = re.sub(
        r'(<string name="app_name">)[^<]*(</string>)',
        rf'\g<1>{app_name}\g<2>',
        raw,
    )
    with open(path, "w", encoding="utf-8") as f:
        f.write(patched)
    print(f"  ✓ strings.xml patched")


def patch_build_gradle(app_id: str):
    for fname in ["app/build.gradle.kts", "app/build.gradle"]:
        if not os.path.exists(fname):
            continue
        print(f"[{fname}] applicationId → {app_id}")
        with open(fname, "r", encoding="utf-8") as f:
            content = f.read()
        # Only replace applicationId line, leave namespace untouched
        patched = re.sub(
            r'(applicationId\s*[=:]\s*)["\']com\.v2ray\.ang["\']',
            rf'\g<1>"{app_id}"',
            content,
        )
        if patched == content:
            print(f"  ⚠ applicationId not found in {fname}")
        else:
            with open(fname, "w", encoding="utf-8") as f:
                f.write(patched)
            print(f"  ✓ {fname} patched")


def patch_manifest(scheme: str):
    path = "app/src/main/AndroidManifest.xml"
    print(f"[AndroidManifest.xml] add scheme {scheme}://")
    with open(path, "r", encoding="utf-8") as f:
        content = f.read()

    if f'android:scheme="{scheme}"' in content:
        print(f"  ⚠ scheme {scheme}:// already present, skipping")
        return

    patched = content.replace(
        f'<data android:scheme="{ORIG_SCHEME}" />',
        f'<data android:scheme="{ORIG_SCHEME}" />\n'
        f'                    <data android:scheme="{scheme}" />',
        1,
    )
    if patched == content:
        patched = content.replace(
            f'android:scheme="{ORIG_SCHEME}"',
            f'android:scheme="{ORIG_SCHEME}"\n'
            f'            /><data android:scheme="{scheme}"',
            1,
        )

    with open(path, "w", encoding="utf-8") as f:
        f.write(patched)
    print(f"  ✓ AndroidManifest.xml patched")


def patch_ui(app_name: str):
    """Simplify UI: one connect button, hide clutter."""
    # Hide FAB (add server button) in main layout
    for layout_path in [
        "app/src/main/res/layout/activity_main.xml",
        "app/src/main/res/layout/fragment_main.xml",
    ]:
        if not os.path.exists(layout_path):
            continue
        with open(layout_path, "r", encoding="utf-8") as f:
            content = f.read()
        # Hide FloatingActionButton
        patched = re.sub(
            r'(<com\.google\.android\.material\.floatingactionbutton\.FloatingActionButton\b[^>]*?)(?<!android:visibility="gone")(\s*/>|\s*>)',
            lambda m: m.group(0).replace(m.group(2), f'\n        android:visibility="gone"{m.group(2)}')
            if 'visibility' not in m.group(0) else m.group(0),
            content,
        )
        if patched != content:
            with open(layout_path, "w", encoding="utf-8") as f:
                f.write(patched)
            print(f"  ✓ FAB hidden in {layout_path}")
        else:
            print(f"  ~ FAB not found or already hidden in {layout_path}")


def resize_and_copy_icon(icon_src: str):
    if not icon_src or not os.path.exists(icon_src):
        print(f"[icon] {icon_src!r} not found — keeping original icons")
        return

    try:
        from PIL import Image, ImageDraw
    except ImportError:
        print("[icon] Pillow not installed — keeping original icons")
        return

    print(f"[icon] Resizing {icon_src} for all densities")
    densities = {
        "mipmap-mdpi": 48,
        "mipmap-hdpi": 72,
        "mipmap-xhdpi": 96,
        "mipmap-xxhdpi": 144,
        "mipmap-xxxhdpi": 192,
    }
    img = Image.open(icon_src).convert("RGBA")
    for density, size in densities.items():
        dst_dir = f"app/src/main/res/{density}"
        os.makedirs(dst_dir, exist_ok=True)
        resized = img.resize((size, size), Image.LANCZOS)
        resized.save(f"{dst_dir}/ic_launcher.png")
        mask = Image.new("L", (size, size), 0)
        draw = ImageDraw.Draw(mask)
        draw.ellipse((0, 0, size, size), fill=255)
        round_icon = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        round_icon.paste(resized, mask=mask)
        round_icon.save(f"{dst_dir}/ic_launcher_round.png")
    print(f"  ✓ Icons resized for {len(densities)} densities")


def main():
    parser = argparse.ArgumentParser(description="Customize v2rayNG for rebranding")
    parser.add_argument("--app-name", default="ChelVPN")
    parser.add_argument("--app-id", default="top.chelvp.vpn")
    parser.add_argument("--scheme", default="chelvpn")
    parser.add_argument("--icon-src", default="")
    args = parser.parse_args()

    print(f"\n{'='*50}")
    print(f"  Branding: {args.app_name}  |  ID: {args.app_id}  |  Scheme: {args.scheme}://")
    print(f"{'='*50}\n")

    patch_strings(args.app_name)
    patch_build_gradle(args.app_id)
    patch_manifest(args.scheme)
    patch_ui(args.app_name)
    resize_and_copy_icon(args.icon_src)

    print(f"\n✅ Customization complete → {args.app_name}")


if __name__ == "__main__":
    main()
