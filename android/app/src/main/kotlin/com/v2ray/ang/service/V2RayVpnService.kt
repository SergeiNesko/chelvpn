package com.v2ray.ang.service

/**
 * Stub class at the v2rayNG package path.
 * hev-socks5-tunnel's JNI bridge registers hevStart/hevStop natives
 * against "com/v2ray/ang/service/V2RayVpnService" at JNI_OnLoad.
 * Declaring this class here makes those registrations land correctly.
 */
class V2RayVpnService {
    external fun hevStart(config: String, fd: Int): Int
    external fun hevStop()
}
