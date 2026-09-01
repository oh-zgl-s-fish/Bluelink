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
}
