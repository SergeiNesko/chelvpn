package top.chelvp.vpn.subscription

import android.net.Uri

/** Поддерживаемые протоколы */
enum class Protocol { VLESS, VMESS, TROJAN, SS }

/**
 * Универсальная конфигурация сервера.
 * Задел на несколько серверов: список хранится в [SubscriptionManager],
 * активный сервер выбирается по индексу.
 */
data class ServerConfig(
    val protocol: Protocol,
    val name: String,
    val host: String,
    val port: Int,
    // VLESS / Trojan
    val uuid: String = "",
    val password: String = "",
    // Транспорт
    val network: String = "tcp",     // tcp | ws | grpc | quic
    val security: String = "none",   // none | tls | reality
    // Reality
    val publicKey: String = "",
    val shortId: String = "",
    val fingerprint: String = "chrome",
    val sni: String = "",
    // TLS
    val allowInsecure: Boolean = false,
    // WebSocket
    val wsPath: String = "/",
    val wsHost: String = "",
    // gRPC
    val grpcServiceName: String = "",
    // VMESS extra
    val alterId: Int = 0,
    val vmessMethod: String = "auto",
    // Flow (VLESS + XTLS)
    val flow: String = "",
    // Shadowsocks
    val ssMethod: String = "",
    val ssPassword: String = "",
) {
    val displayName: String get() = name.ifBlank { "$host:$port" }
}

// ────────────────────────────────────────────────
// Парсеры URI
// ────────────────────────────────────────────────

fun parseServerUri(raw: String): ServerConfig? = runCatching {
    val trimmed = raw.trim()
    if (trimmed.startsWith("{")) return@runCatching parseStoredJson(trimmed)
    val uri = Uri.parse(trimmed)
    when (uri.scheme?.lowercase()) {
        "vless"  -> parseVless(uri)
        "vmess"  -> parseVmess(raw)
        "trojan" -> parseTrojan(uri)
        "ss"     -> parseSs(uri)
        else     -> null
    }
}.getOrNull()

private fun parseStoredJson(json: String): ServerConfig? {
    val obj = org.json.JSONObject(json)
    if (obj.optString("_type") != "raw") return null
    return ServerConfig(
        protocol = Protocol.valueOf(obj.optString("proto", "VLESS")),
        name = obj.optString("name", ""),
        host = obj.optString("host", ""),
        port = obj.optInt("port", 443),
        uuid = obj.optString("uuid", ""),
        password = obj.optString("password", ""),
        network = obj.optString("network", "tcp"),
        security = obj.optString("security", "none"),
        publicKey = obj.optString("pbk", ""),
        shortId = obj.optString("sid", ""),
        fingerprint = obj.optString("fp", "chrome"),
        sni = obj.optString("sni", ""),
        flow = obj.optString("flow", ""),
        wsPath = obj.optString("wsPath", "/"),
        wsHost = obj.optString("wsHost", ""),
        grpcServiceName = obj.optString("grpc", ""),
        alterId = obj.optInt("alterId", 0),
        vmessMethod = obj.optString("vmessMethod", "auto"),
        ssMethod = obj.optString("ssMethod", ""),
        ssPassword = obj.optString("ssPassword", ""),
        allowInsecure = obj.optBoolean("allowInsecure", false),
    )
}

private fun parseVless(uri: Uri): ServerConfig {
    val name = Uri.decode(uri.fragment ?: "")
    val host = uri.host ?: ""
    val port = uri.port.takeIf { it > 0 } ?: 443
    val uuid = uri.userInfo ?: ""
    val network = uri.getQueryParameter("type") ?: "tcp"
    val security = uri.getQueryParameter("security") ?: "none"
    return ServerConfig(
        protocol = Protocol.VLESS,
        name = name,
        host = host,
        port = port,
        uuid = uuid,
        network = network,
        security = security,
        publicKey = uri.getQueryParameter("pbk") ?: "",
        shortId = uri.getQueryParameter("sid") ?: "",
        fingerprint = uri.getQueryParameter("fp") ?: "chrome",
        sni = uri.getQueryParameter("sni") ?: host,
        flow = uri.getQueryParameter("flow") ?: "",
        wsPath = uri.getQueryParameter("path") ?: "/",
        wsHost = uri.getQueryParameter("host") ?: "",
        grpcServiceName = uri.getQueryParameter("serviceName") ?: "",
    )
}

private fun parseVmess(raw: String): ServerConfig {
    val b64 = raw.removePrefix("vmess://")
    val json = String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT))
    val obj = org.json.JSONObject(json)
    val host = obj.optString("add", "")
    val port = obj.optString("port", "443").toIntOrNull() ?: 443
    val network = obj.optString("net", "tcp")
    val security = if (obj.optString("tls", "") == "tls") "tls" else "none"
    return ServerConfig(
        protocol = Protocol.VMESS,
        name = obj.optString("ps", ""),
        host = host,
        port = port,
        uuid = obj.optString("id", ""),
        alterId = obj.optString("aid", "0").toIntOrNull() ?: 0,
        vmessMethod = obj.optString("scy", "auto"),
        network = network,
        security = security,
        sni = obj.optString("sni", host),
        wsPath = obj.optString("path", "/"),
        wsHost = obj.optString("host", ""),
        grpcServiceName = obj.optString("path", ""),
    )
}

private fun parseTrojan(uri: Uri): ServerConfig {
    val host = uri.host ?: ""
    val port = uri.port.takeIf { it > 0 } ?: 443
    val network = uri.getQueryParameter("type") ?: "tcp"
    val security = uri.getQueryParameter("security") ?: "tls"
    return ServerConfig(
        protocol = Protocol.TROJAN,
        name = Uri.decode(uri.fragment ?: ""),
        host = host,
        port = port,
        password = uri.userInfo ?: "",
        network = network,
        security = security,
        sni = uri.getQueryParameter("sni") ?: host,
        fingerprint = uri.getQueryParameter("fp") ?: "chrome",
        allowInsecure = uri.getQueryParameter("allowInsecure") == "1",
        wsPath = uri.getQueryParameter("path") ?: "/",
    )
}

private fun parseSs(uri: Uri): ServerConfig {
    // ss://BASE64(method:password)@host:port#name  OR  ss://BASE64@host:port#name
    val name = Uri.decode(uri.fragment ?: "")
    val host = uri.host ?: ""
    val port = uri.port.takeIf { it > 0 } ?: 8388
    val userInfo = uri.userInfo ?: ""
    val decoded = runCatching {
        String(android.util.Base64.decode(userInfo, android.util.Base64.DEFAULT))
    }.getOrDefault(userInfo)
    val colonIdx = decoded.indexOf(':')
    val method = if (colonIdx > 0) decoded.substring(0, colonIdx) else "aes-256-gcm"
    val password = if (colonIdx > 0) decoded.substring(colonIdx + 1) else decoded
    return ServerConfig(
        protocol = Protocol.SS,
        name = name,
        host = host,
        port = port,
        ssMethod = method,
        ssPassword = password,
    )
}
