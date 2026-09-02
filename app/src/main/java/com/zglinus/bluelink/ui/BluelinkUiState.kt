package com.zglinus.bluelink.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zglinus.bluelink.ble.HandshakeMessage
import com.zglinus.bluelink.ble.SignalMessage
import com.zglinus.bluelink.net.LanStatus
import com.zglinus.bluelink.net.NetworkSummary

// ============ v0.5.0 UI-1：事件时间流 / 两态配对视图 / 抽屉路由 ============

/** 事件时间流条目（主页面最下「时间流」LazyColumn 用；engine [BluelinkEngine.logUiEvent] 追加）。 */
data class EventItem(
    val ts: String, // HH:mm:ss
    val text: String,
    val kind: Int, // 0=信息 1=握手 2=组网 3=传输 4=错误 5=收尾
)

/** 本端设备卡数据（engine 握手/刷新时填；alias/model 取 Build.MODEL，电量/网络实时采集）。 */
data class SelfInfo(
    val alias: String = "",
    val model: String = "",
    val batteryPct: Int? = null,
    val netText: String = "",
)

/** 对端设备卡数据（配对后右侧卡：握手对端 alias/model/battery/net + 连接状态字串 + 同网标记）。 */
data class DeviceInfo(
    val address: String = "",
    val alias: String = "",
    val model: String = "",
    val batteryPct: Int? = null,
    val netText: String = "",
    val statusText: String = "", // 已连接 / 接入 / 未连接
    val sameLan: Boolean = false, // 同网直连按钮标签用（entry.lanStatus == SAME_LAN）
)

/**
 * 附近设备条目。
 * 扫描阶段只有 MAC + RSSI；握手成功后补齐别名/型号/网络信息/同网判定。
 */
data class DeviceEntry(
    val address: String,
    val rssi: Int,
    val firstSeen: Long,
    val lastSeen: Long,
    val handshake: HandshakeMessage? = null,
    val lanStatus: LanStatus = LanStatus.UNKNOWN,
) {
    /** MAC 截断显示（只留末 8 字符，如 …A1:B2:C3）。 */
    val displayMac: String get() = if (address.length >= 8) "…${address.takeLast(8)}" else address
}

/** 供 Compose 观察的 UI 状态（全部为主线程写入）。 */
class BluelinkUiState {

    /** 蓝牙是否开启（监听 ACTION_STATE_CHANGED）。 */
    var btEnabled by mutableStateOf(false)

    /** 所需运行时权限是否已授予。 */
    var permissionsGranted by mutableStateOf(false)

    /** 用户是否希望广播/扫描（顶部开关，rememberSaveable 由 UI 侧保存）。 */
    var advertisingWanted by mutableStateOf(true)

    /** 广播实际状态。 */
    var advertising by mutableStateOf(false)

    /** 扫描实际状态。 */
    var scanning by mutableStateOf(false)

    /** 广播失败原因（状态卡提示用）。 */
    var advertiserError by mutableStateOf<String?>(null)

    /** 扫描失败原因。 */
    var scanError by mutableStateOf<String?>(null)

    /** 本机网络摘要。 */
    var localNetwork by mutableStateOf(NetworkSummary())

    /** 附近设备（按蓝牙 MAC 索引）。 */
    val devices = mutableStateMapOf<String, DeviceEntry>()

    /** 弹层当前选中的设备（openDevice 置；DeviceDetailSheet 渲染依据）。 */
    var detailDevice by mutableStateOf<DeviceEntry?>(null)

    /** GATT 客户端握手进行中（弹层展示“正在握手…”）。 */
    var handshaking by mutableStateOf(false)

    /** 最近一次握手失败原因。 */
    var handshakeError by mutableStateOf<String?>(null)

    /** 诊断日志弹窗是否可见。 */
    var diagVisible by mutableStateOf(false)

    /** 诊断日志弹窗当前展示的文本（打开/刷新时由 UI 侧填充 DiagLogger.dump()）。 */
    var diagnosticText by mutableStateOf("")

    /** 最近一条会话信令（持久信令会话 A2；engine 转发 SessionManager 上抛，供 UI 后续展示）。 */
    var lastSignal by mutableStateOf<Pair<String, SignalMessage>?>(null)

    // ============ 信令自测（Bluelink 验证包） ============

    /** 信令自测状态行文本（engine 经 SignalTest 回调同步，netPoller 500ms 兑底刷新；null = 尚未开始）。 */
    var signalTestStatus by mutableStateOf<String?>(null)

    /** 信令自测是否运行中（「信令自测」开/关按钮状态依据）。 */
    var signalTestRunning by mutableStateOf(false)

    // ============ A5 组网（networking）UI 状态 ============

    /** 组网阶段展示文本（engine 轮询状态机 currentState 映射；null = 未组网）。 */
    var netState by mutableStateOf<String?>(null)

    /** 详情弹层「组建临时局域网」按钮是否可见（异网且已握手，engine 判定）。 */
    var netBtnVisible by mutableStateOf(false)

    /** 组网进行中（非 IDLE/TEARDOWN；「结束组网」按钮显示依据）。 */
    var netActive by mutableStateOf(false)

    /** ④ 手动配网密码登记对话框是否可见（HotspotManager.onManualRequest 触发）。 */
    var manualPwdDialog by mutableStateOf(false)

    /** ④ 手动配网：热点名称输入（占位默认 Bluelink，用户可改）。 */
    var manualSsidInput by mutableStateOf("Bluelink")

    /** ④ 手动配网 / 接入失败重试：密码输入。 */
    var manualPwdInput by mutableStateOf("")

    /** 接入失败对话框（WifiJoiner onFailed：系统弹窗未确认/超时等，可手动输密码重试）。 */
    var joinFailDialog by mutableStateOf(false)

    /** 接入失败原因（WifiJoiner onFailed reason）。 */
    var joinFailReason by mutableStateOf<String?>(null)

    /** WRITE_SETTINGS 授权引导对话框（Android 8–10 接入路径，onNeedWriteSettingsPermission 触发）。 */
    var writeSettingsDialog by mutableStateOf(false)

    /** ③ L2 本地热点（13+）密码登记框是否可见（HotspotManager.onLocalOnlyPasswordRequest 触发）。 */
    var localOnlyPwdDialog by mutableStateOf(false)

    /** ③ L2 本地热点（13+）系统弹窗/通知展示的 SSID（登记框提示用；密码需用户按系统弹窗回填）。 */
    var localOnlySsid by mutableStateOf<String?>(null)

    /** ② 系统预配热点（Binder 直呼成功）登记框模式（v0.3.4）：true=热点已自动开启、请登记本机系统热点 SSID+密码（ManualPwdDialog 按此渲染不同文案与确认回调）；false=④ 手动配网。复用 manualSsidInput/manualPwdInput 输入。 */
    var systemHotspotPwdMode by mutableStateOf(false)

    /**
     * WifiJoiner 权限前置缺失的运行时权限（onNeedPermission 置位；BluelinkRoot 观察此字段发起
     * 系统授权弹窗，请求结果回来后 engine 经 [joinRetryNeeded] 自动重试 join；授权后复位）。
     */
    var requestedPermission by mutableStateOf<String?>(null)

    /** WifiJoiner 权限前置挂起 join 是否待授权后自动重试（授权成功后 engine 自动重试 join 并复位）。 */
    var joinRetryNeeded by mutableStateOf(false)

    // ============ ③ LocalOnly 自测（v0.3.9 独立入口，不经过组网/状态机） ============

    /** ③ LocalOnly 自测状态行文本（engine 经 LocalOnlyHotspotCallback 同步；null = 尚未开始）。 */
    var localOnlyTestInfo by mutableStateOf<String?>(null)

    /** ③ LocalOnly 自测是否运行中（热点已开 / 等待密码登记；「LocalOnly 自测」按钮 running 时变「关闭 LocalOnly」）。 */
    var localOnlyTestRunning by mutableStateOf(false)

    /** ③ LocalOnly 自测（sdk 33+）密码登记框是否可见（onStarted 后系统弹窗/通知展示密码、App 侧不可读，请用户按系统弹窗抄写回填；复用 manualPwdInput 输入）。 */
    var loTestPwdDialog by mutableStateOf(false)

    /** ③ LocalOnly 自测（sdk 33+）：用户已按系统弹窗回填并确认登记密码（完成标记；UI 据此显示「密码已登记」）。 */
    var localOnlyTestPasswordSet by mutableStateOf(false)

    // ============ T3 LocalSend 传输（发送/接收） ============

    /** 发送确认框是否可见（SAF 选文件后展示文件名/大小/目标；确认后 engine 后台发送）。 */
    var sendDialog by mutableStateOf(false)

    /** 传输状态行文本（null=无传输）：发送侧「发送中 文件名 45%」/「发送完成/失败/已取消：…」；
     * 接收侧「接收中 文件名 45%」（Engine 轮询 LocalSendServer.getActiveSessions 映射）、
     * 「已收到…请选择保存位置」（未选保存目录）与「已保存到 <目录名>」（SAF 转存成功，v0.4.5）。
     * v0.4.6 B4 温和收尾：发送完成（onAllDone）/ 接收侧全部转存完成 → 「传输完成 ✅（…）」文案
     * （热点保持/已接入，可继续；不自动拆/不自动断），由用户点「关闭热点」/「断开网络」手动收尾。
     */
    var transferState by mutableStateOf<String?>(null)

    /**
     * B4 温和收尾：本机在组网中的角色标志（true=热点方 / false=从机），传输完成后状态卡据此显示
     * 「关闭热点」或「断开网络」按钮。engine 在组网角色确定时写入：startNetworking 仲裁 who==ME
     * （含手动④ who==null，本机手动开热点）置 true；收到 offer（状态机 onOfferReceived / 接管
     * handlePeerOffer，本机为从机）置 false。
     */
    var hotspotSideAfterTransfer by mutableStateOf(false)

    /** 接收保存目录显示名（SAF OpenDocumentTree 选定目录；null=尚未选择，收到文件时提示点选）。 */
    var receiveDirName by mutableStateOf<String?>(null)

    /** 收到文件但未选保存目录 → UI 应发起 OpenDocumentTree 目录选择（MainScreen LaunchedEffect 消费后复位）。 */
    var receiveDirPrompt by mutableStateOf(false)

    // ============ v0.4.9 PIN 配对验证 ============

    /** PIN 验证模式（0=关 1=仅首次 2=每次；PinStore 持久化，engine 启动/切换时同步到 UI）。 */
    var pinMode by mutableStateOf(0)

    /** PIN 验证进行中（握手完成待校验：发起方展示配对码等待 / 对端输入回传等待；会话结束复位）。 */
    var pinVerifyActive by mutableStateOf(false)

    /** 本会话 PIN 验证已通过（组网/同网直连解锁；会话结束复位 false；仅首次模式配对后本会话不再验）。 */
    var pinVerifyOk by mutableStateOf(false)

    /** 发起方展示的配对码文案（"PIN：XXXXXX（请对端输入）"；仅本机 UI 展示，不落日志/信令回显）。 */
    var pinShow by mutableStateOf<String?>(null)

    /** 对端输入框是否可见（本端为被验证方；确认后经信令回传 pin，等待发起方比对确认——对端不自己判）。 */
    var pinInputDialog by mutableStateOf(false)

    /** 对端输入框内容（数字串；日志仅记长度，不回显内容）。 */
    var pinInput by mutableStateOf("")

    /** PIN 验证状态行文本（等待输入/已发送/通过/中止等）。 */
    var pinStatus by mutableStateOf<String?>(null)

    /** PIN 验证错误/提示（不匹配计数、输入非数字、发送失败、中止原因等）。 */
    var pinError by mutableStateOf<String?>(null)

    /** 已配对指纹数（PinStore 同步；设置区展示「已配对 N 台」）。 */
    var pairedCount by mutableStateOf(0)

    // ============ v0.5.0 UI-1：事件时间流 / 两态配对视图 / 本机信息 / 抽屉路由 ============

    /** 事件时间流（engine [BluelinkEngine.logUiEvent] 追加；上限 [EVENT_LOG_MAX] 条滚动）。 */
    var eventLog by mutableStateOf(listOf<EventItem>())

    /** 配对后视图（会话建立且 PIN 关/已验 → true，主页面切换对端卡视图；复位点随会话 detach/stopAllBle）。 */
    var pairedView by mutableStateOf(false)

    /**
     * v0.5.4a 配网极简弹窗（NetPairingDialog）可见性：openDevice（点设备发起握手）/ startNetworking /
     * beginPinVerification（PIN 校验，含对端被邀请输入）/ 收到组网邀请（接管路径）置 true；
     * 配对完成（pairedView 置 true 点：握手成功免验 / onPinMatch / 对端放行 / 模式切关）或传输就绪
     * （onTransportReadyInternal 收敛点）自动置 false；中止/失败保留弹窗显错误态（关闭由弹窗「关闭」按钮）。
     */
    var pairingDialog by mutableStateOf(false)

    /** 本端设备卡数据（engine 握手/刷新时填）。 */
    var selfCard by mutableStateOf(SelfInfo())

    /** 对端设备卡数据（配对后右侧卡；引擎握手/PIN 验毕后填，会话结束清空）。 */
    var selectedDevice by mutableStateOf<DeviceInfo?>(null)

    /** 抽屉路由当前页：0=主页面 1=发送 2=接收 3=记录 4=设置 5=权限 6=关于。 */
    var currentPage by mutableStateOf(0)

    companion object {
        /** 事件时间流上限（超出丢弃最旧）。 */
        const val EVENT_LOG_MAX = 200
    }
}
