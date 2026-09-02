package com.zglinus.bluelink.ui

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zglinus.bluelink.ble.HandshakeMessage
import com.zglinus.bluelink.ble.HandshakeProtocol
import com.zglinus.bluelink.diag.DiagLogger
import com.zglinus.bluelink.net.LanStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 主页面（docs/ui-design.md §4.1 两态左右布局，v0.5.0 UI-1）：
 * - 开屏（配对前）：顶部左右两栏——左「本端设备卡」1/3、右「对端扫描列表」2/3（LazyColumn
 *   上下滑动；长按或 × 清除失效设备）→ 其下「流程动画区」（配对/组网进行中显示：环形进度/脉冲 +
 *   阶段文案；无进行中流程收起——高度动画）→ 最下「时间流」（事件时间线：倒序 + 自动滚顶 + 上下滚动）。
 * - 配对/会话建立后：顶部等宽两栏（本端 1/2 | 对端 1/2 设备卡：alias/电量/网络 + 状态
 *   「已连接/接入/未连接」+ 重新扫描 + 组网/同网直连按钮）；宽度 1/3→1/2 切换用 Compose 动画
 *   （[animateFloatAsState] 驱动 Row weight，[animateDpAsState] 用于流程区脉冲环）。
 * - 抽屉（[ModalNavigationDrawer]）：头部（应用名/本机 alias）+ 入口（主页面/发送/接收/记录/
 *   设置/权限/关于）→ 设 [BluelinkUiState.currentPage]；非主页面先放骨架占位（「UI-2+ 实现」）。
 * - 控件搬迁：广播/扫描开关置顶栏；发送/收尾按钮进底部动作行；PIN 设置/信令自测/LocalOnly 自测
 *   移入抽屉设置页；发送/接收 SAF launcher、诊断/组网/PIN/发送确认弹层与设备详情弹层原样保留。
 *
 * 历史（v0.3.x）：本机状态卡「LocalOnly 自测」独立入口自 v0.5.0 起移入抽屉设置页；
 * sdk 33+ 密码登记框（[LoTestPwdDialog]）仍在 MainScreen 顶层渲染（不依赖设备详情弹层）。
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
    val engine = BluelinkEngine.current()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

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

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            AppDrawer(
                ui = ui,
                onNavigate = { page ->
                    ui.currentPage = page
                    scope.launch { drawerState.close() }
                },
            )
        },
    ) {
        Scaffold(
            topBar = {
                MainTopBar(
                    ui = ui,
                    advertisingWanted = advertisingWanted,
                    onAdvertisingWantedChange = onAdvertisingWantedChange,
                    onMenuClick = { scope.launch { drawerState.open() } },
                )
            },
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                // 抽屉路由：currentPage 0=主页面 1=发送 2=接收 3=记录 4=设置 5=权限 6=关于
                when (ui.currentPage) {
                    0 -> MainPage(
                        ui = ui,
                        onDeviceClick = onDeviceClick,
                        onRefreshNetwork = onRefreshNetwork,
                        onRequestPermissions = onRequestPermissions,
                        onSendFileClick = { sendFileLauncher.launch(arrayOf("*/*")) },
                        onChooseReceiveDir = { receiveDirLauncher.launch(initialReceiveDirUri()) },
                    )

                    1 -> PlaceholderPage("发送", ui)
                    2 -> PlaceholderPage("接收", ui)
                    3 -> LogPage(ui)
                    4 -> SettingsPage(ui, engine)
                    5 -> PlaceholderPage("权限", ui, onRequestPermissions)
                    6 -> PlaceholderPage("关于", ui)
                    else -> PlaceholderPage("未知页面", ui)
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

    // ③ LocalOnly 自测（v0.3.9）：sdk 33+ 密码登记框（入口已移至抽屉设置页；登记框仍在顶层渲染，
    // 经 BluelinkEngine.current() 取引擎，与设备详情弹层无关）
    engine?.let {
        if (ui.loTestPwdDialog) LoTestPwdDialog(it)
    }

    // T3 LocalSend：发送确认框（SAF 选文件后展示文件名/大小/目标；确认后后台发送）
    engine?.let {
        if (ui.sendDialog) SendConfirmDialog(it)
    }

    // v0.4.9 PIN 配对验证：对端输入框（被验证方可能未打开设备弹层——握手由对方发起，顶层渲染保证可见）
    engine?.let {
        if (ui.pinInputDialog) PinInputDialog(it)
    }
}

/** 顶栏：菜单（☰ 开抽屉）+ 标题 + 广播/扫描开关（置顶栏）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainTopBar(
    ui: BluelinkUiState,
    advertisingWanted: Boolean,
    onAdvertisingWantedChange: (Boolean) -> Unit,
    onMenuClick: () -> Unit,
) {
    TopAppBar(
        title = { Text(if (ui.currentPage == 0) "Bluelink" else pageTitle(ui.currentPage)) },
        navigationIcon = {
            IconButton(onClick = onMenuClick) { Text("☰") }
        },
        actions = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (advertisingWanted) "广播" else "停止",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Switch(
                    checked = advertisingWanted,
                    onCheckedChange = onAdvertisingWantedChange,
                )
            }
        },
    )
}

private fun pageTitle(page: Int): String = when (page) {
    1 -> "发送"
    2 -> "接收"
    3 -> "记录"
    4 -> "设置"
    5 -> "权限"
    6 -> "关于"
    else -> "Bluelink"
}

/** 主页面（两态左右布局）：横幅 → 两栏（1/3|2/3 ⇄ 1/2|1/2，weight 动画）→ 流程动画区 → 底部动作行 → 时间流。 */
@Composable
private fun MainPage(
    ui: BluelinkUiState,
    onDeviceClick: (DeviceEntry) -> Unit,
    onRefreshNetwork: () -> Unit,
    onRequestPermissions: () -> Unit,
    onSendFileClick: () -> Unit,
    onChooseReceiveDir: () -> Unit,
) {
    val engine = BluelinkEngine.current()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        if (!ui.permissionsGranted) {
            PermissionBanner(onRequestPermissions)
        } else if (!ui.btEnabled) {
            BluetoothOffBanner()
        }

        Spacer(Modifier.height(8.dp))

        // ---- 两态左右栏：开屏 1/3 | 2/3；配对后 1/2 | 1/2（Row weight 动画，宽度平滑切换） ----
        val selfWeight by animateFloatAsState(
            targetValue = if (ui.pairedView) 0.5f else 1f / 3f,
            label = "selfWeight",
        )
        Row(modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
        ) {
            SelfDeviceCard(
                ui = ui,
                onRefreshNetwork = onRefreshNetwork,
                modifier = Modifier
                    .weight(selfWeight)
                    .fillMaxHeight(),
            )
            Spacer(Modifier.width(8.dp))
            Crossfade(
                targetState = ui.pairedView,
                modifier = Modifier
                    .weight(1f - selfWeight)
                    .fillMaxHeight(),
                label = "peerPanel",
            ) { paired ->
                if (paired) {
                    PeerDeviceCard(ui = ui, engine = engine)
                } else {
                    ScanListPanel(ui = ui, onDeviceClick = onDeviceClick)
                }
            }
        }

        // ---- 流程动画区：配对/组网进行中显示（环形进度/脉冲 + 阶段文案）；无则收起（高度动画） ----
        FlowAnimationArea(ui)

        Spacer(Modifier.height(8.dp))

        // ---- 底部动作行：发送文件 / 收尾（结束组网·结束直连·关闭热点·断开网络）/ 接收保存位置 ----
        BottomActionRow(
            ui = ui,
            engine = engine,
            onSendFileClick = onSendFileClick,
            onChooseReceiveDir = onChooseReceiveDir,
        )

        Spacer(Modifier.height(8.dp))

        // ---- 时间流（事件时间线：倒序 + 自动滚顶 + 上下滚动） ----
        TimeFlowPanel(
            ui = ui,
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp),
        )
    }
}

/** 本端设备卡（左栏；配对前 1/3、配对后 1/2，宽度由外层 Row weight 动画驱动）。 */
@Composable
private fun SelfDeviceCard(
    ui: BluelinkUiState,
    onRefreshNetwork: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedCard(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "本端设备",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = ui.selfCard.alias.ifBlank { "本机" },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = ui.selfCard.model,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            ui.selfCard.batteryPct?.let {
                Text("电量 $it%", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                text = ui.selfCard.netText.ifBlank { "未连接网络" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (ui.advertising) "广播中 · 扫描中" else "广播已停止",
                style = MaterialTheme.typography.bodySmall,
                color = if (ui.advertising) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            ui.advertiserError?.let {
                Text(
                    text = "广播异常: $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            ui.scanError?.let {
                Text(
                    text = "扫描异常: $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = onRefreshNetwork,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("刷新网络") }
        }
    }
}

/** 对端扫描列表（右栏，配对前 2/3）：LazyColumn 上下滑动；点击设备握手；长按/× 清除失效设备。 */
@Composable
private fun ScanListPanel(
    ui: BluelinkUiState,
    onDeviceClick: (DeviceEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "对端扫描",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            if (ui.scanning) {
                Text(
                    text = "扫描中…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Text(
            text = "点击设备握手 · 长按或 × 清除失效设备",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (ui.devices.isEmpty()) {
            EmptyState()
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(
                    ui.devices.values.sortedBy { it.firstSeen },
                    key = { it.address },
                ) { entry ->
                    DeviceRow(
                        entry = entry,
                        onClick = { onDeviceClick(entry) },
                        onRemove = { BluelinkEngine.current()?.removeDevice(entry.address) },
                    )
                }
            }
        }
    }
}

/** 设备行：握手后显示别名/型号/MAC/RSSI/网络徽标/同网标记；握手前显示 MAC+RSSI。尾「×」或长按清除失效设备。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DeviceRow(entry: DeviceEntry, onClick: () -> Unit, onRemove: () -> Unit) {
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onRemove),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                val hs = entry.handshake
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = hs?.alias?.takeIf { it.isNotBlank() } ?: "未知设备",
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
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
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
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
            TextButton(onClick = onRemove) { Text("×") }
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

/** 对端设备卡（配对后右栏 1/2）：alias/电量/网络 + 状态「已连接/接入/未连接」+ 重新扫描 + 组网/同网直连按钮。 */
@Composable
private fun PeerDeviceCard(
    ui: BluelinkUiState,
    engine: BluelinkEngine?,
    modifier: Modifier = Modifier,
) {
    val peer = ui.selectedDevice
    OutlinedCard(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "对端设备",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                val status = peer?.statusText ?: "未连接"
                val statusColor = when (status) {
                    "接入" -> Color(0xFF2E7D32)
                    "已连接" -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = statusColor.copy(alpha = 0.15f),
                ) {
                    Text(
                        text = status,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor,
                    )
                }
            }
            Text(
                text = peer?.alias ?: "未知设备",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = peer?.model ?: "—",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            peer?.batteryPct?.let {
                Text("电量 $it%", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                text = peer?.netText ?: "网络未知",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            OutlinedButton(
                onClick = { engine?.rescan() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("重新扫描") }
            Spacer(Modifier.height(4.dp))
            val sameLan = peer?.sameLan == true
            Button(
                onClick = { engine?.startNetworking() },
                enabled = engine != null && !engine.pinRequired(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (sameLan) "同网免热点直连" else "组建临时局域网") }
            if (engine?.pinRequired() == true) {
                Text(
                    text = "需先完成 PIN 配对验证（对端输入配对码）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * 流程动画区：ui.netState / 组网阶段 / PIN 验证驱动——有进行中流程才显示
 * （环形进度 + 脉冲 + 阶段文案），无则收起（AnimatedVisibility 高度动画）。
 */
@Composable
private fun FlowAnimationArea(ui: BluelinkUiState) {
    // 有进行中流程（组网阶段/阶段文案/PIN 验证中）才显示；无则收起（高度动画）
    val visible = ui.netState != null || ui.pinVerifyActive
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val busy = ui.netActive || ui.pinVerifyActive
                if (busy) {
                    PulseRing()
                } else {
                    Text(
                        text = if (ui.netState?.startsWith("✅") == true) "✅" else "●",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "流程",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = ui.netState ?: flowFallbackText(ui),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (ui.netState?.startsWith("✅") == true || ui.pinVerifyOk) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        }
    }
}

/** 无 netState 时的流程文案兜底（PIN 验证进行中/已通过）。 */
private fun flowFallbackText(ui: BluelinkUiState): String = when {
    ui.pinVerifyActive -> "PIN 配对验证进行中…"
    ui.pinVerifyOk -> "PIN 验证已通过"
    else -> ""
}

/** 环形进度 + 脉冲：animateDpAsState 呼吸环 + 无限 alpha 脉冲内点。 */
@Composable
private fun PulseRing() {
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "pulseAlpha",
    )
    var big by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(600)
            big = !big
        }
    }
    val ringSize by animateDpAsState(
        targetValue = if (big) 30.dp else 24.dp,
        animationSpec = tween(600),
        label = "ringSize",
    )
    Box(contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            modifier = Modifier.size(ringSize),
            strokeWidth = 3.dp,
        )
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha), CircleShape),
        )
    }
}

/** 底部动作行：发送文件（入口）/ 收尾按钮（结束组网/结束直连/关闭热点/断开网络/取消）/ 接收保存位置。 */
@Composable
private fun BottomActionRow(
    ui: BluelinkUiState,
    engine: BluelinkEngine?,
    onSendFileClick: () -> Unit,
    onChooseReceiveDir: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // T3 发送入口：TRANSPORT（transportPeerIp 已记录）或已有握手对端时可点（同网直连场景 A8 落地后生效）
        val canSend = engine != null &&
            (engine.transportPeerIp.isNotBlank() || ui.devices.values.any { it.handshake != null })
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = onSendFileClick,
                enabled = canSend,
            ) { Text("发送文件") }
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (engine != null && engine.transportPeerIp.isNotBlank()) {
                    "目标: ${engine.transportPeerIp}"
                } else {
                    "组网就绪后可发送"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            // 收尾按钮（B4 温和收尾 / 组网进行中 / 传输进行中取消）
            when {
                engine != null && ui.netActive ->
                    TextButton(onClick = { engine.endNetworking() }) { Text("结束组网") }

                engine != null && ui.transferState?.startsWith("传输完成") == true && engine.sameLanDirectActive ->
                    TextButton(onClick = { engine.endSameLanDirect() }) { Text("结束直连") }

                engine != null && ui.transferState?.startsWith("传输完成") == true && ui.hotspotSideAfterTransfer ->
                    TextButton(onClick = { engine.closeHotspotAfterTransfer() }) { Text("关闭热点") }

                engine != null && ui.transferState?.startsWith("传输完成") == true ->
                    TextButton(onClick = { engine.disconnectNetworkAfterTransfer() }) { Text("断开网络") }

                engine != null && (ui.transferState?.startsWith("发送中") == true || ui.transferState?.startsWith("接收中") == true) ->
                    TextButton(onClick = { engine.cancelSend() }) { Text("取消") }

                else -> Unit
            }
        }
        ui.transferState?.let { state ->
            Text(
                text = state,
                style = MaterialTheme.typography.bodySmall,
                color = if (state.startsWith("发送完成") || state.startsWith("传输完成") || state.startsWith("✅")) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // v0.4.5 接收保存位置（SAF OpenDocumentTree 目录，不自建文件浏览器）
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

/** 时间流面板（主页面最下）：标题 + 条数 + [TimeFlowList]。 */
@Composable
private fun TimeFlowPanel(ui: BluelinkUiState, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "时间流",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${ui.eventLog.size} 条",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        TimeFlowList(ui, Modifier.fillMaxSize())
    }
}

/** 时间流列表：倒序（最新在顶）+ 自动滚顶；LazyColumn 上下滚动。 */
@Composable
private fun TimeFlowList(ui: BluelinkUiState, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(ui.eventLog.size) {
        if (ui.eventLog.isNotEmpty()) listState.scrollToItem(0)
    }
    if (ui.eventLog.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = "暂无事件记录",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        LazyColumn(
            state = listState,
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            itemsIndexed(ui.eventLog.asReversed()) { _, ev ->
                EventRow(ev)
            }
        }
    }
}

/** 事件行：时间（等宽）+ 色点（按 kind）+ 文案。 */
@Composable
private fun EventRow(ev: EventItem) {
    val color = when (ev.kind) {
        BluelinkEngine.EVT_HANDSHAKE -> MaterialTheme.colorScheme.primary
        BluelinkEngine.EVT_NETWORK -> Color(0xFF2E7D32)
        BluelinkEngine.EVT_TRANSFER -> Color(0xFF1565C0)
        BluelinkEngine.EVT_ERROR -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant // EVT_INFO / EVT_TEARDOWN
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = ev.ts,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(58.dp),
        )
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(color, CircleShape),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = ev.text,
            style = MaterialTheme.typography.bodySmall,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 记录页（抽屉 3）：全屏时间流（复用 [TimeFlowList]）。 */
@Composable
private fun LogPage(ui: BluelinkUiState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "记录",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { ui.currentPage = 0 }) { Text("返回") }
        }
        Spacer(Modifier.height(8.dp))
        TimeFlowList(ui, Modifier.fillMaxSize())
    }
}

/** 设置页（抽屉 4）：PIN 配对验证 + 信令自测 + LocalOnly 自测 + 诊断（原本机状态卡控件搬迁至此）。 */
@Composable
private fun SettingsPage(ui: BluelinkUiState, engine: BluelinkEngine?) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "设置",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { ui.currentPage = 0 }) { Text("返回") }
        }
        HorizontalDivider()
        if (engine == null) {
            Text(
                text = "引擎未就绪",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        // ============ v0.4.9 PIN 配对验证（从原本机状态卡移入抽屉设置页） ============
        Text("PIN 配对验证", style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "模式：${pinModeName(ui.pinMode)}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (ui.pairedCount > 0) "已配对 ${ui.pairedCount} 台" else "未配对",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            listOf(
                0 to "关",
                1 to "仅首次",
                2 to "每次",
            ).forEach { (m, label) ->
                OutlinedButton(
                    onClick = { engine.setPinMode(m) },
                    enabled = ui.pinMode != m,
                ) { Text(label) }
                Spacer(Modifier.width(6.dp))
            }
        }
        Text(
            text = "关=不校验；仅首次=首配后按指纹免验；每次=每会话必验（对端输入配对码）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ui.pinStatus?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
        ui.pinError?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        TextButton(onClick = { engine.clearPairedDevices() }) { Text("清除配对列表") }
        HorizontalDivider()

        // ============ 信令自测（验证包） ============
        Text("信令自测（验证包）", style = MaterialTheme.typography.titleSmall)
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
        TextButton(
            onClick = {
                if (ui.signalTestRunning) engine.stopSignalTest() else engine.startSignalTest()
            },
        ) {
            Text(if (ui.signalTestRunning) "停止信令自测" else "开始信令自测")
        }
        HorizontalDivider()

        // ============ ③ LocalOnly 自测（v0.3.9 独立入口） ============
        Text("LocalOnly 自测（③）", style = MaterialTheme.typography.titleSmall)
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
        HorizontalDivider()

        // ============ 诊断 ============
        Text("诊断", style = MaterialTheme.typography.titleSmall)
        TextButton(onClick = { ui.diagVisible = true }) { Text("打开诊断日志") }
    }
}

/** 骨架占位页（发送/接收/权限/关于等 UI-2+ 实现）：「UI-2+ 实现」文案 + 返回。 */
@Composable
private fun PlaceholderPage(
    title: String,
    ui: BluelinkUiState,
    extraAction: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "UI-2+ 实现（骨架先行）",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        extraAction?.let {
            Spacer(Modifier.height(16.dp))
            Button(onClick = it) { Text("去授权") }
        }
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = { ui.currentPage = 0 }) { Text("返回主页面") }
    }
}

/** 抽屉：头部（应用名/本机 alias）+ 入口列表（主页面/发送/接收/记录/设置/权限/关于）→ 设 currentPage。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppDrawer(ui: BluelinkUiState, onNavigate: (Int) -> Unit) {
    ModalDrawerSheet {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 20.dp),
        ) {
            Text("Bluelink", style = MaterialTheme.typography.headlineSmall)
            Text(
                text = "本机：${ui.selfCard.alias.ifBlank { Build.MODEL }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider()
        val entries = listOf(
            0 to "主页面",
            1 to "发送",
            2 to "接收",
            3 to "记录",
            4 to "设置",
            5 to "权限",
            6 to "关于",
        )
        entries.forEach { (page, label) ->
            NavigationDrawerItem(
                label = { Text(label) },
                selected = ui.currentPage == page,
                onClick = { onNavigate(page) },
                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
            )
        }
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
            .padding(top = 32.dp),
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
                    // A8 同网免热点直连：入口标签/文案区分「同网直连」（免热点秒连）与「热点组网」（异网仲裁+热点）
                    val sameLanEntry = entry.lanStatus == LanStatus.SAME_LAN
                    Text(if (sameLanEntry) "同网直连" else "临时局域网", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = if (sameLanEntry) {
                            "本机与对方在同一网络（SSID/子网一致），可免热点直连传输（秒连）。"
                        } else {
                            "本机与对方不在同一网络，可组建临时局域网后直连。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = { engine.startNetworking() },
                        enabled = !engine.pinRequired(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (sameLanEntry) "同网免热点直连" else "组建临时局域网") }
                    if (engine.pinRequired()) {
                        Text(
                            text = "需先完成 PIN 配对验证（对端输入配对码）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
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

            // ============ v0.4.9 PIN 配对验证（握手后：发起方展示配对码 / 对端输入回传 / 状态） ============
            if (engine != null) {
                val ui = engine.ui
                if (ui.pinVerifyActive || ui.pinShow != null || ui.pinVerifyOk || ui.pinStatus != null || ui.pinError != null) {
                    HorizontalDivider()
                    Text("PIN 配对验证", style = MaterialTheme.typography.titleSmall)
                    ui.pinShow?.let { pin ->
                        Text(
                            text = pin,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontFamily = FontFamily.Monospace,
                        )
                        Text(
                            text = "请对方在 Bluelink 中输入此配对码（自动回传比对，内容不落日志）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (ui.pinVerifyOk) {
                        Text(
                            text = "✅ PIN 验证通过，可组建局域网",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    ui.pinStatus?.let {
                        Text(text = it, style = MaterialTheme.typography.bodySmall)
                    }
                    ui.pinError?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    // 对端（非发起方，pinShow==null）：输入框被取消后可重新打开
                    if (ui.pinVerifyActive && ui.pinShow == null && !ui.pinInputDialog) {
                        OutlinedButton(onClick = {
                            ui.pinInput = ""
                            ui.pinInputDialog = true
                        }) { Text("输入配对码") }
                    }
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

/** v0.4.9 PIN 配对验证：对端输入框（本端为被验证方；输入 → 经信令回传 pin，等待发起方比对确认——对端不自己判）。 */
@Composable
private fun PinInputDialog(engine: BluelinkEngine) {
    val ui = engine.ui
    AlertDialog(
        onDismissRequest = { engine.cancelPinInput() },
        title = { Text("PIN 配对验证") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "对方要求 PIN 配对验证，请输入对方设备展示的配对码：",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = ui.pinInput,
                    onValueChange = { ui.pinInput = it.filter { c -> c.isDigit() }.take(8) },
                    label = { Text("配对码（数字）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                ui.pinError?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { engine.confirmPinInput() }) { Text("发送") }
        },
        dismissButton = {
            TextButton(onClick = { engine.cancelPinInput() }) { Text("取消") }
        },
    )
}

/** v0.4.9：PIN 验证模式 → 展示文本。 */
private fun pinModeName(mode: Int): String = when (mode) {
    1 -> "仅首次"
    2 -> "每次"
    else -> "关"
}
