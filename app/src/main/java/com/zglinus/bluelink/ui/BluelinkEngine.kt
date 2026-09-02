package com.zglinus.bluelink.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
import com.zglinus.bluelink.net.LanStatus
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
import com.zglinus.bluelink.networking.Who
import com.zglinus.bluelink.networking.buildLocalCapability
import com.zglinus.bluelink.networking.decide
import com.zglinus.bluelink.security.PinStore
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

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
 * v0.4.6 B4 温和收尾：传输完成后**不自动拆、不自动断**——发送完成（onAllDone）/ 接收侧全部转存完成
 * 置「传输完成 ✅」文案（热点保持/已接入，可继续发送/接收），状态卡按角色出「关闭热点」（热点方，
 * [closeHotspotAfterTransfer]）或「断开网络」（从机，[disconnectNetworkAfterTransfer]）由用户手动收尾：
 * 仅停热点/网络与 LocalSend 服务、组网状态回 IDLE，**不调用 stopAllBle / SessionManager.detach**——
 * BLE 会话/广播/扫描全程保留（可继续传输）。
 *
 * v0.4.7 A8 同网免热点直连：握手完成且 startNetworking 触发时先判同网（[sameLanForPeer]：双方
 * wifi=true、双方 ssid 非空且相等、[SameLanChecker.isSameLan] 子网一致）——同网 → **跳过仲裁/热点/
 * offer 全流程**，直接 [onTransportReadyInternal]（对端握手 net.ip）起 LocalSend 服务免热点
 * TRANSPORT（双方各自起 Server 互连，无协调冲突）；probeTcp(peerIp, 53317) 后台线程异步执行
 * （成功 → 确认「直连可达」；失败 → 仅提示「直连不可达（可能 AP 隔离）」不阻断——对端服务可能
 * 尚未监听，probe 容忍）；异网 → 现状（仲裁 + 热点逐级）不动；收尾沿用 v0.4.6 B4 温和收尾
 * （[endSameLanDirect] 同网直连收尾只停服务 + 复位，BLE 会话/广播/扫描保留）。
 *
 * v0.4.8 A6 Wi-Fi 变化监听：从机未走 Specifier（系统弹窗被忽略 / 用户手动连了热点）时——
 * 收 offer 后注册 [ConnectivityManager.NetworkCallback]（观察 TRANSPORT_WIFI，仅状态观察、
 * 不改变网络行为），当前 SSID（去引号）与 offer.ssid（trim）匹配 → 自动取 IP 回 joined
 * （复用「接入成功→发 joined」路径：状态机仍 WAIT_JOIN 走 onWifiJoined，否则按接管路径直发
 * joined）；监听仅 offer 会话内有效（收 offer 注册 / 会话 detach、组网中止、B4 收尾注销），
 * 防重标志（[wifiMonitorJoinedAlready]）忽略已接入过（joined 已发）的命中。
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
            logUiEvent(EVT_ERROR, "广播异常：$reason")
        }
    })

    private val scanner = BleScanner(mainHandler, object : BleScanner.Callbacks {
        override fun onScanResult(result: android.bluetooth.le.ScanResult) {
            handleScanResult(result)
        }

        override fun onScanFailed(reason: String) {
            ui.scanError = reason
            DiagLogger.log(TAG, "扫描失败回调 UI: $reason")
            logUiEvent(EVT_ERROR, "扫描异常：$reason")
        }
    })

    private val gattServer = GattServer(appContext, mainHandler, object : GattServer.Callbacks {
        override fun onRemoteHandshake(deviceAddress: String, handshake: HandshakeMessage) {
            DiagLogger.log(TAG, "Server 收到远端握手: $deviceAddress alias=${handshake.alias}")
            // v0.4.9 PIN 配对验证：Server 侧 = 对端主动连接本机 → 本端为被验证方（非发起方）
            iAmInitiator = false
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
            // v0.4.9 PIN 配对验证：Client 侧 = 本端主动连接对端 → 本端为发起方（openDevice 已置标记；自动重连保持）
            iAmInitiator = true
            applyRemoteHandshake(deviceAddress, handshake)
        }

        override fun onHandshakeFailed(deviceAddress: String, reason: String) {
            ui.handshaking = false
            ui.handshakeError = reason
            DiagLogger.log(TAG, "握手失败回调 UI: $deviceAddress reason=$reason")
            logUiEvent(EVT_ERROR, "握手失败：$reason（…${deviceAddress.takeLast(8)}）")
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

    // ============ v0.4.9 PIN 配对验证 ============

    /** PIN 配对存储（SharedPreferences）：模式 0=关 1=仅首次 2=每次；配对指纹表（仅首次模式记）。 */
    private val pinStore = PinStore(appContext)

    /** 本端是否握手发起方（openDevice 发起 GATT 连接置 true；收到对端连接为 false）：发起方生成配对码并比对，对端输入回传。 */
    @Volatile
    private var iAmInitiator = false

    /** 发起方本次生成的配对码（Int，不回显；对端输入串 toIntOrNull 比对）。 */
    @Volatile
    private var pendingPin: Int? = null

    /** 本次待验证对端指纹（deviceAddress；配对表以指纹记）。 */
    @Volatile
    private var pendingPinFingerprint: String? = null

    /** 发起方比对失败计数（3 次失败中止会话）。 */
    private var pinFailCount = 0

    // ============ T3 LocalSend 传输（发送/接收） ============

    /** LocalSend 服务（T1）：TRANSPORT 就绪后自动启动，供对端经 53317 发送文件到本机（alias=Build.MODEL）。 */
    val localsendServer = LocalSendServer(appContext, Build.MODEL)

    /** 传输就绪时记录的对端 IPv4（TRANSPORT 后发送目标；一期可能为占位 ""）。 */
    @Volatile
    internal var transportPeerIp: String = ""

    /** A8 同网直连（免热点）进行中标记：true=同网直连已 TRANSPORT（无状态机，startNetworking 直接进入）；收尾/停止时复位。 */
    @Volatile
    internal var sameLanDirectActive = false

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

    /** B4 温和收尾：接收侧转存进行中计数（SAF 转存后台线程；全部完成且无待转存/无活动接收会话时置「传输完成」态）。 */
    private val persistInFlight = AtomicInteger(0)

    // ============ A5 组网接线 ============

    /** 对端接入器（A4）：对端流程收到 offer 后接入对方热点，结果经 [wifiJoinCallbacks] 回灌。 */
    private val wifiJoiner = WifiJoiner(appContext)

    /** A6：ConnectivityManager（Wi-Fi 变化监听注册用；仅状态观察，不改变网络行为）。 */
    private val connectivityManager: ConnectivityManager?
        get() = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    /**
     * A6 Wi-Fi 变化监听（v0.4.8）：从机未走 Specifier（系统弹窗被忽略 / 用户手动连了热点）时，
     * Wi-Fi 连上后当前 SSID 与 offer.ssid 匹配 → 自动取 IP 回 joined（复用「接入成功→发 joined」路径）。
     * 生命周期：收到 offer（状态机 [netCallbacks.onOfferReceived] / 引擎接管 [handlePeerOffer]）后注册——
     * 「会话中且已收 offer」时启用；会话 detach（[stopAllBle]/新握手）或组网中止/收尾（onAbort/B4）时注销。
     * 防重：已通过 Specifier 接入过（joined 已发，[wifiMonitorJoinedAlready]）→ 忽略监听命中；
     * 同一 SSID 重复命中（onCapabilitiesChanged/onLinkPropertiesChanged 高频回调）由
     * [wifiMonitorHitPending] 幂等收敛；监听仅 offer 会话内有效（[monitorOfferSsid] + 会话 attach 双守卫），
     * 不干扰 A8 同网直连/热点流程（A8 无 offer，监听目标为空 → 全路径 no-op）。
     */
    private val wifiMonitorRequest = NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        .build()

    /** A6：监听目标 SSID（最近一次 offer 携带，trim 后；null=未收 offer/已收尾，监听 no-op）。 */
    @Volatile
    private var monitorOfferSsid: String? = null

    /** A6：NetworkCallback 注册状态（幂等注册/注销判定）。 */
    @Volatile
    private var wifiMonitorRegistered = false

    /** A6 防重：本次 offer 会话内 joined 已发（Specifier 接入成功 / 监听命中完成）→ 后续监听命中忽略。 */
    @Volatile
    private var wifiMonitorJoinedAlready = false

    /** A6：监听命中后取 IP 进行中（防同一 SSID 重复命中重复回 joined；完成/超时/注销复位）。 */
    @Volatile
    private var wifiMonitorHitPending = false

    /** A6：命中后延迟取 IP 的已重试次数（对齐 WifiJoiner「延迟取 IP」轮询语义）。 */
    private var wifiMonitorIpRetryCount = 0

    /** A6：命中后取 IP 的重试定时（短延迟重取；注销/完成时移除）。 */
    private val wifiMonitorIpRetryRunnable = object : Runnable {
        override fun run() {
            if (!wifiMonitorRegistered || !wifiMonitorHitPending) return
            val target = monitorOfferSsid ?: return
            val ip = wifiJoiner.fetchIpForSsid(target)
            if (ip != null) {
                completeAutoJoin(ip)
                return
            }
            if (wifiMonitorIpRetryCount >= WIFI_MONITOR_IP_RETRY_MAX) {
                DiagLogger.log(
                    TAG,
                    "A6 延迟取 IP 超时（${WIFI_MONITOR_IP_RETRY_MAX} 次，~${WIFI_MONITOR_IP_RETRY_MAX * WIFI_MONITOR_IP_RETRY_INTERVAL_MS / 1000}s）：未取到 IPv4，按空 IP 回 joined（对齐既有「延迟取 IP 超时按空 IP 上报」语义）",
                )
                completeAutoJoin("")
                return
            }
            wifiMonitorIpRetryCount++
            DiagLogger.log(
                TAG,
                "A6 命中后暂未取到 IPv4（DHCP/地址分配延迟），${WIFI_MONITOR_IP_RETRY_INTERVAL_MS}ms 后重取（第 $wifiMonitorIpRetryCount 次）",
            )
            mainHandler.postDelayed(this, WIFI_MONITOR_IP_RETRY_INTERVAL_MS)
        }
    }

    /** A6：Wi-Fi 变化监听回调（经 mainHandler 主线程；onAvailable/onCapabilitiesChanged/onLinkPropertiesChanged 取 SSID+IP）。 */
    private val wifiMonitorCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            maybeAutoJoinFromWifi(network)
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                maybeAutoJoinFromWifi(network)
            }
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            maybeAutoJoinFromWifi(network)
        }
    }

    /** WifiJoiner 结果回调：成功→状态机 onWifiJoined；失败→手动密码重试对话框；需 WRITE_SETTINGS 引导（8-10 前置 / 12+ 兜底）。 */
    private val wifiJoinCallbacks = object : WifiJoiner.Callbacks {
        override fun onJoined(ip: String) {
            DiagLogger.log(TAG, "WifiJoiner 接入成功 ip=${ip.ifEmpty { "<空>" }}，回灌状态机 onWifiJoined")
            wifiMonitorJoinedAlready = true // A6 防重：已通过 Specifier 接入（joined 已发）→ 监听命中忽略
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
            wifiMonitorJoinedAlready = true // A6 防重：已通过 Specifier 接入（joined 已发）→ 监听命中忽略
            sendJoinedAsPeer(ip)
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
            logUiEvent(EVT_NETWORK, "收到组网邀请（SSID=$ssid），接入热点…")
            // B4 温和收尾：收到 offer = 本机为从机（非热点方），传输完成后状态卡显示「断开网络」
            ui.hotspotSideAfterTransfer = false
            ui.pairingDialog = true // v0.5.4a：收到组网邀请（状态机路径）→ 配网弹窗显示接入阶段（防御性置位）
            pendingJoinSsid = ssid
            pendingJoinPwd = pwd ?: ""
            pendingJoinCallbacks = wifiJoinCallbacks
            startWifiJoinMonitor(ssid) // A6：会话中已收 offer → 启用 Wi-Fi 变化监听（弹窗被忽略/手动接入自动回 joined）
            wifiJoiner.join(ssid, pwd ?: "", wifiJoinCallbacks)
        }

        override fun onTransportReady(peerIp: String) {
            logUiEvent(EVT_NETWORK, "组网转移：传输就绪（peerIp=${peerIp.ifEmpty { "<空>" }}）")
            // T3：传输就绪（peerIp 可为占位 ""）→ 记录对端 IP + 自动启动 LocalSend 服务（alias=Build.MODEL），
            // 对端即可经 53317 发送文件到本机；同时扫描 filesDir/localsend 初始化接收列表
            DiagLogger.log(TAG, "组网传输就绪 peerIp=${peerIp.ifEmpty { "<空>" }}")
            onTransportReadyInternal(peerIp)
        }

        override fun onAbort(reason: String) {
            DiagLogger.log(TAG, "组网中止: $reason")
            logUiEvent(EVT_TEARDOWN, "组网已中止：$reason")
            netStateMachine = null // 允许再次「组建临时局域网」（下次 start 新建机器）
            mainHandler.removeCallbacks(netPoller)
            stopWifiJoinMonitor() // A6：组网中止 → offer 会话结束，注销 Wi-Fi 变化监听（幂等；监听仅 offer 会话内有效）
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
        // v0.5.4a：传输就绪（状态机 onTransportReady / 接管 ack / 同网直连共用收敛点）→ 自动关配网弹窗
        ui.pairingDialog = false
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
        // v0.4.9 PIN 配对验证：启动时同步模式与配对数量（PinStore 持久化）
        ui.pinMode = pinStore.getMode()
        ui.pairedCount = pinStore.pairedCount
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
                // v0.4.9 PIN 配对验证：pin 信令与 PIN 失败 abort 由 engine 直接消费（不进状态机，与 ping/pong 同层拦截）
                if (msg.type == SignalProtocol.TYPE_PIN) {
                    handlePinSignal(msg)
                    return
                }
                if (msg.type == SignalProtocol.TYPE_ABORT &&
                    (msg.payload?.optString("reason", "") ?: "").contains(PIN_ABORT_REASON)
                ) {
                    handlePinAbort()
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
        refreshSelfCard() // v0.5.0 UI-1：本机设备卡网络/电量随刷新更新
    }

    /** 点击设备：先展示弹层；无握手时发起 GATT 客户端握手。 */
    fun openDevice(entry: DeviceEntry) {
        ui.detailDevice = entry
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
        // v0.4.9 PIN 配对验证：本端主动发起握手 → 标记为发起方（握手完成时生成配对码）；复位上次会话 PIN 状态
        iAmInitiator = true
        resetPinVerifyState()
        // v0.5.4a：点设备发起握手 → 配网极简弹窗（配对完成/传输就绪自动关，失败保留错误态）
        ui.pairingDialog = true
        DiagLogger.log(TAG, "点设备发起握手 ${device.address}")
        // 新握手前先释放既有持久会话（若附着）：GattClient 单连接槽被会话连接占用时，
        // connect 会以"会话忙"拒绝，故必须先 detach 恢复原 cleanup 再发起新握手
        if (sessionManager.isAttached) {
            DiagLogger.log(TAG, "发起新握手 ${device.address}：先 detach 当前会话 ${sessionManager.currentPeer()}")
            sessionManager.detach()
            signalTest.stop() // 信令自测随会话结束停止（防定时器泄漏）
        }
        refreshPairedView() // v0.5.0 UI-1：旧会话 detach → 配对视图复位（新握手期间不显示对端卡）
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
        ui.detailDevice = null
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
        if (netStateMachine != null || sameLanDirectActive) {
            DiagLogger.log(TAG, "startNetworking 忽略：组网/同网直连已在进程中（machine=${netStateMachine != null} sameLanDirect=$sameLanDirectActive）")
            return
        }
        val entry = ui.detailDevice ?: run {
            DiagLogger.log(TAG, "startNetworking：无选中设备")
            return
        }
        val hs = entry.handshake ?: run {
            DiagLogger.log(TAG, "startNetworking：对端尚未握手")
            return
        }
        // v0.4.9 PIN 配对验证：组网/同网直连前置拦截——本会话未通过 PIN 验证且模式非关 → 提示先完成配对验证
        if (pinRequired()) {
            DiagLogger.log(
                TAG,
                "startNetworking 拦截：PIN 验证未通过（mode=${pinStore.getMode()} pinVerifyOk=${ui.pinVerifyOk}），提示先完成配对验证",
            )
            ui.netState = "请先完成 PIN 配对验证（对端输入展示的配对码）后再组建局域网"
            return
        }
        ui.joinFailDialog = false
        ui.writeSettingsDialog = false
        ui.manualPwdDialog = false
        ui.systemHotspotPwdMode = false // ② Binder 直呼（v0.3.4）：新流程复位系统热点登记模式
        // v0.5.4a：手动组网/同网直连 → 配网极简弹窗接管进度（同网直连与热点路径都经此后置位）
        ui.pairingDialog = true

        // A8 同网免热点直连：先刷新本机网络（用当前数据判同网；对端侧 net 取自握手时刻，握手后即固化、无需等 offer），
        // 同网 → 跳过仲裁/热点/offer 全流程直接 TRANSPORT；异网 → 走下方现状（仲裁 + 热点逐级）
        refreshNetwork()
        if (sameLanForPeer(hs.net)) {
            startSameLanDirect(hs)
            return
        }

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
        logUiEvent(EVT_NETWORK, "组网仲裁：who=${decision.who} level=${decision.level}（${decision.reason}）")
        // B4 温和收尾：角色标志——本机仲裁为热点方（who==ME）或手动④（who==null，本机手动开热点）置 true
        // （传输完成后状态卡显示「关闭热点」）；who==PEER 置 false（对端开热点，本机为从机）
        ui.hotspotSideAfterTransfer = decision.who == Who.ME || decision.who == null

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

    /**
     * 「结束组网」：同网直连（免热点）→ [endSameLanDirect]（无状态机）；异网/热点路径 → 取消状态机
     * （发 abort → TEARDOWN → onAbort 收敛）。
     */
    fun endNetworking() {
        ui.manualPwdDialog = false
        ui.joinFailDialog = false
        ui.writeSettingsDialog = false
        ui.systemHotspotPwdMode = false // ② Binder 直呼（v0.3.4）：结束组网复位系统热点登记模式
        if (sameLanDirectActive) {
            endSameLanDirect()
            return
        }
        netStateMachine?.cancel()
    }

    // ============ A8 同网免热点直连（握手后判定同网 → 直接 TRANSPORT，跳过仲裁/热点/offer） ============

    /**
     * A8 同网免热点直连（startNetworking 同网分支）：**跳过仲裁/热点/offer 全流程**——
     * 直接 [onTransportReadyInternal]（起 LocalSendServer + 接收轮询）进入 TRANSPORT，
     * peerIp=对端握手 net.ip（握手时刻采集，同网判定已过即固化，无需等 offer）；
     * probeTcp(peerIp, 53317) 后台线程异步执行：成功 → 确认「直连可达」；失败 → 仍进入传输
     * （同网判定已过，probe 仅提示「直连不可达（可能 AP 隔离）」不阻断——对端 LocalSend 服务
     * 可能尚未监听，probe 容忍：多次重试 + 失败不阻断）。
     * 边界：双方同时判同网同时直连 → 无协调冲突（各自起 Server，端口各自本机 53317，互连即可）。
     */
    private fun startSameLanDirect(hs: HandshakeMessage) {
        val peerIp = hs.net.ip?.trim()?.takeIf { it.isNotBlank() } ?: ""
        logUiEvent(EVT_NETWORK, "同网判定：免热点直连（对端 ${hs.alias.ifBlank { "<未知>" }}）")
        sameLanDirectActive = true
        ui.hotspotSideAfterTransfer = false
        ui.netActive = true
        onTransportReadyInternal(peerIp)
        ui.netState = "✅ 同网直连：传输就绪（免热点）"
        ui.transferState = "同网直连：传输就绪（免热点）"
        DiagLogger.log(
            TAG,
            "A8 同网直连：跳过仲裁/热点/offer 全流程，直接 TRANSPORT（免热点）peerIp=${peerIp.ifEmpty { "<空>" }}",
        )
        if (peerIp.isBlank()) {
            DiagLogger.log(TAG, "A8 同网直连：对端握手 net.ip 为空，跳过 probeTcp（无可探测地址）")
            return
        }
        // probe 异步（后台线程真实 TCP 探测，不阻塞主线程；失败不阻断传输）
        Thread({
            var reachable = false
            for (attempt in 1..SAME_LAN_PROBE_ATTEMPTS) {
                if (SameLanChecker.probeTcp(peerIp, Constants.DEFAULT_TCP_PROBE_PORT, SAME_LAN_PROBE_TIMEOUT_MS)) {
                    reachable = true
                    break
                }
                if (attempt < SAME_LAN_PROBE_ATTEMPTS) {
                    try {
                        Thread.sleep(SAME_LAN_PROBE_RETRY_DELAY_MS)
                    } catch (ie: InterruptedException) {
                        break
                    }
                }
            }
            val result = if (reachable) {
                "✅ 直连可达（probeTcp $peerIp:${Constants.DEFAULT_TCP_PROBE_PORT} 成功）"
            } else {
                "直连不可达（可能 AP 隔离或对端服务尚未监听，probe 仅提示不阻断）"
            }
            mainHandler.post {
                // 不覆盖进行中的发送/接收进度：仅当仍处于直连就绪文案时回写 probe 结果
                val cur = ui.transferState
                if (cur == null || cur.startsWith("同网直连：传输就绪")) {
                    ui.transferState = if (reachable) {
                        "同网直连：传输就绪（免热点）·直连可达"
                    } else {
                        "同网直连：传输就绪（免热点）·直连探测未通（可能 AP 隔离）"
                    }
                }
                DiagLogger.log(TAG, "A8 同网直连 probe 结果：$result")
            }
        }, "samelan-direct-probe").apply { isDaemon = true }.start()
    }

    /**
     * A8 同网直连温和收尾（对齐 v0.4.6 B4 温和收尾语义）：只停 LocalSend 服务与接收轮询、
     * 组网状态回 IDLE，**不调用 stopAllBle / SessionManager.detach**——BLE 会话/广播/扫描全程保留
     * （可继续传输）；无状态机（同网直连无仲裁/热点），等价于状态机路径 cancel → onAbort 的收敛。
     */
    fun endSameLanDirect() {
        DiagLogger.log(TAG, "A8 同网直连收尾（温和，对齐 B4）：停 LocalSend 服务 + 状态复位；BLE 会话/广播/扫描保留（不调 stopAllBle/detach）")
        logUiEvent(EVT_TEARDOWN, "已结束同网直连（BLE 保留）")
        mainHandler.removeCallbacks(receivePoller)
        localsendServer.stop()
        if (ui.transferState?.startsWith("接收中") == true) ui.transferState = null
        transportPeerIp = ""
        sameLanDirectActive = false
        ui.netActive = false
        ui.netState = null
        ui.hotspotSideAfterTransfer = false
        stopWifiJoinMonitor() // A6：同网直连收尾（防御；A8 无 offer 本应无监听，幂等 no-op）
        ui.transferState = "已关闭同网直连（BLE 通信保留）"
    }

    // ============ B4 温和收尾（传输完成后手动关闭热点 / 断开网络；BLE 全程保留） ============

    /**
     * B4 温和收尾（热点方）：传输完成后用户点「关闭热点」——只停热点与 LocalSend 服务、组网状态回 IDLE，
     * **不调用 stopAllBle / SessionManager.detach**：BLE 会话/广播/扫描全程保留（可继续传输）。
     * - ③ L2 本地热点：hotspotManager.stopLocalOnly()（close LocalOnlyHotspotReservation，幂等 no-op 安全）；
     * - ②' Binder 直呼系统热点：hotspotManager.stopBinderTether()（复用既有 k1/c stopTethering 关热点入口
     *   ——mdTetherDispatch 关分支；stopBinderTetherPending 仅为 pending 清理，不关热点）；
     *   ④ 手动配网为系统热点、App 无程序化关闭句柄（用户系统设置管理），本方法对其仅停 server + 状态复位；
     * - localsendServer.stop()（已收文件保留在磁盘）+ 组网状态回 IDLE（调用既有收尾方法
     *   netStateMachine.cancel() → TEARDOWN → onAbort 收敛：停服务/停轮询/清 IP/netActive=false；
     *   onAbort 不触 BLE，属温和路径）；
     * - 状态复位：transferState 置「已关闭热点，传输结束（BLE 通信保留）」、组网文案清空。
     */
    fun closeHotspotAfterTransfer() {
        DiagLogger.log(TAG, "B4 温和收尾：热点方点「关闭热点」——停热点 + 停 LocalSend 服务 + 组网回 IDLE；BLE 会话/广播/扫描保留（不调 stopAllBle/detach）")
        logUiEvent(EVT_TEARDOWN, "已关闭热点，传输结束（BLE 通信保留）")
        // ③ L2 本地热点：close reservation（幂等 no-op 安全）
        hotspotManager.stopLocalOnly()
        // ②' Binder 直呼系统热点：实际关热点入口（k1/c stopTethering 关分支，后台线程执行）
        hotspotManager.stopBinderTether()
        // 组网状态回 IDLE：调用既有收尾方法（cancel → TEARDOWN → onAbort，同步执行；onAbort 不触 BLE）
        netStateMachine?.cancel()
        netStateMachine = null
        mainHandler.removeCallbacks(netPoller)
        mainHandler.removeCallbacks(receivePoller)
        mainHandler.removeCallbacks(takeoverAckTimeoutRunnable) // 接管路径残留定时清理（幂等）
        stopWifiJoinMonitor() // A6：B4 收尾（热点方「关闭热点」）→ offer 会话结束，注销 Wi-Fi 变化监听（幂等）
        // 仅停 server + 状态复位（覆盖 onAbort 的「组网已中止」文案）
        localsendServer.stop()
        if (ui.transferState?.startsWith("接收中") == true) ui.transferState = null
        transportPeerIp = ""
        sameLanDirectActive = false // A8：同网直连标记复位（防御；同网直连不经热点路径）
        ui.netActive = false
        ui.netState = null
        ui.hotspotSideAfterTransfer = false
        ui.transferState = "已关闭热点，传输结束（BLE 通信保留）"
    }

    /**
     * B4 温和收尾（从机）：传输完成后用户点「断开网络」——断开 Specifier 网络 + 停 LocalSend 服务，
     * **不调用 stopAllBle / SessionManager.detach**：BLE 会话/广播/扫描全程保留（可继续传输）。
     * - 断开网络：wifiJoiner.cancel()（其既有断开/释放 API——注销成功路径保留的 NetworkCallback →
     *   系统断开 on-demand Specifier Wi-Fi；有进行中接入同样中止，幂等）；
     *   兜底（无既有 API 时）：ConnectivityManager.bindProcessToNetwork(null) + unregisterNetworkCallback
     *   ——已由 cancel() 内部 releaseRequest/unregisterNetworkCallback 覆盖，此处不再重复；
     * - localsendServer.stop()（已收文件保留在磁盘）+ 组网状态回 IDLE（netStateMachine?.cancel() 既有
     *   收尾；接管路径机器为 null 时仅停 server + 状态复位）。
     */
    fun disconnectNetworkAfterTransfer() {
        DiagLogger.log(TAG, "B4 温和收尾：从机点「断开网络」——断开 Specifier 网络 + 停 LocalSend 服务；BLE 会话/广播/扫描保留（不调 stopAllBle/detach）")
        logUiEvent(EVT_TEARDOWN, "已断开热点网络（BLE 保留）")
        // 从机断网：WifiJoiner.cancel()（注销保留的 NetworkCallback → 系统断开 on-demand 网络；幂等）
        wifiJoiner.cancel()
        // 组网状态回 IDLE：调用既有收尾方法（状态机路径 cancel → onAbort；接管路径机器为 null，仅停 server + 状态复位）
        netStateMachine?.cancel()
        netStateMachine = null
        mainHandler.removeCallbacks(netPoller)
        mainHandler.removeCallbacks(receivePoller)
        mainHandler.removeCallbacks(takeoverAckTimeoutRunnable)
        stopWifiJoinMonitor() // A6：B4 收尾（从机「断开网络」）→ offer 会话结束，注销 Wi-Fi 变化监听（幂等）
        localsendServer.stop()
        if (ui.transferState?.startsWith("接收中") == true) ui.transferState = null
        transportPeerIp = ""
        sameLanDirectActive = false // A8：同网直连标记复位（防御；同网直连不经从机路径）
        ui.netActive = false
        ui.netState = null
        ui.hotspotSideAfterTransfer = false
        ui.transferState = "已断开热点网络（BLE 保留）"
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
                logUiEvent(EVT_TRANSFER, "传输完成：$name（共 ${total}B）")
                // B4 温和收尾：发送全部完成 → 传输完成态（按角色区分文案；热点保持/已接入/同网直连可继续，
                // 或点「关闭热点」/「断开网络」/「结束直连」手动收尾；BLE 会话/广播/扫描全程保留，不自动拆）
                ui.transferState = when {
                    ui.hotspotSideAfterTransfer -> {
                        "传输完成 ✅（热点保持中，可继续发送；或点「关闭热点」结束）"
                    }

                    sameLanDirectActive -> {
                        "传输完成 ✅（同网直连保持中，可继续发送；或点「结束直连」结束）"
                    }

                    else -> {
                        "传输完成 ✅（已接入热点，可继续接收；或点「断开网络」结束）"
                    }
                }
                DiagLogger.log(TAG, "T3 发送全部完成：$name total=${total}B（B4 温和收尾：保留 BLE/热点，等待用户手动收尾）")
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
            persistInFlight.addAndGet(pending.size) // B4：补存计数（与 persistStagedFile 完成递减配对）
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
            persistInFlight.incrementAndGet() // B4：转存计数（与 persistStagedFile 完成递减配对，判定「全部转存完成」）
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
     * B4 温和收尾：每次转存完成（成功）且无进行中转存、无待转存、无活动接收会话 → 「传输完成 ✅」
     * （接收侧全部转存完成；保留 BLE 会话/广播/扫描，不自动断网/关热点，由用户点「断开网络」手动收尾）。
     */
    private fun persistStagedFile(f: StagedFile, treeUri: Uri) {
        var saved = false
        try {
            val dir = DocumentFile.fromTreeUri(appContext, treeUri)
            if (dir == null || !dir.canWrite()) throw IOException("目标目录不可写")
            val doc = dir.createFile(f.mimeType.ifBlank { "application/octet-stream" }, f.fileName)
                ?: throw IOException("createFile 返回 null")
            appContext.contentResolver.openOutputStream(doc.uri)?.use { out ->
                File(f.path).inputStream().use { it.copyTo(out, 64 * 1024) }
            } ?: throw IOException("openOutputStream 返回 null")
            File(f.path).delete() // 转存成功 → 删暂存原件
            saved = true
            mainHandler.post {
                ui.transferState = "已保存到 ${truncateName(dir.name ?: f.fileName)}"
                DiagLogger.log(TAG, "T3 文件已转存用户目录: ${f.fileName}")
                logUiEvent(EVT_TRANSFER, "已保存 ${f.fileName} 至用户目录")
            }
        } catch (e: Exception) {
            mainHandler.post {
                ui.transferState = "保存失败：${e.javaClass.simpleName}（暂存文件已保留，可重新选择目录后补存）"
            }
            DiagLogger.log(TAG, "T3 转存用户目录失败（暂存保留 ${f.path}）: ${f.fileName} ${e.javaClass.simpleName} ${e.message}")
        } finally {
            // B4 温和收尾：接收侧全部转存完成（无进行中转存、无待转存、无活动接收会话）→ 传输完成态；
            // 保留 BLE 会话/广播/扫描，不自动断网/关热点，由用户手动结束（失败不置完成态）。
            // 文案按角色区分：本机为热点方（含热点方接收对端文件）→ 「关闭热点」；从机 → 「断开网络」。
            if (persistInFlight.decrementAndGet() == 0 &&
                pendingStagedFiles.isEmpty() &&
                localsendServer.getActiveSessions().isEmpty()
            ) {
                mainHandler.post {
                    if (saved) {
                        ui.transferState = when {
                            ui.hotspotSideAfterTransfer -> {
                                "传输完成 ✅（热点保持中，可继续发送；或点「关闭热点」结束）"
                            }

                            sameLanDirectActive -> {
                                "传输完成 ✅（同网直连保持中，可继续接收；或点「结束直连」结束）"
                            }

                            else -> {
                                "传输完成 ✅（已接入热点，可继续接收；或点「断开网络」结束）"
                            }
                        }
                    }
                }
            }
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
        logUiEvent(EVT_NETWORK, "收到组网邀请（接管路径），接入热点…")
        // B4 温和收尾：收到 offer = 本机为从机（非热点方），传输完成后状态卡显示「断开网络」
        ui.hotspotSideAfterTransfer = false
        takeoverPeerIp = ip.trim() // v0.4.4：记录 offer 携带的 A 端热点 IP（ack 后作 transportPeerIp；未携带为空串）
        DiagLogger.log(
            TAG,
            "接管 offer：ssid=$s pwdLen=${pwd.length} ip=${takeoverPeerIp.ifEmpty { "<空>" }}（状态机未在等 offer，engine 直接驱动 WifiJoiner 接入）",
        )
        pendingJoinSsid = s
        pendingJoinPwd = pwd
        pendingJoinCallbacks = peerOfferJoinCallbacks
        ui.netState = "收到组网邀请，正在接入热点…"
        ui.pairingDialog = true // v0.5.4a：接管邀请（对端主动发起组网、本端未走 startNetworking）→ 配网弹窗接管接入进度
        startWifiJoinMonitor(s) // A6：接管 offer 后注册 Wi-Fi 变化监听（弹窗被忽略/手动接入 → 自动回 joined）
        wifiJoiner.join(s, pwd, peerOfferJoinCallbacks)
    }

    // ============ A6 Wi-Fi 变化监听（Specifier 弹窗被忽略 / 手动接入自动回 joined） ============

    /**
     * A6：注册 Wi-Fi 变化监听（NetworkCallback，观察 TRANSPORT_WIFI 网络；仅状态观察，不改变网络行为）。
     * 由 offer 接入路径（状态机 [netCallbacks.onOfferReceived] / 引擎接管 [handlePeerOffer]）调用——
     * 「会话中且已收 offer」时启用；会话 detach（[stopAllBle]/新握手）或组网中止/收尾（onAbort/B4）时
     * [stopWifiJoinMonitor] 注销。幂等：已注册时仅更新目标 SSID 并复位防重标志（新 offer 会话）。
     */
    private fun startWifiJoinMonitor(ssid: String) {
        val s = ssid.trim()
        if (s.isBlank()) return
        monitorOfferSsid = s
        wifiMonitorJoinedAlready = false // 新 offer 会话复位防重（本次 offer 尚未接入）
        if (wifiMonitorRegistered) {
            DiagLogger.log(TAG, "A6 Wi-Fi 监听已注册，更新目标 SSID=$s（防重已复位）")
            return
        }
        val cm = connectivityManager
        if (cm == null) {
            DiagLogger.log(TAG, "A6 Wi-Fi 监听注册失败：ConnectivityManager 不可用")
            return
        }
        try {
            cm.registerNetworkCallback(wifiMonitorRequest, wifiMonitorCallback, mainHandler)
            wifiMonitorRegistered = true
            DiagLogger.log(TAG, "A6 Wi-Fi 监听已注册（NetworkCallback）：SSID 匹配 $s → 自动取 IP 回 joined")
        } catch (e: Exception) {
            wifiMonitorRegistered = false
            DiagLogger.log(TAG, "A6 Wi-Fi 监听注册异常（不阻断接入流程）: $e")
        }
    }

    /**
     * A6：注销 Wi-Fi 变化监听并复位（幂等）。会话 detach（[stopAllBle]/新握手）/ 组网中止（onAbort）/
     * B4 收尾（关闭热点/断开网络/结束直连）时调用；监听仅 offer 会话内有效，会话结束即失效。
     */
    private fun stopWifiJoinMonitor() {
        mainHandler.removeCallbacks(wifiMonitorIpRetryRunnable)
        monitorOfferSsid = null
        wifiMonitorJoinedAlready = false
        wifiMonitorHitPending = false
        wifiMonitorIpRetryCount = 0
        if (!wifiMonitorRegistered) return
        wifiMonitorRegistered = false
        try {
            connectivityManager?.unregisterNetworkCallback(wifiMonitorCallback)
            DiagLogger.log(TAG, "A6 Wi-Fi 监听已注销（offer 会话结束/收尾）")
        } catch (e: Exception) {
            DiagLogger.log(TAG, "A6 Wi-Fi 监听注销异常（忽略）: $e")
        }
    }

    /**
     * A6：监听回调统一入口（主线程）——当前 SSID（去引号）与 offer.ssid（trim）相等 →
     * 触发「接入成功」：取本机 IPv4 → [completeAutoJoin] 复用「接入成功→发 joined」路径。
     * 守卫：监听未注册 / 已回 joined（防重）/ 命中取 IP 进行中（幂等）/ 无 offer 目标 / 会话已 detach。
     */
    private fun maybeAutoJoinFromWifi(network: Network?) {
        if (!wifiMonitorRegistered) return
        if (wifiMonitorJoinedAlready || wifiMonitorHitPending) return // 防重：已接入过 / 命中处理中（幂等）
        val target = monitorOfferSsid
        if (target.isNullOrBlank()) return // 未收 offer（A8 同网直连/热点方流程无目标）→ no-op
        if (!sessionManager.isAttached) return // 监听仅 offer 会话内有效（会话已 detach → 忽略）
        val nowSsid = wifiJoiner.currentSsid() // 去引号取当前 SSID（与 WifiJoiner 接入轮询同源）
        if (nowSsid == null || nowSsid != target) return // SSID 不匹配（trim 已在记录 offer.ssid 时归一）
        // 命中：当前 SSID == offer.ssid（未走 Specifier / 系统弹窗被忽略后用户手动接入）
        DiagLogger.log(
            TAG,
            "A6 Wi-Fi 监听命中：当前 SSID=$nowSsid == offer.ssid=$target（未走 Specifier，手动接入），自动取 IP 回 joined",
        )
        wifiMonitorHitPending = true
        wifiMonitorIpRetryCount = 0
        var ip: String? = network?.let { ipFromNetwork(it) } // 该 Wi-Fi 网络自身 LinkProperties（最准）
        if (ip == null) ip = wifiJoiner.fetchIpForSsid(target) // 复用既有取 IP 采集链（collect + WifiInfo 兜底）
        if (ip != null) {
            completeAutoJoin(ip)
            return
        }
        DiagLogger.log(TAG, "A6 命中但暂未取到 IPv4（DHCP/地址分配延迟），${WIFI_MONITOR_IP_RETRY_INTERVAL_MS}ms 后短延迟重取")
        mainHandler.postDelayed(wifiMonitorIpRetryRunnable, WIFI_MONITOR_IP_RETRY_INTERVAL_MS)
    }

    /**
     * A6：监听命中取 IP 完成 → 复用「接入成功→发 joined」路径：
     * - 状态机仍 [NetState.WAIT_JOIN]（弹窗未确认期间用户已手动连上）→ 复用状态机
     *   [NetworkingStateMachine.onWifiJoined]（发 joined + JOINED 等 ack，与 Specifier 接入成功完全对齐）；
     * - offer 等待窗口已过 / 状态机已非 WAIT_JOIN（null/中止/接管路径）→ 按接管路径直发 joined
     *   （[sendJoinedAsPeer]，复用 [peerOfferJoinCallbacks.onJoined] 同款载荷与 ack 超时语义）。
     * 防重：完成即置 [wifiMonitorJoinedAlready]，同一 SSID 重复命中（回调高频）幂等忽略。
     */
    private fun completeAutoJoin(ip: String) {
        wifiMonitorHitPending = false
        wifiMonitorIpRetryCount = 0
        mainHandler.removeCallbacks(wifiMonitorIpRetryRunnable)
        wifiMonitorJoinedAlready = true // 防重：本次 offer 会话内已回 joined
        DiagLogger.log(TAG, "A6 自动取 IP 成功 ip=${ip.ifEmpty { "<空>" }}，回 joined")
        val m = netStateMachine
        if (m != null && m.currentState == NetState.WAIT_JOIN) {
            DiagLogger.log(TAG, "A6 状态机仍 WAIT_JOIN：走状态机 onWifiJoined（对齐 Specifier 接入成功路径）")
            m.onWifiJoined(ip)
            return
        }
        DiagLogger.log(TAG, "A6 状态机已非 WAIT_JOIN（machine=${m != null} state=${m?.currentState}）：按接管路径直发 joined")
        sendJoinedAsPeer(ip)
    }

    /** A6：从 NetworkCallback 携带的 network 取该网络自身 IPv4（LinkProperties，最准；DHCP 未完成时可能为空）。 */
    private fun ipFromNetwork(network: Network): String? {
        return try {
            connectivityManager?.getLinkProperties(network)?.linkAddresses?.forEach { la ->
                val addr = la.address
                if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                    return addr.hostAddress
                }
            }
            null
        } catch (e: Exception) {
            DiagLogger.log(TAG, "A6 读取 LinkProperties IPv4 失败: $e")
            null
        }
    }

    /**
     * 从机发 joined（复用点）：接管路径 [peerOfferJoinCallbacks.onJoined] 与 A6 监听命中
     * [completeAutoJoin] 共用——发送 TYPE_JOINED{ip} 回报热点方（载荷与状态机 joined 一致，
     * 热点方按既有 onJoined 收敛）→ 启动接管路径 ack 超时（120s，对齐状态机 JOINED 等 ack）。
     */
    private fun sendJoinedAsPeer(ip: String) {
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
        logUiEvent(EVT_NETWORK, "接管路径确认：组网完成，传输就绪")
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

    // ============ v0.4.9 PIN 配对验证（握手 PIN 校验：关/仅首次/每次） ============

    /**
     * 握手完成 → PIN 校验（组网/同网直连前置拦截点，在 [applyRemoteHandshake] 内调用）：
     * - mode=0（关）→ 直接放行（pinVerifyOk=true，现状）；
     * - mode=1（仅首次）且 [PinStore.isPaired]（fp=对端握手指纹 deviceAddress）→ 放行（「已配对免验」）；
     * - 否则 → 弹 PIN 验证 UI：发起方（本端主动连接）生成 4-6 位数字配对码展示，等待对端 pin 信令回传；
     *   对端弹输入框，用户输入经信令回发 pin，等待发起方放行/中止（对端不自己判）；
     * - 自动重连（sessionResume，会话未 detach）且已验 → 保持放行不重验（会话内不再验）。
     */
    private fun beginPinVerification(fp: String, sessionResume: Boolean) {
        // iAmInitiator 由握手入口维护：openDevice（本端发起连接）置 true / Server 收到对端连接置 false（本端为被验证方）
        if (sessionResume && ui.pinVerifyOk) {
            // 自动重连恢复会话：PIN 已验（会话内不再验），保持放行状态
            DiagLogger.log(TAG, "PIN 验证：自动重连恢复会话，保持已验状态（会话内不再验）fp=$fp")
            return
        }
        resetPinVerifyState() // 新会话复位（pinVerifyOk/pinVerifyActive/展示/输入框/计数）
        val mode = pinStore.getMode()
        ui.pinMode = mode
        when {
            // 模式 0=关：直接放行（现状）
            mode == PinStore.MODE_OFF -> {
                ui.pinVerifyOk = true
                DiagLogger.log(TAG, "PIN 验证：模式=关，直接放行 fp=$fp")
            }
            // 模式 1=仅首次 且 已配对：免验放行
            mode == PinStore.MODE_FIRST && pinStore.isPaired(fp) -> {
                ui.pinVerifyOk = true
                ui.pinStatus = "已配对免验（本会话免 PIN）"
                DiagLogger.log(TAG, "PIN 验证：已配对免验 fp=$fp")
            }
            // 需校验：发起方生成配对码展示 / 对端弹输入框回传
            else -> {
                ui.pinVerifyOk = false
                ui.pinVerifyActive = true
                pendingPinFingerprint = fp
                pinFailCount = 0
                // v0.5.4a：PIN 校验阶段（含对端被邀请输入——握手由对方发起、本端未走 openDevice）→ 保持/开启配网弹窗
                ui.pairingDialog = true
                if (iAmInitiator) {
                    val pin = Random.nextInt(PIN_MIN, PIN_MAX) // 4-6 位（1000..999999）
                    pendingPin = pin
                    ui.pinShow = "PIN：$pin（请对端输入）"
                    ui.pinStatus = "等待对端输入配对码…"
                    DiagLogger.log(TAG, "PIN 验证：本端为发起方，已生成配对码（长度=${pin.toString().length}，内容不回显），等待对端输入")
                } else {
                    ui.pinInput = ""
                    ui.pinInputDialog = true
                    ui.pinStatus = "对端要求 PIN 配对验证：请输入对方展示的配对码"
                    DiagLogger.log(TAG, "PIN 验证：本端为对端，弹出输入框等待用户输入回传（PIN 不回显）")
                }
            }
        }
    }

    /** PIN 会话状态复位（配对表与模式持久保留；会话结束/新握手/中止时调用）。 */
    private fun resetPinVerifyState() {
        ui.pinVerifyActive = false
        ui.pinVerifyOk = false
        ui.pinShow = null
        ui.pinInputDialog = false
        ui.pinInput = ""
        ui.pinStatus = null
        ui.pinError = null
        pendingPin = null
        pendingPinFingerprint = null
        pinFailCount = 0
    }

    /**
     * 引擎层消费 pin 信令（init 的 onRemoteSignal 拦截点调用，不进状态机）：
     * - 发起方收到对端回传 pin{pin} → 比对（匹配 → 放行 + 仅首次记配对；不匹配 → 计数，3 次失败中止）；
     * - 对端收到发起方 pin{ok:true} → 放行解锁（对端不自己判，等待本端确认）；
     * - 对端收到 pin{ok:false, failCount} → 重新弹输入框（达 3 次由 abort 路径收敛，见 [handlePinAbort]）。
     */
    private fun handlePinSignal(msg: SignalMessage) {
        val payload = msg.payload ?: run {
            DiagLogger.log(TAG, "PIN 信令：payload 缺失，忽略")
            return
        }
        // 对端 → 发起方：回传输入
        if (payload.has("pin")) {
            val input = payload.optString("pin", "")
            DiagLogger.log(TAG, "PIN 信令：收到对端回传（长度=${input.length}，内容不回显）")
            val expected = pendingPin
            if (expected == null) {
                DiagLogger.log(TAG, "PIN 信令：本端无待验证配对码（非发起方/已复位），忽略")
                return
            }
            if (input.trim().toIntOrNull() == expected) {
                onPinMatch()
            } else {
                onPinMismatch(input)
            }
            return
        }
        // 发起方 → 对端：放行确认 / 不匹配通知
        if (payload.has("ok")) {
            if (payload.optBoolean("ok", false)) {
                // 放行：对端解锁组网（对端不自己判，等待本端确认）
                ui.pinVerifyOk = true
                ui.pinVerifyActive = false
                ui.pinInputDialog = false
                ui.pinShow = null
                pendingPin = null
                pendingPinFingerprint = null
                pinFailCount = 0
                ui.pinStatus = "✅ PIN 验证通过（对端已确认），可组建局域网"
                DiagLogger.log(TAG, "PIN 验证：收到对端放行确认，本会话已解锁（配对码不回显）")
                logUiEvent(EVT_HANDSHAKE, "对端确认 PIN 验证通过（可组建局域网）")
                refreshPairedView() // v0.5.0 UI-1：PIN 验毕 → 切对端卡视图
                ui.pairingDialog = false // v0.5.4a：配对完成（pairedView 置 true 点）→ 自动关配网弹窗
            } else {
                val n = payload.optInt("failCount", 0)
                if (n >= PIN_MAX_FAILS) {
                    // 3 次失败（对端已中止）：按中止收敛
                    handlePinAbort()
                    return
                }
                ui.pinInput = ""
                ui.pinInputDialog = true
                ui.pinError = "PIN 不匹配（第 $n/$PIN_MAX_FAILS 次），请重新输入对方展示的配对码"
                DiagLogger.log(TAG, "PIN 验证：收到不匹配通知（第 $n/$PIN_MAX_FAILS 次），重新弹输入框")
            }
        }
    }

    /** 发起方比对成功：放行 + （仅首次模式）记配对表 + 通知对端放行。 */
    private fun onPinMatch() {
        val fp = pendingPinFingerprint
        DiagLogger.log(TAG, "PIN 比对成功（内容不回显）")
        // 仅首次模式：匹配后按指纹记入配对表（指纹=对端握手指纹 deviceAddress）
        if (pinStore.getMode() == PinStore.MODE_FIRST && fp != null && pinStore.addPaired(fp)) {
            ui.pairedCount = pinStore.pairedCount
            DiagLogger.log(TAG, "PIN 验证：首次配对已记录 fp=$fp（后续同指纹免验）")
        }
        ui.pinVerifyOk = true
        ui.pinVerifyActive = false
        ui.pinShow = null
        ui.pinStatus = "✅ PIN 验证通过，可组建局域网"
        logUiEvent(EVT_HANDSHAKE, "PIN 配对验证通过（可组建局域网）")
        refreshPairedView() // v0.5.0 UI-1：PIN 验毕 → 切对端卡视图
        ui.pairingDialog = false // v0.5.4a：配对完成（pairedView 置 true 点）→ 自动关配网弹窗
        // 通知对端放行（同一 type pin；对端不自己判，等待本端确认）
        val ok = sessionManager.sendSignal(SignalMessage(SignalProtocol.TYPE_PIN, JSONObject().put("ok", true)))
        DiagLogger.log(TAG, "PIN 验证通过：已发送放行通知 ok=$ok")
        pendingPin = null
        pendingPinFingerprint = null
        pinFailCount = 0
    }

    /** 发起方比对失败：计数，3 次失败中止会话；未达上限通知对端重新输入。 */
    private fun onPinMismatch(input: String) {
        pinFailCount++
        DiagLogger.log(TAG, "PIN 比对失败：第 $pinFailCount/$PIN_MAX_FAILS 次（输入长度=${input.length}，内容不回显）")
        logUiEvent(EVT_ERROR, "PIN 不匹配（第 $pinFailCount/$PIN_MAX_FAILS 次）")
        if (pinFailCount >= PIN_MAX_FAILS) {
            abortPinSession()
            return
        }
        ui.pinError = "PIN 不匹配（第 $pinFailCount/$PIN_MAX_FAILS 次），请对端重新输入"
        ui.pinStatus = "PIN 不匹配，等待对端重新输入…"
        sessionManager.sendSignal(
            SignalMessage(SignalProtocol.TYPE_PIN, JSONObject().put("ok", false).put("failCount", pinFailCount))
        )
    }

    /** 3 次失败：中止会话（复用 abort 协议通知对端 + 断开本端会话 + 收敛组网状态）。 */
    private fun abortPinSession() {
        DiagLogger.log(TAG, "PIN 验证失败（$PIN_MAX_FAILS 次）：中止会话")
        sessionManager.sendSignal(
            SignalMessage(SignalProtocol.TYPE_ABORT, JSONObject().put("reason", PIN_ABORT_REASON))
        )
        netStateMachine?.cancel()
        netStateMachine = null
        sessionManager.detach()
        resetPinVerifyState()
        ui.pinVerifyOk = false
        ui.pinError = "PIN 验证失败，会话中止"
        ui.pinStatus = PIN_ABORT_REASON
        ui.netState = PIN_ABORT_REASON
        logUiEvent(EVT_ERROR, "PIN 验证失败（$PIN_MAX_FAILS 次），会话中止")
        refreshPairedView() // v0.5.0 UI-1：会话中止 → 配对视图复位
    }

    /** 对端收到 PIN 失败中止（TYPE_ABORT reason 含 [PIN_ABORT_REASON]）：收敛会话与 UI。 */
    private fun handlePinAbort() {
        DiagLogger.log(TAG, "PIN 验证：收到对端中止（PIN 验证失败），会话中止")
        netStateMachine?.cancel()
        netStateMachine = null
        sessionManager.detach()
        resetPinVerifyState()
        ui.pinVerifyOk = false
        ui.pinError = "PIN 验证失败，会话中止"
        ui.pinStatus = "PIN 验证失败，会话中止（对端已断开）"
        ui.netState = PIN_ABORT_REASON
        logUiEvent(EVT_ERROR, "PIN 验证中止（对端已断开）")
        refreshPairedView() // v0.5.0 UI-1：会话中止 → 配对视图复位
    }

    /** 对端输入框「发送」：校验数字 → 经信令回传 pin{...} → 等待发起方比对确认（对端不自己判）。 */
    fun confirmPinInput() {
        val input = ui.pinInput.trim()
        if (input.isEmpty() || input.toIntOrNull() == null) {
            ui.pinError = "请输入对方展示的数字配对码"
            DiagLogger.log(TAG, "PIN 回传：输入为空/非数字，保持输入框等待")
            return
        }
        val ok = sessionManager.sendSignal(
            SignalMessage(SignalProtocol.TYPE_PIN, JSONObject().put("pin", input))
        )
        if (!ok) {
            ui.pinError = "PIN 回传发送失败（无会话通道）"
            DiagLogger.log(TAG, "PIN 回传发送失败（无会话通道）")
            return
        }
        ui.pinInputDialog = false
        ui.pinError = null
        ui.pinStatus = "PIN 已发送，等待对方确认…（内容不回显）"
        DiagLogger.log(TAG, "PIN 回传已发送（长度=${input.length}，内容不回显），等待对方确认")
    }

    /** 对端输入框「取消」：关闭输入框（会话保持等待，可重新输入）。 */
    fun cancelPinInput() {
        ui.pinInputDialog = false
        ui.pinStatus = "PIN 输入已取消：可重新输入配对码"
        DiagLogger.log(TAG, "PIN 输入已取消（对端等待中，可重新输入）")
    }

    /** 设置区：切换 PIN 验证模式（0=关 1=仅首次 2=每次；PinStore 持久化）。切为「关」时立即放行当前会话。 */
    fun setPinMode(mode: Int) {
        val m = mode.coerceIn(PinStore.MODE_OFF, PinStore.MODE_EVERY)
        pinStore.setMode(m)
        ui.pinMode = m
        val name = when (m) {
            PinStore.MODE_OFF -> "关"
            PinStore.MODE_FIRST -> "仅首次"
            else -> "每次"
        }
        DiagLogger.log(TAG, "PIN 验证模式已切换：$name（$m）")
        if (m == PinStore.MODE_OFF) {
            // 切为「关」：进行中的验证取消并直接放行（无需重新握手）
            resetPinVerifyState()
            ui.pinVerifyOk = true
            DiagLogger.log(TAG, "PIN 验证：模式切为关，当前会话直接放行")
            logUiEvent(EVT_INFO, "PIN 验证模式切为关，当前会话直接放行")
            refreshPairedView() // v0.5.0 UI-1：PIN 关 → 切对端卡视图
            ui.pairingDialog = false // v0.5.4a：PIN 关直接放行 = 配对完成 → 自动关配网弹窗
        }
    }

    /** 设置区：清除配对列表（PinStore.clearAll；仅首次模式的免验记忆随之清空）。 */
    fun clearPairedDevices() {
        val n = pinStore.pairedCount
        pinStore.clearAll()
        ui.pairedCount = 0
        ui.pinStatus = "配对列表已清空（原 $n 条）"
        DiagLogger.log(TAG, "PIN 配对列表已清空（原 $n 条）")
    }

    /** 组网/同网直连前置判定：模式非「关」且本会话尚未验证通过 → 需先完成 PIN 验证。 */
    fun pinRequired(): Boolean = pinStore.getMode() != PinStore.MODE_OFF && !ui.pinVerifyOk

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
        sameLanDirectActive = false // A8：同网直连标记随停止复位（幂等）
        // ③ L2 本地热点收尾预留（B4 正式收尾前释放入口；幂等；stopAllBle 覆盖 release() 收尾路径）
        hotspotManager.stopLocalOnly()
        // ② Binder 直呼（v0.3.4）收尾兜底：状态机为 null 但登记框仍悬挂时释放待收敛 Binder 结果（幂等）
        hotspotManager.stopBinderTetherPending()
        ui.systemHotspotPwdMode = false
        wifiJoiner.cancel() // 释放进行中的接入 / 残留 NetworkCallback
        stopWifiJoinMonitor() // A6：会话结束（detach）→ 注销 Wi-Fi 变化监听（幂等）
        advertiser.stop()
        scanner.stop()
        gattServer.stop()
        sessionManager.detach() // 会话结束：恢复原 cleanup（内部 gattClient.release()）
        signalTest.stop() // 信令自测随会话停止（防定时器泄漏）
        gattClient.release() // 静默中断进行中的握手（幂等）
        // v0.4.9 PIN 配对验证：会话结束复位（pinVerifyOk/pinVerifyActive 等；配对表持久保留）
        resetPinVerifyState()
        iAmInitiator = false
        ui.advertising = false
        ui.scanning = false
        // v0.5.0 UI-1：会话 detach/停止 → 配对视图与对端卡复位（复位点随会话）
        ui.pairedView = false
        ui.selectedDevice = null
        ui.pairingDialog = false // v0.5.4a：全部流程停止 → 配网弹窗一并关闭
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
        // v0.4.9 PIN 配对验证：自动重连（会话未结束、attach 保持）→ 恢复会话而非新会话，PIN 状态保持不重验
        val sessionResume = sessionManager.isAttached
        val now = System.currentTimeMillis()
        val existing = ui.devices[deviceAddress]
        val entry = (existing ?: DeviceEntry(deviceAddress, 0, now, now))
            .copy(handshake = handshake, lastSeen = now)
        ui.devices[deviceAddress] = entry.copy(
            lanStatus = SameLanChecker.check(ui.localNetwork, handshake.net)
        )
        if (ui.detailDevice?.address == deviceAddress) {
            ui.detailDevice = ui.devices[deviceAddress]
            updateNetBtnVisibility() // A5：握手完成/刷新后重算「组建临时局域网」入口
        }
        // 持久信令会话：握手成功（Client 或 Server 任一通道）即 attach；
        // Client 侧由 keepAlive() 保留底层连接替代原硬 cleanup；Server 侧保留连接腿
        sessionManager.attach(deviceAddress)
        // Bluelink 组网补丁：新会话重置 offer 接管去重（一次会话一次接管）
        peerOfferHandled = false
        stopWifiJoinMonitor() // A6：新会话（重新握手 attach）→ 旧 offer 监听失效，注销并复位（新 offer 到来时重新注册）
        // v0.4.4：接管路径状态复位（offer 热点 IP 清空 + ack 超时定时取消，幂等）
        takeoverPeerIp = ""
        mainHandler.removeCallbacks(takeoverAckTimeoutRunnable)
        // 信令自测（验证包）：attach 成功后自动开始 120s 心跳收发（每 5s 一条 ping，对端回 pong）
        signalTest.start()
        // v0.4.9 PIN 配对验证：握手完成即触发校验（组网/同网直连前置拦截点；fingerprint=对端握手指纹 deviceAddress）
        beginPinVerification(deviceAddress, sessionResume)
        // v0.5.0 UI-1：握手后刷新本机卡 + 配对视图（PIN 关/已验 → 对端卡视图）+ 事件时间流
        refreshSelfCard()
        refreshPairedView()
        // v0.5.4a：配对完成且无需继续 PIN 校验（PIN 关 / 仅首次免验）→ 自动关配网弹窗；PIN 校验中由弹窗接管 PIN 阶段
        if (!ui.pinVerifyActive) ui.pairingDialog = false
        logUiEvent(EVT_HANDSHAKE, "与 ${handshake.alias.ifBlank { "未知设备" }} 握手完成（…${deviceAddress.takeLast(8)}）")
    }

    private fun refreshAllLanStatus() {
        ui.devices.keys.toList().forEach { addr ->
            val e = ui.devices[addr] ?: return@forEach
            val hs = e.handshake ?: return@forEach
            ui.devices[addr] = e.copy(lanStatus = SameLanChecker.check(ui.localNetwork, hs.net))
        }
        updateNetBtnVisibility()
    }

    // ============ v0.5.0 UI-1：事件时间流 / 两态配对视图 / 本机信息 / 移除设备 ============

    /**
     * 事件时间流追加（主线程）：时间 HH:mm:ss + 文案入 [BluelinkUiState.eventLog]，
     * 上限 [BluelinkUiState.EVENT_LOG_MAX] 条滚动（丢弃最旧）。kind 见 [EVT_INFO]…[EVT_TEARDOWN]。
     */
    fun logUiEvent(kind: Int, text: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val next = ui.eventLog + EventItem(ts, text, kind)
        ui.eventLog = if (next.size > BluelinkUiState.EVENT_LOG_MAX) {
            next.takeLast(BluelinkUiState.EVENT_LOG_MAX)
        } else {
            next
        }
    }

    /** 从扫描列表移除设备（清除失效设备；同时清关联弹层/对端卡并重算组网入口）。 */
    fun removeDevice(address: String) {
        ui.devices.remove(address)
        if (ui.detailDevice?.address == address) {
            ui.detailDevice = null
            updateNetBtnVisibility()
        }
        if (ui.selectedDevice?.address == address) {
            ui.selectedDevice = null
            refreshPairedView()
        }
        DiagLogger.log(TAG, "移除失效设备: $address")
        logUiEvent(EVT_INFO, "已清除设备 …${address.takeLast(8)}")
    }

    /** 重新扫描（对端卡「重新扫描」按钮）：重启扫描器；权限/蓝牙/开关未就绪时提示不启动。 */
    fun rescan() {
        val a = adapter
        if (!ui.permissionsGranted || !ui.btEnabled || !ui.advertisingWanted || a == null || !a.isEnabled) {
            logUiEvent(EVT_INFO, "重新扫描：广播/扫描未开启，无法扫描")
            return
        }
        scanner.stop()
        scanner.start(a)
        ui.scanning = true
        logUiEvent(EVT_INFO, "重新扫描已触发")
    }

    /** 按地址打开设备（便捷入口；查无此设备 no-op）。 */
    fun openDevice(address: String) {
        val entry = ui.devices[address] ?: run {
            DiagLogger.log(TAG, "openDevice(address) 查无设备: $address")
            return
        }
        openDevice(entry)
    }

    /** 刷新本机设备卡（alias/model=Build.MODEL；电量 [readBattery]；网络 describe）。 */
    private fun refreshSelfCard() {
        ui.selfCard = SelfInfo(
            alias = Build.MODEL,
            model = Build.MODEL,
            batteryPct = readBattery(),
            netText = ui.localNetwork.describe(),
        )
    }

    /**
     * 配对后视图刷新（两态切换判定）：会话已 attach 且 PIN 关/已验（[pinRequired] 为 false）→
     * [BluelinkUiState.pairedView]=true + 填对端卡 [BluelinkUiState.selectedDevice]；否则复位
     * （会话 detach/新握手/PIN 未验时对端卡视图不显示）。
     */
    private fun refreshPairedView() {
        val peer = sessionManager.currentPeer()
        val entry = peer?.let { ui.devices[it] }
            ?: ui.devices.values.firstOrNull { it.handshake != null }
        val paired = sessionManager.isAttached && !pinRequired()
        ui.pairedView = paired
        val hs = entry?.handshake
        if (paired && entry != null && hs != null) {
            ui.selectedDevice = DeviceInfo(
                address = entry.address,
                alias = hs.alias.ifBlank { "未知设备" },
                model = hs.model.ifBlank { entry.displayMac },
                batteryPct = hs.battery,
                netText = hs.net.describe(),
                statusText = peerStatusText(),
                sameLan = entry.lanStatus == LanStatus.SAME_LAN,
            )
        } else {
            ui.selectedDevice = null
        }
    }

    /** 对端卡连接状态字串：传输就绪=「接入」；会话保持=「已连接」；否则「未连接」。 */
    private fun peerStatusText(): String = when {
        transportPeerIp.isNotBlank() || sameLanDirectActive -> "接入"
        sessionManager.isAttached -> "已连接"
        else -> "未连接"
    }

    // ---------- A5 内部：异网判定 / 阶段映射 / 工具 ----------

    /**
     * A8 同网判定（握手完成且 startNetworking 触发时；同网判定在握手后即固化，无需等 offer）：
     * - 双方 `wifi=true` 且双方 `ssid` 非空且相等（trim 后比较）；
     * - 且 [SameLanChecker.isSameLan]（纯子网比较：双方 (IP & mask) 一致；复用 SameLanChecker）；
     * 满足 → 同网（免热点直连）；否则 → 异网（仲裁 + 热点逐级）。
     * 依据日志：ssid / 子网（describeSubnet）。
     */
    private fun sameLanForPeer(peerNet: NetworkSummary): Boolean {
        val local = ui.localNetwork
        val localSsid = local.ssid?.trim()?.takeIf { it.isNotBlank() }
        val peerSsid = peerNet.ssid?.trim()?.takeIf { it.isNotBlank() }
        if (!local.wifi || !peerNet.wifi || localSsid == null || peerSsid == null || localSsid != peerSsid) {
            DiagLogger.log(
                TAG,
                "A8 同网判定：ssid/网络类型不满足 → 异网（local.wifi=${local.wifi} peer.wifi=${peerNet.wifi} " +
                    "local.ssid=${localSsid ?: "<空>"} peer.ssid=${peerSsid ?: "<空>"}）",
            )
            return false
        }
        val subnetSame = SameLanChecker.isSameLan(local, peerNet)
        DiagLogger.log(
            TAG,
            "A8 同网判定依据：ssid 一致（$localSsid）；子网比较 " +
                "${SameLanChecker.describeSubnet(local.ip, local.mask)} vs ${SameLanChecker.describeSubnet(peerNet.ip, peerNet.mask)}" +
                " → isSameLan=$subnetSame（${if (subnetSame) "同网，免热点直连" else "异网，走仲裁+热点" }）",
        )
        return subnetSame
    }

    /**
     * 异网判定（简单版，任务约定）：握手后本机与对方 net 比较——
     * wifi 不都为 true，或 ssid 不同，或 IP 网段前缀不同 → 异网；否则视为同网。
     * v0.4.7 A8：入口可见性改由 [updateNetBtnVisibility] 按「已握手即可见」判定（同网/异网均可点，
     * 同网走免热点直连、异网走仲裁+热点），本方法保留作异网语义参考。
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

    /**
     * 重算详情弹层「组建临时局域网 / 同网免热点直连」入口可见性：已握手即可见（v0.4.7 A8：
     * 同网 → 免热点直连入口；异网 → 仲裁+热点组网入口；入口标签按 entry.lanStatus 区分）。
     */
    private fun updateNetBtnVisibility() {
        val entry = ui.detailDevice ?: run {
            ui.netBtnVisible = false
            return
        }
        if (entry.handshake == null) {
            ui.netBtnVisible = false
            return
        }
        ui.netBtnVisible = true
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
        // v0.5.0 UI-1：事件时间流 kind 常量（0=信息 1=握手 2=组网 3=传输 4=错误 5=收尾）
        const val EVT_INFO = 0
        const val EVT_HANDSHAKE = 1
        const val EVT_NETWORK = 2
        const val EVT_TRANSFER = 3
        const val EVT_ERROR = 4
        const val EVT_TEARDOWN = 5

        private const val TAG = "BluelinkEngine"

        /** 组网阶段轮询间隔。 */
        private const val NET_POLL_INTERVAL_MS = 500L

        /** T3 接收进度轮询间隔。 */
        private const val RECEIVE_POLL_INTERVAL_MS = 1000L

        /** 接管路径从机等 ack 超时：120s，对齐状态机 JOINED 等 ack（MANUAL_TIMEOUT_MS 同一值；v0.4.4）。 */
        private const val TAKEOVER_ACK_TIMEOUT_MS: Long = 120_000L

        /** A8 同网直连：probeTcp 异步探测重试次数（容忍对端 LocalSend 服务尚未监听 / AP 隔离，失败不阻断传输）。 */
        private const val SAME_LAN_PROBE_ATTEMPTS = 3

        /** A8 同网直连：单次 probeTcp 超时（ms；后台线程执行，不阻塞主线程）。 */
        private const val SAME_LAN_PROBE_TIMEOUT_MS = 1_500L

        /** A8 同网直连：probe 重试间隔（ms；给对端 LocalSend 服务启动留时间）。 */
        private const val SAME_LAN_PROBE_RETRY_DELAY_MS = 1_000L

        /** A6 Wi-Fi 监听：命中后取 IP 的短延迟重试间隔（ms；对齐 WifiJoiner IP_POLL_INTERVAL_MS）。 */
        private const val WIFI_MONITOR_IP_RETRY_INTERVAL_MS: Long = 500L

        /** A6 Wi-Fi 监听：命中后取 IP 最长重试次数（~5s，对齐 WifiJoiner IP_POLL_TIMEOUT_MS 语义）。 */
        private const val WIFI_MONITOR_IP_RETRY_MAX = 10

        /** v0.4.9 PIN 配对验证：生成配对码区间（Random.nextInt 半开区间 1000..999999 → 4-6 位数字）。 */
        private const val PIN_MIN = 1000
        private const val PIN_MAX = 1000000

        /** v0.4.9 PIN 配对验证：最大失败次数（3 次失败中止会话）。 */
        private const val PIN_MAX_FAILS = 3

        /** v0.4.9 PIN 配对验证：失败中止 abort 的 reason 标记（对端据此识别 PIN 失败中止并收敛）。 */
        private const val PIN_ABORT_REASON = "PIN 验证失败，会话中止"

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
