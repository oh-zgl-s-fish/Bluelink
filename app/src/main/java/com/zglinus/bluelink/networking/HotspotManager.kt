package com.zglinus.bluelink.networking

import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.zglinus.bluelink.ble.RootDetector
import com.zglinus.bluelink.diag.DiagLogger
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

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

    /** ② L1 自动热点：私有 API 通道（反射系统隐藏接口；一期按 `sdkInt in 26..33` 可尝试启发，与 [Arbiter] 一致；真实可行性由 B2 反射 try 实测收口）。 */
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
 * @param ip 热点本机 IPv4（B1：root 真热点启动后采集；B2：私有 API 路径启动后采集；未取到为空串 ""，一期允许）。
 * @param error 失败/等待原因（如 "本包实现(B包)" 降级、root 全矩阵失败聚合串、`"AwaitingManual"` 等待手动、
 *   `"AwaitingWriteSettings"` 等待 WRITE_SETTINGS 授权）。
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

    /**
     * ② 私有 API 反射热点前置缺失：WRITE_SETTINGS（「修改系统设置」AppOps）未授权。
     * Android 10+ 反射 `WifiManager.setWifiApEnabled` 需此权限；UI 引导授权（复用现有
     * WriteSettingsDialog / openWriteSettings 语义），授权后重试本等级（引擎经现有孤儿兜底覆盖）。
     */
    fun onWriteSettingsPermission()
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
 * - ②（[HotspotStartLevel.L1_PRIVATE_API]，B2 真实现）：私有 API 反射热点（root 之后的降级方案）——
 *   - 前置 WRITE_SETTINGS：`Settings.System.canWrite(ctx)` 未授权 → 回调
 *     [HotspotListener.onWriteSettingsPermission] 引导「修改系统设置」（Android 10+ 反射
 *     `setWifiApEnabled` 需此 AppOps），返回 `HotspotResult(false, error="AwaitingWriteSettings")`
 *     待授权后重试；ctx 缺省时经 `ActivityThread.currentApplication()` 反射兜底（同 [RootSoftAp]）；
 *   - 反射 `WifiManager.setWifiApEnabled(config, true)`（`java.lang.Boolean.TYPE` 精匹配），
 *     构造 WifiConfiguration：SSID=Bluelink-XXXX（4 位随机）/ 随机 8 位密码 / WPA2
 *     （`allowedKeyManagement` 置位 4，即 KeyMgmt.WPA2_PSK）/ `isAccessible=true`；
 *   - 轮询校验：反射 `isWifiApEnabled` ≤5s / 400ms，置 true 即成功，超时/异常 → 失败；
 *   - 成功后采集热点本机 IPv4（NetworkInterface 枚举按热点网段打分，免 root）；
 *   - 失败原因透传（含异常类）交状态机降级 ③；密码全程不回显；
 *   - 运行时 try 实测降级、不预验：真机（A15/KernelSU）大概率 `NoSuchMethodException` 落失败 ③，
 *     8-13 部分机型/ROM 仍可（压力路径）；
 * - ③（[HotspotStartLevel.L2_LOCAL_ONLY]）：Local-only 无密码局域网真实现留 B3，本包保持 stub
 *   降级 `HotspotResult(false, error = "本包实现(B包)")`；
 * - ④（[HotspotStartLevel.MANUAL]）：触发 [HotspotListener.onManualRequest] 走 UI 手动配网，
 *   返回骨架 `HotspotResult(false, error = "AwaitingManual")`；用户密码经 [setPassword] 登记，
 *   供后续 offer（热点信息广播）使用。
 *
 * 边界：只做「启动 + 取信息 + 返回 Result」；关闭/收尾（stop）留 B4；
 * B2 起状态机侧同步接线（② 也走异步桥 onPrivateApiAsyncResult，见 NetworkingStateMachine）。
 * 线程模型（Bluelink ANR 修复）：[start] 保留同步契约（MANUAL/其他调用方不破）；新增
 * [startAsync] 把矩阵体（含 L1_ROOT 的 su/IO、L1_PRIVATE_API 的反射/轮询 sleep）放到后台线程
 * 执行、结果经主线程回调——状态机 L1_ROOT / L1_PRIVATE_API 均改走 [startAsync]，真机点击
 * 「组建临时局域网」不再因 root 穷举矩阵 / 私有 API 轮询卡死主线程（RootSoftAp 总预算护栏
 * ≤10s、② 轮询 ≤5s，均不超状态机 15s 步骤超时窗口，超时兜底 abort）。
 *
 * 私有 API 一期按 `sdkInt in 26..33` 启发（可尝试范围，与 [Arbiter.buildLocalCapability] 的
 * `privateApiCapable` 判定一致）；真实可行性由 B2 反射 try 实测收口（见 [tryPrivateApiHotspot]）。
 *
 * @param listener 生命周期回调（UI / 引擎注入）。
 * @param context 反射路径取 WifiManager 需要（经 Context.getSystemService）；缺省 null 时
 *   [RootSoftAp] 会经 `ActivityThread.currentApplication()` 反射兜底（② 的 [resolveContext] 同法），
 *   仍取不到则该路径如实失败，待接线方注入 applicationContext。
 */
class HotspotManager(
    private val listener: HotspotListener,
    private val context: Context? = null,
) {

    private val tag = "HotspotManager"

    /** ④ 用户手动配网密码登记（App 不生成不指定，仅登记，供后续 offer 使用）。 */
    @Volatile
    private var manualPwd: String? = null

    /** 异步桥：矩阵（含 L1_ROOT su/IO、L1_PRIVATE_API 反射/轮询）专用后台单线程（daemon，不阻止进程退出）。 */
    private val hotspotExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "Bluelink-HotspotManager-async").apply { isDaemon = true }
        }

    /** 主线程 Handler（Looper.getMainLooper() 取主 Handler）：startAsync 结果统一回主线程回调。 */
    private val mainHandler: Handler = Handler(Looper.getMainLooper())

    /** 异步启动进行中标志（防重入：上一次启动未完成时重复 startAsync 直接忽略）。 */
    private val asyncRunning = AtomicBoolean(false)

    /**
     * 按仲裁结果 [level] 启动热点。
     *
     * - [HotspotStartLevel.L1_ROOT]：B1 真路径（root 探测前置 + 委托 [RootSoftAp] 全矩阵穷举，
     *   见 [startL1Root]）；
     * - [HotspotStartLevel.L1_PRIVATE_API]：B2 真实现（私有 API 反射热点，见 [tryPrivateApiHotspot]）；
     * - [HotspotStartLevel.L2_LOCAL_ONLY]：③ 真实现留 B3，本包 stub 降级
     *   `HotspotResult(false, error = "本包实现(B包)")`；
     * - [HotspotStartLevel.MANUAL]：触发 [HotspotListener.onManualRequest] 走 UI 手动配网，
     *   返回骨架 `HotspotResult(false, error = "AwaitingManual")`（后续 offer 由 UI 回填密码走 ready）。
     */
    fun start(level: HotspotStartLevel): HotspotResult {
        DiagLogger.log(tag, "start(level=$level) 调用")
        return startSyncInternal(level)
    }

    /**
     * 异步启动热点（Bluelink ANR 修复：root 热点穷举矩阵 / 私有 API 反射轮询异步桥）。
     *
     * 矩阵体（含 [HotspotStartLevel.L1_ROOT] 的 su/IO，[RootSoftAp] 总预算护栏 ≤10s；以及
     * [HotspotStartLevel.L1_PRIVATE_API] 的反射 + 轮询 sleep，≤5s）在后台线程 [hotspotExecutor]
     * 执行，不占用 UI 主线程；结果经主线程 [mainHandler] 回调 [cb]，成功/失败均回调。
     * 所有等级统一走此异步包装（②③④ 结果同样经主线程 cb 回传）；
     * [asyncRunning] 防重入：上一次启动仍在进行中时重复调用直接忽略。
     *
     * @param level 启动等级（语义与 [start] 一致）。
     * @param cb 主线程回调（携带最终 [HotspotResult]；调用方需自行校验当前状态防时序漂移）。
     */
    fun startAsync(level: HotspotStartLevel, cb: (HotspotResult) -> Unit) {
        if (!asyncRunning.compareAndSet(false, true)) {
            DiagLogger.log(tag, "startAsync(level=$level) 忽略：上一次异步启动仍在进行中（isRunning）")
            return
        }
        DiagLogger.log(tag, "startAsync(level=$level) 提交后台线程（矩阵 su/IO 与反射轮询不在主线程执行）")
        hotspotExecutor.execute {
            val result = try {
                startSyncInternal(level)
            } catch (e: Exception) {
                // 不吞异常：记录 + 如实透传（startL1Root 内部已有 catch，此处为最外层兜底）
                DiagLogger.log(tag, "startAsync 后台执行异常（不吞）: $e")
                HotspotResult(
                    success = false,
                    error = "startAsync 后台异常: ${e.message ?: e.javaClass.simpleName}",
                )
            }
            // 结果统一回主线程回调（状态机按主线程契约消费；回调前释放 isRunning 供下次启动）
            mainHandler.post {
                asyncRunning.set(false)
                cb(result)
            }
        }
    }

    /**
     * 同步启动执行体（[start] 与 [startAsync] 共用；行为与历史 [start] 完全一致）。
     */
    private fun startSyncInternal(level: HotspotStartLevel): HotspotResult = when (level) {
        // ① root 真热点（B1）：root 探测前置 + RootSoftAp 配置×启动全矩阵穷举
        HotspotStartLevel.L1_ROOT -> startL1Root()

        // ② 私有 API 反射热点（B2 真实现）：WRITE_SETTINGS 前置 + 反射 setWifiApEnabled + 轮询校验 + 取 IP
        HotspotStartLevel.L1_PRIVATE_API -> tryPrivateApiHotspot()

        // ③ L2 本地热点（Local-only，无密码局域网）：真实现留 B3，本包保持 stub 降级
        HotspotStartLevel.L2_LOCAL_ONLY ->
            HotspotResult(success = false, error = "本包实现(B包)")

        // ④ 手动路径：请求 UI 引导用户手动配网；密码回填后走 onHotspotReady。
        // onManualRequest 触达 UI：经主线程 post（startAsync 后台线程调用该分支时也安全）。
        HotspotStartLevel.MANUAL -> {
            DiagLogger.log(tag, "MANUAL：请求 UI 手动配网（主线程 post）")
            mainHandler.post { listener.onManualRequest() }
            HotspotResult(success = false, error = "AwaitingManual")
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

    // ================= ② L1_PRIVATE_API 真路径（B2：私有 API 反射热点） =================

    /**
     * ② 私有 API 反射热点（root 之后的降级方案；B2 真实现）：
     * 1) 前置 WRITE_SETTINGS：`Settings.System.canWrite(ctx)` 为 false → 经主线程回调
     *    [HotspotListener.onWriteSettingsPermission] 引导「修改系统设置」授权（复用现有
     *    WriteSettingsDialog / openWriteSettings 语义），返回 `AwaitingWriteSettings` 待授权后重试；
     *    ctx 缺省时经 `ActivityThread.currentApplication()` 反射兜底（同 [RootSoftAp]）；
     * 2) 反射 `WifiManager.setWifiApEnabled(config, true)`（`java.lang.Boolean.TYPE` 精匹配），
     *    构造 WifiConfiguration：SSID=Bluelink-XXXX（4 位随机）、preSharedKey=随机 8 位、
     *    WPA2（`allowedKeyManagement` 置位 4，即 KeyMgmt.WPA2_PSK）、`isAccessible=true`
     *    （hidden 字段经反射设置，缺失忽略）；
     * 3) 轮询校验：反射 `isWifiApEnabled` ≤5s / 400ms，置 true 即成功；超时或异常 → 失败；
     * 4) 取 IP：NetworkInterface 枚举按热点网段打分（定向 ap 接口优先，192.168.43.x 默认热点网段
     *    加分），取不到为空串 ""（一期允许占位）；
     * 失败原因透传（含异常类）；密码全程不回显。
     * 运行时 try 实测降级、不预验：真机（A15/KernelSU）大概率 NoSuchMethodException →
     * 如实失败交状态机降级 ③；8-13 部分机型/ROM 仍可（压力路径）。
     *
     * 线程：随 [startAsync] 后台线程执行（startSyncInternal 被后台 executor 调用），
     * 轮询 sleep 不占主线程；UI 回调统一主线程 post。
     */
    @Suppress("DEPRECATION") // WifiConfiguration / WifiManager 热点 API 自 API 26 起弃用，私有反射路径唯一可用通道
    private fun tryPrivateApiHotspot(): HotspotResult {
        val ctx = resolveContext()
        if (ctx == null) {
            val err = "L1_PRIVATE_API：Context 不可用（注入与 ActivityThread.currentApplication() 兜底均失败）"
            DiagLogger.log(tag, err)
            return HotspotResult(success = false, error = err)
        }

        // 前置 WRITE_SETTINGS：Android 10+ 反射 setWifiApEnabled 需「修改系统设置」AppOps
        if (!Settings.System.canWrite(ctx)) {
            DiagLogger.log(
                tag,
                "L1_PRIVATE_API 前置失败：WRITE_SETTINGS（修改系统设置）未授权（canWrite=false），" +
                    "回调 onWriteSettingsPermission 引导授权，返回 AwaitingWriteSettings 待授权后重试",
            )
            mainHandler.post { listener.onWriteSettingsPermission() }
            return HotspotResult(success = false, error = AWAITING_WRITE_SETTINGS)
        }

        val ssid = generateSsid()
        val pwd = generatePassword()
        DiagLogger.log(
            tag,
            "L1_PRIVATE_API：sdk=${Build.VERSION.SDK_INT} ssid=$ssid pwdLen=${pwd.length}（密码不回显），反射尝试 setWifiApEnabled",
        )

        val wm = resolveWifiManager(ctx)
        if (wm == null) {
            val err = "L1_PRIVATE_API：WifiManager 不可用（Context 已取得但 getSystemService 失败）"
            DiagLogger.log(tag, err)
            return HotspotResult(success = false, ssid = ssid, pwd = pwd, error = err)
        }

        return try {
            val method = WifiManager::class.java.getMethod(
                "setWifiApEnabled",
                WifiConfiguration::class.java,
                java.lang.Boolean.TYPE, // boolean 基本类型精匹配（Kotlin 中 javaPrimitiveType 可空，Java 静态常量最稳）
            )
            method.isAccessible = true
            val config = buildPrivateApConfig(ssid, pwd)
            val accepted = method.invoke(wm, config, true) as? Boolean ?: false
            DiagLogger.log(tag, "L1_PRIVATE_API 反射 setWifiApEnabled 返回 accepted=$accepted")
            if (!accepted) {
                val err = "L1_PRIVATE_API：反射 setWifiApEnabled 返回 false（系统拒绝请求）"
                DiagLogger.log(tag, err)
                return HotspotResult(success = false, ssid = ssid, pwd = pwd, error = err)
            }

            // 轮询校验：反射 isWifiApEnabled ≤5s / 400ms
            val started = pollWifiApEnabled(wm, System.currentTimeMillis() + PRIVATE_AP_POLL_TIMEOUT_MS)
            if (!started) {
                val err = "L1_PRIVATE_API：${PRIVATE_AP_POLL_TIMEOUT_MS / 1000}s 内 isWifiApEnabled 未置 true（超时）"
                DiagLogger.log(tag, err)
                return HotspotResult(success = false, ssid = ssid, pwd = pwd, error = err)
            }

            val ip = collectHotspotIp()
            DiagLogger.log(
                tag,
                "L1_PRIVATE_API 成功：ssid=$ssid pwdLen=${pwd.length} ip=${ip.ifEmpty { "<空>" }}（密码不回显）",
            )
            HotspotResult(success = true, ssid = ssid, pwd = pwd, ip = ip)
        } catch (e: Exception) {
            // 不吞异常：记录 + 如实透传（含异常类；真机 NoSuchMethodException → 降级 ③ 属预期）
            DiagLogger.log(tag, "L1_PRIVATE_API 反射启动异常（不吞）: $e")
            HotspotResult(
                success = false,
                ssid = ssid,
                pwd = pwd,
                error = "私有 API 反射启动异常: ${e.javaClass.simpleName}: ${e.message}",
            )
        }
    }

    /** 反射取 WifiManager（② 用；ctx 已由 [resolveContext] 保证非 null）。 */
    private fun resolveWifiManager(ctx: Context): WifiManager? = try {
        ctx.applicationContext.getSystemService(WifiManager::class.java)
    } catch (e: Exception) {
        DiagLogger.log(tag, "取 WifiManager 异常: $e")
        null
    }

    /**
     * Context 兜底：优先构造注入的 [context]；null 时经 `ActivityThread.currentApplication()` 反射
     * （同 [RootSoftAp] 的兜底法；P+ 可能被 hidden API 拦截，失败如实返回 null）。
     */
    private fun resolveContext(): Context? {
        context?.let { return it }
        return try {
            Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null) as? Context
        } catch (e: Exception) {
            DiagLogger.log(tag, "ActivityThread.currentApplication() 反射失败（Context 未注入兜底）: $e")
            null
        }
    }

    /**
     * 构造 ② 的 WifiConfiguration：SSID=Bluelink-XXXX、preSharedKey=随机 8 位、
     * WPA2（`allowedKeyManagement` 置位 4，即 KeyMgmt.WPA2_PSK）；
     * `isAccessible=true` 为 hidden 字段（不在公开 SDK），经反射设置，缺失忽略。
     */
    @Suppress("DEPRECATION")
    private fun buildPrivateApConfig(ssid: String, pwd: String): WifiConfiguration {
        val c = WifiConfiguration()
        c.SSID = "\"$ssid\"" // WifiConfiguration 约定 SSID 需加引号
        c.preSharedKey = "\"$pwd\""
        c.allowedKeyManagement.set(4) // WPA2（WifiConfiguration.KeyMgmt.WPA2_PSK == 4；直接置位避免 SDK 常量差异）
        c.allowedProtocols.set(WifiConfiguration.Protocol.RSN)
        c.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP)
        c.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.CCMP)
        c.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.TKIP)
        try {
            val f = WifiConfiguration::class.java.getField("isAccessible")
            f.isAccessible = true
            f.setBoolean(c, true)
        } catch (e: Exception) {
            // hidden 字段缺失/被拦截：忽略（部分 SDK/ROM 无此字段，不影响 setWifiApEnabled 尝试）
            DiagLogger.log(tag, "isAccessible hidden 字段反射设置失败（缺失则忽略）: ${e.javaClass.simpleName}")
        }
        return c
    }

    /** 轮询反射 `isWifiApEnabled`：≤[PRIVATE_AP_POLL_TIMEOUT_MS]/[PRIVATE_AP_POLL_INTERVAL_MS]，置 true 即成功；超时/异常 → false。 */
    private fun pollWifiApEnabled(wm: WifiManager, deadlineMs: Long): Boolean {
        while (System.currentTimeMillis() < deadlineMs) {
            try {
                val m = WifiManager::class.java.getMethod("isWifiApEnabled")
                m.isAccessible = true
                if (m.invoke(wm) == true) return true
            } catch (e: Exception) {
                DiagLogger.log(tag, "L1_PRIVATE_API 轮询 isWifiApEnabled 反射异常（按失败处理）: $e")
                return false
            }
            try {
                Thread.sleep(PRIVATE_AP_POLL_INTERVAL_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
    }

    /**
     * 取热点本机 IPv4（简化版，复用 RootSoftAp 四级采集思路的 NetworkInterface 环节）：
     * 枚举全部接口，按接口名/网段打分（ap 系 +100、192.168.43.x 默认热点网段 +50、
     * 192.168.x +20、wlan +10、10./172. +5）取最优；无候选返回空串 ""（一期允许占位）。
     * 本路径免 root（② 为 root 降级后的非 root 通道），故不执行 su 命令采集。
     */
    private fun collectHotspotIp(): String {
        val candidates = mutableListOf<Pair<String, String>>()
        try {
            NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { ni ->
                if (!ni.isUp || ni.isLoopback) return@forEach
                ni.interfaceAddresses.forEach { ia ->
                    val a = ia.address
                    if (a is Inet4Address && !a.isLoopbackAddress && !a.isLinkLocalAddress) {
                        candidates.add(ni.name to (a.hostAddress ?: ""))
                    }
                }
            }
        } catch (e: Exception) {
            DiagLogger.log(tag, "NetworkInterface 枚举失败: $e")
            return ""
        }
        val best = candidates.maxByOrNull { scoreHotspotIface(it.first, it.second) } ?: return ""
        val ip = best.second
        if (ip.isBlank()) return ""
        DiagLogger.log(tag, "L1_PRIVATE_API 取 IP：iface=${best.first} ip=$ip")
        return ip
    }

    /** 接口名/网段打分（与 RootSoftAp 同思路）：ap 系 +100、192.168.43.x +50、192.168.x +20、wlan +10、10./172. +5。 */
    private fun scoreHotspotIface(iface: String, ip: String): Int {
        val n = iface.lowercase(Locale.US)
        var s = 0
        if (n.startsWith("ap") || n.contains("softap")) s += 100
        if (ip.startsWith("192.168.43.")) s += 50 // Android 默认热点网段
        if (ip.startsWith("192.168.")) s += 20
        if (n.contains("wlan")) s += 10
        if (ip.startsWith("10.") || ip.startsWith("172.")) s += 5
        return s
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

        /** ② 私有 API 前置缺失标记（error 字段，等待 WRITE_SETTINGS 授权后重试）。 */
        private const val AWAITING_WRITE_SETTINGS = "AwaitingWriteSettings"

        /** ② 反射 setWifiApEnabled 后轮询 isWifiApEnabled 的最长等待（任务约定 ≤5s）。 */
        private const val PRIVATE_AP_POLL_TIMEOUT_MS: Long = 5_000L

        /** ② 轮询 isWifiApEnabled 间隔（任务约定 400ms）。 */
        private const val PRIVATE_AP_POLL_INTERVAL_MS: Long = 400L
    }
}
