package com.zglinus.bluelink.ui

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.zglinus.bluelink.ble.HandshakeMessage
import com.zglinus.bluelink.ble.HandshakeProtocol
import com.zglinus.bluelink.diag.DiagLogger
import com.zglinus.bluelink.net.LanStatus
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 主页面（docs/ui-design.md §4.1 一期最简版）：
 * 权限/蓝牙引导卡 → 本机状态卡（广播开关 + 网络摘要）→ 附近设备列表 → 空态文案。
 *
 * v0.3.9：本机状态卡新增「LocalOnly 自测」独立入口（不经过组网/状态机）——
 * running 时按钮变「关闭 LocalOnly」，下方展示 [BluelinkUiState.localOnlyTestInfo] 状态行；
 * sdk 33+ 密码登记框（[LoTestPwdDialog]）在 MainScreen 顶层渲染（不依赖设备详情弹层）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    ui: BluelinkUiState,
    advertisingWanted: Boolean,
    onAdvertisingWantedChange: (Boolean) -> Unit,
    onDeviceClick: (DeviceEntry) -> Unit,
    onRefreshNetwork: () -> Unit,
    onRequestPermissions: () -> Unit,
) {
    // T3 发送入口：SAF OpenDocument 文件选择器（系统 picker；结果 → engine.onSendFilePicked）
    val sendFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) BluelinkEngine.current()?.onSendFilePicked(uri)
    }

    // v0.4.5 接收侧：SAF OpenDocumentTree 目录选择器（系统 picker，不自建文件浏览器；
    // 结果 → engine.onReceiveDirPicked；初始目录经 EXTRA_INITIAL_URI 尽力，不支持则系统默认）
    val receiveDirLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) BluelinkEngine.current()?.onReceiveDirPicked(uri)
    }

    // v0.4.5：收到文件但未选保存目录（engine 置 receiveDirPrompt）→ 自动弹目录选择器（消费后复位；
    // 用户取消则保持未选，可再点「选择保存目录」，暂存文件选定后补存）
    LaunchedEffect(ui.receiveDirPrompt) {
        if (ui.receiveDirPrompt) {
            receiveDirLauncher.launch(initialReceiveDirUri())
            ui.receiveDirPrompt = false
        }
    }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
        ) {
            if (!ui.permissionsGranted) {
                PermissionBanner(onRequestPermissions)
            } else if (!ui.btEnabled) {
                BluetoothOffBanner()
            }

            Spacer(Modifier.height(12.dp))

            Text("Bluelink", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))

            LocalStatusCard(
                ui = ui,
                advertisingWanted = advertisingWanted,
                onAdvertisingWantedChange = onAdvertisingWantedChange,
                onRefreshNetwork = onRefreshNetwork,
                onSendFileClick = { sendFileLauncher.launch(arrayOf("*/*")) },
                onChooseReceiveDir = { receiveDirLauncher.launch(initialReceiveDirUri()) },
            )

            Spacer(Modifier.height(8.dp))

            if (ui.devices.isEmpty()) {
                EmptyState()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        Text("附近的设备", style = MaterialTheme.typography.titleMedium)
                    }
                    items(
                        ui.devices.values.sortedBy { it.firstSeen },
                        key = { it.address },
                    ) { entry ->
                        DeviceRow(entry = entry, onClick = { onDeviceClick(entry) })
                    }
                }
            }
        }
    }

    // 诊断日志弹窗：打开时自动加载一次 dump
    LaunchedEffect(ui.diagVisible) {
        if (ui.diagVisible) ui.diagnosticText = DiagLogger.dump()
    }

    if (ui.diagVisible) {
        DiagnosticLogDialog(
            text = ui.diagnosticText,
            onDismiss = { ui.diagVisible = false },
            onRefresh = { ui.diagnosticText = DiagLogger.dump() },
            onClear = {
                DiagLogger.clear()
                ui.diagnosticText = DiagLogger.dump()
            },
        )
    }

    // ③ LocalOnly 自测（v0.3.9）：sdk 33+ 密码登记框（主界面独立入口，经 BluelinkEngine.current() 取引擎，与设备详情弹层无关）
    BluelinkEngine.current()?.let { engine ->
        if (ui.loTestPwdDialog) LoTestPwdDialog(engine)
    }

    // T3 LocalSend：发送确认框（SAF 选文件后展示文件名/大小/目标；确认后后台发送）
    BluelinkEngine.current()?.let { engine ->
        if (ui.sendDialog) SendConfirmDialog(engine)
    }
}

/** 本机状态卡：广播开关（Switch）+ 本机网络摘要（Wi-Fi/蜂窝/IP/子网）+ 信令自测 + ③ LocalOnly 自测。 */
@Composable
private fun LocalStatusCard(
    ui: BluelinkUiState,
    advertisingWanted: Boolean,
    onAdvertisingWantedChange: (Boolean) -> Unit,
    onRefreshNetwork: () -> Unit,
    onSendFileClick: () -> Unit,
    onChooseReceiveDir: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("本机", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = Build.MODEL,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { ui.diagVisible = true }) { Text("诊断") }
                Switch(
                    checked = advertisingWanted,
                    onCheckedChange = onAdvertisingWantedChange,
                )
            }
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = ui.localNetwork.describe(),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRefreshNetwork) { Text("刷新") }
            }
            if (ui.advertising) {
                Text(
                    text = "广播中 · 扫描中",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Text(
                    text = "广播已停止",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ui.advertiserError?.let {
                Text(
                    text = "广播异常: $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            ui.scanError?.let {
                Text(
                    text = "扫描异常: $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // ============ 信令自测（验证包）：状态行 + 开/关按钮 ============
            ui.signalTestStatus?.let { status ->
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ui.signalTestRunning) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            BluelinkEngine.current()?.let { engine ->
                TextButton(
                    onClick = {
                        if (ui.signalTestRunning) engine.stopSignalTest() else engine.startSignalTest()
                    },
                ) {
                    Text(if (ui.signalTestRunning) "停止信令自测" else "开始信令自测")
                }
            }

            // ============ ③ LocalOnly 自测（v0.3.9 独立入口，不经过组网/状态机） ============
            BluelinkEngine.current()?.let { engine ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = {
                            if (ui.localOnlyTestRunning) {
                                engine.closeLocalOnlySelfTest()
                            } else {
                                engine.localOnlySelfTest()
                            }
                        },
                    ) {
                        Text(if (ui.localOnlyTestRunning) "关闭 LocalOnly" else "LocalOnly 自测")
                    }
                    if (ui.localOnlyTestRunning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                }
                ui.localOnlyTestInfo?.let { info ->
                    Text(
                        text = if (ui.localOnlyTestPasswordSet) "✅ $info" else info,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (ui.localOnlyTestRunning) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }

            // ============ T3 LocalSend 传输（发送入口 / 进度 / 接收保存位置） ============
            BluelinkEngine.current()?.let { engine ->
                // 发送入口：TRANSPORT（transportPeerIp 已记录）或已有握手对端时可点（同网直连场景 A8 落地后生效）
                val canSend = engine.transportPeerIp.isNotBlank() ||
                    ui.devices.values.any { it.handshake != null }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = onSendFileClick,
                        enabled = canSend,
                    ) { Text("发送文件") }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (engine.transportPeerIp.isNotBlank()) {
                            "目标: ${engine.transportPeerIp}"
                        } else {
                            "组网就绪后可发送"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ui.transferState?.let { state ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = state,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (state.startsWith("发送完成") || state.startsWith("✅")) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { engine.cancelSend() }) { Text("取消") }
                    }
                }
                // ============ v0.4.5 接收保存位置（SAF OpenDocumentTree 目录，不自建文件浏览器） ============
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "接收保存至：${ui.receiveDirName ?: "未选择（点选）"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (ui.receiveDirName != null) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.weight(1f),
                    )
                    if (ui.receiveDirName == null) {
                        OutlinedButton(onClick = onChooseReceiveDir) { Text("选择保存目录") }
                    }
                }
            }
        }
    }
}

/** 设备行：握手后显示别名/型号/MAC/RSSI/网络徽标/同网标记；握手前显示 MAC+RSSI。 */
@Composable
private fun DeviceRow(entry: DeviceEntry, onClick: () -> Unit) {
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val hs = entry.handshake
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = hs?.alias?.takeIf { it.isNotBlank() } ?: "未知设备",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (hs != null) {
                    NetworkBadge(hs)
                } else {
                    Text(
                        text = "扫描中…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (hs != null) {
                Text(
                    text = listOfNotNull(
                        hs.model.takeIf { it.isNotBlank() },
                        entry.displayMac,
                        "${entry.rssi} dBm",
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = "${entry.displayMac} · ${entry.rssi} dBm",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = when (entry.lanStatus) {
                    LanStatus.SAME_LAN -> "✅ 同网"
                    LanStatus.DIFFERENT_NETWORK -> "🌐 异网"
                    LanStatus.UNKNOWN -> "❔ 未知"
                },
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

/** 网络徽标：同Wi-Fi / 蜂窝 / 未知（取自对方握手 net 字段）。 */
@Composable
private fun NetworkBadge(hs: HandshakeMessage) {
    val (text, color) = when {
        hs.net.wifi -> "同Wi-Fi" to Color(0xFF2E7D32)
        hs.net.cellular -> "蜂窝" to Color(0xFFEF6C00)
        else -> "未知" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = MaterialTheme.shapes.small,
        color = color.copy(alpha = 0.15f),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

/** 权限引导卡（未授权时置顶）。 */
@Composable
private fun PermissionBanner(onRequestPermissions: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "需要权限: 蓝牙 + 位置",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRequestPermissions) { Text("去授权") }
        }
    }
}

/** 蓝牙未开提示（不自动开，仅提示）。 */
@Composable
private fun BluetoothOffBanner() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Text(
            text = "请在系统设置开启蓝牙",
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** 无设备空态。 */
@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "等待周围设备…",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "确保对方已打开 Bluelink 广播",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 设备详情弹层：握手详情 JSON 渲染 + 同网判定结果 + A5 组网入口/阶段/结束。
 *
 * A5 组网状态经 [BluelinkEngine.current()]（companion 单例，init 注册 / release 注销）读取：
 * 保持 BluelinkRoot 零改动以符合「3 文件」改动范围，engine 与 UI 同包无需 import。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceDetailSheet(
    entry: DeviceEntry,
    handshaking: Boolean,
    handshakeError: String?,
    onDismiss: () -> Unit,
) {
    val engine = BluelinkEngine.current()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val hs = entry.handshake
            Text(
                text = hs?.alias?.takeIf { it.isNotBlank() } ?: "未知设备",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = "${entry.displayMac} · ${entry.rssi} dBm",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()

            Text("同网判定", style = MaterialTheme.typography.titleSmall)
            Text(
                text = when (entry.lanStatus) {
                    LanStatus.SAME_LAN -> "✅ 同网（同一子网，可直连传输）"
                    LanStatus.DIFFERENT_NETWORK -> "🌐 异网（可组建临时局域网）"
                    LanStatus.UNKNOWN -> "❔ 未知（信息不足）"
                },
                style = MaterialTheme.typography.bodyMedium,
            )

            // ============ A5 组网：入口 / 阶段 / 结束 ============
            if (engine != null) {
                val ui = engine.ui
                if (ui.netBtnVisible) {
                    HorizontalDivider()
                    Text("临时局域网", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "本机与对方不在同一网络，可组建临时局域网后直连。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = { engine.startNetworking() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("组建临时局域网") }
                }
                ui.netState?.let { state ->
                    Text(
                        text = "组网状态：$state",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (state.startsWith("✅")) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                if (ui.netActive) {
                    OutlinedButton(
                        onClick = { engine.endNetworking() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("结束组网") }
                }
            }

            HorizontalDivider()

            Text("握手详情", style = MaterialTheme.typography.titleSmall)
            when {
                hs != null -> Text(
                    text = HandshakeProtocol.prettyJson(hs),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                handshaking -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("正在握手…", style = MaterialTheme.typography.bodyMedium)
                }
                handshakeError != null -> Text(
                    text = "握手失败: $handshakeError",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                else -> Text(
                    text = "点击设备后发起 GATT 握手",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("关闭") }
        }
    }

    // ============ A5 组网对话框 ============
    if (engine != null) {
        if (engine.ui.manualPwdDialog) ManualPwdDialog(engine)
        if (engine.ui.joinFailDialog) JoinFailDialog(engine)
        if (engine.ui.writeSettingsDialog) WriteSettingsDialog(engine)
        if (engine.ui.localOnlyPwdDialog) LocalOnlyPwdDialog(engine)
    }
}

/** ④ 手动配网对话框 / ② 系统预配热点登记框（v0.3.4）：按 systemHotspotPwdMode 分流——true 走
 *  SystemHotspotPwdDialog（Binder 直呼成功、登记本机系统热点 SSID+密码）；false 走 ④ 手动配网（原样）。 */
@Composable
private fun ManualPwdDialog(engine: BluelinkEngine) {
    val ui = engine.ui
    if (ui.systemHotspotPwdMode) {
        SystemHotspotPwdDialog(engine)
    } else {
        ManualPwdDialogV4(engine)
    }
}

/** ② 系统预配热点登记框（v0.3.4）：热点已自动开启（Binder 直呼成功，SSID/密码为系统配置、App 不可读），
 *  复用登记框字段（manualSsidInput/manualPwdInput），请用户按本机热点信息登记；确认走 confirmSystemHotspotPwd。 */
@Composable
private fun SystemHotspotPwdDialog(engine: BluelinkEngine) {
    val ui = engine.ui
    AlertDialog(
        onDismissRequest = { ui.manualPwdDialog = false },
        title = { Text("系统热点登记（②）") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "热点已自动开启，请输入本机系统热点的名称与密码：",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "请在手机顶部/系统设置中查看热点名称与密码（系统预配，App 无法读取）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = ui.manualSsidInput,
                    onValueChange = { ui.manualSsidInput = it },
                    label = { Text("系统热点名称 (SSID)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = ui.manualPwdInput,
                    onValueChange = { ui.manualPwdInput = it },
                    label = { Text("系统热点密码") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { engine.confirmSystemHotspotPwd() }) { Text("确认") }
        },
        dismissButton = {
            TextButton(onClick = { ui.manualPwdDialog = false }) { Text("取消") }
        },
    )
}

/** ④ 手动配网对话框：指引 + SSID/密码输入 + 确认 + 「打开系统热点设置」。 */
@Composable
private fun ManualPwdDialogV4(engine: BluelinkEngine) {
    val ui = engine.ui
    AlertDialog(
        onDismissRequest = { ui.manualPwdDialog = false },
        title = { Text("手动配网（④）") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "本机无法自动开启热点，请手动操作：",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "1. 在系统设置中打开「个人热点 / 便携式 Wi-Fi 热点」\n2. 将热点名称与密码填写如下并保存",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = ui.manualSsidInput,
                    onValueChange = { ui.manualSsidInput = it },
                    label = { Text("热点名称 (SSID)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = ui.manualPwdInput,
                    onValueChange = { ui.manualPwdInput = it },
                    label = { Text("热点密码") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { engine.openHotspotSettings() }) {
                    Text("打开系统热点设置")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { engine.confirmManualPwd() }) { Text("确认") }
        },
        dismissButton = {
            TextButton(onClick = { ui.manualPwdDialog = false }) { Text("取消") }
        },
    )
}

/** ③ L2 本地热点（13+）密码登记框：系统弹窗/通知已展示 SSID 与密码，请用户按系统弹窗回填密码（确认走 confirmLocalOnlyPwd）。 */
@Composable
private fun LocalOnlyPwdDialog(engine: BluelinkEngine) {
    val ui = engine.ui
    AlertDialog(
        onDismissRequest = { ui.localOnlyPwdDialog = false },
        title = { Text("本地热点密码登记（③）") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "系统已弹出本地热点通知/弹窗，展示 SSID 与密码：",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "SSID：${ui.localOnlySsid ?: "未知"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "请查看系统通知中的密码并填写如下：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = ui.manualPwdInput,
                    onValueChange = { ui.manualPwdInput = it },
                    label = { Text("热点密码（按系统弹窗）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { engine.confirmLocalOnlyPwd() }) { Text("确认") }
        },
        dismissButton = {
            TextButton(onClick = { ui.localOnlyPwdDialog = false }) { Text("取消") }
        },
    )
}

/** ③ LocalOnly 自测（v0.3.9，sdk 33+）密码登记框：系统弹窗/通知已展示 SSID 与密码（App 侧不可读），
 *  请用户按系统弹窗抄写回填（复用 manualPwdInput 输入；确认走 confirmLocalOnlySelfTestPwd）。 */
@Composable
private fun LoTestPwdDialog(engine: BluelinkEngine) {
    val ui = engine.ui
    AlertDialog(
        onDismissRequest = { ui.loTestPwdDialog = false },
        title = { Text("LocalOnly 密码登记（自测 ③）") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "系统已弹出本地热点通知/弹窗，展示 SSID 与密码（App 侧不可读）：",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "请按系统弹窗抄写密码并填写如下：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = ui.manualPwdInput,
                    onValueChange = { ui.manualPwdInput = it },
                    label = { Text("热点密码（按系统弹窗抄写）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { engine.confirmLocalOnlySelfTestPwd() }) { Text("确认") }
        },
        dismissButton = {
            TextButton(onClick = { ui.loTestPwdDialog = false }) { Text("取消") }
        },
    )
}

/** 接入失败对话框：提示原因 + 手动输入密码重试（WifiJoiner onFailed）。 */
@Composable
private fun JoinFailDialog(engine: BluelinkEngine) {
    val ui = engine.ui
    AlertDialog(
        onDismissRequest = { ui.joinFailDialog = false },
        title = { Text("接入失败") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "未能接入对方热点：${ui.joinFailReason ?: "未知原因"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = "若对方热点设有密码，可手动输入后重试：",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = ui.manualPwdInput,
                    onValueChange = { ui.manualPwdInput = it },
                    label = { Text("热点密码") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { engine.retryJoin(ui.manualPwdInput) }) { Text("重试接入") }
        },
        dismissButton = {
            TextButton(onClick = { ui.joinFailDialog = false }) { Text("取消") }
        },
    )
}

/** WRITE_SETTINGS 授权引导对话框（Android 8–10 接入路径）。 */
@Composable
private fun WriteSettingsDialog(engine: BluelinkEngine) {
    val ui = engine.ui
    AlertDialog(
        onDismissRequest = { ui.writeSettingsDialog = false },
        title = { Text("需要「修改系统设置」权限") },
        text = {
            Text(
                text = "Android 8–10 接入热点需 WRITE_SETTINGS 授权。\n请授权后返回，再点「重试接入」。",
                style = MaterialTheme.typography.bodySmall,
            )
        },
        confirmButton = {
            TextButton(onClick = { engine.openWriteSettings() }) { Text("去授权") }
        },
        dismissButton = {
            TextButton(onClick = { engine.retryJoin(ui.manualPwdInput) }) { Text("重试接入") }
            TextButton(onClick = { ui.writeSettingsDialog = false }) { Text("取消") }
        },
    )
}

/** 诊断日志弹窗：可滚动文本 + 刷新/复制/导出/清空（导出写 App 外部私有目录，无需存储权限）。 */
@Composable
private fun DiagnosticLogDialog(
    text: String,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onClear: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("诊断日志") },
        text = {
            Column {
                Text(
                    text = text.ifBlank { "（暂无日志）" },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onRefresh) { Text("刷新") }
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(text))
                        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                    }) { Text("复制全部") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { exportDiagnosticFile(context, text) }) { Text("导出文件") }
                    TextButton(onClick = onClear) { Text("清空") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

/** 导出诊断日志到 getExternalFilesDir(null)/diag_<yyyyMMdd_HHmmss>.txt，Toast 提示完整路径。 */
private fun exportDiagnosticFile(context: Context, text: String) {
    val dir = context.getExternalFilesDir(null)
    if (dir == null) {
        Toast.makeText(context, "外部存储不可用，导出失败", Toast.LENGTH_SHORT).show()
        return
    }
    val name = "diag_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".txt"
    val file = File(dir, name)
    try {
        file.writeText(text)
        Toast.makeText(context, "已导出: ${file.absolutePath}", Toast.LENGTH_LONG).show()
    } catch (e: Exception) {
        Toast.makeText(context, "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

/** T3 LocalSend 发送确认框：SAF 选文件后展示文件名/大小/目标；确认 → engine.confirmSend()（后台发送），取消 → engine.dismissSendDialog()。 */
@Composable
private fun SendConfirmDialog(engine: BluelinkEngine) {
    AlertDialog(
        onDismissRequest = { engine.dismissSendDialog() },
        title = { Text("发送文件") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "文件：${engine.pendingSendName ?: "未知"}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "大小：${formatFileSize(engine.pendingSendSize)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "目标：${engine.transportPeerIp.ifBlank { "未知对端" }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "确认后将经 LocalSend 直连传输（文件内容不经过 BLE/日志）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { engine.confirmSend() }) { Text("发送") }
        },
        dismissButton = {
            TextButton(onClick = { engine.dismissSendDialog() }) { Text("取消") }
        },
    )
}

/** 文件大小人类可读格式化（B/KB/MB/GB）。 */
private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024))
    bytes >= 1024L -> String.format(Locale.US, "%.2f KB", bytes / 1024.0)
    else -> "$bytes B"
}

/**
 * 尽力构造 OpenDocumentTree 初始目录 Uri（Downloads）。SAF 的 OpenDocumentTree 本身不支持指定初始
 * 目录——仅 Android 8+ 经 Intent EXTRA_INITIAL_URI 尽力（buildDocumentUri 失败回退 null 由系统默认）。
 */
private fun initialReceiveDirUri(): Uri? = try {
    DocumentsContract.buildDocumentUri(
        "com.android.externalstorage.documents",
        "primary:${Environment.DIRECTORY_DOWNLOADS}",
    )
} catch (e: Exception) {
    null
}
