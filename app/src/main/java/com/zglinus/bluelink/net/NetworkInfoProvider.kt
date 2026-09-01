package com.zglinus.bluelink.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.util.Log
import com.zglinus.bluelink.ble.NetInfo
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 网络信息采集（一期）：
 * - ConnectivityManager 判 Wi-Fi / 蜂窝（activeNetwork + NetworkCapabilities）；
 * - WifiManager 拿 SSID（Android 12+ 未授权定位时可能拿不到，允许为空）；
 * - LinkProperties / NetworkInterface 拿 IPv4 与子网掩码（前缀长度转点分十进制）；
 * - 蜂窝时 ip 可空（握手字段允许空）。
 */
data class NetworkSummary(
    val wifi: Boolean = false,
    val ssid: String? = null,
    val ip: String? = null,
    val mask: String? = null,
    val cellular: Boolean = false,
) {
    val connected: Boolean get() = wifi || cellular

    /** 状态卡摘要文案。 */
    fun describe(): String = when {
        !connected -> "未连接网络"
        wifi && ssid != null && ip != null && mask != null -> "Wi-Fi · $ssid · $ip/$mask"
        wifi && ip != null && mask != null -> "Wi-Fi · $ip/$mask"
        wifi && ssid != null -> "Wi-Fi · $ssid"
        wifi -> "Wi-Fi"
        cellular -> "蜂窝网络"
        else -> "未知网络"
    }
}

object NetworkInfoProvider {
    private const val TAG = "NetworkInfoProvider"

    fun collect(context: Context): NetworkSummary {
        val appContext = context.applicationContext
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return NetworkSummary()

        val active = cm.activeNetwork
        val caps = active?.let { cm.getNetworkCapabilities(it) }
        val wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val cellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true

        var ssid: String? = null
        if (wifi) {
            ssid = try {
                val wm = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                @Suppress("DEPRECATION")
                wm?.connectionInfo?.ssid
                    ?.removeSurrounding("\"")
                    ?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
            } catch (e: Exception) {
                Log.w(TAG, "获取 SSID 失败: $e")
                null
            }
        }

        var ip: String? = null
        var mask: String? = null

        // 优先走 LinkProperties：activeNetwork 的 linkAddress（地址 + 前缀长度 → 掩码）
        val lp = active?.let { cm.getLinkProperties(it) }
        lp?.linkAddresses?.forEach { la ->
            val addr = la.address
            if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                ip = addr.hostAddress
                mask = prefixToMask(la.prefixLength)
            }
        }

        // 兜底：遍历所有已启用的网络接口
        if (ip == null) {
            try {
                NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { ni ->
                    if (!ni.isUp || ni.isLoopback) return@forEach
                    // 单 lambda + 局部变量：is 检查作用于局部 val addr，不依赖跨 lambda 智能转换
                    val v4 = ni.interfaceAddresses.firstOrNull { a ->
                        val addr = a.address
                        addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress
                    }
                    if (v4 != null) {
                        ip = v4.address.hostAddress
                        mask = prefixToMask(v4.networkPrefixLength.toInt())
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "遍历网络接口失败: $e")
            }
        }

        return NetworkSummary(wifi = wifi, ssid = ssid, ip = ip, mask = mask, cellular = cellular)
    }

    /** 前缀长度 → 点分十进制子网掩码。 */
    fun prefixToMask(prefix: Int): String {
        val bits = prefix.coerceIn(0, 32)
        val m = if (bits == 0) 0L else (0xFFFFFFFFL shl (32 - bits)) and 0xFFFFFFFFL
        return "${(m shr 24) and 0xFF}.${(m shr 16) and 0xFF}.${(m shr 8) and 0xFF}.${m and 0xFF}"
    }
}

/** 握手 net 字段 → 本机网络摘要（同网判定 / 弹层展示共用同一结构）。 */
fun NetInfo.toNetworkSummary(): NetworkSummary =
    NetworkSummary(wifi = wifi, ssid = ssid, ip = ip, mask = mask, cellular = cellular)
