package top.chelvp.vpn.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
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
                startForeground(NOTIF_ID, buildNotification())
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
        try {
            val configFile = File(filesDir, "config.json")
            configFile.writeText(xrayConfig)

            startXray(configFile.absolutePath, xrayConfig)

            tunFd = buildTun()
            if (tunFd == null) {
                Log.e(TAG, "Failed to establish VPN tunnel")
                stop()
                return
            }

            startHevTunnel(tunFd!!.fd)

            isRunning = true
            Log.i(TAG, "VPN started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN", e)
            stop()
        }
    }

    private fun stop() {
        isRunning = false
        try { stopHevTunnel() } catch (_: Exception) {}
        try { stopXray() }     catch (_: Exception) {}
        try { tunFd?.close() } catch (_: Exception) {}
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
        val supportIface = Class.forName("libv2ray.V2RayVpnServiceSupports")

        val proxy = Proxy.newProxyInstance(
            Thread.currentThread().contextClassLoader,
            arrayOf(supportIface),
            XrayProtocol()
        )

        val newPoint = libCls.getMethod("newV2RayPoint", supportIface, Boolean::class.java)
        val point = newPoint.invoke(null, proxy, false)!!
        v2rayPoint = point

        // Try setConfigureFileContent first, fall back to setConfigureFile
        runCatching {
            point.javaClass.getMethod("setConfigureFileContent", String::class.java)
                .invoke(point, configJson)
        }.onFailure {
            point.javaClass.getMethod("setConfigureFile", String::class.java)
                .invoke(point, configPath)
        }

        point.javaClass.getMethod("runLoop", Boolean::class.java).invoke(point, false)
    }

    private fun stopXray() {
        v2rayPoint?.let {
            it.javaClass.getMethod("stopLoop").invoke(it)
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

    private fun startHevTunnel(fd: Int) {
        System.loadLibrary("hev-socks5-tunnel")
        hevBridge.hevStart(buildHevConfig(), fd)
    }

    private fun stopHevTunnel() {
        runCatching { hevBridge.hevStop() }
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
