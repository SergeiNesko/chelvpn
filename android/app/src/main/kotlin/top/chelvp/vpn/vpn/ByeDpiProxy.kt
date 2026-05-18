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

        // Default bypass strategy for Russian DPI:
        //   -s 1  split ClientHello at byte 1 (send 1 byte, then rest)
        //   -f 4  fake packet with TTL=4 (reaches DPI, not the server)
        // Effective against SNI-based HTTP/HTTPS blocking.
        // argv[0] is the program name — getopt skips it (curr_optind=1 in parse_args).
        // Without it, "-p" lands in argv[0] and is never parsed → byedpi defaults to port 1080.
        private val DEFAULT_ARGS = arrayOf("byedpi", "-p", PORT.toString(), "-s", "1", "-f", "4")

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
