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


def write_minimal_layout():
    """Replace activity_main.xml with a minimal one-button layout.

    Keeps all IDs referenced by MainActivity.kt but hides everything
    except the toolbar (needed for menu/drawer toggle) and the centered FAB.
    The subscription update is accessible via toolbar overflow menu or drawer.
    """
    path = "app/src/main/res/layout/activity_main.xml"
    if not os.path.exists(path):
        print(f"  ⚠ {path} not found, skipping layout replacement")
        return

    minimal_layout = '''\
<?xml version="1.0" encoding="utf-8"?>
<androidx.drawerlayout.widget.DrawerLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:id="@+id/drawer_layout"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:fitsSystemWindows="true"
        android:orientation="vertical">

        <!-- Toolbar stays: needed for hamburger icon + overflow menu (sub_update) -->
        <com.google.android.material.appbar.AppBarLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content">

            <androidx.appcompat.widget.Toolbar
                android:id="@+id/toolbar"
                android:layout_width="match_parent"
                android:layout_height="?attr/actionBarSize" />

        </com.google.android.material.appbar.AppBarLayout>

        <!-- Full-screen area for centered FAB + hidden IDs kept for code compatibility -->
        <androidx.coordinatorlayout.widget.CoordinatorLayout
            android:id="@+id/main_content"
            android:layout_width="match_parent"
            android:layout_height="match_parent">

            <!-- Hidden views: IDs required by MainActivity but not shown to user -->
            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="vertical"
                android:visibility="gone">

                <com.google.android.material.progressindicator.LinearProgressIndicator
                    android:id="@+id/progress_bar"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:indeterminate="true"
                    android:visibility="invisible"
                    app:indicatorColor="@color/color_fab_active" />

                <com.google.android.material.tabs.TabLayout
                    android:id="@+id/tab_group"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    app:tabIndicatorFullWidth="false"
                    app:tabMode="scrollable"
                    app:tabTextAppearance="@style/TabLayoutTextStyle" />

                <androidx.viewpager2.widget.ViewPager2
                    android:id="@+id/view_pager"
                    android:layout_width="match_parent"
                    android:layout_height="200dp"
                    android:nextFocusRight="@+id/fab"
                    android:scrollbars="vertical" />

                <LinearLayout
                    android:id="@+id/layout_test"
                    android:layout_width="match_parent"
                    android:layout_height="@dimen/view_height_dp64"
                    android:clickable="true"
                    android:contentDescription="@string/connection_test_pending"
                    android:focusable="true"
                    android:nextFocusLeft="@+id/view_pager"
                    android:nextFocusRight="@+id/fab"
                    android:orientation="vertical">

                    <View
                        android:layout_width="wrap_content"
                        android:layout_height="1dp"
                        android:background="@color/divider_color_light" />

                    <TextView
                        android:id="@+id/tv_test_state"
                        android:layout_width="wrap_content"
                        android:layout_height="match_parent"
                        android:gravity="start|center"
                        android:maxLines="2"
                        android:minLines="1"
                        android:paddingStart="@dimen/padding_spacing_dp16"
                        android:text="@string/connection_test_pending"
                        android:textAppearance="@style/TextAppearance.AppCompat.Small" />

                </LinearLayout>
            </LinearLayout>

            <!-- Single centered connect button -->
            <FrameLayout
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                android:layout_gravity="center">

                <com.google.android.material.floatingactionbutton.FloatingActionButton
                    android:id="@+id/fab"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:layout_gravity="center"
                    android:clickable="true"
                    android:contentDescription="@string/tasker_start_service"
                    android:focusable="true"
                    android:nextFocusLeft="@+id/layout_test"
                    android:src="@drawable/ic_play_24dp"
                    app:tint="@color/colorWhite"
                    app:useCompatPadding="true" />

            </FrameLayout>

        </androidx.coordinatorlayout.widget.CoordinatorLayout>
    </LinearLayout>

    <!-- Navigation drawer: settings + subscription access via swipe from left -->
    <com.google.android.material.navigation.NavigationView
        android:id="@+id/nav_view"
        android:layout_width="wrap_content"
        android:layout_height="match_parent"
        android:layout_gravity="start"
        app:headerLayout="@layout/nav_header"
        app:menu="@menu/menu_drawer">

    </com.google.android.material.navigation.NavigationView>

</androidx.drawerlayout.widget.DrawerLayout>
'''

    with open(path, "w", encoding="utf-8") as f:
        f.write(minimal_layout)
    print("  ✓ activity_main.xml replaced with minimal one-button layout")


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
    write_minimal_layout()
    patch_menu_sub_update()
    resize_and_copy_icon(args.icon_src)

    print(f"\n✅ Customization complete → {args.app_name}")


if __name__ == "__main__":
    main()
