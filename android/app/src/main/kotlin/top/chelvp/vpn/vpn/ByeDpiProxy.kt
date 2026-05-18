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

        // OOB desync — exact settings from user's working ByeByeDPI v1.7.2 config:
        //   byedpi_desync_method = "oob", split_position = 1
        //   fake_ttl = 8, fake_sni = "www.iana.org", desync_udp = true, udp_fake_count = 1
        // -o 1              OOB byte at position 1 of ClientHello (bypasses SNI-based DPI)
        // -t 8              TTL for fake packets = 8 (dies before real server)
        // -n www.iana.org   fake SNI in accompanying fake TLS record
        // -a 1              1 UDP fake packet (for QUIC/YouTube)
        // DNS is handled by hev's built-in resolver (26.26.26.2 → 8.8.8.8) and never
        // reaches byedpi, so UDP fake does not corrupt DNS queries.
        // argv[0] is the program name — getopt skips it (curr_optind=1 in parse_args).
        private val DEFAULT_ARGS = arrayOf(
            "byedpi", "-p", PORT.toString(),
            "-o", "1",
            "-t", "8",
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
