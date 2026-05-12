package top.chelvp.vpn.subscription

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class SubscriptionManager {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Загружает подписку по URL, возвращает список серверов.
     * Подписка — base64-строка, каждая строка которой — URI сервера.
     */
    suspend fun fetchServers(url: String): Result<List<ServerConfig>> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(url).build()
            val body = http.newCall(req).execute().use { it.body?.string() ?: "" }
            parseSubscriptionContent(body)
        }
    }

    /**
     * Парсит содержимое подписки (base64 или сырые URI через перенос строки).
     */
    fun parseSubscriptionContent(content: String): List<ServerConfig> {
        val text = content.trim()
        // Попробуем base64
        val decoded = runCatching {
            String(Base64.decode(text, Base64.DEFAULT)).trim()
        }.getOrNull()

        val lines = (decoded ?: text)
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.contains("://") }

        return lines.mapNotNull { parseServerUri(it) }
    }

    /**
     * Разбирает одиночный URI (для вставки из буфера или QR).
     * Поддерживает:
     *  - vless://...
     *  - vmess://...
     *  - trojan://...
     *  - ss://...
     *  - https://... (URL подписки — сохраняем и скачиваем)
     */
    fun parseSingleInput(input: String): SingleInputResult {
        val trimmed = input.trim()
        return when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") ->
                SingleInputResult.SubscriptionUrl(trimmed)
            trimmed.contains("://") -> {
                val server = parseServerUri(trimmed)
                if (server != null) SingleInputResult.Server(server)
                else SingleInputResult.Unknown
            }
            else -> SingleInputResult.Unknown
        }
    }
}

sealed class SingleInputResult {
    data class SubscriptionUrl(val url: String) : SingleInputResult()
    data class Server(val config: ServerConfig) : SingleInputResult()
    object Unknown : SingleInputResult()
}
