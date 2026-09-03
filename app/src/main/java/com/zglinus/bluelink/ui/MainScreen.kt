package com.zglinus.bluelink.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
// v0.5.13 md3-audit-2 K4/T2/N1：Material Icons（依赖 androidx.compose.material:material-icons-extended，走 composeBom）——
// 字形当图标（☰/×/›）换矢量 Icons；抽屉 4 项图标（History/Palette/Settings/Info）
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
// v0.5.14：☰ Menu 非 automirrored 图标——automirrored/filled 在 core 1.7.8 仅含
// ArrowBack/ArrowForward/ExitToApp/KeyboardArrow{L,R}/List/Send，Menu 基础图标在 filled（material-icons-core）
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.zglinus.bluelink.BuildConfig
import com.zglinus.bluelink.ble.HandshakeMessage
import com.zglinus.bluelink.diag.DiagLogger
import com.zglinus.bluelink.net.LanStatus
import com.zglinus.bluelink.ui.personalize.PersonalizePage
import com.zglinus.bluelink.ui.personalize.WallpaperBackdrop
import com.zglinus.bluelink.ui.personalize.WallpaperStore
import com.zglinus.bluelink.ui.theme.MetricTokens
import com.zglinus.bluelink.ui.transfer.TransferFileInfo
import com.zglinus.bluelink.ui.transfer.TransferRecord
import com.zglinus.bluelink.ui.transfer.TransferStatus
import com.zglinus.bluelink.ui.theme.MotionTokens
import com.zglinus.bluelink.ui.theme.SpacingTokens
import com.zglinus.bluelink.ui.theme.THEME_MODE_SYSTEM
import com.zglinus.bluelink.ui.theme.extended
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 主页面（docs/ui-design.md §4.1 两态左右布局；v0.5.0 UI-1；v0.5.1a 实机微调；v0.5.4a 扁平化定稿；
 * v0.5.4b 去阴影 + surfaceContainer 容器分层；v0.5.5c edge-to-edge 沉浸（Scaffold 背景铺满 + insets 归 Scaffold，见下方 Scaffold 注释）；
 * v0.5.6 UI1b-A 导航重排：顶栏左侧应用名「蓝鲸·X」/右侧 ☰（原左 ☰ 位移）；抽屉改 4 栏
 * （文件传输记录/个性化/设置/关于，PAGE_* 常量路由）；个性化页于 v0.5.7 UI1b-B 实现真页
 * （三壁纸槽/遮罩/取色/预览 + 主页面背景应用，见 ui/personalize/PersonalizePage.kt 与 WallpaperBackdrop.kt）；
 * v0.5.9 UI1b-C 设置页五区真页 + 关于页扩展：设置（安全/热点/传输/外观/权限检测，见 ui/SettingsPage.kt）
 * 与个性化已实现；关于页 v0.5.10 重做（新布局 + 隐藏收集日志两段式；旧「开发者」区自测/诊断入口删除，
 * 见下方 AboutPage）；深浅三态（themeMode）经 MainScreen 参数传入设置页外观区，全局联动见
 * MainActivity/BluelinkTheme（effectiveDark 判定源 Provide）。主页面仍为默认页（不列抽屉项）。
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
 * - 抽屉（[ModalNavigationDrawer]，v0.5.6 UI1b-A 4 栏重排）：头部（应用名「蓝鲸·X」/本机 alias）+
 *   入口（文件传输记录/个性化/设置/关于）→ 设 [BluelinkUiState.currentPage]（companion PAGE_* 常量，勿写数字）；
 *   主页面为默认页不列项（子页「返回」回主页面）；发送/接收入口已并入主页面操作行与设置页；
 *   权限检测并入设置页（v0.5.9 UI1b-C 权限检测区）；个性化已实现（v0.5.7 UI1b-B），关于为扩展页。
 * - v0.5.7 UI1b-B 主页面背景应用：根背景 Box（纯色 background 打底）→ WallpaperBackdrop（壁纸+遮罩，
 *   按当前系统深浅模式取槽）→ ModalNavigationDrawer/Scaffold（containerColor Transparent）；
 * - v0.5.8 UI1b-B2 主页面浮层化（修真机「背景不变」）+ 强调色运行态应用：HOME 内容容器（两态左右卡/
 *   底部动作行/时间流/横幅）与顶栏改半透明 M3 表面（surfaceContainer 系列 surface copy(alpha≈容器透明度，默认 0.80；v0.5.11 起运行态可调 WallpaperStore.containerAlpha())，
 *   以文字可读为准）浮于壁纸之上；壁纸根层 [RootWallpaperLayer] 仅 HOME 渲染（其它页/抽屉保持不透明
 *   表面，壁纸只垫主页面）；无壁纸（三槽全空）时背景回纯色，浮层化复合结果≈原色无副作用；
 *   强调色（accent）保存后经 MainActivity 主题 state → BluelinkTheme(accent) 运行态重算
 *   （个性化页新交互见 ui/personalize/PersonalizePage.kt）。
 * - 控件：广播/扫描开关置顶栏；发送/收尾按钮进底部动作行；PIN 配对验证/热点预设/接收目录/深浅三态/
 *   权限检测入设置页（ui/SettingsPage.kt）；信令自测/LocalOnly 自测/诊断入口曾入关于页开发者区（v0.5.9 UI1b-C，
 *   v0.5.10 随关于页重做整块删除）；发送/接收 SAF launcher、组网/发送确认等弹层与设备详情弹层原样保留。
 *
 * 历史（v0.3.x）：本机状态卡「LocalOnly 自测」独立入口自 v0.5.0 起移入抽屉设置页，v0.5.9 随设置页重构迁入关于页；
 * sdk 33+ 密码登记框（[LoTestPwdDialog]）曾于 MainScreen 顶层渲染——v0.5.10 随开发者区删除；
 * ③ LocalOnly 主路径登记框（[LocalOnlyPwdDialog]）保留（组网真路径，与自测无关）。
 */

/** v0.5.7 UI1b-B 起为根背景壁纸层：自订阅 ui.wallpaperTick（槽/遮罩改动信号），避免每次改动重排整个
 * MainScreen——只在此层与个性化页内重读 [WallpaperStore] 刷新；无壁纸时 WallpaperBackdrop 不绘制，
 * 露出外层 Box 纯色 background。
 * v0.5.8 UI1b-B2：壁纸只垫 HOME 主页面——非 HOME（LOG/个性化/设置/关于/抽屉打开）时经 graphicsLayer
 * alpha=0 隐藏（层保留在组合中，壁纸解码缓存不丢，避免反复进出主页重解码）；HOME 才显示。 */
@Composable
private fun RootWallpaperLayer(ui: BluelinkUiState) {
    val context = LocalContext.current
    val wallpaperStore = remember { WallpaperStore(context.applicationContext) }
    // 当前页（Compose state 读取；currentPage 切换自动重算）：HOME → 可见；其它页隐藏（壁纸只垫主页面）
    val wallpaperVisible = ui.currentPage == BluelinkUiState.PAGE_HOME
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = if (wallpaperVisible) 1f else 0f },
    ) {
        WallpaperBackdrop(
            store = wallpaperStore,
            tick = ui.wallpaperTick,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    ui: BluelinkUiState,
    advertisingWanted: Boolean,
    onAdvertisingWantedChange: (Boolean) -> Unit,
    onDeviceClick: (DeviceEntry) -> Unit,
    onRefreshNetwork: () -> Unit,
    onRequestPermissions: () -> Unit,
    // v0.5.8 UI1b-B2：个性化页「保存」回调（保存的强调色 ARGB Long？null=清除/未选）→ MainActivity 主题 state
    onAccentSaved: (Long?) -> Unit = {},
    // v0.5.9 UI1b-C：深浅三态（themeMode 当前值 + 变更回调）→ 设置页外观区；state 由 MainActivity 主题层持有（持久化）
    themeMode: Int = THEME_MODE_SYSTEM,
    onThemeModeChange: (Int) -> Unit = {},
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

    // v0.5.10 关于页：隐藏热区解锁态上提 MainScreen——AboutPage 随路由切页离开组合，解锁需本会话内保持
    var aboutLogUnlocked by remember { mutableStateOf(false) }

    // v0.5.12 md3-audit-2 FI2：个性化页未保存草稿标记（PersonalizePage 经 onDirtyChange 上报、进页/保存后
    // 复位 false）——离开个性化页（抽屉切页 / 顶栏应用名返回 / 页内返回）时若有未保存草稿 → Snackbar 提示
    var personalDirty by remember { mutableStateOf(false) }

    // v0.5.12 md3-audit-2 F4：关于页「收集日志」两段式状态提升——collecting / 起点偏移 / 待落盘文本 原为
    // AboutPage 局部 remember（随路由切页出组合即丢：再进入点「收集日志」需重新开始，窗口静默丢失）；
    // 上提 MainScreen（与 aboutLogUnlocked 同级）后跨页不丢；logExporting = 导出中 busy（IO/SAF 选目录
    // 期间收集行禁点 + 文案「导出中…」，见 onLogCollectClick）
    var logCollecting by remember { mutableStateOf(false) }
    var logCollectStart by remember { mutableStateOf(0L) }
    var logPendingText by remember { mutableStateOf<String?>(null) }
    var logExporting by remember { mutableStateOf(false) }

    // v0.5.12 md3-audit-2 FI2：路由离开统一出口——从个性化页离开且草稿未保存 → Snackbar「有未保存的改动」
    // （只提示不阻断离开；抽屉切页、顶栏应用名返回主页与 v0.5.13 系统返回键均经此，个性化页内「返回」在页内自行直判）
    fun navigateFrom(page: Int) {
        if (ui.currentPage == BluelinkUiState.PAGE_PERSONAL && personalDirty) {
            ui.showSnack("有未保存的改动")
        }
        ui.currentPage = page
    }

    // v0.5.13 md3-audit-2 系统返回键：路由在子页（LOG/个性化/设置/关于）时按返回 → 走统一出口 navigateFrom 回主页。
    // - 语义与现有返回路径一致：个性化页 dirty → Snackbar「有未保存的改动」后仍回主页（只提示不阻断离开）；
    //   其它子页直接回主页；主页（PAGE_HOME）onSubPage=false → 回调禁用（等同不注册）→ 保持系统默认返回退出；
    // - 弹窗层不误拦截：pairingDialog/sendDialog/detailDevice 等浮层在独立弹窗窗口内由系统弹窗栈/各自返回
    //   处理接管（AlertDialog/ModalBottomSheet 返回 = 收弹层），此处再按状态 enabled 排除兜底——有弹窗时不回主页；
    // - 抽屉拉开时不抢返回：抽屉自带返回收拢处理（M3 内部 BackHandler，本回调禁用让位）→ 先关抽屉再回主页。
    val onSubPage = ui.currentPage != BluelinkUiState.PAGE_HOME
    BackHandler(
        enabled = onSubPage && !ui.pairingDialog && !ui.sendDialog && ui.detailDevice == null && !drawerState.isOpen,
    ) {
        navigateFrom(BluelinkUiState.PAGE_HOME)
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

    // v0.5.11 UI1b-E 改③：HOME 浮层容器 alpha 运行态可调（原固定 0.80 常量已删）——
    // 读 WallpaperStore.containerTransparency（5–50%，默认 20 → alpha 0.80，同 v0.5.8d 规格）→ containerAlpha()；
    // ui.wallpaperTick 作 remember key：个性化「保存」（tick++）后重算重读 → 主页容器/顶栏 alpha 即时刷新
    val wallpaperCtx = LocalContext.current.applicationContext
    val containerStore = remember { WallpaperStore(wallpaperCtx) }
    val containerAlpha by remember(ui.wallpaperTick) {
        mutableStateOf(containerStore.containerAlpha())
    }

    // v0.5.12 md3-audit-2 F4：收集日志导出目录选择器上提 MainScreen（与状态提升配套——AboutPage 出组合
    // 不丢 launcher/待落盘文本；未自定义接收目录时弹 SAF 选落盘位置，Downloads 初始、不改接收目录设置）
    val logDirLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        val text = logPendingText
        logPendingText = null
        if (uri == null || text == null) {
            // 取消选择 / 无待存文本：结束导出 busy（本次放弃保存，不误报）
            logExporting = false
        } else {
            // 选定目录：MainScreen scope 落盘（不随 AboutPage 出组合取消 IO）；写完结束 busy + Toast 结果
            scope.launch {
                val msg = withContext(Dispatchers.IO) {
                    writeLogTextToTree(wallpaperCtx, uri, text)
                }
                logExporting = false
                // v0.5.13 md3-audit-2 F2：导出完成 Toast → ui.showSnack（统一 Snackbar 反馈通道；本回调在
                // MainScreen 顶层 scope，snackbarHost 同层可及——消费见 LaunchedEffect(ui.snackbarMsg)）
                ui.showSnack(msg)
            }
        }
    }

    // v0.5.12 md3-audit-2 F4：收集行点击状态机（开始记录 / 停止并导出；导出中 busy 禁点）。逻辑放 MainScreen
    // 层——scope（IO 不随 AboutPage 出组合取消）、目录选择器、状态同层；AboutPage 只展示 + 回调本函数
    fun onLogCollectClick() {
        if (logExporting) return // busy：导出中（含 SAF 选目录期间）禁点，防 IO 期间重入
        if (!logCollecting) {
            // 开始记录：起点偏移 = 当前已入缓冲条数（停止时导出起点后新增）；Snackbar 起始提示
            logCollecting = true
            logCollectStart = DiagLogger.entryCount()
            ui.showSnack("已开始收集，操作复现后再次点击保存")
        } else {
            // 停止并导出：脱敏 txt 落盘——自定义接收目录直接 SAF 写入；未自定义则弹目录选择器（本次保存）
            logCollecting = false
            val text = buildLogExportText(
                alias = ui.selfCard.alias.ifBlank { Build.MODEL },
                startCount = logCollectStart,
            )
            val tree = engine?.receiveDirUri()
            if (tree == null) {
                logPendingText = text
                logExporting = true // busy 档开启（SAF 选目录期间禁点 + 「导出中…」文案）
                logDirLauncher.launch(initialReceiveDirUri())
            } else {
                logExporting = true
                scope.launch {
                    val msg = withContext(Dispatchers.IO) {
                        writeLogTextToTree(wallpaperCtx, tree, text)
                    }
                    logExporting = false
                    // v0.5.13 md3-audit-2 F2：导出完成 Toast → ui.showSnack（同 logDirLauncher 回调，统一 Snackbar）
                    ui.showSnack(msg)
                }
            }
        }
    }

    // v0.5.7 UI1b-B 主页面背景应用（App 根背景）：RootWallpaperLayer（自订阅 ui.wallpaperTick）垫在
    // ModalNavigationDrawer/Scaffold 之下；根背景 Box = 纯色 background 打底（无壁纸回纯色现状）→
    // WallpaperBackdrop（壁纸 + surfaceVariant 遮罩，按当前深浅模式取槽：深→深槽/浅→浅槽，槽未设→统一槽兜底）；
    // v0.5.8 UI1b-B2：壁纸层仅 HOME 渲染（RootWallpaperLayer 内按 currentPage 隐藏，壁纸只垫主页面）；
    // Scaffold containerColor 保持 Transparent 透出背景；HOME 内容容器与顶栏改半透明浮层（默认 0.80，
    // v0.5.11 起运行态可调 —— containerAlpha 见上方重读块（WallpaperStore.containerAlpha()）），
    // 其它页（LOG/个性化/设置/关于）内容容器不透明不动；配网弹窗等浮层面板保持不透明。
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        RootWallpaperLayer(ui = ui)
        ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            AppDrawer(
                ui = ui,
                onNavigate = { page ->
                    // v0.5.12 md3-audit-2 FI2：抽屉切页统一走 navigateFrom（离开个性化页且有未保存草稿 → Snackbar）
                    navigateFrom(page)
                    scope.launch { drawerState.close() }
                },
            )
        },
    ) {
        // v0.5.5c edge-to-edge 沉浸（配合 MainActivity.onCreate 的 enableEdgeToEdge；背景铺满说明）：
        // Scaffold 根 Surface 默认 fillMaxSize，铺满整窗（含状态栏/导航条下区域）；v0.5.7 UI1b-B 起
        // containerColor 改 Transparent——整窗背景由外层根背景 Box（纯色 background 打底）+
        // WallpaperBackdrop（壁纸+遮罩）提供，enableEdgeToEdge 后系统栏区域透出壁纸氛围层。
        // 内容 insets 全部由 Scaffold 承担，页面内不重复加 statusBarsPadding/navigationBarsPadding（会双重留白）：
        // - 顶部：M3 TopAppBar（MainTopBar）自带 windowInsets = TopAppBarDefaults.windowInsets（含 statusBars），
        //   顶栏背景吃满状态栏、内容自动避开（无需再包 statusBarsPadding）；
        // - 底部：无 bottomBar 时 Scaffold contentWindowInsets（systemBarsForVisualComponents）的 bottom
        //   （= navigationBars 导航条 inset，与 navigationBarsPadding 同值）并入 innerPadding →
        //   底部动作行/时间流等自动不贴小白条，底部按钮不贴条；
        // - SnackbarHost 亦由 Scaffold 抬升至导航条之上；抽屉/底部弹层/AlertDialog 系统自带 insets 处理。
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            // v0.5.7 UI1b-B：容器背景 Transparent（壁纸层在 Scaffold 之下透出）；无壁纸时由外层 Box 纯色 background 提供背景色
            containerColor = Color.Transparent,
            topBar = {
                MainTopBar(
                    advertisingWanted = advertisingWanted,
                    reduceMotion = ui.reduceMotion, // v0.5.6b：广播呼吸按钮减动效分支（静止绿）
                    onAdvertisingWantedChange = onAdvertisingWantedChange,
                    onMenuClick = { scope.launch { drawerState.open() } },
                    // v0.5.8 UI1b-B2：仅 HOME 主页面顶栏浮层化（半透明透壁纸）；其它页保持不透明
                    floating = ui.currentPage == BluelinkUiState.PAGE_HOME,
                    // v0.5.11 UI1b-E 改①/改③：非主页时点应用名「蓝鲸·X」返回主页（主页时 null=不可点无操作）；
                    // 顶栏浮层 alpha 同主页容器透明度（containerAlpha = store 运行态重读值）
                    onAppNameClick = if (ui.currentPage != BluelinkUiState.PAGE_HOME) {
                        // v0.5.12 md3-audit-2 FI2：应用名返回主页同样过 navigateFrom（个性化页未保存草稿提示）
                        { navigateFrom(BluelinkUiState.PAGE_HOME) }
                    } else {
                        null
                    },
                    containerAlpha = containerAlpha,
                )
            },
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                // 抽屉路由（v0.5.6 UI1b-A 4 栏重排；取值 BluelinkUiState.PAGE_*，勿写数字）：
                // PAGE_HOME=主页面（默认） PAGE_LOG=文件传输记录 PAGE_PERSONAL=个性化（v0.5.7 UI1b-B 真页）
                // PAGE_SETTINGS=设置（v0.5.9 UI1b-C 五区真页，ui/SettingsPage.kt） PAGE_ABOUT=关于（v0.5.10 重做：应用名/版本/外链行/隐藏收集日志/致谢）
                // v0.5.13 md3-audit-2 M3：页身份切换套 Crossfade（fade 150–200ms → MotionTokens.crossfadeSpec
                // 的 200ms 档；减动效（ui.reduceMotion）→ tween(0) 直切——与主页两态面板同档复用，不再硬编码字面量）。
                // 跨页状态均在 MainScreen 层持有（aboutLogUnlocked/logCollecting 等收集状态机、personalDirty），
                // 页面切走/切回不丢（v0.5.12 F4/FI2 上提，见上）；Crossfade 过渡期间旧页短暂保留属正常。
                Crossfade(
                    targetState = ui.currentPage,
                    animationSpec = MotionTokens.crossfadeSpec(ui.reduceMotion),
                    label = "pageRoute",
                ) { page ->
                    when (page) {
                        BluelinkUiState.PAGE_HOME -> MainPage(
                            ui = ui,
                            containerAlpha = containerAlpha,
                            onDeviceClick = onDeviceClick,
                            onRefreshNetwork = onRefreshNetwork,
                            onRequestPermissions = onRequestPermissions,
                            onSendFileClick = { sendFileLauncher.launch(arrayOf("*/*")) },
                            onChooseReceiveDir = { receiveDirLauncher.launch(initialReceiveDirUri()) },
                        )

                        BluelinkUiState.PAGE_LOG -> LogPage(ui)
                        // v0.5.8 UI1b-B2：个性化页整页重做（无滚动一屏 + 右上保存）；保存回调上抛主题强调色
                        BluelinkUiState.PAGE_PERSONAL -> PersonalizePage(
                            ui = ui,
                            onSaved = onAccentSaved,
                            // v0.5.12 md3-audit-2 FI2：草稿 dirty 上报（离开页面前提示未保存改动用）
                            onDirtyChange = { personalDirty = it },
                        )
                        // v0.5.9 UI1b-C：设置页五区真页（ui/SettingsPage.kt：安全/热点/传输/外观/权限检测 + 深浅三态）
                        BluelinkUiState.PAGE_SETTINGS -> SettingsPage(
                            ui = ui,
                            engine = engine,
                            themeMode = themeMode,
                            onThemeModeChange = onThemeModeChange,
                        )
                        // v0.5.10：关于页重做（新布局 + 隐藏收集日志两段式，见下方 AboutPage）
                        // v0.5.12 md3-audit-2 F4：收集状态/导出状态机在 MainScreen（跨路由不丢 + 导出 busy）
                        BluelinkUiState.PAGE_ABOUT -> AboutPage(
                            ui = ui,
                            logUnlocked = aboutLogUnlocked,
                            onLogUnlocked = { aboutLogUnlocked = true },
                            collecting = logCollecting,
                            exporting = logExporting,
                            onCollectRowClick = { onLogCollectClick() },
                        )
                        else -> MainPage(
                            ui = ui,
                            containerAlpha = containerAlpha,
                            onDeviceClick = onDeviceClick,
                            onRefreshNetwork = onRefreshNetwork,
                            onRequestPermissions = onRequestPermissions,
                            onSendFileClick = { sendFileLauncher.launch(arrayOf("*/*")) },
                            onChooseReceiveDir = { receiveDirLauncher.launch(initialReceiveDirUri()) },
                        )
                    }
                }
            }
        }
    }
    } // ← 关闭根背景 Box（WallpaperBackdrop 层，v0.5.7 UI1b-B；包住 ModalNavigationDrawer）

    // v0.5.10：原「打开诊断日志」弹层（DiagnosticLogDialog）与 LocalOnly 自测密码登记框（LoTestPwdDialog）
    // 入口随旧 About「开发者」区一并删除（engine 侧自测/诊断方法保留，不面向 UI）。

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

/** 顶栏（v0.5.6 UI1b-A ☰ 导航槽位移 → v0.5.6c 单 Row 布局 → v0.5.6d ☰ 回左上 + 广播呼吸力量感重做）：
 * 整条顶栏并入 title 槽单 Row（fillMaxWidth），子项顺序（☰ 在最左 = 左上；其后标题；右侧组贴右缘）：
 *   [IconButton ☰]（开抽屉）→「蓝鲸·X」（titleLarge/语义色 primary）→ Spacer(weight(1f)) 吸余宽 →
 *   右侧「广播/停止」状态字 + [BroadcastBreathButton]（呼吸圆钮，开=绿底呼吸/关=灰底静止）。
 * v0.5.6b：广播开关 Switch → 呼吸圆钮（语义保持 role=Switch + stateDescription「广播开启/广播停止」）；
 * v0.5.6c：M3 1.4 无导航槽 TopAppBar 不把 actions 槽推到右缘（实机 ☰ 居中偏左）→ 弃用 actions 槽、
 *   标题与右侧组并入单 Row、标题后 Spacer(weight(1f)) 吸余宽（当时 ☰ 在 Row 最末 = 右上）；
 * v0.5.6d：☰ 由 Row 最末移至最左（☰ 放顶栏最左、其后标题，右侧保留广播钮）——侧边栏入口回左上。
 * v0.5.11 UI1b-E 改①/改③：标题「蓝鲸·X」非主页时可点返回主页（只包文本区，不吞 ☰/右侧钮的点击）；
 * 浮层 alpha 由固定 0.80 改运行态（containerAlpha 参数 = WallpaperStore.containerAlpha()）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainTopBar(
    advertisingWanted: Boolean,
    reduceMotion: Boolean,
    onAdvertisingWantedChange: (Boolean) -> Unit,
    onMenuClick: () -> Unit,
    // v0.5.8 UI1b-B2：true=HOME 主页面顶栏浮层化（containerColor 半透明透壁纸）；false=默认不透明 M3 表面
    floating: Boolean = false,
    // v0.5.11 UI1b-E 改①：非主页（LOG/个性化/设置/关于）时顶栏应用名点击回调（返回主页）；主页时 null=不可点
    onAppNameClick: (() -> Unit)? = null,
    // v0.5.11 UI1b-E 改③：HOME 浮层容器 alpha（WallpaperStore.containerAlpha() 重读值，保存后随 tick 刷新）
    containerAlpha: Float,
) {
    // v0.5.8 UI1b-B2：顶栏容器约同档 alpha 浮于壁纸之上（HOME）；其它页（LOG/个性化/设置/关于）不透明不动。
    // 未使用 scrollBehavior → 顶栏无 scrolled 态，只覆 containerColor 即全态生效（其余颜色走默认）
    val topBarColors = if (floating) {
        TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = containerAlpha),
        )
    } else {
        TopAppBarDefaults.topAppBarColors()
    }
    // v0.5.6d：Row 子项顺序 = [☰] 最左（顶栏左上）→ 标题 → Spacer(weight(1f)) 吸余宽 → 右侧广播组贴右缘
    TopAppBar(
        colors = topBarColors,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // v0.5.6d：☰ 放 Row 最左 = 顶栏左上（v0.5.6 UI1b-A 曾位移到右侧组最末=右上 → 回左上）；
                // v0.5.13 md3-audit-2 K4/T2：字形 ☰ → Material 图标 Menu
                // v0.5.14：Icons.Filled.Menu（core；无 automirrored 变体）——字形跨字体/读屏拼读不稳定；
                // icon-only 控件 contentDescription「打开菜单」保留具名（audit A1/K3）
                IconButton(onClick = onMenuClick) {
                    Icon(
                        imageVector = Icons.Filled.Menu,
                        contentDescription = "打开菜单",
                    )
                }
                // v0.5.6d：☰ 其后接标题「蓝鲸·X」（左 ☰ + 标题；语义色 primary 保持）
                // v0.5.11 UI1b-E 改①：非主页时应用名可点 → 返回主页（只包文本区：不吞 ☰/右侧钮的点击与水波纹）；
                // 水波纹收进文本区圆角（shapes.small），语义 contentDescription「返回主页」（主页时不可点）
                Text(
                    text = "蓝鲸·X",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (onAppNameClick != null) {
                        Modifier
                            .clip(MaterialTheme.shapes.small)
                            .clickable(onClick = onAppNameClick)
                            .semantics { contentDescription = "返回主页" }
                    } else {
                        Modifier
                    },
                )
                // v0.5.6c：标题（左）与右侧控件组之间 Spacer 占满剩余宽度 → 右侧组被推到屏幕右缘
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (advertisingWanted) "广播" else "停止",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                BroadcastBreathButton(
                    advertisingWanted = advertisingWanted,
                    reduceMotion = reduceMotion,
                    onAdvertisingWantedChange = onAdvertisingWantedChange,
                )
            }
        },
    )
}

/**
 * 广播呼吸圆钮（v0.5.6h 峰值 1.3 / 光晕贴钮 3dp 档——v0.5.6f 基态 30→20dp、v0.5.6e 幅度清晰基线；
 * v0.5.6b 开关圆钮化 / v0.5.6c 尺寸呼吸见文末历史）。
 * 视觉四要素（Compose 落地映射）：
 * 1) 幅度清晰（v0.5.6e 实机反馈 1.05 太隐 → 上调；v0.5.6f 基态 30→20dp；v0.5.6g 峰值 1.25→1.5；v0.5.6h 峰值 1.5→1.3）：
 *    呼吸 scale 1.0↔1.3——20dp 圆钮峰值直径 20→26dp（Δ6dp、双侧各 3dp：v0.5.6g 取 1.5=30dp 峰实测过大、用户要求
 *    回落 26dp → 1.3=26dp 较 1.25=25dp 更清晰、较 1.5=30dp 更收敛），峰值钮缘外扩 r=13dp ≪ 48dp 命中区半径 24dp
 *    （光晕 +3dp ≤r16dp 亦不越，见 3)）；
 *    仅向外膨胀、不缩于基态 → 无晃动感（区别于 v0.5.6c 曾 0.9↔1.08 双向晃动过大 → 已收窄，勿双向摆动）；
 * 2) 非对称节奏：keyframes + infiniteRepeatable，单周期 ~3.2s——膨胀段 ~1900ms（~60% 周期，
 *    FastOutSlowInEasing 缓出「到顶停住」）→ 收缩段 ~1300ms（~40% 周期，LinearOutSlowInEasing 近 ease-in
 *    「回落干脆」）；段端点均 1.0 → RepeatMode.Restart 无缝；
 * 3) 厚重感（阴影/光晕联动）：Compose 无 box-shadow → Modifier.drawBehind 画径向 glow（成功色 radial
 *    gradient：钮缘满 α 起向外衰减；不透明钮体盖住中心 → 呈沿钮缘外扩的辉光）——光晕贴钮外扩仅 ~3dp
 *    （v0.5.6h 光晕 5dp→3dp 贴钮更紧：glow 半径 = 钮视觉半径×displayScale + 3dp：基态钮 r10dp→glow r13dp、
 *    峰（×1.3）钮 r13dp→glow r16dp；不再取 48dp 命中区 size.minDimension×0.58 大扩散），径向渐变自钮缘
 *    满 α 向外的 3dp 带内线性衰减到 α=0（清晰贴钮亮环、不糊大）；模拟「隆起推开」的厚重暗示；关闭/静止态不画（无/极弱）；
 * 4) 点击蓄力回弹：interactionSource.collectIsPressedAsState() —— 按下 scale 瞬压 0.94（~80ms tween），
 *    松手 ~120ms 回弹 1.03（releaseBounce 窗口）再回落/交还呼吸 → 「蓄力→弹起→回落」。
 *
 * 节奏/幅度/光晕/点击映射表：
 * | 阶段 | scale | 时长 | easing |
 * | 膨胀（0→~60%） | 1.0→1.3 | ~1900ms | FastOutSlowIn（近 ease-out，缓出停止感） |
 * | 收缩（~60%→100%） | 1.3→1.0 | ~1300ms | LinearOutSlowIn（近 ease-in，干脆回落） |
 * | 按下 | →0.94 瞬压 | ~80ms | FastOutSlowIn |
 * | 松手回弹 | →1.03 再回落 | ~120ms | FastOutSlowIn |
 * | 光晕 | 贴钮外扩 3dp（钮半径×scale+3dp；基态 r13/峰 r16）、alpha 0.45 满至钮缘后向外衰减 | 随呼吸 | drawBehind radial |
 * | 关 / 减动效 | 静止 1.0、无 glow | — | — |
 *
 * 覆盖优先级：pressed / releaseBounce 窗口 → pressScale（0.94 / 1.03 覆盖优先）；空闲 → 呼吸 keyframes 直驱。
 * - 开：绿底 extended.success / onSuccess 白点 + 呼吸 + 光晕；减动效（ui.reduceMotion，P2-1/M1）：不创建
 *   呼吸循环 → 静止 1.0（按压手感保留——用户触发的瞬时反馈）；关：surfaceVariant 灰底静止、无光晕；
 * - 语义（audit A2/P2-2 原 Switch）：clickable(role=Role.Switch) + stateDescription「广播开启/广播停止」；
 * - 触达：外命中区 48dp（audit ≥48×48）保持不缩放（v0.5.6f 钮 30→20dp 不缩命中）；内层 20dp 圆钮 graphicsLayer scale（transformOrigin 中心）；
 *   v0.5.6h 峰值外扩 r13dp（直径 26dp）、光晕 r16dp 均 < 命中区半径 24dp → 呼吸/辉光全程不越命中区。
 *
 * v0.5.6b：广播 Switch → 圆钮（docs 原「广播/扫描开关」广播侧换控件，扫描随广播联动未动）；
 * v0.5.6c：呼吸由 alpha 0.5↔1 改尺寸缩放 0.9↔1.08（650ms 对称往返 = DurationPulse 1300ms 脉冲档）；
 * v0.5.6d：按上表重做为「力量感」参数（幅度/节奏/光晕/点击全换）。
 * v0.5.6e：实机反馈广播呼吸（1.0↔1.05）不够明显 → 幅度上调至 1.15（30dp 圆钮取「肉眼清晰」档：
 *   峰值直径 +4.5dp、双侧各 2.25dp，仍 ≪48dp 命中区；仅外扩无晃动）；径向 glow 同步增强（半径系数
 *   0.47→0.58 更外扩、alpha 系数 0.30→0.45 更饱满）；节奏/时长/点击蓄力回弹（0.94/1.03）保持不动。
 * v0.5.6f：用户要求钮最小（基态）30dp→20dp——AdvertiseKnob token 30→20dp（主改点，使用处同 token 单点同步）；
 *   峰值 scale 1.15→1.25（20×1.15=23dp Δ3dp 在更小钮上不明显 → 1.25=25dp Δ5dp、双侧各 2.5dp，
 *   幅度量级 ≥v0.5.6e 旧 4.5dp，仍 ≪48dp 命中区、不越顶栏；仅外扩无晃动）；48dp 命中区保持；
 *   glow 系数 0.58/0.45 不动（glowRadius 取 48dp 命中区 size.minDimension，不随钮体变 → 新基态 20dp 钮
 *   盖住的辉光中心更少、钮缘亮环反而更显饱满；静息 r≈27.8dp、峰值(×1.25) r≈34.8dp 软衰减仍落顶栏内
 *   不越界）；文字「广播/停止」在命中区左邻，钮变小 → 间距更宽不贴；点击蓄力回弹/关态/减动效保持不动。
 * v0.5.6g：用户确认广播钮基态 20dp、峰值 30dp（scale 1.25→1.5，20×1.5=30dp 峰、Δ10dp 双侧各 5dp；
 *   峰值钮缘外扩 r15dp ≪ 48dp 命中区 r24dp，光晕 +5dp ≤r20dp 亦不越 → 不越命中区/顶栏；节奏/时长/点击蓄力
 *   回弹（0.94/1.03）/关态/减动效保持不动）；光晕调小贴钮（外扩仅 ~5dp，不再大扩散）：弃 48dp 命中区
 *   size.minDimension×0.58 系数（旧静息 r≈27.8dp/峰 r≈34.8dp 大扩散）→ 改 glow 半径 = 钮视觉半径×displayScale
 *   + 5.dp（基态 10+5=15dp、峰 15+5=20dp；LocalDensity 转 px 直算，不随钮体尺寸换算），径向渐变自钮缘满 α
 *   向外 5dp 带衰减到 α=0（alpha 保持 0.45×displayScale——贴钮窄环取清晰不糊大；0.4 偏淡不用）。
 * v0.5.6h：用户要求广播钮峰值 30dp→26dp——scale 1.5→1.3（替代 1.5=30dp：20×1.3=26dp 峰、Δ6dp 双侧各 3dp；
 *   量级较 1.25=25dp 清晰、较 1.5=30dp 收敛（30dp 峰用户实测过大）；26dp 峰钮缘 r13dp ≪ 命中区 r24dp 不越）；
 *   光晕外扩 5dp→3dp 贴钮更紧（glow 半径 = 钮视觉半径×displayScale + 3dp：基态 10+3=13dp、峰 13+3=16dp，
 *   衰减带收窄至 3dp 亮环贴钮缘，峰 glow r16dp 仍 < 命中区 r24dp）；alpha 0.45/节奏/时长/点击蓄力回弹
 *   （0.94/1.03）/关态/减动效保持不动。
 */
@Composable
private fun BroadcastBreathButton(
    advertisingWanted: Boolean,
    reduceMotion: Boolean,
    onAdvertisingWantedChange: (Boolean) -> Unit,
) {
    // 绿/灰 token 对（双通道：底色语义对 + 中心点；相邻「广播/停止」文字为第三通道；随主题深浅切换）
    val backgroundColor = if (advertisingWanted) {
        MaterialTheme.extended.success // 开=绿（呼吸）
    } else {
        MaterialTheme.colorScheme.surfaceVariant // 关=灰（静止）
    }
    val dotColor = if (advertisingWanted) {
        MaterialTheme.extended.onSuccess // 中心白点（success/onSuccess 对）
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant // 点置灰
    }
    // ===== 呼吸循环（v0.5.6h 峰值 1.3 档：非对称 keyframes 1.0↔1.3（20dp 基态 → 26dp 峰）、单周期 ~3.2s；节奏/时长同 v0.5.6d）=====
    // 减动效（ui.reduceMotion，P2-1/M1）或关闭态 → 不创建 rememberInfiniteTransition（无无限动画节点）
    val breathState: State<Float>? = if (advertisingWanted && !reduceMotion) {
        val pulse = rememberInfiniteTransition(label = "broadcastBreath")
        pulse.animateFloat(
            initialValue = 1.0f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                // keyframes 非对称段：0%:1.0 → ~60%:1.3（~1900ms 膨胀，ease-out 缓出「到顶停住」）
                //             → 100%:1.0（~1300ms 收缩，ease-in 类「回落干脆」）；端点同 1.0 → Restart 无缝
                animation = keyframes {
                    durationMillis = MotionTokens.BreathPeriod // 全周期 ~3200ms
                    1.0f at 0 with LinearEasing
                    1.3f at MotionTokens.BreathExpand with MotionTokens.EasingBreathExpand // v0.5.6h 峰值 1.3（替代 v0.5.6g 1.5=30dp）：20dp 钮直径 20→26dp（Δ6dp、双侧各 3dp）——30dp 峰用户要求回落：1.3=26dp 较 1.25=25dp 清晰、较 1.5=30dp 收敛；峰值钮缘 r13dp ≪ 48dp 命中区 r24dp、光晕 +3dp ≤r16dp 亦不越命中区；仅外扩不缩于基态 → 无晃动
                    1.0f at MotionTokens.BreathPeriod with MotionTokens.EasingBreathContract
                },
                repeatMode = RepeatMode.Restart,
            ),
            label = "broadcastBreathScale",
        )
    } else {
        null // 静止 1.0（关 / 减动效 reduced-motion 分支）
    }
    val breathScale = breathState?.value ?: 1.0f

    // ===== 点击蓄力/回弹（v0.5.6d）：interactionSource 与 clickable 共用 → 可检测按压态 =====
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    // 刚松手 120ms 窗口：releaseBounce=true → pressScale 目标 1.03（从 0.94 弹起），随后交还呼吸循环
    var wasPressed by remember { mutableStateOf(false) }
    var releaseBounce by remember { mutableStateOf(false) }
    LaunchedEffect(pressed) {
        if (wasPressed && !pressed) { // 仅在「按下过 → 刚松手」触发回弹窗口（首次进入不触发）
            releaseBounce = true
            delay(MotionTokens.PressRelease.toLong()) // 窗口时长 = 回弹时长 120ms
            releaseBounce = false
        }
        wasPressed = pressed
    }
    // pressScale：按下 → 0.94（~80ms 瞬压）；松手窗口 → 1.03（~120ms 弹起）；空闲回 1f（不介入呼吸）
    val pressScale by animateFloatAsState(
        targetValue = when {
            pressed -> 0.94f // 点击瞬间压缩 scale(0.94)
            releaseBounce -> 1.03f // 松手迅速回弹峰值 scale(1.03)
            else -> 1f
        },
        animationSpec = tween(
            durationMillis = if (pressed) MotionTokens.PressCompress else MotionTokens.PressRelease,
            easing = MotionTokens.EasingLayout, // FastOutSlowIn：按下快速到位 / 回弹干脆
        ),
        label = "broadcastPressScale",
    )
    // 最终缩放：按压/回弹窗口 → pressScale（覆盖优先）；空闲 → 呼吸 keyframes 直驱（形态不被中间层扭曲）
    val displayScale = if (pressed || releaseBounce) pressScale else breathScale
    // v0.5.6h：光晕贴钮外扩 3dp 以 dp 直算（drawBehind 内需 px）——弃外层 48dp 命中区 ×0.58 的换算（会随钮体尺寸漂移）
    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .size(MetricTokens.AdvertiseKnobTouch) // 48dp 命中区（≥48×48 触达 audit）
            // v0.5.6h 厚重感光晕（放链首 → 绘于最底层，钮体/水波纹盖其上）：drawBehind 径向辉光——
            // 贴钮外扩仅 ~3dp（v0.5.6h 光晕 5dp→3dp 贴钮更紧，不再取 48dp 命中区 size.minDimension×0.58 大扩散）：
            // glow 半径 = 钮视觉半径×displayScale + 3dp（基态钮 r10dp → glow r13dp；峰 ×1.3 钮 r13dp → glow r16dp），
            // 径向渐变自钮缘满 α 向外 3dp 带内衰减到 α=0 → 清晰贴钮亮环不糊大（Compose 无 box-shadow，以 glow 模拟阴影）；
            // 关闭/减动效态不画（无/极弱）
            .drawBehind {
                if (advertisingWanted && !reduceMotion) { // 减动效/关闭：静止钮无 glow（装饰光晕仅随呼吸出现）
                    // v0.5.6h：glowRadiusDp = 钮直径/2 × displayScale + 3dp —— 基态 10×1.0+3=13dp、
                    // 峰值 10×1.3+3=16dp（外扩带恒定 3dp，不随 scale 拉伸）；峰 glow r16dp < 命中区 r24dp（48dp/2）
                    // → 不越命中区/顶栏；钮体不透明盖住中心满 α 区 → 可视亮环 = 钮缘外 3dp 衰减带
                    val knobRadiusPx = with(density) { (MetricTokens.AdvertiseKnob / 2f * displayScale).toPx() } // 钮视觉半径 px（随 displayScale：基态 10dp/峰 13dp）
                    val glowRadiusPx = with(density) { (MetricTokens.AdvertiseKnob / 2f * displayScale + 3.dp).toPx() } // 光晕半径 = 钮视觉半径 + 3dp 贴钮外扩（基态 13dp/峰 16dp）
                    val glowAlpha = 0.45f * displayScale // alpha 保持 0.45（放大时稍升）；贴钮窄环取清晰不糊大（0.4 偏淡不用）
                    drawCircle(
                        brush = Brush.radialGradient(
                            colorStops = arrayOf(
                                0f to backgroundColor.copy(alpha = glowAlpha), // 中心满 α（被钮体盖住，仅兜底）
                                // 钮缘处仍满 α → 自此向外 3dp 带线性衰减到 α=0：径向渐变「从中心钮缘向外衰减到 glow 半径」
                                (knobRadiusPx / glowRadiusPx).coerceIn(0f, 1f) to backgroundColor.copy(alpha = glowAlpha),
                                1f to Color.Transparent,
                            ),
                            center = center,
                            radius = glowRadiusPx,
                        ),
                        radius = glowRadiusPx,
                        center = center,
                    )
                }
            }
            .clickable(
                interactionSource = interactionSource, // 与 collectIsPressedAsState 共用 → 按压/回弹检测
                indication = LocalIndication.current, // 保留 M3 ripple（点击水波反馈）
                role = Role.Switch, // role=Switch：切换由 clickable 语义承接（原 Switch toggleable 语义位）
            ) {
                // 沿用既有广播开关回调：BluelinkRoot onAdvertisingWantedChange → engine.setAdvertisingWanted
                onAdvertisingWantedChange(!advertisingWanted)
            }
            .semantics {
                // v0.5.12 md3-audit-2 A3：开关名称补位（此前只有 role=Switch + stateDescription，读屏孤立
                // 节点无名；旁边「广播/停止」状态字为独立文本节点未绑定——名称 =「广播」）
                contentDescription = "广播"
                // v0.5.14：checked 语义行已删——androidx.compose.ui.semantics.checked 符号在当前 compose-bom
                // 下 unresolved（合并编译 778:17）；K2 双态位目标已由 role=Switch + contentDescription「广播」+
                // stateDescription「广播开启/广播停止」覆盖（名称/状态描述齐备；保留 clickable 点按语义不变）
                // audit A2/P2-2：状态描述保持原 Switch 语义（读屏「广播开启/广播停止」）
                stateDescription = if (advertisingWanted) "广播开启" else "广播停止"
            },
        contentAlignment = Alignment.Center,
    ) {
        // 圆钮视觉（20dp，v0.5.6f 基态 30→20dp）：v0.5.6c 呼吸改走 scale——graphicsLayer 默认 transformOrigin=中心 → 圆钮中心缩放；
        // scale 只作用于内层视觉圆钮，外层 48dp 命中区不缩放（触达 audit 恒定）；底色恒不透明（alpha 段已删）
        Box(
            modifier = Modifier
                .size(MetricTokens.AdvertiseKnob)
                .graphicsLayer {
                    // v0.5.6d：呼吸/按压合成后的最终缩放（transformOrigin 默认中心 → 圆钮中心缩放）
                    scaleX = displayScale
                    scaleY = displayScale
                }
                .clip(CircleShape)
                .background(backgroundColor),
            contentAlignment = Alignment.Center,
        ) {
            // 中心小圆点（8dp，语义图形 ≥8dp audit S6）
            Box(
                modifier = Modifier
                    .size(MetricTokens.AdvertiseKnobDot)
                    .background(dotColor, CircleShape),
            )
        }
    }
}

/** 主页面（两态左右布局）：横幅 → 两栏（1/3|2/3 ⇄ 1/2|1/2，weight 动画）→ 底部动作行 → 时间流；配网进度在 [NetPairingDialog]（v0.5.4a）。
 * v0.5.11 UI1b-E 改③：接收 containerAlpha（主页浮层容器 alpha 运行态值）透传横幅/两栏/动作行/时间流。 */
@Composable
private fun MainPage(
    ui: BluelinkUiState,
    onDeviceClick: (DeviceEntry) -> Unit,
    onRefreshNetwork: () -> Unit,
    onRequestPermissions: () -> Unit,
    onSendFileClick: () -> Unit,
    onChooseReceiveDir: () -> Unit,
    // v0.5.11 UI1b-E 改③：主页浮层容器 alpha（WallpaperStore.containerAlpha()，保存 tick++ 后即时刷新）
    containerAlpha: Float,
) {
    val engine = BluelinkEngine.current()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = SpacingTokens.SpaceLg),
    ) {
        if (!ui.permissionsGranted) {
            PermissionBanner(onRequestPermissions = onRequestPermissions, containerAlpha = containerAlpha)
        } else if (!ui.btEnabled) {
            BluetoothOffBanner(containerAlpha = containerAlpha)
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
                containerAlpha = containerAlpha,
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
                    PeerDevicePane(ui = ui, engine = engine, containerAlpha = containerAlpha)
                } else {
                    ScanListPanel(ui = ui, onDeviceClick = onDeviceClick, containerAlpha = containerAlpha)
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
            containerAlpha = containerAlpha,
        )

        Spacer(Modifier.height(SpacingTokens.SpaceMd)) // v0.5.1a-5：时间流前留白加大

        // ---- 时间流（下半屏 ~45% 屏高；事件时间线：倒序 + 自动滚顶 + 上下滚动） ----
        TimeFlowPanel(
            ui = ui,
            // v0.5.1a-1：时间流占屏高 ~45%（原固定 160dp）；上半屏为顶部两栏 + 底部动作行，页面不额外滚动
            // v0.5.13 md3-audit-2 AD1：加 260dp 高度封顶（timeFlowFill）——矮屏/横屏/大字号下防时间流
            // 挤爆上半屏主任务区（两栏/底部动作行被压没）；实机定稿档（0.45×可用高 ≤260dp）观感不变，
            // 仅当 45% 超出 260dp 时才收窄。不直接拼 fillMaxHeight+heightIn：fill 会把内层约束钉成精确高度，
            // 外层 heightIn 无法再收窄（见 TimeFlowPanel 下方 timeFlowFill 注释）。
            containerAlpha = containerAlpha,
            modifier = Modifier
                .fillMaxWidth()
                .timeFlowFill(),
        )
    }
}

/** 本端设备区（左栏；配对前 1/3、配对后 1/2，宽度由外层 Row weight 动画驱动；v0.5.4b surfaceContainerLow 容器分层）。 */
@Composable
private fun SelfDevicePane(
    ui: BluelinkUiState,
    onRefreshNetwork: () -> Unit,
    // v0.5.11 UI1b-E 改③：主页浮层容器 alpha（WallpaperStore.containerAlpha()，保存 tick++ 后即时刷新）
    containerAlpha: Float,
    modifier: Modifier = Modifier,
) {
    // v0.5.4b 映射：本端设备区＝次级/主层次块 → surfaceContainerLow；无 elevation（不设阴影）、无边框；
    // 块级圆角 10（MaterialTheme.shapes.large = ShapeTokens.Modal，theme 接线见 BluelinkTheme.kt）
    Surface(
        modifier = modifier,
        // v0.5.8 UI1b-B2 主页面浮层化：HOME 内容容器半透明浮于壁纸之上（无壁纸时复合≈原色无副作用）
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = containerAlpha),
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
    // v0.5.11 UI1b-E 改③：主页浮层容器 alpha（WallpaperStore.containerAlpha()，保存 tick++ 后即时刷新）
    containerAlpha: Float,
    modifier: Modifier = Modifier,
) {
    // v0.5.4b 映射：对端扫描列表（列表容器保留）＝次级/主层次块 → surfaceContainerLow；
    // 无 elevation（不设阴影）、无边框；块级圆角 10（MaterialTheme.shapes.large = ShapeTokens.Modal）
    Surface(
        modifier = modifier,
        // v0.5.8 UI1b-B2 主页面浮层化：HOME 内容容器半透明浮于壁纸之上（无壁纸时复合≈原色无副作用）
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = containerAlpha),
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
            // v0.5.13 md3-audit-2 K4/T2：字形 × → Material 图标 Icons.Filled.Close（字形跨字体/读屏拼读
            // 「乘号」不稳定）；icon-only 控件 contentDescription「移除设备」保留具名
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "移除设备",
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
    // v0.5.11 UI1b-E 改③：主页浮层容器 alpha（WallpaperStore.containerAlpha()，保存 tick++ 后即时刷新）
    containerAlpha: Float,
    modifier: Modifier = Modifier,
) {
    val peer = ui.selectedDevice
    // v0.5.4b 映射：配对后对端卡＝需强调/浮起块（仅个别） → surfaceContainerHigh；
    // 无 elevation（不设阴影）、无边框；块级圆角 10（MaterialTheme.shapes.large = ShapeTokens.Modal）
    Surface(
        modifier = modifier,
        // v0.5.8 UI1b-B2 主页面浮层化：配对后对端卡（强调层）半透明浮于壁纸之上
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = containerAlpha),
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
    // v0.5.11 UI1b-E 改③：主页浮层容器 alpha（WallpaperStore.containerAlpha()，保存 tick++ 后即时刷新）
    containerAlpha: Float,
) {
    // v0.5.4b 映射：底部动作行（含流程信息行 transferState、接收目录行）＝页内常规内容块 → surfaceContainerLowest；
    // 无 elevation（不设阴影）、无边框；块级圆角 10（MaterialTheme.shapes.large = ShapeTokens.Modal）
    Surface(
        modifier = Modifier.fillMaxWidth(),
        // v0.5.8 UI1b-B2 主页面浮层化：底部动作行半透明浮于壁纸之上
        color = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = containerAlpha),
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
            // v0.5.13 md3-audit-2 A8：传输状态行 liveRegion（发送/接收/完成/取消等阶段切换时 TalkBack
            // 主动播报，不依赖读屏焦点恰好停在行上）
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
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
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

/** v0.5.13 md3-audit-2 AD1：时间流面板高度上限（矮屏/横屏保护；实机定稿档 0.45×可用高 ≤260dp 不受影响）。 */
private val TimeFlowMaxHeight: Dp = 260.dp

/**
 * v0.5.13 md3-audit-2 AD1：时间流高度 = 可用高 ×0.45（原 fillMaxHeight(0.45f) 语义）但 ≤ [TimeFlowMaxHeight]（260dp）。
 *
 * 为什么不用 `fillMaxHeight(0.45f).heightIn(max=…)` 链：fillMaxHeight 的 fraction 布局会把内层约束强制为
 * 精确高度（min=max=0.45×maxH），heightIn 在其内层拿到的已是钉死的高度、无法再收窄（SizeIn 非 required 语义
 * 下子内容 fillMaxSize 仍会取到内层上限）——即字面上追加 heightIn 是空操作。这里改在单个 layout 内对「原始
 * 可用高」取 45% 后直接封顶 260dp，并把面板钉在最终高度上：矮屏（0.45×maxH ≤ 260dp）行为与原 fillMaxHeight
 * 完全一致，仅当 45% 超 260dp 才收窄 → 不推翻实机定稿观感。
 */
private fun Modifier.timeFlowFill(): Modifier = layout { measurable, constraints ->
    val cap = TimeFlowMaxHeight.roundToPx()
    val h = Math.round(constraints.maxHeight * 0.45f)
        .coerceIn(constraints.minHeight, cap)
    val placeable = measurable.measure(
        constraints.copy(minHeight = h, maxHeight = h),
    )
    layout(placeable.width, h) { placeable.place(0, 0) }
}

/** 时间流面板（主页面最下，v0.5.4b surfaceContainerLow 列表容器分层）：标题 + 条数 + [TimeFlowList]。 */
@Composable
private fun TimeFlowPanel(
    ui: BluelinkUiState,
    // v0.5.11 UI1b-E 改③：主页浮层容器 alpha（WallpaperStore.containerAlpha()，保存 tick++ 后即时刷新）
    containerAlpha: Float,
    modifier: Modifier = Modifier,
) {
    // v0.5.4b 映射：时间流＝列表容器（主层次） → surfaceContainerLow；无 elevation（不设阴影）、无边框；
    // 块级圆角 10（MaterialTheme.shapes.large = ShapeTokens.Modal）
    Surface(
        modifier = modifier,
        // v0.5.8 UI1b-B2 主页面浮层化：HOME 内容容器半透明浮于壁纸之上（无壁纸时复合≈原色无副作用）
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = containerAlpha),
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

/** 文件传输记录页（抽屉 1 / BluelinkUiState.PAGE_LOG；v0.5.14d 重做——由「全屏事件流」改为**真正的
 *  文件传输记录**，docs/ui-design.md §4.7「默认摘要 · 可展开」）：每行 = 一次传输会话摘要（对方别名 +
 *  收/发 n 文件 · 大小 + 状态徽标 + 时间），点击展开明细（文件清单/落盘路径/用时/速度/失败原因红字）；
 *  失败记录自动展开（异常可见优先）；空态「暂无传输记录」；记录持久化（重启保留）。
 *  数据源 [BluelinkUiState.transferRecords]（引擎经 [com.zglinus.bluelink.ui.transfer.TransferRecordStore]
 *  prefs JSON 持久化，上限 50 丢最旧；启动加载 + 每次传输会话结束落库刷新，倒序=最新在前）。
 *  主页时间流（[TimeFlowList]/eventLog）与其它页不受影响。 */
@Composable
private fun LogPage(ui: BluelinkUiState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(SpacingTokens.SpaceLg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "文件传输记录",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { ui.currentPage = BluelinkUiState.PAGE_HOME }) { Text("返回") }
        }
        Spacer(Modifier.height(SpacingTokens.SpaceSm))
        // v0.5.4b 映射：记录列表＝列表容器（主层次） → surfaceContainerLow；无 elevation（不设阴影）、无边框；
        // 块级圆角 10（MaterialTheme.shapes.large = ShapeTokens.Modal）
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.large,
        ) {
            TransferRecordList(
                records = ui.transferRecords,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = SpacingTokens.SpaceSm),
            )
        }
    }
}

/** 传输记录列表（倒序=最新在前；空态「暂无传输记录」；行 key=记录 id——新增记录不丢既有行展开态）。 */
@Composable
private fun TransferRecordList(records: List<TransferRecord>, modifier: Modifier = Modifier) {
    if (records.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = "暂无传输记录",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        LazyColumn(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(SpacingTokens.SpaceXs),
        ) {
            items(records, key = { it.id }) { record ->
                TransferRecordRow(record)
            }
        }
    }
}

/** 单条传输记录行：摘要（别名 + 收/发文件数·大小 + 时间 + 状态徽标）可点击展开/收起；明细含文件清单/
 *  落盘路径/用时/速度/失败原因红字。失败（FAILED）记录默认展开（异常可见优先；可手动收起；
 *  页面切走再回时失败记录重新默认展开）。 */
@Composable
private fun TransferRecordRow(record: TransferRecord) {
    var expanded by remember(record.id) { mutableStateOf(record.status == TransferStatus.FAILED) }
    // 状态徽标 token 对（audit P1-4 双通道）：成功=success 对 / 失败=error 对 / 取消=中性 surfaceVariant 对
    val (badgeText, badgeContainer, badgeContent) = when (record.status) {
        TransferStatus.OK -> Triple(
            "已完成",
            MaterialTheme.extended.successContainer,
            MaterialTheme.extended.onSuccessContainer,
        )
        TransferStatus.FAILED -> Triple(
            "失败",
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
        )
        TransferStatus.CANCELLED -> Triple(
            "已取消",
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = SpacingTokens.SpaceXs),
    ) {
        // ---- 摘要行（点击展开/收起） ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .clickable { expanded = !expanded }
                .padding(horizontal = SpacingTokens.SpaceSm, vertical = SpacingTokens.SpaceXs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                // 对方别名 + 展开箭头（▸/▾）
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = record.peerAlias.ifBlank { "未知设备" },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = if (expanded) "▾" else "▸",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(2.dp))
                // 时间 · 收/发 n 文件 · 总大小
                Text(
                    text = "${transferTimeText(record.startedAt)} · " +
                        (if (record.isReceive) "接收" else "发送") +
                        " ${record.fileCount} 个文件 · ${formatFileSize(record.totalBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(SpacingTokens.SpaceSm))
            // 状态徽标（8dp 小件档：MaterialTheme.shapes.small）
            Surface(shape = MaterialTheme.shapes.small, color = badgeContainer) {
                Text(
                    text = badgeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = badgeContent,
                    modifier = Modifier.padding(horizontal = SpacingTokens.SpaceSm, vertical = 2.dp),
                )
            }
        }
        // ---- 展开明细 ----
        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = SpacingTokens.SpaceSm, end = SpacingTokens.SpaceSm, bottom = SpacingTokens.SpaceXs),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                // 文件清单（名称/大小/结果标记 ✓✗）
                record.files.forEach { f ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = f.name.ifBlank { "(未知名)" },
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(SpacingTokens.SpaceSm))
                        Text(
                            text = formatFileSize(f.bytes),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(SpacingTokens.SpaceSm))
                        Text(
                            text = if (f.ok) "✓" else "✗",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (f.ok) MaterialTheme.extended.success else MaterialTheme.colorScheme.error,
                        )
                    }
                }
                // 落盘路径（接收侧；发送侧无此字段）
                if (record.dirPath != null) {
                    Text(
                        text = "落盘 ${record.dirPath}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 用时（+ 平均速度；仅成功记录含速度）
                Text(
                    text = buildString {
                        append("用时 ")
                        append(formatDuration(record.durationMs ?: (record.endedAt - record.startedAt).coerceAtLeast(0L)))
                        record.peerSpeedBps?.let { append(" · 速度 ${formatFileSize(it)}/s") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // 失败/取消原因红字（异常可见优先）
                if (record.failReason != null) {
                    Text(
                        text = record.failReason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/** 记录时间显示（HH:mm；与事件时间流同格式）。 */
private fun transferTimeText(epochMs: Long): String =
    SimpleDateFormat("HH:mm", Locale.US).format(Date(epochMs))

/** 用时人类可读：<1s 向上取整为 1s；≥1 分钟 → 「X分Ys」。 */
private fun formatDuration(ms: Long): String {
    val secs = ((ms.coerceAtLeast(0L)) + 999L) / 1000L
    return if (secs >= 60L) {
        "${secs / 60}分${secs % 60}秒"
    } else {
        "${secs}s"
    }
}

// 设置页（抽屉 3 / PAGE_SETTINGS）自 v0.5.9 UI1b-C 起移至 ui/SettingsPage.kt 实现：
// 五区（安全 / 热点 / 传输 / 外观 / 权限检测）分组容器 + 深浅三态联动（themeMode 经 MainScreen 参数传入）；
// 旧页 PIN 配对验证区能力并入新页安全区（PinStore 直驱：模式三态/已配对列表/重置指纹/清空配对），
// 信令自测 / LocalOnly 自测 / 诊断三块曾迁入关于页「开发者」区（v0.5.9 UI1b-C；v0.5.10 整块删除，见下方 AboutPage）。

// 个性化页（抽屉 2 / PAGE_PERSONAL）自 v0.5.7 UI1b-B 起移至 ui/personalize/PersonalizePage.kt 实现：
// 三壁纸槽（统一/深色/浅色）+ 遮罩滑块（0–80%）+ 取色区（API27+ 从壁纸取色 + 8 色板 + 选中色 chip）+
// 预览块（按当前模式取槽渲染：壁纸+遮罩）；路由见 MainScreen 上方 when(ui.currentPage) 分支（本文件不再保留实现）。

// ==================== v0.5.10 关于页（AboutPage 重做；旧「开发者」区删除） ====================
// 演变：v0.5.6 UI1b-A 占位页 → v0.5.9 UI1b-C（基础信息 + 开发者区：信令自测/LocalOnly 自测/诊断入口迁自旧设置页）
// → v0.5.10 重做：应用名居中 + 版本号（buildConfig 已开启，引 BuildConfig.VERSION_NAME）+
// 行式条目（GitHub / 项目地址 / 反馈，ACTION_VIEW 外链）+ 隐藏热区五连击解锁「收集日志」+
// 「收集日志」两段式导出（脱敏 txt 落盘接收目录体系）+ 致谢区。旧开发者区三块整块删除，
// 对应 MainScreen 顶层 DiagnosticLogDialog / LoTestPwdDialog 渲染一并清理；engine 自测方法保留不面向 UI。
// → v0.5.10c：连击目标由隐藏热区迁移至版本号行条目——淡条热区（60dp 槽位）整段删除，正常形态链接 Surface
//   与致谢 Surface 直接相邻（无留白）；解锁「收集日志」卡片置链接 Surface 正下方槽位，链接区条件压缩上移、
//   按钮首屏可见。热区最小高度等热区专用常量与淡条一并删除（本文件无残留）。

/** v0.5.10c 关于页：版本号行连点解锁参数（相邻点击间隔 ≤ [ABOUT_VERSION_TAP_WINDOW_MS]，超时计数清零；连点 5 次解锁；v0.5.10c 起连击目标为版本号行条目，原隐藏热区废弃）。 */
private const val ABOUT_VERSION_TAP_WINDOW_MS = 2000L
private const val ABOUT_VERSION_TAPS_UNLOCK = 5

/** v0.5.10 关于页行式条目外链目标。 */
private const val ABOUT_GITHUB_URL = "https://github.com/zglinus"
private const val ABOUT_PROJECT_URL = "https://github.com/oh-zgl-s-fish/Bluelink"
private const val ABOUT_FEEDBACK_URL = "https://github.com/oh-zgl-s-fish/Bluelink/issues"
private const val ABOUT_WANGBAOBAO_URL = "https://space.bilibili.com/1978636705/"

/**
 * 关于页（抽屉 4 / BluelinkUiState.PAGE_ABOUT）：v0.5.10 重做；v0.5.10c 连击目标迁移 + 热区槽位删除。布局自上而下：
 * 1) 顶部应用名「蓝鲸·X」居中（headlineLarge，页面顶部留白保持 v0.5.10b 紧凑值）；
 * 2) 版本号行条目（v0.5.10c 起为连击目标）：AboutLinkRow 同款独立行——左「版本」+ 右 [BuildConfig.VERSION_NAME]
 *    （v0.5.10 起 buildConfig 已开启），整行可点带水波纹；
 * 3-5) 行式条目区（列表行样式 + 点击水波纹）：GitHub 主页 / 项目地址 / 反馈（ACTION_VIEW 外链）；
 * 6) 链接 Surface 与致谢 Surface 之间：v0.5.10c 起正常形态无中间元素（两 Surface 直接相邻、仅容器常规间距，
 *    原 60dp 淡条隐藏热区已删除）；版本号行快速连点 [ABOUT_VERSION_TAPS_UNLOCK] 次（相邻间隔 ≤
 *    [ABOUT_VERSION_TAP_WINDOW_MS]ms，超时清零）解锁；解锁态 [logUnlocked] 由 MainScreen 持有——AboutPage 随
 *    路由切页离开组合，解锁需本会话保持）；解锁后同槽位（链接 Surface 正下方紧邻）显示「收集日志」卡片，两段式：
 *    首次点击开始记录（[DiagLogger.entryCount] 起点偏移 + Snackbar），期间日志持续入内存缓冲；再次点击停止 →
 *    [DiagLogger.entriesSince] 取起点后新增条目 → 脱敏（型号/别名/pwd/ssid 键值/MAC/IPv4/6 位 PIN）→ 写 txt
 *    （自定义接收目录直接 SAF 落盘；未自定义则本次弹目录选择器选落盘位置，Downloads 初始，不改接收目录设置）
 *    → Toast「已保存日志：…」；
 *    v0.5.12 md3-audit-2 F4：两段式状态机（collecting / 起点 / 待落盘文本）与导出目录选择器、导出 IO 已
 *    上提 MainScreen（logCollect / onLogCollectClick/logDirLauncher，与 logUnlocked 同级）——AboutPage 只读
 *    展示并上报点击，随路由切页不丢收集窗口；导出中 logExporting busy（收集行禁点 + 「导出中…」文案）。
 * 7) 底部致谢区（DeepSeek / 王宝煲 / LocalSend / MacroDroid / Material 3 / GPL-3.0）。
 */
@Composable
private fun AboutPage(
    ui: BluelinkUiState,
    // v0.5.10（v0.5.10c 注释同步：连击目标为版本号行）：解锁态（MainScreen 持有；AboutPage 只读展示 + 上报解锁）
    logUnlocked: Boolean,
    onLogUnlocked: () -> Unit,
    // v0.5.12 md3-audit-2 F4：收集两段式状态/导出由 MainScreen 层持有执行（collecting / 起点 / 待落盘文本 /
    // 导出 busy 随路由切页不丢；本页只展示状态并上报「收集行点击」，状态机见 MainScreen logCollect / onLogCollectClick）
    collecting: Boolean,
    exporting: Boolean,
    onCollectRowClick: () -> Unit,
) {
    val context = LocalContext.current

    // v0.5.10c：版本号行连点计数（相邻间隔 >2s 清零；原隐藏热区计数变量随热区删除改名迁移）
    var versionTaps by remember { mutableStateOf(0) }
    var lastVersionTapMs by remember { mutableStateOf(0L) }

    // v0.5.10c：版本号行连击（连击目标由隐藏热区迁至版本号行条目；原连击处理函数改名 onVersionTap，
    // 计数逻辑零改动：相邻间隔 ≤ ABOUT_VERSION_TAP_WINDOW_MS 递增、超时清零、达 5 次解锁）
    fun onVersionTap() {
        val now = SystemClock.elapsedRealtime()
        versionTaps = if (now - lastVersionTapMs <= ABOUT_VERSION_TAP_WINDOW_MS) versionTaps + 1 else 1
        lastVersionTapMs = now
        if (versionTaps >= ABOUT_VERSION_TAPS_UNLOCK) {
            versionTaps = 0
            onLogUnlocked()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(SpacingTokens.SpaceLg),
        verticalArrangement = Arrangement.spacedBy(SpacingTokens.SpaceMd),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 标题行（同 LOG/设置页风格）：左侧「关于」+ 右侧「返回」回主页面
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("关于", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { ui.currentPage = BluelinkUiState.PAGE_HOME }) { Text("返回") }
        }

        // 1-2) 顶部应用名居中（页面顶部留白，保持 v0.5.10b 紧凑值）+ 版本号行条目（v0.5.10c 连击目标）
        // v0.5.10b 顶部留白压缩：SpaceXl(24dp)→SpaceMd(12dp)，本任务保持不回退。
        // v0.5.10c：版本号小字 Text → AboutLinkRow 同款独立行条目（左「版本」+ 右 BuildConfig.VERSION_NAME、
        // 整行可点带水波纹）——隐藏热区废弃后本行为新连击目标；原版本号下 SpaceMd 大空档随热区一并移除，
        // 正常形态链接 Surface 与致谢 Surface 直接相邻、无留白。
        Spacer(Modifier.height(SpacingTokens.SpaceMd))
        Text(
            text = "蓝鲸·X",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        AboutLinkRow(
            title = "版本",
            onClick = { onVersionTap() },
            // v0.5.13 md3-audit-2 AP2/A10：版本行 = 隐藏开发者入口（刻意隐藏，不宣示，本注释即记录）：
            // ① 五连击解锁（2s 窗口内连点 5 次，onVersionTap；TalkBack 用户每次激活间隔难 <2s，几乎不可达）；
            // ② 长按解锁（combinedClickable onLongClick → onLogUnlocked，与连击并存）——长按对读屏可达
            // （TalkBack 双指长按 / 可访问性「长按」动作），作无障碍替代入口；解锁内容仅「收集日志」开发者卡片，风险低
            onLongPress = { onLogUnlocked() },
            // v0.5.13 md3-audit-2 K4/T2：版本号仍为文本内容（trailing 槽位）；右箭头图标只给可跳转外链行
            trailing = {
                Text(
                    text = BuildConfig.VERSION_NAME,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
        // v0.5.10c 条件间距（版本号行 ↔ 链接 Surface）：正常态 SpaceXs 分组留白；解锁态去掉额外 Spacer（仅
        // 容器常规 spacedBy）→ 链接 Surface 与其正下方「收集日志」卡片整体上移、按钮进入首屏明显可见。
        if (!logUnlocked) {
            Spacer(Modifier.height(SpacingTokens.SpaceXs))
        }

        // 3-5) 行式条目区（列表行样式，同设置页分组行风格；点击水波纹；ACTION_VIEW 外链）
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            shape = MaterialTheme.shapes.large,
        ) {
            Column {
                AboutLinkRow(
                    title = "GitHub",
                    subtitle = ABOUT_GITHUB_URL.removePrefix("https://"),
                    onClick = { openExternalUrl(context, ABOUT_GITHUB_URL) },
                )
                HorizontalDivider()
                AboutLinkRow(
                    title = "项目地址",
                    subtitle = ABOUT_PROJECT_URL.removePrefix("https://"),
                    onClick = { openExternalUrl(context, ABOUT_PROJECT_URL) },
                )
                HorizontalDivider()
                AboutLinkRow(
                    title = "反馈",
                    subtitle = ABOUT_FEEDBACK_URL.removePrefix("https://"),
                    onClick = { openExternalUrl(context, ABOUT_FEEDBACK_URL) },
                )
            }
        }

        // 6) 链接 Surface 与致谢 Surface 之间（v0.5.10c）：正常形态无中间元素（两 Surface 直接相邻，仅容器
        //    常规 spacedBy；原 60dp 淡条隐藏热区 else 分支已整段删除）；解锁态同槽位渲染「收集日志」卡片——
        //    位于链接 Surface 正下方紧邻，随上方条件压缩整组上移、首屏可见。卡片两段式内容零改动（v0.5.10）。
        if (logUnlocked) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                shape = MaterialTheme.shapes.large,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.large)
                        // v0.5.12 md3-audit-2 F4：导出中 busy——收集行禁点（IO/SAF 选目录期间防重入）
                        .clickable(
                            enabled = !exporting,
                            // v0.5.13 md3-audit-2 A6：可点行补 role=Role.Button（读屏按「按钮」播报；行内两行状态文字合并为按钮标签）
                            role = Role.Button,
                            onClick = onCollectRowClick,
                        )
                        // v0.5.13 md3-audit-2 A8：两段式收集状态 liveRegion——开始/收集中/导出中/完成状态文案与颜色切换
                        // 时 TalkBack 主动播报（Polite；接在 clickable 之后，liveRegion 随 clickable 合并节点上报）
                        .semantics { liveRegion = LiveRegionMode.Polite }
                        .padding(horizontal = SpacingTokens.SpaceLg, vertical = SpacingTokens.SpaceMd),
                    verticalArrangement = Arrangement.spacedBy(SpacingTokens.SpaceXs),
                ) {
                    Text(
                        text = when {
                            exporting -> "收集日志（导出中…）"
                            collecting -> "收集日志（收集中）"
                            else -> "收集日志"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            exporting -> MaterialTheme.colorScheme.onSurfaceVariant
                            collecting -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Text(
                        text = when {
                            exporting -> "正在脱敏并保存为 txt，请稍候…"
                            collecting -> "已开始记录——操作复现后再次点击停止并保存"
                            else -> "点击开始记录；再次点击停止并脱敏保存为 txt"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // 7) 底部致谢区（小字号/多行，普通分组容器风格；v0.5.10c 起正常形态与链接 Surface 直接相邻、无留白）
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
                Text("致谢", style = MaterialTheme.typography.titleMedium)
                HorizontalDivider()
                Text(
                    text = "LocalSend（传输协议形状启发，端到端互通）\nMacroDroid（系统热点机制逆向参考，仅方法论）\nMaterial Design 3（UI/UX 决策系统）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("Contributors", style = MaterialTheme.typography.titleMedium)
                HorizontalDivider()
                Text(
                    text = "zglinus（项目维护与集成：编码协调、构建发布）\nDeepSeek（绝大多数代码与设计由 DeepSeek 模型生成）\npi agent（DeepSeek-powered coding subagent：按任务切割执行模块编码/修复/逆向分析）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // 王宝煲：应用图标表情包来源（可链 Bilibili 空间）——Contributors 组（图标来源 🫶）
                // v0.5.13 md3-audit-2 A6：可点行补 role=Role.Button；K4/T2：右侧字形 › → 右箭头 Material 图标
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.large)
                        .clickable(onClick = { openExternalUrl(context, ABOUT_WANGBAOBAO_URL) }, role = Role.Button)
                        .padding(vertical = SpacingTokens.SpaceXs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "王宝煲（应用图标表情包来源）· Bilibili 空间",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    RowChevronIcon()
                }
            }
        }
    }
}

/** 关于页行式条目（列表行样式，同设置页分组行；整行可点 + 点击水波纹；subtitle 小字；trailing 右侧槽位）。
 * v0.5.13 md3-audit-2 改动：K4/T2 trailing 由字形字符串（›）改 composable 槽位（默认渲染右箭头 Material 图标——
 * 字形跨字体/读屏拼读「大于号」不稳定）；A6 clickable 补 role=Role.Button（读屏播「按钮」）；AP2/A10 可选
 * onLongPress（版本号行替代解锁入口用，见 AboutPage 调用处；combinedClickable 暴露读屏「长按」动作）。 */
@OptIn(ExperimentalFoundationApi::class) // combinedClickable（onLongPress 分支；DeviceRow 同款 OptIn）
@Composable
private fun AboutLinkRow(
    title: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    // v0.5.13 md3-audit-2 K4/T2：trailing 槽位——null（默认）= 渲染右箭头 chevron（[RowChevronIcon]，外链行惯例）；
    // 调用方可传任意 composable 覆写（版本行传版本号 Text，无箭头）
    trailing: (@Composable () -> Unit)? = null,
    // v0.5.13 md3-audit-2 AP2/A10：版本号行替代解锁——长按同样触发解锁（与五连击并存）；其余行不传
    onLongPress: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            // v0.5.13 md3-audit-2 A6：可点行补 role=Role.Button（读屏按「按钮」播报而非裸可点）
            .let { base ->
                if (onLongPress != null) {
                    base.combinedClickable(
                        onClick = onClick,
                        onLongClick = onLongPress,
                        role = Role.Button,
                    )
                } else {
                    base.clickable(onClick = onClick, role = Role.Button)
                }
            }
            .padding(horizontal = SpacingTokens.SpaceLg, vertical = SpacingTokens.SpaceMd),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            trailing()
        } else {
            RowChevronIcon()
        }
    }
}

/** v0.5.13 md3-audit-2 K4/T2：可点行右侧装饰 chevron（Icons.AutoMirrored.Filled.KeyboardArrowRight，extended）。
 * 装饰性图标不读屏（contentDescription=null）：行内 title/文字即整行名称（A6 role=Button 合并语义），不重复播报。 */
@Composable
private fun RowChevronIcon() {
    Icon(
        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 打开外链（ACTION_VIEW；失败 Toast 提示，不崩溃）。
 * v0.5.13 md3-audit-2 F2：外链打开失败**保留 Toast**——系统 Activity 跳转失败属工具性例外（非页面内反馈流，
 * Snackbar 上下文不连续：跳转尝试发生于点击即时的系统层，失败回传无页面内状态可挂靠），注释注明例外保留；
 * 收集日志导出完成已改 ui.showSnack（见 MainScreen 顶层回调）。 */
private fun openExternalUrl(context: Context, url: String) {
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (e: Exception) {
        Toast.makeText(context, "无法打开链接", Toast.LENGTH_SHORT).show()
    }
}

// ==================== v0.5.10 关于页「收集日志」导出支撑 ====================

/** JSON 键值打码正则：键含 pwd/password/passwd → 值 <pwd>；键含 ssid → 值 <ssid>（值保留键、整值打码）。 */
private val LOG_JSON_PWD_KEY_RE = Regex(
    "(\"([^\"]*(?:pwd|password|passwd)[^\"]*)\"\\s*:\\s*)\"[^\"]*\"",
    RegexOption.IGNORE_CASE,
)
private val LOG_JSON_SSID_KEY_RE = Regex(
    "(\"([^\"]*ssid[^\"]*)\"\\s*:\\s*)\"[^\"]*\"",
    RegexOption.IGNORE_CASE,
)

/** MAC / IPv4 / 6 位纯数字 PIN 打码正则。 */
private val LOG_MAC_RE = Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")
private val LOG_IPV4_RE = Regex("\\b\\d{1,3}(\\.\\d{1,3}){3}\\b")
private val LOG_PIN6_RE = Regex("\\b\\d{6}\\b")

/** 两段式导出文本：头部（时间，不含设备信息）+ 起点后新增诊断条目（[DiagLogger.entriesSince]）。 */
private fun buildLogExportText(alias: String, startCount: Long): String {
    val sb = StringBuilder()
    sb.append("Bluelink 诊断日志（导出已脱敏：型号/别名/密码/PIN/IP/MAC 打码）\n")
    sb.append("导出时间：").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())).append('\n')
    sb.append("----------------------------------------\n")
    sb.append(sanitizeLogText(DiagLogger.entriesSince(startCount), alias))
    return sb.toString()
}

/**
 * 导出文本脱敏（保守优先，宁可多打码不漏）：
 * 1) 设备信息：Build.MODEL / Build.MANUFACTURER → `<model>`；本机别名 → `<alias>`（先型号后别名——别名默认即型号）；
 * 2) JSON 键值打码（先于裸 PIN，整值保护）：pwd/password 系键值 → `<pwd>`；ssid 键值 → `<ssid>`；
 * 3) 正则打码：MAC → `<mac>`；IPv4 → `<ip>`；6 位纯数字（PIN） → `<pin>`。
 */
private fun sanitizeLogText(raw: String, alias: String): String {
    var s = raw
    Build.MODEL.takeIf { it.isNotBlank() }?.let { s = s.replace(it, "<model>") }
    Build.MANUFACTURER.takeIf { it.isNotBlank() }?.let { s = s.replace(it, "<model>") }
    alias.takeIf { it.isNotBlank() }?.let { s = s.replace(it, "<alias>") }
    s = LOG_JSON_PWD_KEY_RE.replace(s) { m -> m.groupValues[1] + "\"<pwd>\"" }
    s = LOG_JSON_SSID_KEY_RE.replace(s) { m -> m.groupValues[1] + "\"<ssid>\"" }
    s = LOG_MAC_RE.replace(s, "<mac>")
    s = LOG_IPV4_RE.replace(s, "<ip>")
    s = LOG_PIN6_RE.replace(s, "<pin>")
    return s
}

/**
 * 写诊断日志到 SAF 目录（与引擎收文件落盘同机制：DocumentFile.createFile + openOutputStream，
 * 用户可读）；文件名 bluelink-log-<yyyyMMdd-HHmmss>.txt。在后台线程调用（调用方经 Dispatchers.IO）。
 * @return 用户可读结果文案（成功含文件名 + 目录名；失败含原因）。
 */
private fun writeLogTextToTree(context: Context, treeUri: Uri, text: String): String {
    val fileName = "bluelink-log-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt"
    val appContext = context.applicationContext
    try {
        val dir = DocumentFile.fromTreeUri(appContext, treeUri)
        if (dir == null) return "保存失败：无法访问所选目录"
        if (!dir.canWrite()) return "保存失败：所选目录不可写"
        val doc = dir.createFile("text/plain", fileName)
            ?: return "保存失败：无法在目录中创建文件"
        val out = appContext.contentResolver.openOutputStream(doc.uri)
            ?: return "保存失败：无法打开输出流"
        out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        val dirName = dir.name?.takeIf { it.isNotBlank() }
            ?: Uri.decode(treeUri.lastPathSegment ?: "")?.takeIf { it.isNotBlank() }
            ?: "所选目录"
        return "已保存日志：$fileName（$dirName）"
    } catch (e: Exception) {
        return "保存失败：${e.message ?: e.javaClass.simpleName}"
    }
}


/** 抽屉（v0.5.6 UI1b-A 4 栏重排）：头部（应用名「蓝鲸·X」/本机 alias）+ 入口列表
 *  （文件传输记录/个性化/设置/关于）→ 设 currentPage（BluelinkUiState.PAGE_* 常量）；
 *  主页面为默认页不列项（子页「返回」回主页面）；旧发送/接收/权限栏已移除（发送/接收并入主页面
 *  操作与设置、权限检测并入设置页权限检测区（v0.5.9 UI1b-C））。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppDrawer(ui: BluelinkUiState, onNavigate: (Int) -> Unit) {
    ModalDrawerSheet {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SpacingTokens.SpaceLg, vertical = 20.dp), // 20dp 非 4dp 节奏（审计未列，保持原值）
        ) {
            Text("蓝鲸·X", style = MaterialTheme.typography.headlineSmall)
            Text(
                text = "本机：${ui.selfCard.alias.ifBlank { Build.MODEL }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider()
        // v0.5.13 md3-audit-2 N1：抽屉 4 项配 Material 图标（History/Palette/Settings/Info，extended）——
        // 扫读更快（icon+label 双通道）；图标装饰（label 文字即名称，contentDescription=null 不重复读屏）
        val entries = listOf(
            Triple(BluelinkUiState.PAGE_LOG, "文件传输记录", Icons.Filled.History),
            Triple(BluelinkUiState.PAGE_PERSONAL, "个性化", Icons.Filled.Palette),
            Triple(BluelinkUiState.PAGE_SETTINGS, "设置", Icons.Filled.Settings),
            Triple(BluelinkUiState.PAGE_ABOUT, "关于", Icons.Filled.Info),
        )
        entries.forEach { (page, label, icon) ->
            NavigationDrawerItem(
                label = { Text(label) },
                icon = {
                    Icon(
                        imageVector = icon,
                        contentDescription = null, // 装饰性图标（抽屉行 label 即名称）
                    )
                },
                selected = ui.currentPage == page,
                onClick = { onNavigate(page) },
                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
            )
        }
    }
}

/** 权限提示行（未授权时置顶；v0.5.4b 横幅容器：surfaceContainer，无 elevation、无边框，块级圆角 10）。 */
@Composable
private fun PermissionBanner(
    onRequestPermissions: () -> Unit,
    // v0.5.11 UI1b-E 改③：主页浮层容器 alpha（WallpaperStore.containerAlpha()，保存 tick++ 后即时刷新）
    containerAlpha: Float,
) {
    // v0.5.4b 映射：横幅＝提示块（主层次） → surfaceContainer；无 elevation（不设阴影）、无边框；
    // 块级圆角 10（MaterialTheme.shapes.large = ShapeTokens.Modal）
    Surface(
        modifier = Modifier.fillMaxWidth(),
        // v0.5.8 UI1b-B2 主页面浮层化：HOME 横幅提示半透明浮于壁纸之上
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = containerAlpha),
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SpacingTokens.SpaceLg, vertical = SpacingTokens.SpaceSm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // v0.5.13 md3-audit-2 C6：缺权限 = 「待动作/阻塞可恢复」而非「已出错」——error 语义日常化会稀释
            // 真实错误识别 → 点/文字改 secondary（图标双通道保留：StatusDot 点 + 文字同色，非色 alone 表状态）；
            // 「去授权」按钮保留（可恢复动作入口）
            StatusDot(color = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.width(SpacingTokens.SpaceSm))
            Text(
                text = "需要权限: 蓝牙 + 位置",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRequestPermissions) { Text("去授权") }
        }
    }
}

/** 蓝牙未开提示（不自动开，仅提示；v0.5.4b 横幅容器：surfaceContainer，无 elevation、无边框，块级圆角 10）。 */
@Composable
private fun BluetoothOffBanner(
    // v0.5.11 UI1b-E 改③：主页浮层容器 alpha（WallpaperStore.containerAlpha()，保存 tick++ 后即时刷新）
    containerAlpha: Float,
) {
    // v0.5.4b 映射：横幅＝提示块（主层次） → surfaceContainer；无 elevation（不设阴影）、无边框；
    // 块级圆角 10（MaterialTheme.shapes.large = ShapeTokens.Modal）
    Surface(
        modifier = Modifier.fillMaxWidth(),
        // v0.5.8 UI1b-B2 主页面浮层化：HOME 横幅提示半透明浮于壁纸之上
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = containerAlpha),
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
            // v0.5.13 md3-audit-2 K3：关闭 = 低风险 dismiss 动作——原全宽 filled Button 与区内主操作
            // （同网免热点直连/组建临时局域网 filled）同视区双 filled 抢层级 → 降级 TextButton 右对齐；
            // filled 只留主操作
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
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

/** 状态短语一行（v0.5.4a）：进行中=onSurface；错误态=error 色文案。
 * v0.5.13 md3-audit-2 A8：liveRegion——配网弹窗阶段短语（正在握手/PIN/开热点/等待接入/传输就绪/错误）
 * 切换时 TalkBack 主动播报，不依赖读屏焦点恰好停在弹窗上。 */
@Composable
private fun StatusPhrase(text: String, error: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
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

