package com.zglinus.bluelink.networking

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiManager.LocalOnlyHotspotCallback
import android.net.wifi.WifiManager.LocalOnlyHotspotReservation
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.provider.Settings
import android.widget.Toast
import com.zglinus.bluelink.ble.RootDetector
import com.zglinus.bluelink.diag.DiagLogger
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.Executor
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

    /** ② L1 自动热点：私有 API 通道（v0.3.8 改 k1/c 式按名枚举：ConnectivityManager 类自身 getDeclaredMethods 找 "startTethering"（MakroDroid k1/c 手法，真机实锤覆盖 sdk31 的 IConnectivityManager 签名差异）→ invoke 成功 + 状态确认 → systemTetherSuccess 登记复用；失败降级反射 setWifiApEnabled，见 [tryPrivateApiHotspot]）。 */
    L1_PRIVATE_API,

    /** ③ L2 本地热点：Local-only 无密码局域网（Android 8-9 或 13+ 可用；10-12 盲区假设 v0.3.9.2 起放行调用 + onStarted 统一先试读实测：试读非空即推翻假设）。 */
    L2_LOCAL_ONLY,

    /** ④ 手动配网：UI 提示用户手工输入/分享热点。 */
    MANUAL,
}

/**
 * 热点启动结果。
 *
 * @param success 是否成功开启热点；false 时 [error] 给出降级/等待原因。
 * @param ssid 热点 SSID（成功时返回，供对端连接；手动路径由 UI 回填）。
 * @param pwd 热点密码（成功时返回；② 私有 API 路径由本包自设随机密码；③ L2 本地热点 onStarted 统一
 *   先试读 preSharedKey（v0.3.9-verify ③-① 实测定案；v0.3.9.2 起 26-32 同样先试读）——非空自动完成；
 *   空/null 时 33+ 由用户按系统弹窗回填登记、26-32 按盲区失败降级；手动路径由 UI 回填）。
 * @param ip 热点本机 IPv4（② 私有 API 路径启动后采集；未取到为空串 ""，一期允许）。
 * @param error 失败/等待原因（如 root 路径已停用(B1 移除) 降级、③ L2 盲区失败（sdk 26-32 试读空）/系统 reason/启动异常
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
     * ③ L2 本地热点（13+，sdk 33+）：onStarted 后**先试读 preSharedKey**（v0.3.9-verify ③-①：
     * 网页版主张 13+ 授权 NEARBY_WIFI_DEVICES 后可直接读，以实测定案），试读为空/null
     * （软 AP 配置未回传密码；系统弹窗/通知展示 SSID 与密码）才触发本回调——请求 UI 弹出密码
     * 登记框，请用户按系统弹窗回填密码；回填后经 [HotspotManager.completeLocalOnlyPassword]
     * 完成 L2 成功结果（26-32 试读为空按盲区失败降级 ④，不触发本回调）。
     */
    fun onLocalOnlyPasswordRequest(ssid: String)

    /**
     * ② Binder 直呼系统热点成功（v0.3.4 增强）：系统预配热点已自动开启，但 SSID/密码为系统配置、
     * App 侧不可读——请求 UI 弹出登记框（复用 ④ manualPwdDialog 登记框，模式置
     * systemHotspotPwdMode），请用户按本机热点信息登记 SSID+密码；回填后经
     * [HotspotManager.completeSystemHotspotPassword] 完成 ② 成功结果（ip 现采）。
     */
    fun onSystemHotspotPasswordRequest()

    /**
     * NEARBY_WIFI_DEVICES（Android 13+，Manifest 已声明 neverForLocation）运行时授权前置缺失——
     * 请求 UI/引擎发起系统授权（复用现有 requestedPermission 授权链，Engine 已接），授权后自动
     * 重试触发方：
     * - ② Binder 直呼系统热点（v0.3.6 第一手段）前置缺失：未授权时本等级失败透传、照旧降级 ③；
     * - ③ L2 本地热点（v0.3.9-verify ③-②）调 startLocalOnlyHotspot 前的前置缺失：未授权 →
     *   引导授权并返回 AwaitingNearbyPermission，授权后经 Engine.handleHotspotPermissionRetry
     *   重跑组网（② 降级后重入 ③）。
     */
    fun onNeedNearbyPermission()
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
 * - ②（[HotspotStartLevel.L1_PRIVATE_API]，B2 真实现 + v0.3.8 k1/c 式按名枚举）：私有 API 热点
 *   （① 停用后的降级主路径）——优先级定案：**k1/c 式按名枚举 ConnectivityManager 类自身 hidden
 *   startTethering/stopTethering（第一手段，v0.3.8；MakroDroid k1/c 手法——`getDeclaredMethods()`
 *   按方法名找 "startTethering"，运行时挑可调签名；真机实锤：固定 3/4 参在 sdk31 的
 *   IConnectivityManager 必 NoSuchMethod，靠按名枚举成功开热点）→ invoke 成功 + 2s +
 *   mdWifiApEnabled 确认 → systemTetherSuccess 登记复用；失败 → mdTetherBinder 兜底（次选）
 *   → 反射 setWifiApEnabled 降级（8-9）→ 失败透传**（见 [tryPrivateApiHotspot]）：
 *   - 前置 WRITE_SETTINGS：`Settings.System.canWrite(ctx)` 未授权 → 回调
 *     [HotspotListener.onWriteSettingsPermission] 引导「修改系统设置」（Android 10+ 反射
 *     `setWifiApEnabled` 需此 AppOps），返回 `HotspotResult(false, error="AwaitingWriteSettings")`
 *     待授权后重试；ctx 缺省时经 `ActivityThread.currentApplication()` 反射兜底（见 [resolveContext]）；
 *   - 前置 NEARBY_WIFI_DEVICES（sdk≥33）：`ctx.checkSelfPermission` 未授权 → 回调
 *     [HotspotListener.onNeedNearbyPermission] 引导授权（Engine 复用 requestedPermission 授权链，
 *     授权后自动重试本等级），返回 `HotspotResult(false, error="NeedNearbyPermission")` 失败透传；
 *   - 反射 `WifiManager.setWifiApEnabled(config, true)`（`java.lang.Boolean.TYPE` 精匹配），
 *     构造 WifiConfiguration：SSID=Bluelink-XXXX（4 位随机）/ 随机 8 位密码 / WPA2
 *     （`allowedKeyManagement` 置位 4，即 KeyMgmt.WPA2_PSK）/ `isAccessible=true`；
 *   - 轮询校验：反射 `isWifiApEnabled` ≤5s / 400ms，置 true 即成功，超时/异常 → 失败；
 *   - 成功后采集热点本机 IPv4（NetworkInterface 枚举按热点网段打分，免 root）；
 *   - 失败原因透传（含异常类）交状态机降级 ③；密码全程不回显；
 *   - 运行时 try 实测降级、不预验：真机（A15/KernelSU）大概率 `NoSuchMethodException` 落失败 ③，
 *     8-13 部分机型/ROM 仍可（压力路径）；
 * - ③（[HotspotStartLevel.L2_LOCAL_ONLY]，B3 真实现）：Local-only 本地热点——公开 API
 *   `WifiManager.startLocalOnlyHotspot(callback, handler)` 三版本分流（design 定稿 + v0.3.9.2 补丁，
 *   见 [tryLocalOnlyHotspot]）：
 *   sdk 26-28 全自动（onStarted 读 `reservation.wifiConfiguration` 的 SSID/preSharedKey + 采集 IP）；
 *   sdk 29-32 v0.3.9.2 起**放行调用**（移除「盲区直接禁用」）——onStarted 统一先试读 preSharedKey
 *   实测「10-12 盲区」假设（真机 A12/sdk31）：非空 → 推翻假设、自动完成；空 → 确认盲区，
 *   报 `"LocalOnlyHotspot 密码不可读（sdk=X 实测盲区），降级 ④"`；
 *   sdk 33+ 调 startLocalOnlyHotspot 前置 NEARBY_WIFI_DEVICES 运行时授权（v0.3.9-verify ③-②：
 *   未授权 → [HotspotListener.onNeedNearbyPermission] 引导、授权后重试；返回 AwaitingNearbyPermission）；
 *   onStarted 统一先试读 preSharedKey（26-33 全走同一逻辑；v0.3.9-verify ③-①：网页版主张 13+ 授权后
 *   可读，实测定案）——非空自动完成（无论 sdk），空/null：33+ 触发
 *   [HotspotListener.onLocalOnlyPasswordRequest] 请用户回填、26-32 盲区失败降级 ④；
 *   [completeLocalOnlyPassword] 完成后返回成功结果。真异步：系统回调经主线程
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
 * `privateApiCapable` 判定一致）；v0.3.8 起第一手段为 k1/c 式按名枚举（MakroDroid k1/c 手法：
 * ConnectivityManager 类自身 getDeclaredMethods 找 "startTethering"，运行时挑可调签名——覆盖
 * sdk31 的 IConnectivityManager 签名差异），真实可行性由运行时 try 实测收口
 * （见 [tryBinderTether] / [tryPrivateApiHotspot]）。
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

    /**
     * v0.5.9 UI1b-C 热点预设存储（懒初始化：构造 context 可空，经 resolveContext 兜底取；
     * 不可用（null）→ 预设不生效，完全维持现行为）。消费点：自设 SSID 路径（② 私有 API 反射降级）；
     * ③ LocalOnly 系统生成 SSID/密码**不适用**（不消费预设）；手动④ 预设仅用于预填提示（UI 任务消费）。
     */
    private val presetStore: HotspotPresetStore? by lazy {
        resolveContext()?.let { HotspotPresetStore(it) }
    }

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

    /** ② 系统预配热点（Binder 直呼）：成功后待收敛的异步回调（等待用户登记本机系统热点 SSID+密码，经 [dispatchBinderTetherResult] 收敛；登记被中止时经 [stopBinderTetherPending] 清理）。 */
    @Volatile
    private var pendingBinderTetherCb: ((HotspotResult) -> Unit)? = null

    /** 热点启动前的接口快照（接口名 → 首个 IPv4；旧 Wi-Fi 排除 / 热点开启后新接口识别用；不联网）。 */
    @Volatile
    private var preHotspotIfaces: Map<String, String> = emptyMap()

    /** 热点启动前旧 Wi-Fi 的网段（IP & mask，int；对应握手 net.ssid 时刻连接的旧 Wi-Fi；null=未采到则不排除）。 */
    @Volatile
    private var preHotspotWifiNet: Int? = null

    /** 热点启动前旧 Wi-Fi 的掩码（int；与 [preHotspotWifiNet] 配套）。 */
    @Volatile
    private var preHotspotWifiMask: Int? = null

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
     * - [HotspotStartLevel.L1_PRIVATE_API]：B2 真实现（k1/c 式按名枚举 ConnectivityManager hidden startTethering（v0.3.8）第一手段 + 反射降级，见 [tryPrivateApiHotspot]）；
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
        // ★ ② 系统预配热点（Binder 直呼）：L1_PRIVATE_API 成功路径（系统预配热点已开）需用户
        // 登记本机系统热点 SSID+密码——先在本线程登记待收敛 cb（与 L2 同语义，登记先于后台提交），
        // 成功路径经 dispatchBinderTetherResult 收敛；失败/降级路径照旧走下方主线程 cb。
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
            // ★ ② 系统预配热点：成功→等待系统热点密码登记（与 L2 同语义：不回调、不释放 isRunning
            // ——登记完成经 dispatchBinderTetherResult 统一收敛，防登记等待期重复启动）
            if (result.error == PUBLIC_TETHER_PENDING) {
                DiagLogger.log(
                    tag,
                    "startAsync(level=$level)：② 系统预配热点成功，等待 completeSystemHotspotPassword 登记收敛（不重复回调）",
                )
                return@execute
            }
            // 结果统一回主线程回调（状态机按主线程契约消费；回调前释放 isRunning 供下次启动）
            mainHandler.post {
                asyncRunning.set(false)
                pendingBinderTetherCb = null // 非 pending 结果：清理预留（防残留悬挂）
                pendingLocalOnlyCb = null // 非 pending 结果（含 ③-② 前置 AwaitingNearbyPermission 同步失败）：清理 L2 预留（防残留悬挂）
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

        // ② 私有 API 热点（B2 真实现 + v0.3.6 修正）：WRITE_SETTINGS/NEARBY 前置 +
        // Binder 直呼（第一手段）→ 反射 setWifiApEnabled 降级 → 失败透传，见 [tryPrivateApiHotspot]
        HotspotStartLevel.L1_PRIVATE_API -> tryPrivateApiHotspot()

        // ③ L2 本地热点（Local-only，无密码局域网）：B3 真实现——三版本分流
        // （26-28 全自动 / 29-32 放行调用+onStarted 统一先试读实测盲区（v0.3.9.2） / 33+ 密码回填），见 [tryLocalOnlyHotspot]
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
     * 10-12 按盲区保守判定（与 [Arbiter] 的 `localOnlyAvailable` 一致；v0.3.9.2 不改此仲裁判定——
     * 29-32 放行实测以 [localOnlySelfTest] 自测入口为主，状态机仲裁仍按现状）。
     */
    fun isLevel2Available(sdkInt: Int): Boolean = sdkInt in 26..28 || sdkInt >= 33

    /**
     * ① root 通道可用性：复用 [ble.HandshakeProtocol] 内 [RootDetector] 的能力
     * （应用启动时后台探测 `su -c id` 校验 uid=0 并缓存结果，探测失败/未授权一律 false）。
     */
    fun isRootAvailable(): Boolean = RootDetector.isRoot()

    // ================= ② L1_PRIVATE_API 真路径（B2：私有 API 反射热点） =================

    /**
     * ② 私有 API 热点（① 停用后的降级主路径；v0.3.7 照抄移植修正——**Binder 直呼系统热点
     * （第一手段）→ 反射 setWifiApEnabled 降级 → 失败透传**）：
     * 0) 前置 WRITE_SETTINGS：`Settings.System.canWrite(ctx)` 为 false → 经主线程回调
     *    [HotspotListener.onWriteSettingsPermission] 引导「修改系统设置」授权（复用现有
     *    WriteSettingsDialog / openWriteSettings 语义），返回 `AwaitingWriteSettings` 待授权后重试；
     *    ctx 缺省时经 `ActivityThread.currentApplication()` 反射兜底（见 [resolveContext]）；
     * 1) ★ 第一手段 [tryBinderTether]（v0.3.7 起；MakroDroid WifiHotspotService 逐 smali 翻译——
     *    权威照抄源 md-in/hotspot-symbols.txt（k1/c 段）：对 **ConnectivityManager 类自身**（非
     *    IConnectivityManager 接口）`getDeclaredMethods()` 按方法名找 "startTethering"，
     *    运行时按 parameterTypes 挑可调签名——真机实锤：固定 3/4 参在 sdk31 的 IConnectivityManager
     *    必 NoSuchMethod（sdk31 起该接口换带 TetheringRequestParcel 的新签名），MacroDroid 靠按名
     *    枚举成功开热点）：参数含 OnStartTetheringCallback → 匿名子类实例 + Handler(主线程)；
     *    (int, ResultReceiver, boolean)/(int, Executor, callback) → int=0 / 现 ResultReceiver /
     *    单线程 Executor；含 TetheringRequestParcel → 跳过（构造不可行）；全部候选失败 → 返回
     *    失败原因；invoke 成功 → 2s + mdWifiApEnabled 确认 → systemTetherSuccess（登记复用）；
     *    失败 → mdTetherBinder 兜底（次选）；sdk ≥34 直接失败（「sdk34+ 不裸调 startTethering
     *    （逆向结论）」）；sdk<26 无此路径（smali 分段落 f() 反射）；
     *    成功 → 系统预配热点已开（SSID/密码系统配置、App 不可读）→ 触发
     *    [HotspotListener.onSystemHotspotPasswordRequest] 请用户登记 → [PUBLIC_TETHER_PENDING]
     *    标记待 [completeSystemHotspotPassword] 收敛（startAsync 持有异步闸）；
     *    前置 NEARBY_WIFI_DEVICES（sdk≥33，Manifest 已声明 neverForLocation）：未授权 → 回调
     *    [HotspotListener.onNeedNearbyPermission] 走 requestedPermission 授权链（Engine 已接）、
     *    授权后自动重试，未授权时本等级失败透传（[NEED_NEARBY_PERMISSION]）；
     * 2) ★ 第二手段（降级）：原反射 `WifiManager.setWifiApEnabled(config, true)`
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
        // v0.4.0：先快照热点启动前的旧 Wi-Fi 接口（collectHotspotIp 排除旧 Wi-Fi 网段用）
        snapshotPreHotspotInterfaces()
        // v0.5.14 启用 ②（回归私有 API 反射路径）：DISABLE_PRIVATE_API=false 时本守卫恒 false
        // （编译期常量折叠），正常走 k1/c 式按名枚举 Binder 直呼 → setWifiApEnabled 降级全链；
        // 如需强制 LocalOnly 联调可临时置 true（② 直接失败，状态机既有降级链自动落 ③）
        if (DISABLE_PRIVATE_API) return HotspotResult(success = false, error = "② 已经 DISABLE_PRIVATE_API 联调开关禁用，降级 ③")
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

        // 前置 NEARBY_WIFI_DEVICES（sdk≥33，Binder 直呼同样受其约束；Android 13+ 强制，Manifest
        // 已声明 neverForLocation）：未授权 → 回调 onNeedNearbyPermission 走 requestedPermission
        // 授权链（Engine 已接），授权后自动重试本等级；未授权 → 本等级失败透传、降级 ③
        if (Build.VERSION.SDK_INT >= 33) {
            val nearbyGranted = try {
                ctx.checkSelfPermission(NEARBY_WIFI_DEVICES_PERMISSION) == PackageManager.PERMISSION_GRANTED
            } catch (e: Exception) {
                DiagLogger.log(tag, "NEARBY_WIFI_DEVICES 权限检查异常（按未授权处理，catch 兜底）: $e")
                false
            }
            if (!nearbyGranted) {
                DiagLogger.log(
                    tag,
                    "L1_PRIVATE_API 前置：NEARBY_WIFI_DEVICES 未授权（sdk=${Build.VERSION.SDK_INT}，Manifest 已声明 neverForLocation），" +
                        "回调 onNeedNearbyPermission 走 requestedPermission 授权链，返回 NeedNearbyPermission 失败透传",
                )
                mainHandler.post { listener.onNeedNearbyPermission() }
                return HotspotResult(success = false, error = NEED_NEARBY_PERMISSION)
            }
        }

        val wm = resolveWifiManager(ctx)
        if (wm == null) {
            val err = "L1_PRIVATE_API：WifiManager 不可用（Context 已取得但 getSystemService 失败）"
            DiagLogger.log(tag, err)
            return HotspotResult(success = false, error = err)
        }

        // ★ 第一手段（v0.3.8 k1/c 式按名枚举）：MakroDroid k1/c 手法——对 ConnectivityManager 类
        // 自身 getDeclaredMethods 按名找 startTethering/stopTethering，运行时挑可调签名（真机实锤：
        // 固定 3/4 参在 sdk31 的 IConnectivityManager 必 NoSuchMethod）：
        // sdk 26-33 执行；sdk≥34 快失败；invoke 成功 + 确认 → PUBLIC_TETHER_PENDING 等待系统热点
        // 密码登记；失败 → mdTetherBinder 兜底 → 降级原反射 setWifiApEnabled（8-9 有效）
        val binder = tryBinderTether(ctx, wm)
        if (binder.error == PUBLIC_TETHER_PENDING || binder.success) {
            // PUBLIC_TETHER_PENDING：系统热点已开，等待用户登记本机系统热点 SSID+密码收敛
            // （startAsync 持有异步闸；登记经 completeSystemHotspotPassword → dispatchBinderTetherResult）
            return binder
        }
        val binderFailReason = binder.error ?: "Binder 直呼失败（无原因）"
        DiagLogger.log(
            tag,
            "L1_PRIVATE_API：Binder 直呼未成功（$binderFailReason），降级原反射 setWifiApEnabled（8-9 有效）",
        )

        // ★ 第二手段（降级）：原反射 setWifiApEnabled（现状逻辑原样；失败透传附加 Binder 直呼失败原因）
        // v0.5.9 UI1b-C 热点预设消费点：预设启用且 ssid 非空 → 自设 SSID/密码用预设值（password 空沿用
        // 随机生成）；enabled=false/未设 → 完全现行为（generateSsid/generatePassword）。offer 随
        // HotspotResult.ssid/pwd 自然携带实际值（状态机既有通道），无需另改 offer 构造。
        val ssid = presetSsidOr { generateSsid() }
        val pwd = presetPasswordOr { generatePassword() }
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
            return legacy.copy(
                error = "${legacy.error}；Binder 直呼失败：$binderFailReason",
            )
        }
        return legacy
    }


    /**
     * ★ ② 第一手段（v0.3.8 改 k1/c 式按名枚举；v0.3.7 曾为 IConnectivityManager 固定签名、v0.3.6 曾为
     * ITetheringConnector+parcel、v0.3.5 曾为兜底）：
     * k1/c 式按名枚举系统热点——MakroDroid `k1/c`（ConnectivityManager 反射助手）**逐 smali 翻译**
     * （权威照抄源 md-in/hotspot-symbols.txt k1/c 段：`c.a` 遍历 `getDeclaredMethods()` 找
     * "startTethering"（dex 1888471-1888568），找不到记
     * "ConnectivityManager.startTetheringMethod() is not found"（只记日志不崩）；`c.c` 用
     * `getSystemService(ConnectivityManager.class)` + `getDeclaredMethod("stopTethering",[int])`
     * （dex 1888630-1888650））：
     * - **真机实锤**：固定 3/4 参在 sdk31 的 IConnectivityManager 必 NoSuchMethod（sdk31 起
     *   IConnectivityManager.startTethering 换带 TetheringRequestParcel 的新签名）；MacroDroid 靠
     *   **按名枚举 + ConnectivityManager 类自身 hidden 方法**成功开热点（sdk31 的 ConnectivityManager
     *   类仍有 startTethering hidden 变体，按 parameterTypes 挑可调签名）；
     * - 版本门控：sdk 26–33 执行；**sdk ≥34 直接返回失败**（MakroDroid sdk<34 才 startService
     *   本服务，逆向结论）；sdk <26 无此路径（smali 分段落 f() 反射 setWifiApEnabled）→ 返回明确
     *   reason 交上层降级反射；
     * - 拿服务（k1/c 原样）：`context.getSystemService(ConnectivityManager::class.java)` →
     *   `cm.javaClass`（ConnectivityManager 类自身）按名枚举（非 IConnectivityManager 接口）；
     * - 候选矩阵（[mdTetherModern] 内，按 m.parameterTypes 匹配构造实参并逐一 invoke）：
     *   a. 参数含 `OnStartTetheringCallback`（public 嵌套类）→ 匿名子类实例（abstract class →
     *      Unsafe.allocateInstance 免构造；interface → Proxy）+ Handler(Looper.getMainLooper())
     *      （若有 Handler 参）；
     *   b. (int, ResultReceiver, boolean) / (int, Executor, callback) → int=0 / 现 ResultReceiver /
     *      单线程 Executor；
     *   c. 含 TetheringRequestParcel → 记一次失败原因跳过（sdk31 构造不可行，保持兼容分支）；
     *   每次 invoke 包 try/Catch(SecurityException/Exception) → 全部候选失败 → 返回失败原因
     *   （列出该方法签名与异常）；
     * - 执行顺序（smali e() 语义）：[mdTetherModern]（e()）invoke 成功 → sleep 2s（smali 001a）→
     *   `b()` 状态检查（[mdWifiApEnabled]：getWifiApState 归一化 ∈{2,3}）确认已开 → 成功 →
     *   [systemTetherSuccess]（登记复用，跳过直呼）；未确认/失败 → [mdTetherBinder]（d()，次选：
     *   保留现有 3/4 参与 2 参 stopTethering 段，可简化）兜底；关热点对称：按名枚举 "stopTethering"
     *   invoke(int=0)；
     * - 成功判定（任务要求回调/轮询确认）：mdTetherModern 内 2s + mdWifiApEnabled 确认；未走 k1/c
     *   确认路径时 confirmBinderTether 兜底——回调错误码 0 即成功（AOSP TETHER_ERROR_NO_ERROR）；
     *   回调未达时轮询 `getWifiApState`（smali Lu3/a.a 归一化：>10 减 10）∈{2=ENABLING,3=ENABLED}
     *   即成功（smali b() 判定）；状态不可读（hidden 拦截）以「回调码 0」为准；8s 超时兜底；
     * - 成功 → 系统预配热点已开（SSID/密码为系统配置、App 不可读）→ 主线程触发
     *   [HotspotListener.onSystemHotspotPasswordRequest]，返回 [PUBLIC_TETHER_PENDING] 标记，
     *   [completeSystemHotspotPassword] 登记后经 [dispatchBinderTetherResult] 收敛成功结果（登记机制不动）；
     * - 失败（快失败/枚举 find null/全部候选失败/异常/回调错误码非 0/8s 超时）→ 失败透传（不吞），
     *   上层降级原反射 setWifiApEnabled。
     *
     * 线程：随 [startAsync] 后台线程执行（2s/8s 等待不占主线程）；回调在 binder 线程写
     * AtomicInteger（可见性安全）；UI 回调统一主线程 post。
     */
    private fun tryBinderTether(ctx: Context, wm: WifiManager?): HotspotResult {
        val sdk = Build.VERSION.SDK_INT
        // 版本门控：sdk ≥34 不裸调 startTethering（MakroDroid sdk<34 才 startService WifiHotspotService）
        if (sdk >= 34) {
            val err = "sdk34+ 不裸调 startTethering（逆向结论），降级 ③"
            DiagLogger.log(tag, "L1_PRIVATE_API Binder 直呼快失败：sdk=$sdk → $err")
            return HotspotResult(success = false, error = err)
        }
        // sdk <26：smali 分段 sdk<26 落 f()（反射 setWifiApEnabled），无 k1/c 枚举路径——
        // 返回明确 reason，上层降级反射
        if (sdk < 26) {
            val err = "sdk=$sdk 无 k1/c 枚举路径（smali 分段走反射 setWifiApEnabled），降级反射"
            DiagLogger.log(tag, "L1_PRIVATE_API Binder 直呼快失败：$err")
            return HotspotResult(success = false, error = err)
        }
        if (wm == null) {
            val err = "L1_PRIVATE_API：WifiManager 不可用（Binder 直呼状态确认需要）"
            DiagLogger.log(tag, err)
            return HotspotResult(success = false, error = err)
        }

        // 回调错误码（AtomicInteger：binder 回调线程写入、本线程轮询读取；CODE_NOT_RECEIVED=回调未达）
        val binderCode = AtomicInteger(CODE_NOT_RECEIVED)

        return try {
            // ==== 照抄 smali：onHandleIntent（WifiAPState=0 turn on / ForceLegacy=false）→ c(true,false) → e(true) ====
            // v0.3.8：mdTetherDispatch 内 e()（mdTetherModern）已改 k1/c 按名枚举，返回决定性结果
            // （成功 PUBLIC_TETHER_PENDING 待登记 / 失败原因）时直接收敛；null=未决 → confirmBinderTether 轮询兜底
            val decisive = mdTetherDispatch(ctx, wm, turnOn = true, forceLegacy = false, turnWifiOn = true, binderCode)
            if (decisive != null) {
                DiagLogger.log(tag, "L1_PRIVATE_API k1/c 决定性结果：success=${decisive.success} error=${decisive.error}")
                decisive
            } else {
                // ==== 成功判定（smali e() 的 2s+b() 检查之后；任务要求回调/轮询确认）====
                confirmBinderTether(ctx, wm, binderCode)
            }
        } catch (e: Exception) {
            // 不吞异常：记录 + 如实透传（含异常类；hidden API 拦截 / 服务端拒绝均在此落）
            DiagLogger.log(tag, "L1_PRIVATE_API Binder 直呼异常（不吞）: $e")
            HotspotResult(
                success = false,
                error = "Binder 直呼 startTethering 异常: ${e.javaClass.simpleName}: ${e.message}",
            )
        }
    }

    /**
     * 照抄 smali `WifiHotspotService.c(ZZ)V`（onHandleIntent 的 WifiAPState 分支执行体）：
     * 1) turnOn 且缓存字段 a==-1 时缓存 `WifiManager.getWifiState()`（smali 字段 a，写后未读——死缓存，
     *    port 为局部变量，语义等价）；
     * 2) sdk<29 且 turnOn 且当前连接着 wifi → `setWifiEnabled(false)`（SecurityException → 提示
     *    "Could not change wifi state"；其他异常 → log "Could not change wifi state: "），随后轮询
     *    10×500ms 等 `getWifiState()==1(DISABLED)`；
     * 3) sdk 分段（smali 005d-0073 原样）：sdk≥26 且 !forceLegacy → [mdTetherModern]（e()，v0.3.8
     *    k1/c 式按名枚举——返回决定性结果（成功 PUBLIC_TETHER_PENDING / 失败原因）或 null 未决）；
     *    sdk==25 且 !forceLegacy → [mdTetherBinder]（d()）；否则 → [mdTetherLegacy]（f() 反射
     *    setWifiApEnabled）；分支后缓存复位 -1（smali 0076：a=-1）；
     * 4) !turnOn（关热点）收尾（smali 0078-00ad）：[mdApState] 读 AP state（**仅读一次**，smali 原样
     *    陈旧值），循环 10×500ms 等待（state∈{0,3,4} 时继续）→ sleep 1s → TurnOnWifi 为 true 时重开
     *    wifi（sdk<29 `setWifiEnabled(true)`；sdk≥29 [mdEnableWifi]（Lu3/a.b 等价））。
     *
     * @return 决定性结果：非 null = k1/c 按名枚举已定（[PUBLIC_TETHER_PENDING] 成功待登记 / 失败原因），
     *   调用方直接收敛；null = 未决（已落 mdTetherBinder/legacy 兜底或关热点路径），调用方继续
     *   confirmBinderTether 轮询收敛。
     *
     * 线程：随 [startAsync] 后台线程执行（所有 sleep 不占主线程）。
     */
    private fun mdTetherDispatch(
        ctx: Context,
        wm: WifiManager,
        turnOn: Boolean,
        forceLegacy: Boolean,
        turnWifiOn: Boolean,
        binderCode: AtomicInteger,
    ): HotspotResult? {
        val sdk = Build.VERSION.SDK_INT
        // smali 0000-000d：缓存 wifi state（仅 turnOn 且未缓存；写后未读，smali 原样死缓存）
        var cachedWifiState = -1
        if (turnOn && cachedWifiState == -1) cachedWifiState = wm.wifiState
        // smali 001b-005c：sdk<29 且 turnOn 且已连接 wifi → 先关 wifi（释放射频），等 DISABLED
        if (sdk < 29 && turnOn) {
            val info = try {
                wm.connectionInfo // smali 001f：getConnectionInfo()
            } catch (e: Exception) {
                null
            }
            if (info != null) {
                try {
                    wm.setWifiEnabled(false) // smali 0029
                } catch (e: SecurityException) {
                    mdToast(ctx, SMALI_TOAST_TITLE, SMALI_TOAST_DRIVER_MSG) // smali 0047：SecurityException 提示
                } catch (e: Exception) {
                    DiagLogger.log(tag, "Could not change wifi state: $e") // smali 0033 串
                }
                var n = 10
                while (n > 0 && wm.wifiState != SMALI_WIFI_STATE_DISABLED) {
                    try {
                        Thread.sleep(500) // smali 0057
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                    n--
                }
            }
        }
        // smali 005d-0073：sdk 分段分支（e() / d() / f()）；v0.3.8：e() 返回决定性结果（非 null 直接收敛）
        var decisive: HotspotResult? = null
        when {
            sdk >= 26 && !forceLegacy -> decisive = mdTetherModern(ctx, wm, turnOn, binderCode) // e()（k1/c 按名枚举）
            sdk >= 25 && !forceLegacy -> mdTetherBinder(ctx, turnOn, binderCode) // d()（sdk==25）
            else -> mdTetherLegacy(ctx, wm, turnOn) // f()（sdk<25 或 ForceLegacy）
        }
        cachedWifiState = -1 // smali 0076：a = -1（分支后复位）
        // smali 0078-00ad：关热点收尾（仅 !turnOn）
        if (!turnOn) {
            // smali 007c：Lu3/a.a 读一次（陈旧值，原样）——异常（RuntimeException）不捕获，冒泡由外层收敛
            val st = mdApState(wm)
            var n = 10
            while (n > 0 && (st == 0 || st == SMALI_AP_ENABLED || st == SMALI_AP_FAILED)) {
                try {
                    Thread.sleep(500) // smali 0080
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
                n--
            }
            try {
                Thread.sleep(1_000L) // smali 0091
            } catch (e: Exception) {
                mdToast(ctx, SMALI_TOAST_TITLE, SMALI_TOAST_DRIVER_MSG) // smali 00a8：sleep 异常提示
                return decisive
            }
            if (turnWifiOn) {
                if (sdk < 29) {
                    wm.setWifiEnabled(true) // smali 009e：sdk<29 直接开 wifi
                } else {
                    mdEnableWifi(ctx) // smali 00a4：Lu3/a.b（sdk>=29 root/Helper File 等价）
                }
            }
        }
        // v0.3.8：返回 k1/c 按名枚举的决定性结果（null=未决，调用方 confirmBinderTether 兜底）
        return decisive
    }

    /**
     * ★ ② 第一手段（v0.3.8 改 k1/c 式按名枚举；取代 v0.3.7 固定 IConnectivityManager 签名串）：
     * MakroDroid `k1/c` 手法——对 **ConnectivityManager 类自身**（非 IConnectivityManager 接口）
     * `getDeclaredMethods()` 按方法名找 "startTethering"/"stopTethering"（真机实锤：固定 3/4 参在
     * sdk31 的 IConnectivityManager 必 NoSuchMethod——sdk31 起 IConnectivityManager.startTethering
     * 换带 TetheringRequestParcel 的新签名；MacroDroid 靠按名枚举 + ConnectivityManager 类自身
     * hidden 方法成功开热点）。
     *
     * 候选矩阵（找到 m 后按 m.parameterTypes 匹配构造实参并逐一 invoke，全部 try/Catch）：
     * a. 参数含 `OnStartTetheringCallback`（public 嵌套类）→ 匿名子类实例
     *    （AOSP 为 abstract class：Proxy 不可用 → Unsafe.allocateInstance 免构造空回调；interface
     *    时 Proxy）+ `Handler(Looper.getMainLooper())`（若有 Handler 参）；
     * b. 参数是 (int, ResultReceiver, boolean) 或 (int, Executor, callback) → 对应实参
     *    （int=0 / 现 ResultReceiver / 单线程 Executor）；
     * c. 参数含 TetheringRequestParcel → 记一次失败原因跳过（sdk31 构造不可行，保持兼容分支）；
     * 全部候选失败 → 返回失败原因（列出该方法签名与异常）；无该方法（find null）→ 失败
     * 「本 ROM 无 startTethering hidden 方法」→ 降级。
     *
     * invoke 成功后 2s 等待（smali 001a）→ `mdWifiApEnabled` 轮询确认（smali b() 判定）：
     * 成功 → [systemTetherSuccess]（登记复用）；失败 → [mdTetherBinder] 兜底（次选：保留现有
     * 3/4 参与 2 参 stopTethering 段，可简化）。关热点对称：按名枚举 "stopTethering" invoke(int=0)。
     *
     * @return 决定性结果：非 null = 已定（[PUBLIC_TETHER_PENDING] 成功待登记 / 失败原因）；
     *   null = 未决（已落 [mdTetherBinder] 兜底），调用方继续 confirmBinderTether 轮询收敛。
     */
    private fun mdTetherModern(
        ctx: Context,
        wm: WifiManager,
        turnOn: Boolean,
        binderCode: AtomicInteger,
    ): HotspotResult? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        if (cm == null) {
            DiagLogger.log(tag, "mdTetherModern：getSystemService(ConnectivityManager) 返回 null，落 mdTetherBinder 兜底")
            mdTetherBinder(ctx, turnOn, binderCode)
            return null
        }
        val name = if (turnOn) "startTethering" else "stopTethering"
        // k1/c 手法：按名枚举 getDeclaredMethods（对 ConnectivityManager 类自身，非 IConnectivityManager）
        val m = findMethodByName(cm.javaClass, name)
        if (m == null) {
            if (turnOn) {
                // k1/c 串（素材：找不到记 "ConnectivityManager.startTetheringMethod() is not found"，只记日志不崩）
                DiagLogger.log(tag, K1C_START_TETHERING_NOT_FOUND)
                val err = "本 ROM 无 startTethering hidden 方法（k1/c 按名枚举 find null），降级"
                DiagLogger.log(tag, "L1_PRIVATE_API：$err")
                return HotspotResult(success = false, error = err)
            }
            // 关热点对称：k1/c.c 串（素材 dex 1888649："stopTetheringMethod is null"）→ 落 mdTetherBinder 2 参段兜底
            DiagLogger.log(tag, "stopTetheringMethod is null")
            DiagLogger.log(tag, "L1_PRIVATE_API：本 ROM 无 stopTethering hidden 方法，落 mdTetherBinder 兜底")
            mdTetherBinder(ctx, turnOn, binderCode)
            return null
        }
        // 日志：枚举到的方法签名（DiagLogger 可读；密码不回显）
        val candidates = cm.javaClass.declaredMethods.filter { it.name == name }
        DiagLogger.log(
            tag,
            "k1/c 按名枚举 $name：命中 ${candidates.size} 个候选签名：" +
                candidates.joinToString("; ") { methodSignature(it) },
        )
        if (!turnOn) {
            // 关热点对称：按名枚举 stopTethering invoke(int=0)（k1/c.c 语义）
            val ok = invokeStopTetheringCandidates(cm, candidates)
            if (ok) {
                DiagLogger.log(tag, "k1/c 按名枚举 stopTethering invoke 成功（关热点路径，无需确认）")
            } else {
                DiagLogger.log(tag, "k1/c 按名枚举 stopTethering 全部候选失败，落 mdTetherBinder 兜底")
                mdTetherBinder(ctx, turnOn, binderCode)
            }
            return null
        }
        // 候选矩阵：按 parameterTypes 构造实参逐一 invoke（全部 try/Catch）
        val failures = mutableListOf<String>()
        var parcelSkipped = false
        for (c in candidates) {
            val args = buildTetherArgs(ctx, c, binderCode)
            if (args == null) {
                if (c.parameterTypes.any { it.name == "android.net.TetheringRequestParcel" }) {
                    if (!parcelSkipped) {
                        parcelSkipped = true
                        failures.add("${methodSignature(c)}：含 TetheringRequestParcel（sdk31 构造不可行，跳过）")
                        DiagLogger.log(tag, "k1/c 候选跳过：${methodSignature(c)}（含 TetheringRequestParcel，sdk31 构造不可行，保持兼容分支）")
                    }
                } else {
                    failures.add("${methodSignature(c)}：参数不可构造（无匹配实参矩阵）")
                }
                continue
            }
            try {
                c.isAccessible = true
                c.invoke(cm, *args)
                DiagLogger.log(tag, "k1/c invoke 成功：${methodSignature(c)}（实参 ${args.size} 个）")
                // invoke 成功后 2s 等待（smali 001a）→ mdWifiApEnabled 轮询确认（smali b() 判定）
                try {
                    Thread.sleep(2_000L)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                if (mdWifiApEnabled(wm)) {
                    DiagLogger.log(tag, "k1/c 枚举启动成功：2s 后 mdWifiApEnabled 确认已开，走 systemTetherSuccess（登记复用）")
                    return systemTetherSuccess()
                }
                // 失败 → mdTetherBinder 兜底（次选），未决返回 null（confirmBinderTether 继续收敛）
                DiagLogger.log(tag, "k1/c invoke 成功但 2s 后 mdWifiApEnabled 未确认，落 mdTetherBinder 兜底（次选）")
                mdTetherBinder(ctx, turnOn, binderCode)
                return null
            } catch (e: SecurityException) {
                failures.add("${methodSignature(c)}：SecurityException ${e.message}")
                DiagLogger.log(tag, "k1/c 候选 invoke SecurityException（继续下一候选）：${methodSignature(c)}：${e.message}")
            } catch (e: Exception) {
                failures.add("${methodSignature(c)}：${e.javaClass.simpleName} ${e.message}")
                DiagLogger.log(tag, "k1/c 候选 invoke 异常（继续下一候选）：${methodSignature(c)}：$e")
            }
        }
        // 全部候选失败 → 返回失败原因（列出该方法签名与异常）→ 上层降级反射 setWifiApEnabled
        val err = "k1/c 按名枚举 $name 全部候选失败（${failures.size} 个）：" + failures.joinToString(" | ")
        DiagLogger.log(tag, "L1_PRIVATE_API：$err")
        return HotspotResult(success = false, error = err)
    }

    /**
     * k1/c 手法（素材 md-in/hotspot-symbols.txt k1/c 段）：按方法名枚举 `cls.declaredMethods`
     * firstOrNull 命中（对 ConnectivityManager 类自身；非 IConnectivityManager 接口）。
     */
    private fun findMethodByName(cls: Class<*>, name: String): Method? =
        cls.declaredMethods.firstOrNull { it.name == name }

    /** 反射方法签名可读串（k1/c 候选日志用）：`startTethering(int, android.net.ConnectivityManager$OnStartTetheringCallback, android.os.Handler)`。 */
    private fun methodSignature(m: Method): String =
        "${m.name}(${m.parameterTypes.joinToString(", ") { it.name }})"

    /**
     * 按候选方法 [m] 的 parameterTypes 构造 invoke 实参（k1/c 候选矩阵）：
     * a. 参数含 `OnStartTetheringCallback`（public 嵌套类）→ 匿名子类实例
     *    （[instantiateTetherCallback]）+ `Handler(Looper.getMainLooper())`（若有 Handler 参）；
     * b. (int, ResultReceiver, boolean) / (int, Executor, callback) → int=0 / 现 ResultReceiver /
     *    单线程 Executor；
     * c. 含 TetheringRequestParcel → null（sdk31 构造不可行，保持兼容分支，调用方记失败原因跳过）。
     * 其余不可构造参数 → null（该候选跳过）。
     */
    private fun buildTetherArgs(ctx: Context, m: Method, binderCode: AtomicInteger): Array<Any?>? {
        val pts = m.parameterTypes
        if (pts.isEmpty()) return null
        if (pts.any { it.name == "android.net.TetheringRequestParcel" }) return null // c.
        val args = arrayOfNulls<Any?>(pts.size)
        for (i in pts.indices) {
            val p = pts[i]
            args[i] = when {
                p == java.lang.Integer.TYPE -> Integer.valueOf(TETHERING_TYPE_WIFI) // int=0
                p == java.lang.Boolean.TYPE -> java.lang.Boolean.FALSE // boolean=false
                p == ResultReceiver::class.java -> newTetherReceiver(binderCode) // 现 ResultReceiver
                p == Handler::class.java -> Handler(Looper.getMainLooper()) // a. Handler 参
                p == Executor::class.java -> Executors.newSingleThreadExecutor { r ->
                    Thread(r, "Bluelink-k1c-tether-exec").apply { isDaemon = true }
                } // b. 单线程 Executor
                p.simpleName == "OnStartTetheringCallback" -> instantiateTetherCallback(p) ?: return null // a.
                else -> return null // 其他不可构造参数
            }
        }
        return args
    }

    /**
     * 实例化 OnStartTetheringCallback（public 嵌套类，@hide abstract class）匿名子类实例：
     * - interface → Proxy 空代理（MacroDroid 生成的 onStartTethering 为空实现）；
     * - abstract class（AOSP 实际形态）→ `sun.misc.Unsafe.allocateInstance` 免构造实例
     *   （等价匿名子类空回调；Proxy 仅 interface 可用）。
     * 失败 → null（该候选跳过）。
     */
    private fun instantiateTetherCallback(cbClass: Class<*>): Any? = try {
        if (cbClass.isInterface) {
            Proxy.newProxyInstance(
                cbClass.classLoader ?: ClassLoader.getSystemClassLoader(),
                arrayOf(cbClass),
                InvocationHandler { _, _, _ -> null },
            )
        } else {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val theUnsafe = unsafeClass.getDeclaredField("theUnsafe")
            theUnsafe.isAccessible = true
            val unsafe = theUnsafe.get(null)
            val alloc = unsafeClass.getMethod("allocateInstance", Class::class.java)
            alloc.invoke(unsafe, cbClass)
        }
    } catch (e: Exception) {
        DiagLogger.log(tag, "k1/c 回调实例化失败（该候选跳过）: $e")
        null
    }

    /** 现 ResultReceiver（候选矩阵 b. 变体；smali 无 Handler——new ResultReceiver(null)，覆写 onReceiveResult 记回调码）。 */
    private fun newTetherReceiver(binderCode: AtomicInteger): ResultReceiver =
        object : ResultReceiver(null) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                binderCode.set(resultCode)
                DiagLogger.log(tag, "startTethering 回调：resultCode=$resultCode")
            }
        }

    /**
     * 关热点对称：stopTethering 候选按名枚举 invoke(int=0)（k1/c.c 语义；smali 0013-0028 同款
     * getDeclaredMethod("stopTethering", [int])）。任一候选成功即 true；全部失败 false。
     */
    private fun invokeStopTetheringCandidates(cm: ConnectivityManager, candidates: List<Method>): Boolean {
        for (c in candidates) {
            val pts = c.parameterTypes
            val args = arrayOfNulls<Any?>(pts.size)
            var ok = true
            for (i in pts.indices) {
                when {
                    pts[i] == java.lang.Integer.TYPE -> args[i] = Integer.valueOf(TETHERING_TYPE_WIFI)
                    else -> {
                        ok = false
                        break
                    }
                }
            }
            if (!ok) continue
            try {
                c.isAccessible = true
                c.invoke(cm, *args)
                DiagLogger.log(tag, "k1/c stopTethering invoke 成功：${methodSignature(c)}（int=0）")
                return true
            } catch (e: Exception) {
                DiagLogger.log(tag, "k1/c stopTethering 候选 invoke 异常（继续下一候选）：${methodSignature(c)}：$e")
            }
        }
        return false
    }

    /**
     * 照抄 smali `WifiHotspotService.d(Z)I`（sdk==25 或 e() 未决时的**次选兜底**）：
     * `context.getSystemService("connectivity")` → ConnectivityManager；
     * turnOn → [mdTetherStart]（a()：IConnectivityManager.startTethering 3/4 参反射链，次选保留）；
     * 否则 → 反射 `ConnectivityManager.getDeclaredMethod("stopTethering", int)` invoke 0（stopTethering
     * 用法，smali 0013-0028）；异常 → log "Failed to set hotspot on API25+: "（smali 串）；返回 0（smali 固定）。
     * v0.3.8：主路径 [mdTetherModern] 已改 k1/c 式按名枚举（对 ConnectivityManager 类自身），本函数保留
     * 现有 3/4 参与 2 参段作次选（真机实锤 sdk31 的 IConnectivityManager 固定签名必 NoSuchMethod，
     * 主路径靠按名枚举覆盖；此处仅兜底）。
     */
    private fun mdTetherBinder(ctx: Context, turnOn: Boolean, binderCode: AtomicInteger) {
        val cm = ctx.getSystemService("connectivity") as? ConnectivityManager
        if (cm == null) {
            DiagLogger.log(tag, "mdTetherBinder：getSystemService(\"connectivity\") 返回 null")
            return
        }
        try {
            if (turnOn) {
                mdTetherStart(ctx, cm, binderCode) // smali 000d：a(cm)
            } else {
                // smali 0013-0028：ConnectivityManager.getDeclaredMethod("stopTethering", int).invoke(cm, 0)
                val m = ConnectivityManager::class.java.getDeclaredMethod("stopTethering", java.lang.Integer.TYPE)
                m.isAccessible = true
                m.invoke(cm, Integer.valueOf(TETHERING_TYPE_WIFI))
            }
        } catch (e: Exception) {
            DiagLogger.log(tag, "Failed to set hotspot on API25+: $e") // smali 0031 串
        }
    }

    /**
     * 照抄 smali `WifiHotspotService.a(Object)`（IConnectivityManager.startTethering 反射链，**次选兜底**——
     * v0.3.8 主路径已改 k1/c 式按名枚举，见 [mdTetherModern]；本函数保留现有 3/4 参段作次选，可简化）：
     * `Class.forName("android.net.IConnectivityManager")` 取接口类，参数构造
     * `(int=0, ResultReceiver, boolean=false)`；**invoke 在 ConnectivityManager 实例上**
     * （smali 经 d() 传入 getSystemService("connectivity") 的对象；非 ServiceManager/mService）；
     * ResultReceiver 为 `new ResultReceiver(null)`（smali 无 Handler），我们覆写 onReceiveResult
     * 记录回调码（任务要求回调确认；AOSP TETHER_ERROR_NO_ERROR=0）；
     * 异常回退链（smali 三个 try 原样）：3 参失败 → 再 3 参（dexdump 原样 dup，等价一次）→
     * 4 参 `(int, ResultReceiver, boolean, String pkg)`（smali 第三 try 传 "com.arlosoft.macrodroid"
     * 字面量，我们用 ctx.packageName）→ 全部失败 log "Cannot start tethering: "（smali 串）。
     */
    private fun mdTetherStart(ctx: Context, cm: ConnectivityManager, binderCode: AtomicInteger) {
        val receiver = newTetherReceiver(binderCode) // 复用 k1/c 候选矩阵的 ResultReceiver（记回调码）
        val icm = try {
            Class.forName("android.net.IConnectivityManager")
        } catch (e: ClassNotFoundException) {
            DiagLogger.log(tag, "Cannot start tethering: ${e.javaClass.simpleName}: ${e.message}")
            return
        }
        // smali 参数构造（001c 起）：int=0 / ResultReceiver / boolean=false；4 参追加 String pkg
        val sig3 = arrayOf(java.lang.Integer.TYPE, ResultReceiver::class.java, java.lang.Boolean.TYPE)
        val sig4 = arrayOf(
            java.lang.Integer.TYPE, ResultReceiver::class.java, java.lang.Boolean.TYPE, String::class.java,
        )
        val zero = Integer.valueOf(TETHERING_TYPE_WIFI)
        val flag = java.lang.Boolean.FALSE
        val pkg = ctx.packageName
        try {
            // smali 001c-0032：3 参
            val m = icm.getDeclaredMethod("startTethering", *sig3)
            m.invoke(cm, zero, receiver, flag)
            DiagLogger.log(tag, "Binder 直呼：已调用 IConnectivityManager.startTethering(0, receiver, false)（3 参）")
            return
        } catch (e1: Exception) {
            // smali 0036-004c：同 3 参重试（dexdump 原样 dup，等价一次）→ 落 4 参兜底
            DiagLogger.log(
                tag,
                "IConnectivityManager.startTethering 3 参失败（落 4 参兜底）: ${e1.javaClass.simpleName}: ${e1.message}",
            )
        }
        try {
            // smali 0051-006f：4 参 (int, ResultReceiver, boolean, String pkg)
            val m = icm.getDeclaredMethod("startTethering", *sig4)
            m.invoke(cm, zero, receiver, flag, pkg)
            DiagLogger.log(tag, "Binder 直呼：已调用 IConnectivityManager.startTethering(0, receiver, false, pkg=$pkg)（4 参）")
        } catch (e: Exception) {
            DiagLogger.log(tag, "Cannot start tethering: $e") // smali 0079 串
        }
    }

    /**
     * 照抄 smali `WifiHotspotService.b()Z`：反射 `WifiManager.getWifiApState()`（getDeclaredMethod +
     * setAccessible(true)），归一化（>10 减 10），返回 state∈{2=ENABLING,3=ENABLED}；
     * 异常 → log "Error getting wifi AP State: " + message 并抛 RuntimeException（smali 0051 包装）。
     */
    private fun mdWifiApEnabled(wm: WifiManager): Boolean {
        try {
            val m = wm.javaClass.getDeclaredMethod("getWifiApState")
            m.isAccessible = true
            var st = (m.invoke(wm) as Int)
            if (st > 10) st -= 10 // smali 0029：>10 减 10（AP 状态与 wifi 状态同编号）
            return st == SMALI_AP_ENABLING || st == SMALI_AP_ENABLED
        } catch (e: Exception) {
            DiagLogger.log(tag, "Error getting wifi AP State: ${e.message}")
            throw RuntimeException("Error getting wifi AP State: ${e.message}", e)
        }
    }

    /**
     * 照抄 smali `u3/a.a(WifiManager)I`（WifiHotspotService 与 SetHotspotAction 共用的 AP 状态读取）：
     * 反射 `getWifiApState()`（getMethod），归一化（>10 减 10）返回（0-4 编号与 WIFI_STATE_* 同）；
     * 异常 → 抛 RuntimeException("WifiHotspotService: getWifiAPState failed: ...")（smali 原样）。
     */
    private fun mdApState(wm: WifiManager): Int {
        try {
            val m = wm.javaClass.getMethod("getWifiApState")
            var st = (m.invoke(wm) as Int)
            if (st > 10) st -= 10
            return st
        } catch (e: Exception) {
            throw RuntimeException("WifiHotspotService: getWifiAPState failed: ${e.message}", e)
        }
    }

    /**
     * 照抄 smali `WifiHotspotService.f(Z)I`（sdk<25 或 ForceLegacy 的反射降级路径）：
     * 1) `setWifiEnabled(false)`（SecurityException → 提示 "Could not change wifi state" /
     *    "...custom ROM..."；其他异常 → sneakyThrow 上抛（smali 0009→0051））；
     * 2) 反射 `WifiManager.setWifiApEnabled(WifiConfiguration, boolean)`（getMethod 公开查找），
     *    **config=null**（smali 0031 原样）；随后反射 `getWifiApState()` 取 state；
     * 3) turnOn 时轮询（smali 0074-008f）：state 读一次（smali 原样陈旧值），state∈{1,2,4} 且
     *    剩余次数>0 → sleep 500ms；
     * 4) 异常（setWifiApEnabled/getWifiApState）→ sneakyThrow 上抛（smali 0051 语义：异常继续冒泡，
     *    由外层 catch 收敛为失败 reason）。
     */
    private fun mdTetherLegacy(ctx: Context, wm: WifiManager, turnOn: Boolean) {
        try {
            wm.setWifiEnabled(false) // smali 0005
        } catch (e: SecurityException) {
            mdToast(ctx, SMALI_TOAST_TITLE, SMALI_TOAST_DRIVER_MSG) // smali 000b：SecurityException 提示
        } catch (e: Exception) {
            throw e // smali 0009 → 0051 sneakyThrow（异常上抛）
        }
        try {
            val m = wm.javaClass.getMethod(
                "setWifiApEnabled",
                WifiConfiguration::class.java, java.lang.Boolean.TYPE,
            )
            m.isAccessible = true
            m.invoke(wm, null, java.lang.Boolean.valueOf(turnOn)) // smali 0031：config=null
            val m2 = wm.javaClass.getMethod("getWifiApState")
            m2.isAccessible = true
            val st = (m2.invoke(wm) as Int)
            // smali 0074-008f：turnOn 轮询（getWifiApState 仅读一次，smali 原样陈旧值）
            if (turnOn) {
                var n = 10
                while (n > 0 && (st == SMALI_WIFI_STATE_DISABLED || st == SMALI_AP_ENABLING || st == SMALI_AP_FAILED)) {
                    try {
                        Thread.sleep(500) // smali 0089
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                    n--
                }
            }
        } catch (e: Exception) {
            // smali 0051：Lw1/a.w(e) sneakyThrow —— 异常继续上抛（其后 log/toast/-1 为死代码）
            throw e
        }
    }

    /**
     * ② 成功判定（任务要求回调/轮询确认；smali 服务侧 e() 的 2s+b() 检查后叠加）：
     * 回调错误码 0 即成功（AOSP TETHER_ERROR_NO_ERROR）；回调未达时轮询 [mdApState]
     * （smali Lu3/a.a 归一化）∈{2=ENABLING,3=ENABLED} 即成功（smali b() 判定）；状态不可读
     * （mdApState 抛异常，hidden 拦截）以「回调码 0」为准；8s 超时兜底 → 明确 reason。
     */
    private fun confirmBinderTether(ctx: Context, wm: WifiManager, binderCode: AtomicInteger): HotspotResult {
        val deadline = System.currentTimeMillis() + BINDER_CONFIRM_TIMEOUT_MS
        var stateApiWarned = false
        while (System.currentTimeMillis() < deadline) {
            val code = binderCode.get()
            if (code == TETHER_ERROR_NO_ERROR) {
                DiagLogger.log(tag, "Binder 直呼成功：回调错误码 0（系统预配热点已开启，SSID/密码为系统配置）")
                return systemTetherSuccess()
            }
            if (code != CODE_NOT_RECEIVED) {
                val err = "Binder 直呼回调错误码=$code（非 0，系统拒绝/失败）"
                DiagLogger.log(tag, "L1_PRIVATE_API：$err")
                return HotspotResult(success = false, error = err)
            }
            val st = try {
                mdApState(wm)
            } catch (e: Exception) {
                -1 // 状态 API 不可读（hidden 拦截）→ 以「回调码 0」为准
            }
            if (st == SMALI_AP_ENABLING || st == SMALI_AP_ENABLED) { // smali b() 判定（归一化）
                DiagLogger.log(tag, "Binder 直呼成功（回调未达，状态轮询确认热点已开启 state=$st）")
                return systemTetherSuccess()
            }
            if (st == -1 && !stateApiWarned) {
                stateApiWarned = true
                DiagLogger.log(tag, "Binder 直呼状态轮询不可用（hidden API 拦截），以「回调码 0 即成功」为准")
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
        return HotspotResult(success = false, error = err)
    }

    /** smali `w1.u(Context, title, msg, false)` 的等价提示（"Could not change wifi state" 等 Toast 文案）。 */
    private fun mdToast(ctx: Context, title: String, msg: String) {
        try {
            Toast.makeText(ctx.applicationContext, "$title\n$msg", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            DiagLogger.log(tag, "mdToast 展示失败（忽略，不影响流程）: $e")
        }
    }

    /**
     * 照抄 smali `u3/a.b(Context, String)` 的等价（sdk≥29 关热点后重开 wifi）：
     * MacroDroid 原实现：root → `svc wifi enable`；Helper File（外部 companion App，Bluelink 无）→
     * 请求；均无 → log 报错。Bluelink 移植：无 Helper File 基础设施、root 通道已停用（B1），
     * 直呼 `setWifiEnabled(true)`（sdk<29 有效；29+ 系统可能拒绝——如实记录，如需 root 恢复在此接线）。
     */
    private fun mdEnableWifi(ctx: Context) {
        val wm = ctx.getSystemService(WifiManager::class.java) ?: return
        try {
            wm.setWifiEnabled(true)
        } catch (e: Exception) {
            DiagLogger.log(
                tag,
                "mdEnableWifi（Lu3/a.b 等价）setWifiEnabled(true) 失败（sdk>=29 常见，需 root/Helper File）: $e",
            )
        }
    }

    /**
     * ② 系统预配热点成功收敛（Binder 直呼回调码 0）：
     * 系统预配热点已开启（SSID/密码为系统配置、App 不可读）——主线程触发
     * [HotspotListener.onSystemHotspotPasswordRequest] 请用户登记本机系统热点 SSID+密码；
     * 返回 [PUBLIC_TETHER_PENDING] 标记（startAsync 持有异步闸，等待
     * [completeSystemHotspotPassword] 经 [dispatchBinderTetherResult] 收敛成功结果，ip 现采）。
     */
    private fun systemTetherSuccess(): HotspotResult {
        DiagLogger.log(
            tag,
            "系统预配热点已开启（SSID/密码为系统配置、App 不可读），请求用户登记本机系统热点 SSID+密码",
        )
        mainHandler.post { listener.onSystemHotspotPasswordRequest() }
        return HotspotResult(success = false, error = PUBLIC_TETHER_PENDING)
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
     * ③ L2 本地热点（Local-only，无密码局域网；B3 真实现）——三版本分流（design 定稿 + v0.3.9.2 补丁）：
     * - sdk 26–28（Android 8-9）：`WifiManager.startLocalOnlyHotspot(callback, mainHandler)`（公开
     *   API 26+）→ [LocalOnlyHotspotReservation.wifiConfiguration] 读 SSID（已含引号，去引号）与
     *   preSharedKey（系统下发的随机密码）→ 全自动返回成功 [HotspotResult]（IP 经 [collectHotspotIp]
     *   采集，参考 ②；此路径免人工）；
     * - sdk 29–32（Android 10-12）：v0.3.9.2 起**放行调用**（不再直接禁用）——与其它版本一致调
     *   `startLocalOnlyHotspot`，onStarted 统一先试读 preSharedKey 实测「10-12 盲区」假设
     *   （真机 A12/sdk31 定案）：试读非空 → 推翻假设、自动完成成功 [HotspotResult]；
     *   试读空 → 确认盲区，返回 `HotspotResult(false, error="LocalOnlyHotspot 密码不可读（sdk=X 实测盲区），降级 ④")`
     *   交状态机降级 ④（手动）；
     * - sdk 33+（Android 13+）：调 startLocalOnlyHotspot 前先做 NEARBY_WIFI_DEVICES 运行时授权前置
     *   （v0.3.9-verify ③-②：未授权 → 回调 [HotspotListener.onNeedNearbyPermission] 引导授权、返回
     *   `AwaitingNearbyPermission` 待授权后重试，复用 Engine requestedPermission 链）；onStarted 后
     *   **统一先试读 preSharedKey**（26-33 全走同一逻辑；v0.3.9-verify ③-①：网页版主张 13+ 授权后
     *   App 侧可直接读，以实测定案；v0.3.9.2 扩展 26-32）——非空 → 自动完成（无论 sdk）；空/null
     *   （软 AP 配置未回传密码；系统弹窗/通知展示）→ 33+ 触发
     *   [HotspotListener.onLocalOnlyPasswordRequest](ssid) 请 UI 弹密码登记框、用户按系统弹窗回填 →
     *   [completeLocalOnlyPassword] 完成后返回成功 [HotspotResult]（ssid / pwd=用户登记值 / ip）；
     *   26-32 空 → 盲区失败降级 ④；
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
     *
     * 注（v0.5.9 UI1b-C）：LocalOnly 的 SSID/密码由系统生成，**不消费热点预设**（HotspotPresetStore）——
     * 预设仅用于自设 SSID 路径（② 私有 API 反射降级，见 [presetSsidOr]/[presetPasswordOr]）与手动④预填展示。
     */
    @Suppress("DEPRECATION") // startLocalOnlyHotspot(callback, handler) 自 API 33 起弃用（改无 handler 重载），26+ 统一走此重载
    private fun tryLocalOnlyHotspot(): HotspotResult {
        // v0.4.0：先快照热点启动前的旧 Wi-Fi 接口（collectHotspotIp 排除旧 Wi-Fi 网段/识别新接口用）
        snapshotPreHotspotInterfaces()
        val sdk = Build.VERSION.SDK_INT
        // v0.3.9.2：sdk 29-32 不再直接禁用（移除「盲区直接失败」）——放行调用，与 26-28/33+ 一致
        // 走 startLocalOnlyHotspot + onStarted 统一先试读 preSharedKey，实测「10-12 盲区」假设
        // （真机 A12/sdk31）：试读非空 → 推翻假设、自动完成；空 → 确认盲区降级 ④
        // （NEARBY 前置仅 sdk 33+ 生效，29-32 不经此前置直接落调用路径）

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

        // ★ v0.3.9-verify ③-②：sdk 33+ 调 startLocalOnlyHotspot 前的 NEARBY_WIFI_DEVICES 运行时授权
        // 前置（Android 13+ 强制，Manifest 已声明 neverForLocation）——未授权 → 回调
        // onNeedNearbyPermission 引导授权（Engine requestedPermission 授权链，授权后经
        // handleHotspotPermissionRetry 重跑组网重入 ③），返回 AwaitingNearbyPermission 待重试；
        // 已授权 → 放行 startLocalOnlyHotspot（onStarted 内 ③-① 再先试读密码）。
        if (Build.VERSION.SDK_INT >= 33) {
            val nearbyGranted = try {
                ctx.checkSelfPermission(NEARBY_WIFI_DEVICES_PERMISSION) == PackageManager.PERMISSION_GRANTED
            } catch (e: Exception) {
                DiagLogger.log(tag, "L2_LOCAL_ONLY NEARBY_WIFI_DEVICES 权限检查异常（按未授权处理，catch 兜底）: $e")
                false
            }
            if (!nearbyGranted) {
                DiagLogger.log(
                    tag,
                    "L2_LOCAL_ONLY(sdk=$sdk) 前置：NEARBY_WIFI_DEVICES 未授权（Android 13+ 强制，Manifest 已声明 neverForLocation），" +
                        "回调 onNeedNearbyPermission 走 requestedPermission 授权链，返回 AwaitingNearbyPermission 待授权后重试",
                )
                mainHandler.post { listener.onNeedNearbyPermission() }
                return HotspotResult(success = false, error = AWAITING_NEARBY_PERMISSION)
            }
        }

        DiagLogger.log(
            tag,
            "L2_LOCAL_ONLY：sdk=$sdk 调用 startLocalOnlyHotspot(callback, mainHandler)" +
                "（v0.3.9.2 起 29-32 放行实测盲区；结果主线程回调收敛）",
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
     * onStarted → 统一先试读 preSharedKey（26-33 全走同一逻辑：非空自动完成 / 空→33+ 回填、26-32 盲区失败）；
     * onFailed → 失败透传（含系统 reason）；
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

    /** ③ onStarted 收敛（主线程）：持有 reservation → 统一先试读 preSharedKey（v0.3.9-verify ③-① +
     *  v0.3.9.2 扩展 26-32，26-33 全走同一逻辑）：非空 → 自动完成（无论 sdk，含 29-32 推翻盲区假设）；
     *  空/null → 33+ 请求密码回填、26-32 报「密码不可读（sdk=X 实测盲区），降级 ④」（29-32 确认盲区）。 */
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
        // ★ v0.3.9-verify ③-① + v0.3.9.2：统一先试读 preSharedKey（26-33 全走同一逻辑，不再按 sdk
        // 分「可读/不可读」单路径）——网页版主张 Android 13+（sdk 33+）授权 NEARBY_WIFI_DEVICES 后
        // onStarted 可直接读密码，以实测定案；v0.3.9.2 扩展：29-32 同样先试读，实测「10-12 盲区」
        // 假设（真机 A12/sdk31）——非空 → 推翻假设、自动完成（无论 sdk）；空 → 33+ 走回填兜底、
        // 26-32 确认盲区、按失败降级 ④。
        // 注：cfg 已在上面 try/catch 读好（reservation.wifiConfiguration 访问可能抛异常），语义与
        // reservation.wifiConfiguration?.preSharedKey 一致，仅多一层异常防护。
        val pwd = cfg?.preSharedKey?.trim()?.removeSurrounding("\"")?.takeIf { it.isNotBlank() }
        if (pwd != null) {
            // v0.4.1（①③ 修复）：onStarted 时机热点接口未起，立即 collectHotspotIp 枚举拿不到
            // 热点接口 IP（返回空串，offer 携带空 IP）——onStarted 自动完成路径改**延迟采集**：
            // 主线程 postDelayed 1500ms 后再枚举（热点接口应已 up）；仍为空则每 500ms 重试
            // （postDelayed 链，不阻塞主线程），≤3s 收敛、首次成功即收；收敛后以最终 IP 组装
            // 成功结果 dispatchLocalOnlyResult（日志注明「延迟枚举等待热点接口 IP
            // （LocalOnly onStarted 时机接口未起）」）。
            DiagLogger.log(
                tag,
                "L2_LOCAL_ONLY 密码自动读取成功（统一先试读）：sdk=$sdk ssid=$ssid pwdLen=${pwd.length}（密码不回显）——延迟枚举等待热点接口 IP（LocalOnly onStarted 时机接口未起）",
            )
            collectHotspotIpDelayedThenDispatch(ssid, pwd)
            return
        }
        if (sdk in 26..32) {
            // 26-32 试读为空：密码不可读（29-32 实测定案盲区，确认假设；26-28 系统未下发密码同语义
            // 失败）→ 如实失败降级 ④（文案注明 sdk 与「实测盲区」）
            DiagLogger.log(
                tag,
                "L2_LOCAL_ONLY onStarted(sdk=$sdk)：试读 preSharedKey 为空（密码不可读，sdk=$sdk 实测盲区），按失败处理降级 ④",
            )
            dispatchLocalOnlyResult(
                HotspotResult(success = false, ssid = ssid, error = "LocalOnlyHotspot 密码不可读（sdk=$sdk 实测盲区），降级 ④"),
            )
            return
        }
        // sdk 33+：试读 preSharedKey 为空/null（软 AP 配置未回传密码；系统弹窗/通知展示 SSID 与密码）→
        // 走回填兜底：触发 UI 请用户按系统弹窗回填密码，完成经 [completeLocalOnlyPassword] 收敛
        pendingLocalOnlySsid = ssid
        DiagLogger.log(
            tag,
            "L2_LOCAL_ONLY(sdk=$sdk)：试读 preSharedKey 为空（软 AP 配置未回传密码；系统弹窗/通知展示），触发 onLocalOnlyPasswordRequest(ssid=$ssid) 请用户回填",
        )
        listener.onLocalOnlyPasswordRequest(ssid)
        // 等待 completeLocalOnlyPassword(pwd) 收敛（pendingLocalOnlyCb 保留；状态机步骤超时已放宽 120s）
    }

    /**
     * v0.4.1（①③ 修复）：③ L2 onStarted 自动完成路径的**延迟采集**——onStarted 时机热点接口未起，
     * 立即 [collectHotspotIp] 枚举拿不到热点接口 IP（返回空串，offer 携带空 IP）；本方法主线程
     * postDelayed 延迟 [HOTSPOT_IP_DELAY_MS]（1500ms）后再枚举（热点接口应已 up），仍为空则每
     * [HOTSPOT_IP_RETRY_INTERVAL_MS]（500ms）重试（postDelayed 链，不阻塞主线程），总等待
     * ≤ [HOTSPOT_IP_MAX_WAIT_MS]（3s）、首次成功即收；收敛后以最终 IP 组装成功结果
     * dispatchLocalOnlyResult（IP 仍未取到则空串，一期允许占位，与 HotspotResult 语义一致）。
     */
    private fun collectHotspotIpDelayedThenDispatch(ssid: String, pwd: String) {
        collectHotspotIpDelayedAttempt(ssid, pwd, attempt = 0, startedAt = System.currentTimeMillis())
    }

    /** ③ 延迟采集单次尝试（postDelayed 链，主线程）：attempt=0 延迟 1500ms，之后每 500ms 重试；≤3s 收敛。 */
    private fun collectHotspotIpDelayedAttempt(ssid: String, pwd: String, attempt: Int, startedAt: Long) {
        val delay = if (attempt == 0) HOTSPOT_IP_DELAY_MS else HOTSPOT_IP_RETRY_INTERVAL_MS
        mainHandler.postDelayed({
            val ip = collectHotspotIp()
            val elapsed = System.currentTimeMillis() - startedAt
            val done = ip.isNotBlank() || elapsed >= HOTSPOT_IP_MAX_WAIT_MS
            DiagLogger.log(
                tag,
                "延迟枚举等待热点接口 IP（LocalOnly onStarted 时机接口未起）：attempt=$attempt 延迟=${delay}ms " +
                    "elapsed=${elapsed}ms ip=${ip.ifEmpty { "<空>" }}（${if (ip.isNotBlank()) "首次成功即收" else "未取到（≤3s 超时收敛，空串占位）"}）",
            )
            if (done) {
                dispatchLocalOnlyResult(HotspotResult(success = true, ssid = ssid, pwd = pwd, ip = ip))
            } else {
                collectHotspotIpDelayedAttempt(ssid, pwd, attempt + 1, startedAt)
            }
        }, delay)
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
     * ③ sdk 33+ 密码回填兜底（引擎在用户按系统弹窗回填后调用，主线程）：onStarted 试读
     * preSharedKey 为空/null 后（v0.3.9-verify ③-①）触发本回填——校验非空后完成 L2 成功结果
     * （ssid / pwd=用户登记值 / ip=采集）并收敛 [pendingLocalOnlyCb]
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

    // ================= ② 系统预配热点（Binder 直呼：系统预配热点自动开） =================

    /**
     * ② 系统预配热点（Binder 直呼成功）SSID+密码登记（引擎在用户按提示填写
     * 本机系统热点名称与密码后调用，主线程）：校验非空后组装成功结果（ssid=用户登记值、pwd=登记值、
     * ip=现采）并收敛 [pendingBinderTetherCb]（状态机 onPrivateApiAsyncResult → onHotspotReady 发
     * offer）。密码全程不回显。
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
     * ② 系统预配热点结果收敛（public/Binder 共用，主线程）：释放待收敛状态与异步闸 → 回调 [pendingBinderTetherCb]
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
     * ② 系统预配热点（Binder 直呼）**实际关热点**入口（B4 温和收尾：传输完成后热点方点「关闭热点」调用）：
     * [stopBinderTetherPending] 仅为待收敛结果清理（pending 清理，不关热点）；实际关热点复用既有
     * k1/c 按名枚举 stopTethering 关分支——[mdTetherDispatch](turnOn=false) → [invokeStopTetheringCandidates]
     * （按名枚举 stopTethering invoke(int=0)）→ 失败落 [mdTetherBinder] stopTethering 兜底。
     * 后台线程 [hotspotExecutor] 执行（关分支含 sleep 等待，不占主线程）；turnWifiOn=false 不改动 Wi-Fi 状态；
     * 幂等（未开 ② 热点时仅清理 pending，no-op 安全；与 [stopLocalOnly] 同语义）。
     */
    fun stopBinderTether() {
        // pending 清理（幂等；登记框/等待期被中止时释放待收敛结果与异步闸，防后续启动悬挂）
        stopBinderTetherPending()
        val ctx = resolveContext()
        val wm = if (ctx != null) resolveWifiManager(ctx) else null
        if (ctx == null || wm == null) {
            DiagLogger.log(tag, "stopBinderTether：Context/WifiManager 不可用（仅清理 pending，无法派发关热点）")
            return
        }
        hotspotExecutor.execute {
            try {
                mdTetherDispatch(
                    ctx, wm,
                    turnOn = false, forceLegacy = false, turnWifiOn = false,
                    binderCode = AtomicInteger(CODE_NOT_RECEIVED),
                )
                DiagLogger.log(tag, "stopBinderTether：k1/c stopTethering 关热点已派发（后台线程，turnWifiOn=false 不改 Wi-Fi 状态）")
            } catch (e: Exception) {
                DiagLogger.log(tag, "stopBinderTether：关热点异常（不吞，已记录）: $e")
            }
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
     * 取热点本机 IPv4（②③ 用；NetworkInterface 枚举按热点网段打分）：
     * 枚举全部接口，按接口名/网段打分（ap 系 +100、热点开启后新出现的接口 +80、
     * 192.168.43.x 默认热点网段 +50、192.168.x +20、wlan +10、10./172. +5）取最优；
     * 并**排除旧 Wi-Fi 接口**（v0.4.0：热点启动前 [snapshotPreHotspotInterfaces] 快照的旧 Wi-Fi
     * 网段——对应握手 net.ssid 时刻设备所连旧 Wi-Fi，热点网段与之必然不同，排除避免旧 Wi-Fi IP
     * 平票/压过热点网段 IP）；无候选返回空串 ""（一期允许占位）。
     * 本路径免 root（②③ 均为非 root 通道），故不执行 su 命令采集。
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
        // v0.4.0：排除旧 Wi-Fi 网段（热点启动前快照；对应握手 net.ssid 时刻的旧 Wi-Fi 接口）
        val oldNet = preHotspotWifiNet
        val oldMask = preHotspotWifiMask
        val filtered = if (oldNet != null && oldMask != null) {
            candidates.filter { (iface, ip) ->
                val keep = (ipToInt(ip)?.and(oldMask) ?: oldNet) != oldNet
                if (!keep) {
                    DiagLogger.log(tag, "collectHotspotIp 排除旧 Wi-Fi 接口：iface=$iface ip=$ip（旧 Wi-Fi 网段）")
                }
                keep
            }
        } else {
            candidates
        }
        val best = filtered.maxByOrNull { scoreHotspotIface(it.first, it.second) } ?: return ""
        val ip = best.second
        if (ip.isBlank()) return ""
        DiagLogger.log(tag, "collectHotspotIp：iface=${best.first} ip=$ip（候选 ${filtered.size}/${candidates.size} 个）")
        return ip
    }

    /**
     * 接口名/网段打分（②③ 用）：ap 系 +100、热点开启后新出现的接口 +80、192.168.43.x +50、
     * 192.168.x +20、wlan +10、10./172. +5。
     */
    private fun scoreHotspotIface(iface: String, ip: String): Int {
        val n = iface.lowercase(Locale.US)
        var s = 0
        if (n.startsWith("ap") || n.contains("softap")) s += 100
        // v0.4.0 增强：热点开启后新出现的接口（LocalOnly 网段非 43.x 且接口名非 ap* 时（如
        // 192.168.49.x/wlan1 等）的兜底识别——旧接口（含旧 Wi-Fi wlan0）在快照 preHotspotIfaces 中，不获此加分）
        if (preHotspotIfaces.isNotEmpty() && !preHotspotIfaces.containsKey(iface)) s += 80
        if (ip.startsWith("192.168.43.")) s += 50 // Android 默认热点网段
        if (ip.startsWith("192.168.")) s += 20 // 全部 192.168.x 均计入（LocalOnly 常用 192.168.49.x）
        if (n.contains("wlan")) s += 10
        if (ip.startsWith("10.") || ip.startsWith("172.")) s += 5
        return s
    }

    /**
     * 快照热点启动前的网络接口（②③ 启动前调用；旧 Wi-Fi 排除与新接口识别共用）：
     * - [preHotspotIfaces]：当前全部 up 的非回环 IPv4 接口（接口名 → IP）；
     * - [preHotspotWifiNet]/[preHotspotWifiMask]：旧 Wi-Fi 主接口（优先 wlan*）的网段与掩码——
     *   对应握手 net.ssid 时刻设备所连旧 Wi-Fi；热点网段与之必然不同，collectHotspotIp 据此排除。
     * 线程：随 [startAsync] 后台线程执行（枚举网络接口无阻塞 IO）；volatile 供主线程 collect 读取。
     */
    private fun snapshotPreHotspotInterfaces() {
        val ifaces = enumerateIpv4Interfaces()
        preHotspotIfaces = ifaces
        val oldEntry = ifaces.entries.firstOrNull { it.key.contains("wlan") } ?: ifaces.entries.firstOrNull()
        if (oldEntry != null) {
            // 旧 Wi-Fi 接口前缀长度：从接口地址取（缺省按 /24）
            val prefix = try {
                NetworkInterface.getByName(oldEntry.key)?.interfaceAddresses
                    ?.firstOrNull { it.address is Inet4Address }
                    ?.networkPrefixLength?.toInt() ?: 24
            } catch (e: Exception) {
                24
            }
            preHotspotWifiNet = ipToInt(oldEntry.value)?.and(prefixToMaskInt(prefix))
            preHotspotWifiMask = prefixToMaskInt(prefix)
            DiagLogger.log(
                tag,
                "热点启动前接口快照：${ifaces.size} 个 IPv4 接口，旧 Wi-Fi iface=${oldEntry.key} " +
                    "ip=${oldEntry.value} mask=/$prefix（collectHotspotIp 将排除旧 Wi-Fi 网段）",
            )
        } else {
            preHotspotWifiNet = null
            preHotspotWifiMask = null
            DiagLogger.log(tag, "热点启动前接口快照：${ifaces.size} 个 IPv4 接口，未识别旧 Wi-Fi 接口（不排除）")
        }
    }

    /** 枚举全部 up 的非回环 IPv4 接口（接口名 → 首个 IPv4；无则空 map；不联网）。 */
    private fun enumerateIpv4Interfaces(): Map<String, String> {
        val m = linkedMapOf<String, String>()
        try {
            NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { ni ->
                if (!ni.isUp || ni.isLoopback) return@forEach
                for (ia in ni.interfaceAddresses) {
                    val a = ia.address
                    if (a is Inet4Address && !a.isLoopbackAddress && !a.isLinkLocalAddress) {
                        val ip = a.hostAddress
                        if (!ip.isNullOrBlank() && !m.containsKey(ni.name)) m[ni.name] = ip
                    }
                }
            }
        } catch (e: Exception) {
            DiagLogger.log(tag, "NetworkInterface 枚举失败: $e")
        }
        return m
    }

    /** IPv4 点分十进制 → int（解析失败 null）。 */
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

    /** 前缀长度 → 掩码 int（0..32 收敛）。 */
    private fun prefixToMaskInt(prefix: Int): Int {
        val bits = prefix.coerceIn(0, 32)
        return if (bits == 0) 0 else (0xFFFFFFFFL shl (32 - bits)).toInt()
    }

    /**
     * v0.5.9 UI1b-C：预设 SSID 取值（自设 SSID 分支统一入口）——预设启用（[HotspotPresetStore.enabled]）
     * 且 ssid 非空时返回预设值；否则 [fallback]（原随机生成 [generateSsid]）。
     * ③ LocalOnly 系统生成 SSID 不消费预设（系统生成，App 不可指定）。
     */
    private fun presetSsidOr(fallback: () -> String): String {
        val store = presetStore
        if (store != null && store.enabled() && store.ssid().isNotBlank()) {
            DiagLogger.log(tag, "热点预设生效：自设 SSID 用预设（${store.ssid()}）")
            return store.ssid()
        }
        return fallback()
    }

    /**
     * v0.5.9 UI1b-C：预设密码取值——预设启用且 ssid 非空时取预设密码；预设密码为空（null/空白，
     * 留空随机语义）→ [fallback]（原随机生成 [generatePassword]）；预设未启用 → [fallback]（现行为）。
     */
    private fun presetPasswordOr(fallback: () -> String): String {
        val store = presetStore
        if (store != null && store.enabled() && store.ssid().isNotBlank()) {
            val preset = store.password()
            if (!preset.isNullOrBlank()) {
                DiagLogger.log(tag, "热点预设生效：自设密码用预设（长度=${preset.length}，密码不回显）")
                return preset
            }
            DiagLogger.log(tag, "热点预设：预设密码留空 → 沿用原随机生成")
        }
        return fallback()
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
        /**
         * ② 私有 API 路径联调开关：v0.5.14 起置 false（启用 ②，回归私有 API 反射路径——
         * k1/c 式按名枚举 Binder 直呼第一手段 + setWifiApEnabled 降级全链，见 [tryPrivateApiHotspot]）；
         * 如需强制 LocalOnly（③）联调可临时置 true（② 直接失败、走状态机既有降级链落 ③）。
         */
        private const val DISABLE_PRIVATE_API = false

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

        /** ③ L2 onStarted 延迟枚举等待热点接口 IP 的初始延迟（v0.4.1：LocalOnly onStarted 时机接口未起，主线程 postDelayed 1500ms 后再枚举）。 */
        private const val HOTSPOT_IP_DELAY_MS: Long = 1_500L

        /** ③ L2 onStarted 延迟枚举重试间隔（首次枚举为空时每 500ms 重试，postDelayed 链不阻塞主线程）。 */
        private const val HOTSPOT_IP_RETRY_INTERVAL_MS: Long = 500L

        /** ③ L2 onStarted 延迟枚举总等待上限（≤3s：1500+500*3 共 4 次尝试，首次成功即收）。 */
        private const val HOTSPOT_IP_MAX_WAIT_MS: Long = 3_000L

        /** ② 反射 setWifiApEnabled 后轮询 isWifiApEnabled 的最长等待（任务约定 ≤5s）。 */
        private const val PRIVATE_AP_POLL_TIMEOUT_MS: Long = 5_000L

        /** ② 轮询 isWifiApEnabled 间隔（任务约定 400ms）。 */
        private const val PRIVATE_AP_POLL_INTERVAL_MS: Long = 400L

        /** ② 系统预配热点成功待登记标记（error 字段，现役语义：Binder 直呼；等待用户登记本机系统热点 SSID+密码后经 [completeSystemHotspotPassword] 收敛）。 */
        private const val PUBLIC_TETHER_PENDING = "PublicTetherPending"

        /** ② Binder 直呼前置缺失标记（error 字段，NEARBY_WIFI_DEVICES 未授；Engine 走 requestedPermission 授权链，授权后自动重试热点）。 */
        private const val NEED_NEARBY_PERMISSION = "NeedNearbyPermission"

        /** ③ L2 本地热点前置缺失标记（v0.3.9-verify ③-②：error 字段，sdk 33+ 调 startLocalOnlyHotspot 前 NEARBY_WIFI_DEVICES 未授；Engine 走 requestedPermission 授权链，授权后重跑组网重入 ③）。 */
        private const val AWAITING_NEARBY_PERMISSION = "AwaitingNearbyPermission"

        /** ② NEARBY_WIFI_DEVICES 权限名字面量（sdk≥33 强制，Manifest 已声明 neverForLocation）。 */
        private const val NEARBY_WIFI_DEVICES_PERMISSION = "android.permission.NEARBY_WIFI_DEVICES"

        /** ② Binder 直呼回调/状态轮询确认最长等待（任务约定 8s 超时兜底）。 */
        private const val BINDER_CONFIRM_TIMEOUT_MS: Long = 8_000L

        /** ② Binder 直呼回调未达时状态轮询间隔（与 ② 反射轮询同节奏 400ms）。 */
        private const val BINDER_POLL_INTERVAL_MS: Long = 400L

        /** ② ResultReceiver/回调未达的初始错误码（区别于真实回调码；smali 侧服务无回调监听，port 增强）。 */
        private const val CODE_NOT_RECEIVED = -1

        /** ② startTethering type=0（WIFI，AOSP TetheringManager.TETHERING_WIFI；smali Integer.valueOf(0) 原样）。 */
        private const val TETHERING_TYPE_WIFI = 0

        /** ② startTethering 成功回调码（AOSP TetherErrorCode NO_ERROR=0 / IConnectivityManager TETHER_ERROR_NO_ERROR=0）。 */
        private const val TETHER_ERROR_NO_ERROR = 0

        /** ② k1/c 串（md-in/hotspot-symbols.txt k1/c 段，dex 1888568）：startTethering 按名枚举 find null 时的日志（只记日志不崩）。 */
        private const val K1C_START_TETHERING_NOT_FOUND = "ConnectivityManager.startTetheringMethod() is not found"

        /** ② smali 归一化 AP 状态常量（WifiManager.getWifiApState 减 10 后与 WIFI_STATE_* 同编号；compileSdk37 jar 常量对 Kotlin 不可见，用字面量）。 */
        private const val SMALI_WIFI_STATE_DISABLED = 1
        private const val SMALI_AP_ENABLING = 2
        private const val SMALI_AP_ENABLED = 3
        private const val SMALI_AP_FAILED = 4

        /** ② smali 提示串（w1.u 等价 Toast 文案："Could not change wifi state" / custom ROM）。 */
        private const val SMALI_TOAST_TITLE = "Could not change wifi state"
        private const val SMALI_TOAST_DRIVER_MSG = "The wifi state could not be changed due to a problem with your wifi driver. This is most likely due to a problem in a custom ROM."
    }
}
