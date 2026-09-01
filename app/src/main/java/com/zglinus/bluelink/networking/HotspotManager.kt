package com.zglinus.bluelink.networking

import android.content.Context
import android.os.Build
import com.zglinus.bluelink.ble.RootDetector
import com.zglinus.bluelink.diag.DiagLogger
import java.security.SecureRandom
import java.util.Locale

/**
 * 热点等级（A3b 热启动维度）：
 * ①② 自动热点（L1）细分 root 通道 / 私有 API 通道，③ L2 本地热点（无密码局域网），④ 手动配网。
 *
 * 命名收敛（A3c）：本枚举原名 `HotspotLevel`，与同包 [Arbiter] 内已存在的 `HotspotLevel`
 * （L1_AUTO/L2_LOCAL_ONLY/MANUAL）同名，两处声明处于同一包
 * （com.zglinus.bluelink.networking），构成 Kotlin 重名声明（编译期 redeclaration）。
 * 按收敛方案将**本侧更名为 [HotspotStartLevel]**（Arbiter 侧不动），值与语义不变：
 * [HotspotStartLevel.L1_ROOT] / [HotspotStartLevel.L1_PRIVATE_API] 对应仲裁 L1_AUTO，
 * [HotspotStartLevel.L2_LOCAL_ONLY] 对应仲裁 L2_LOCAL_ONLY，[HotspotStartLevel.MANUAL] 对应仲裁 MANUAL。
 */
enum class HotspotStartLevel {
    /** ① L1 自动热点：root 通道（su 提权创建系统热点；B1 已实现真路径，全矩阵穷举）。 */
    L1_ROOT,

    /** ② L1 自动热点：私有 API 通道（反射系统隐藏接口；一期按 `sdkInt in 26..33` 可尝试启发，与 [Arbiter] 一致；真实可行性由 B 包反射 try 实测收口）。 */
    L1_PRIVATE_API,

    /** ③ L2 本地热点：Local-only 无密码局域网（Android 8-9 或 13+，10-12 盲区禁用）。 */
    L2_LOCAL_ONLY,

    /** ④ 手动配网：UI 提示用户手工输入/分享热点。 */
    MANUAL,
}

/**
 * 热点启动结果。
 *
 * @param success 是否成功开启热点；false 时 [error] 给出降级/等待原因。
 * @param ssid 热点 SSID（成功时返回，供对端连接；手动路径由 UI 回填）。
 * @param pwd 热点密码（成功时返回；root 路径由本包自设随机密码；手动路径由 UI 回填）。
 * @param ip 热点本机 IPv4（B1：root 真热点启动后采集，供 offer 携带；未取到为空串 ""，一期允许）。
 * @param error 失败/等待原因（如 "本包实现(B包)" 降级、root 全矩阵失败聚合串、`"AwaitingManual"` 等待手动）。
 */
data class HotspotResult(
    val success: Boolean,
    val ssid: String? = null,
    val pwd: String? = null,
    val ip: String? = null,
    val error: String? = null,
)

/**
 * 热点生命周期回调（由 UI / 引擎实现并注入 [HotspotManager]）。
 */
interface HotspotListener {
    /** ④ 手动路径：请求 UI 引导用户手动配网（用户输入密码后回填并经 [HotspotManager.setPassword] 登记）。 */
    fun onManualRequest()

    /** 热点就绪（④ 由 UI 触发、回填密码后走 ready，携带最终 [HotspotResult]）。 */
    fun onHotspotReady(result: HotspotResult)
}

/**
 * 热启动管理器（A3b，单文件瘦身版；① root 真热点经 [RootSoftAp] 全矩阵穷举执行）。
 *
 * 对应设计文档 docs/networking.md §2「热点角色仲裁」：仲裁器 [Arbiter] 决策 who/level 后，
 * 由本管理器按 [HotspotStartLevel] 实际启动热点。
 *
 * - ①（[HotspotStartLevel.L1_ROOT]，B1 真实现）：
 *   - 前置：复用 [ble.HandshakeProtocol] 内 [RootDetector.isRoot]（应用启动时后台探测缓存），
 *     false → 如实返回失败，交状态机降级试下一级 ②；
 *   - root 为 true 后：委托 [RootSoftAp.start] 执行**配置 × 启动双矩阵穷举**——
 *     配置矩阵 A1 apex WifiConfigStore.xml / A2 传统 WifiConfigStore.xml / A3 softap.conf /
 *     A4 cmd wifi set-softap 系列 / A5 反射 setSoftApConfiguration（写 SSID=Bluelink-XXXX 与随机
 *     8 位密码）；启动矩阵 B1 cmd wifi start-softap / B2 cmd wifi set-softap enabled /
 *     B3 反射 setWifiApEnabled / B4 LocalSocket @android:wpa_wlan0 / B5 service call wifi；
 *     每个启动尝试后延时 600ms 走校验矩阵（cmd wifi status / dumpsys softap / ip link ap 接口 /
 *     isWifiApEnabled 反射），任一判定 started 即成功；不再按 `sdkInt in 26..28 / >=29` 硬分版本，
 *     版本仅作探测顺序偏好（8.0 优先 A3）；
 *   - 成功：`HotspotResult(success=true, ssid, pwd, ip=热点本机 IPv4)`（IP 四级采集：ip addr 定向
 *     ap 接口 → 全量打分 → ifconfig → NetworkInterface）；
 *   - 失败：聚合每条失败原因（策略名 + exit + 输出摘要，密码脱敏不回显）返回 error 串，
 *     状态机照旧降级 ②；
 * - ②③（[HotspotStartLevel.L1_PRIVATE_API] / [HotspotStartLevel.L2_LOCAL_ONLY]）：
 *   真实现与私有 API / Local-only 按机型实测均为 B 包范围，本包一律返回失败降级
 *   `HotspotResult(false, error = "本包实现(B包)")`；
 * - ④（[HotspotStartLevel.MANUAL]）：触发 [HotspotListener.onManualRequest] 走 UI 手动配网，
 *   返回骨架 `HotspotResult(false, error = "AwaitingManual")`；用户密码经 [setPassword] 登记，
 *   供后续 offer（热点信息广播）使用。
 *
 * 边界（B1）：只做「启动 + 取信息 + 返回 Result」；关闭/收尾（stop）留 B4；不改状态机。
 * 线程模型：与状态机一致，[start] 同步执行（root shell / 轮询有 [RootSoftAp] 总预算护栏
 * ≤10s，不超状态机 15s 步骤超时窗口）。
 *
 * 私有 API 一期按 `sdkInt in 26..33` 启发（可尝试范围，与 [Arbiter.buildLocalCapability] 的
 * `privateApiCapable` 判定一致）；真实可行性由 B 包反射 try 实测收口。
 *
 * @param listener 生命周期回调（UI / 引擎注入）。
 * @param context 反射路径取 WifiManager 需要（经 Context.getSystemService）；缺省 null 时
 *   [RootSoftAp] 会经 `ActivityThread.currentApplication()` 反射兜底，仍取不到则该路径如实失败，
 *   待接线方注入 applicationContext。
 */
class HotspotManager(
    private val listener: HotspotListener,
    private val context: Context? = null,
) {

    private val tag = "HotspotManager"

    /** ④ 用户手动配网密码登记（App 不生成不指定，仅登记，供后续 offer 使用）。 */
    @Volatile
    private var manualPwd: String? = null

    /**
     * 按仲裁结果 [level] 启动热点。
     *
     * - [HotspotStartLevel.L1_ROOT]：B1 真路径（root 探测前置 + 委托 [RootSoftAp] 全矩阵穷举，
     *   见 [startL1Root]）；
     * - [HotspotStartLevel.L1_PRIVATE_API] / [HotspotStartLevel.L2_LOCAL_ONLY]：
     *   真实现为 B 包范围（私有 API / Local-only 按机型实测），本包 stub 降级，一律返回
     *   `HotspotResult(false, error = "本包实现(B包)")`；
     * - [HotspotStartLevel.MANUAL]：触发 [HotspotListener.onManualRequest] 走 UI 手动配网，
     *   返回骨架 `HotspotResult(false, error = "AwaitingManual")`（后续 offer 由 UI 回填密码走 ready）。
     */
    fun start(level: HotspotStartLevel): HotspotResult {
        DiagLogger.log(tag, "start(level=$level) 调用")
        return when (level) {
            // ① root 真热点（B1）：root 探测前置 + RootSoftAp 配置×启动全矩阵穷举
            HotspotStartLevel.L1_ROOT -> startL1Root()

            // ②③ 真实现与私有 API / Local-only 按机型实测为 B 包范围，本包一律降级
            HotspotStartLevel.L1_PRIVATE_API, HotspotStartLevel.L2_LOCAL_ONLY ->
                HotspotResult(success = false, error = "本包实现(B包)")

            // ④ 手动路径：请求 UI 引导用户手动配网；密码回填后走 onHotspotReady
            HotspotStartLevel.MANUAL -> {
                DiagLogger.log(tag, "MANUAL：请求 UI 手动配网")
                listener.onManualRequest()
                HotspotResult(success = false, error = "AwaitingManual")
            }
        }
    }

    /**
     * 登记 ④ 用户自定义密码（App 不生成不指定，仅登记，供后续 offer 使用）。
     *
     * 仅记录登记动作与长度，不回显明文密码，避免敏感信息进入日志。
     */
    fun setPassword(pwd: String) {
        manualPwd = pwd
        DiagLogger.log(tag, "已登记用户自定义密码，长度=${pwd.length}")
    }

    /**
     * ③ L2 本地热点（Local-only，无密码局域网）可用性：
     * Android 8-9（`sdkInt in 26..28`）或 13+（`sdkInt >= 33`）可用；
     * 10-12 为盲区禁用（与 [Arbiter] 的 `localOnlyAvailable` 判定一致）。
     */
    fun isLevel2Available(sdkInt: Int): Boolean = sdkInt in 26..28 || sdkInt >= 33

    /**
     * ① root 通道可用性：复用 [ble.HandshakeProtocol] 内 [RootDetector] 的能力
     * （应用启动时后台探测 `su -c id` 校验 uid=0 并缓存结果，探测失败/未授权一律 false）。
     */
    fun isRootAvailable(): Boolean = RootDetector.isRoot()

    // ================= ① L1_ROOT 真路径（B1） =================

    /**
     * ① root 真热点（B1，全矩阵穷举版）：
     * 1) 前置：[RootDetector.isRoot] 为 false → 如实返回失败（交状态机试下一级 ②）；
     * 2) root 为 true → 生成 SSID/密码，委托 [RootSoftAp.start] 执行
     *    「配置 5 × 启动 5 + 校验 4」全矩阵（不再按版本硬分路径，版本仅作探测顺序偏好）；
     * 3) 成功返回 `HotspotResult(success=true, ssid, pwd, ip=热点本机 IPv4)`；
     * 4) 失败如实返回聚合原因串（策略名 + exit + 输出摘要，密码脱敏），不吞异常。
     */
    private fun startL1Root(): HotspotResult {
        // 前置：root 不可用 → 返回失败，交状态机降级试下一级 ②（L1_PRIVATE_API）
        if (!RootDetector.isRoot()) {
            DiagLogger.log(
                tag,
                "L1_ROOT 前置失败：RootDetector.isRoot()=false（无 su / 探测未授权），返回失败降级下一级",
            )
            return HotspotResult(success = false, error = "root 不可用（RootDetector.isRoot()=false），降级")
        }

        val ssid = generateSsid()
        val pwd = generatePassword()
        DiagLogger.log(tag, "L1_ROOT：sdk=${Build.VERSION.SDK_INT} ssid=$ssid pwdLen=${pwd.length}（密码不回显）")

        return try {
            // 全矩阵穷举：配置×启动+校验（版本不再硬分，统一走 RootSoftAp 矩阵）
            RootSoftAp.start(ssid, pwd, context)
        } catch (e: Exception) {
            // 不吞异常：记录 + 如实透传原因
            DiagLogger.log(tag, "L1_ROOT 启动异常（不吞）: $e")
            HotspotResult(
                success = false,
                ssid = ssid,
                pwd = pwd,
                error = "root 热点启动异常: ${e.message ?: e.javaClass.simpleName}",
            )
        }
    }

    /** SSID = "Bluelink-" + 4 位随机数字（如 Bluelink-0831）。 */
    private fun generateSsid(): String {
        val n = kotlin.random.Random.nextInt(0, 10_000)
        return SSID_PREFIX + String.format(Locale.US, "%0${SSID_SUFFIX_LEN}d", n)
    }

    /** 随机密码：8 位字母数字（去易混淆字符集）。 */
    private fun generatePassword(len: Int = PWD_LEN): String {
        val rnd = SecureRandom()
        val sb = StringBuilder(len)
        repeat(len) { sb.append(PWD_CHARSET[rnd.nextInt(PWD_CHARSET.length)]) }
        return sb.toString()
    }

    companion object {
        /** SSID 前缀。 */
        private const val SSID_PREFIX = "Bluelink-"

        /** SSID 随机后缀位数（4 位随机数字）。 */
        private const val SSID_SUFFIX_LEN = 4

        /** 密码长度（8 位）。 */
        private const val PWD_LEN = 8

        /** 密码字符集（去 0/O/1/l/I 等易混淆字符）。 */
        private const val PWD_CHARSET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"
    }
}
