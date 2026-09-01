package com.zglinus.bluelink.net

import android.Manifest
import androidx.annotation.RequiresApi
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.zglinus.bluelink.diag.DiagLogger
import java.net.Inet4Address
import java.util.Locale

/**
 * 对端接入器（A4）：把对端 offer 的 (ssid, pwd) 实际接入本机 Wi-Fi，并把结果经 [Callbacks] 回调出去。
 *
 * 双路径分流（按任务约定）：
 * - **Android 11+（API 29+）**：[WifiNetworkSpecifier]（[WifiNetworkSpecifier.Builder.setSsid] +
 *   [WifiNetworkSpecifier.Builder.setWpa2Passphrase]，pwd 为空则仅 SSID 匹配开放网络）→
 *   权限前置（13+ 需 NEARBY_WIFI_DEVICES / 29–32 需 ACCESS_FINE_LOCATION，缺失回调 onNeedPermission）→
 *   [ConnectivityManager.requestNetwork] —— **系统弹窗由用户确认**；
 *   [ConnectivityManager.NetworkCallback.onAvailable] → 接入成功（延迟取 IP），
 *   [ConnectivityManager.NetworkCallback.onUnavailable] → 失败；
 * - **Android 8–10（API 26–28）**：先检查 `Settings.System.canWrite`（WRITE_SETTINGS 授权），
 *   未授权回调 [Callbacks.onNeedWriteSettingsPermission]（上层引导
 *   `Settings.ACTION_MANAGE_WRITE_SETTINGS` 后重新 [join]）；已授权则
 *   [WifiManager.addNetwork] + [WifiManager.enableNetwork]（**不强开** Wi-Fi，即不调
 *   [WifiManager.setWifiEnabled]）→ 轮询 [android.net.wifi.WifiInfo] 确认 SSID 匹配
 *   （≤10s）→ 成功 / 超时失败；
 * - **root 静默接入：stub（B 包）**——仅注释占位（见 [joinWithRootStub]）。
 *
 * 接入成功后取本机新 IPv4（复用 [NetworkInfoProvider.collect]，并以 specifier 网络的
 * LinkProperties / [android.net.wifi.WifiInfo.getIpAddress] 转换兜底）；断开非目标 Wi-Fi
 * 的提示由上层处理（本类不主动断网、不提示）。
 *
 * 解耦：本类只把结果经 [Callbacks] 回调出去，**不直接调状态机**；由上层接线方把
 * `onJoined/onFailed` 转发给 `NetworkingStateMachine.onWifiJoined/onWifiJoinFailed`。
 *
 * 线程模型：与状态机一致——[join] 需在主线程调用；API 29+ 的 NetworkCallback 经
 * `requestNetwork(..., mainHandler)` 指定主线程派发，SSID/IP 轮询亦走主 Handler，
 * 因此全部 [Callbacks] 都在主线程回调。
 *
 * 错误处理：全链路 try/catch + [DiagLogger]；[join] 幂等（有进行中的接入时忽略新调用）。
 */
class WifiJoiner(private val context: Context) {

    /**
     * 接入结果回调（由上层接线方消费，转发给状态机 `onWifiJoined / onWifiJoinFailed`）。
     */
    interface Callbacks {
        /**
         * 接入成功。
         *
         * @param ip 本机新 IPv4；延迟取 IP 超时未取到时为空串
         *   （状态机同网复核对空 IP 按 UNKNOWN 占位通过）。
         */
        fun onJoined(ip: String)

        /**
         * 接入失败（reason 见各路径：如「系统弹窗未确认」「10s 内未连上目标 Wi-Fi」）。
         */
        fun onFailed(reason: String)

        /**
         * Android 8–10 路径需要 WRITE_SETTINGS 授权：
         * 上层引导用户到 `Settings.ACTION_MANAGE_WRITE_SETTINGS`，授权后重新调用 [join]。
         */
        fun onNeedWriteSettingsPermission()

        /**
         * API 29+（Specifier）路径需要运行时权限（缺失时 requestNetwork 会抛 SecurityException，
         * 真机实锤：Android 12 未授 ACCESS_FINE_LOCATION、Android 13+ 未授 NEARBY_WIFI_DEVICES）：
         * 上层发起系统授权，授权成功后重新调用 [join]（join 幂等，可安全重试）。
         *
         * @param permission 缺失的权限：Android 13+ 为 `Manifest.permission.NEARBY_WIFI_DEVICES`，
         *   Android 12 及以下（29–32）为 `Manifest.permission.ACCESS_FINE_LOCATION`。
         */
        fun onNeedPermission(permission: String)
    }

    private val tag = "WifiJoiner"

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private val wifiManager: WifiManager?
        get() = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private val connectivityManager: ConnectivityManager?
        get() = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    /** 当前进行中的接入尝试（非空即视为进行中，[join] 幂等判定）。 */
    @Volatile
    private var current: Attempt? = null

    /**
     * API 29+ 路径已注册的 NetworkCallback。
     *
     * 说明：成功路径**不注销**——WifiNetworkSpecifier 是 on-demand 网络，注销会触发系统断开该
     * Wi-Fi；保持注册以维持对端接入期间的连接，会话结束时由 [cancel] 统一释放。
     */
    @Volatile
    private var registeredCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * 接入对端 Wi-Fi（幂等：已有进行中的接入时忽略，仅记录日志）。
     *
     * @param ssid 目标 SSID。
     * @param pwd 密码；空串视为开放网络（如 L2 本地热点无密码），仅日志记长度不回显明文。
     * @param callbacks 结果回调（主线程）。
     */
    fun join(ssid: String, pwd: String, callbacks: Callbacks) {
        val s = ssid.trim()
        if (s.isBlank()) {
            DiagLogger.log(tag, "join 参数非法：SSID 为空，直接失败")
            callbacks.onFailed("SSID 为空")
            return
        }
        if (current != null) {
            DiagLogger.log(tag, "join($s) 忽略：已有进行中的接入（幂等）")
            return
        }
        val attempt = Attempt(s, pwd.trim().takeIf { it.isNotEmpty() }, callbacks)
        current = attempt
        DiagLogger.log(tag, "join：ssid=$s pwdLen=${attempt.pwd?.length ?: 0} sdk=${Build.VERSION.SDK_INT}")
        try {
            when {
                Build.VERSION.SDK_INT >= 29 -> joinWithSpecifier(attempt)
                Build.VERSION.SDK_INT >= 26 -> joinLegacy(attempt)
                else -> fail(attempt, "不支持的 Android 版本（sdk=${Build.VERSION.SDK_INT}，minSdk=26）")
            }
        } catch (e: Exception) {
            fail(attempt, "join 异常: $e")
        }
    }

    /**
     * 取消进行中的接入 / 释放成功路径残留的 NetworkCallback（幂等，不回调结果）。
     *
     * 供上层在状态机中止、切角色或传输会话结束时调用：避免系统弹窗悬挂、以及
     * on-demand 网络长期占用。
     */
    fun cancel() {
        val attempt = current
        if (attempt != null) {
            DiagLogger.log(tag, "cancel：中止进行中的接入 ssid=${attempt.ssid}")
            complete(attempt) { releaseRequest(attempt) }
            return
        }
        val cb = registeredCallback
        if (cb != null) {
            registeredCallback = null
            try {
                connectivityManager?.unregisterNetworkCallback(cb)
            } catch (e: Exception) {
                DiagLogger.log(tag, "cancel 注销 NetworkCallback 异常（忽略）: $e")
            }
            DiagLogger.log(tag, "cancel：释放成功路径残留的 NetworkCallback")
        }
    }

    // ---------- Android 11+（API 29+）：WifiNetworkSpecifier + 系统弹窗 ----------

    /**
     * API 29+ 路径：构造 WifiNetworkSpecifier → requestNetwork，系统弹窗由用户确认。
     *
     * - onAvailable：接入成功，延迟取 IP（DHCP/地址分配可能有延迟，轮询 [IP_POLL_TIMEOUT_MS]）；
     * - onUnavailable：系统弹窗未确认 / 请求失败；
     * - onLost：仅记录（传输层已按成功上报，是否降级由上层决定）。
     *
     * 成功路径保持注册（见 [registeredCallback] 注释），[cancel] 时统一注销。
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun joinWithSpecifier(attempt: Attempt) {
        // 权限前置（真机实锤）：API 29+ 路径缺失运行时权限时 requestNetwork 直接抛 SecurityException。
        // Android 13+ 需 NEARBY_WIFI_DEVICES；Android 12 及以下（29–32）需 ACCESS_FINE_LOCATION。
        // 缺失 → 回调 onNeedPermission（不调 requestNetwork），由上层发起系统授权，授权后重新 join。
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (appContext.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            DiagLogger.log(
                tag,
                "API 29+ 路径：缺少运行时权限 $permission，回调 onNeedPermission（不调 requestNetwork）",
            )
            complete(attempt) { attempt.callbacks.onNeedPermission(permission) }
            return
        }
        DiagLogger.log(tag, "API 29+ 路径：运行时权限 $permission 已授权，继续 Specifier 流程")
        val cm = connectivityManager
        if (cm == null) {
            fail(attempt, "ConnectivityManager 不可用")
            return
        }
        val specifier = try {
            WifiNetworkSpecifier.Builder()
                .setSsid(attempt.ssid)
                .apply { attempt.pwd?.let { setWpa2Passphrase(it) } } // pwd 为空 → 仅 SSID（开放网络）
                .build()
        } catch (e: Exception) {
            fail(attempt, "构造 WifiNetworkSpecifier 失败: $e")
            return
        }
        val request = try {
            NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .setNetworkSpecifier(specifier)
                .build()
        } catch (e: Exception) {
            fail(attempt, "构造 NetworkRequest 失败: $e")
            return
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                DiagLogger.log(tag, "onAvailable：目标 Wi-Fi 可用（network=$network），延迟取 IP")
                scheduleIpPoll(attempt, network = network)
            }

            override fun onUnavailable() {
                DiagLogger.log(tag, "onUnavailable：系统弹窗未确认或请求失败")
                fail(attempt, "系统弹窗未确认或请求失败（onUnavailable）")
            }

            override fun onLost(network: Network) {
                DiagLogger.log(tag, "onLost($network)：目标 Wi-Fi 连接已丢失（传输层已按成功上报，仅记录）")
            }
        }
        try {
            cm.requestNetwork(request, callback, mainHandler)
        } catch (e: Exception) {
            fail(attempt, "requestNetwork 异常: $e")
            return
        }
        // requestNetwork 同步注册成功后才登记引用（回调经 mainHandler 异步派发，无竞争）
        attempt.networkCallback = callback
        registeredCallback = callback
        DiagLogger.log(tag, "requestNetwork 已注册：等待系统弹窗确认（ssid=${attempt.ssid}）")
    }

    // ---------- Android 8–10（API 26–28）：WRITE_SETTINGS + WifiManager.addNetwork ----------

    /**
     * API 26–28 路径：WRITE_SETTINGS 授权校验 → addNetwork + enableNetwork(netId, true)
     * （不强开 Wi-Fi）→ 轮询 WifiInfo 确认 SSID 匹配（≤10s）。
     *
     * 断开当前连接改连目标网络由 enableNetwork(disconnectOthers=true) 完成；
     * 「将断开非目标 Wi-Fi」的提示由上层负责（本类不提示）。
     */
    @Suppress("DEPRECATION") // addNetwork/enableNetwork/isWifiEnabled 为 26–28 路径唯一可用通道
    private fun joinLegacy(attempt: Attempt) {
        if (!Settings.System.canWrite(appContext)) {
            DiagLogger.log(tag, "Android 8–10 路径：无 WRITE_SETTINGS 授权，回调 onNeedWriteSettingsPermission()")
            complete(attempt) { attempt.callbacks.onNeedWriteSettingsPermission() }
            return
        }
        val wm = wifiManager
        if (wm == null) {
            fail(attempt, "WifiManager 不可用")
            return
        }
        if (!wm.isWifiEnabled) {
            fail(attempt, "Wi-Fi 未开启（Android 8–10 路径不强开 Wi-Fi，请先手动开启）")
            return
        }
        val config = try {
            buildWifiConfiguration(attempt.ssid, attempt.pwd)
        } catch (e: Exception) {
            fail(attempt, "构造 WifiConfiguration 失败: $e")
            return
        }
        val netId = try {
            wm.addNetwork(config)
        } catch (e: Exception) {
            fail(attempt, "addNetwork 异常: $e")
            return
        }
        if (netId < 0) {
            fail(attempt, "addNetwork 失败（netId=$netId）")
            return
        }
        val enabled = try {
            wm.enableNetwork(netId, true)
        } catch (e: Exception) {
            fail(attempt, "enableNetwork 异常: $e")
            return
        }
        if (!enabled) {
            fail(attempt, "enableNetwork($netId, disconnectOthers=true) 失败")
            return
        }
        DiagLogger.log(tag, "addNetwork + enableNetwork 成功（netId=$netId），轮询 WifiInfo 确认 SSID 匹配（≤10s）")
        pollSsidMatch(attempt, deadlineMs = System.currentTimeMillis() + SSID_MATCH_TIMEOUT_MS)
    }

    @Suppress("DEPRECATION")
    private fun buildWifiConfiguration(ssid: String, pwd: String?): WifiConfiguration {
        val config = WifiConfiguration()
        config.SSID = "\"$ssid\""
        if (pwd == null) {
            // 开放网络（L2 本地热点无密码）
            config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
        } else {
            // WPA2/WPA-PSK
            config.preSharedKey = "\"$pwd\""
            config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
        }
        return config
    }

    /** 轮询 WifiInfo 确认 SSID 匹配（≤10s，超时失败）。 */
    private fun pollSsidMatch(attempt: Attempt, deadlineMs: Long) {
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                if (attempt.done) return
                val nowSsid = currentSsid()
                if (nowSsid == attempt.ssid) {
                    DiagLogger.log(tag, "SSID 已匹配（$nowSsid），轮询取新 IP")
                    scheduleIpPoll(attempt, network = null)
                    return
                }
                if (System.currentTimeMillis() >= deadlineMs) {
                    fail(attempt, "10s 内未连上目标 Wi-Fi（当前 SSID=${nowSsid ?: "<未知>"}）")
                    return
                }
                mainHandler.postDelayed(this, SSID_POLL_INTERVAL_MS)
            }
        }, SSID_POLL_INTERVAL_MS)
    }

    @Suppress("DEPRECATION") // WifiInfo.ssid 读取（Android 12+ 未授权定位时可能拿不到，返回 null 属正常）
    private fun currentSsid(): String? {
        val wm = wifiManager ?: return null
        return try {
            wm.connectionInfo.ssid
                ?.removeSurrounding("\"")
                ?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
        } catch (e: Exception) {
            DiagLogger.log(tag, "读取 WifiInfo.ssid 失败: $e")
            null
        }
    }

    // ---------- 接入成功：延迟取 IP ----------

    /**
     * 接入成功后轮询取本机新 IPv4（最长 [IP_POLL_TIMEOUT_MS]，取到即上报成功；
     * 超时未取到按空 IP 上报——状态机同网复核对空 IP 按占位通过）。
     */
    private fun scheduleIpPoll(attempt: Attempt, network: Network?) {
        val deadline = System.currentTimeMillis() + IP_POLL_TIMEOUT_MS
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                if (attempt.done) return
                val ip = fetchCurrentIp(attempt.ssid, network)
                if (ip != null) {
                    reportJoined(attempt, ip)
                    return
                }
                if (System.currentTimeMillis() >= deadline) {
                    DiagLogger.log(tag, "取 IP 超时（${IP_POLL_TIMEOUT_MS / 1000}s）：未取到 IPv4，按空 IP 上报")
                    reportJoined(attempt, "")
                    return
                }
                mainHandler.postDelayed(this, IP_POLL_INTERVAL_MS)
            }
        }, IP_POLL_INTERVAL_MS)
    }

    /**
     * 取本机当前 IPv4（按优先级，均与目标 SSID 校验）：
     * 1) API 29+ 路径：specifier 网络自身的 LinkProperties（最准，DHCP 未完成时可能为空）；
     * 2) 复用 [NetworkInfoProvider.collect]（activeNetwork；蜂窝默认时可能取不到 → 走 3)）；
     * 3) 兜底：[WifiInfo.getIpAddress]（Int，主机序）转点分十进制。
     */
    @Suppress("DEPRECATION") // WifiInfo.getIpAddress() 转换兜底
    private fun fetchCurrentIp(ssid: String, network: Network?): String? {
        // 1) specifier 网络自身的 LinkProperties
        if (network != null) {
            try {
                connectivityManager?.getLinkProperties(network)?.linkAddresses?.forEach { la ->
                    val addr = la.address
                    if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                        return addr.hostAddress
                    }
                }
            } catch (e: Exception) {
                DiagLogger.log(tag, "getLinkProperties(network) 失败: $e")
            }
        }
        // 2) 复用 NetworkInfoProvider（同包）
        val summary = NetworkInfoProvider.collect(appContext)
        if (summary.wifi && summary.ip != null && (summary.ssid == null || summary.ssid == ssid)) {
            return summary.ip
        }
        // 3) WifiInfo.ipAddress 转换兜底（connectionInfo 反映当前 Wi-Fi 连接，与默认路由无关）
        val wm = wifiManager ?: return null
        val info = try {
            wm.connectionInfo
        } catch (e: Exception) {
            DiagLogger.log(tag, "读取 WifiInfo 失败: $e")
            null
        } ?: return null
        val infoSsid = info.ssid
            ?.removeSurrounding("\"")
            ?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
        val ipInt = info.ipAddress
        if (ipInt == 0 || (infoSsid != null && infoSsid != ssid)) return null
        return formatIp(ipInt)
    }

    /** WifiInfo.ipAddress（Int，主机序）→ 点分十进制。 */
    private fun formatIp(ip: Int): String =
        String.format(Locale.US, "%d.%d.%d.%d", ip and 0xFF, (ip shr 8) and 0xFF, (ip shr 16) and 0xFF, (ip shr 24) and 0xFF)

    // ---------- 结果收敛 ----------

    /** 终止尝试：标记完成并清空 [current]（幂等，先到者生效）。 */
    private fun complete(attempt: Attempt, block: () -> Unit) {
        if (attempt.done) return
        attempt.done = true
        if (current === attempt) current = null
        block()
    }

    /** 上报成功：成功路径不注销 NetworkCallback（维持 on-demand 网络），见 [registeredCallback]。 */
    private fun reportJoined(attempt: Attempt, ip: String) {
        complete(attempt) {
            DiagLogger.log(tag, "接入成功：ssid=${attempt.ssid} ip=${ip.ifEmpty { "<空>" }}")
            attempt.callbacks.onJoined(ip)
        }
    }

    /** 上报失败：注销 NetworkCallback（失败即释放请求），回调 [Callbacks.onFailed]。 */
    private fun fail(attempt: Attempt, reason: String) {
        complete(attempt) {
            releaseRequest(attempt)
            DiagLogger.log(tag, "接入失败：$reason")
            attempt.callbacks.onFailed(reason)
        }
    }

    /** 注销尝试关联的 NetworkCallback（未注册 / 已自动注销时忽略异常）。 */
    private fun releaseRequest(attempt: Attempt) {
        val cb = attempt.networkCallback ?: return
        attempt.networkCallback = null
        if (registeredCallback === cb) registeredCallback = null
        try {
            connectivityManager?.unregisterNetworkCallback(cb)
        } catch (e: Exception) {
            DiagLogger.log(tag, "注销 NetworkCallback 异常（忽略）: $e")
        }
    }

    // ---------- root 静默接入：stub（B 包） ----------

    /**
     * root 静默接入（B 包范围，本包仅占位）：
     * TODO(B包): su 通道直接向 wpa_supplicant / wificond 下发 PSK 并触发连接（免系统弹窗、
     *   免 WRITE_SETTINGS），真实现按机型实测替换。本包不实现，[join] 按双路径
     *   （API 29+ specifier / API 26–28 WRITE_SETTINGS）分流，root 通道留待 B 包接入。
     */
    @Suppress("unused")
    private fun joinWithRootStub() {
        // 占位：B 包实现 root 静默接入
    }

    /** 单次接入尝试（幂等判定 + 终止清理）。 */
    private class Attempt(
        val ssid: String,
        val pwd: String?,
        val callbacks: Callbacks,
    ) {
        /** 是否已终止（onAvailable/onUnavailable/超时/异常 任一先到者生效）。 */
        @Volatile
        var done: Boolean = false

        /** API 29+ 路径注册的 NetworkCallback（失败/取消时注销，成功时保留至 [WifiJoiner.cancel]）。 */
        var networkCallback: ConnectivityManager.NetworkCallback? = null
    }

    companion object {
        /** SSID 匹配轮询间隔。 */
        private const val SSID_POLL_INTERVAL_MS: Long = 500L

        /** API 26–28 路径确认 SSID 匹配的最长等待（任务约定 ≤10s）。 */
        private const val SSID_MATCH_TIMEOUT_MS: Long = 10_000L

        /** 接入成功后取新 IP 的轮询间隔。 */
        private const val IP_POLL_INTERVAL_MS: Long = 500L

        /** 接入成功后取新 IP 的最长等待（对端加入后状态机 WAIT_JOIN 15s 内有充足余量）。 */
        private const val IP_POLL_TIMEOUT_MS: Long = 5_000L
    }
}
