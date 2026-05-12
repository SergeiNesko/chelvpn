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

ORIG_PACKAGE = "com.v2ray.ang"
ORIG_SCHEME = "v2rayng"


def patch_strings(app_name: str):
    path = "app/src/main/res/values/strings.xml"
    print(f"[strings.xml] app_name → {app_name}")
    with open(path, "r", encoding="utf-8") as f:
        raw = f.read()
    patched = re.sub(
        r'(<string name="app_name"[^>]*>)[^<]*(</string>)',
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
        patched = re.sub(
            rf'(android:scheme="{ORIG_SCHEME}")',
            rf'\1 />\n                    <data android:scheme="{scheme}"',
            content,
            count=1,
        )

    with open(path, "w", encoding="utf-8") as f:
        f.write(patched)
    print(f"  ✓ AndroidManifest.xml patched")


def patch_ui():
    """Simplify UI: hide server list and tabs, center and enlarge the connect FAB."""
    path = "app/src/main/res/layout/activity_main.xml"
    if not os.path.exists(path):
        print(f"  ⚠ {path} not found, skipping UI patch")
        return
    with open(path, "r", encoding="utf-8") as f:
        content = f.read()
    original = content

    # 1. Hide TabLayout (server group tabs)
    if 'android:id="@+id/tab_group"' in content:
        content = re.sub(
            r'(android:id="@\+id/tab_group")',
            r'\1\n        android:visibility="gone"',
            content, count=1,
        )
        print("  ✓ tab_group hidden")
    else:
        print("  ⚠ tab_group not found in layout")

    # 2. Hide ViewPager2 (server list)
    if 'android:id="@+id/view_pager"' in content:
        content = re.sub(
            r'(android:id="@\+id/view_pager")',
            r'\1\n        android:visibility="gone"',
            content, count=1,
        )
        print("  ✓ view_pager hidden")
    else:
        print("  ⚠ view_pager not found in layout")

    # 3. Center FAB container FrameLayout: remove bottom|end + margins
    # Flexible whitespace matching between attributes
    new_content = re.sub(
        r'android:layout_gravity="bottom\|end"\s*\n\s*android:layout_marginBottom="-16dp"\s*\n\s*android:layout_marginEnd="[^"]*">',
        'android:layout_gravity="center">',
        content, count=1,
    )
    if new_content != content:
        content = new_content
        print("  ✓ FAB container gravity → center")
    else:
        # Fallback: remove margins separately
        new_content = re.sub(
            r'android:layout_gravity="bottom\|end"(\s*\n\s*android:layout_marginBottom="[^"]*")?(\s*\n\s*android:layout_marginEnd="[^"]*")?>',
            'android:layout_gravity="center">',
            content, count=1,
        )
        if new_content != content:
            content = new_content
            print("  ✓ FAB container gravity → center (fallback)")
        else:
            print("  ⚠ FAB container bottom|end not matched")

    # 4. Make FrameLayout match_parent so center gravity works
    new_content = re.sub(
        r'(<FrameLayout\s*\n\s*)android:layout_width="wrap_content"(\s*\n\s*)android:layout_height="wrap_content"(\s*\n\s*)android:layout_gravity="center"',
        r'\1android:layout_width="match_parent"\2android:layout_height="match_parent"\3android:layout_gravity="center"',
        content, count=1,
    )
    if new_content != content:
        content = new_content
        print("  ✓ FrameLayout → match_parent for centering")
    else:
        print("  ⚠ FrameLayout wrap_content + center not matched (may already be ok)")

    # 5. Center FAB itself: remove bottom|end + marginBottom from the FAB element
    new_content = re.sub(
        r'android:layout_gravity="bottom\|end"\s*\n\s*android:layout_marginBottom="[^"]*"',
        'android:layout_gravity="center"',
        content, count=1,
    )
    if new_content != content:
        content = new_content
        print("  ✓ FAB gravity → center")
    else:
        print("  ⚠ FAB bottom|end + marginBottom not matched")

    # 6. Enlarge FAB and remove anchor gravity
    new_content = re.sub(
        r'(app:useCompatPadding="true")\s*\n\s*app:layout_anchorGravity="[^"]*"',
        r'app:fabSize="large"\n                        \1',
        content, count=1,
    )
    if new_content != content:
        content = new_content
        print("  ✓ FAB enlarged (fabSize=large), anchorGravity removed")
    else:
        print("  ⚠ useCompatPadding + anchorGravity not matched, trying to just add fabSize")
        new_content = re.sub(
            r'(app:useCompatPadding="true")',
            r'app:fabSize="large"\n                        \1',
            content, count=1,
        )
        if new_content != content:
            content = new_content
            print("  ✓ FAB enlarged (fabSize=large added)")

    if content != original:
        with open(path, "w", encoding="utf-8") as f:
            f.write(content)
        print("  ✓ activity_main.xml saved")
    else:
        print("  ⚠ UI patch: no changes applied to activity_main.xml")


def patch_menu_sub_update():
    """Make subscription update button always visible in toolbar (not hidden in overflow)."""
    path = "app/src/main/res/menu/menu_main.xml"
    if not os.path.exists(path):
        print(f"  ⚠ {path} not found, skipping menu patch")
        return
    with open(path, "r", encoding="utf-8") as f:
        content = f.read()

    # Exact match for v2rayNG 2.1.7 menu_main.xml
    patched = content.replace(
        '        android:id="@+id/sub_update"\n'
        '        android:title="@string/title_sub_update"\n'
        '        app:showAsAction="never" />',
        '        android:id="@+id/sub_update"\n'
        '        android:icon="@drawable/ic_subscriptions_24dp"\n'
        '        android:title="@string/title_sub_update"\n'
        '        app:showAsAction="always" />',
    )
    if patched == content:
        # Regex fallback
        patched = re.sub(
            r'(android:id="@\+id/sub_update"[\s\S]*?)app:showAsAction="never"(\s*/>)',
            r'\1android:icon="@drawable/ic_subscriptions_24dp"\n        app:showAsAction="always"\2',
            content,
            count=1,
        )
    if patched == content:
        print("  ⚠ sub_update menu item not found")
        return
    with open(path, "w", encoding="utf-8") as f:
        f.write(patched)
    print("  ✓ sub_update button: always visible in toolbar")


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
    patch_ui()
    patch_menu_sub_update()
    resize_and_copy_icon(args.icon_src)

    print(f"\n✅ Customization complete → {args.app_name}")


if __name__ == "__main__":
    main()
