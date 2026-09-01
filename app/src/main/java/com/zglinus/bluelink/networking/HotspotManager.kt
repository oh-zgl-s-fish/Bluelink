package com.zglinus.bluelink.networking

import com.zglinus.bluelink.ble.RootDetector
import com.zglinus.bluelink.diag.DiagLogger

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
    /** ① L1 自动热点：root 通道（su 提权创建系统热点）。 */
    L1_ROOT,

    /** ② L1 自动热点：私有 API 通道（反射系统隐藏接口；一期按 `sdkInt in 26..28` 启发，B 包按机型实测替换）。 */
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
 * @param pwd 热点密码（成功时返回；手动路径由 UI 回填，App 不生成不指定）。
 * @param error 失败/等待原因（如 "本包实现(B包)" 降级、`"AwaitingManual"` 等待手动）。
 */
data class HotspotResult(
    val success: Boolean,
    val ssid: String? = null,
    val pwd: String? = null,
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
 * 热启动管理器（A3b，单文件、**stub 骨架**，纯 Kotlin）。
 *
 * 对应设计文档 docs/networking.md §2「热点角色仲裁」：仲裁器 [Arbiter] 决策 who/level 后，
 * 由本管理器按 [HotspotStartLevel] 实际启动热点。当前为 **B 包范围降级实现**：
 *
 * - ①②③（[HotspotStartLevel.L1_ROOT] / [HotspotStartLevel.L1_PRIVATE_API] / [HotspotStartLevel.L2_LOCAL_ONLY]）：
 *   真实现与 root / 私有 API 按机型实测均为 B 包范围，本包一律返回失败降级
 *   `HotspotResult(false, error = "本包实现(B包)")`；
 * - ④（[HotspotStartLevel.MANUAL]）：触发 [HotspotListener.onManualRequest] 走 UI 手动配网，
 *   返回骨架 `HotspotResult(false, error = "AwaitingManual")`；用户密码经 [setPassword] 登记，
 *   供后续 offer（热点信息广播）使用。
 *
 * 私有 API 一期按 `sdkInt in 26..28` 启发（与 [Arbiter.buildLocalCapability] 的
 * `privateApiCapable` 判定一致），B 包按机型实测替换。
 */
class HotspotManager(private val listener: HotspotListener) {

    private val tag = "HotspotManager"

    /** ④ 用户手动配网密码登记（App 不生成不指定，仅登记，供后续 offer 使用）。 */
    @Volatile
    private var manualPwd: String? = null

    /**
     * 按仲裁结果 [level] 启动热点。
     *
     * - [HotspotStartLevel.L1_ROOT] / [HotspotStartLevel.L1_PRIVATE_API] / [HotspotStartLevel.L2_LOCAL_ONLY]：
     *   真实现为 B 包范围（root / 私有 API 按机型实测），本包 stub 降级，一律返回
     *   `HotspotResult(false, error = "本包实现(B包)")`；
     * - [HotspotStartLevel.MANUAL]：触发 [HotspotListener.onManualRequest] 走 UI 手动配网，
     *   返回骨架 `HotspotResult(false, error = "AwaitingManual")`（后续 offer 由 UI 回填密码走 ready）。
     */
    fun start(level: HotspotStartLevel): HotspotResult {
        DiagLogger.log(tag, "start(level=$level) 调用")
        return when (level) {
            // ①②③ 真实现与 root/私有 API 按机型实测为 B 包范围，本包一律降级
            HotspotStartLevel.L1_ROOT, HotspotStartLevel.L1_PRIVATE_API, HotspotStartLevel.L2_LOCAL_ONLY ->
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
}
