package com.v2ray.ang.service

// Compatibility stub required for libhevtun.so (hev-socks5-tunnel, compiled by v2rayNG).
// JNI_OnLoad in libhevtun.so does FindClass("com/v2ray/ang/service/TProxyService")
// and RegisterNatives against these three methods. If the class is not found, JNI_OnLoad
// returns JNI_ERR and the JVM aborts (SIGABRT).
//
// TProxyStartService runs hev in a background pthread; it returns after starting.
// The TUN fd is passed directly — hev sets it non-blocking and reads raw IP packets
// from it, forwarding each TCP/UDP connection through the SOCKS5 proxy.
class TProxyService {
    companion object {
        val hevAvailable: Boolean = try {
            System.loadLibrary("hevtun")
            true
        } catch (_: Throwable) { false }
    }

    // configPath: path to hev YAML config file (socks5 address/port, misc timeouts)
    // fd: TUN file descriptor — hev reads raw IP packets from it
    external fun TProxyStartService(configPath: String, fd: Int)
    external fun TProxyStopService()
    external fun TProxyGetStats(): LongArray
}
