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

        // OOB desync matching user's working ByeByeDPI v1.7.2 settings.
        // -o 1            OOB byte at position 1 of ClientHello (confuses SNI-based DPI)
        // -t 8            fake packet TTL=8: reaches ISP DPI (3-5 hops) but not the real server.
        //                 Matches user's confirmed-working ByeByeDPI config (fake_ttl=8).
        // -n www.iana.org fake SNI in the accompanying fake TLS record
        // UDP fakes (-a) are intentionally omitted: byedpi receives ALL UDP via hev including
        // DNS queries (port 53). Sending fake UDP to DNS servers causes FORMERR responses and
        // breaks DNS. TCP OOB desync is sufficient for HTTPS (Instagram/YouTube HTTP/2).
        // argv[0] is the program name — getopt skips it (curr_optind=1 in parse_args).
        private val DEFAULT_ARGS = arrayOf(
            "byedpi", "-p", PORT.toString(),
            "-o", "1",
            "-t", "8",
            "-n", "www.iana.org",
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
