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

        // OOB desync from user's working ByeByeDPI v1.7.2 settings.
        // -o 1            OOB byte at position 1 of ClientHello (confuses SNI-based DPI)
        // -t 2            fake packet TTL=2: dies at ISP's DPI equipment (1-2 hops away)
        //                 but never reaches DNS servers (1.1.1.1 / 8.8.8.8 = 5+ hops) →
        //                 DNS queries are not corrupted despite UDP fake being enabled
        // -n www.iana.org fake SNI in the accompanying fake TLS record
        // -a 1            1 UDP fake packet for QUIC (YouTube HTTP/3)
        // argv[0] is the program name — getopt skips it (curr_optind=1 in parse_args).
        private val DEFAULT_ARGS = arrayOf(
            "byedpi", "-p", PORT.toString(),
            "-o", "1",
            "-t", "2",
            "-n", "www.iana.org",
            "-a", "1",
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
