package top.chelvp.vpn.subscription

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class SubscriptionManager {

    // Accept all SSL certificates — x-ui panels commonly use self-signed certs.
    private val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    })

    private val http = run {
        val sslCtx = SSLContext.getInstance("TLS").apply {
            init(null, trustAllCerts, java.security.SecureRandom())
        }
        OkHttpClient.Builder()
            .sslSocketFactory(sslCtx.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Загружает подписку по URL, возвращает список серверов.
     * Подписка — base64-строка, каждая строка которой — URI сервера.
     */
    suspend fun fetchServers(url: String): Result<List<ServerConfig>> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(url).build()
            val (code, body) = http.newCall(req).execute().use { resp ->
                resp.code to (resp.body?.string() ?: "")
            }
            if (body.isEmpty()) error("Сервер вернул пустой ответ (HTTP $code)")
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
