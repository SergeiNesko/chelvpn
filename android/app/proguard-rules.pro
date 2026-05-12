# libv2ray — keep all classes accessed via reflection
-keep class libv2ray.** { *; }
-dontwarn libv2ray.**

# hev-socks5-tunnel JNI bridge (v2rayNG class path — JNI_OnLoad registers natives on this class)
-keep class com.v2ray.ang.service.V2RayVpnService { *; }

# Keep our VPN service intact
-keep class top.chelvp.vpn.vpn.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# CameraX / MLKit
-dontwarn com.google.mlkit.**
-dontwarn androidx.camera.**

# Kotlin coroutines
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}
