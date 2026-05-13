package top.chelvp.vpn.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import top.chelvp.vpn.R
import top.chelvp.vpn.ui.MainActivity
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

class ChelVpnService : VpnService() {

    companion object {
        const val ACTION_START = "top.chelvp.vpn.START"
        const val ACTION_STOP  = "top.chelvp.vpn.STOP"
        const val EXTRA_CONFIG = "xray_config_json"
        private const val TAG  = "ChelVpnService"
        private const val NOTIF_CHANNEL = "chelvpn_vpn"
        private const val NOTIF_ID = 1

        @Volatile var isRunning = false
        @Volatile var lastError = ""
    }

    private var tunFd: ParcelFileDescriptor? = null
    private var v2rayPoint: Any? = null
    private val hevBridge = com.v2ray.ang.service.V2RayVpnService()

    // ── Lifecycle ─────────────────────────────────────────────

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stop()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val config = intent.getStringExtra(EXTRA_CONFIG) ?: run {
                    Log.e(TAG, "No config in intent")
                    stopSelf()
                    return START_NOT_STICKY
                }
                try {
                    val notif = buildNotification()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                    } else {
                        startForeground(NOTIF_ID, notif)
                    }
                } catch (e: Throwable) {
                    lastError = "startForeground: ${e.javaClass.simpleName}: ${e.message?.take(100)}"
                    Log.e(TAG, "startForeground failed", e)
                    stopSelf()
                    return START_NOT_STICKY
                }
                start(config)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stop()
        super.onDestroy()
    }

    // ── Start / Stop ──────────────────────────────────────────

    private fun start(xrayConfig: String) {
        lastError = ""
        try {
            val configFile = File(filesDir, "config.json")
            configFile.writeText(xrayConfig)

            startXray(configFile.absolutePath, xrayConfig)

            tunFd = buildTun()
            if (tunFd == null) {
                lastError = "Не удалось создать TUN-интерфейс (нет разрешения VPN?)"
                Log.e(TAG, lastError)
                stop()
                return
            }

            // hevStart блокирует поток — запускаем в отдельном потоке
            startHevTunnel(tunFd!!.fd)

            isRunning = true
            Log.i(TAG, "VPN started")
        } catch (e: Throwable) {
            lastError = "${e.javaClass.simpleName}: ${e.message?.take(120)}"
            Log.e(TAG, "Failed to start VPN", e)
            stop()
        }
    }

    private fun stop() {
        isRunning = false
        try { stopHevTunnel() } catch (_: Throwable) {}
        try { stopXray() }     catch (_: Throwable) {}
        try { tunFd?.close() } catch (_: Throwable) {}
        tunFd = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "VPN stopped")
    }

    // ── TUN ───────────────────────────────────────────────────

    private fun buildTun(): ParcelFileDescriptor? = Builder()
        .setSession("ChelVPN")
        .setMtu(8500)
        .addAddress("172.19.0.1", 30)
        .addRoute("0.0.0.0", 0)
        .addRoute("::", 0)
        .addDnsServer("1.1.1.1")
        .addDnsServer("8.8.8.8")
        .establish()

    // ── Xray via libv2ray (reflection) ────────────────────────

    private fun startXray(configPath: String, configJson: String) {
        val libCls = Class.forName("libv2ray.Libv2ray")

        // initCoreEnv needed in libv2ray 2.x before creating the controller
        libCls.methods.firstOrNull { it.name == "initCoreEnv" }?.let { initMethod ->
            try {
                initMethod.invoke(null, filesDir.absolutePath, filesDir.absolutePath)
                Log.d(TAG, "initCoreEnv OK")
            } catch (e: Throwable) {
                Log.w(TAG, "initCoreEnv skipped: ${e.message}")
            }
        }

        // Factory method name varies across libv2ray versions
        val newPointMethod = libCls.methods.firstOrNull {
            it.name == "newV2RayPoint" || it.name == "newVpoint" ||
            it.name == "initV2Env" || it.name == "newCoreController"
        } ?: run {
            val available = libCls.methods.joinToString { it.name }
            throw NoSuchMethodException("Методы Libv2ray: $available")
        }

        val supportIface = newPointMethod.parameterTypes[0]
        Log.d(TAG, "factory=${newPointMethod.name} iface=${supportIface.name}")

        val proxy = Proxy.newProxyInstance(
            Thread.currentThread().contextClassLoader,
            arrayOf(supportIface),
            XrayProtocol()
        )

        // newCoreController(supports) — 1 param; newV2RayPoint(supports, useIPv6) — 2 params
        val point = if (newPointMethod.parameterTypes.size == 1) {
            newPointMethod.invoke(null, proxy)!!
        } else {
            newPointMethod.invoke(null, proxy, false)!!
        }
        v2rayPoint = point

        // Try JSON content first, then file path; propagate error if both fail
        val configSet = runCatching {
            point.javaClass.getMethod("setConfigureFileContent", String::class.java)
                .invoke(point, configJson)
        }.isSuccess || runCatching {
            point.javaClass.getMethod("setConfigureFile", String::class.java)
                .invoke(point, configPath)
        }.isSuccess
        if (!configSet) throw IllegalStateException("Не удалось передать конфиг в libv2ray")

        // runLoop — try primitive boolean, then boxed Boolean, then search by name
        val runMethod = runCatching {
            point.javaClass.getMethod("runLoop", Boolean::class.java)
        }.getOrElse {
            runCatching {
                point.javaClass.getMethod("runLoop", java.lang.Boolean::class.java)
            }.getOrElse {
                val found = point.javaClass.methods
                    .filter { m -> m.name.contains("run", ignoreCase = true) || m.name.contains("start", ignoreCase = true) }
                    .joinToString { m -> "${m.name}(${m.parameterTypes.joinToString { p -> p.simpleName }})" }
                throw NoSuchMethodException("runLoop не найден. run/start методы: $found")
            }
        }
        runMethod.invoke(point, false)
    }

    private fun stopXray() {
        v2rayPoint?.let {
            runCatching { it.javaClass.getMethod("stopLoop").invoke(it) }
        }
        v2rayPoint = null
    }

    private inner class XrayProtocol : InvocationHandler {
        override fun invoke(proxy: Any?, method: Method?, args: Array<out Any?>?): Any? {
            return when (method?.name) {
                "protect" -> protect((args?.get(0) as? Long)?.toInt() ?: 0)
                "onEmitStatus" -> 0L
                else -> null
            }
        }
    }

    // ── hev-socks5-tunnel ────────────────────────────────────

    private var hevThread: Thread? = null

    private fun startHevTunnel(fd: Int) {
        System.loadLibrary("hev-socks5-tunnel")
        val cfg = buildHevConfig()
        hevThread = Thread(null, {
            try { hevBridge.hevStart(cfg, fd) }
            catch (e: Exception) { Log.e(TAG, "hevStart error", e) }
        }, "hev-tunnel").also { it.isDaemon = true; it.start() }
    }

    private fun stopHevTunnel() {
        runCatching { hevBridge.hevStop() }
        hevThread?.interrupt()
        hevThread = null
    }

    private fun buildHevConfig(): String = """
misc:
  task-stack-size: 81920
  connect-timeout: 300
  read-write-timeout: 60
  log-file: stderr
  log-level: warn
  pid-file: ''
  limit-nofile: 65535
tunnel:
  mtu: 8500
  ipv4-address: 172.19.0.1
  restrict-ports:
  restrict-ips:
socks5:
  port: ${ConfigBuilder.SOCKS_PORT}
  address: 127.0.0.1
  udp: udp
  pipeline: false
dns:
  port: 0
  address: 224.0.0.0
""".trimIndent()

    // ── Notification ──────────────────────────────────────────

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(NOTIF_CHANNEL, "ChelVPN", NotificationManager.IMPORTANCE_LOW)
        )
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, ChelVpnService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, NOTIF_CHANNEL)
            .setContentTitle("ChelVPN")
            .setContentText("Подключено")
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setContentIntent(openIntent)
            .addAction(Notification.Action.Builder(null, "Отключить", stopIntent).build())
            .setOngoing(true)
            .build()
    }
}