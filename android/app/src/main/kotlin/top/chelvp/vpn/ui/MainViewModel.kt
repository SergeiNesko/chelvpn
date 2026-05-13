package top.chelvp.vpn.ui

import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.chelvp.vpn.subscription.ServerConfig
import top.chelvp.vpn.subscription.SingleInputResult
import top.chelvp.vpn.subscription.SubscriptionManager
import top.chelvp.vpn.util.Prefs
import top.chelvp.vpn.vpn.ChelVpnService
import top.chelvp.vpn.vpn.ConfigBuilder
import android.content.Context.MODE_PRIVATE

data class MainUiState(
    val isConnected: Boolean = false,
    val isConnecting: Boolean = false,
    val hasServer: Boolean = false,
    val statusText: String = "Нет подключения",
    val statusColor: Color = Color(0xFF9E9E9E),
    val message: String = "",
    val activeServer: ServerConfig? = null,
    val pingMs: Int = -1,
    val lastError: String = "",
)

class MainViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState

    private val subManager = SubscriptionManager()
    private lateinit var prefs: Prefs

    fun init(context: Context) {
        prefs = Prefs(context)
        // Читаем checkpoint напрямую из SharedPreferences — не ждём сервис
        val sp = context.getSharedPreferences(ChelVpnService.PREFS_DEBUG, MODE_PRIVATE)
        val lastStep = sp.getString(ChelVpnService.KEY_STEP, null)
        val ctrlMethods = sp.getString(ChelVpnService.KEY_CTRL_METHODS, null)
        if (lastStep != null && ChelVpnService.lastError.isEmpty()) {
            val methodsInfo = if (ctrlMethods != null) " | ctrl: $ctrlMethods" else ""
            var errorMsg = "Краш после шага: $lastStep$methodsInfo"
            // xray.log — что xray делал перед крашем
            try {
                val logFile = java.io.File(context.filesDir, "xray.log")
                if (logFile.exists() && logFile.length() > 0) {
                    val tail = logFile.readText().trimEnd().takeLast(400)
                    if (tail.isNotEmpty()) errorMsg += "\n\nXray log:\n$tail"
                }
            } catch (_: Throwable) {}
            // xray_panic.log — Go panic trace (stderr fd=2), невидимый ранее
            try {
                val panicFile = java.io.File(context.filesDir, ChelVpnService.PANIC_LOG)
                if (panicFile.exists() && panicFile.length() > 0) {
                    val tail = panicFile.readText().trimEnd().takeLast(600)
                    if (tail.isNotEmpty()) errorMsg += "\n\nGo panic:\n$tail"
                }
            } catch (_: Throwable) {}
            ChelVpnService.lastError = errorMsg
        }
        refreshState()
    }

    private fun refreshState() {
        val server = prefs.activeServer
        val running = ChelVpnService.isRunning
        val wasConnected = _uiState.value.isConnected
        _uiState.value = MainUiState(
            isConnected = running,
            isConnecting = false,
            hasServer = server != null,
            statusText = when {
                running -> "Подключено · ${server?.displayName ?: ""}"
                server != null -> "Готово · ${server.displayName}"
                else -> "Подписка не добавлена"
            },
            statusColor = if (running) Color(0xFF00C853) else Color(0xFF9E9E9E),
            activeServer = server,
            lastError = ChelVpnService.lastError,
        )
        if (running && !wasConnected) {
            startPingMonitor()
        }
    }

    // ── VPN control ───────────────────────────────────────────

    fun startVpn(context: Context) {
        val server = prefs.activeServer ?: run {
            setMessage("Нет сервера. Добавьте подписку.")
            return
        }
        ChelVpnService.lastError = ""
        _uiState.value = _uiState.value.copy(
            isConnecting = true,
            statusText = "Подключение...",
            statusColor = Color(0xFFFF8F00),
            message = "",
            lastError = "",
        )
        val config = ConfigBuilder.build(server)
        val intent = Intent(context, ChelVpnService::class.java).apply {
            action = ChelVpnService.ACTION_START
            putExtra(ChelVpnService.EXTRA_CONFIG, config)
        }
        context.startForegroundService(intent)
        viewModelScope.launch {
            delay(2000)
            refreshState()
            if (_uiState.value.isConnected) {
                startPingMonitor()
            } else if (ChelVpnService.lastError.isNotEmpty()) {
                setMessage("Ошибка: ${ChelVpnService.lastError}")
            }
        }
    }

    fun stopVpn(context: Context) {
        context.startService(
            Intent(context, ChelVpnService::class.java).apply {
                action = ChelVpnService.ACTION_STOP
            }
        )
        viewModelScope.launch {
            delay(500)
            refreshState()
        }
    }

    // ── Ping monitor ──────────────────────────────────────────

    private fun startPingMonitor() {
        viewModelScope.launch {
            delay(2000) // дать VPN стабилизироваться
            while (_uiState.value.isConnected) {
                val ping = measurePing()
                _uiState.value = _uiState.value.copy(pingMs = ping)
                delay(10_000)
            }
            _uiState.value = _uiState.value.copy(pingMs = -1)
        }
    }

    private suspend fun measurePing(): Int = withContext(Dispatchers.IO) {
        try {
            val start = System.currentTimeMillis()
            java.net.Socket().use {
                it.soTimeout = 5000
                it.connect(java.net.InetSocketAddress("1.1.1.1", 443), 5000)
            }
            (System.currentTimeMillis() - start).toInt()
        } catch (_: Exception) { -1 }
    }

    // ── Subscription / Import ─────────────────────────────────

    fun handleImportInput(context: Context, input: String) {
        viewModelScope.launch {
            prefs = Prefs(context)
            setMessage("Загружаю...")
            when (val result = subManager.parseSingleInput(input)) {
                is SingleInputResult.SubscriptionUrl -> {
                    prefs.subscriptionUrl = result.url
                    fetchAndSave(result.url)
                }
                is SingleInputResult.Server -> {
                    prefs.addServer(result.config)
                    refreshState()
                    setMessage("Сервер добавлен: ${result.config.displayName}")
                }
                is SingleInputResult.Unknown ->
                    setMessage("Не удалось распознать ссылку")
            }
        }
    }

    fun updateSubscription(context: Context) {
        val url = prefs.subscriptionUrl
        if (url.isEmpty()) {
            setMessage("URL подписки не задан")
            return
        }
        viewModelScope.launch {
            setMessage("Обновляю подписку...")
            fetchAndSave(url)
        }
    }

    fun resetConfig(context: Context) {
        prefs = Prefs(context)
        prefs.clearServers()
        prefs.subscriptionUrl = ""
        ChelVpnService.lastError = ""
        refreshState()
        setMessage("Конфигурация сброшена")
    }

    private suspend fun fetchAndSave(url: String) {
        val result = subManager.fetchServers(url)
        result.onSuccess { servers ->
            if (servers.isEmpty()) {
                setMessage("Подписка пуста или не распознана")
            } else {
                prefs.servers = servers
                prefs.activeServerIndex = 0
                refreshState()
                setMessage("Загружено серверов: ${servers.size}")
            }
        }.onFailure { e ->
            setMessage("Ошибка: ${e.message?.take(60)}")
        }
    }

    private fun setMessage(msg: String) {
        _uiState.value = _uiState.value.copy(message = msg)
    }
}
