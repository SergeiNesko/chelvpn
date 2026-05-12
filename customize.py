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
import sys

ORIG_PACKAGE = "com.v2ray.ang"
ORIG_SCHEME = "v2rayng"


def replace_in_file(path: str, old: str, new: str, expected: int = 1) -> bool:
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        content = f.read()
    count = content.count(old)
    if count == 0:
        print(f"  ⚠ [{path}] pattern not found: {repr(old[:60])}")
        return False
    if expected and count != expected:
        print(f"  ⚠ [{path}] found {count} occurrences (expected {expected}), patching all")
    patched = content.replace(old, new)
    with open(path, "w", encoding="utf-8") as f:
        f.write(patched)
    return True


def patch_strings(app_name: str):
    path = "app/src/main/res/values/strings.xml"
    print(f"[strings.xml] app_name → {app_name}")
    replace_in_file(path, ">v2rayNG<", f">{app_name}<", expected=0)
    replace_in_file(path, ">v2rayng<", f">{app_name}<", expected=0)
    # Fallback: any remaining app_name value
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
        replace_in_file(fname, f'"{ORIG_PACKAGE}"', f'"{app_id}"', expected=0)
        replace_in_file(fname, f"'{ORIG_PACKAGE}'", f"'{app_id}'", expected=0)
        print(f"  ✓ {fname} patched")


def patch_manifest(scheme: str):
    path = "app/src/main/AndroidManifest.xml"
    print(f"[AndroidManifest.xml] add scheme {scheme}://")
    with open(path, "r", encoding="utf-8") as f:
        content = f.read()

    # Find the existing v2rayng:// intent-filter block and duplicate with new scheme
    old_scheme_tag = f'android:scheme="{ORIG_SCHEME}"'
    new_scheme_tag = f'android:scheme="{scheme}"'

    if new_scheme_tag in content:
        print(f"  ⚠ scheme {scheme}:// already present, skipping")
        return

    # Insert new data tag alongside existing scheme
    patched = content.replace(
        f'<data android:scheme="{ORIG_SCHEME}" />',
        f'<data android:scheme="{ORIG_SCHEME}" />\n'
        f'                    <data android:scheme="{scheme}" />',
        1,
    )
    if patched == content:
        # Try alternate format without self-closing slash
        patched = content.replace(
            f'android:scheme="{ORIG_SCHEME}"',
            f'android:scheme="{ORIG_SCHEME}"\n'
            f'            /><data android:scheme="{scheme}"',
            1,
        )

    with open(path, "w", encoding="utf-8") as f:
        f.write(patched)
    print(f"  ✓ AndroidManifest.xml patched")


def patch_scheme_handler(scheme: str):
    """Add 'chelvpn' to the URI scheme check in Kotlin source."""
    kt_files = []
    for root, _, files in os.walk("app/src/main/kotlin"):
        for f in files:
            if f.endswith(".kt"):
                kt_files.append(os.path.join(root, f))

    patched_any = False
    for path in kt_files:
        with open(path, "r", encoding="utf-8", errors="replace") as f:
            content = f.read()
        # Look for scheme comparison like: == "v2rayng" or startsWith("v2rayng")
        if f'"{ORIG_SCHEME}"' not in content:
            continue
        # Patch equality check: uri.scheme == "v2rayng" → uri.scheme in listOf(...)
        patched = re.sub(
            rf'(\.scheme\s*==\s*)"{re.escape(ORIG_SCHEME)}"',
            rf'\g<1>"{ORIG_SCHEME}" || uri.scheme == "{scheme}"',
            content,
        )
        # Patch when-expression: "v2rayng" -> { → "v2rayng", "{scheme}" -> {
        patched = re.sub(
            rf'"{re.escape(ORIG_SCHEME)}"\s*->',
            rf'"{ORIG_SCHEME}", "{scheme}" ->',
            patched,
        )
        if patched != content:
            with open(path, "w", encoding="utf-8") as f:
                f.write(patched)
            rel = os.path.relpath(path)
            print(f"  ✓ scheme handler patched in {rel}")
            patched_any = True

    if not patched_any:
        print(f"  ⚠ scheme handler not found in Kotlin sources — {scheme}:// intent will open app but URL parsing may fall back to v2rayng:// handler")
    else:
        print(f"[Kotlin] scheme {scheme}:// handler added")


def resize_and_copy_icon(icon_src: str):
    if not icon_src or not os.path.exists(icon_src):
        print(f"[icon] {icon_src!r} not found — keeping original icons")
        return

    try:
        from PIL import Image
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
        # Round icon: circle crop
        from PIL import ImageDraw
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
    patch_scheme_handler(args.scheme)
    resize_and_copy_icon(args.icon_src)

    print(f"\n✅ Customization complete → {args.app_name}")


if __name__ == "__main__":
    main()
