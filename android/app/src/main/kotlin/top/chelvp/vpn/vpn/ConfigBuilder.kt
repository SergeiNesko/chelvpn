package top.chelvp.vpn.vpn

import org.json.JSONArray
import org.json.JSONObject
import top.chelvp.vpn.subscription.Protocol
import top.chelvp.vpn.subscription.ServerConfig

/**
 * Строит конфиг Xray-core в JSON-формате.
 * Входящий трафик: SOCKS5 на 127.0.0.1:10808
 * Исходящий: прокси на сервер + direct для локальных адресов
 */
object ConfigBuilder {

    const val SOCKS_PORT = 10808
    const val HTTP_PORT  = 10809

    fun build(server: ServerConfig): String {
        val root = JSONObject()

        // Log
        root.put("log", JSONObject().apply {
            put("loglevel", "warning")
        })

        // DNS
        root.put("dns", JSONObject().apply {
            put("servers", JSONArray().apply {
                put("1.1.1.1")
                put("8.8.8.8")
            })
        })

        // Inbounds
        root.put("inbounds", JSONArray().apply {
            // SOCKS5 для hev-socks5-tunnel
            put(JSONObject().apply {
                put("tag", "socks")
                put("protocol", "socks")
                put("port", SOCKS_PORT)
                put("listen", "127.0.0.1")
                put("settings", JSONObject().apply {
                    put("auth", "noauth")
                    put("udp", true)
                })
            })
            // HTTP proxy
            put(JSONObject().apply {
                put("tag", "http")
                put("protocol", "http")
                put("port", HTTP_PORT)
                put("listen", "127.0.0.1")
            })
        })

        // Outbounds
        root.put("outbounds", JSONArray().apply {
            put(buildOutbound(server))
            put(JSONObject().apply {
                put("tag", "direct")
                put("protocol", "freedom")
                put("settings", JSONObject())
            })
            put(JSONObject().apply {
                put("tag", "block")
                put("protocol", "blackhole")
                put("settings", JSONObject())
            })
        })

        // Routing — всё через прокси кроме локальных адресов
        root.put("routing", JSONObject().apply {
            put("domainStrategy", "IPIfNonMatch")
            put("rules", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "field")
                    put("outboundTag", "direct")
                    put("ip", JSONArray().apply {
                        put("geoip:private")
                    })
                })
            })
        })

        return root.toString(2)
    }

    private fun buildOutbound(s: ServerConfig): JSONObject {
        val out = JSONObject()
        out.put("tag", "proxy")

        when (s.protocol) {
            Protocol.VLESS -> {
                out.put("protocol", "vless")
                out.put("settings", JSONObject().apply {
                    put("vnext", JSONArray().apply {
                        put(JSONObject().apply {
                            put("address", s.host)
                            put("port", s.port)
                            put("users", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("id", s.uuid)
                                    put("encryption", "none")
                                    if (s.flow.isNotEmpty()) put("flow", s.flow)
                                })
                            })
                        })
                    })
                })
            }
            Protocol.VMESS -> {
                out.put("protocol", "vmess")
                out.put("settings", JSONObject().apply {
                    put("vnext", JSONArray().apply {
                        put(JSONObject().apply {
                            put("address", s.host)
                            put("port", s.port)
                            put("users", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("id", s.uuid)
                                    put("alterId", s.alterId)
                                    put("security", s.vmessMethod)
                                })
                            })
                        })
                    })
                })
            }
            Protocol.TROJAN -> {
                out.put("protocol", "trojan")
                out.put("settings", JSONObject().apply {
                    put("servers", JSONArray().apply {
                        put(JSONObject().apply {
                            put("address", s.host)
                            put("port", s.port)
                            put("password", s.password)
                        })
                    })
                })
            }
            Protocol.SS -> {
                out.put("protocol", "shadowsocks")
                out.put("settings", JSONObject().apply {
                    put("servers", JSONArray().apply {
                        put(JSONObject().apply {
                            put("address", s.host)
                            put("port", s.port)
                            put("method", s.ssMethod)
                            put("password", s.ssPassword)
                        })
                    })
                })
            }
        }

        out.put("streamSettings", buildStreamSettings(s))
        return out
    }

    private fun buildStreamSettings(s: ServerConfig): JSONObject {
        val ss = JSONObject()
        ss.put("network", s.network)
        ss.put("security", s.security)

        when (s.security) {
            "reality" -> ss.put("realitySettings", JSONObject().apply {
                put("serverName", s.sni.ifEmpty { s.host })
                put("fingerprint", s.fingerprint.ifEmpty { "chrome" })
                put("show", false)
                put("publicKey", s.publicKey)
                put("shortId", s.shortId)
                put("spiderX", "")
            })
            "tls" -> ss.put("tlsSettings", JSONObject().apply {
                put("serverName", s.sni.ifEmpty { s.host })
                put("allowInsecure", s.allowInsecure)
                put("fingerprint", s.fingerprint.ifEmpty { "chrome" })
            })
        }

        when (s.network) {
            "ws" -> ss.put("wsSettings", JSONObject().apply {
                put("path", s.wsPath.ifEmpty { "/" })
                put("headers", JSONObject().apply {
                    if (s.wsHost.isNotEmpty()) put("Host", s.wsHost)
                })
            })
            "grpc" -> ss.put("grpcSettings", JSONObject().apply {
                put("serviceName", s.grpcServiceName)
                put("multiMode", false)
            })
        }

        return ss
    }
}
