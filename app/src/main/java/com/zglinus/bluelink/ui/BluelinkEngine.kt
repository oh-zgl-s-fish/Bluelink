package com.zglinus.bluelink.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.zglinus.bluelink.ble.BleAdvertiser
import com.zglinus.bluelink.ble.BleScanner
import com.zglinus.bluelink.ble.GattClient
import com.zglinus.bluelink.ble.GattServer
import com.zglinus.bluelink.ble.HandshakeMessage
import com.zglinus.bluelink.ble.RootDetector
import com.zglinus.bluelink.ble.SessionManager
import com.zglinus.bluelink.ble.SignalMessage
import com.zglinus.bluelink.ble.SignalProtocol
import com.zglinus.bluelink.ble.Constants
import com.zglinus.bluelink.ble.SignalTest
import com.zglinus.bluelink.diag.DiagLogger
import com.zglinus.bluelink.transport.LocalSendClient
import com.zglinus.bluelink.transport.LocalSendServer
import com.zglinus.bluelink.transport.SendFile
import com.zglinus.bluelink.net.NetworkInfoProvider
import com.zglinus.bluelink.net.NetworkSummary
import com.zglinus.bluelink.net.SameLanChecker
import com.zglinus.bluelink.net.WifiJoiner
import com.zglinus.bluelink.networking.Capability
import com.zglinus.bluelink.networking.HotspotListener
import com.zglinus.bluelink.networking.HotspotManager
import com.zglinus.bluelink.networking.HotspotResult
import com.zglinus.bluelink.networking.NetState
import com.zglinus.bluelink.networking.NetworkingStateMachine
import com.zglinus.bluelink.networking.buildLocalCapability
import com.zglinus.bluelink.networking.decide
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 一期 BLE 链路接线（生命周期由 MainActivity 持有）：
 * - 广播 / 扫描 / GATT Server 随顶部开关启停；
 * - GATT Client 在点击设备时发起握手；
 * - 握手结果（Server 端收 + Client 端收）统一写入 [BluelinkUiState]。
 *
 * A5 组网接线（异网设备组建临时局域网，A 包收官）：
 * - [HotspotManager]（A3b）：④ 手动路径 onManualRequest → ui.manualPwdDialog；
 * - ③ L2 本地热点（B3）：26-28 全自动；13+ 前置 NEARBY_WIFI_DEVICES 授权（onNeedNearbyPermission →
 *   requestedPermission → handleHotspotPermissionRetry 重跑）→ onStarted 先试读 preSharedKey
 *   （v0.3.9-verify ③-①，实测定案），试读空才 onLocalOnlyPasswordRequest → ui.localOnlyPwdDialog
 *   （用户按系统弹窗回填密码 → confirmLocalOnlyPwd → completeLocalOnlyPassword）；
 *   reservation 收尾经 stopLocalOnly（onAbort / stopAllBle 接线，B4 正式收尾前预留释放入口）；
 * - [NetworkingStateMachine]（A3c）：点「组建临时局域网」→ 按本机/对端能力仲裁后创建，
 *   阶段经轮询 [NetworkingStateMachine.currentState] 映射到 [BluelinkUiState.netState]；
 * - [WifiJoiner]（A4）：对端流程收到 offer → join，结果 onJoined/onFailed 回灌状态机；
 * - ④ 手动流程：密码登记（setPassword + 打开系统热点设置）→ onManualConfigured → offer。
 *
 * v0.3.9 ③ LocalOnly 自测（独立入口，不经过组网/状态机）：主界面「LocalOnly 自测」按钮 →
 * [localOnlySelfTest] 直接驱动 `WifiManager.startLocalOnlyHotspot(callback, mainHandler)` 单环节
 * 验证（专测 A15/sdk35 上 ③ 的行为：热点是否自动开、系统弹窗/密码回填、reservation close）；
 * 结果写 [BluelinkUiState.localOnlyTestInfo] / [BluelinkUiState.localOnlyTestRunning]，
 * 与 [HotspotManager] ③ 真路径同源同 API 但独立实现（互不影响，各自持有 reservation）。
 * v0.3.9.2 自测同步：onStarted 统一先试读 preSharedKey（26-33 全走同一逻辑，与主路径一致）——
 * 29-32 盲区假设实测入口：试读非空 → 推翻假设（自动完成文案）；空 → 「盲区（实测确认）」落 ④。
 *
 * Bluelink 组网补丁：对端收到 offer 但状态机未启动（netStateMachine==null）或不在等 offer 态时，
 * offer 会被状态机分发忽略 → 无人 join、无人回 joined、热点方等 joined 超时。
 * 补丁在 onRemoteSignal 分发点拦截：状态机不在等 offer → [handlePeerOffer] 直接接管
 * （WifiJoiner.join 接入 + 回 joined{ip}），复用 [SignalProtocol.TYPE_JOINED] 协议载荷。
 * v0.4.4：接管路径同步捕获 offer 载荷 ip（A 端热点 IP，[takeoverPeerIp]）并消费热点方 ack
 * （[handlePeerAck]）——offer 被引擎接管后状态机为 null / 未进入 JOINED（onAck 无法触发，真机实锤：
 * B 收 ack 后仅 ping/pong、无状态转移）→ engine 直接完成 ack→TRANSPORT（启动 LocalSend 服务），
 * 与状态机路径 JOINED→TRANSPORT→onTransportReady 对齐；发 joined 后 120s ack 超时（对齐
 * NetworkingStateMachine 的 JOINED 等 ack，同 MANUAL_TIMEOUT_MS 语义）。
 *
 * T3 LocalSend 传输接线：TRANSPORT 后自动启动 [LocalSendServer]（alias=Build.MODEL）供对端经 53317
 * 发送文件；「发送文件」SAF 选文件 → [confirmSend] 后台线程 [LocalSendClient.send]，进度/结果写
 * transferState + DiagLogger（内容不回显）；服务端每文件完整落盘（[LocalSendServer.onFileReceived]，
 * 语义 = 文件已入**暂存** filesDir/localsend/，v0.4.5 不再自建收件列表/私有目录常态化展示）→ 接收侧经
 * 用户 SAF OpenDocumentTree 选择的目录转存：已选（[receiveDirUri]，[onReceiveDirPicked]）→ 后台
 * DocumentFile.createFile 拷贝后删暂存原件；未选 → 提示「请选择保存位置」（MainScreen 弹目录选择器），
 * 选定后再补存（[pendingStagedFiles] 排队）；轮询 getActiveSessions 映射「接收中 …」到 transferState；
 * 中止/停止时停服务与轮询（暂存文件保留在磁盘）。
 *
 * 所有 BLE 回调已由各封装切回主线程；UI 状态只在主线程写入。
 */
class BluelinkEngine(private val context: Context) {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private val bluetoothManager: BluetoothManager? =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    @Suppress("DEPRECATION")
    private val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()

    private val advertiser = BleAdvertiser(mainHandler, object : BleAdvertiser.Callbacks {
        override fun onAdvertisingStarted() {
            ui.advertising = true
            ui.advertiserError = null
            DiagLogger.log(TAG, "广播已启动（回调 UI）")
        }

        override fun onAdvertisingFailed(reason: String) {
            ui.advertising = false
            ui.advertiserError = reason
            DiagLogger.log(TAG, "广播启动失败回调 UI: $reason")
        }
    })

    private val scanner = BleScanner(mainHandler, object : BleScanner.Callbacks {
        override fun onScanResult(result: android.bluetooth.le.ScanResult) {
            handleScanResult(result)
        }

        override fun onScanFailed(reason: String) {
            ui.scanError = reason
            DiagLogger.log(TAG, "扫描失败回调 UI: $reason")
        }
    })

    private val gattServer = GattServer(appContext, mainHandler, object : GattServer.Callbacks {
        override fun onRemoteHandshake(deviceAddress: String, handshake: HandshakeMessage) {
            DiagLogger.log(TAG, "Server 收到远端握手: $deviceAddress alias=${handshake.alias}")
            applyRemoteHandshake(deviceAddress, handshake)
        }

        override fun onRemoteSignal(deviceAddress: String, msg: SignalMessage) {
            sessionManager.onRemoteSignal(deviceAddress, msg)
        }

        override fun onDeviceDisconnected(deviceAddress: String) {
            sessionManager.onServerLegLost(deviceAddress)
        }
    })

    private val gattClient = GattClient(appContext, mainHandler, object : GattClient.Callbacks {
        override fun onHandshakeCompleted(deviceAddress: String, handshake: HandshakeMessage) {
            ui.handshaking = false
            ui.handshakeError = null
            DiagLogger.log(TAG, "握手完成回调 UI: $deviceAddress alias=${handshake.alias}")
            applyRemoteHandshake(deviceAddress, handshake)
        }

        override fun onHandshakeFailed(deviceAddress: String, reason: String) {
            ui.handshaking = false
            ui.handshakeError = reason
            DiagLogger.log(TAG, "握手失败回调 UI: $deviceAddress reason=$reason")
            sessionManager.onHandshakeFailed(deviceAddress) // 自动重连握手失败时收敛会话状态
        }

        override fun onRemoteSignal(deviceAddress: String, msg: SignalMessage) {
            sessionManager.onRemoteSignal(deviceAddress, msg)
        }

        override fun onSessionDisconnected(deviceAddress: String) {
            sessionManager.onSessionDisconnected(deviceAddress) // 断线自动重连（一次）
        }
    })

    /** 持久信令会话管理器（A2）：握手成功后 attach 保留连接，信令经 WRITE/NOTIFY 通道收发。 */
    private lateinit var sessionManager: SessionManager

    /** 信令自测（验证包）：attach 后自动 120s 心跳 ping/pong 收发（真机验证通道长时双工可靠性）。 */
    private lateinit var signalTest: SignalTest

    val ui = BluelinkUiState()

    // ============ T3 LocalSend 传输（发送/接收） ============

    /** LocalSend 服务（T1）：TRANSPORT 就绪后自动启动，供对端经 53317 发送文件到本机（alias=Build.MODEL）。 */
    val localsendServer = LocalSendServer(appContext, Build.MODEL)

    /** 传输就绪时记录的对端 IPv4（TRANSPORT 后发送目标；一期可能为占位 ""）。 */
    @Volatile
    internal var transportPeerIp: String = ""

    /** 进行中的发送客户端（transferState 旁「取消」→ cancel()）。 */
    @Volatile
    private var activeSendClient: LocalSendClient? = null

    /** SAF 选文件后、发送确认前的待发文件（引擎内部持有；确认框经 ui.sendDialog 展示）。 */
    private var pendingSendUri: Uri? = null

    /** 待发文件名（发送确认框展示用；同包 MainScreen 读取）。 */
    internal var pendingSendName: String? = null

    /** 待发文件大小（发送确认框展示用；同包 MainScreen 读取）。 */
    internal var pendingSendSize: Long = 0L

    /**
     * 接收进度轮询（T3）：服务运行期间每 [RECEIVE_POLL_INTERVAL_MS] 读一次
     * [LocalSendServer.getActiveSessions]，映射到 ui.transferState「接收中 文件名 xx%」；
     * 无进行中会话且此前为接收状态时清空。服务停止时自停。
     */
    private val receivePoller = object : Runnable {
        override fun run() {
            if (!localsendServer.isRunning) {
                mainHandler.removeCallbacks(this)
                return
            }
            val sessions = localsendServer.getActiveSessions()
            if (sessions.isEmpty()) {
                if (ui.transferState?.startsWith("接收中") == true) ui.transferState = null
            } else {
                val progress = sessions.entries.first().value
                val pct = if (progress.size > 0) (progress.received * 100 / progress.size).toInt() else 0
                ui.transferState = "接收中 ${progress.fileName.ifBlank { "文件" }} $pct%"
            }
            mainHandler.postDelayed(this, RECEIVE_POLL_INTERVAL_MS)
        }
    }

    // ============ v0.4.5 接收侧：SAF 保存目录（暂存 → 用户目录转存） ============

    /** 接收保存目录（SAF OpenDocumentTree tree uri；null=未选择，收到文件时提示点选后补存）。 */
    @Volatile
    private var receiveDirUri: Uri? = null

    /** 已完整落盘到暂存目录、等待转存用户目录的文件（key=暂存绝对路径，天然去重；线程安全）。 */
    private val pendingStagedFiles = ConcurrentHashMap<String, StagedFile>()

    /** 暂存文件元信息（fileName 展示用 / path 拷贝源 / mimeType createFile 用）。 */
    private class StagedFile(val fileName: String, val path: String, val mimeType: String)

    // ============ A5 组网接线 ============

    /** 对端接入器（A4）：对端流程收到 offer 后接入对方热点，结果经 [wifiJoinCallbacks] 回灌。 */
    private val wifiJoiner = WifiJoiner(appContext)

    /** WifiJoiner 结果回调：成功→状态机 onWifiJoined；失败→手动密码重试对话框；需 WRITE_SETTINGS 引导（8-10 前置 / 12+ 兜底）。 */
    private val wifiJoinCallbacks = object : WifiJoiner.Callbacks {
        override fun onJoined(ip: String) {
            DiagLogger.log(TAG, "WifiJoiner 接入成功 ip=${ip.ifEmpty { "<空>" }}，回灌状态机 onWifiJoined")
            netStateMachine?.onWifiJoined(ip.ifEmpty { "" })
        }

        override fun onFailed(reason: String) {
            DiagLogger.log(TAG, "WifiJoiner 接入失败: $reason")
            // 系统弹窗未确认 / 超时等：弹「手动输入密码」对话框供用户重试
            ui.joinFailDialog = true
            ui.joinFailReason = reason
            netStateMachine?.onWifiJoinFailed(reason)
        }

        override fun onNeedWriteSettingsPermission() {
            DiagLogger.log(TAG, "需要 WRITE_SETTINGS 授权（8–10 前置 / 12+ SecurityException 兜底），引导系统设置")
            ui.writeSettingsDialog = true
        }

        override fun onNeedPermission(permission: String) {
            DiagLogger.log(TAG, "WifiJoiner 缺少权限 $permission：置 requestedPermission 发起系统授权，授权后自动重试 join")
            if (permission == Manifest.permission.WRITE_SETTINGS || permission == Manifest.permission.CHANGE_NETWORK_STATE) {
                // CHANGE_NETWORK_STATE（normal，声明即授予）/ WRITE_SETTINGS（app-op「修改系统设置」）
                // 均无法经系统运行时授权弹窗授予（Android 12 部分 ROM requestNetwork 仍要求
                // WRITE_SETTINGS 兜底）：复用 WriteSettingsDialog / openWriteSettings 引导。
                DiagLogger.log(TAG, "权限 $permission 非运行时权限：引导「修改系统设置」（WRITE_SETTINGS）授权")
                ui.writeSettingsDialog = true
                ui.netState = "接入需「修改系统设置」（WRITE_SETTINGS）授权，请授权后重试"
                return
            }
            pendingJoinPermission = permission
            ui.requestedPermission = permission
            ui.joinRetryNeeded = true
            ui.netState = "接入需要权限 $permission，请授权后自动重试"
        }
    }

    /**
     * 引擎接管 offer 时的 WifiJoiner 结果回调（Bluelink 组网补丁）：
     * 状态机不在等 offer 态（null / 未启动 / 已中止 / 非等待态）时由 engine 直接接管——
     * 成功 → 直接发 joined{ip} 回报热点方（复用 [SignalProtocol.TYPE_JOINED] 载荷）；
     * 失败 → 手动密码重试对话框；需 WRITE_SETTINGS 时引导授权（8–10 前置 / 12+ 兜底）。
     */
    private val peerOfferJoinCallbacks = object : WifiJoiner.Callbacks {
        override fun onJoined(ip: String) {
            DiagLogger.log(TAG, "接管接入成功 ip=${ip.ifEmpty { "<空>" }}，发送 joined 回报热点方")
            val ok = sessionManager.sendSignal(
                SignalMessage(SignalProtocol.TYPE_JOINED, JSONObject().put("ip", ip))
            )
            if (!ok) {
                DiagLogger.log(TAG, "joined 回报发送失败（无会话/无通道）")
                ui.netState = "已接入热点，但 joined 回报发送失败（无会话通道）"
                return
            }
            DiagLogger.log(TAG, "joined 回报已发送 ip=${ip.ifEmpty { "<空>" }}")
            ui.netState = "已接入热点，等待对方确认（IP：${ip.ifEmpty { "<未知>" }}）"
            // v0.4.4：从机 ack 超时对齐状态机 JOINED 等 ack（120s，MANUAL_TIMEOUT_MS 同一值）——
            // 热点方 ack 未到（已中止/通道异常）给出明确提示，不无限挂起
            mainHandler.removeCallbacks(takeoverAckTimeoutRunnable)
            mainHandler.postDelayed(takeoverAckTimeoutRunnable, TAKEOVER_ACK_TIMEOUT_MS)
        }

        override fun onFailed(reason: String) {
            DiagLogger.log(TAG, "接管接入失败: $reason")
            ui.joinFailDialog = true
            ui.joinFailReason = reason
            ui.netState = "接入失败：$reason"
        }

        override fun onNeedWriteSettingsPermission() {
            DiagLogger.log(TAG, "接管路径需要 WRITE_SETTINGS 授权（8–10 前置 / 12+ SecurityException 兜底），引导系统设置")
            ui.writeSettingsDialog = true
            ui.netState = "接入需 WRITE_SETTINGS 授权，请先授权后重试"
        }

        override fun onNeedPermission(permission: String) {
            DiagLogger.log(TAG, "接管路径 WifiJoiner 缺少权限 $permission：发起系统授权，授权后自动重试 join")
            if (permission == Manifest.permission.WRITE_SETTINGS || permission == Manifest.permission.CHANGE_NETWORK_STATE) {
                // 同 wifiJoinCallbacks：非运行时权限，无法经运行时弹窗授予 → 复用
                // WriteSettingsDialog / openWriteSettings 引导「修改系统设置」兜底。
                DiagLogger.log(TAG, "接管路径权限 $permission 非运行时权限：引导「修改系统设置」（WRITE_SETTINGS）授权")
                ui.writeSettingsDialog = true
                ui.netState = "接入需「修改系统设置」（WRITE_SETTINGS）授权，请授权后重试"
                return
            }
            pendingJoinPermission = permission
            ui.requestedPermission = permission
            ui.joinRetryNeeded = true
            ui.netState = "接入需要权限 $permission，请授权后自动重试"
        }
    }

    /**
     * 热点管理器（A3b）：① root 真路径，② 私有 API 反射真路径（B2），③ Local-only 本地热点真路径
     * （B3：startLocalOnlyHotspot 三版本分流——26-28 全自动 / 29-32 放行调用+onStarted 统一先试读
     * 实测盲区（v0.3.9.2） / 33+ 密码回填，见 HotspotManager.tryLocalOnlyHotspot），④ 手动路径触发 UI 密码登记。
     * Bluelink ANR 修复（构造/回调兼容确认）：L1_ROOT / L1_PRIVATE_API 均改由
     * [HotspotManager.startAsync] 后台线程执行、主线程回调——mainHandler 由 HotspotManager
     * 内部经 Looper.getMainLooper() 自建，无需注入；本构造（listener + context=null）下
     * ② 的 Context 经 ActivityThread.currentApplication() 兜底（HotspotManager.resolveContext）；
     * onHotspotReady / onWriteSettingsPermission / onNeedNearbyPermission 回调均为本构造新增/既有接线。
     */
    private val hotspotManager = HotspotManager(object : HotspotListener {
        override fun onManualRequest() {
            // ④ 手动路径：请求 UI 引导用户手动配网（密码登记对话框）
            DiagLogger.log(TAG, "④ 手动配网请求：打开密码登记对话框")
            ui.manualPwdDialog = true
            ui.manualPwdInput = "" // 新流程清空上次输入
            ui.netState = "手动配网：请设置热点并登记密码"
        }

        override fun onHotspotReady(result: HotspotResult) {
            DiagLogger.log(TAG, "热点就绪：success=${result.success} ssid=${result.ssid} error=${result.error}")
            if (result.success) {
                ui.netState = "热点已就绪（ssid=${result.ssid}）"
            }
        }

        override fun onWriteSettingsPermission() {
            // ② 私有 API 反射热点前置缺失（WRITE_SETTINGS「修改系统设置」AppOps，Android 10+ 反射
            // setWifiApEnabled 需此权限）：复用现有 WriteSettingsDialog / openWriteSettings 引导。
            // 授权返回后的重试由现有孤儿兜底覆盖（confirmManualPwd 自动补 startNetworking 重跑 ②；
            // 状态机中止后重新点击「组建临时局域网」同样重跑 ②——届时 canWrite=true 直接进入反射）。
            DiagLogger.log(TAG, "② 私有 API 热点需 WRITE_SETTINGS 授权（修改系统设置），引导系统设置")
            ui.writeSettingsDialog = true
            ui.netState = "开启热点需「修改系统设置」（WRITE_SETTINGS）授权，请授权后重试"
        }

        override fun onLocalOnlyPasswordRequest(ssid: String) {
            // ③ L2 本地热点（13+，sdk 33+）：系统弹窗/通知已展示 SSID 与密码，App 侧密码不可读
            // （软 AP 配置不回传密码）——弹出密码登记框（标题带 SSID），请用户按系统弹窗回填；
            // 确认后经 confirmLocalOnlyPwd → HotspotManager.completeLocalOnlyPassword 完成成功结果。
            DiagLogger.log(TAG, "③ L2 本地热点请求回填密码（13+ 系统弹窗展示）：ssid=$ssid")
            ui.localOnlyPwdDialog = true
            ui.localOnlySsid = ssid
            ui.manualPwdInput = "" // 新流程清空上次输入
            ui.netState = "本地热点已启动（SSID=$ssid）：请按系统弹窗密码回填登记"
        }

        override fun onSystemHotspotPasswordRequest() {
            // ② Binder 直呼系统热点（v0.3.4）：系统预配热点已自动开启，SSID/密码为系统配置、
            // App 不可读——复用 ④ manualPwdDialog 登记框（systemHotspotPwdMode 模式）请用户
            // 按本机热点信息登记 SSID+密码；确认经 confirmSystemHotspotPwd →
            // HotspotManager.completeSystemHotspotPassword 完成成功结果。
            // 防幽灵弹窗：状态机已不处于 HOTSPOT_STARTING（中止/超时）时跳过（stopBinderTetherPending
            // 已清理待收敛结果，不再上抛）。
            val machine = netStateMachine
            if (machine == null || machine.currentState != NetState.HOTSPOT_STARTING) {
                DiagLogger.log(
                    TAG,
                    "② Binder 直呼成功但状态机已不在 HOTSPOT_STARTING（machine=${machine != null} state=${machine?.currentState}），跳过登记框（结果已被清理）",
                )
                return
            }
            DiagLogger.log(TAG, "② 系统预配热点已自动开启：请求登记本机系统热点 SSID+密码（复用登记框）")
            ui.systemHotspotPwdMode = true
            ui.manualPwdDialog = true
            ui.manualSsidInput = "我系统热点名" // 预填提示（用户改为本机系统热点实际名称）
            ui.manualPwdInput = "" // 新流程清空上次输入
            ui.netState = "系统热点已自动开启：请输入本机系统热点的名称与密码"
        }

        override fun onNeedNearbyPermission() {
            // NEARBY_WIFI_DEVICES（Android 13+，Manifest 已声明 neverForLocation）运行时授权前置缺失——
            // 触发方：② public startTethering（v0.3.5 第一手段）前置 / startTethering 抛 SecurityException；
            // ③ L2 本地热点（v0.3.9-verify ③-②）调 startLocalOnlyHotspot 前的前置（未授权引导授权）。
            // 复用现有 requestedPermission 授权链（BluelinkRoot LaunchedEffect 发起系统授权弹窗，
            // 结果经 onJoinPermissionResult → handleHotspotPermissionRetry 自动重试热点，覆盖 ②/③——
            // 重跑 startNetworking：② 降级后重入 ③ 时前置已授权）。
            // 已授权仍触发（② SecurityException 可能为其他原因）→ 不重复弹授权（防授权重试死循环）。
            val granted = try {
                appContext.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) ==
                    PackageManager.PERMISSION_GRANTED
            } catch (e: Exception) {
                DiagLogger.log(TAG, "检查 NEARBY_WIFI_DEVICES 异常: $e")
                false
            }
            if (granted) {
                DiagLogger.log(TAG, "NEARBY_WIFI_DEVICES 已授权但 ②/③ 路径仍失败（② SecurityException 可能为其他原因），不重复发起授权")
                ui.netState = "开启热点失败：系统仍拒绝热点（NEARBY_WIFI_DEVICES 已授权）"
                return
            }
            pendingHotspotPermission = Manifest.permission.NEARBY_WIFI_DEVICES
            ui.requestedPermission = Manifest.permission.NEARBY_WIFI_DEVICES
            DiagLogger.log(TAG, "②/③ 开启热点需 NEARBY_WIFI_DEVICES 权限：置 requestedPermission 发起系统授权，授权后自动重试")
            ui.netState = "开启热点需要「附近的设备」权限（NEARBY_WIFI_DEVICES），请授权后自动重试"
        }
    })

    /** 组网状态机回调（A3c）：offer→WifiJoiner 接入；传输就绪；中止收敛（置空机器 + 停轮询）。 */
    private val netCallbacks = object : NetworkingStateMachine.Callbacks {
        override fun onOfferReceived(ssid: String, pwd: String?) {
            DiagLogger.log(TAG, "收到对端 offer：ssid=$ssid pwdLen=${pwd?.length ?: 0}，WifiJoiner 接入")
            pendingJoinSsid = ssid
            pendingJoinPwd = pwd ?: ""
            pendingJoinCallbacks = wifiJoinCallbacks
            wifiJoiner.join(ssid, pwd ?: "", wifiJoinCallbacks)
        }

        override fun onTransportReady(peerIp: String) {
            // T3：传输就绪（peerIp 可为占位 ""）→ 记录对端 IP + 自动启动 LocalSend 服务（alias=Build.MODEL），
            // 对端即可经 53317 发送文件到本机；同时扫描 filesDir/localsend 初始化接收列表
            DiagLogger.log(TAG, "组网传输就绪 peerIp=${peerIp.ifEmpty { "<空>" }}")
            onTransportReadyInternal(peerIp)
        }

        override fun onAbort(reason: String) {
            DiagLogger.log(TAG, "组网中止: $reason")
            netStateMachine = null // 允许再次「组建临时局域网」（下次 start 新建机器）
            mainHandler.removeCallbacks(netPoller)
            // ③ L2 本地热点收尾预留（B4 正式收尾前）：中止/结束时释放 LocalOnlyHotspotReservation
            // 并清理待收敛的 L2 pending（幂等；无 L2 时为 no-op，不影响 ①②④）
            hotspotManager.stopLocalOnly()
            // ② Binder 直呼（v0.3.4）收尾：登记框打开期间被中止（如 15s 步骤超时）→ 释放待收敛的
            // Binder 结果与异步闸（幂等），并关闭系统热点登记框（区别于 ④ manualPwdDialog 保留语义）
            hotspotManager.stopBinderTetherPending()
            if (ui.systemHotspotPwdMode) {
                ui.systemHotspotPwdMode = false
                ui.manualPwdDialog = false
            }
            ui.localOnlyPwdDialog = false
            // T3：组网中止/停止时停止 LocalSend 服务（已收文件保留在磁盘）、停接收轮询并清接收态；
            // 对端 IP 复位（发送入口随之回到「组网就绪后可发送」）
            mainHandler.removeCallbacks(receivePoller)
            localsendServer.stop()
            if (ui.transferState?.startsWith("接收中") == true) ui.transferState = null
            transportPeerIp = ""
            ui.netActive = false
            ui.netState = "组网已中止：$reason"
        }
    }

    /**
     * TRANSPORT 就绪收敛（v0.4.4）：状态机回调 [netCallbacks.onTransportReady] 与接管路径
     * [handlePeerAck] 共用——记录对端 IPv4（transportPeerIp，发送目标）→ 自动启动
     * [LocalSendServer]（alias=Build.MODEL，对端即可经 53317 发送文件到本机）→ 启动接收进度轮询。
     * 幂等：服务已运行时不再重复 start。
     */
    private fun onTransportReadyInternal(peerIp: String) {
        transportPeerIp = peerIp
        if (!localsendServer.isRunning) {
            val ok = localsendServer.start()
            DiagLogger.log(TAG, "T3 LocalSend 服务自动启动：ok=$ok alias=${Build.MODEL}")
        }
        mainHandler.removeCallbacks(receivePoller)
        mainHandler.post(receivePoller)
    }

    /** 组网状态机（按选中对端握手能力 + 本机能力仲裁后创建；结束后置 null）。 */
    private var netStateMachine: NetworkingStateMachine? = null

    /** 接入失败/重试的目标 SSID（最近一次 offer 携带）。 */
    private var pendingJoinSsid: String? = null

    /** 待重试 join 的密码（与 [pendingJoinSsid] 同源，权限授权成功后自动重试用）。 */
    private var pendingJoinPwd: String = ""

    /** 待重试 join 的结果回调（状态机路径 [wifiJoinCallbacks] / 接管路径 [peerOfferJoinCallbacks]）。 */
    private var pendingJoinCallbacks: WifiJoiner.Callbacks? = null

    /** 待重试 join 缺失的运行时权限（onNeedPermission 记录，授权成功后自动重试 join）。 */
    private var pendingJoinPermission: String? = null

    /** ②/③ 前置缺失的运行时权限（onNeedNearbyPermission 记录；授权成功后自动重试热点——③ L2 本地热点 v0.3.9-verify ③-② 同链）。 */
    private var pendingHotspotPermission: String? = null

    /** 引擎接管 offer 去重（Bluelink 组网补丁）：一次会话内最多接管一次；WifiJoiner.join 幂等兜底重复 offer。 */
    private var peerOfferHandled = false

    /** 接管路径：offer 携带的对端（A 端）热点 IPv4（v0.4.4；从机 ack 后作为 transportPeerIp，
     *  与状态机 offerPeerIp 对齐；offer 未带 ip 字段则为空串，由上层按占位处理）。 */
    @Volatile
    private var takeoverPeerIp: String = ""

    /** 接管路径 ack 超时（v0.4.4，120s 对齐状态机 JOINED 等 ack 的 MANUAL_TIMEOUT_MS）：发 joined 后
     *  热点方 ack 未到（已中止/通道异常）→ 明确提示，不无限挂起。 */
    private val takeoverAckTimeoutRunnable = Runnable { onTakeoverAckTimeout() }

    /**
     * 组网阶段轮询：状态机不暴露状态变化回调（仅 currentState getter），
     * 由 engine 以固定间隔读取并映射到 [BluelinkUiState.netState] / netActive。
     * 终态（TRANSPORT/TEARDOWN）后停止轮询；机器置空（onAbort）时自停。
     */
    private val netPoller = object : Runnable {
        override fun run() {
            // 信令自测（验证包）：500ms 轮询同步最新状态（主来源为 SignalTest 事件回调，此处兑底刷新；
            // 组网不活动时轮询自停，由回调继续驱动）
            ui.signalTestStatus = signalTest.status()
            ui.signalTestRunning = signalTest.isRunning
            val m = netStateMachine ?: run {
                ui.netActive = false
                mainHandler.removeCallbacks(this)
                return
            }
            val s = m.currentState
            // Bluelink 组网补丁：engine 已接管 offer 时保留接管流程的 UI 文案，
            // 不被状态机阶段轮询覆盖（状态机本身不在等 offer，其阶段文本无意义）
            if (!peerOfferHandled) {
                ui.netState = netStateText(s)
            }
            ui.netActive = s != NetState.IDLE && s != NetState.TEARDOWN
            if (s == NetState.TRANSPORT || s == NetState.TEARDOWN) {
                mainHandler.removeCallbacks(this) // 终态：停止轮询（TRANSPORT 保留机器供「结束组网」）
                return
            }
            mainHandler.postDelayed(this, NET_POLL_INTERVAL_MS)
        }
    }

    init {
        instance = this // A5：供详情弹层（MainScreen.kt）读取 engine/ui，保持 BluelinkRoot 零改动
        // 持久信令会话（A2）：engine 作为唯一接线点，把 GattClient/GattServer 的信令与断线
        // 回调统一转发给 SessionManager；SessionManager 上抛的信令（onRemoteSignal）落库到 ui。
        // lateinit 说明：gattClient/gattServer 回调 lambda 仅在运行期触发，此时 sessionManager 已赋值。
        sessionManager = SessionManager(appContext, gattClient, gattServer)
        // 信令自测（验证包）：绑定 SessionManager + 主线程定时器；状态变化（start/每条 send/pong/stop）
        // 经回调实时同步 ui.signalTestStatus（netPoller 另有 500ms 兜底刷新）
        signalTest = SignalTest(sessionManager, mainHandler) { running, status ->
            ui.signalTestRunning = running
            ui.signalTestStatus = status
        }
        sessionManager.setCallbacks(object : SessionManager.Callbacks {
            override fun onRemoteSignal(peerAddress: String, msg: SignalMessage) {
                DiagLogger.log(TAG, "引擎上抛会话信令: $peerAddress type=${msg.type}")
                // 信令自测（验证包）：ping/pong 仅用于测通道（ping 回 pong、pong 统计），不进状态机
                if (msg.type == SignalProtocol.TYPE_PING || msg.type == SignalProtocol.TYPE_PONG) {
                    signalTest.onRemoteSignal(msg)
                    return
                }
                ui.lastSignal = peerAddress to msg
                // A5：转发组网状态机（A3c 按 type 分发 offer/joined/ack/abort；
                // offer 经状态机回调 onOfferReceived 驱动 WifiJoiner 接入）。
                // Bluelink 组网补丁：offer 优先给状态机（仅当其处于等 offer 的 NEGOTIATING 态
                // 才会真正消费）；否则（状态机 null / 非等 offer 态——对端从未触发组网的实锤
                // 场景）由 engine 直接接管：WifiJoiner 接入 + 回报 joined，避免 offer 被忽略、
                // 无人 join、热点方等 joined 超时。
                val m = netStateMachine
                val machineWaitingOffer = m != null && m.currentState == NetState.NEGOTIATING
                if (msg.type == SignalProtocol.TYPE_OFFER && !machineWaitingOffer) {
                    val payload = msg.payload
                    // v0.4.4：接管路径同步捕获 offer 载荷 ip（A 端热点 IP，从机 ack 后作为
                    // transportPeerIp；与状态机 onOffer 记录 offerPeerIp 对齐）
                    handlePeerOffer(
                        ssid = payload?.optString("ssid", "") ?: "",
                        pwd = payload?.optString("pwd", "") ?: "",
                        ip = payload?.optString("ip", "") ?: "",
                    )
                } else if (msg.type == SignalProtocol.TYPE_ACK && peerOfferHandled) {
                    // v0.4.4：接管路径从机 ack 消费——offer 被 engine 接管（状态机 null / 非等 offer 态）时
                    // 状态机不会进入 JOINED，onAck 无法触发（真机实锤：B 收 ack 仅 ping/pong、无状态转移）
                    // → engine 直接完成 ack→TRANSPORT（启动 LocalSend 服务），与状态机 JOINED→TRANSPORT 对齐
                    handlePeerAck()
                } else {
                    netStateMachine?.onRemoteSignal(msg)
                }
            }
        })
        // 握手期拒连（地址无关）：把 ui.handshaking 实时状态透传给 GattServer，
        // 握手进行中（发起置 true / 完成或失败置 false 由现有逻辑维护）对端新连接一律掐断。
        // ui 声明在 gattServer 之后，故在此 init（ui 已初始化）注册，lambda 每次查询实时值。
        gattServer.setHandshakingProvider { ui.handshaking }
        // v0.4.5：LocalSend 服务文件完整落盘（worker 线程）→ 转存用户目录或提示选择保存位置。
        // Server 回调语义 = 文件已入**暂存** filesDir/localsend/（防断连丢数据），待/已转存用户目录。
        localsendServer.onFileReceived = { _, fileName, path, mimeType ->
            handleFileReceived(fileName, path, mimeType)
        }
    }

    private val bleStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val state = intent?.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            mainHandler.post {
                ui.btEnabled = state == BluetoothAdapter.STATE_ON
                DiagLogger.log(TAG, "蓝牙状态变更: ${btStateName(state ?: BluetoothAdapter.ERROR)}")
                if (ui.btEnabled) startBleIfNeeded() else stopAllBle()
            }
        }
    }

    /** 应用启动：root 探测 + 网络采集 + 蓝牙状态监听 + 启动 BLE（权限已授予时）。 */
    fun onStart() {
        RootDetector.init()
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        ContextCompat.registerReceiver(
            appContext, bleStateReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
        refreshNetwork()
        ui.btEnabled = adapter?.isEnabled == true
        if (ui.btEnabled) startBleIfNeeded()
    }

    /** 权限请求结果回填。 */
    fun onPermissionsResult(granted: Boolean) {
        ui.permissionsGranted = granted
        DiagLogger.log(TAG, "权限请求结果 granted=$granted")
        if (granted) {
            ui.btEnabled = adapter?.isEnabled == true
            if (ui.btEnabled) startBleIfNeeded()
        } else {
            stopAllBle()
        }
    }

    /**
     * WifiJoiner 权限前置授权结果回填（BluelinkRoot 权限请求回调里，仅当本次请求包含
     * [BluelinkUiState.requestedPermission] 时调用）：
     * [BluelinkUiState.joinRetryNeeded] 置位（onNeedPermission 挂起过 join）且目标权限已授予 →
     * 自动重试挂起的 join（WifiJoiner.join 幂等，可安全重试）；未授予 → 保持挂起并提示。
     */
    fun onJoinPermissionResult() {
        // ②/③ 前置 NEARBY_WIFI_DEVICES（onNeedNearbyPermission → requestedPermission；③ L2 本地热点
        // v0.3.9-verify ③-② 同链）：授权结果回灌后先走热点自动重试分支（join 挂起与热点挂起互斥，分支独立处理）
        pendingHotspotPermission?.let { handleHotspotPermissionRetry(it) }

        val p = pendingJoinPermission ?: return
        if (!ui.joinRetryNeeded) return
        val granted = try {
            appContext.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            DiagLogger.log(TAG, "检查权限 $p 异常（按未授权处理）: $e")
            false
        }
        if (!granted) {
            // 用户拒绝：清 requestedPermission，下次 join（onNeedPermission）可再次触发系统授权弹窗；
            // 挂起状态由 joinRetryNeeded 保留（期间 BLE「去授权」请求不含该权限，不会被误判为 join 结果）
            ui.requestedPermission = null
            DiagLogger.log(TAG, "权限 $p 未授权（用户拒绝），join 保持挂起，可再次触发 join 重试授权")
            ui.netState = "接入需权限 $p（未授权），授权后会自动重试接入"
            return
        }
        ui.joinRetryNeeded = false
        ui.requestedPermission = null
        val callbacks = pendingJoinCallbacks
        val ssid = pendingJoinSsid
        if (callbacks == null || ssid.isNullOrBlank()) {
            DiagLogger.log(TAG, "权限 $p 已授权，但无挂起的 join（ssid=${ssid} callbacks=${callbacks != null}），忽略自动重试")
            pendingJoinPermission = null
            return
        }
        DiagLogger.log(TAG, "权限 $p 已授权，自动重试 join ssid=$ssid（join 幂等，安全重试）")
        ui.netState = "权限已授权，正在重新接入热点…"
        wifiJoiner.join(ssid, pendingJoinPwd, callbacks)
    }

    /**
     * ②/③ 前置 NEARBY_WIFI_DEVICES 授权结果回灌（onJoinPermissionResult 分支，BluelinkRoot 的
     * permissionLauncher 回调路由：请求包含 ui.requestedPermission 时进入）——触发方：② public
     * startTethering 前置 / ③ L2 本地热点（v0.3.9-verify ③-②）调 startLocalOnlyHotspot 前前置：
     * - 已授予 → 自动重试热点（覆盖 ②/③）：状态机仍 [NetState.HOTSPOT_STARTING] 时取消当前流程
     *   干净重跑（cancel → onAbort 停 L2/清理 pending → startNetworking 重协商，② 带权限执行、
     *   降级后重入 ③ 时前置已授权）；
     *   状态机已中止/未启动（授权期间 15s 步骤超时 abort / ③ 前置返回 AwaitingNearbyPermission 后
     *   降级 ④ 或中止）→ 孤儿兜底 startNetworking 直接重跑；
     * - 未授予（用户拒绝）→ 清 requestedPermission 保持待重试（下次 ②/③ 路径再次触发
     *   onNeedNearbyPermission 可再弹授权）。
     */
    private fun handleHotspotPermissionRetry(permission: String) {
        if (permission != Manifest.permission.NEARBY_WIFI_DEVICES) return
        val granted = try {
            appContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            DiagLogger.log(TAG, "检查权限 $permission 异常（按未授权处理）: $e")
            false
        }
        if (!granted) {
            pendingHotspotPermission = null
            ui.requestedPermission = null
            DiagLogger.log(TAG, "NEARBY_WIFI_DEVICES 未授权（用户拒绝）：清 requestedPermission 保持待重试（下次 ②/③ 路径可再触发授权）")
            ui.netState = "开启热点需「附近的设备」权限（NEARBY_WIFI_DEVICES），未授权时将降级 ③/④"
            return
        }
        pendingHotspotPermission = null
        ui.requestedPermission = null
        DiagLogger.log(TAG, "NEARBY_WIFI_DEVICES 已授权：自动重试热点（② public startTethering / ③ L2 本地热点现可执行）")
        val m = netStateMachine
        if (m == null || m.currentState != NetState.HOTSPOT_STARTING) {
            // 状态机不活动（授权期间 15s 步骤超时 abort / 未启动）：孤儿兜底——直接重跑组网
            DiagLogger.log(
                TAG,
                "NEARBY_WIFI_DEVICES 已授权但状态机不活动（machine=${m != null} state=${m?.currentState}），补 startNetworking() 重跑 ②/③",
            )
            startNetworking()
            return
        }
        // 状态机仍 HOTSPOT_STARTING（本次尝试已降级 ③/④）：取消当前流程并干净重跑（②/③ 均安全）
        DiagLogger.log(TAG, "NEARBY_WIFI_DEVICES 已授权且状态机仍 HOTSPOT_STARTING：取消当前流程重跑 ②/③（clean restart）")
        netStateMachine?.cancel()
        startNetworking()
    }

    /** 顶部广播开关。 */
    fun setAdvertisingWanted(wanted: Boolean) {
        ui.advertisingWanted = wanted
        DiagLogger.log(TAG, "用户设置广播/扫描开关 wanted=$wanted")
        if (wanted && ui.permissionsGranted && ui.btEnabled) {
            startBleIfNeeded()
        } else if (!wanted) {
            stopAllBle()
        }
    }

    /** 刷新本机网络摘要，并重算所有已握手设备的同网判定。 */
    fun refreshNetwork() {
        ui.localNetwork = NetworkInfoProvider.collect(appContext)
        refreshAllLanStatus()
    }

    /** 点击设备：先展示弹层；无握手时发起 GATT 客户端握手。 */
    fun openDevice(entry: DeviceEntry) {
        ui.selectedDevice = entry
        updateNetBtnVisibility() // A5：按选中设备握手/异网情况刷新「组建临时局域网」入口
        if (entry.handshake != null) return // 已有握手，直接展示详情
        val a = adapter
        if (a == null || !a.isEnabled || !ui.permissionsGranted) return
        val device = try {
            a.getRemoteDevice(entry.address)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "非法 MAC: ${entry.address}")
            DiagLogger.log(TAG, "非法 MAC: ${entry.address}")
            return
        }
        ui.handshaking = true
        ui.handshakeError = null
        DiagLogger.log(TAG, "点设备发起握手 ${device.address}")
        // 新握手前先释放既有持久会话（若附着）：GattClient 单连接槽被会话连接占用时，
        // connect 会以"会话忙"拒绝，故必须先 detach 恢复原 cleanup 再发起新握手
        if (sessionManager.isAttached) {
            DiagLogger.log(TAG, "发起新握手 ${device.address}：先 detach 当前会话 ${sessionManager.currentPeer()}")
            sessionManager.detach()
            signalTest.stop() // 信令自测随会话结束停止（防定时器泄漏）
        }
        // 握手连接仲裁：若本机 Server 已与该设备建立反连接，先断开对端反连，
        // 避免同一设备 Client→对端 + 对端 Client→本机 Server 双 GATT 连接并发（蓝牙栈写入挂起根因）；
        // 断开后延迟 200ms 等栈稳定再发起 Client 连接。
        if (gattServer.isDeviceConnected(device.address)) {
            DiagLogger.log(TAG, "为避免双连接，已断开对端反连 ${device.address}")
            gattServer.disconnectDevice(device.address)
            mainHandler.postDelayed({ gattClient.connect(device) }, 200L)
        } else {
            gattClient.connect(device)
        }
    }

    fun dismissSheet() {
        ui.selectedDevice = null
        updateNetBtnVisibility()
    }

    // ============ 信令自测（验证包） ============

    /** 手动启动信令自测（attach 后已自动开始；此入口供 UI「信令自测」按钮手动重跑/补跑）。 */
    fun startSignalTest() {
        if (!sessionManager.isAttached) {
            DiagLogger.log(TAG, "信令自测：当前无持久会话（未 attach），忽略手动启动")
            ui.signalTestStatus = "信令测试: 未附着会话（握手后自动开始）"
            return
        }
        signalTest.start()
    }

    /** 手动停止信令自测。 */
    fun stopSignalTest() {
        signalTest.stop()
    }

    // ============ ③ LocalOnly 自测（v0.3.9 独立入口，不经过组网/状态机） ============

    /** ③ LocalOnly 自测：onStarted 后持有的 LocalOnlyHotspotReservation（[closeLocalOnlySelfTest] 时 close 释放）。 */
    private var loTestReservation: WifiManager.LocalOnlyHotspotReservation? = null

    /** ③ LocalOnly 自测：onStarted 取得的 SSID（去引号；密码登记完成后文案拼接用）。 */
    private var loTestSsid: String? = null

    /** ③ LocalOnly 自测：用户主动关闭标记（[closeLocalOnlySelfTest] 置位；系统 onStopped 到来时保留「已关闭」文案，不被「状态复位」覆盖）。 */
    private var loTestClosedByUser = false

    /**
     * ③ LocalOnly 自测（v0.3.9）：独立单环节验证入口——不经过组网/状态机，直接驱动
     * `WifiManager.startLocalOnlyHotspot(callback, mainHandler)`，专测 A15/sdk35 上 ③ 的行为
     * （热点是否自动开、系统弹窗/密码回填、reservation close）；app 生命周期外随时可点，不自动进 ④。
     *
     * 与 [HotspotManager] ③ 真路径（B3）同源同 API，但独立实现（不注入 listener、不接线状态机、
     * 不影响正式组网的 reservation），onStarted 统一先试读（v0.3.9.2 起 26-33 全走同一逻辑）：
     * - 读成功（无论 sdk，含 29-32 推翻盲区假设）→ 「密码自动读取成功（长度=N）」（不回显明文）；
     * - 读空：sdk 33+（A15/sdk35 主测目标）弹密码登记框（复用 manualPwdInput 输入，提示按系统弹窗
     *   抄写）→ [confirmLocalOnlySelfTestPwd] → 完成标记 + 「密码已登记」；
     *   sdk 29-32 → 「盲区（实测确认）」（10-12 盲区假设成立，落 ④）；
     *   sdk 26-28 → 「系统未下发密码（缺失）」（语义与现状一致）。
     * onFailed(reason) → 失败原因（1/2/3/4 字面量映射）；onStopped → 状态复位。
     * 结果写入 [BluelinkUiState.localOnlyTestInfo] / [BluelinkUiState.localOnlyTestRunning]。
     */
    @Suppress("DEPRECATION") // startLocalOnlyHotspot(callback, handler) 自 API 33 起弃用（改无 handler 重载），26+ 统一走此重载
    fun localOnlySelfTest() {
        if (ui.localOnlyTestRunning) {
            DiagLogger.log(TAG, "LocalOnly 自测忽略：上一次自测仍在进行中（localOnlyTestRunning=true）")
            return
        }
        ui.localOnlyTestPasswordSet = false
        ui.loTestPwdDialog = false
        val wm = appContext.getSystemService(WifiManager::class.java)
        if (wm == null) {
            ui.localOnlyTestRunning = false
            ui.localOnlyTestInfo = "③ LocalOnly 自测失败：WifiManager 不可用"
            DiagLogger.log(TAG, "LocalOnly 自测失败：WifiManager 不可用（getSystemService 返回 null）")
            return
        }
        ui.localOnlyTestRunning = true
        ui.localOnlyTestInfo = "③ LocalOnly 自测：正在启动…（sdk=${Build.VERSION.SDK_INT}）"
        DiagLogger.log(
            TAG,
            "LocalOnly 自测：调用 startLocalOnlyHotspot(callback, mainHandler) sdk=${Build.VERSION.SDK_INT}",
        )
        try {
            wm.startLocalOnlyHotspot(loTestCallback, mainHandler)
        } catch (e: Exception) {
            // 不吞异常：记录 + 如实回显（如 SecurityException / UnsupportedOperationException）
            ui.localOnlyTestRunning = false
            ui.localOnlyTestInfo = "③ LocalOnly 自测启动异常：${e.javaClass.simpleName}: ${e.message}"
            DiagLogger.log(TAG, "LocalOnly 自测 startLocalOnlyHotspot 调用异常（不吞）: $e")
        }
    }

    /**
     * ③ LocalOnly 自测：关闭已开启的热点（reservation.close → 系统随后回调 onStopped → 状态复位）；
     * 状态「已关闭」；无 reservation 时幂等 no-op。
     */
    fun closeLocalOnlySelfTest() {
        val r = loTestReservation
        loTestReservation = null
        loTestClosedByUser = true // 系统 onStopped 到来时保留「已关闭」文案，不被「状态复位」覆盖
        ui.localOnlyTestRunning = false
        ui.loTestPwdDialog = false
        if (r != null) {
            try {
                r.close()
                ui.localOnlyTestInfo = "③ LocalOnly 已关闭"
                DiagLogger.log(TAG, "LocalOnly 自测：已 close LocalOnlyHotspotReservation")
            } catch (e: Exception) {
                ui.localOnlyTestInfo = "③ LocalOnly 关闭异常：${e.javaClass.simpleName}: ${e.message}"
                DiagLogger.log(TAG, "LocalOnly 自测 close 异常（不吞）: $e")
            }
        } else {
            ui.localOnlyTestInfo = "③ LocalOnly 已关闭（无 reservation，幂等）"
            DiagLogger.log(TAG, "LocalOnly 自测：无持有中的 reservation（幂等 no-op）")
        }
    }

    /** ③ LocalOnly 自测（sdk 33+）密码登记框确认：用户按系统弹窗抄写回填 → 完成标记 + 「密码已登记」（只记长度，不回显明文）。 */
    fun confirmLocalOnlySelfTestPwd() {
        val pwd = ui.manualPwdInput.trim()
        if (pwd.isBlank()) {
            ui.localOnlyTestInfo = "③ LocalOnly：密码为空，请按系统弹窗抄写后确认"
            DiagLogger.log(TAG, "LocalOnly 自测：密码为空，保持登记框等待回填")
            return
        }
        ui.localOnlyTestPasswordSet = true
        ui.loTestPwdDialog = false
        ui.localOnlyTestInfo = "③ LocalOnly 已开：SSID=${loTestSsid ?: "未知"}；密码已登记（长度=${pwd.length}）"
        DiagLogger.log(TAG, "LocalOnly 自测（sdk 33+）密码登记完成：pwdLen=${pwd.length}（密码不回显）")
    }

    /**
     * ③ LocalOnly 自测系统回调（startLocalOnlyHotspot 结果；经 mainHandler 主线程）：
     * onStarted → 统一先试读 preSharedKey（26-33 全走同一逻辑：读成功 → 「密码自动读取成功（长度=N）」；
     * 读空 → 33+ 密码登记框 / 29-32「盲区（实测确认）」/ 26-28「系统未下发密码（缺失）」）；
     * onFailed → 失败原因（reason 字面量映射）；onStopped → 状态复位。密码全程不回显。
     */
    @Suppress("DEPRECATION") // LocalOnlyHotspotCallback 与 startLocalOnlyHotspot 同源弃用（API 33+），26+ 唯一公开路径
    private val loTestCallback = object : WifiManager.LocalOnlyHotspotCallback() {
        override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
            handleLoTestStarted(reservation)
        }

        override fun onFailed(reason: Int) {
            handleLoTestFailed(reason)
        }

        override fun onStopped() {
            handleLoTestStopped()
        }
    }

    /** ③ LocalOnly 自测 onStarted（主线程）：持有 reservation → 统一先试读 preSharedKey（26-33 全走
     *  同一逻辑，v0.3.9.2 与主路径同步）：读成功 → 「密码自动读取成功（长度=N）」；读空 → 33+ 登记框、
     *  29-32「盲区（实测确认）」、26-28「系统未下发密码（缺失）」。 */
    @Suppress("DEPRECATION") // reservation.wifiConfiguration 为 WifiConfiguration 旧 API（26+ 公开），软 AP 密码回传行为随版本分流
    private fun handleLoTestStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
        loTestReservation = reservation
        val sdk = Build.VERSION.SDK_INT
        val cfg = try {
            reservation.wifiConfiguration
        } catch (e: Exception) {
            DiagLogger.log(TAG, "LocalOnly 自测 onStarted 读 wifiConfiguration 异常: $e")
            null
        }
        val ssid = cfg?.SSID?.trim()?.removeSurrounding("\"") ?: ""
        loTestSsid = ssid
        val base = if (ssid.isBlank()) "③ LocalOnly 已开（SSID 缺失）" else "③ LocalOnly 已开：SSID=$ssid"
        ui.localOnlyTestInfo = base
        DiagLogger.log(TAG, "LocalOnly 自测 onStarted：ssid=${ssid.ifBlank { "<缺失>" }} sdk=$sdk（密码不回显）")
        // ★ v0.3.9.2：统一先试读 preSharedKey（26-33 全走同一逻辑，与 HotspotManager 主路径同步）——
        // 读成功（无论 sdk，含 29-32 推翻盲区假设）→ 「密码自动读取成功（长度=N）」；
        // 读空 → 33+ 登记框 / 29-32「盲区（实测确认）」/ 26-28「系统未下发密码（缺失）」
        val pwd = cfg?.preSharedKey?.trim()?.removeSurrounding("\"")?.takeIf { it.isNotBlank() }
        if (pwd != null) {
            ui.localOnlyTestInfo = "$base；密码自动读取成功（长度=${pwd.length}）"
            DiagLogger.log(
                TAG,
                "LocalOnly 自测 onStarted(sdk=$sdk)：密码自动读取成功（统一先试读）pwdLen=${pwd.length}（密码不回显）",
            )
            return
        }
        when {
            // sdk 33+（A15/sdk35 主测目标）：试读为空（软 AP 配置不回传密码；系统弹窗/通知展示 SSID
            // 与密码）→ 弹密码登记框（复用 manualPwdInput 输入），请用户按系统弹窗抄写回填
            sdk >= 33 -> {
                ui.localOnlyTestInfo = "$base；请按系统弹窗抄密码回填登记"
                ui.manualPwdInput = "" // 新流程清空上次输入
                ui.loTestPwdDialog = true
            }
            // sdk 29-32：试读为空 → 10-12 盲区假设成立（实测确认，A12/sdk31 定案）——显示盲区文案，落 ④（手动）
            sdk in 29..32 -> {
                ui.localOnlyTestInfo = "$base；盲区（实测确认）"
                DiagLogger.log(TAG, "LocalOnly 自测 onStarted(sdk=$sdk)：试读 preSharedKey 为空 → 盲区（实测确认，10-12 假设成立）")
            }
            // sdk 26-28：试读为空（系统未下发密码）→ 缺失提示（语义与现状一致）
            sdk in 26..28 -> {
                ui.localOnlyTestInfo = "$base；系统未下发密码（缺失）"
                DiagLogger.log(TAG, "LocalOnly 自测 onStarted(sdk=$sdk)：preSharedKey 缺失（系统未下发密码）")
            }
            // sdk < 26：startLocalOnlyHotspot 要求 API 26+（理论不可达，防御分支）
            else -> {
                ui.localOnlyTestInfo = "$base；sdk<26 不支持 LocalOnlyHotspot"
                DiagLogger.log(TAG, "LocalOnly 自测 onStarted(sdk=$sdk)：sdk<26 不支持（理论不可达）")
            }
        }
    }

    /** ③ LocalOnly 自测 onFailed（主线程）：系统 reason 字面量映射（1/2/3/4，与 HotspotManager.localOnlyErrorText 同表），失败状态复位。 */
    private fun handleLoTestFailed(reason: Int) {
        val text = loTestErrorText(reason)
        ui.localOnlyTestRunning = false
        ui.loTestPwdDialog = false
        ui.localOnlyTestInfo = "③ LocalOnly 启动失败（reason=$reason: $text）"
        DiagLogger.log(TAG, "LocalOnly 自测 onFailed(reason=$reason: $text)")
    }

    /** ③ LocalOnly 自测 onStopped（主线程）：状态复位（系统停止 / close 后触发）；用户主动关闭时保留「已关闭」文案。 */
    private fun handleLoTestStopped() {
        loTestReservation = null
        ui.localOnlyTestRunning = false
        ui.loTestPwdDialog = false
        if (loTestClosedByUser) {
            loTestClosedByUser = false
            DiagLogger.log(TAG, "LocalOnly 自测 onStopped（用户已主动关闭，保留「已关闭」文案）")
        } else {
            ui.localOnlyTestInfo = "③ LocalOnly 已停止（状态复位）"
            DiagLogger.log(TAG, "LocalOnly 自测 onStopped（系统停止，状态复位）")
        }
    }

    /** ③ LocalOnly 自测 onFailed reason → 字面量（AOSP 常量；compileSdk37 内置 jar 对 Kotlin 不可见，用字面量 1/2/3/4）。 */
    private fun loTestErrorText(reason: Int): String = when (reason) {
        1 -> "ERROR_GENERIC"
        2 -> "ERROR_NO_CHANNEL"
        3 -> "ERROR_INCOMPATIBLE_MODE"
        4 -> "ERROR_TETHERING_DISALLOWED"
        else -> "未知($reason)"
    }

    // ============ A5 组网动作（UI 入口） ============

    /** 「组建临时局域网」：按选中对端握手能力 + 本机能力仲裁，创建状态机并 start。 */
    fun startNetworking() {
        if (netStateMachine != null) {
            DiagLogger.log(TAG, "startNetworking 忽略：组网已在进程中")
            return
        }
        val entry = ui.selectedDevice ?: run {
            DiagLogger.log(TAG, "startNetworking：无选中设备")
            return
        }
        val hs = entry.handshake ?: run {
            DiagLogger.log(TAG, "startNetworking：对端尚未握手")
            return
        }
        ui.joinFailDialog = false
        ui.writeSettingsDialog = false
        ui.manualPwdDialog = false
        ui.systemHotspotPwdMode = false // ② Binder 直呼（v0.3.4）：新流程复位系统热点登记模式

        val mine = buildLocalCapability(
            isRoot = RootDetector.isRoot(),
            battery = readBattery(),
            sdkInt = Build.VERSION.SDK_INT,
        )
        // 一期握手未携带对端 sdkInt：privateApiCapable/localOnlyAvailable 按保守 false，
        // 对端 L1/L2 自动热点能力仅以 root + 电量参与仲裁（B 包可补握手字段）
        val peer = Capability(
            isRoot = hs.root,
            privateApiCapable = false,
            localOnlyAvailable = false,
            battery = hs.battery,
        )
        val decision = decide(mine, peer)
        DiagLogger.log(TAG, "组网仲裁：who=${decision.who} level=${decision.level} reason=${decision.reason}")

        netStateMachine = NetworkingStateMachine(
            session = sessionManager,
            hotspot = hotspotManager,
            arbiterResult = decision,
            callbacks = netCallbacks,
            handler = mainHandler,
            mineCapability = mine,
            peerCapability = peer,
            localNetwork = ui.localNetwork,
        )
        ui.netState = netStateText(NetState.NEGOTIATING)
        ui.netActive = true
        mainHandler.removeCallbacks(netPoller)
        mainHandler.post(netPoller)
        netStateMachine?.start()
    }

    /** 「结束组网」：取消状态机（发 abort → TEARDOWN → onAbort 收敛）。 */
    fun endNetworking() {
        ui.manualPwdDialog = false
        ui.joinFailDialog = false
        ui.writeSettingsDialog = false
        ui.systemHotspotPwdMode = false // ② Binder 直呼（v0.3.4）：结束组网复位系统热点登记模式
        netStateMachine?.cancel()
    }

    // ============ T3 LocalSend 传输（发送入口 / 取消 / 接收列表） ============

    /**
     * SAF 选文件回调（主线程，MainScreen OpenDocument launcher 触发）：读取文件名/大小 →
     * 记录待发文件并弹发送确认框（[BluelinkUiState.sendDialog]）；读取失败 → transferState 报错。
     * 文件内容不在此读取（发送时才懒打开流），也不回显。
     */
    fun onSendFilePicked(uri: Uri) {
        var name: String? = null
        var size: Long = -1L
        try {
            appContext.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null, null, null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0) name = c.getString(nameIdx)
                    if (sizeIdx >= 0 && !c.isNull(sizeIdx)) size = c.getLong(sizeIdx)
                }
            }
        } catch (e: Exception) {
            DiagLogger.log(TAG, "读取所选文件元数据异常（内容不回显）: ${e.javaClass.simpleName} ${e.message}")
            ui.transferState = "发送失败：无法读取文件信息（${e.javaClass.simpleName}）"
            return
        }
        if (name.isNullOrBlank() || size <= 0L) {
            DiagLogger.log(TAG, "SAF 文件元数据缺失/大小为 0：name=$name size=$size（不可发送）")
            ui.transferState = "发送失败：无法获取文件名或文件大小为 0"
            return
        }
        pendingSendUri = uri
        pendingSendName = name
        pendingSendSize = size
        ui.sendDialog = true
    }

    /**
     * 发送确认框「发送」（主线程）：构造 [SendFile]（input 懒打开流）→ 后台线程
     * [LocalSendClient.send]（peer=transportPeerIp, port=53317）；回调（进度/单文件完成/全部完成/
     * 失败/取消）写 [BluelinkUiState.transferState] + DiagLogger（只记文件名/元数据，文件内容不回显）。
     */
    fun confirmSend() {
        ui.sendDialog = false
        val uri = pendingSendUri ?: run {
            ui.transferState = "发送失败：文件选择已失效，请重新选择"
            return
        }
        val name = pendingSendName
        val size = pendingSendSize
        val peer = transportPeerIp
        if (name.isNullOrBlank() || size <= 0L) {
            ui.transferState = "发送失败：待发文件信息缺失"
            return
        }
        if (peer.isBlank()) {
            DiagLogger.log(TAG, "发送取消：transportPeerIp 为空（未处于 TRANSPORT）")
            ui.transferState = "发送失败：对端 IP 未知（请先完成组网）"
            return
        }
        val mimeType = try {
            appContext.contentResolver.getType(uri) ?: "application/octet-stream"
        } catch (e: Exception) {
            "application/octet-stream"
        }
        ui.transferState = "发送中 $name 0%"
        DiagLogger.log(
            TAG,
            "T3 发送开始：peer=$peer port=${Constants.DEFAULT_TCP_PROBE_PORT} name=$name size=${size}B mime=$mimeType",
        )
        val sendFile = SendFile(
            id = UUID.randomUUID().toString(),
            name = name,
            size = size,
            mimeType = mimeType,
            input = {
                appContext.contentResolver.openInputStream(uri)
                    ?: throw IOException("无法打开所选文件（openInputStream 返回 null）")
            },
        )
        val client = LocalSendClient(peer, Constants.DEFAULT_TCP_PROBE_PORT, Build.MODEL)
        activeSendClient = client
        var lastPct = -1
        client.onProgress = { _, fname, sent, total ->
            // 节流：仅百分比变化时更新 UI/日志（客户端每 64KB 分块回调过密）
            val pct = if (total > 0) (sent * 100 / total).toInt() else 0
            if (pct != lastPct) {
                lastPct = pct
                mainHandler.post {
                    ui.transferState = "发送中 $fname $pct%"
                    DiagLogger.log(TAG, "T3 发送进度：$fname $pct%（$sent/${total}B）")
                }
            }
        }
        client.onFileDone = { _, fname ->
            mainHandler.post { DiagLogger.log(TAG, "T3 单文件发送完成：$fname") }
        }
        client.onAllDone = { total ->
            mainHandler.post {
                ui.transferState = "发送完成：$name（${total}B）"
                DiagLogger.log(TAG, "T3 发送全部完成：$name total=${total}B")
                activeSendClient = null
            }
        }
        client.onError = { stage, msg ->
            mainHandler.post {
                ui.transferState = "发送失败：$msg"
                DiagLogger.log(TAG, "T3 发送失败：stage=$stage err=$msg")
                activeSendClient = null
            }
        }
        client.onCancelled = {
            mainHandler.post {
                ui.transferState = "发送已取消：$name"
                DiagLogger.log(TAG, "T3 发送已取消：$name")
                activeSendClient = null
            }
        }
        Thread({ client.send(listOf(sendFile)) }, "localsend-send")
            .apply { isDaemon = true }
            .start()
    }

    /** 发送确认框「取消」/dismiss：清除待发文件，不发送。 */
    fun dismissSendDialog() {
        ui.sendDialog = false
        pendingSendUri = null
        pendingSendName = null
        pendingSendSize = 0L
    }

    /** transferState 旁「取消」：取消进行中的发送（client.cancel() 中断写/读 + 尽力发 cancel API）。 */
    fun cancelSend() {
        DiagLogger.log(TAG, "T3 用户取消发送")
        activeSendClient?.cancel()
    }

    // ============ v0.4.5 接收侧：SAF 目录选择 / 暂存转存用户目录 ============

    /**
     * SAF OpenDocumentTree 目录选择回调（主线程，MainScreen launcher 触发）：记录 tree uri →
     * 持久化目录权限（[takePersistableUriPermission] 尽力；失败仅本次运行有效）→ 展示目录显示名 →
     * 补存排队中的暂存文件（后台线程逐个 DocumentFile 转存）。
     */
    fun onReceiveDirPicked(uri: Uri) {
        receiveDirUri = uri
        ui.receiveDirPrompt = false
        try {
            appContext.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            DiagLogger.log(TAG, "T3 接收目录权限已持久化: $uri")
        } catch (e: Exception) {
            DiagLogger.log(TAG, "T3 接收目录权限持久化失败（尽力，仅本次运行有效）: ${e.javaClass.simpleName} ${e.message}")
        }
        ui.receiveDirName = queryTreeDisplayName(uri) ?: "已选择目录"
        DiagLogger.log(TAG, "T3 接收保存目录已选定: ${ui.receiveDirName}")
        // 选定后再补存（未选期间入暂存的文件，多文件同一目录逐个转存）
        val pending = pendingStagedFiles.values.toList()
        pendingStagedFiles.clear()
        if (pending.isNotEmpty()) {
            DiagLogger.log(TAG, "T3 选定目录后补存暂存文件: ${pending.size} 个")
            Thread({
                for (f in pending) persistStagedFile(f, uri)
            }, "localsend-persist").apply { isDaemon = true }.start()
        }
    }

    /**
     * LocalSendServer.onFileReceived（worker 线程）：文件已完整落盘到**暂存目录** filesDir/localsend/
     * （防断连丢数据）→ 已选保存目录则立即后台转存；未选则排队并提示 UI 发起目录选择（选定后补存）。
     */
    private fun handleFileReceived(fileName: String, path: String, mimeType: String) {
        val uri = receiveDirUri
        if (uri != null) {
            Thread({ persistStagedFile(StagedFile(fileName, path, mimeType), uri) }, "localsend-persist")
                .apply { isDaemon = true }.start()
        } else {
            pendingStagedFiles[path] = StagedFile(fileName, path, mimeType)
            mainHandler.post {
                ui.transferState = "已收到「${truncateName(fileName)}」，请选择保存位置"
                ui.receiveDirPrompt = true
                DiagLogger.log(TAG, "T3 文件已入暂存（未选保存目录），提示选择: $fileName")
            }
        }
    }

    /**
     * 把暂存文件转存到用户 SAF 目录（后台线程）：[DocumentFile.fromTreeUri] → createFile(mimeType, fileName)
     * → 流式拷贝 → 成功后删除暂存原件；失败保留暂存（防断连丢数据）并提示。
     */
    private fun persistStagedFile(f: StagedFile, treeUri: Uri) {
        try {
            val dir = DocumentFile.fromTreeUri(appContext, treeUri)
            if (dir == null || !dir.canWrite()) throw IOException("目标目录不可写")
            val doc = dir.createFile(f.mimeType.ifBlank { "application/octet-stream" }, f.fileName)
                ?: throw IOException("createFile 返回 null")
            appContext.contentResolver.openOutputStream(doc.uri)?.use { out ->
                File(f.path).inputStream().use { it.copyTo(out, 64 * 1024) }
            } ?: throw IOException("openOutputStream 返回 null")
            File(f.path).delete() // 转存成功 → 删暂存原件
            mainHandler.post {
                ui.transferState = "已保存到 ${truncateName(dir.name ?: f.fileName)}"
                DiagLogger.log(TAG, "T3 文件已转存用户目录: ${f.fileName}")
            }
        } catch (e: Exception) {
            mainHandler.post {
                ui.transferState = "保存失败：${e.javaClass.simpleName}（暂存文件已保留，可重新选择目录后补存）"
            }
            DiagLogger.log(TAG, "T3 转存用户目录失败（暂存保留 ${f.path}）: ${f.fileName} ${e.javaClass.simpleName} ${e.message}")
        }
    }

    /** 查询 SAF tree uri 的目录显示名（尽力；失败回退 uri 末段解码）。 */
    private fun queryTreeDisplayName(uri: Uri): String? {
        val name = try {
            DocumentFile.fromTreeUri(appContext, uri)?.name?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
        return name ?: uri.lastPathSegment?.substringAfterLast(':')?.let { Uri.decode(it) }
    }

    /** 名称截断显示（超长省略号；transferState 展示用）。 */
    private fun truncateName(name: String, max: Int = 24): String =
        if (name.length > max) name.take(max - 1) + "…" else name

    /**
     * ④ 手动配网确认：登记密码 → 打开系统热点设置 → 回填状态机 onManualConfigured。
     *
     * 孤儿确认兜底：若状态机已 TEARDOWN / 为 null / 不活动（用户跳系统开热点+设密码期间
     * 状态机可能已超时中止并置空），自动补一次 [startNetworking]（其「忽略已在进程中」守卫
     * 保证不重复启动/不递归）后再回填 onManualConfigured(ssid,pwd)。
     */
    fun confirmManualPwd() {
        val pwd = ui.manualPwdInput.trim()
        val ssid = ui.manualSsidInput.trim().ifBlank { "Bluelink" }
        DiagLogger.log(TAG, "④ 手动配网确认：ssid=$ssid pwdLen=${pwd.length}")
        hotspotManager.setPassword(pwd) // 密码登记（App 不生成不指定）
        openHotspotSettings() // 打开系统热点设置（用户手动开启/保存热点）
        val m = netStateMachine
        val active = m != null &&
            m.currentState != NetState.IDLE &&
            m.currentState != NetState.TEARDOWN
        if (!active) {
            // 孤儿确认兜底：状态机不活动（已中止置空 / TEARDOWN / IDLE）→ 补一次 startNetworking
            DiagLogger.log(
                TAG,
                "④ 手动配网确认时状态机不活动（machine=${m != null} state=${m?.currentState}），补 startNetworking()",
            )
            startNetworking()
        }
        netStateMachine?.onManualConfigured(ssid, pwd.ifBlank { null })
        ui.manualPwdDialog = false
    }

    /**
     * ③ L2 本地热点（13+）密码登记框确认：用户按系统弹窗/通知回填密码 → 回填
     * [HotspotManager.completeLocalOnlyPassword] 完成成功结果（状态机 onLocalOnlyAsyncResult → offer）。
     */
    fun confirmLocalOnlyPwd() {
        val pwd = ui.manualPwdInput.trim()
        if (pwd.isBlank()) {
            DiagLogger.log(TAG, "③ L2 本地热点密码为空，保持登记框等待回填")
            ui.netState = "本地热点密码为空，请按系统弹窗回填"
            return
        }
        val ssid = ui.localOnlySsid
        DiagLogger.log(TAG, "③ L2 本地热点密码回填：ssid=$ssid pwdLen=${pwd.length}（密码不回显）")
        hotspotManager.completeLocalOnlyPassword(pwd)
        ui.localOnlyPwdDialog = false
    }

    /**
     * ② 系统预配热点（Binder 直呼成功）SSID+密码登记确认（登记框确认走此入口，区别于 ④ confirmManualPwd）：
     * 校验非空 → HotspotManager.completeSystemHotspotPassword 完成 ② 成功结果（状态机
     * onPrivateApiAsyncResult → offer）；登记框关闭并复位系统热点模式。
     */
    fun confirmSystemHotspotPwd() {
        val ssid = ui.manualSsidInput.trim()
        val pwd = ui.manualPwdInput.trim()
        if (ssid.isBlank() || pwd.isBlank()) {
            DiagLogger.log(TAG, "② 系统热点登记：SSID/密码为空，保持登记框等待回填")
            ui.netState = "系统热点 SSID/密码不能为空，请按本机热点信息填写"
            return
        }
        DiagLogger.log(TAG, "② 系统热点登记：ssid=$ssid pwdLen=${pwd.length}（密码不回显）")
        hotspotManager.completeSystemHotspotPassword(ssid, pwd)
        ui.manualPwdDialog = false
        ui.systemHotspotPwdMode = false
    }

    /** 接入失败对话框「重试接入」：用用户手填密码重新 join 最近一次 offer 的 SSID。 */
    fun retryJoin(pwd: String) {
        ui.joinFailDialog = false
        ui.writeSettingsDialog = false
        val ssid = pendingJoinSsid
        if (ssid.isNullOrBlank()) {
            ui.netState = "重试接入失败：无目标热点（offer 缺失）"
            return
        }
        DiagLogger.log(TAG, "重试接入热点 ssid=$ssid pwdLen=${pwd.length}")
        pendingJoinSsid = ssid
        pendingJoinPwd = pwd
        pendingJoinCallbacks = wifiJoinCallbacks
        wifiJoiner.join(ssid, pwd, wifiJoinCallbacks)
    }

    /**
     * Bluelink 组网补丁：对端收到 offer 但状态机未在等 offer（null / 非 NEGOTIATING 态）时的
     * engine 直接接管入口（由 onRemoteSignal 分发点调用）。
     *
     * 复用 [WifiJoiner.join]（幂等：已有进行中的接入时忽略重复 offer）：
     * - join 成功 → [peerOfferJoinCallbacks.onJoined] 直接发 joined{ip} 回报热点方
     *   （载荷与状态机 [NetworkingStateMachine] 的 joined 一致，热点方按既有 onJoined 收敛）；
     * - 失败 → joinFailDialog 手动输密码重试（复用 [retryJoin]）；Android 8–10 → WRITE_SETTINGS 授权引导。
     *
     * 去重：一次会话内 [peerOfferHandled] 置位后忽略后续 offer（避免重复 join / 重复弹窗），
     * 新会话（重新握手 attach）时重置。
     */
    private fun handlePeerOffer(ssid: String, pwd: String, ip: String = "") {
        val s = ssid.trim()
        if (s.isBlank()) {
            DiagLogger.log(TAG, "接管 offer：SSID 为空，忽略")
            return
        }
        if (peerOfferHandled) {
            DiagLogger.log(TAG, "接管 offer：本次会话已处理过（peerOfferHandled），忽略重复 offer ssid=$s")
            return
        }
        peerOfferHandled = true
        takeoverPeerIp = ip.trim() // v0.4.4：记录 offer 携带的 A 端热点 IP（ack 后作 transportPeerIp；未携带为空串）
        DiagLogger.log(
            TAG,
            "接管 offer：ssid=$s pwdLen=${pwd.length} ip=${takeoverPeerIp.ifEmpty { "<空>" }}（状态机未在等 offer，engine 直接驱动 WifiJoiner 接入）",
        )
        pendingJoinSsid = s
        pendingJoinPwd = pwd
        pendingJoinCallbacks = peerOfferJoinCallbacks
        ui.netState = "收到组网邀请，正在接入热点…"
        wifiJoiner.join(s, pwd, peerOfferJoinCallbacks)
    }

    /**
     * v0.4.4：接管路径从机收到 ack（热点方已确认传输就绪）→ 对齐状态机 onAck 的
     * JOINED→TRANSPORT→onTransportReady 语义，由 engine 直接完成 TRANSPORT 就绪：
     * 记录对端 IP（offer 携带的 A 端热点 IP [takeoverPeerIp]）→ [onTransportReadyInternal]
     * （启动 LocalSend 服务 + 初始化接收列表 + 启动接收轮询）→ 取消 ack 超时定时。
     */
    private fun handlePeerAck() {
        mainHandler.removeCallbacks(takeoverAckTimeoutRunnable)
        DiagLogger.log(
            TAG,
            "接管路径收到 ack：传输就绪 peerIp=${takeoverPeerIp.ifEmpty { "<空>" }}（对齐状态机 JOINED→TRANSPORT→onTransportReady）",
        )
        onTransportReadyInternal(takeoverPeerIp)
        ui.netState = "✅ 组网完成，传输就绪"
    }

    /** 接管路径 ack 超时（v0.4.4，120s）：热点方未确认传输就绪 → 明确提示（不中止已接入的热点 Wi-Fi）。 */
    private fun onTakeoverAckTimeout() {
        DiagLogger.log(
            TAG,
            "接管路径等 ack 超时（${TAKEOVER_ACK_TIMEOUT_MS / 1000}s）：热点方未确认传输就绪（可能已中止）",
        )
        ui.netState = "等待对方确认超时（${TAKEOVER_ACK_TIMEOUT_MS / 1000}s）：热点方未确认（可能已中止），传输未就绪"
    }

    /** 打开系统热点设置（④ 指引；ACTION_WIFI_SETTINGS 兜底，热点入口在 Wi-Fi 设置内）。 */
    fun openHotspotSettings() {
        try {
            appContext.startActivity(
                Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            DiagLogger.log(TAG, "打开热点设置失败: $e")
            ui.netState = "无法打开系统热点设置：${e.message}"
        }
    }

    /** 打开 WRITE_SETTINGS 授权页（Android 8–10 接入路径引导）。 */
    fun openWriteSettings() {
        ui.writeSettingsDialog = false
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:${appContext.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(intent)
        } catch (e: Exception) {
            ui.netState = "无法打开 WRITE_SETTINGS 授权页：${e.message}"
            DiagLogger.log(TAG, "打开 WRITE_SETTINGS 授权页失败: $e")
        }
    }

    /** 退出：停掉所有 BLE 并注销接收器（防泄漏）。 */
    fun release() {
        signalTest.stop() // 信令自测停止（防定时器泄漏）
        stopAllBle()
        try {
            appContext.unregisterReceiver(bleStateReceiver)
        } catch (e: Exception) {
            // 未注册时忽略
        }
        sessionManager.detach() // 原 gattClient.release() 收敛到 detach（内部恢复原 cleanup）
        if (instance === this) instance = null
    }

    // ---------- 内部 ----------

    private fun startBleIfNeeded() {
        if (!ui.permissionsGranted || !ui.btEnabled || !ui.advertisingWanted) return
        val a = adapter ?: return
        if (!a.isEnabled) return
        stopAllBle() // 幂等重启
        DiagLogger.log(TAG, "启动 BLE：广播/扫描/GATT Server")
        advertiser.start(a)
        scanner.start(a)
        bluetoothManager?.let { gattServer.start(it) }
        ui.scanning = true
    }

    private fun stopAllBle() {
        DiagLogger.log(TAG, "停止 BLE：广播/扫描/GATT Server/客户端")
        netStateMachine?.cancel() // A5：收尾/关停时取消组网（发 abort → 置空机器）
        // v0.4.4：接管路径收尾（幂等）——清 offer 热点 IP + 取消 ack 超时定时（无状态机可 cancel）
        takeoverPeerIp = ""
        mainHandler.removeCallbacks(takeoverAckTimeoutRunnable)
        // T3：停止/关停时停止 LocalSend 服务（服务端停止；已收文件保留）并停接收轮询、清接收态
        mainHandler.removeCallbacks(receivePoller)
        localsendServer.stop()
        if (ui.transferState?.startsWith("接收中") == true) ui.transferState = null
        transportPeerIp = ""
        // ③ L2 本地热点收尾预留（B4 正式收尾前释放入口；幂等；stopAllBle 覆盖 release() 收尾路径）
        hotspotManager.stopLocalOnly()
        // ② Binder 直呼（v0.3.4）收尾兜底：状态机为 null 但登记框仍悬挂时释放待收敛 Binder 结果（幂等）
        hotspotManager.stopBinderTetherPending()
        ui.systemHotspotPwdMode = false
        wifiJoiner.cancel() // 释放进行中的接入 / 残留 NetworkCallback
        advertiser.stop()
        scanner.stop()
        gattServer.stop()
        sessionManager.detach() // 会话结束：恢复原 cleanup（内部 gattClient.release()）
        signalTest.stop() // 信令自测随会话停止（防定时器泄漏）
        gattClient.release() // 静默中断进行中的握手（幂等）
        ui.advertising = false
        ui.scanning = false
    }

    private fun handleScanResult(result: android.bluetooth.le.ScanResult) {
        val device = result.device ?: return
        val addr = device.address ?: return
        if (addr == adapter?.address) return // 跳过本机
        val now = System.currentTimeMillis()
        val existing = ui.devices[addr]
        ui.devices[addr] = when {
            existing == null -> DeviceEntry(
                address = addr,
                rssi = result.rssi,
                firstSeen = now,
                lastSeen = now,
            )
            existing.rssi != result.rssi || now - existing.lastSeen > 5_000L ->
                existing.copy(rssi = result.rssi, lastSeen = now)
            else -> existing
        }
    }

    private fun applyRemoteHandshake(deviceAddress: String, handshake: HandshakeMessage) {
        val now = System.currentTimeMillis()
        val existing = ui.devices[deviceAddress]
        val entry = (existing ?: DeviceEntry(deviceAddress, 0, now, now))
            .copy(handshake = handshake, lastSeen = now)
        ui.devices[deviceAddress] = entry.copy(
            lanStatus = SameLanChecker.check(ui.localNetwork, handshake.net)
        )
        if (ui.selectedDevice?.address == deviceAddress) {
            ui.selectedDevice = ui.devices[deviceAddress]
            updateNetBtnVisibility() // A5：握手完成/刷新后重算「组建临时局域网」入口
        }
        // 持久信令会话：握手成功（Client 或 Server 任一通道）即 attach；
        // Client 侧由 keepAlive() 保留底层连接替代原硬 cleanup；Server 侧保留连接腿
        sessionManager.attach(deviceAddress)
        // Bluelink 组网补丁：新会话重置 offer 接管去重（一次会话一次接管）
        peerOfferHandled = false
        // v0.4.4：接管路径状态复位（offer 热点 IP 清空 + ack 超时定时取消，幂等）
        takeoverPeerIp = ""
        mainHandler.removeCallbacks(takeoverAckTimeoutRunnable)
        // 信令自测（验证包）：attach 成功后自动开始 120s 心跳收发（每 5s 一条 ping，对端回 pong）
        signalTest.start()
    }

    private fun refreshAllLanStatus() {
        ui.devices.keys.toList().forEach { addr ->
            val e = ui.devices[addr] ?: return@forEach
            val hs = e.handshake ?: return@forEach
            ui.devices[addr] = e.copy(lanStatus = SameLanChecker.check(ui.localNetwork, hs.net))
        }
        updateNetBtnVisibility()
    }

    // ---------- A5 内部：异网判定 / 阶段映射 / 工具 ----------

    /**
     * 异网判定（简单版，任务约定）：握手后本机与对方 net 比较——
     * wifi 不都为 true，或 ssid 不同，或 IP 网段前缀不同 → 异网；否则视为同网。
     */
    private fun isDifferentNet(local: NetworkSummary, remote: NetworkSummary): Boolean {
        if (!local.wifi || !remote.wifi) return true
        val ls = local.ssid?.trim()?.takeIf { it.isNotBlank() }
        val rs = remote.ssid?.trim()?.takeIf { it.isNotBlank() }
        if (ls != null && rs != null && ls != rs) return true
        val lp = local.ip?.substringBeforeLast('.')
        val rp = remote.ip?.substringBeforeLast('.')
        if (lp != null && rp != null && lp != rp) return true
        return false
    }

    /** 重算详情弹层「组建临时局域网」按钮可见性（异网且已握手才显示）。 */
    private fun updateNetBtnVisibility() {
        val entry = ui.selectedDevice ?: run {
            ui.netBtnVisible = false
            return
        }
        val hs = entry.handshake ?: run {
            ui.netBtnVisible = false
            return
        }
        ui.netBtnVisible = isDifferentNet(ui.localNetwork, hs.net)
    }

    /** 状态机阶段 → 展示文本（UI 最简文本式）。 */
    private fun netStateText(s: NetState): String = when (s) {
        NetState.IDLE -> "未开始"
        NetState.NEGOTIATING -> "组网协商中…"
        NetState.HOTSPOT_STARTING -> "热点启动中…（④ 手动配网等待回填）"
        NetState.OFFER_SENT -> "热点信息已发送，等待对方接入…"
        NetState.WAIT_JOIN -> "正在加入对方热点…"
        NetState.JOINED -> "已入网，等待对方确认…"
        NetState.TRANSPORT -> "✅ 组网完成，传输就绪"
        NetState.TEARDOWN -> "组网已中止"
    }

    /** 本机电量（0..100；异常/不可用为 null，仲裁按相等处理）。 */
    private fun readBattery(): Int? = try {
        val bm = appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
    } catch (e: Exception) {
        Log.w(TAG, "读取电量失败: $e")
        null
    }

    companion object {
        private const val TAG = "BluelinkEngine"

        /** 组网阶段轮询间隔。 */
        private const val NET_POLL_INTERVAL_MS = 500L

        /** T3 接收进度轮询间隔。 */
        private const val RECEIVE_POLL_INTERVAL_MS = 1000L

        /** 接管路径从机等 ack 超时：120s，对齐状态机 JOINED 等 ack（MANUAL_TIMEOUT_MS 同一值；v0.4.4）。 */
        private const val TAKEOVER_ACK_TIMEOUT_MS: Long = 120_000L

        /**
         * A5：当前引擎实例（MainActivity 创建，init 注册 / release 注销）。
         * 供详情弹层（MainScreen.kt 同包）读取 ui 并调用组网动作，保持 BluelinkRoot 零改动。
         */
        @Volatile
        private var instance: BluelinkEngine? = null

        fun current(): BluelinkEngine? = instance

        private fun btStateName(state: Int): String = when (state) {
            BluetoothAdapter.STATE_ON -> "开"
            BluetoothAdapter.STATE_OFF -> "关"
            BluetoothAdapter.STATE_TURNING_ON -> "正在开启"
            BluetoothAdapter.STATE_TURNING_OFF -> "正在关闭"
            else -> "未知($state)"
        }
    }
}
