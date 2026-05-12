package top.chelvp.vpn.ui

import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import top.chelvp.vpn.subscription.ServerConfig
import top.chelvp.vpn.subscription.SingleInputResult
import top.chelvp.vpn.subscription.SubscriptionManager
import top.chelvp.vpn.util.Prefs
import top.chelvp.vpn.vpn.ChelVpnService
import top.chelvp.vpn.vpn.ConfigBuilder

data class MainUiState(
    val isConnected: Boolean = false,
    val isConnecting: Boolean = false,
    val hasServer: Boolean = false,
    val statusText: String = "Нет подключения",
    val statusColor: Color = Color(0xFF9E9E9E),
    val message: String = "",
    val activeServer: ServerConfig? = null,
)

class MainViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState

    private val subManager = SubscriptionManager()
    private lateinit var prefs: Prefs

    fun init(context: Context) {
        prefs = Prefs(context)
        refreshState()
    }

    private fun refreshState() {
        val server = prefs.activeServer
        val running = ChelVpnService.isRunning
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
        )
    }

    // ── VPN control ───────────────────────────────────────────

    fun startVpn(context: Context) {
        val server = prefs.activeServer ?: run {
            setMessage("Нет сервера. Добавьте подписку.")
            return
        }
        _uiState.value = _uiState.value.copy(
            isConnecting = true,
            statusText = "Подключение...",
            statusColor = Color(0xFFFF8F00),
            message = ""
        )
        val config = ConfigBuilder.build(server)
        val intent = Intent(context, ChelVpnService::class.java).apply {
            action = ChelVpnService.ACTION_START
            putExtra(ChelVpnService.EXTRA_CONFIG, config)
        }
        context.startForegroundService(intent)
        // Через секунду обновляем статус
        viewModelScope.launch {
            kotlinx.coroutines.delay(1500)
            refreshState()
        }
    }

    fun stopVpn(context: Context) {
        context.startService(
            Intent(context, ChelVpnService::class.java).apply {
                action = ChelVpnService.ACTION_STOP
            }
        )
        viewModelScope.launch {
            kotlinx.coroutines.delay(500)
            refreshState()
        }
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
