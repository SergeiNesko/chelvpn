package top.chelvp.vpn.vpn

enum class VpnMode {
    /** All apps routed through the remote VLESS/VMess/Trojan/SS server. */
    FULL_VPN,

    /** Only Instagram, YouTube, Discord. Traffic goes through local byedpi
     *  DPI-bypass proxy — no remote server, direct connection with packet
     *  fragmentation to confuse Russian DPI. */
    DPI_BYPASS
}
