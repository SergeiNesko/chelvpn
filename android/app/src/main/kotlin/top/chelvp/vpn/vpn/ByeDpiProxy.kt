package top.chelvp.vpn.vpn

import android.util.Log

// SOCKS5 DPI-bypass proxy backed by byedpi (hufrea/byedpi).
// jniStartProxy() BLOCKS until the proxy exits — call it from a background thread.
// jniStopProxy() sends shutdown() to the listening socket, which unblocks jniStartProxy().
class ByeDpiProxy {

    companion object {
        private const val TAG = "ByeDpiProxy"

        // Port used for the local SOCKS5 DPI-bypass proxy.
        // Must not conflict with xray's ports (10808/10809).
        const val PORT = 10810

        // OOB desync args matching ByeByeDPI v1.7.5 UI defaults for OOB method.
        // -i 127.0.0.1  explicit loopback bind (avoids 0.0.0.0 binding ambiguity in VPN)
        // -Kt,h         apply desync to HTTPS (TLS) and HTTP traffic only
        // -o 1          OOB byte at position 1 of ClientHello (confuses SNI-based DPI)
        // -ea           OOB char 'a' (ASCII 97) — same as ByeByeDPI default oob_char
        // -An           accept group terminator — required by ciadpi arg parser
        // UDP fakes (-Ku -a1) omitted: byedpi receives ALL UDP via hev including DNS
        // queries (port 53) → fake UDP breaks DNS → no internet. TCP OOB only.
        // -t/-n omitted: in newer byedpi (ciadpi) these only affect DESYNC_FAKE, not OOB.
        private val DEFAULT_ARGS = arrayOf(
            "ciadpi",
            "-i", "127.0.0.1",
            "-p", PORT.toString(),
            "-Kt,h",
            "-o", "1",
            "-ea",
            "-An",
        )

        val isAvailable: Boolean = try {
            System.loadLibrary("byedpi")
            true
        } catch (_: Throwable) { false }
    }

    fun startProxy(args: Array<String> = DEFAULT_ARGS): Int {
        Log.i(TAG, "startProxy args=${args.toList()}")
        return jniStartProxy(args)
    }

    fun stopProxy(): Int {
        Log.i(TAG, "stopProxy")
        return jniStopProxy()
    }

    private external fun jniStartProxy(args: Array<String>): Int
    private external fun jniStopProxy(): Int
    external fun jniForceClose(): Int
}
