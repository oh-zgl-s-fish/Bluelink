package com.zglinus.bluelink.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zglinus.bluelink.ble.HandshakeMessage
import com.zglinus.bluelink.ble.SignalMessage
import com.zglinus.bluelink.net.LanStatus
import com.zglinus.bluelink.net.NetworkSummary

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

    /** 弹层当前选中的设备。 */
    var selectedDevice by mutableStateOf<DeviceEntry?>(null)

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
     */
    var transferState by mutableStateOf<String?>(null)

    /** 接收保存目录显示名（SAF OpenDocumentTree 选定目录；null=尚未选择，收到文件时提示点选）。 */
    var receiveDirName by mutableStateOf<String?>(null)

    /** 收到文件但未选保存目录 → UI 应发起 OpenDocumentTree 目录选择（MainScreen LaunchedEffect 消费后复位）。 */
    var receiveDirPrompt by mutableStateOf(false)
}
