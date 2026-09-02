package com.zglinus.bluelink.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zglinus.bluelink.ble.HandshakeMessage
import com.zglinus.bluelink.diag.DiagLogger
import com.zglinus.bluelink.net.LanStatus
import com.zglinus.bluelink.ui.theme.MetricTokens
import com.zglinus.bluelink.ui.theme.MotionTokens
import com.zglinus.bluelink.ui.theme.SpacingTokens
import com.zglinus.bluelink.ui.theme.extended
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 主页面（docs/ui-design.md §4.1 两态左右布局；v0.5.0 UI-1；v0.5.1a 实机微调；v0.5.4a 扁平化定稿；
 * v0.5.4b 去阴影 + surfaceContainer 容器分层；v0.5.5c edge-to-edge 沉浸（Scaffold 背景铺满 + insets 归 Scaffold，见下方 Scaffold 注释））：
 * v0.5.4a（基线）全 App 扁平化：无任何内容型卡片/阴影，内容平铺 surface 背景，区块靠留白与分组标题分层。
 * v0.5.4b 保持「无 elevation（不设阴影）/无边框」的扁平观感，改以 surfaceContainer 系列容器分层表达各内容区：
 * - surfaceContainerLowest（最贴近背景、弱层次）：设置分组容器、设备详情弹层正文、底部动作行/流程信息行（页内常规内容块）；
 * - surfaceContainerLow / surfaceContainer（主层次）：本端设备区、对端扫描列表、时间流/记录列表、横幅提示；
 * - surfaceContainerHigh（仅个别、强调浮起）：配对后对端设备区；
 * - 徽章（8dp 小件）保持 token 双通道（successContainer/primaryContainer/surfaceVariant 对，shapes.small=8）；
 *   浮层面板（ModalBottomSheet/AlertDialog/抽屉）本体由系统 MaterialTheme surface 提供（不动）。
 * 块级容器圆角 10dp（MaterialTheme.shapes.large = ShapeTokens.Modal，接线见 theme/BluelinkTheme.kt），
 * 小件（badge/按钮/输入框）保持 8dp（shapes.small）；容器一律 Surface(color=…) 不设 elevation（无阴影）、无边框。
 * - 布局（两态）：顶部左右两栏——配对前 1/3|2/3（左「本端设备区」、右「对端扫描列表」LazyColumn，
 *   长按或 × 清除失效设备），配对/会话建立后 1/2|1/2（左本端、右对端区：alias/电量/网络/状态 +
 *   重新扫描 + 组网/同网直连按钮）；宽度切换用 [animateFloatAsState] 驱动 Row weight；
 *   最下「时间流」（占屏 ~45%，倒序 + 自动滚顶 + 上下滚动）。
 * - 配网（v0.5.4a）：握手→PIN→组网进度一律在极简弹窗 [NetPairingDialog]（主页流程动画区已移除）；
 *   点设备/组网/PIN 校验触发 ui.pairingDialog；配对完成（pairedView 置位）或传输就绪自动关，
 *   中止/失败保留弹窗显错误态 + 关闭；时间流仍记录全部事件。
 * - 抽屉（[ModalNavigationDrawer]）：头部（应用名/本机 alias）+ 入口（主页面/发送/接收/记录/
 *   设置/权限/关于）→ 设 [BluelinkUiState.currentPage]；非主页面先放骨架占位（「UI-2+ 实现」）。
 * - 控件：广播/扫描开关置顶栏；发送/收尾按钮进底部动作行；PIN 设置/信令自测/LocalOnly 自测移入
 *   抽屉设置页；发送/接收 SAF launcher、诊断/组网/发送确认等弹层与设备详情弹层原样保留。
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

    // P2-4/F3 反馈通道：Snackbar（Toast → Snackbar）。宿主接 Scaffold snackbarHost；ui.snackbarMsg 为一次性
    // 信号（Engine.snack()/MainScreen 提示点写入），消费先复位 null（同文案可再触发），再经 hostState 展示。
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(ui.snackbarMsg) {
        val msg = ui.snackbarMsg ?: return@LaunchedEffect
        ui.snackbarMsg = null
        snackbarHostState.showSnackbar(msg)
    }

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
        // v0.5.5c edge-to-edge 沉浸（配合 MainActivity.onCreate 的 enableEdgeToEdge；背景铺满说明）：
        // Scaffold 根 Surface 默认 fillMaxSize + containerColor，铺满整窗（含状态栏/导航条下区域）——
        // enableEdgeToEdge 后系统栏区域即透出此背景色（浅 #FDFBFF / 深 #111318，随 BluelinkTheme 主题）。
        // 内容 insets 全部由 Scaffold 承担，页面内不重复加 statusBarsPadding/navigationBarsPadding（会双重留白）：
        // - 顶部：M3 TopAppBar（MainTopBar）自带 windowInsets = TopAppBarDefaults.windowInsets（含 statusBars），
        //   顶栏背景吃满状态栏、内容自动避开（无需再包 statusBarsPadding）；
        // - 底部：无 bottomBar 时 Scaffold contentWindowInsets（systemBarsForVisualComponents）的 bottom
        //   （= navigationBars 导航条 inset，与 navigationBarsPadding 同值）并入 innerPadding →
        //   底部动作行/时间流等自动不贴小白条，底部按钮不贴条；
        // - SnackbarHost 亦由 Scaffold 抬升至导航条之上；抽屉/底部弹层/AlertDialog 系统自带 insets 处理。
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            // containerColor 显式接线 = 默认值 MaterialTheme.colorScheme.background（整窗背景铺满，见上注释）
            containerColor = MaterialTheme.colorScheme.background,
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
            // F3/P2-4：复制/导出结果 → Snackbar（替换原 Toast）
            onNotify = { ui.showSnack(it) },
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

    // v0.5.4a 配网极简弹窗：握手→PIN→组网全进度在此（引擎置 ui.pairingDialog；配对完成/传输就绪自动关，
    // 中止/失败保留弹窗显错误态 + 「关闭」）。原独立 PinInputDialog 已并入本弹窗（对端内嵌单行输入）。
    engine?.let {
        if (ui.pairingDialog) NetPairingDialog(it)
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
            IconButton(onClick = onMenuClick) {
                // 图标控件须具名（audit A1/K3）：☰ 字形 + contentDescription，避免读屏读裸字形
                Text(
                    text = "☰",
                    modifier = Modifier.semantics { contentDescription = "打开菜单" },
                )
            }
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
                    // audit A2/P2-2：开关补状态描述语义（读屏可理解「广播开启/广播停止」）
                    modifier = Modifier.semantics {
                        stateDescription = if (advertisingWanted) "广播开启" else "广播停止"
                    },
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

/** 主页面（两态左右布局）：横幅 → 两栏（1/3|2/3 ⇄ 1/2|1/2，weight 动画）→ 底部动作行 → 时间流；配网进度在 [NetPairingDialog]（v0.5.4a）。 */
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
            .padding(horizontal = SpacingTokens.SpaceLg),
    ) {
        if (!ui.permissionsGranted) {
            PermissionBanner(onRequestPermissions)
        } else if (!ui.btEnabled) {
            BluetoothOffBanner()
        }

        Spacer(Modifier.height(SpacingTokens.SpaceMd)) // v0.5.1a-5：区块间距加大

        // ---- 两态左右栏：开屏 1/3 | 2/3；配对后 1/2 | 1/2（Row weight 动画，宽度平滑切换） ----
        val selfWeight by animateFloatAsState(
            targetValue = if (ui.pairedView) 0.5f else 1f / 3f,
            // v0.5.1a-3：宽度切换放慢（650ms FastOutSlowIn，避免「太快」观感）；P2-1：spec 进 MotionTokens，减动效档 tween(0) 直切
            animationSpec = MotionTokens.layoutSpec(ui.reduceMotion), // P2-1：减动效 → tween(0) 直切
            label = "selfWeight",
        )
        Row(modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
        ) {
            SelfDevicePane(
                ui = ui,
                onRefreshNetwork = onRefreshNetwork,
                modifier = Modifier
                    .weight(selfWeight)
                    .fillMaxHeight(),
            )
            Spacer(Modifier.width(SpacingTokens.SpaceMd)) // v0.5.1a-5：两栏间 gap ≥ 12dp
            Crossfade(
                targetState = ui.pairedView,
                animationSpec = MotionTokens.crossfadeSpec(ui.reduceMotion), // P2-1：减动效 → 直切
                modifier = Modifier
                    .weight(1f - selfWeight)
                    .fillMaxHeight(),
                label = "peerPanel",
            ) { paired ->
                if (paired) {
                    PeerDevicePane(ui = ui, engine = engine)
                } else {
                    ScanListPanel(ui = ui, onDeviceClick = onDeviceClick)
                }
            }
        }

        // v0.5.4a：配网进度（握手→PIN→组网）改由极简弹窗 NetPairingDialog 展示，主页流程动画区已移除；
        // 两栏与底部动作行之间保留区块留白
        Spacer(Modifier.height(SpacingTokens.SpaceMd))

        // ---- 底部动作行：发送文件 / 收尾（结束组网·结束直连·关闭热点·断开网络）/ 接收保存位置 ----
        BottomActionRow(
            ui = ui,
            engine = engine,
            onSendFileClick = onSendFileClick,
            onChooseReceiveDir = onChooseReceiveDir,
        )

        Spacer(Modifier.height(SpacingTokens.SpaceMd)) // v0.5.1a-5：时间流前留白加大

        // ---- 时间流（下半屏 ~45% 屏高；事件时间线：倒序 + 自动滚顶 + 上下滚动） ----
        TimeFlowPanel(
            ui = ui,
            // v0.5.1a-1：时间流占屏高 ~45%（原固定 160dp）；上半屏为顶部两栏 + 底部动作行，页面不额外滚动
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.45f),
        )
    }
}

/** 本端设备区（左栏；配对前 1/3、配对后 1/2，宽度由外层 Row weight 动画驱动；v0.5.4b surfaceContainerLow 容器分层）。 */
@Composable
private fun SelfDevicePane(
    ui: BluelinkUiState,
    onRefreshNetwork: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // v0.5.4b 映射：本端设备区＝次级/主层次块 → surfaceContainerLow；无 elevation（不设阴影）、无边框；
    // 块级圆角 10（MaterialTheme.shapes.large = ShapeTokens.Modal，theme 接线见 BluelinkTheme.kt）
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
    ) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(SpacingTokens.SpaceLg),
        // v0.5.5a-①：顶部卡内字段间距 12→8dp 回退紧凑（v0.5.1a-⑤ 曾 6→12 加大；窄栏 1/3 时压缩不溢出）
        verticalArrangement = Arrangement.spacedBy(SpacingTokens.SpaceSm),
    ) {
        // v0.5.5a-①：字段区 weight(1f)+verticalScroll 允许收缩——窄栏 1/3/大字号下字段超高只滚字段区，
        // 卡底操作按钮固定、不被 weight 挤压出可视区（1/3 与 1/2 两态均可见）；文本行均 maxLines+Ellipsis
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(SpacingTokens.SpaceSm),
        ) {
            Text(
                text = "本端设备",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
                Text(
                    text = "电量 $it%",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
        }
        TextButton(
            onClick = onRefreshNetwork,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("刷新网络") }
    }
    }
}

/** 对端扫描列表（右栏，配对前 2/3，v0.5.4b surfaceContainerLow 列表容器分层）：LazyColumn 上下滑动；点击设备握手；长按/× 清除失效设备。 */
@Composable
private fun ScanListPanel(
    ui: BluelinkUiState,
    onDeviceClick: (DeviceEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    // v0.5.4b 映射：对端扫描列表（列表容器保留）＝次级/主层次块 → surfaceContainerLow；
    // 无 elevation（不设阴影）、无边框；块级圆角 10（MaterialTheme.shapes.large = ShapeTokens.Modal）
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
    ) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(SpacingTokens.SpaceLg),
        verticalArrangement = Arrangement.spacedBy(SpacingTokens.SpaceSm), // v0.5.1a-5：标题/提示/列表间留白
    ) {
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
            text = "点击设备握手 · 移除失效设备",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (ui.devices.isEmpty()) {
            EmptyState(ui)
        } else {
            // 列表连续项（audit K1/P1-2）：去卡片化，行间 HorizontalDivider 分隔（M3 列表规范）
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                itemsIndexed(
                    ui.devices.values.sortedBy { it.firstSeen },
                    key = { _, entry -> entry.address },
                ) { index, entry ->
                    DeviceRow(
                        entry = entry,
                        onClick = { onDeviceClick(entry) },
                        onRemove = { BluelinkEngine.current()?.removeDevice(entry.address) },
                    )
                    if (index < ui.devices.size - 1) {
                        HorizontalDivider()
                    }
                }
            }
        }
    }
    }
}

/**
 * 设备行（列表连续项语义，audit K1/P1-2）：ListItem headline=别名、supporting=型号·MAC·RSSI·同网状态
 * （icon+label+token，P1-4）、leading=连接状态点（信号）、trailing=IconButton「×」（contentDescription 移除设备）；
 * 行点击=握手/详情不变，长按仍可清除（audit A5：× 为主入口、长按保留）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DeviceRow(entry: DeviceEntry, onClick: () -> Unit, onRemove: () -> Unit) {
    val hs = entry.handshake
    val alias = hs?.alias?.takeIf { it.isNotBlank() } ?: "未知设备"
    val modelMac = if (hs != null) {
        listOfNotNull(
            hs.model.takeIf { it.isNotBlank() },
            entry.displayMac,
            "${entry.rssi} dBm",
        ).joinToString(" · ")
    } else {
        "${entry.displayMac} · ${entry.rssi} dBm"
    }
    ListItem(
        // leading：连接/扫描状态点（已握手=primary 可进详情；扫描中=中性）
        leadingContent = {
            StatusDot(
                color = if (hs != null) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = alias,
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
        },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.SpaceXs)) {
                Text(
                    text = modelMac,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // 同网状态：icon（点）+ label + token（audit P1-4：色不单独表达状态）
                LanStatusLine(entry.lanStatus)
            }
        },
        trailingContent = {
            // 图标删除（audit K2/A1）：× TextButton → IconButton + contentDescription
            IconButton(onClick = onRemove) {
                Text(
                    text = "×",
                    modifier = Modifier.semantics { contentDescription = "移除设备" },
                )
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onRemove)
            // audit P2-3：可点击行补 role=Button（读屏按按钮播报；ListItem 行语义合并）
            .semantics { role = Role.Button },
    )
}

/** 网络徽标：同Wi-Fi / 蜂窝 / 未知（取自对方握手 net 字段）；icon（点）+ label + token 容器对（audit P1-4）。 */
@Composable
private fun NetworkBadge(hs: HandshakeMessage) {
    // 徽章容器/文字对（audit P0-2/P1-4）：同Wi-Fi=successContainer 对；蜂窝/未知=中性 surfaceVariant 对（蜂窝非警告，中性化）
    val (text, containerColor, contentColor) = when {
        hs.net.wifi -> Triple(
            "同Wi-Fi",
            MaterialTheme.extended.successContainer,
            MaterialTheme.extended.onSuccessContainer,
        )
        hs.net.cellular -> Triple(
            "蜂窝",
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
        else -> Triple(
            "未知",
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Surface(
        shape = MaterialTheme.shapes.small,
        color = containerColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = SpacingTokens.SpaceSm, vertical = 2.dp), // 徽章内部留白例外
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(color = contentColor)
            Spacer(Modifier.width(SpacingTokens.SpaceXs))
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor,
            )
        }
    }
}

/** 语义状态点（≥8dp，audit S6）：与 label/text 双通道表达状态，色不单独使用（audit P1-4）。 */
@Composable
private fun StatusDot(color: Color, modifier: Modifier = Modifier, size: Dp = MetricTokens.EventDot) {
    Box(
        modifier = modifier
            .size(size)
            .background(color, CircleShape),
    )
}

/** 同网判定行（icon+label+token）：同网=success / 异网/未知=中性（异网非警告，中性化，audit P0-2/P1-4）。 */
@Composable
private fun LanStatusLine(lanStatus: LanStatus, modifier: Modifier = Modifier) {
    val (label, color) = when (lanStatus) {
        LanStatus.SAME_LAN -> "同网" to MaterialTheme.extended.success
        LanStatus.DIFFERENT_NETWORK -> "异网" to MaterialTheme.colorScheme.onSurfaceVariant
        LanStatus.UNKNOWN -> "未知" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        StatusDot(color)
        Spacer(Modifier.width(SpacingTokens.SpaceXs))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = color,
        )
    }
}

/** 对端设备区（配对后右栏 1/2，v0.5.4b surfaceContainerHigh 强调分层）：alias/电量/网络 + 状态「已连接/接入/未连接」+ 重新扫描 + 组网/同网直连按钮。 */
@Composable
private fun PeerDevicePane(
    ui: BluelinkUiState,
    engine: BluelinkEngine?,
    modifier: Modifier = Modifier,
) {
    val peer = ui.selectedDevice
    // v0.5.4b 映射：配对后对端卡＝需强调/浮起块（仅个别） → surfaceContainerHigh；
    // 无 elevation（不设阴影）、无边框；块级圆角 10（MaterialTheme.shapes.large = ShapeTokens.Modal）
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
    ) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(SpacingTokens.SpaceLg),
        // v0.5.5a-①：顶部卡内字段间距 12→8dp 回退紧凑（窄栏/1/2 态压缩不溢出）
        verticalArrangement = Arrangement.spacedBy(SpacingTokens.SpaceSm),
    ) {
        // v0.5.5a-①：内容区（标题行/字段）weight(1f)+verticalScroll 允许收缩——超高只滚内容区，
        // 卡底操作按钮（重新扫描/直连）固定、不被 weight 挤压出可视区（1/2 态与窄屏均可见）
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(SpacingTokens.SpaceSm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "对端设备",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val status = peer?.statusText ?: "未连接"
                // 状态徽标三档 token 对（audit P1-4）：接入=successContainer / 已连接=primaryContainer / 其他=surfaceVariant；
                // icon（点）+ label + token 双通道，不以色 alone 表达状态（小件 8dp 徽章保留）
                val (statusContainer, statusContent) = when (status) {
                    "接入" -> MaterialTheme.extended.successContainer to MaterialTheme.extended.onSuccessContainer
                    "已连接" -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
                }
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = statusContainer,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = SpacingTokens.SpaceSm, vertical = 2.dp), // 徽章内部留白例外
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StatusDot(color = statusContent)
                        Spacer(Modifier.width(SpacingTokens.SpaceXs))
                        Text(
                            text = status,
                            style = MaterialTheme.typography.labelSmall,
                            color = statusContent,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
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
                Text(
                    text = "电量 $it%",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = peer?.netText ?: "网络未知",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        OutlinedButton(
            onClick = {
                // v0.5.1a-2：重新扫描 = 退出对端视图回设备选择列表（pairedView=false）+ 触发 rescan；状态进时间流
                ui.pairedView = false
                ui.selectedDevice = null
                engine?.logUiEvent(BluelinkEngine.EVT_INFO, "重新扫描中…")
                engine?.rescan()
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("重新扫描") }
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
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    }
}

// v0.5.4a：主页流程动画区已移除——配网进度（握手→PIN→组网）一律在极简弹窗 NetPairingDialog 展示；
// 本区块原职责（环形进度/脉冲/PIN 码行/组网短状态）由弹窗承接，时间流仍记录全部事件。

/**
 * 组网阶段 → 必要信息短文案（v0.5.1a-4 精简；v0.5.4a 起供配网极简弹窗 NetPairingDialog 状态短语用；
 * 技术细节保留在时间流/各弹窗）。只映射状态机/引擎常用阶段字串为短标签，未命中时回退通用文案。
 */
private fun shortNetStage(state: String): String = when {
    state.contains("中止") -> "组网中止"
    state.contains("超时") -> "连接超时"
    state.contains("失败") || state.contains("异常") -> "组网失败"
    state.contains("无法") -> "操作失败"
    state.contains("传输就绪") -> "传输就绪"
    state.contains("等待对方接入") || state.contains("加入") || state.contains("接入热点") -> "等待接入…"
    state.contains("等待对方确认") || state.contains("入网") || state.contains("确认") -> "等待确认…"
    state.contains("协商") || state.contains("仲裁") -> "协商中…"
    state.contains("权限") || state.contains("授权") -> "等待授权…"
    state.contains("热点") || state.contains("配网") || state.contains("登记") || state.contains("回填") -> "开热点…"
    state.contains("PIN") || state.contains("验证") -> "等待配对验证"
    state.contains("接入") -> "等待接入…"
    state.startsWith("✅") -> "传输就绪"
    else -> "进行中…"
}



/** 底部动作行（v0.5.4b surfaceContainerLowest 容器分层）：发送文件（入口）/ 收尾按钮（结束组网/结束直连/关闭热点/断开网络/取消）/ 接收保存位置。 */
@Composable
private fun BottomActionRow(
    ui: BluelinkUiState,
    engine: BluelinkEngine?,
    onSendFileClick: () -> Unit,
    onChooseReceiveDir: () -> Unit,
) {
    // v0.5.4b 映射：底部动作行（含流程信息行 transferState、接收目录行）＝页内常规内容块 → surfaceContainerLowest；
    // 无 elevation（不设阴影）、无边框；块级圆角 10（MaterialTheme.shapes.large = ShapeTokens.Modal）
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = MaterialTheme.shapes.large,
    ) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SpacingTokens.SpaceLg, vertical = SpacingTokens.SpaceSm),
        verticalArrangement = Arrangement.spacedBy(SpacingTokens.SpaceSm),
    ) { // v0.5.1a-5：动作行行距加大
        // T3 发送入口：TRANSPORT（transportPeerIp 已记录）或已有握手对端时可点（同网直连场景 A8 落地后生效）
        val canSend = engine != null &&
            (engine.transportPeerIp.isNotBlank() || ui.devices.values.any { it.handshake != null })
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = onSendFileClick,
                enabled = canSend,
            ) { Text("发送文件") }
            Spacer(Modifier.width(SpacingTokens.SpaceMd)) // v0.5.1a-5：按钮/文字不紧贴
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
}

/** 时间流面板（主页面最下，v0.5.4b surfaceContainerLow 列表容器分层）：标题 + 条数 + [TimeFlowList]。 */
@Composable
private fun TimeFlowPanel(ui: BluelinkUiState, modifier: Modifier = Modifier) {
    // v0.5.4b 映射：时间流＝列表容器（主层次） → surfaceContainerLow；无 elevation（不设阴影）、无边框；
    // 块级圆角 10（MaterialTheme.shapes.large = ShapeTokens.Modal）
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(SpacingTokens.SpaceLg),
        ) {
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
            Spacer(Modifier.height(SpacingTokens.SpaceSm)) // v0.5.1a-5：时间流标题与列表间距加大
            TimeFlowList(ui, Modifier.fillMaxSize())
        }
    }
}

/** 时间流列表：倒序（最新在顶）+ 自动滚顶；LazyColumn 上下滚动。 */
@Composable
private fun TimeFlowList(ui: BluelinkUiState, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    // audit M4/P2：仅在用户已在顶部附近（首可见项 ≤ 2）才自动滚顶——下翻读历史时不被拽回；
    // scrollToItem(0) 为瞬时跳转（无动画），减动效档无需另行处理
    LaunchedEffect(ui.eventLog.size) {
        if (ui.eventLog.isNotEmpty() && listState.firstVisibleItemIndex <= 2) {
            listState.scrollToItem(0)
        }
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
            verticalArrangement = Arrangement.spacedBy(SpacingTokens.SpaceXs),
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
    // kind → 语义色（audit P0-2/C4：传输蓝并入 primary 品牌蓝；网络/成功=success 扩展对；错误=error）
    val color = when (ev.kind) {
        BluelinkEngine.EVT_NETWORK -> MaterialTheme.extended.success
        BluelinkEngine.EVT_HANDSHAKE, BluelinkEngine.EVT_TRANSFER -> MaterialTheme.colorScheme.primary
        BluelinkEngine.EVT_ERROR -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant // EVT_INFO / EVT_TEARDOWN
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = SpacingTokens.SpaceXs), // 日志行距 2→4（audit S5）
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = ev.ts,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(MetricTokens.TimeColumnWidth),
        )
        Box(
            modifier = Modifier
                .size(MetricTokens.EventDot) // 色点 6→8dp（audit S6：语义图形 ≥8dp）
                .background(color, CircleShape),
        )
        Spacer(Modifier.width(SpacingTokens.SpaceSm))
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

/** 记录页（抽屉 3）：全屏时间流（复用 [TimeFlowList]；v0.5.4b surfaceContainerLow 列表容器分层）。 */
@Composable
private fun LogPage(ui: BluelinkUiState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(SpacingTokens.SpaceLg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "记录",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { ui.currentPage = 0 }) { Text("返回") }
        }
        Spacer(Modifier.height(SpacingTokens.SpaceSm))
        // v0.5.4b 映射：记录列表＝列表容器（主层次） → surfaceContainerLow；无 elevation（不设阴影）、无边框；
        // 块级圆角 10（MaterialTheme.shapes.large = ShapeTokens.Modal）
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.large,
        ) {
            TimeFlowList(
                ui,
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = SpacingTokens.SpaceSm),
            )
        }
    }
}

/** 设置页（抽屉 4；v0.5.4b 设置分组容器＝surfaceContainerLowest）：PIN 配对验证 + 信令自测 + LocalOnly 自测 + 诊断（原本机状态卡控件搬迁至此）。 */
@Composable
private fun SettingsPage(ui: BluelinkUiState, engine: BluelinkEngine?) {
    // 破坏性动作显式确认（audit K12/P1-1）：清除配对列表先弹确认框，确认后才执行
    var showClearPairedDialog by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(SpacingTokens.SpaceLg),
        verticalArrangement = Arrangement.spacedBy(SpacingTokens.SpaceSm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "设置",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { ui.currentPage = 0 }) { Text("返回") }
        }
        Spacer(Modifier.height(SpacingTokens.SpaceMd)) // v0.5.4b：页面标题与分组容器间距（区块 ≥12dp）
        // v0.5.4b 映射：设置分组容器＝页内常规内容块 → surfaceContainerLowest；
        // 无 elevation（不设阴影）、无边框；块级圆角 10（MaterialTheme.shapes.large = ShapeTokens.Modal）；
        // 组内各分节（PIN/信令自测/LocalOnly 自测/诊断）仍以 HorizontalDivider 分隔（audit K11 容器内分节）
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            shape = MaterialTheme.shapes.large,
        ) {
        Column(
            modifier = Modifier.padding(horizontal = SpacingTokens.SpaceLg, vertical = SpacingTokens.SpaceMd),
            verticalArrangement = Arrangement.spacedBy(SpacingTokens.SpaceSm),
        ) {
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
        // 单选互斥（audit K4/P1-1）：3×OutlinedButton → RadioButton 组（selected 语义 + 整行 48dp 触达）
        listOf(
            0 to "关",
            1 to "仅首次",
            2 to "每次",
        ).forEach { (m, label) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = ui.pinMode == m,
                        role = Role.RadioButton,
                        onClick = { engine.setPinMode(m) },
                    )
                    .padding(vertical = SpacingTokens.SpaceXs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = ui.pinMode == m, onClick = null)
                Spacer(Modifier.width(SpacingTokens.SpaceSm))
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                )
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
        // 破坏性动作（audit P1-1/K12）：TextButton → OutlinedButton error 色系；执行须经确认框（见文件尾）
        OutlinedButton(
            onClick = { showClearPairedDialog = true },
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
        ) { Text("清除配对列表") }
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
            // 状态行 icon+label（audit P1-4）：密码已登记(✅)加 success 语义点；色不单独表达状态
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (ui.localOnlyTestPasswordSet) {
                    StatusDot(color = MaterialTheme.extended.success)
                    Spacer(Modifier.width(SpacingTokens.SpaceSm))
                }
                Text(
                    text = info,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ui.localOnlyTestRunning) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
        HorizontalDivider()

        // ============ 诊断 ============
        Text("诊断", style = MaterialTheme.typography.titleSmall)
        TextButton(onClick = { ui.diagVisible = true }) { Text("打开诊断日志") }
        }
        }
    }

    // 清除配对列表确认框（audit K12/P1-1）：破坏性操作显式、防误触；确认才执行 engine.clearPairedDevices()
    if (showClearPairedDialog) {
        AlertDialog(
            onDismissRequest = { showClearPairedDialog = false },
            title = { Text("清除配对列表？") },
            text = {
                Text(
                    text = if (ui.pairedCount > 0) {
                        "将清除已配对的 ${ui.pairedCount} 台设备（含仅首次免验记忆），此操作不可撤销。"
                    } else {
                        "将清除全部配对记录（含仅首次免验记忆），此操作不可撤销。"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        engine?.clearPairedDevices()
                        showClearPairedDialog = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("清除") }
            },
            dismissButton = {
                TextButton(onClick = { showClearPairedDialog = false }) { Text("取消") }
            },
        )
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
            .padding(SpacingTokens.SpaceXl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(SpacingTokens.SpaceSm))
        Text(
            text = "UI-2+ 实现（骨架先行）",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        extraAction?.let {
            Spacer(Modifier.height(SpacingTokens.SpaceLg))
            Button(onClick = it) { Text("去授权") }
        }
        Spacer(Modifier.height(SpacingTokens.SpaceXl))
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
                .padding(horizontal = SpacingTokens.SpaceLg, vertical = 20.dp), // 20dp 非 4dp 节奏（审计未列，保持原值）
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

/** 权限提示行（未授权时置顶；v0.5.4b 横幅容器：surfaceContainer，无 elevation、无边框，块级圆角 10）。 */
@Composable
private fun PermissionBanner(onRequestPermissions: () -> Unit) {
    // v0.5.4b 映射：横幅＝提示块（主层次） → surfaceContainer；无 elevation（不设阴影）、无边框；
    // 块级圆角 10（MaterialTheme.shapes.large = ShapeTokens.Modal）
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SpacingTokens.SpaceLg, vertical = SpacingTokens.SpaceSm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(SpacingTokens.SpaceSm))
            Text(
                text = "需要权限: 蓝牙 + 位置",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRequestPermissions) { Text("去授权") }
        }
    }
}

/** 蓝牙未开提示（不自动开，仅提示；v0.5.4b 横幅容器：surfaceContainer，无 elevation、无边框，块级圆角 10）。 */
@Composable
private fun BluetoothOffBanner() {
    // v0.5.4b 映射：横幅＝提示块（主层次） → surfaceContainer；无 elevation（不设阴影）、无边框；
    // 块级圆角 10（MaterialTheme.shapes.large = ShapeTokens.Modal）
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SpacingTokens.SpaceLg, vertical = SpacingTokens.SpaceSm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(color = MaterialTheme.colorScheme.tertiary)
            Spacer(Modifier.width(SpacingTokens.SpaceSm))
            Text(
                text = "请在系统设置开启蓝牙",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 空态规格（文案 + 下一步动作；audit K10/P1-1 可操作空态）。 */
private data class EmptyStateSpec(
    val title: String,
    val body: String,
    val actionLabel: String,
    val onAction: () -> Unit,
)

/** 无设备空态（可操作引导，audit K10/P1-1）：按阻塞原因给文案 + 下一步按钮；不再只有纯文案。 */
@Composable
private fun EmptyState(ui: BluelinkUiState) {
    val context = LocalContext.current
    val engine = BluelinkEngine.current()
    val spec = when {
        // 权限未授：去授权 → 跳系统应用设置（可操作恢复路径）
        !ui.permissionsGranted -> EmptyStateSpec(
            "未授予扫描权限",
            "需要蓝牙 + 位置权限才能发现附近设备",
            "去授权",
            { openAppSettings(context) },
        )
        // 蓝牙未开：去开蓝牙 → 跳系统蓝牙设置（引擎无主动开蓝牙入口）
        !ui.btEnabled -> EmptyStateSpec(
            "蓝牙未开启",
            "开启蓝牙后即可扫描附近设备",
            "去开蓝牙",
            { openBluetoothSettings(context) },
        )
        // 扫描中但无设备：重扫重试（engine.rescan 重启扫描器）
        ui.scanning -> EmptyStateSpec(
            "等待周围设备…",
            "确保对方已打开 Bluelink 广播；仍无结果可重新扫描",
            "重新扫描",
            {
                engine?.setAdvertisingWanted(true)
                engine?.rescan()
            },
        )
        // 扫描已停：开启扫描（对应引擎开关 + rescan）
        else -> EmptyStateSpec(
            "扫描已停止",
            "开启扫描以发现附近设备",
            "开启扫描",
            {
                engine?.setAdvertisingWanted(true)
                engine?.rescan()
            },
        )
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = SpacingTokens.SpaceXxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = spec.title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(SpacingTokens.SpaceXs))
        Text(
            text = spec.body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(SpacingTokens.SpaceLg))
        OutlinedButton(onClick = spec.onAction) { Text(spec.actionLabel) }
    }
}

/**
 * 设备详情弹层：同网判定结果 + A5 组网入口/阶段/结束（v0.5.4b：弹层正文用
 * surfaceContainerLowest 分组容器分层；浮层面板本体由系统 ModalBottomSheet surface 提供，不动；
 * v0.5.4c：移除「握手详情」字段块——握手信息明细（网络/型号/协议/电量等）不再在弹层展示，
 * 握手→PIN→组网进度一律在顶层极简弹窗 NetPairingDialog）。
 *
 * A5 组网状态经 [BluelinkEngine.current()]（companion 单例，init 注册 / release 注销）读取；
 * engine 与 UI 同包无需 import。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceDetailSheet(
    entry: DeviceEntry,
    onDismiss: () -> Unit,
) {
    val engine = BluelinkEngine.current()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SpacingTokens.SpaceLg)
                .padding(bottom = SpacingTokens.SpaceXxl),
            verticalArrangement = Arrangement.spacedBy(SpacingTokens.SpaceSm),
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

            // v0.5.4b 映射：设备详情弹层正文（同网判定 / A5 组网分节；v0.5.4c 已移除握手详情分节）
            // ＝弹层内页级常规内容块 → surfaceContainerLowest；无 elevation（不设阴影）、无边框；块级圆角 10
            //（MaterialTheme.shapes.large = ShapeTokens.Modal）；浮层面板本体由系统 ModalBottomSheet surface 提供（不动）
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                shape = MaterialTheme.shapes.large,
            ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = SpacingTokens.SpaceLg, vertical = SpacingTokens.SpaceMd),
                verticalArrangement = Arrangement.spacedBy(SpacingTokens.SpaceSm),
            ) {
            Text("同网判定", style = MaterialTheme.typography.titleSmall)
            // 同网判定：icon（点）+ label + token（audit P1-4/C7：emoji 字形 → 语义点；色不单独表状态）
            val (lanLabel, lanDetail, lanColor) = when (entry.lanStatus) {
                LanStatus.SAME_LAN -> Triple("同网", "（同一子网，可直连传输）", MaterialTheme.extended.success)
                LanStatus.DIFFERENT_NETWORK -> Triple("异网", "（可组建临时局域网）", MaterialTheme.colorScheme.onSurfaceVariant)
                LanStatus.UNKNOWN -> Triple("未知", "（信息不足）", MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(lanColor)
                Spacer(Modifier.width(SpacingTokens.SpaceSm))
                Text(
                    text = "$lanLabel $lanDetail",
                    style = MaterialTheme.typography.bodyMedium,
                    color = lanColor,
                )
            }

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

            // v0.5.4a：PIN 配对验证阶段已并入顶层极简弹窗 NetPairingDialog（beginPinVerification 置
            // ui.pairingDialog，发起方大号码字 / 对端内嵌单行输入），详情弹层不再重复展示 PIN 面板
            // v0.5.4c：弹层不再展示握手信息明细（原「握手详情」分节已移除）；弹层只留设备名/状态/操作入口
            }
            }

            Spacer(Modifier.height(SpacingTokens.SpaceSm))
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
            Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.SpaceSm)) {
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
            Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.SpaceSm)) {
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
            Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.SpaceSm)) {
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
            Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.SpaceSm)) {
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
            Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.SpaceSm)) {
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

/** 诊断日志弹窗：可滚动文本 + 刷新/复制/导出/清空（导出写 App 外部私有目录，无需存储权限）。
 * 反馈通道（audit F3/P2-4）：复制/导出结果经 [onNotify] → Snackbar，不再直接 Toast。 */
@Composable
private fun DiagnosticLogDialog(
    text: String,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onClear: () -> Unit,
    onNotify: (String) -> Unit,
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
                        .heightIn(max = MetricTokens.DiagLogMaxHeight)
                        .verticalScroll(rememberScrollState()),
                )
                Spacer(Modifier.height(SpacingTokens.SpaceSm))
                Row(horizontalArrangement = Arrangement.spacedBy(SpacingTokens.SpaceSm)) {
                    TextButton(onClick = onRefresh) { Text("刷新") }
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(text))
                        onNotify("已复制")
                    }) { Text("复制全部") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(SpacingTokens.SpaceSm)) {
                    TextButton(onClick = { exportDiagnosticFile(context, text, onNotify) }) { Text("导出文件") }
                    TextButton(onClick = onClear) { Text("清空") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

/** 导出诊断日志到 getExternalFilesDir(null)/diag_<yyyyMMdd_HHmmss>.txt；结果经 onNotify → Snackbar（audit F3/P2-4）。 */
private fun exportDiagnosticFile(context: Context, text: String, onNotify: (String) -> Unit) {
    val dir = context.getExternalFilesDir(null)
    if (dir == null) {
        onNotify("外部存储不可用，导出失败")
        return
    }
    val name = "diag_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".txt"
    val file = File(dir, name)
    try {
        file.writeText(text)
        onNotify("已导出: ${file.absolutePath}")
    } catch (e: Exception) {
        onNotify("导出失败: ${e.message}")
    }
}

/** 跳系统应用设置页（空态「去授权」：权限未授的恢复路径，audit P1-1）。 */
private fun openAppSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    )
    context.startActivity(intent)
}

/** 跳系统蓝牙设置页（空态「去开蓝牙」；引擎无主动开启蓝牙入口，按系统设置引导）。 */
private fun openBluetoothSettings(context: Context) {
    context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
}

/** T3 LocalSend 发送确认框：SAF 选文件后展示文件名/大小/目标；确认 → engine.confirmSend()（后台发送），取消 → engine.dismissSendDialog()。 */
@Composable
private fun SendConfirmDialog(engine: BluelinkEngine) {
    AlertDialog(
        onDismissRequest = { engine.dismissSendDialog() },
        title = { Text("发送文件") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.SpaceSm)) {
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

/**
 * v0.5.4a 配网极简弹窗（替代 v0.5.1a 主页流程动画区）：握手→PIN→组网全过程极简单列——
 * 设备名一行（对端 alias 或「正在连接…」）+ 状态短语一行（正在握手…/PIN 阶段/开热点…/等待接入…/传输就绪）
 * + 细 LinearProgressIndicator（indeterminate，进行中）；PIN 阶段：发起方大号码字（ui.pinShow）/
 * 对端内嵌单行输入框（复用原 PinInputDialog 逻辑）；中止/失败保留弹窗显错误态（error 色文案 + 「关闭」）。
 * 开关由引擎收敛：openDevice/startNetworking/PIN 校验置 ui.pairingDialog=true；
 * 配对完成（pairedView 置 true）或传输就绪自动关（false）。弹窗为浮层面板（保留），内部不套任何内容卡片。
 */
@Composable
private fun NetPairingDialog(engine: BluelinkEngine) {
    val ui = engine.ui
    // 设备名：对端 alias（对端区/已握手设备）或「正在连接…」
    val peerAlias = ui.selectedDevice?.alias
        ?: ui.devices.values.firstOrNull { it.handshake != null }?.handshake?.alias
    val deviceName = peerAlias?.takeIf { it.isNotBlank() } ?: "正在连接…"

    val handshaking = ui.handshaking
    val pinActive = ui.pinVerifyActive
    // 终态错误（保留弹窗显错误态 + 关闭）：握手失败 / PIN 验证中止（非进行中残留错误）/ 组网中止·失败·超时
    val netErr = ui.netState?.takeIf { it.contains("中止") || it.contains("失败") || it.contains("超时") }
    val terminalErr: String? = when {
        ui.handshakeError != null && !handshaking -> "握手失败：${ui.handshakeError}"
        ui.pinError != null && !pinActive -> ui.pinError
        netErr != null -> netErr
        else -> null
    }

    AlertDialog(
        // 进行中不允许误关（back/点外无效）；仅终态错误可关闭（另见 confirmButton「关闭」）
        onDismissRequest = { if (terminalErr != null) ui.pairingDialog = false },
        title = {
            Text(
                text = deviceName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.SpaceMd)) {
                when {
                    // ---- PIN 阶段（进行中：发起方大号码字 / 对端内嵌输入） ----
                    pinActive -> PinStageColumn(ui, engine)
                    // ---- 握手进行中 ----
                    handshaking -> {
                        StatusPhrase("正在握手…")
                        ThinProgress()
                    }
                    // ---- 终态错误：中止/失败保留弹窗 ----
                    terminalErr != null -> StatusPhrase(terminalErr, error = true)
                    // ---- 组网进行中（短阶段文案；长细节在时间流） ----
                    ui.netState != null || ui.netActive -> {
                        StatusPhrase(shortNetStage(ui.netState ?: "进行中…"))
                        ThinProgress()
                    }
                    // ---- 兑底：已触发、回调未回执 ----
                    else -> {
                        StatusPhrase("正在连接…")
                        ThinProgress()
                    }
                }
            }
        },
        confirmButton = {
            if (terminalErr != null) {
                TextButton(onClick = { ui.pairingDialog = false }) { Text("关闭") }
            }
        },
    )
}

/** 状态短语一行（v0.5.4a）：进行中=onSurface；错误态=error 色文案。 */
@Composable
private fun StatusPhrase(text: String, error: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
    )
}

/** 细 LinearProgressIndicator（indeterminate；v0.5.4a 极简进度）。 */
@Composable
private fun ThinProgress() {
    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
}

/**
 * PIN 阶段（NetPairingDialog 内嵌，v0.5.4a）：发起方展示大号码字（ui.pinShow 数字部分）；
 * 对端（pinShow==null）内嵌单行输入框——复用原输入框逻辑（数字过滤、≤8 位、confirmPinInput 回传；
 * 取消后可「输入配对码」重开）。
 */
@Composable
private fun PinStageColumn(ui: BluelinkUiState, engine: BluelinkEngine) {
    // pinShow 为委托属性（by mutableStateOf）无法智能转换：先取局部再判空（pinShow 可能为 null）
    val pin = ui.pinShow
    Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.SpaceSm)) {
        if (pin != null) {
            // 发起方：大号码字（只取数字，Monospace 同宽易读）
            Text(
                text = pin.filter { it.isDigit() },
                style = MaterialTheme.typography.displayMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
            Text(
                text = "请对方在 Bluelink 中输入此配对码（自动回传比对，内容不落日志）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            // 对端：内嵌单行输入（复用原输入逻辑）
            if (ui.pinInputDialog) {
                OutlinedTextField(
                    value = ui.pinInput,
                    onValueChange = { ui.pinInput = it.filter { c -> c.isDigit() }.take(8) },
                    label = { Text("配对码（数字）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { engine.cancelPinInput() }) { Text("取消") }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { engine.confirmPinInput() }) { Text("发送") }
                }
            } else {
                TextButton(onClick = {
                    ui.pinInput = ""
                    ui.pinInputDialog = true
                }) { Text("输入配对码") }
            }
        }
        ui.pinStatus?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ui.pinError?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** v0.4.9：PIN 验证模式 → 展示文本。 */
private fun pinModeName(mode: Int): String = when (mode) {
    1 -> "仅首次"
    2 -> "每次"
    else -> "关"
}
