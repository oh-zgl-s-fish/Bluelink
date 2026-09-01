package com.zglinus.bluelink.ui

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.zglinus.bluelink.ble.BleAdvertiser
import com.zglinus.bluelink.ble.BleScanner
import com.zglinus.bluelink.ble.GattClient
import com.zglinus.bluelink.ble.GattServer
import com.zglinus.bluelink.ble.HandshakeMessage
import com.zglinus.bluelink.ble.RootDetector
import com.zglinus.bluelink.ble.SessionManager
import com.zglinus.bluelink.ble.SignalMessage
import com.zglinus.bluelink.ble.SignalProtocol
import com.zglinus.bluelink.ble.SignalTest
import com.zglinus.bluelink.diag.DiagLogger
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

/**
 * 一期 BLE 链路接线（生命周期由 MainActivity 持有）：
 * - 广播 / 扫描 / GATT Server 随顶部开关启停；
 * - GATT Client 在点击设备时发起握手；
 * - 握手结果（Server 端收 + Client 端收）统一写入 [BluelinkUiState]。
 *
 * A5 组网接线（异网设备组建临时局域网，A 包收官）：
 * - [HotspotManager]（A3b）：④ 手动路径 onManualRequest → ui.manualPwdDialog；
 * - [NetworkingStateMachine]（A3c）：点「组建临时局域网」→ 按本机/对端能力仲裁后创建，
 *   阶段经轮询 [NetworkingStateMachine.currentState] 映射到 [BluelinkUiState.netState]；
 * - [WifiJoiner]（A4）：对端流程收到 offer → join，结果 onJoined/onFailed 回灌状态机；
 * - ④ 手动流程：密码登记（setPassword + 打开系统热点设置）→ onManualConfigured → offer。
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

    // ============ A5 组网接线 ============

    /** 对端接入器（A4）：对端流程收到 offer 后接入对方热点，结果经 [wifiJoinCallbacks] 回灌。 */
    private val wifiJoiner = WifiJoiner(appContext)

    /** WifiJoiner 结果回调：成功→状态机 onWifiJoined；失败→手动密码重试对话框；8-10 需 WRITE_SETTINGS 引导。 */
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
            DiagLogger.log(TAG, "Android 8–10 路径需要 WRITE_SETTINGS 授权，引导系统设置")
            ui.writeSettingsDialog = true
        }
    }

    /** 热点管理器（A3b）：①②③ 本包降级，④ 手动路径触发 UI 密码登记。 */
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
    })

    /** 组网状态机回调（A3c）：offer→WifiJoiner 接入；传输就绪；中止收敛（置空机器 + 停轮询）。 */
    private val netCallbacks = object : NetworkingStateMachine.Callbacks {
        override fun onOfferReceived(ssid: String, pwd: String?) {
            DiagLogger.log(TAG, "收到对端 offer：ssid=$ssid pwdLen=${pwd?.length ?: 0}，WifiJoiner 接入")
            pendingJoinSsid = ssid
            wifiJoiner.join(ssid, pwd ?: "", wifiJoinCallbacks)
        }

        override fun onTransportReady(peerIp: String) {
            // 传输就绪（一期 peerIp 可为占位 ""）；A 包仅展示，传输为 B 包范围
            DiagLogger.log(TAG, "组网传输就绪 peerIp=${peerIp.ifEmpty { "<空>" }}（A 包仅提示，传输为 B 包范围）")
        }

        override fun onAbort(reason: String) {
            DiagLogger.log(TAG, "组网中止: $reason")
            netStateMachine = null // 允许再次「组建临时局域网」（下次 start 新建机器）
            mainHandler.removeCallbacks(netPoller)
            ui.netActive = false
            ui.netState = "组网已中止：$reason"
        }
    }

    /** 组网状态机（按选中对端握手能力 + 本机能力仲裁后创建；结束后置 null）。 */
    private var netStateMachine: NetworkingStateMachine? = null

    /** 接入失败/重试的目标 SSID（最近一次 offer 携带）。 */
    private var pendingJoinSsid: String? = null

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
            ui.netState = netStateText(s)
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
                // offer 经状态机回调 onOfferReceived 驱动 WifiJoiner 接入）
                netStateMachine?.onRemoteSignal(msg)
            }
        })
        // 握手期拒连（地址无关）：把 ui.handshaking 实时状态透传给 GattServer，
        // 握手进行中（发起置 true / 完成或失败置 false 由现有逻辑维护）对端新连接一律掐断。
        // ui 声明在 gattServer 之后，故在此 init（ui 已初始化）注册，lambda 每次查询实时值。
        gattServer.setHandshakingProvider { ui.handshaking }
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
        netStateMachine?.cancel()
    }

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
        wifiJoiner.join(ssid, pwd, wifiJoinCallbacks)
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
