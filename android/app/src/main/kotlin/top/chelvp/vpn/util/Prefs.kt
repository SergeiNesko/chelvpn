package top.chelvp.vpn.util

import android.content.Context
import android.content.SharedPreferences
import top.chelvp.vpn.subscription.ServerConfig
import top.chelvp.vpn.subscription.parseServerUri
import org.json.JSONArray
import org.json.JSONObject

/**
 * Хранит настройки приложения в SharedPreferences.
 * Задел на несколько серверов: список serialized как JSON-массив.
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("chelvpn_prefs", Context.MODE_PRIVATE)

    // ── Subscription URL ──────────────────────────────────────

    var subscriptionUrl: String
        get() = sp.getString(KEY_SUB_URL, "") ?: ""
        set(v) { sp.edit().putString(KEY_SUB_URL, v).apply() }

    // ── Server list ───────────────────────────────────────────

    /** Список всех серверов (JSON-сериализованный). */
    var servers: List<ServerConfig>
        get() {
            val json = sp.getString(KEY_SERVERS, null) ?: return emptyList()
            return runCatching {
                val arr = JSONArray(json)
                (0 until arr.length()).mapNotNull {
                    parseServerUri(arr.getString(it))
                }
            }.getOrDefault(emptyList())
        }
        set(list) {
            val arr = JSONArray()
            list.forEach { s -> arr.put(serverToUri(s)) }
            sp.edit().putString(KEY_SERVERS, arr.toString()).apply()
        }

    /** Индекс активного сервера */
    var activeServerIndex: Int
        get() = sp.getInt(KEY_ACTIVE_IDX, 0)
        set(v) { sp.edit().putInt(KEY_ACTIVE_IDX, v).apply() }

    val activeServer: ServerConfig? get() = servers.getOrNull(activeServerIndex)

    fun addServer(server: ServerConfig) {
        val list = servers.toMutableList()
        if (list.none { it.host == server.host && it.port == server.port }) {
            list.add(server)
            servers = list
        }
    }

    fun clearServers() {
        sp.edit().remove(KEY_SERVERS).remove(KEY_ACTIVE_IDX).apply()
    }

    // ── Helpers ───────────────────────────────────────────────

    /** Превращает ServerConfig обратно в URI-строку (для хранения). */
    private fun serverToUri(s: ServerConfig): String {
        // Простое хранение в виде объекта JSON для точного восстановления
        return JSONObject().apply {
            put("_type", "raw")
            put("proto", s.protocol.name)
            put("name", s.name)
            put("host", s.host)
            put("port", s.port)
            put("uuid", s.uuid)
            put("password", s.password)
            put("network", s.network)
            put("security", s.security)
            put("pbk", s.publicKey)
            put("sid", s.shortId)
            put("fp", s.fingerprint)
            put("sni", s.sni)
            put("flow", s.flow)
            put("wsPath", s.wsPath)
            put("wsHost", s.wsHost)
            put("grpc", s.grpcServiceName)
            put("alterId", s.alterId)
            put("vmessMethod", s.vmessMethod)
            put("ssMethod", s.ssMethod)
            put("ssPassword", s.ssPassword)
            put("allowInsecure", s.allowInsecure)
        }.toString()
    }

    companion object {
        private const val KEY_SUB_URL   = "sub_url"
        private const val KEY_SERVERS   = "servers_json"
        private const val KEY_ACTIVE_IDX = "active_idx"
    }
}
