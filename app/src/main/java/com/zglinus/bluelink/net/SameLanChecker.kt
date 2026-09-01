package com.zglinus.bluelink.net

import android.util.Log
import com.zglinus.bluelink.ble.Constants

/** 同网判定结果。 */
enum class LanStatus { SAME_LAN, DIFFERENT_NETWORK, UNKNOWN }

/**
 * 同网判定（一期：仅子网比较，不做 TCP 实测）。
 *
 * 规则：
 * - 双方都连 Wi-Fi 且 IP 与掩码齐全 → 双方 (IP & mask) 相等为 SAME_LAN，否则 DIFFERENT_NETWORK；
 * - 任一方蜂窝 → DIFFERENT_NETWORK（蜂窝网段不可靠/不可直连）；
 * - 信息不足（ip/mask 缺失）→ UNKNOWN。
 *
 * TCP 探测接口（[probeTcp]，targetPort 参数默认 [Constants.DEFAULT_TCP_PROBE_PORT]）
 * 一期只留接口不实际执行。
 */
object SameLanChecker {
    private const val TAG = "SameLanChecker"

    fun check(local: NetworkSummary, remote: NetworkSummary): LanStatus {
        if (local.cellular || remote.cellular) {
            return LanStatus.DIFFERENT_NETWORK
        }
        val localIp = ipToInt(local.ip) ?: return LanStatus.UNKNOWN
        val localMask = ipToInt(local.mask) ?: return LanStatus.UNKNOWN
        val remoteIp = ipToInt(remote.ip) ?: return LanStatus.UNKNOWN
        val remoteMask = ipToInt(remote.mask) ?: return LanStatus.UNKNOWN

        val localNet = localIp and localMask
        val remoteNet = remoteIp and remoteMask
        return if (localNet == remoteNet) LanStatus.SAME_LAN else LanStatus.DIFFERENT_NETWORK
    }

    /**
     * TCP 连通性探测（预留接口，一期不实际执行）。
     *
     * TODO(二期): 用 Socket 连通实测替换/补充子网判定——同网段但被 AP 隔离的设备
     *  应判 DIFFERENT_NETWORK；异网段但实际可达的设备应判 SAME_LAN。
     */
    fun probeTcp(
        host: String,
        targetPort: Int = Constants.DEFAULT_TCP_PROBE_PORT,
        timeoutMs: Long = 2_000L,
    ): Boolean {
        Log.d(TAG, "probeTcp($host:$targetPort) 一期不执行 TCP 探测，返回 false")
        return false
    }

    private fun ipToInt(s: String?): Int? {
        if (s == null) return null
        val parts = s.split('.')
        if (parts.size != 4) return null
        var v = 0
        for (p in parts) {
            val b = p.toIntOrNull() ?: return null
            if (b !in 0..255) return null
            v = (v shl 8) or b
        }
        return v
    }
}
