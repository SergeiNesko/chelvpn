package top.chelvp.vpn.ui

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.chelvp.vpn.vpn.ChelVpnService

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) vm.startVpn(this)
    }

    private val qrLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.getStringExtra(QrScanActivity.EXTRA_RESULT) ?: return@registerForActivityResult
            vm.handleImportInput(this, uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm.init(this)
        handleIntent(intent)

        setContent {
            ChelVpnTheme {
                val state by vm.uiState.collectAsState()
                MainScreen(
                    state = state,
                    onConnectClick = { handleConnectClick() },
                    onUpdateSubClick = { vm.updateSubscription(this) },
                    onResetClick = { vm.resetConfig(this) },
                    onPasteClick = { handlePaste() },
                    onQrClick = { qrLauncher.launch(Intent(this, QrScanActivity::class.java)) },
                    onShowLogClick = { vm.loadXrayLog() },
                    onDismissLog = { vm.clearXrayLog() },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.host == "install-config") {
            val url = uri.getQueryParameter("url") ?: return
            vm.handleImportInput(this, url)
        }
    }

    private fun handleConnectClick() {
        if (ChelVpnService.isRunning) {
            vm.stopVpn(this)
        } else {
            val vpnIntent = VpnService.prepare(this)
            if (vpnIntent != null) {
                vpnPermissionLauncher.launch(vpnIntent)
            } else {
                vm.startVpn(this)
            }
        }
    }

    private fun handlePaste() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: return
        vm.handleImportInput(this, text)
    }
}

// ─── Screen ─────────────────────────────────────────────────────────────────

@Composable
fun MainScreen(
    state: MainUiState,
    onConnectClick: () -> Unit,
    onUpdateSubClick: () -> Unit,
    onResetClick: () -> Unit,
    onPasteClick: () -> Unit,
    onQrClick: () -> Unit,
    onShowLogClick: () -> Unit,
    onDismissLog: () -> Unit,
) {
    val bgTop = Color(0xFF0D1B2A)
    val bgBot = Color(0xFF1B2838)

    if (state.xrayLog.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = onDismissLog,
            title = { Text("Xray лог", fontWeight = FontWeight.Bold) },
            text = {
                Box(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    Text(state.xrayLog, fontSize = 10.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                }
            },
            confirmButton = { TextButton(onClick = onDismissLog) { Text("Закрыть") } }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(bgTop, bgBot))),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
        ) {

            Text(
                "ChelVPN",
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )

            Spacer(Modifier.height(6.dp))

            Text(
                state.statusText,
                color = state.statusColor,
                fontSize = 14.sp
            )

            // Пинг — показываем только когда подключено
            if (state.isConnected && state.pingMs > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Пинг: ${state.pingMs} мс",
                    color = pingColor(state.pingMs),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(Modifier.height(48.dp))

            ConnectButton(
                isConnected = state.isConnected,
                isConnecting = state.isConnecting,
                enabled = !state.isConnecting && state.hasServer,
                onClick = onConnectClick
            )

            Spacer(Modifier.height(40.dp))

            if (state.hasServer) {
                // Обновить подписку
                OutlinedButton(
                    onClick = onUpdateSubClick,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.4f)),
                    modifier = Modifier.height(44.dp)
                ) {
                    Icon(Icons.Default.Refresh, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Обновить подписку", fontSize = 14.sp)
                }

                Spacer(Modifier.height(10.dp))

                // Сбросить конфигурацию
                TextButton(
                    onClick = onResetClick,
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.White.copy(alpha = 0.5f))
                ) {
                    Text("🗑 Сбросить конфигурацию", fontSize = 13.sp)
                }
            } else {
                Text("Добавьте подписку:", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = onPasteClick,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.4f))
                    ) { Text("📋 Вставить") }
                    OutlinedButton(
                        onClick = onQrClick,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.4f))
                    ) { Text("📷 QR-код") }
                }
            }

            // Ошибка VPN (если есть)
            if (state.lastError.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text(
                    "⚠️ ${state.lastError}",
                    color = Color(0xFFFF5252),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Сообщение / статус
            if (state.message.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(state.message, color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
            }

            Spacer(Modifier.height(16.dp))
            TextButton(
                onClick = onShowLogClick,
                colors = ButtonDefaults.textButtonColors(contentColor = Color.White.copy(alpha = 0.35f))
            ) {
                Text("📋 xray лог", fontSize = 12.sp)
            }
        }
    }
}

private fun pingColor(ms: Int): Color = when {
    ms < 100 -> Color(0xFF00C853)
    ms < 250 -> Color(0xFFFFD600)
    else     -> Color(0xFFFF5252)
}

@Composable
fun ConnectButton(
    isConnected: Boolean,
    isConnecting: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val pulseAnim = rememberInfiniteTransition(label = "pulse")
    val scale by pulseAnim.animateFloat(
        initialValue = 1f,
        targetValue = if (isConnecting) 1.06f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    val btnColor by animateColorAsState(
        targetValue = when {
            isConnected  -> Color(0xFF00C853)
            isConnecting -> Color(0xFFFF8F00)
            else         -> Color(0xFF1565C0)
        },
        animationSpec = tween(400),
        label = "btnColor"
    )

    Button(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = btnColor),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 12.dp),
        modifier = Modifier
            .size(180.dp)
            .scale(scale),
        contentPadding = PaddingValues(0.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.PowerSettingsNew,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(48.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = when {
                    isConnecting -> "ПОДОЖДИТЕ"
                    isConnected  -> "ОТКЛЮЧИТЬ"
                    else         -> "ПОДКЛЮЧИТЬ"
                },
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
        }
    }
}

// ─── Theme ───────────────────────────────────────────────────────────────────

@Composable
fun ChelVpnTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF1565C0),
            background = Color(0xFF0D1B2A),
            surface = Color(0xFF1B2838),
        ),
        content = content
    )
}
