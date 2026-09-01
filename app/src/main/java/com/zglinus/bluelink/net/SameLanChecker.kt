package com.zglinus.bluelink.net

import android.util.Log
import com.zglinus.bluelink.ble.Constants

/** 同网判定结果（[check] 兼容包装用；判定语义已收敛到 [isSameLan]）。 */
enum class LanStatus { SAME_LAN, DIFFERENT_NETWORK, UNKNOWN }

/**
 * 同网判定（v0.4.0 修复：拆出两个判定——[isSameLan] 纯子网比较 + [probeTcp] 辅助探测）。
 *
 * 规则：
 * - [isSameLan]：仅子网/IP 前缀比较，**无任何网络 IO**——双方 IP 与掩码齐全时
 *   (IP & mask) 相等为 true，否则 false（含信息不足）；任一方蜂窝 → false；
 * - [probeTcp]：TCP 连通性辅助探测——**当前仅辅助、不参与判定**；待 LocalSend 服务
 *   （端口 [Constants.DEFAULT_TCP_PROBE_PORT]=53317）在两端真正监听后，再启用为「必过项」；
 *   当前实现不发起真实连接（53317 暂无服务监听，connect 必败且复核运行在主线程会阻塞 UI），
 *   恒返回 false 仅供日志参考；
 * - [check]：兼容包装（UI 状态卡 lanStatus / 网段差异按钮仍消费 LanStatus），语义同 [isSameLan]，
 *   信息不足返回 UNKNOWN。
 */
object SameLanChecker {
    private const val TAG = "SameLanChecker"

    /**
     * 同网判定（仅子网/IP 前缀比较，无网络 IO；v0.4.0 起为组网 TRANSPORT 前的**通过条件**）：
     * - 任一方蜂窝 → false（蜂窝网段不可靠/不可直连）；
     * - 本机 ip/mask 或对端 ip 缺失 → false（信息不足，不猜测通过）；
     * - 对端掩码缺失（如 joined 载荷仅携带 IP）→ 按本机掩码同粒度比较
     *   （热点 DHCP 分配给对端的网段与本机热点接口网段一致，见 HotspotManager.collectHotspotIp）；
     * - 返回 (mine.ip & mask) == (peer.ip & mask)。
     */
    fun isSameLan(mine: NetworkSummary, peer: NetworkSummary): Boolean {
        if (mine.cellular || peer.cellular) return false
        val myIp = ipToInt(mine.ip) ?: return false
        val peerIp = ipToInt(peer.ip) ?: return false
        val myMask = ipToInt(mine.mask) ?: return false
        // 对端掩码缺失 → 按本机掩码同粒度比较（热点场景两端掩码一致）
        val peerMask = ipToInt(peer.mask) ?: myMask
        return (myIp and myMask) == (peerIp and peerMask)
    }

    /**
     * 兼容包装（UI 状态卡 lanStatus / 网段差异按钮仍消费 LanStatus）：语义同 [isSameLan]；
     * 本机 ip/mask 或对端 ip 缺失 → UNKNOWN；蜂窝 → DIFFERENT_NETWORK。
     * 组网状态机不复用本方法（以 [isSameLan] 为通过条件）。
     */
    fun check(local: NetworkSummary, remote: NetworkSummary): LanStatus {
        if (local.cellular || remote.cellular) {
            return LanStatus.DIFFERENT_NETWORK
        }
        if (local.ip == null || local.mask == null || remote.ip == null) {
            return LanStatus.UNKNOWN
        }
        return if (isSameLan(local, remote)) LanStatus.SAME_LAN else LanStatus.DIFFERENT_NETWORK
    }

    /**
     * TCP 连通性探测（保留接口；**当前仅辅助，不参与判定**——待 LocalSend 服务
     * （[Constants.DEFAULT_TCP_PROBE_PORT]=53317）在两端真正监听后，再启用为「必过项」）。
     *
     * 当前实现不发起真实连接：53317 暂为占位、无服务监听，connect 必败；且复核运行在主线程
     * （onJoined），真实探测会阻塞 UI（ANR 风险，与工程 ANR 修复方向冲突）。
     * 恒返回 false，结果仅供同网复核日志参考（状态机「仅记日志，不阻断 TRANSPORT」）。
     */
    fun probeTcp(
        host: String,
        targetPort: Int = Constants.DEFAULT_TCP_PROBE_PORT,
        timeoutMs: Long = 2_000L,
    ): Boolean {
        Log.d(TAG, "probeTcp($host:$targetPort) 辅助探测未启用（待 LocalSend 服务监听后启用为必过），返回 false")
        return false
    }

    /** 子网描述（复核日志用）：ip/mask 齐全 → "ip/mask"，否则 "未知"。 */
    fun describeSubnet(ip: String?, mask: String?): String =
        if (!ip.isNullOrBlank() && !mask.isNullOrBlank()) "$ip/$mask" else "未知"

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
