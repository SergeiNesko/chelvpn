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
import android.system.Os
import android.system.OsConstants
import android.util.Log
import top.chelvp.vpn.R
import top.chelvp.vpn.ui.MainActivity
import top.chelvp.vpn.util.Prefs
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
        const val PREFS_DEBUG = "chelvpn_debug"
        const val KEY_STEP = "last_step"
        const val KEY_CTRL_METHODS = "ctrl_methods"
        const val PANIC_LOG = "xray_panic.log"

        @Volatile var isRunning = false
        @Volatile var lastError = ""
    }

    private var tunFd: ParcelFileDescriptor? = null
    private var v2rayPoint: Any? = null
    private var usedNewApi = false
    private var tProxy: com.v2ray.ang.service.TProxyService? = null
    private var byeDpiProxy: ByeDpiProxy? = null
    private var byeDpiThread: Thread? = null

    // ── Lifecycle ─────────────────────────────────────────────

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stop()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val config = intent.getStringExtra(EXTRA_CONFIG) ?: ""

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

    // ── Checkpoint ────────────────────────────────────────────

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
        if (lastError.isEmpty()) lastError = ""
        val mode = Prefs(this).vpnMode
        try {
            step("buildTun")
            tunFd = buildTun(mode)
            if (tunFd == null) {
                clearStep()
                lastError = "Не удалось создать TUN-интерфейс (нет разрешения VPN?)"
                Log.e(TAG, lastError)
                stop()
                return
            }

            if (mode == VpnMode.DPI_BYPASS) {
                step("startByeDpi")
                startByeDpiProxy()
                step("startHev")
                startHevTunnel(tunFd!!.fd, ByeDpiProxy.PORT)
            } else {
                step("copyGeoAssets")
                copyGeoAssets()

                step("writeConfig")
                val xrayLog = File(filesDir, "xray.log")
                xrayLog.delete()
                val enrichedConfig = injectLogPath(xrayConfig, xrayLog.absolutePath)
                val configFile = File(filesDir, "config.json")
                configFile.writeText(enrichedConfig)

                redirectStderr()

                step("startXray")
                startXray(configFile.absolutePath, enrichedConfig, tunFd!!.fd)

                step("startHev")
                startHevTunnel(tunFd!!.fd, ConfigBuilder.SOCKS_PORT)
            }

            step("running")
            isRunning = true
            Log.i(TAG, "VPN started, mode=$mode")
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
        usedNewApi = false
        clearStep()
        getSharedPreferences(PREFS_DEBUG, Context.MODE_PRIVATE)
            .edit().remove(KEY_CTRL_METHODS).apply()
        try { stopXray() } catch (_: Throwable) {}
        stopHevTunnel()
        stopByeDpiProxy()
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

    private fun buildTun(mode: VpnMode): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession("ChelVPN")
            .setMtu(9000)
            .addAddress("26.26.26.1", 24)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .addDnsServer("1.1.1.1")
            .addDnsServer("8.8.8.8")

        if (mode == VpnMode.DPI_BYPASS) {
            // Only route selected apps through the TUN — everything else goes normally
            for (pkg in listOf(
                "com.instagram.android",
                "com.google.android.youtube",
                "com.discord",
            )) {
                runCatching { builder.addAllowedApplication(pkg) }
                    .onFailure { Log.w(TAG, "addAllowedApplication($pkg) failed: ${it.message}") }
            }
        } else {
            // All apps except ours go through TUN (prevents routing loop)
            builder.addDisallowedApplication(packageName)
        }

        return builder.establish()
    }

    // ── Hev-socks5-tunnel TUN bridge ─────────────────────────
    // hev reads raw IP packets from the TUN fd and forwards each TCP/UDP
    // connection through xray's SOCKS5 inbound (127.0.0.1:10808).
    // Without this bridge, other apps receive no internet despite xray running,
    // because startLoop() in AndroidLibXrayLite does not include a built-in
    // gVisor TUN stack — it only starts xray-core with SOCKS5/HTTP inbounds.

    private fun startHevTunnel(fd: Int, socksPort: Int) {
        if (!com.v2ray.ang.service.TProxyService.hevAvailable) {
            Log.w(TAG, "libhevtun not available — TUN bridge disabled")
            return
        }
        val configFile = java.io.File(filesDir, "hev_config.yaml")
        configFile.writeText(buildHevYaml(socksPort))
        val tp = com.v2ray.ang.service.TProxyService().also { tProxy = it }
        try {
            tp.TProxyStartService(configFile.absolutePath, fd)
            Log.i(TAG, "hev TUN bridge started (fd=$fd → socks5 :$socksPort)")
        } catch (e: Throwable) {
            Log.e(TAG, "hev start failed: ${e.message}")
        }
    }

    private fun stopHevTunnel() {
        tProxy?.let { tp ->
            try { tp.TProxyStopService() } catch (_: Throwable) {}
            tProxy = null
        }
    }

    private fun buildHevYaml(socksPort: Int): String = """
tunnel:
  name: tun0
  mtu: 9000
socks5:
  port: $socksPort
  address: '127.0.0.1'
  udp: 'udp'
misc:
  task-stack-size: 81920
  connect-timeout: 5000
  read-write-timeout: 60000
  log-file: stderr
  log-level: warn
""".trimIndent()

    // ── ByeDpi DPI-bypass proxy ───────────────────────────────
    // jniStartProxy() blocks until the proxy exits — run in a daemon thread.
    // jniStopProxy() calls shutdown(server_fd) to unblock it.

    private fun startByeDpiProxy() {
        if (!ByeDpiProxy.isAvailable) {
            lastError = "libbyedpi.so недоступна — DPI-bypass невозможен"
            Log.e(TAG, lastError)
            throw IllegalStateException(lastError)
        }
        val proxy = ByeDpiProxy().also { byeDpiProxy = it }
        val thread = Thread(null, {
            val code = proxy.startProxy()
            Log.i(TAG, "byedpi exited with code $code")
        }, "byedpi-proxy").also {
            it.isDaemon = true
            it.start()
        }
        byeDpiThread = thread

        // Wait up to 3 s for port to open. In Android VPN context on some devices,
        // loopback Socket checks can be unreliable — so if the thread is still alive
        // we proceed even on timeout rather than incorrectly aborting the start.
        val portOpen = waitForPort(ByeDpiProxy.PORT, 3000)
        if (!thread.isAlive) {
            lastError = "byedpi не запустился (завершился досрочно)"
            Log.e(TAG, lastError)
            throw IllegalStateException(lastError)
        }
        if (!portOpen) {
            Log.w(TAG, "byedpi port check timed out — thread alive, proceeding")
        } else {
            Log.i(TAG, "byedpi DPI-bypass proxy ready on :${ByeDpiProxy.PORT}")
        }
    }

    private fun waitForPort(port: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                java.net.Socket("127.0.0.1", port).close()
                return true
            } catch (_: Exception) {
                Thread.sleep(50)
            }
        }
        return false
    }

    private fun stopByeDpiProxy() {
        byeDpiProxy?.let { proxy ->
            runCatching { proxy.stopProxy() }
            runCatching { proxy.jniForceClose() }
        }
        byeDpiThread?.join(1000)
        byeDpiProxy = null
        byeDpiThread = null
    }

    // ── Xray via libv2ray (reflection) ────────────────────────
    //
    // Новый API (v2rayNG ≥ 2.x, AndroidLibXrayLite):
    //   Libv2ray.initCoreEnv(assetsDir, key)
    //   val ctrl = Libv2ray.newCoreController(callback)
    //   ctrl.startLoop(configJson, tunFd)   ← аналог v2rayNG
    //   ctrl.stopLoop()
    //
    // Старый API (v2rayNG < 2.x):
    //   val point = Libv2ray.newV2RayPoint(callback)
    //   point.setConfigureFileContent(json) + point.runLoop(false)
    //   point.stopLoop()

    private fun startXray(configPath: String, configJson: String, tunFd: Int) {
        step("libv2ray.Class.forName")
        val libCls = Class.forName("libv2ray.Libv2ray")

        // initCoreEnv(assetsPath, key) — key используется как xudp.BaseKey.
        // xudp.BaseKey ДОЛЖЕН быть ровно 32 байта (Go panic иначе).
        // MD5(ANDROID_ID) → 16 байт → 32 hex-символа = 32 байта как Go-строка.
        step("initCoreEnv")
        val androidId = runCatching {
            android.provider.Settings.Secure.getString(
                contentResolver, android.provider.Settings.Secure.ANDROID_ID
            ) ?: ""
        }.getOrDefault("")
        // Go base64-декодирует ключ переданный в initCoreEnv и проверяет len == 32.
        // Доказательство из паник:
        //   ANDROID_ID 16 символов → base64 → 12 байт → "len 12"
        //   MD5-hex 32 символа     → base64 → 24 байта → "len 24"
        // Нужно: base64(SHA-256(androidId)) → Go декодирует → 32 байта → len == 32 ✓
        // Go использует base64.RawURLEncoding (URL-safe, без паддинга).
        // Доказательство: "...s+aJL..." → останавливается на '+' → 4 группы = 12 байт.
        // SHA-256(androidId) = 32 байта → RawURL-base64 = 43 символа.
        // Go: RawURLEncoding.Decode(43 chars) → 32 байта → len == 32 ✓
        val deviceKey = try {
            val sha256 = java.security.MessageDigest.getInstance("SHA-256")
                .digest(androidId.toByteArray())
            android.util.Base64.encodeToString(
                sha256,
                android.util.Base64.NO_WRAP or
                android.util.Base64.URL_SAFE or
                android.util.Base64.NO_PADDING
            )
        } catch (_: Throwable) {
            android.util.Base64.encodeToString(
                ByteArray(32),
                android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING
            )
        }
        libCls.methods.firstOrNull { it.name == "initCoreEnv" }?.let { m ->
            runCatching {
                when (m.parameterTypes.size) {
                    2    -> m.invoke(null, filesDir.absolutePath, deviceKey)
                    1    -> m.invoke(null, filesDir.absolutePath)
                    else -> Unit
                }
            }.onSuccess { Log.d(TAG, "initCoreEnv OK, key[0..7]=${deviceKey.take(8)}") }
             .onFailure { Log.w(TAG, "initCoreEnv: ${it.message}") }
        }

        val factoryMethod = libCls.methods.firstOrNull {
            it.name == "newV2RayPoint" || it.name == "newVpoint" ||
            it.name == "initV2Env"     || it.name == "newCoreController"
        } ?: run {
            val available = libCls.methods.joinToString { it.name }
            throw NoSuchMethodException("Нет фабричного метода. Доступно: $available")
        }
        Log.d(TAG, "factory=${factoryMethod.name}")

        val supportIface = factoryMethod.parameterTypes[0]

        step("Proxy.newProxyInstance")
        val proxy = Proxy.newProxyInstance(
            supportIface.classLoader,
            arrayOf(supportIface),
            XrayProtocol()
        )

        step(factoryMethod.name)
        val point = if (factoryMethod.parameterTypes.size == 1) {
            factoryMethod.invoke(null, proxy)!!
        } else {
            factoryMethod.invoke(null, proxy, false)!!
        }
        v2rayPoint = point

        // Сохраняем методы контроллера в SharedPreferences ДО запуска — переживёт краш
        val ctrlMethods = descMethods(point)
        getSharedPreferences(PREFS_DEBUG, Context.MODE_PRIVATE)
            .edit().putString(KEY_CTRL_METHODS, ctrlMethods).commit()
        Log.d(TAG, "Controller methods: $ctrlMethods")

        // ProcessFinder: нужен xray для per-app routing; регистрируем заглушку
        registerProcessFinderStub(point)

        step("startCore")
        usedNewApi = false

        // Пробуем новый API: startLoop(configContent, tunFd) — по аналогии с v2rayNG
        if (tryStartLoop(point, configJson, tunFd)) {
            usedNewApi = true
            Log.d(TAG, "Core started via startLoop (new API)")
            return
        }

        // Fallback: старый API setConfigureFileContent + runLoop
        if (tryOldApiStart(point, configJson, configPath)) {
            Log.d(TAG, "Core started via runLoop (old API)")
            return
        }

        val methods = descMethods(point)
        throw IllegalStateException("Не могу запустить ядро. Методы контроллера: $methods")
    }

    // Новый API v2rayNG: controller.startLoop(configContent: String, tunFd: Int)
    // Go-код вызывает Startup() на callback для получения TUN fd — см. XrayProtocol.
    // addDisallowedApplication(packageName) исключает наш процесс из VPN →
    // outbound-соединения xray идут мимо TUN → нет routing loop.
    private fun tryStartLoop(point: Any, configJson: String, tunFd: Int): Boolean {
        val m = point.javaClass.methods.firstOrNull { m ->
            m.name == "startLoop" && m.parameterTypes.size == 2 &&
            m.parameterTypes[0] == String::class.java
        } ?: return false

        return runCatching {
            val fdArg: Any = if (m.parameterTypes[1] == java.lang.Long.TYPE) tunFd.toLong() else tunFd
            val result = m.invoke(point, configJson, fdArg)
            Log.d(TAG, "startLoop(fd=$tunFd) OK, result=$result")
            // v2rayNG вызывает setIsRunning(true) после startLoop()
            runCatching {
                point.javaClass
                    .getMethod("setIsRunning", Boolean::class.javaPrimitiveType)
                    .invoke(point, true)
                Log.d(TAG, "setIsRunning(true) after startLoop")
            }.onFailure { Log.w(TAG, "setIsRunning: ${it.message}") }
        }.onFailure { Log.e(TAG, "startLoop threw: ${it.message}") }.isSuccess
    }

    // Старый API v2rayNG: setConfigureFileContent + runLoop(false)
    private fun tryOldApiStart(point: Any, configJson: String, configPath: String): Boolean {
        if (!hasMethod(point, "setConfigureFileContent") &&
            !hasMethod(point, "setConfigureFile") &&
            !hasMethod(point, "runLoop")) return false

        invokeConfig(point, configJson, configPath)
        invokeRunLoop(point)
        return true
    }

    private fun hasMethod(obj: Any, name: String) =
        obj.javaClass.methods.any { it.name == name }

    private fun descMethods(obj: Any) = obj.javaClass.methods
        .filterNot { it.declaringClass == Object::class.java }
        .joinToString { m -> "${m.name}(${m.parameterTypes.joinToString { p -> p.simpleName }})" }

    private fun invokeConfig(target: Any, json: String, path: String) {
        val ok = runCatching {
            target.javaClass.getMethod("setConfigureFileContent", String::class.java)
                .invoke(target, json)
        }.isSuccess || runCatching {
            target.javaClass.getMethod("setConfigureFile", String::class.java)
                .invoke(target, path)
        }.isSuccess
        if (!ok) Log.w(TAG, "config method not found, Xray reads from filesDir via initCoreEnv")
    }

    private fun invokeRunLoop(target: Any) {
        val m = runCatching { target.javaClass.getMethod("runLoop", Boolean::class.java) }
            .getOrElse { runCatching { target.javaClass.getMethod("runLoop", java.lang.Boolean::class.java) }.getOrNull() }
            ?: run {
                val found = target.javaClass.methods
                    .filter { it.name.contains("run", ignoreCase = true) || it.name.contains("start", ignoreCase = true) }
                    .joinToString { "${it.name}(${it.parameterTypes.joinToString { p -> p.simpleName }})" }
                throw NoSuchMethodException("runLoop не найден. run/start методы: $found")
            }
        m.invoke(target, false)
    }

    private fun stopXray() {
        v2rayPoint?.let { point ->
            runCatching { point.javaClass.getMethod("stopLoop").invoke(point) }
        }
        v2rayPoint = null
        usedNewApi = false
    }

    private fun registerProcessFinderStub(point: Any) {
        val m = point.javaClass.methods.firstOrNull { it.name == "registerProcessFinder" } ?: return
        if (m.parameterTypes.isEmpty()) return
        val iface = m.parameterTypes[0]
        runCatching {
            val stub = Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { _, method, _ ->
                when (method?.returnType) {
                    java.lang.Long.TYPE    -> 0L
                    java.lang.Integer.TYPE -> 0
                    java.lang.Boolean.TYPE -> false
                    else                   -> null
                }
            }
            m.invoke(point, stub)
            Log.d(TAG, "ProcessFinder stub registered")
        }.onFailure { Log.w(TAG, "registerProcessFinder: ${it.message}") }
    }

    private fun injectLogPath(config: String, logPath: String): String {
        return try {
            val json = org.json.JSONObject(config)
            val log = json.optJSONObject("log") ?: org.json.JSONObject()
            log.put("loglevel", "warning")
            log.put("error", logPath)
            // access лог — каждая строка = один проксированный запрос.
            // Если пустой после подключения — TUN-стек не передаёт пакеты в xray.
            log.put("access", logPath.replace("xray.log", "xray_access.log"))
            json.put("log", log)
            json.toString()
        } catch (_: Throwable) { config }
    }

    // Callback для libv2ray — реализуем интерфейс CoreCallbackHandler.
    //
    // ВАЖНО: Startup() должен вернуть реальный TUN fd.
    // Go-код вызывает Startup() на callback чтобы получить fd для своего TUN-стека.
    // Если вернуть 0 — Go пытается читать stdin как TUN-интерфейс → Go panic → SIGABRT.
    // v2rayNG: override fun Startup() = vpnInterfaceFd?.fd ?: -1
    //
    // Имена методов в новом API (gomobile, capital case): Startup, Shutdown, OnEmitStatus.
    // Старый API (V2RayVpnServiceSupports): protect, onEmitStatus (lowercase).
    private inner class XrayProtocol : InvocationHandler {
        override fun invoke(proxy: Any?, method: Method?, args: Array<out Any?>?): Any? {
            return when (method?.name) {
                "protect", "Protect" -> {
                    val fd = when (val a = args?.get(0)) {
                        is Long -> a.toInt()
                        is Int  -> a
                        else    -> 0
                    }
                    protect(fd)
                }
                // Возвращаем реальный TUN fd — Go-код использует его для netstack
                "Startup", "startup" -> {
                    val fd = tunFd?.fd ?: -1
                    Log.d(TAG, "Startup() → fd=$fd")
                    fd
                }
                "Shutdown", "shutdown" -> {
                    Log.d(TAG, "Shutdown() called")
                    0
                }
                "OnEmitStatus", "onEmitStatus" -> primitiveDefault(method)
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

    // ── Stderr capture ────────────────────────────────────────
    // Go panic trace пишется в stderr (fd=2), не в xray.log.
    // Перенаправляем fd=2 в файл до запуска xray — следующий запуск прочитает его.
    private fun redirectStderr() {
        try {
            val f = File(filesDir, PANIC_LOG)
            f.delete()
            val fd = Os.open(
                f.absolutePath,
                OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_TRUNC,
                0x1b4 // 0644
            )
            Os.dup2(fd, 2)
            Os.close(fd)
            Log.d(TAG, "stderr → ${f.absolutePath}")
        } catch (e: Throwable) {
            Log.w(TAG, "redirectStderr: ${e.message}")
        }
    }

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
