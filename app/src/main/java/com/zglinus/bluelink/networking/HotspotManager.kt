package com.zglinus.bluelink.networking

import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiManager.LocalOnlyHotspotCallback
import android.net.wifi.WifiManager.LocalOnlyHotspotReservation
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ResultReceiver
import android.provider.Settings
import com.zglinus.bluelink.ble.RootDetector
import com.zglinus.bluelink.diag.DiagLogger
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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
    /** ① L1 自动热点：root 通道（已停用：B1 移除，A15/root 路径废弃；HotspotManager 返回失败 stub，状态机自动降级 ②）。 */
    L1_ROOT,

    /** ② L1 自动热点：私有 API 通道（v0.3.4 增强：Binder 直呼系统热点优先——sdk 26-33 执行、sdk34+ 快失败 → 反射 setWifiApEnabled 降级；真实可行性由反射 try 实测收口）。 */
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
 * @param pwd 热点密码（成功时返回；② 私有 API 路径由本包自设随机密码；③ L2 本地热点 26-28 由系统下发
 *   （onStarted 读 preSharedKey）、33+ 由用户按系统弹窗回填登记；手动路径由 UI 回填）。
 * @param ip 热点本机 IPv4（② 私有 API 路径启动后采集；未取到为空串 ""，一期允许）。
 * @param error 失败/等待原因（如 root 路径已停用(B1 移除) 降级、③ L2 盲区禁用/系统 reason/启动异常
 *   降级、`"AwaitingManual"` 等待手动、`"AwaitingWriteSettings"` 等待 WRITE_SETTINGS 授权）。
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

    /**
     * ③ L2 本地热点（13+，sdk 33+）：onStarted 后 App 侧密码不可读（软 AP 配置不回传密码；
     * 系统弹窗/通知展示 SSID 与密码）——请求 UI 弹出密码登记框，请用户按系统弹窗回填密码；
     * 回填后经 [HotspotManager.completeLocalOnlyPassword] 完成 L2 成功结果（26-28 全自动路径不触发本回调）。
     */
    fun onLocalOnlyPasswordRequest(ssid: String)

    /**
     * ② Binder 直呼系统热点成功（v0.3.4 增强）：系统预配热点已自动开启，但 SSID/密码为系统配置、
     * App 侧不可读——请求 UI 弹出登记框（复用 ④ manualPwdDialog 登记框，模式置
     * systemHotspotPwdMode），请用户按本机热点信息登记 SSID+密码；回填后经
     * [HotspotManager.completeSystemHotspotPassword] 完成 ② 成功结果（ip 现采）。
     */
    fun onSystemHotspotPasswordRequest()
}

/**
 * 热启动管理器（A3b，单文件瘦身版；① root 真热点已停用——B1 移除，A15/root 路径废弃）。
 *
 * 对应设计文档 docs/networking.md §2「热点角色仲裁」：仲裁器 [Arbiter] 决策 who/level 后，
 * 由本管理器按 [HotspotStartLevel] 实际启动热点。
 *
 * - ①（[HotspotStartLevel.L1_ROOT]，已停用）：B1 root 真热点穷举矩阵已整体移除（A15/root 路径废弃），
 *   本等级为失败 stub——[startSyncInternal] 直接返回
 *   `HotspotResult(false, error="root 热点路径已停用(B1 移除)，降级 ②")`，不启动线程、不生成凭据；
 *   状态机 L1_ROOT 异步桥收到 false 照旧降级 ②（L1_PRIVATE_API），状态机无需改动；
 * - ②（[HotspotStartLevel.L1_PRIVATE_API]，B2 真实现 + v0.3.4 Binder 直呼增强）：私有 API 热点
 *   （① 停用后的降级主路径）——优先级定案：**Binder 直呼系统热点（系统预配热点自动开）→ 反射
 *   setWifiApEnabled 降级 → 失败透传**（见 [tryPrivateApiHotspot]）：
 *   - 前置 WRITE_SETTINGS：`Settings.System.canWrite(ctx)` 未授权 → 回调
 *     [HotspotListener.onWriteSettingsPermission] 引导「修改系统设置」（Android 10+ 反射
 *     `setWifiApEnabled` 需此 AppOps），返回 `HotspotResult(false, error="AwaitingWriteSettings")`
 *     待授权后重试；ctx 缺省时经 `ActivityThread.currentApplication()` 反射兜底（见 [resolveContext]）；
 *   - 反射 `WifiManager.setWifiApEnabled(config, true)`（`java.lang.Boolean.TYPE` 精匹配），
 *     构造 WifiConfiguration：SSID=Bluelink-XXXX（4 位随机）/ 随机 8 位密码 / WPA2
 *     （`allowedKeyManagement` 置位 4，即 KeyMgmt.WPA2_PSK）/ `isAccessible=true`；
 *   - 轮询校验：反射 `isWifiApEnabled` ≤5s / 400ms，置 true 即成功，超时/异常 → 失败；
 *   - 成功后采集热点本机 IPv4（NetworkInterface 枚举按热点网段打分，免 root）；
 *   - 失败原因透传（含异常类）交状态机降级 ③；密码全程不回显；
 *   - 运行时 try 实测降级、不预验：真机（A15/KernelSU）大概率 `NoSuchMethodException` 落失败 ③，
 *     8-13 部分机型/ROM 仍可（压力路径）；
 * - ③（[HotspotStartLevel.L2_LOCAL_ONLY]，B3 真实现）：Local-only 本地热点——公开 API
 *   `WifiManager.startLocalOnlyHotspot(callback, handler)` 三版本分流（design 定稿，见 [tryLocalOnlyHotspot]）：
 *   sdk 26-28 全自动（onStarted 读 `reservation.wifiConfiguration` 的 SSID/preSharedKey + 采集 IP）；
 *   sdk 29-32 本级禁用（密码盲区，直接失败 `"LocalOnlyHotspot 密码盲区(10-12 禁用)，降级 ④"`）；
 *   sdk 33+ onStarted 后密码不可读（系统弹窗展示）→ 触发 [HotspotListener.onLocalOnlyPasswordRequest]
 *   请用户回填，[completeLocalOnlyPassword] 完成后返回成功结果。真异步：系统回调经主线程
 *   [dispatchLocalOnlyResult] 收敛（同步返回 [LOCAL_ONLY_PENDING] 标记）；reservation 持有到组网收尾，
 *   [stopLocalOnly] 为 B4 正式收尾前的释放入口；
 * - ④（[HotspotStartLevel.MANUAL]）：触发 [HotspotListener.onManualRequest] 走 UI 手动配网，
 *   返回骨架 `HotspotResult(false, error = "AwaitingManual")`；用户密码经 [setPassword] 登记，
 *   供后续 offer（热点信息广播）使用。
 *
 * 边界：只做「启动 + 取信息 + 返回 Result」；关闭/收尾（stop）留 B4；
 * B2 起状态机侧同步接线（② 也走异步桥 onPrivateApiAsyncResult，见 NetworkingStateMachine）。
 * 线程模型（Bluelink ANR 修复）：[start] 保留同步契约（MANUAL/其他调用方不破）；新增
 * [startAsync] 把②（L1_PRIVATE_API）的反射/轮询 sleep 放到后台线程执行、结果经主线程回调——
 * 状态机 L1_ROOT（stub 立即返回 false）/ L1_PRIVATE_API 均走 [startAsync]，真机点击
 * 「组建临时局域网」不再因私有 API 轮询卡死主线程（② 轮询 ≤5s，不超状态机 15s 步骤
 * 超时窗口，超时兜底 abort）。
 *
 * 私有 API 一期按 `sdkInt in 26..33` 启发（可尝试范围，与 [Arbiter.buildLocalCapability] 的
 * `privateApiCapable` 判定一致）；真实可行性由 B2 反射 try 实测收口（见 [tryPrivateApiHotspot]）。
 *
 * @param listener 生命周期回调（UI / 引擎注入）。
 * @param context 反射路径取 WifiManager 需要（经 Context.getSystemService）；缺省 null 时
 *   [resolveContext] 会经 `ActivityThread.currentApplication()` 反射兜底，仍取不到则该路径
 *   如实失败，待接线方注入 applicationContext。
 */
class HotspotManager(
    private val listener: HotspotListener,
    private val context: Context? = null,
) {

    private val tag = "HotspotManager"

    /** ④ 用户手动配网密码登记（App 不生成不指定，仅登记，供后续 offer 使用）。 */
    @Volatile
    private var manualPwd: String? = null

    /** ③ L2 本地热点：onStarted 后持有中的 LocalOnlyHotspotReservation（B4 正式收尾前持有，[stopLocalOnly] 释放）。 */
    @Volatile
    private var localOnlyReservation: LocalOnlyHotspotReservation? = null

    /** ③ L2 本地热点：待收敛的异步回调（[startAsync] L2 分支登记；onStarted/onFailed/onStopped/密码回填后经 [dispatchLocalOnlyResult] 收敛）。 */
    @Volatile
    private var pendingLocalOnlyCb: ((HotspotResult) -> Unit)? = null

    /** ③ L2 本地热点：sdk 33+ 待用户回填密码的 SSID（onLocalOnlyPasswordRequest 已触发，等 [completeLocalOnlyPassword]）。 */
    @Volatile
    private var pendingLocalOnlySsid: String? = null

    /** ② Binder 直呼系统热点（v0.3.4）：成功后待收敛的异步回调（等待用户登记本机系统热点 SSID+密码，经 [dispatchBinderTetherResult] 收敛；登记被中止时经 [stopBinderTetherPending] 清理）。 */
    @Volatile
    private var pendingBinderTetherCb: ((HotspotResult) -> Unit)? = null

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
     * - [HotspotStartLevel.L1_ROOT]：已停用（B1 移除）——失败 stub，立即返回
     *   `HotspotResult(false, error="root 热点路径已停用(B1 移除)，降级 ②")`；
     * - [HotspotStartLevel.L1_PRIVATE_API]：B2 真实现（私有 API 反射热点，见 [tryPrivateApiHotspot]）；
     * - [HotspotStartLevel.L2_LOCAL_ONLY]：③ B3 真实现（Local-only 本地热点，见 [tryLocalOnlyHotspot]；
     *   真异步——同步返回 [LOCAL_ONLY_PENDING] 标记，最终结果经 LocalOnlyHotspotCallback 主线程收敛；
     *   状态机 L2 分支走 [startAsync]）；
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
     * 矩阵体（[HotspotStartLevel.L1_PRIVATE_API] 的反射 + 轮询 sleep，≤5s；L1_ROOT 为失败 stub
     * 无后台耗时）在后台线程 [hotspotExecutor] 执行，不占用 UI 主线程；结果经主线程 [mainHandler]
     * 回调 [cb]，成功/失败均回调。
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
        // ③ L2 真异步（B3）：startLocalOnlyHotspot 结果经 LocalOnlyHotspotCallback 主线程回调，
        // 与后台 executor 解耦——先在本线程（状态机主线程调用）登记待收敛 cb，保证系统回调
        // 触发时必有收敛目标（登记先于后台 startSyncInternal 提交，系统回调经 mainHandler 排队在后）。
        if (level == HotspotStartLevel.L2_LOCAL_ONLY) {
            pendingLocalOnlyCb = cb
        }
        // ★ ② Binder 直呼（v0.3.4）：L1_PRIVATE_API 成功路径（系统预配热点已开）需用户登记
        // 本机系统热点 SSID+密码——先在本线程登记待收敛 cb（与 L2 同语义，登记先于后台提交），
        // 成功路径经 dispatchBinderTetherResult 收敛；失败/降级反射路径照旧走下方主线程 cb。
        if (level == HotspotStartLevel.L1_PRIVATE_API) {
            pendingBinderTetherCb = cb
        }
        DiagLogger.log(tag, "startAsync(level=$level) 提交后台线程（矩阵 su/IO 与反射轮询不在主线程执行）")
        hotspotExecutor.execute {
            val result = try {
                startSyncInternal(level)
            } catch (e: Exception) {
                // 不吞异常：记录 + 如实透传（② tryPrivateApiHotspot 内部已有 catch，此处为最外层兜底）
                DiagLogger.log(tag, "startAsync 后台执行异常（不吞）: $e")
                HotspotResult(
                    success = false,
                    error = "startAsync 后台异常: ${e.message ?: e.javaClass.simpleName}",
                )
            }
            // ③ L2 真异步：startSyncInternal 已提交 startLocalOnlyHotspot 并返回 LOCAL_ONLY_PENDING
            // 标记——结果由 LocalOnlyHotspotCallback（onStarted/onFailed/onStopped）经主线程
            // dispatchLocalOnlyResult 收敛到 pendingLocalOnlyCb；此处不回调、不释放 isRunning
            // （收敛时统一释放，防 L2 等待期重复启动）。
            if (result.error == LOCAL_ONLY_PENDING) {
                DiagLogger.log(
                    tag,
                    "startAsync(level=$level)：L2 真异步进行中，等待 LocalOnlyHotspotCallback 收敛（不重复回调）",
                )
                return@execute
            }
            // ★ ② Binder 直呼（v0.3.4）：成功→等待系统热点密码登记（与 L2 同语义：不回调、
            // 不释放 isRunning——登记完成经 dispatchBinderTetherResult 统一收敛，防登记等待期重复启动）
            if (result.error == BINDER_TETHER_PENDING) {
                DiagLogger.log(
                    tag,
                    "startAsync(level=$level)：② Binder 直呼成功，等待 completeSystemHotspotPassword 登记收敛（不重复回调）",
                )
                return@execute
            }
            // 结果统一回主线程回调（状态机按主线程契约消费；回调前释放 isRunning 供下次启动）
            mainHandler.post {
                asyncRunning.set(false)
                pendingBinderTetherCb = null // 非 pending 结果：清理预留（防残留悬挂）
                cb(result)
            }
        }
    }

    /**
     * 同步启动执行体（[start] 与 [startAsync] 共用；行为与历史 [start] 完全一致）。
     */
    private fun startSyncInternal(level: HotspotStartLevel): HotspotResult = when (level) {
        // ① root 真热点（B1 已移除）：失败 stub——不启动线程、不生成凭据；
        // 状态机 L1_ROOT 异步桥收到 false 照旧降级 ②（L1_PRIVATE_API），无需改状态机
        HotspotStartLevel.L1_ROOT ->
            HotspotResult(success = false, error = "root 热点路径已停用(B1 移除)，降级 ②")

        // ② 私有 API 热点（B2 真实现 + v0.3.4 Binder 直呼增强）：WRITE_SETTINGS 前置 +
        // Binder 直呼（sdk 26-33，系统预配热点自动开）→ 反射 setWifiApEnabled 降级 → 失败透传，见 [tryPrivateApiHotspot]
        HotspotStartLevel.L1_PRIVATE_API -> tryPrivateApiHotspot()

        // ③ L2 本地热点（Local-only，无密码局域网）：B3 真实现——三版本分流
        // （26-28 全自动 / 29-32 盲区禁用 / 33+ 密码回填），见 [tryLocalOnlyHotspot]
        HotspotStartLevel.L2_LOCAL_ONLY -> tryLocalOnlyHotspot()

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

    // ================= ② L1_PRIVATE_API 真路径（B2：私有 API 反射热点） =================

    /**
     * ② 私有 API 热点（① 停用后的降级主路径；v0.3.4 增强——优先级定案：**Binder 直呼系统热点
     * （系统预配热点自动开）→ 反射 setWifiApEnabled 降级 → 失败透传**）：
     * 0) 前置 WRITE_SETTINGS：`Settings.System.canWrite(ctx)` 为 false → 经主线程回调
     *    [HotspotListener.onWriteSettingsPermission] 引导「修改系统设置」授权（复用现有
     *    WriteSettingsDialog / openWriteSettings 语义），返回 `AwaitingWriteSettings` 待授权后重试；
     *    ctx 缺省时经 `ActivityThread.currentApplication()` 反射兜底（见 [resolveContext]）；
     * 1) ★ 第一步 [tryBinderTether]（逆向骨架照抄 MakroDroid T1——sdk31+ 开）：sdk 26–33 直呼
     *    Binder（ServiceManager.getService("tethering") → ITetheringConnector$Stub.asInterface →
     *    TetheringRequestParcel{tetheringType=0, showProvisioningUi=false} → startTethering 反射调用；
     *    IIntResultListener 真实现记录错误码；回调码 0 / 状态轮询确认 / 8s 超时兜底）；
     *    sdk ≥34 直接失败（「sdk34+ 不裸调 startTethering（逆向结论）」）；
     *    成功 → 系统预配热点已开（SSID/密码为系统配置、App 不可读）→ 触发
     *    [HotspotListener.onSystemHotspotPasswordRequest] 请用户登记 → 返回 BinderTetherPending
     *    标记待 [completeSystemHotspotPassword] 收敛（startAsync 持有异步闸）；
     * 2) ★ 第二步（降级）：原反射 `WifiManager.setWifiApEnabled(config, true)`
     *    （`java.lang.Boolean.TYPE` 精匹配），构造 WifiConfiguration：SSID=Bluelink-XXXX（4 位随机）、
     *    preSharedKey=随机 8 位、WPA2（`allowedKeyManagement` 置位 4，即 KeyMgmt.WPA2_PSK）、
     *    `isAccessible=true`（hidden 字段经反射设置，缺失忽略）——8-9 机型 setWifiApEnabled 仍有效；
     * 3) 轮询校验：反射 `isWifiApEnabled` ≤5s / 400ms，置 true 即成功；超时或异常 → 失败；
     * 4) 取 IP：NetworkInterface 枚举按热点网段打分（定向 ap 接口优先，192.168.43.x 默认热点网段
     *    加分），取不到为空串 ""（一期允许占位）；
     * 失败原因透传（含异常类 + Binder 直呼失败原因）；密码全程不回显。
     * 运行时 try 实测降级、不预验：真机（A15/KernelSU）大概率 NoSuchMethodException →
     * 如实失败交状态机降级 ③；8-13 部分机型/ROM 仍可（压力路径）。
     *
     * 线程：随 [startAsync] 后台线程执行（startSyncInternal 被后台 executor 调用），
     * Binder 直呼 8s 确认等待与轮询 sleep 均不占主线程；UI 回调统一主线程 post。
     */
    @Suppress("DEPRECATION") // WifiConfiguration / WifiManager 热点 API 自 API 26 起弃用，私有反射路径唯一可用通道
    private fun tryPrivateApiHotspot(): HotspotResult {
        val ctx = resolveContext()
        if (ctx == null) {
            val err = "L1_PRIVATE_API：Context 不可用（注入与 ActivityThread.currentApplication() 兜底均失败）"
            DiagLogger.log(tag, err)
            return HotspotResult(success = false, error = err)
        }

        // 前置 WRITE_SETTINGS：Android 10+ 反射 setWifiApEnabled / Binder 直呼均以「修改系统设置」AppOps 为前置
        if (!Settings.System.canWrite(ctx)) {
            DiagLogger.log(
                tag,
                "L1_PRIVATE_API 前置失败：WRITE_SETTINGS（修改系统设置）未授权（canWrite=false），" +
                    "回调 onWriteSettingsPermission 引导授权，返回 AwaitingWriteSettings 待授权后重试",
            )
            mainHandler.post { listener.onWriteSettingsPermission() }
            return HotspotResult(success = false, error = AWAITING_WRITE_SETTINGS)
        }

        val wm = resolveWifiManager(ctx)
        if (wm == null) {
            val err = "L1_PRIVATE_API：WifiManager 不可用（Context 已取得但 getSystemService 失败）"
            DiagLogger.log(tag, err)
            return HotspotResult(success = false, error = err)
        }

        // ★ 第一步（v0.3.4 新优先级）：Binder 直呼系统热点（系统预配热点自动开）——
        // sdk 26-33 执行；sdk≥34 快失败；失败 → 降级原反射 setWifiApEnabled（8-9 有效）
        val binder = tryBinderTether(ctx, wm)
        if (binder.error == BINDER_TETHER_PENDING || binder.success) {
            // BINDER_TETHER_PENDING：系统热点已开，等待用户登记本机系统热点 SSID+密码收敛
            // （startAsync 持有异步闸；登记经 completeSystemHotspotPassword → dispatchBinderTetherResult）
            return binder
        }
        val binderFailReason = binder.error ?: "Binder 直呼失败（无原因）"
        DiagLogger.log(
            tag,
            "L1_PRIVATE_API：Binder 直呼未成功（$binderFailReason），降级原反射 setWifiApEnabled（8-9 有效）",
        )

        // ★ 第二步（降级）：原反射 setWifiApEnabled（现状逻辑原样；失败透传附加 binder 失败原因）
        val ssid = generateSsid()
        val pwd = generatePassword()
        DiagLogger.log(
            tag,
            "L1_PRIVATE_API：sdk=${Build.VERSION.SDK_INT} ssid=$ssid pwdLen=${pwd.length}（密码不回显），反射尝试 setWifiApEnabled",
        )
        val legacy = try {
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
                HotspotResult(success = false, ssid = ssid, pwd = pwd, error = err)
            } else {
                // 轮询校验：反射 isWifiApEnabled ≤5s / 400ms
                val started = pollWifiApEnabled(wm, System.currentTimeMillis() + PRIVATE_AP_POLL_TIMEOUT_MS)
                if (!started) {
                    val err = "L1_PRIVATE_API：${PRIVATE_AP_POLL_TIMEOUT_MS / 1000}s 内 isWifiApEnabled 未置 true（超时）"
                    DiagLogger.log(tag, err)
                    HotspotResult(success = false, ssid = ssid, pwd = pwd, error = err)
                } else {
                    val ip = collectHotspotIp()
                    DiagLogger.log(
                        tag,
                        "L1_PRIVATE_API 成功：ssid=$ssid pwdLen=${pwd.length} ip=${ip.ifEmpty { "<空>" }}（密码不回显）",
                    )
                    HotspotResult(success = true, ssid = ssid, pwd = pwd, ip = ip)
                }
            }
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
        if (!legacy.success) {
            return legacy.copy(error = "${legacy.error}；Binder 直呼失败：$binderFailReason")
        }
        return legacy
    }

    /**
     * ★ ② 第一步：Binder 直呼系统热点（v0.3.4 新优先级；逆向骨架照抄 MakroDroid T1——sdk31+ 开，
     * 依据已逆向确证的 FINAL-REPORT-SetHotspotAction.md §2.3）：
     * - 版本门控：sdk 26–33 执行；**sdk ≥34 直接返回失败**（原因「sdk34+ 不裸调 startTethering（逆向结论），降级 ③」）；
     * - 取服务：反射 `ServiceManager.getService("tethering")` → IBinder →
     *   `Class.forName("android.net.ITetheringConnector$Stub").getMethod("asInterface", IBinder)` → connector；
     * - Parcel：`Class.forName("android.net.TetheringRequestParcel").newInstance()` →
     *   `tetheringType`=0（WIFI）/ `showProvisioningUi`=false（getDeclaredField + setInt/setBoolean）；
     * - 回调：`Proxy.newProxyInstance(cl, [IIntResultListener], handler)`——MakroDroid（ua0/ta0）为
     *   空实现，**我们做真实现**：onResult 错误码写入 AtomicInteger（binder 回调线程 → 后台轮询线程
     *   可见，线程安全）；
     * - 调用分段：sdk≥31 `startTethering([TetheringRequestParcel, String, String, IIntResultListener])`
     *   args={parcel, 包名, null, proxy}；sdk 30 三参（无 String）；sdk 26-29 反射
     *   `IConnectivityManager.startTethering(int=0, ResultReceiver, boolean=false)`（分段兼容）；
     * - 成功判定：回调 onResult 错误码 0 即成功；回调未达时轮询确认 `WifiManager.isWifiApEnabled`
     *   （8.x 可用）或 `getWifiApState`∈{13=WIFI_AP_STATE_ENABLED}；状态不可读（hidden API 拦截）
     *   则以「回调码 0 即成功」为准；全程 8s 超时兜底；
     * - 成功 → 系统预配热点已开（SSID/密码为系统配置、App 不可读）→ 主线程触发
     *   [HotspotListener.onSystemHotspotPasswordRequest]，返回 [BINDER_TETHER_PENDING] 标记，
     *   [completeSystemHotspotPassword] 登记后经 [dispatchBinderTetherResult] 收敛成功结果；
     * - 失败（快失败/异常/回调错误码非 0/8s 超时）→ 失败透传（不吞），上层降级原反射 setWifiApEnabled。
     *
     * 线程：随 [startAsync] 后台线程执行（8s 确认等待不占主线程）；回调在 binder 线程写
     * AtomicInteger（可见性安全）；UI 回调统一主线程 post。
     */
    private fun tryBinderTether(ctx: Context, wm: WifiManager?): HotspotResult {
        val sdk = Build.VERSION.SDK_INT
        // 版本门控：sdk ≥34 不裸调 startTethering（MakroDroid 已切换无障碍点磁贴/Shizuku，逆向结论）
        if (sdk >= 34) {
            val err = "sdk34+ 不裸调 startTethering（逆向结论），降级 ③"
            DiagLogger.log(tag, "L1_PRIVATE_API Binder 直呼快失败：sdk=$sdk → $err")
            return HotspotResult(success = false, error = err)
        }

        // 回调错误码（AtomicInteger：binder 回调线程写入、本线程轮询读取；CODE_NOT_RECEIVED=回调未达）
        val binderCode = AtomicInteger(CODE_NOT_RECEIVED)

        return try {
            if (sdk >= 30) {
                // ---- sdk 30-33：ITetheringConnector.startTethering（MakroDroid T1 骨架照抄） ----
                val parcelCls = Class.forName("android.net.TetheringRequestParcel")
                val parcel = parcelCls.newInstance()
                parcelCls.getDeclaredField("tetheringType").apply { isAccessible = true }
                    .setInt(parcel, TETHERING_TYPE_WIFI) // 0=WIFI（AOSP TetheringManager.TETHERING_WIFI）
                parcelCls.getDeclaredField("showProvisioningUi").apply { isAccessible = true }
                    .setBoolean(parcel, false)

                val binder = serviceBinder("tethering")
                    ?: throw IllegalStateException("ServiceManager.getService(\"tethering\") 返回 null")
                val connector = Class.forName("android.net.ITetheringConnector\$Stub")
                    .getMethod("asInterface", IBinder::class.java)
                    .invoke(null, binder)
                    ?: throw IllegalStateException("ITetheringConnector\$Stub.asInterface 返回 null")

                // 回调：Proxy 动态代理（MakroDroid 的 ua0/ta0 为空实现；我们做真实现——记录错误码）
                val listenerCls = Class.forName("android.net.IIntResultListener")
                val listenerProxy = Proxy.newProxyInstance(
                    listenerCls.classLoader ?: ClassLoader.getSystemClassLoader(),
                    arrayOf(listenerCls),
                    InvocationHandler { _, method, args ->
                        if (method.name == "onResult" && args != null && args.isNotEmpty()) {
                            val code = args[0] as? Int
                            if (code != null) {
                                binderCode.set(code)
                                DiagLogger.log(tag, "Binder 直呼 onResult 回调：错误码=$code")
                            }
                        }
                        null // 其余方法（含 toString/equals）返回 null（照抄 MakroDroid 空 handler 语义）
                    },
                )

                val pkg = ctx.packageName
                val connectorIfaceCls = Class.forName("android.net.ITetheringConnector")
                if (sdk >= 31) {
                    // sdk≥31：四参 startTethering(TetheringRequestParcel, String, String, IIntResultListener)
                    val m = connectorIfaceCls.getMethod(
                        "startTethering",
                        parcelCls, String::class.java, String::class.java, listenerCls,
                    )
                    m.isAccessible = true
                    m.invoke(connector, parcel, pkg, null, listenerProxy)
                    DiagLogger.log(tag, "Binder 直呼：已调用 startTethering(parcel, pkg=$pkg, null, proxy)（sdk=$sdk 四参）")
                } else {
                    // sdk 30：三参 startTethering(TetheringRequestParcel, String, IIntResultListener)
                    val m = connectorIfaceCls.getMethod(
                        "startTethering",
                        parcelCls, String::class.java, listenerCls,
                    )
                    m.isAccessible = true
                    m.invoke(connector, parcel, pkg, listenerProxy)
                    DiagLogger.log(tag, "Binder 直呼：已调用 startTethering(parcel, pkg=$pkg, proxy)（sdk=$sdk 三参）")
                }
            } else {
                // ---- sdk 26-29：IConnectivityManager.startTethering(int=0, ResultReceiver, boolean=false) 分段兼容 ----
                val binder = serviceBinder("connectivity")
                    ?: throw IllegalStateException("ServiceManager.getService(\"connectivity\") 返回 null")
                val cmCls = Class.forName("android.net.IConnectivityManager")
                val cm = cmCls.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
                    ?: throw IllegalStateException("IConnectivityManager\$Stub.asInterface 返回 null")
                val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
                    override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                        binderCode.set(resultCode)
                        DiagLogger.log(tag, "IConnectivityManager.startTethering 结果回调：resultCode=$resultCode")
                    }
                }
                val m = cmCls.getMethod(
                    "startTethering",
                    java.lang.Integer.TYPE, ResultReceiver::class.java, java.lang.Boolean.TYPE,
                )
                m.isAccessible = true
                m.invoke(cm, TETHERING_TYPE_WIFI, receiver, false)
                DiagLogger.log(tag, "Binder 直呼：已调用 IConnectivityManager.startTethering(0, receiver, false)（sdk=$sdk）")
            }

            // 成功判定：回调错误码 0 即成功；回调未达时轮询 isWifiApEnabled / getWifiApState∈{13} 确认；
            // 状态不可读以「回调码 0」为准；8s 超时兜底
            val deadline = System.currentTimeMillis() + BINDER_CONFIRM_TIMEOUT_MS
            var stateApiWarned = false
            while (System.currentTimeMillis() < deadline) {
                val code = binderCode.get()
                if (code == TETHER_ERROR_NO_ERROR) {
                    DiagLogger.log(tag, "Binder 直呼成功：回调错误码 0（系统预配热点已开启，SSID/密码为系统配置）")
                    return binderSuccess()
                }
                if (code != CODE_NOT_RECEIVED) {
                    val err = "Binder 直呼回调错误码=$code（非 0，系统拒绝/失败）"
                    DiagLogger.log(tag, "L1_PRIVATE_API：$err")
                    return HotspotResult(success = false, error = err)
                }
                when (pollHotspotStateOnce(wm)) {
                    STATE_ON -> {
                        DiagLogger.log(tag, "Binder 直呼成功（回调未达，状态轮询确认热点已开启）")
                        return binderSuccess()
                    }
                    STATE_UNAVAILABLE -> if (!stateApiWarned) {
                        stateApiWarned = true
                        DiagLogger.log(tag, "Binder 直呼状态轮询不可用（hidden API 拦截），以「回调码 0 即成功」为准")
                    }
                    else -> { /* STATE_OFF：继续等待回调/轮询 */ }
                }
                try {
                    Thread.sleep(BINDER_POLL_INTERVAL_MS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return HotspotResult(success = false, error = "Binder 直呼确认等待被中断")
                }
            }
            val err = "Binder 直呼 ${BINDER_CONFIRM_TIMEOUT_MS / 1000}s 超时：无回调且状态未确认"
            DiagLogger.log(tag, "L1_PRIVATE_API：$err")
            HotspotResult(success = false, error = err)
        } catch (e: Exception) {
            // 不吞异常：记录 + 如实透传（含异常类；hidden API 拦截 / 服务端拒绝均在此落）
            DiagLogger.log(tag, "L1_PRIVATE_API Binder 直呼异常（不吞）: $e")
            HotspotResult(
                success = false,
                error = "Binder 直呼 startTethering 异常: ${e.javaClass.simpleName}: ${e.message}",
            )
        }
    }

    /** 反射 `ServiceManager.getService(name)` → IBinder（② Binder 直呼用；失败返回 null 由调用方抛 IllegalStateException 收敛）。 */
    private fun serviceBinder(name: String): IBinder? = try {
        val m = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
        m.isAccessible = true
        m.invoke(null, name) as? IBinder
    } catch (e: Exception) {
        DiagLogger.log(tag, "ServiceManager.getService($name) 反射异常: $e")
        null
    }

    /**
     * 单次状态轮询确认（② Binder 直呼成功判定辅助）：优先反射 `isWifiApEnabled`（8.x 公开可用）；
     * 被 hidden API 拦截时退回反射 `getWifiApState` ∈ {13=WifiManager.WIFI_AP_STATE_ENABLED}；
     * 两者均不可用返回 [STATE_UNAVAILABLE]（不判定失败——以「回调码 0 即成功」为准）。
     */
    private fun pollHotspotStateOnce(wm: WifiManager?): Int {
        if (wm == null) return STATE_UNAVAILABLE
        try {
            val m = WifiManager::class.java.getMethod("isWifiApEnabled")
            m.isAccessible = true
            if (m.invoke(wm) == true) return STATE_ON
            return STATE_OFF
        } catch (e: Exception) {
            // isWifiApEnabled 不可用（hidden API 拦截）→ 降级 getWifiApState
        }
        return try {
            val m = WifiManager::class.java.getMethod("getWifiApState")
            m.isAccessible = true
            val state = (m.invoke(wm) as? Int) ?: -1
            if (state == 13) STATE_ON else STATE_OFF // AOSP WIFI_AP_STATE_ENABLED=13（compileSdk37 jar 常量对 Kotlin 不可见）
        } catch (e: Exception) {
            STATE_UNAVAILABLE
        }
    }

    /**
     * ② Binder 直呼成功收敛：系统预配热点已开启（SSID/密码为系统配置、App 不可读）——
     * 主线程触发 [HotspotListener.onSystemHotspotPasswordRequest] 请用户登记本机系统热点
     * SSID+密码；返回 [BINDER_TETHER_PENDING] 标记（startAsync 持有异步闸，等待
     * [completeSystemHotspotPassword] 经 [dispatchBinderTetherResult] 收敛成功结果，ip 现采）。
     */
    private fun binderSuccess(): HotspotResult {
        DiagLogger.log(
            tag,
            "Binder 直呼成功：系统预配热点已开启（SSID/密码为系统配置、App 不可读），请求用户登记本机系统热点 SSID+密码",
        )
        mainHandler.post { listener.onSystemHotspotPasswordRequest() }
        return HotspotResult(success = false, error = BINDER_TETHER_PENDING)
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
     * （P+ 可能被 hidden API 拦截，失败如实返回 null）。
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

    // ================= ③ L2_LOCAL_ONLY 真路径（B3：Local-only 本地热点） =================

    /**
     * ③ L2 本地热点（Local-only，无密码局域网；B3 真实现）——三版本分流（design 定稿）：
     * - sdk 26–28（Android 8-9）：`WifiManager.startLocalOnlyHotspot(callback, mainHandler)`（公开
     *   API 26+）→ [LocalOnlyHotspotReservation.wifiConfiguration] 读 SSID（已含引号，去引号）与
     *   preSharedKey（系统下发的随机密码）→ 全自动返回成功 [HotspotResult]（IP 经 [collectHotspotIp]
     *   采集，参考 ②；此路径免人工）；
     * - sdk 29–32（Android 10-12）：**本级禁用**（密码盲区：系统不下发可读密码，行为不可靠）→
     *   直接返回 `HotspotResult(false, error="LocalOnlyHotspot 密码盲区(10-12 禁用)，降级 ④")`
     *   交状态机降级 ④（手动）；
     * - sdk 33+（Android 13+）：onStarted 后系统弹窗/通知展示 SSID 与密码，App 侧
     *   [LocalOnlyHotspotReservation.wifiConfiguration] 密码不可读（软 AP 配置不回传密码）→
     *   触发 [HotspotListener.onLocalOnlyPasswordRequest](ssid) 请 UI 弹密码登记框、用户按系统弹窗
     *   回填 → [completeLocalOnlyPassword] 完成后返回成功 [HotspotResult]（ssid / pwd=用户登记值 / ip）；
     * - 失败路径：onFailed(reason)（系统 reason 映射见 [localOnlyErrorText]）/ onStopped（等待期被
     *   系统停止）/ 异常 → 失败透传，交状态机降级 ④。
     *
     * 异步：startLocalOnlyHotspot 本身异步（回调线程由传入 [mainHandler] 指定）——本方法同步调用后
     * 立即返回 [LOCAL_ONLY_PENDING] 标记；最终结果由 [localOnlyCallback]（onStarted/onFailed/onStopped）
     * 经 [dispatchLocalOnlyResult] 收敛到 [pendingLocalOnlyCb]（状态机经 [startAsync] 传入）。
     *
     * 生命周期（B4 正式收尾前）：onStarted 持有 [LocalOnlyHotspotReservation] 到组网收尾，
     * [stopLocalOnly] 为预留释放入口（引擎 onAbort / stopAllBle 接线，幂等）。
     *
     * 线程：startSyncInternal 由 [startAsync] 后台 executor 调用，startLocalOnlyHotspot 的系统回调
     * 经 mainHandler 回主线程；onLocalOnlyPasswordRequest / completeLocalOnlyPassword 亦在主线程。
     */
    @Suppress("DEPRECATION") // startLocalOnlyHotspot(callback, handler) 自 API 33 起弃用（改无 handler 重载），26+ 统一走此重载
    private fun tryLocalOnlyHotspot(): HotspotResult {
        val sdk = Build.VERSION.SDK_INT
        // sdk 29–32：本级禁用（密码盲区）——直接失败交状态机降级 ④（不调系统 API）
        if (sdk in 29..32) {
            val err = "LocalOnlyHotspot 密码盲区(10-12 禁用)，降级 ④"
            DiagLogger.log(tag, "L2_LOCAL_ONLY 本级禁用：sdk=$sdk → $err")
            return HotspotResult(success = false, error = err)
        }

        val ctx = resolveContext()
        if (ctx == null) {
            val err = "L2_LOCAL_ONLY：Context 不可用（注入与 ActivityThread.currentApplication() 兜底均失败）"
            DiagLogger.log(tag, err)
            return HotspotResult(success = false, error = err)
        }
        val wm = resolveWifiManager(ctx)
        if (wm == null) {
            val err = "L2_LOCAL_ONLY：WifiManager 不可用（Context 已取得但 getSystemService 失败）"
            DiagLogger.log(tag, err)
            return HotspotResult(success = false, error = err)
        }

        DiagLogger.log(
            tag,
            "L2_LOCAL_ONLY：sdk=$sdk 调用 startLocalOnlyHotspot(callback, mainHandler)" +
                "（26-28 全自动 / 33+ 密码回填；结果主线程回调收敛）",
        )
        return try {
            wm.startLocalOnlyHotspot(localOnlyCallback, mainHandler)
            // 真异步：同步返回 pending 标记，最终结果由 localOnlyCallback 收敛（不在此同步返回）
            HotspotResult(success = false, error = LOCAL_ONLY_PENDING)
        } catch (e: Exception) {
            // 不吞异常：记录 + 如实透传（含异常类；如 SecurityException / UnsupportedOperationException）
            DiagLogger.log(tag, "L2_LOCAL_ONLY startLocalOnlyHotspot 调用异常（不吞）: $e")
            HotspotResult(
                success = false,
                error = "LocalOnlyHotspot 启动异常: ${e.javaClass.simpleName}: ${e.message}",
            )
        }
    }

    /**
     * ③ L2 系统回调（startLocalOnlyHotspot 结果；经 mainHandler 主线程回调）：
     * onStarted → 三版本分流（26-28 全自动 / 33+ 密码回填）；onFailed → 失败透传（含系统 reason）；
     * onStopped → 释放持有并收敛（等待期被系统停止时按失败处理）。
     */
    @Suppress("DEPRECATION") // LocalOnlyHotspotCallback 与 startLocalOnlyHotspot 同源弃用（API 33+），26+ 唯一公开路径
    private val localOnlyCallback = object : LocalOnlyHotspotCallback() {
        override fun onStarted(reservation: LocalOnlyHotspotReservation) {
            handleLocalOnlyStarted(reservation)
        }

        override fun onStopped() {
            handleLocalOnlyStopped()
        }

        override fun onFailed(reason: Int) {
            handleLocalOnlyFailed(reason)
        }
    }

    /** ③ onStarted 收敛（主线程）：持有 reservation → 三版本分流（26-28 全自动 / 33+ 请求密码回填）。 */
    @Suppress("DEPRECATION") // reservation.wifiConfiguration 为 WifiConfiguration 旧 API（26+ 公开），软 AP 密码回传行为随版本分流
    private fun handleLocalOnlyStarted(reservation: LocalOnlyHotspotReservation) {
        localOnlyReservation = reservation // 持有到组网收尾（B4 正式收尾前由 [stopLocalOnly] 释放）
        val sdk = Build.VERSION.SDK_INT
        val cfg = try {
            reservation.wifiConfiguration
        } catch (e: Exception) {
            DiagLogger.log(tag, "L2_LOCAL_ONLY onStarted 读 wifiConfiguration 异常: $e")
            null
        }
        val ssid = cfg?.SSID?.trim()?.removeSurrounding("\"") ?: ""
        if (ssid.isBlank()) {
            DiagLogger.log(tag, "L2_LOCAL_ONLY onStarted：SSID 缺失（系统未下发），按失败处理")
            dispatchLocalOnlyResult(
                HotspotResult(success = false, error = "LocalOnlyHotspot 已启动但 SSID 缺失"),
            )
            return
        }
        // sdk 26–28：公开 API 26+ 可读 preSharedKey（系统随机密码）→ 全自动成功
        if (sdk in 26..28) {
            val pwd = cfg?.preSharedKey?.trim()?.removeSurrounding("\"")
            if (pwd.isNullOrBlank()) {
                DiagLogger.log(tag, "L2_LOCAL_ONLY onStarted(sdk=$sdk)：preSharedKey 缺失（系统未下发密码），按失败处理")
                dispatchLocalOnlyResult(
                    HotspotResult(success = false, ssid = ssid, error = "LocalOnlyHotspot 已启动但系统未下发密码"),
                )
                return
            }
            val ip = collectHotspotIp()
            DiagLogger.log(
                tag,
                "L2_LOCAL_ONLY 自动路径成功：sdk=$sdk ssid=$ssid pwdLen=${pwd.length} ip=${ip.ifEmpty { "<空>" }}（密码不回显）",
            )
            dispatchLocalOnlyResult(HotspotResult(success = true, ssid = ssid, pwd = pwd, ip = ip))
            return
        }
        // sdk 33+：App 侧密码不可读（软 AP 配置不回传密码；系统弹窗/通知展示 SSID 与密码）→
        // 触发 UI 请用户按系统弹窗回填密码，完成经 [completeLocalOnlyPassword] 收敛
        pendingLocalOnlySsid = ssid
        DiagLogger.log(
            tag,
            "L2_LOCAL_ONLY(sdk=$sdk)：密码不可读（系统弹窗/通知展示），触发 onLocalOnlyPasswordRequest(ssid=$ssid) 请用户回填",
        )
        listener.onLocalOnlyPasswordRequest(ssid)
        // 等待 completeLocalOnlyPassword(pwd) 收敛（pendingLocalOnlyCb 保留；状态机步骤超时已放宽 120s）
    }

    /** ③ onFailed 收敛（主线程）：系统 reason 映射为可读文案，失败透传交状态机降级 ④。 */
    private fun handleLocalOnlyFailed(reason: Int) {
        val text = localOnlyErrorText(reason)
        DiagLogger.log(tag, "L2_LOCAL_ONLY onFailed(reason=$reason:$text)，失败透传（降级 ④）")
        dispatchLocalOnlyResult(
            HotspotResult(success = false, error = "LocalOnlyHotspot 启动失败($text)"),
        )
    }

    /** ③ onStopped 收敛（主线程）：释放持有；若仍在等待（onStarted 后密码未回填）按失败收敛。 */
    private fun handleLocalOnlyStopped() {
        localOnlyReservation = null
        if (pendingLocalOnlyCb != null) {
            DiagLogger.log(tag, "L2_LOCAL_ONLY onStopped（等待密码回填期间被系统停止），按失败收敛")
            dispatchLocalOnlyResult(HotspotResult(success = false, error = "LocalOnlyHotspot 已停止"))
        } else {
            DiagLogger.log(tag, "L2_LOCAL_ONLY onStopped（无待收敛结果，仅记录释放）")
        }
    }

    /**
     * ③ L2 结果收敛（主线程）：释放 pending 状态与异步闸 → 回调 [pendingLocalOnlyCb]
     * （状态机 onLocalOnlyAsyncResult；回调侧自行校验当前状态，可能已被 cancel/超时置空而忽略）。
     */
    private fun dispatchLocalOnlyResult(result: HotspotResult) {
        pendingLocalOnlySsid = null
        val cb = pendingLocalOnlyCb
        pendingLocalOnlyCb = null
        asyncRunning.set(false) // L2 真异步收敛时统一释放 isRunning（防等待期重复启动）
        cb?.invoke(result)
        if (cb == null) {
            DiagLogger.log(
                tag,
                "L2_LOCAL_ONLY 结果无可收敛回调（pendingLocalOnlyCb=null，可能已取消/停止），仅记录 success=${result.success}",
            )
        }
    }

    /** ③ onFailed reason → 可读文案。
     * 框架常量（WifiManager.LOCAL_ONLY_HOTSPOT_ERROR_*）在 compileSdk37（AGP9 内置 jar）对 Kotlin 不可见，
     * 改用 AOSP 字面量（1/2/3/4，API 26+ 稳定；含 API 33+ 新增 ERROR_TETHERING_DISALLOWED）。 */
    private fun localOnlyErrorText(reason: Int): String = when (reason) {
        1 -> "ERROR_GENERIC"
        2 -> "ERROR_NO_CHANNEL"
        3 -> "ERROR_INCOMPATIBLE_MODE"
        4 -> "ERROR_TETHERING_DISALLOWED"
        else -> "未知($reason)"
    }

    /**
     * ③ sdk 33+ 密码回填（引擎在用户按系统弹窗回填后调用，主线程）：校验非空后完成
     * L2 成功结果（ssid / pwd=用户登记值 / ip=采集）并收敛 [pendingLocalOnlyCb]
     * （状态机 onLocalOnlyAsyncResult → onHotspotReady 发 offer）。密码全程不回显。
     */
    fun completeLocalOnlyPassword(pwd: String) {
        val ssid = pendingLocalOnlySsid
        if (ssid.isNullOrBlank()) {
            DiagLogger.log(tag, "completeLocalOnlyPassword 忽略：无待回填的 L2 流程（pendingLocalOnlySsid=null）")
            return
        }
        if (pwd.isBlank()) {
            DiagLogger.log(tag, "completeLocalOnlyPassword：密码为空，保持等待回填（不收敛）")
            return
        }
        pendingLocalOnlySsid = null
        val ip = collectHotspotIp()
        DiagLogger.log(
            tag,
            "L2_LOCAL_ONLY 回填路径成功：ssid=$ssid pwdLen=${pwd.length} ip=${ip.ifEmpty { "<空>" }}（密码不回显）",
        )
        dispatchLocalOnlyResult(HotspotResult(success = true, ssid = ssid, pwd = pwd, ip = ip))
    }

    // ================= ② Binder 直呼系统热点（v0.3.4 增强：系统预配热点自动开） =================

    /**
     * ② 系统预配热点（Binder 直呼成功）SSID+密码登记（引擎在用户按提示填写本机系统热点名称与密码后调用，主线程）：
     * 校验非空后组装成功结果（ssid=用户登记值、pwd=登记值、ip=现采）并收敛 [pendingBinderTetherCb]
     * （状态机 onPrivateApiAsyncResult → onHotspotReady 发 offer）。密码全程不回显。
     */
    fun completeSystemHotspotPassword(ssid: String, pwd: String) {
        val cb = pendingBinderTetherCb
        if (cb == null) {
            DiagLogger.log(tag, "completeSystemHotspotPassword 忽略：无待收敛的 Binder 直呼结果（pendingBinderTetherCb=null）")
            return
        }
        if (ssid.isBlank() || pwd.isBlank()) {
            DiagLogger.log(tag, "completeSystemHotspotPassword：SSID/密码为空，保持登记框等待回填（不收敛）")
            return
        }
        val ip = collectHotspotIp()
        DiagLogger.log(
            tag,
            "L1_PRIVATE_API Binder 直呼（系统预配热点）登记成功：ssid=$ssid pwdLen=${pwd.length} ip=${ip.ifEmpty { "<空>" }}（密码不回显）",
        )
        dispatchBinderTetherResult(HotspotResult(success = true, ssid = ssid, pwd = pwd, ip = ip))
    }

    /**
     * ② Binder 直呼结果收敛（主线程）：释放待收敛状态与异步闸 → 回调 [pendingBinderTetherCb]
     * （状态机 onPrivateApiAsyncResult；回调侧自行校验状态防时序漂移）。
     */
    private fun dispatchBinderTetherResult(result: HotspotResult) {
        val cb = pendingBinderTetherCb
        pendingBinderTetherCb = null
        asyncRunning.set(false) // Binder 直呼登记收敛时统一释放 isRunning（防登记等待期重复启动）
        cb?.invoke(result)
        if (cb == null) {
            DiagLogger.log(
                tag,
                "Binder 直呼结果无可收敛回调（pendingBinderTetherCb=null，可能已取消/超时中止），仅记录 success=${result.success}",
            )
        }
    }

    /**
     * ② 系统预配热点待登记清理（收尾兜底，幂等；与 [stopLocalOnly] 同语义）：用户登记框打开期间
     * 组网被中止/收尾（状态机 15s 步骤超时 abort、onAbort、stopAllBle）时释放待收敛的 Binder 结果
     * 与异步闸，防止后续启动被悬挂（登记结果到达时 pendingBinderTetherCb=null → 不再上抛）。
     */
    fun stopBinderTetherPending() {
        val cb = pendingBinderTetherCb
        pendingBinderTetherCb = null
        asyncRunning.set(false)
        if (cb != null) {
            DiagLogger.log(tag, "stopBinderTetherPending：清理待收敛的 Binder 直呼结果（登记被中止，结果不再上抛）")
        } else {
            DiagLogger.log(tag, "stopBinderTetherPending：无待收敛的 Binder 直呼结果（幂等 no-op）")
        }
    }

    /**
     * ③ L2 本地热点收尾预留入口（B4 正式收尾前：reservation 持有与 close 入口；幂等）：
     * 关闭 [localOnlyReservation]（系统随后回调 onStopped → 释放持有并收敛待定结果），
     * 并清理待收敛的 L2 pending（等待系统回调/密码回填期间被中止时，防止异步闸与回调悬挂
     * 阻塞后续启动）。引擎在组网中止/结束（onAbort、stopAllBle）接线调用。
     */
    fun stopLocalOnly() {
        val r = localOnlyReservation
        localOnlyReservation = null
        if (r != null) {
            try {
                r.close()
                DiagLogger.log(tag, "stopLocalOnly：已 close LocalOnlyHotspotReservation（B4 正式收尾前预留释放入口）")
            } catch (e: Exception) {
                DiagLogger.log(tag, "stopLocalOnly：close 异常（不吞）: $e")
            }
        } else {
            DiagLogger.log(tag, "stopLocalOnly：无持有中的 LocalOnlyHotspotReservation（幂等 no-op）")
        }
        // 收尾兜底：无论是否持有 reservation，清理待收敛状态（含等待系统回调但未收到任何回执的场景）
        pendingLocalOnlySsid = null
        val cb = pendingLocalOnlyCb
        pendingLocalOnlyCb = null
        asyncRunning.set(false)
        if (cb != null) {
            DiagLogger.log(tag, "stopLocalOnly：清理待收敛的 L2 回调（原等待被中止，结果不再上抛）")
        }
    }

    /**
     * 取热点本机 IPv4（② 用；NetworkInterface 枚举按热点网段打分）：
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

    /** 接口名/网段打分（② 用）：ap 系 +100、192.168.43.x +50、192.168.x +20、wlan +10、10./172. +5。 */
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

        /** ③ L2 真异步 pending 标记（startSyncInternal 同步返回；最终结果由 LocalOnlyHotspotCallback 收敛）。 */
        private const val LOCAL_ONLY_PENDING = "LocalOnlyPending"

        /** ② 反射 setWifiApEnabled 后轮询 isWifiApEnabled 的最长等待（任务约定 ≤5s）。 */
        private const val PRIVATE_AP_POLL_TIMEOUT_MS: Long = 5_000L

        /** ② 轮询 isWifiApEnabled 间隔（任务约定 400ms）。 */
        private const val PRIVATE_AP_POLL_INTERVAL_MS: Long = 400L

        /** ② Binder 直呼成功待登记标记（error 字段；等待用户登记本机系统热点 SSID+密码后经 [completeSystemHotspotPassword] 收敛）。 */
        private const val BINDER_TETHER_PENDING = "BinderTetherPending"

        /** ② Binder 直呼回调/状态轮询确认最长等待（任务约定 8s 超时兜底）。 */
        private const val BINDER_CONFIRM_TIMEOUT_MS: Long = 8_000L

        /** ② Binder 直呼回调未达时状态轮询间隔（与 ② 反射轮询同节奏 400ms）。 */
        private const val BINDER_POLL_INTERVAL_MS: Long = 400L

        /** ② IIntResultListener 回调未达的初始错误码（区别于真实回调码）。 */
        private const val CODE_NOT_RECEIVED = -1

        /** ② TetheringRequestParcel.tetheringType=0（WIFI，AOSP TetheringManager.TETHERING_WIFI）。 */
        private const val TETHERING_TYPE_WIFI = 0

        /** ② startTethering 成功回调码（AOSP TetherErrorCode NO_ERROR=0 / IConnectivityManager TETHER_ERROR_NO_ERROR=0）。 */
        private const val TETHER_ERROR_NO_ERROR = 0

        /** ② Binder 直呼状态轮询结果：热点已开启（isWifiApEnabled=true 或 getWifiApState==WIFI_AP_STATE_ENABLED）。 */
        private const val STATE_ON = 1

        /** ② Binder 直呼状态轮询结果：热点未开启（继续等待回调/轮询）。 */
        private const val STATE_OFF = 0

        /** ② Binder 直呼状态轮询结果：状态 API 不可用（hidden 拦截，以「回调码 0 即成功」为准）。 */
        private const val STATE_UNAVAILABLE = -1
    }
}
