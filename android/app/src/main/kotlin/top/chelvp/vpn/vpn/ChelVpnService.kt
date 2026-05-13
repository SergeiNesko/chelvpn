package top.chelvp.vpn.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
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
        private const val PREFS_DEBUG = "chelvpn_debug"
        private const val KEY_STEP = "last_step"

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

                // Если прошлый запуск завершился нативным крашем — сообщаем
                val prevStep = getSharedPreferences(PREFS_DEBUG, Context.MODE_PRIVATE)
                    .getString(KEY_STEP, null)
                if (prevStep != null) {
                    lastError = "Нативный краш после шага: $prevStep"
                    Log.e(TAG, "Previous native crash at: $prevStep")
                    clearStep()
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

    // ── Checkpoint (синхронная запись перед каждым нативным вызовом) ──

    private fun step(name: String) {
        getSharedPreferences(PREFS_DEBUG, Context.MODE_PRIVATE)
            .edit().putString(KEY_STEP, name).commit()
        Log.d(TAG, "step: $name")
    }

    private fun clearStep() {
        getSharedPreferences(PREFS_DEBUG, Context.MODE_PRIVATE)
            .edit().remove(KEY_STEP).commit()
    }

    // ── Start / Stop ──────────────────────────────────────────

    private fun start(xrayConfig: String) {
        if (lastError.isNotEmpty()) {
            // lastError уже содержит информацию о предыдущем краше — не затираем
            val prev = lastError
            lastError = prev
        } else {
            lastError = ""
        }
        try {
            step("copyGeoAssets")
            copyGeoAssets()

            step("writeConfig")
            val configFile = File(filesDir, "config.json")
            configFile.writeText(xrayConfig)

            step("startXray")
            startXray(configFile.absolutePath, xrayConfig)

            step("buildTun")
            tunFd = buildTun()
            if (tunFd == null) {
                clearStep()
                lastError = "Не удалось создать TUN-интерфейс (нет разрешения VPN?)"
                Log.e(TAG, lastError)
                stop()
                return
            }

            step("startHevTunnel")
            startHevTunnel(tunFd!!.fd)

            clearStep()
            isRunning = true
            Log.i(TAG, "VPN started")
        } catch (e: Throwable) {
            clearStep()
            if (lastError.isEmpty()) {
                lastError = "${e.javaClass.simpleName}: ${e.message?.take(120)}"
            }
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

    // ── Geo assets ────────────────────────────────────────────

    private fun copyGeoAssets() {
        listOf("geoip.dat", "geosite.dat").forEach { name ->
            val dest = File(filesDir, name)
            if (!dest.exists()) {
                try {
                    assets.open(name).use { input ->
                        dest.outputStream().use { input.copyTo(it) }
                    }
                    Log.d(TAG, "Copied $name to filesDir")
                } catch (e: Throwable) {
                    Log.w(TAG, "$name not in assets: ${e.message}")
                }
            }
        }
    }

    // ── TUN ───────────────────────────────────────────────────

    private fun buildTun(): ParcelFileDescriptor? = Builder()
        .setSession("ChelVPN")
        .setMtu(1500)
        .addAddress("172.19.0.1", 30)
        .addRoute("0.0.0.0", 0)
        .addRoute("::", 0)
        .addDnsServer("1.1.1.1")
        .addDnsServer("8.8.8.8")
        .establish()

    // ── Xray via libv2ray (reflection) ────────────────────────

    private fun startXray(configPath: String, configJson: String) {
        step("libv2ray.Class.forName")
        val libCls = Class.forName("libv2ray.Libv2ray")

        // Factory method (name changed across libv2ray versions)
        val newPointMethod = libCls.methods.firstOrNull {
            it.name == "newV2RayPoint" || it.name == "newVpoint" ||
            it.name == "initV2Env"     || it.name == "newCoreController"
        } ?: run {
            val available = libCls.methods.joinToString { it.name }
            throw NoSuchMethodException("Нет фабричного метода. Доступно: $available")
        }
        Log.d(TAG, "factory=${newPointMethod.name} params=${newPointMethod.parameterCount}")

        val supportIface = newPointMethod.parameterTypes[0]

        step("Proxy.newProxyInstance")
        val proxy = Proxy.newProxyInstance(
            supportIface.classLoader,
            arrayOf(supportIface),
            XrayProtocol()
        )

        step(newPointMethod.name)
        val point = if (newPointMethod.parameterTypes.size == 1) {
            newPointMethod.invoke(null, proxy)!!
        } else {
            newPointMethod.invoke(null, proxy, false)!!
        }
        v2rayPoint = point

        step("setConfigureFileContent")
        val configMethod = point.javaClass.methods.firstOrNull { m ->
            m.name.lowercase().contains("config") || m.name.lowercase().contains("content")
        }
        val configSet = if (configMethod != null) {
            Log.d(TAG, "configMethod=${configMethod.name} params=${configMethod.parameterTypes.map { it.simpleName }}")
            runCatching {
                if (configMethod.parameterTypes.size == 1 &&
                    configMethod.parameterTypes[0] == String::class.java) {
                    configMethod.invoke(point, configJson)
                } else {
                    configMethod.invoke(point, configPath)
                }
            }.isSuccess
        } else {
            false
        }
        if (!configSet) {
            val methods = point.javaClass.methods
                .filterNot { it.declaringClass == Any::class.java }
                .joinToString { m -> "${m.name}(${m.parameterTypes.joinToString { p -> p.simpleName }})" }
            throw IllegalStateException("Методы контроллера: $methods")
        }

        step("runLoop")
        val runMethod = runCatching {
            point.javaClass.getMethod("runLoop", Boolean::class.java)
        }.getOrElse {
            runCatching {
                point.javaClass.getMethod("runLoop", java.lang.Boolean::class.java)
            }.getOrElse {
                val found = point.javaClass.methods
                    .filter { m -> m.name.contains("run", ignoreCase = true) ||
                                   m.name.contains("start", ignoreCase = true) }
                    .joinToString { m -> "${m.name}(${m.parameterTypes.joinToString { p -> p.simpleName }})" }
                throw NoSuchMethodException("runLoop не найден. run/start: $found")
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
                "protect" -> {
                    val fd = when (val a = args?.get(0)) {
                        is Long -> a.toInt()
                        is Int  -> a
                        else    -> 0
                    }
                    protect(fd)
                }
                "onEmitStatus" -> 0L
                else -> primitiveDefault(method)
            }
        }

        private fun primitiveDefault(method: Method?): Any? = when (method?.returnType) {
            java.lang.Boolean.TYPE   -> false
            java.lang.Integer.TYPE   -> 0
            java.lang.Long.TYPE      -> 0L
            java.lang.Double.TYPE    -> 0.0
            java.lang.Float.TYPE     -> 0f
            java.lang.Short.TYPE     -> 0.toShort()
            java.lang.Byte.TYPE      -> 0.toByte()
            java.lang.Character.TYPE -> ' '
            else                     -> null
        }
    }

    // ── hev-socks5-tunnel ────────────────────────────────────

    private var hevThread: Thread? = null

    private fun startHevTunnel(fd: Int) {
        step("loadLibrary.hev-socks5-tunnel")
        System.loadLibrary("hev-socks5-tunnel")
        val cfg = buildHevConfig()
        step("hevStart")
        hevThread = Thread(null, {
            try { hevBridge.hevStart(cfg, fd) }
            catch (e: Throwable) { Log.e(TAG, "hevStart error", e) }
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
  limit-nofile: 65535
tunnel:
  mtu: 1500
  ipv4-address: 172.19.0.1
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
