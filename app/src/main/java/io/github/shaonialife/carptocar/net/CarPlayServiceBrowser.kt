package io.github.shaonialife.carptocar.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

/**
 * mDNS 服务发现：找到接收端（车机/xcertplay）广播的 _airplay._tcp，
 * 解析出 AirPlay 端点与 TXT 记录（deviceid/features/pi/pk/model…）。
 *
 * 真实时序中这一步发生在加入车机热点之后、发起 AirPlay 主会话之前。
 */
class CarPlayServiceBrowser(
    context: Context,
    private val log: (String) -> Unit,
    /** 结果验收：只缓存/上报真正的车机端点（手机双归属时 mDNS 会混入其它 AirPlay 设备）。 */
    private val accept: (NsdServiceInfo) -> Boolean = { true },
    private val onEndpoint: (NsdServiceInfo) -> Unit = {},
) {
    companion object {
        const val AIRPLAY_TYPE = "_airplay._tcp"
        const val CARPLAY_CTRL_TYPE = "_carplay-ctrl._tcp"
        private const val TAG = "NSD"
    }

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    @Volatile
    private var discovery: Discovery? = null

    @Volatile
    private var resolved: NsdServiceInfo? = null
    private var resolvedKey: String? = null

    /** 跨轮保留的最后一次解析结果（仅作兜底候选，不代表本轮发现已就绪）。 */
    @Volatile
    private var lastKnown: NsdServiceInfo? = null

    @Volatile
    private var lastKnownAtMillis: Long = 0L

    // Android 12+ 每个 NsdManager 客户端仅允许单条并发 resolveService；发现重启后仍需串行排空槽位。
    private var resolving: ResolveRequest? = null
    private val pendingResolves = ArrayDeque<ResolveRequest>()

    /** 更换全新 DiscoveryListener 重新枚举服务，并使旧监听器的异步回调自动失效。 */
    @Synchronized
    fun refresh(serviceType: String = AIRPLAY_TYPE) {
        stop()
        start(serviceType)
    }

    /** 跨轮保留的最后一次解析结果（可能过期，供兜底候选使用）。 */
    fun lastKnownEndpoint(): NsdServiceInfo? = lastKnown

    /**
     * 等已解析结果（轮询缓存）。返回 null = 超时仍未发现。
     * 调用方通常在等待期间继续做别的事（绑定网络、握手），不是纯粹的阻塞 sleep。
     *
     * @param allowStale 本轮没有新结果时是否回退到 [lastKnown]（跨轮缓存）。
     *   只有「拿上轮地址试一下也比不试强」的端点解析该传 true；纯诊断性质的探测
     *   （要判断车机此刻到底有没有起 AirPlay）必须保持 false，否则会误报「已就绪」。
     */
    fun awaitEndpoint(
        timeoutMillis: Long,
        allowStale: Boolean = false,
    ): NsdServiceInfo? {
        val deadline = android.os.SystemClock.uptimeMillis() + timeoutMillis
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            resolved?.let { return it }
            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) {
                return resolved
            }
        }
        resolved?.let { return it }
        if (allowStale) {
            lastKnown?.let {
                val ageSec = (android.os.SystemClock.uptimeMillis() - lastKnownAtMillis) / 1000
                log(
                    "$TAG: 本轮无新结果——沿用上轮地址 ${it.host?.hostAddress}:${it.port}" +
                        "（${ageSec}s 前解析；仅作兜底候选，不代表本轮发现就绪）",
                )
                return it
            }
        }
        return null
    }

    @Synchronized
    fun start(serviceType: String = AIRPLAY_TYPE) {
        if (discovery != null) return
        val run = Discovery()
        discovery = run
        log("$TAG: 开始发现 $serviceType")
        try {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, run)
        } catch (error: Exception) {
            if (discovery === run) discovery = null
            log("$TAG: discoverServices 异常 - ${error.message}")
        }
    }

    @Synchronized
    fun stop() {
        val run = discovery
        discovery = null
        resolved = null
        resolvedKey = null
        pendingResolves.clear()
        if (run == null) return
        try {
            nsdManager.stopServiceDiscovery(run)
        } catch (error: Exception) {
            log("$TAG: stopServiceDiscovery 异常 - ${error.message}")
        }
        log("$TAG: 停止发现")
    }

    private fun serviceKey(info: NsdServiceInfo): String = "${info.serviceName}\u0000${info.serviceType}"

    private inner class Discovery : NsdManager.DiscoveryListener {
        val services = mutableMapOf<String, ResolveRequest>()

        override fun onStartDiscoveryFailed(
            serviceType: String,
            errorCode: Int,
        ) {
            synchronized(this@CarPlayServiceBrowser) {
                if (discovery !== this) return
                discovery = null
                resolved = null
                resolvedKey = null
                pendingResolves.clear()
                log("$TAG: 发现启动失败 code=$errorCode")
            }
        }

        override fun onStopDiscoveryFailed(
            serviceType: String,
            errorCode: Int,
        ) {
            log("$TAG: 停止失败 code=$errorCode")
        }

        override fun onDiscoveryStarted(serviceType: String) = Unit

        override fun onDiscoveryStopped(serviceType: String) {
            synchronized(this@CarPlayServiceBrowser) {
                if (discovery === this) {
                    discovery = null
                    resolved = null
                    resolvedKey = null
                    pendingResolves.clear()
                }
            }
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            synchronized(this@CarPlayServiceBrowser) {
                if (discovery !== this) return
                val request = ResolveRequest(this, serviceInfo)
                services[request.key] = request
                log("$TAG: 发现服务 ${serviceInfo.serviceName} (${serviceInfo.serviceType})，解析中…")
                pendingResolves.addLast(request)
                startNextResolve()
            }
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            synchronized(this@CarPlayServiceBrowser) {
                if (discovery !== this) return
                val key = serviceKey(serviceInfo)
                services.remove(key)
                if (resolvedKey == key) {
                    resolved = null
                    resolvedKey = null
                }
                log("$TAG: 服务消失 ${serviceInfo.serviceName}")
            }
        }
    }

    private fun startNextResolve() {
        if (resolving != null) return
        while (pendingResolves.isNotEmpty()) {
            val request = pendingResolves.removeFirst()
            if (!request.isCurrent()) continue
            resolving = request
            try {
                nsdManager.resolveService(request.serviceInfo, request)
                return
            } catch (error: Exception) {
                resolving = null
                log("$TAG: resolveService 异常 - ${error.message}")
            }
        }
    }

    private inner class ResolveRequest(
        val run: Discovery,
        val serviceInfo: NsdServiceInfo,
    ) : NsdManager.ResolveListener {
        val key = serviceKey(serviceInfo)
        private var attempts = 3

        fun isCurrent(): Boolean = discovery === run && run.services[key] === this

        override fun onResolveFailed(
            info: NsdServiceInfo,
            errorCode: Int,
        ) {
            synchronized(this@CarPlayServiceBrowser) {
                if (resolving !== this) return
                resolving = null
                if (isCurrent()) {
                    if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE && --attempts > 0) {
                        Thread(
                            {
                                try {
                                    Thread.sleep(200)
                                } catch (_: InterruptedException) {
                                    Thread.currentThread().interrupt()
                                    return@Thread
                                }
                                synchronized(this@CarPlayServiceBrowser) {
                                    if (isCurrent()) {
                                        pendingResolves.addLast(this)
                                        startNextResolve()
                                    }
                                }
                            },
                            "nsd-resolve",
                        ).start()
                    } else {
                        log("$TAG: 解析失败 ${info.serviceName} code=$errorCode")
                    }
                }
                startNextResolve()
            }
        }

        override fun onServiceResolved(info: NsdServiceInfo) {
            synchronized(this@CarPlayServiceBrowser) {
                if (resolving !== this) return
                resolving = null
                try {
                    if (!isCurrent()) return
                    val host = info.host?.hostAddress ?: "no-host"
                    val accepted = accept(info)
                    log("$TAG: OK ${info.serviceName} -> $host:${info.port}${if (accepted) "" else "（非车机，忽略）"}")
                    if (accepted) {
                        resolved = info
                        resolvedKey = key
                        lastKnown = info
                        lastKnownAtMillis = android.os.SystemClock.uptimeMillis()
                    }
                    val txt =
                        info.attributes.entries.joinToString(", ") { (name, value) ->
                            "$name=${value?.toString(Charsets.UTF_8)?.trimEnd { it.code == 0 }.orEmpty()}"
                        }
                    if (txt.isNotEmpty()) log("$TAG: TXT $txt")
                    onEndpoint(info)
                } finally {
                    startNextResolve()
                }
            }
        }
    }
}
