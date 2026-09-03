@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.zglinus.bluelink.ui.personalize

import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zglinus.bluelink.ui.BluelinkUiState
import com.zglinus.bluelink.ui.theme.SpacingTokens
import com.zglinus.bluelink.ui.theme.extended
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import android.graphics.Color as AndroidColor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 个性化页（抽屉 2 / BluelinkUiState.PAGE_PERSONAL；v0.5.8 UI1b-B2 整页重做，覆盖 v0.5.7 UI1b-B
 * 三槽长表单版——真机反馈 v0.5.7「主页面背景不变」由 MainScreen HOME 浮层化修复（另见
 * ui/MainScreen.kt RootWallpaperLayer；主页浮层 alpha 已运行态可调见 WallpaperStore.containerAlpha），本页只管草稿编辑与保存）。
 *
 * v0.5.8b 本版改动（壁纸来源修复 + 视觉收尾，真机反馈「壁纸两个来源都不显示/重启后丢失」）：
 * - 自选图：SAF 收到 content:// 后**立即复制到 App 私有目录**（filesDir/wallpapers/，存文件绝对路径
 *   [copyPickedToPrivateDir]）——重启必在、BitmapFactory.decodeFile 直读无需 provider 授权
 *   （修 v0.5.8「SAF grant 重启失效（takePersistable 不一定成功）+ content:// 双次 openInputStream
 *   部分 ROM 不稳 → 自选图不显示/重启丢」）；takePersistableUriPermission 保留尝试（忽略失败，兼容
 *   老存量 content:// 数据链路）；清除槽时私有副本文件随槽 IO 删除；
 * - 解码失败可见：预览区解码失败不再无限「加载壁纸预览…」→ 显示失败文案 + 来源小字提示
 *   （预览走 WallpaperBackdrop [rememberSlotDecode] 三态，见 [PreviewSection]）；
 * - 视觉收尾：右 5/6 具体颜色方块小格 → **40dp 大圆形色点**（选中 = primary 2dp 圆环）；「从壁纸取色」
 *   入口 → **彩虹 sweepGradient 圆钮**（无文字）；场景三按钮 → **无字圆形按钮**（统一=半灰分半 /
 *   深=深灰 / 浅=浅灰，选中 = primary 外环），按钮行与预览区之间加当前场景说明句 [sceneHint]。
 *
 * v0.5.8d 本版改动（色系交互区视觉定稿，按 HTML 预览用户确认的最终交互落地；其余区保持 v0.5.8b 不动）：
 * - 色系入口去文字：左 1/6 窄条（色块+名称）→ **圆形按钮**，填充当前色系代表色双色半彩示意（代表色 +
 *   其加深半彩各半，无文字）；展开态 = primary 外环高亮（同具体色点选中态样式，见 [FamilyEntryStrip]）；
 * - 色系选择改「右侧同款切换」（取代 v0.5.8「点入口向右展开 chips 行」）：点左圆钮展开态下右 5/6 切为
 *   **色系大圆点横滑行**——圆点 = 各色系代表色、与具体色点同 40dp 视觉（无卡片/无边框容器/无文字，
 *   LazyRow 可左右滑动，选中环 = 当前色系，见 [FamilyDotsRow]）；色系列表点选某色系 → 右区**无缝切回**
 *   该色系具体色横滑行（同款切换，无过渡动画，简单状态切换）；左圆钮再点（toggle）在色系行/具体色行间
 *   切换（展开=色系行，入口钮高亮）；两行的容器同位置同样式（右区同一块 if/else 分支，仅内容数组与选中
 *   回调不同，无覆盖层/卡片/边框/阴影）；
 * - 颜色区与壁纸区（场景钮/预览区）之间加 1px 水平细分隔线（outlineVariant，左右约 16dp 边距）；
 * - 提示文案居中核对：场景说明句与壁纸预览占位文案显式 TextAlign.Center（保持一致居中）；
 *
 * 布局（docs/ui-design.md §4.1b v0.5.8 定稿 + v0.5.8b 收尾 + v0.5.8d 色系区改版；竖屏无上下滚动、一屏放完）：
 * - 顶部条：左「个性化」标题 / 右上「保存」（保存为最右角按钮；返回主页面入口与同级子页一致
 *   放标题右侧、保存左侧——规格图仅画 [保存]，本页为抽屉子页（主页面不列抽屉项、无 BackHandler），
 *   无返回即死胡同，故补 [返回]，保存仍保持右上角）；
 * - 颜色区（约占标题下内容区高 1/8，BoxWithConstraints 取 12.5% 收 80–128dp）：
 *   左 1/6「色系入口」**圆形按钮**（v0.5.8d 无文字：当前色系代表色双色半彩示意，展开态 primary 外环）+
 *   中间竖分割线 + 右 5/6：收拢态 = 当前色系 HSV 明暗连续 10 个**大圆色点**（直径 40dp，LazyRow 可左右
 *   滑动，点选即选中——选中态 = 2dp primary 圆环，色点纯色无文字）；展开态 = **色系代表色大圆点行**
 *   （v0.5.8d 同款切换：红橙黄绿青蓝紫品粉棕灰白黑 13 色系，选中环 = 当前色系，点选收拢并切该色系取色）；
 *   API27+（O_MR1 门，26 隐藏）「从壁纸取色」**彩虹 sweepGradient 圆钮**固定在右区末端，取到的壁纸主色
 *   同样先入选中态（保存才生效）；颜色区与壁纸区之间 1px 水平细分隔线（v0.5.8d）；
 * - 壁纸区：场景三**无字圆钮**（统一壁纸（兜底）= 半深灰半浅灰圆、深色模式壁纸 = 深灰圆、
 *   浅色模式壁纸 = 浅灰圆，对应 [WallpaperStore] SLOT_UNIFIED/SLOT_DARK/SLOT_LIGHT，当前场景 =
 *   primary 2dp 外环高亮）+ 其下一行当前场景说明句 [sceneHint] + 弹性复用预览区：渲染当前场景槽草稿 =
 *   壁纸图 + 当前遮罩草稿叠加（复用 ui/personalize/WallpaperBackdrop.kt 同套渲染/解码函数
 *   [rememberSlotDecode]/[WallpaperEffect]，预览即真实背景效果）；槽未设 → 占位（图标 +
 *   「点击选择壁纸」）；解码失败 → 可见失败文案（v0.5.8b）；点预览区 → [WallpaperSourceSheet]
 *   三选项：跟随系统壁纸 / 自选图片（SAF）/ 清除（清除仅已设时显示）；
 * - SAF 选图后**立即复制到私有目录**（见 [copyPickedToPrivateDir]，IO 线程；成功才写槽）；
 * - 遮罩区（底部固定 · 全局共用）：「遮罩」+ Slider 0–80%（显示百分比），拖动即页内预览
 *   （预览区遮罩同步变）。遮罩色 = 主题 surfaceVariant（随系统深浅自动切换，v0.5.8b 确认，见
 *   WallpaperBackdrop.WallpaperEffect 注释）。
 * - 容器透明度区（v0.5.11 UI1b-E 改④；遮罩行下方）：文字「容器透明度」+ Slider 5–50%（5% 步进，
 *   显示百分比，同遮罩行样式）——语义 = 主页浮层容器「透明程度」（容器实际 alpha = 1−值/100：
 *   5→0.95 … 50→0.50，默认 20→0.80）；拖动只改本地草稿 transparencyDraft（v0.5.12b 起壁纸预览区
 *   以浮层卡模拟实时预览——壁纸之上叠主页同款圆角表面块模拟浮层容器、卡外露壁纸（见 PreviewSection/
 *   HomeContainerMock），透明度草稿越高卡越透明、壁纸透出越多），保存写 store.containerTransparency，
 *   主页面顶栏/内容容器 alpha 随之刷新。
 *
 * 保存语义（v0.5.8 新交互，v0.5.11 增容器透明度草稿）：页面持本地编辑态（三槽草稿 / maskAlpha 草稿 /
 * 容器透明度草稿 transparencyDraft / accent 草稿 / 当前场景 / 色系展开与选中态），进入页面从
 * [WallpaperStore] 读初值；任何改动只改本地态并即时页内预览（不写 prefs，保存前主页面背景与主题不变）。
 * 右上「保存」一次性写 prefs（三槽 setSlot×3 + maskAlpha + containerTransparency + accentColor）→
 * `ui.wallpaperTick++`（主页面背景 WallpaperBackdrop 与浮层容器 alpha（MainScreen 以 tick 为 key 重读
 * containerAlpha()）一并刷新，见 [BluelinkUiState.wallpaperTick]）
 * → [onSaved] 上抛强调色（MainActivity 主题 state → BluelinkTheme(accent) 重算 primary 系）
 * → Snackbar「已保存」。离开页面未保存 = 丢弃草稿（remember 随页面出组合失效，重进从 prefs 重读）；
 * v0.5.12 md3-audit-2 FI2：离开时（返回钮 / 抽屉切页 / 顶栏返回主页）若草稿 ≠ store 已存值 → Snackbar
 * 「有未保存的改动」提示（只提示不阻断离开；dirty 判定见 [dirty] 计算块，经 onDirtyChange 上报 MainScreen）。
 * 强调色未选/null → 主题用默认 M3 品牌蓝派生，不覆写。
 */
@Composable
fun PersonalizePage(
    ui: BluelinkUiState,
    // v0.5.8 UI1b-B2：保存回调（保存的强调色 ARGB Long？null=未选/清除）→ MainActivity 主题强调色 state
    onSaved: (Long?) -> Unit = {},
    // v0.5.12 md3-audit-2 FI2：未保存草稿 dirty 上报（MainScreen 持有 personalDirty，离开个性化页前提示）
    onDirtyChange: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val store = remember { WallpaperStore(context.applicationContext) }
    // v0.5.8b：自选图复制 / 清除槽删私有副本在 Dispatchers.IO 执行（文件操作不入主线程）
    val scope = rememberCoroutineScope()

    // ==================== v0.5.8 本地编辑态（草稿；保存才写 prefs） ====================
    var unifiedDraft by remember { mutableStateOf(store.slot(WallpaperStore.SLOT_UNIFIED)) }
    var darkDraft by remember { mutableStateOf(store.slot(WallpaperStore.SLOT_DARK)) }
    var lightDraft by remember { mutableStateOf(store.slot(WallpaperStore.SLOT_LIGHT)) }
    var maskDraft by remember { mutableStateOf(store.maskAlpha) }
    var accentDraft by remember { mutableStateOf(store.accentColor) }
    // v0.5.11 UI1b-E 改④：容器透明度草稿（初值 store.containerTransparency；拖动只改本地态，保存才写 store；
    // 离开未保存 = 丢弃，与 mask 草稿同语义；v0.5.12b 起预览区按草稿以浮层卡模拟实时预览（见 PreviewSection））
    var transparencyDraft by remember { mutableStateOf(store.containerTransparency) }

    // v0.5.12 md3-audit-2 FI2：未保存草稿判定（三槽 / 遮罩 / 容器透明度 / 强调色 任一 ≠ store 已存值）。
    // dirty=true → 离开页面（返回钮直判 + MainScreen 侧拦截抽屉/顶栏路由经 onDirtyChange 上报）时
    // Snackbar「有未保存的改动」提示（防调色半天点返回全丢无提示；只提示不阻断离开，保持简单）。
    val dirty = remember(unifiedDraft, darkDraft, lightDraft, maskDraft, transparencyDraft, accentDraft) {
        unifiedDraft != store.slot(WallpaperStore.SLOT_UNIFIED) ||
            darkDraft != store.slot(WallpaperStore.SLOT_DARK) ||
            lightDraft != store.slot(WallpaperStore.SLOT_LIGHT) ||
            maskDraft != store.maskAlpha ||
            transparencyDraft != store.containerTransparency ||
            accentDraft != store.accentColor
    }
    // 上报 MainScreen.personalDirty：进页初值/保存后 = false；任何改动 = true（页面每次进出自动复位）
    LaunchedEffect(dirty) { onDirtyChange(dirty) }
    // 当前场景（场景三按钮高亮 + 预览区渲染该槽草稿）
    var sceneSlot by remember { mutableStateOf(WallpaperStore.SLOT_UNIFIED) }
    // 色系展开态：false=右区显示当前色系具体色行；true=右区展开色系列表大圆点行（v0.5.8d 同款切换，
    // 点选某色系后收拢并切到该色系具体色行；左入口圆钮 toggle 同此态）
    var familyExpanded by remember { mutableStateOf(false) }
    // 当前色系（右区取色对象；初值 = 已存强调色所在色系，未选 → 品牌默认蓝系）
    var currentFamily by remember {
        mutableStateOf(store.accentColor?.let { accentFamilyOf(it) } ?: FAMILY_BLUE)
    }
    // 壁纸来源弹层可见性（点预览区打开；三选项：跟随系统壁纸/自选图片/清除）
    var sourceSheet by remember { mutableStateOf(false) }
    // SAF 自选图片：记录目标场景槽 → GetContent 返回后写该槽草稿（捕获值，防止选图期间切场景错位）
    var pickingSlot by remember { mutableStateOf<Int?>(null) }

    fun slotDraft(slotId: Int): WallpaperSlot = when (slotId) {
        WallpaperStore.SLOT_DARK -> darkDraft
        WallpaperStore.SLOT_LIGHT -> lightDraft
        else -> unifiedDraft
    }

    fun setSlotDraft(slotId: Int, slot: WallpaperSlot) {
        when (slotId) {
            WallpaperStore.SLOT_DARK -> darkDraft = slot
            WallpaperStore.SLOT_LIGHT -> lightDraft = slot
            else -> unifiedDraft = slot
        }
    }

    // 自选图片（SAF GetContent，image/*）：收到 content:// uri 后**立即在 Dispatchers.IO 复制到私有目录**
    // （v0.5.8b：存文件绝对路径——重启必在、读取最稳；修 v0.5.8 content:// grant 重启失效 / 双次
    // openInputStream 部分 ROM 不稳 → 自选图不显示）。复制成功才写目标场景槽草稿，失败 Snackbar 不写槽。
    // persistUriReadPermission 保留尝试（老存量 content:// 兼容链路不变；provider 不支持时忽略）。
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        val slotId = pickingSlot ?: return@rememberLauncherForActivityResult
        pickingSlot = null
        if (uri != null) {
            persistUriReadPermission(context, uri)
            scope.launch {
                val path = withContext(Dispatchers.IO) {
                    copyPickedToPrivateDir(context, uri)
                }
                if (path != null) {
                    // 复制成功 → 槽写私有副本文件绝对路径（uri 字段语义不变，见 WallpaperStore：字符串字段可存文件路径）
                    setSlotDraft(slotId, WallpaperSlot(type = WallpaperSlot.TYPE_URI, uri = path))
                } else {
                    ui.showSnack("自选图片复制失败，未更改壁纸来源")
                }
            }
        }
    }

    // API27+ 「从壁纸取色」（O_MR1 门，26 无此入口；取壁纸主色同样先入选中态、保存生效）
    fun pickWallpaperPrimary() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return
        try {
            val colors = WallpaperManager.getInstance(context)
                .getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
            val primary = colors?.primaryColor
            if (primary != null) {
                val argb = primary.toArgb().toLong() and 0xFFFFFFFFL
                accentDraft = argb
                currentFamily = accentFamilyOf(argb)
                ui.showSnack("已取壁纸主色（保存后生效）")
            } else {
                ui.showSnack("未取到壁纸主色，可在下方色板中自选")
            }
        } catch (t: Throwable) {
            ui.showSnack("取壁纸主色失败：${t.message ?: "未知错误"}")
        }
    }

    // 「保存」：一次性写 prefs（三槽 + mask + 容器透明度 + accent）→ 主页面背景/浮层 alpha 刷新信号 → 主题强调色上抛 → Snackbar
    fun save() {
        store.setSlot(WallpaperStore.SLOT_UNIFIED, unifiedDraft)
        store.setSlot(WallpaperStore.SLOT_DARK, darkDraft)
        store.setSlot(WallpaperStore.SLOT_LIGHT, lightDraft)
        store.maskAlpha = maskDraft
        // v0.5.11 UI1b-E 改④：容器透明度入 store（mask 之后、tick++ 之前；主页容器 alpha 由 MainScreen
        // 以 wallpaperTick 为 key 重读 containerAlpha() 即时生效）
        store.containerTransparency = transparencyDraft
        store.accentColor = accentDraft
        ui.wallpaperTick++ // 主页面背景（WallpaperBackdrop）与浮层容器 alpha（MainScreen 重读）刷新
        onSaved(accentDraft) // 主题强调色 state 更新 → MaterialTheme 重算（primary 系换色）
        ui.showSnack("已保存")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = SpacingTokens.SpaceLg),
    ) {
        // ---- 顶部条：左「个性化」标题 / 右上「保存」 ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "个性化",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 规格图顶部条只画 [保存]；本页为抽屉子页（主页面不列抽屉项、无 BackHandler）——
            // 无返回即无法回主页面（死胡同），与 LOG/设置/关于页同款「返回」放保存左侧，保存保持最右上角。
            // v0.5.12 md3-audit-2 FI2：返回离开时有未保存草稿 → Snackbar 提示（抽屉/顶栏路由离开由
            // MainScreen navigateFrom 拦截提示；此处直判——两处均只提示不阻断离开，保持简单）
            TextButton(onClick = {
                if (dirty) ui.showSnack("有未保存的改动")
                ui.currentPage = BluelinkUiState.PAGE_HOME
            }) { Text("返回") }
            TextButton(onClick = { save() }) { Text("保存") }
        }
        Spacer(Modifier.height(SpacingTokens.SpaceSm))
        // ---- 内容区（标题下剩余空间）：颜色区 ~1/8 + 场景行 + 说明句 + 预览弹性大部 + 遮罩底部固定 ----
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            // 颜色区高度 ≈ 内容区高 1/8（收 80–128dp，防过小/过大；预览区弹性占剩余大部）
            val colorAreaHeight = (maxHeight * COLOR_AREA_FRACTION).coerceIn(80.dp, 128.dp)
            // S4/AD3（md3-audit-2 P2）：一屏精确适配只在「区内容自然高 + 预览保底」放得下时成立——内容区高低于
            // 估算阈值（337 + 说明句 16×fontScale + 预览保底 60，见 REGION_* 常量）→ 原 weight 一屏布局会把
            // 预览压到 0、底部行溢出被裁（fontScale 超高 / 矮横屏场景）→ 切「自然高 + verticalScroll」滚动兜底；
            // 默认字号内容不高恒走精确适配分支（不滚、一屏规格不变，v0.5.8 定稿布局保持）。
            val fontScale = LocalDensity.current.fontScale
            val fitOneScreen = maxHeight >=
                (REGION_FIXED_CHROME_DP.dp +
                    REGION_HINT_DP_PER_FONT_SCALE.dp * fontScale +
                    REGION_PREVIEW_FLOOR_DP.dp)
            val regionScroll = rememberScrollState()
            // 内容子序列：两分支共用同一组子件（改动须同步）；差异只在容器与预览高度策略——精确适配 = 预览
            // weight(1f) 弹性吸收剩余（v0.5.8 定稿原布局）；滚动兜底 = 预览固定高（weight 不适用于滚动容器）
            val sections: @Composable ColumnScope.() -> Unit = {
                ColorSectionRow(
                    accentDraft = accentDraft,
                    currentFamily = currentFamily,
                    familyExpanded = familyExpanded,
                    onToggleFamilies = { familyExpanded = !familyExpanded },
                    onFamilySelected = { family ->
                        currentFamily = family
                        familyExpanded = false
                    },
                    onAccentSelected = { accentDraft = it },
                    onPickFromWallpaper = { pickWallpaperPrimary() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(colorAreaHeight),
                )
                Spacer(Modifier.height(SpacingTokens.SpaceXs))
                // v0.5.8d：颜色区与壁纸区（场景钮/预览）之间 1px 水平细分隔线——M3 HorizontalDivider
                // （色 = outlineVariant，同颜色区竖分割线 token；厚 1dp 默认；内容列自带左右 16dp 页边距 →
                // 分割线两端距屏边约 16dp，视觉同 HTML 预览 divider）
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(SpacingTokens.SpaceSm))
                SceneSwitchRow(
                    sceneSlot = sceneSlot,
                    onSceneSelected = { sceneSlot = it },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(SpacingTokens.SpaceXs))
                // v0.5.8b/v0.5.8d：场景说明句（无字圆钮行与预览区之间；v0.5.8d 居中核对——按钮行本身组居中，
                // 说明句显式 TextAlign.Center 行内居中，与壁纸预览占位文案保持一致居中；正文按规格原文）
                Text(
                    text = sceneHint(sceneSlot),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(SpacingTokens.SpaceXs))
                if (fitOneScreen) {
                    // 精确适配分支：预览区 weight(1f) 弹性占剩余大部（v0.5.8 定稿原布局）
                    PreviewSection(
                        slot = slotDraft(sceneSlot),
                        sceneSlot = sceneSlot,
                        maskAlpha = maskDraft,
                        // v0.5.12b：透明度草稿直传预览区（浮层卡 mock 内部按 (100f-transparencyDraft)/100f
                        // 换算卡 alpha；透明度越高卡越透明、壁纸透出越多，与主页一致：5→0.95 … 50→0.50）
                        transparencyDraft = transparencyDraft,
                        onOpenSource = { sourceSheet = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    )
                } else {
                    // S4/AD3 滚动兜底：预览区固定高（[FALLBACK_PREVIEW_HEIGHT]；滚动容器内不能用 weight 弹性，
                    // 固定高保证 mock/占位有稳定可视高度；超高部分随整列 verticalScroll 可达）
                    PreviewSection(
                        slot = slotDraft(sceneSlot),
                        sceneSlot = sceneSlot,
                        maskAlpha = maskDraft,
                        transparencyDraft = transparencyDraft,
                        onOpenSource = { sourceSheet = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(FALLBACK_PREVIEW_HEIGHT),
                    )
                }
                Spacer(Modifier.height(SpacingTokens.SpaceSm))
                MaskRow(
                    maskAlpha = maskDraft,
                    onMaskChange = { maskDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(SpacingTokens.SpaceSm)) // v0.5.11：遮罩行与下方容器透明度行间同页内行距
                // v0.5.11 UI1b-E 改④：遮罩行下方新增「容器透明度」行（同遮罩行样式：文字 + Slider 5–50% 步进 5 + %）
                ContainerTransparencyRow(
                    transparency = transparencyDraft,
                    onTransparencyChange = { transparencyDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (fitOneScreen) {
                Column(modifier = Modifier.fillMaxSize(), content = sections)
            } else {
                // S4/AD3：滚动兜底列——内容自然高、超高可滚到底；默认字号（内容不高）恒走上方精确适配分支不滚
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(regionScroll),
                    content = sections,
                )
            }
        }
    }

    // 壁纸来源弹层（点预览区打开；选择作用于当前场景槽草稿，页内即时预览、保存才生效）
    if (sourceSheet) {
        WallpaperSourceSheet(
            sceneSlot = sceneSlot,
            current = slotDraft(sceneSlot),
            onDismiss = { sourceSheet = false },
            onFollowSystem = {
                setSlotDraft(sceneSlot, WallpaperSlot(type = WallpaperSlot.TYPE_SYSTEM))
                sourceSheet = false
            },
            onPickImage = {
                pickingSlot = sceneSlot
                sourceSheet = false
                imagePicker.launch("image/*")
            },
            onClear = {
                // v0.5.8b：槽 uri 指向私有副本（filesDir/wallpapers 下）→ Dispatchers.IO 删除本地文件
                // （忽略失败）后再清槽——避免复制进私有目录的文件残留成孤儿
                val clearing = slotDraft(sceneSlot)
                if (isPrivateWallpaperFile(context, clearing.uri)) {
                    scope.launch(Dispatchers.IO) { File(clearing.uri!!).delete() }
                }
                setSlotDraft(sceneSlot, WallpaperSlot.NONE)
                sourceSheet = false
            },
        )
    }
}

/** 颜色区占内容区高度的比例（≈1/8，规格「颜色区占页面内容高约 1/8」）。 */
private const val COLOR_AREA_FRACTION = 0.125f

// ==================== md3-audit-2 S1（P2）：页内非间距/内容度量字面量集中登记（注释收口，不改布局值） ====================
// 个性化页无字圆钮/色点系列是「内容度量 token」（选中环语言/内容列宽/区高，4dp 间距节奏不适用）——按 S1 只登记
// 不收归 SpacingTokens（间距 scale 只管 4dp 节奏留白）；值 = v0.5.8b/8d 定稿视觉、原值保留：
//  - 选中环语言（SH2 已提炼为下方常量并替换使用点）：选中环 2dp / 未选环 2dp（审计 v0.5.11 口径「2/1dp」——
//    v0.5.12 md3-audit-2 C1/C2 已把未选 outlineVariant 1dp 升为 outline 2dp，原 1dp 档现不存在）；
//    环底衬 44dp（= 色点 40 + 2×2dp 环；审计口径「44/42」中 42 为 v0.5.11 旧值，现状无 42）；色点直径 40dp；
//  - 场景钮 56dp 触达/环底衬、52dp 填充圆（审计口径「56/54/52」中 54 为 v0.5.11 旧值，现状 56+52 两档）；
//  - 触达 48dp（色点/色系入口/取色钮命中区，≥48×48 触达规范，audit A5 保持）；
//  - 遮罩/容器透明度行固定文本列 48/96/44dp（MaskRow/ContainerTransparencyRow；S2 已改
//    widthIn(min=原值)+maxLines=1+ellipsis 做 2x 缩放保护，列宽语义不变）；
//  - 颜色区高度收口 80–128dp（= 内容区高 1/8 的收口区间，见 [COLOR_AREA_FRACTION] 消费点）。
// =========================================================================================================

// ==================== md3-audit-2 SH2（P2）：选中环视觉语言页内提炼（token 化，不改当前视觉） ====================
// 无字圆钮/色点「选中环」语言（v0.5.8b 引入；原散落 AccentSwatch/FamilyEntryStrip/SceneDotButton 三处字面量）：
// 实现 = 「内容圆 + 外环底衬」两层 Box（内容圆盖住底衬中心、外缘露环宽，无需描边半宽换算）——底衬直径 =
// 内容直径 + 2×环宽（40+2×2=44、52+2×2=56）。环宽按 v0.5.13 现状常量化：选中/未选均为 2dp，差异只在色档：
// selected = primary（浅 6.21:1 / 深 10.90:1 ✓）、normal = outline（v0.5.12 md3-audit-2 C1/C2 未选
// outlineVariant 1dp 1.66:1 → outline 2dp 后，原 SH2「highContrast=outline」档即现状未选档：浅 #74777F
// 3.39:1 / 深 #8F9099 4.29:1 ≥3:1 图形）——色档随主题读取见 [swatchRingColor]。
private val SwatchRingSelected = 2.dp // 选中环宽（色档 primary；底衬/填充圆按「内容 + 2×环宽」派生，见下）
private val SwatchRingNormal = 2.dp // 未选环宽（色档 outline；v0.5.12 C1/C2 升档后与选中同宽——几何共用
// SwatchRingSelected 的底衬派生（SwatchRingBacking/SceneFillSize），本名保留双名便于读码/维护；未来未选档
// 若另设宽度，在底衬派生处按态区分即可）
private val SwatchTouchSize = 48.dp // 色点/色系入口触达命中区边长（≥48×48 触达规范）
private val SwatchDotSize = 40.dp // 色点/色系圆点内容直径（AccentSwatch/FamilyDotsRow/FamilyEntryStrip 半彩圆共用）
// 底衬/填充 = 内容直径 + 2×环宽（选中/未选同宽 2dp）——改环宽只动 SwatchRing*，底衬与填充随之派生
private val SwatchRingBacking = SwatchDotSize + SwatchRingSelected * 2f // 环底衬直径 = 40 + 2×2 = 44dp
private val SceneDotTouch = 56.dp // 场景钮触达命中区 = 环底衬直径（≥48 触达）
private val SceneFillSize = SceneDotTouch - SwatchRingSelected * 2f // 场景钮填充圆直径 = 56 − 2×2 = 52dp

/** 选中环色档（SH2）：选中 = primary / 未选 = outline——v0.5.12 C1/C2 修复后未选即原 highContrast 档
 *  （outlineVariant 1dp 1.66:1 → outline 2dp：浅 #74777F 3.39:1 / 深 #8F9099 4.29:1 ≥3:1 图形；
 *  选中 primary 浅 6.21:1 / 深 10.90:1 ✓）。 */
@Composable
private fun swatchRingColor(selected: Boolean): Color =
    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline

// ==================== md3-audit-2 S4/AD3（P2）：一屏精确适配 vs 滚动兜底 分界常量（估算） ====================
// 内容区（标题下）「一屏精确适配」可行性估算（dp；消费点在 PersonalizePage 内容区 BoxWithConstraints）：
// 区内不含弹性预览的自然高 ≈ 颜色区封顶 128 + 间隔/分隔线 37（SpaceXs4+Divider1+SpaceSm8+SpaceXs4+
// SpaceXs4+SpaceSm8+SpaceSm8）+ 场景钮行 56 + 遮罩行 48（M3 Slider）+ 容器透明度行 68（Slider 48 + C4
// 风险留白 20）= 337（dp 封顶或与 fontScale 无关）；说明句（bodySmall 单行 maxLines=1）≈ 16×fontScale。
// 内容区高低于（337 + 16×fontScale + 预览保底 60）→ weight 一屏布局会把预览压到 0 且底部行溢出被裁
// （fontScale 超高 / 矮横屏）→ 切滚动兜底（自然高 + verticalScroll）。默认字号内容不高恒走精确适配分支。
private const val REGION_FIXED_CHROME_DP = 337 // 区内固定高部件合计上限（dp；不含弹性预览/说明句）
private const val REGION_HINT_DP_PER_FONT_SCALE = 16 // 说明句单行高 ≈ bodySmall 行高 16sp × fontScale
private const val REGION_PREVIEW_FLOOR_DP = 60 // 精确适配时预览区需保底高度（dp）
private val FALLBACK_PREVIEW_HEIGHT = 180.dp // 滚动兜底布局中预览区固定高（滚动容器内 weight 弹性不适用）

/**
 * 颜色区（占标题下内容区高约 1/8）：左 1/6 色系入口圆钮 / 中竖分割线 / 右 5/6（收拢 = 当前色系具体色
 * 大圆点行；展开 = 色系代表色大圆点行）。v0.5.8d：两种右区行**同款切换**——同一位置同一容器（同一块
 * if/else 分支的 fillMaxHeight Row，无覆盖层/卡片/边框/阴影），差异仅为内容数组与选中回调；色系入口
 * 去文字改圆形按钮（展开态 primary 外环高亮）。
 */
@Composable
private fun ColorSectionRow(
    accentDraft: Long?,
    currentFamily: ColorFamily,
    familyExpanded: Boolean,
    onToggleFamilies: () -> Unit,
    onFamilySelected: (ColorFamily) -> Unit,
    onAccentSelected: (Long) -> Unit,
    onPickFromWallpaper: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 左 1/6：色系入口圆形按钮（无文字；当前色系双色半彩；展开 = 色系列表模式，primary 外环高亮；
        // 点按 toggle 色系行/具体色行）
        FamilyEntryStrip(
            family = currentFamily,
            expanded = familyExpanded,
            onClick = onToggleFamilies,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        )
        Spacer(Modifier.width(SpacingTokens.SpaceXs))
        // 中间竖分割线（左右宽度比固定 1:5）
        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .padding(vertical = SpacingTokens.SpaceXs)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        Spacer(Modifier.width(SpacingTokens.SpaceXs))
        // 右 5/6（v0.5.8d 同款切换）：展开态 = 色系代表色大圆点行 [FamilyDotsRow]；收拢态 = 当前色系具体
        // 颜色大圆色点横滑条。两态同一容器同一套样式（if/else 分支同构 fillMaxHeight），仅内容数组与选中
        // 回调不同——无覆盖层；最右固定「从壁纸取色」彩虹圆钮（API27+，两态均保留，v0.5.8b 不变）
        Row(
            modifier = Modifier
                .weight(5f)
                .fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (familyExpanded) {
                FamilyDotsRow(
                    currentFamily = currentFamily,
                    onFamilySelected = onFamilySelected,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
            } else {
                ConcreteColorRow(
                    family = currentFamily,
                    selected = accentDraft,
                    onSelected = onAccentSelected,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
            }
            // API27+（O_MR1 门；26 隐藏）「从壁纸取色」彩虹圆钮固定在右区末端（两态均保留）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                WallpaperPickEntry(
                    onClick = onPickFromWallpaper,
                    modifier = Modifier.padding(start = SpacingTokens.SpaceXs),
                )
            }
        }
    }
}

/**
 * 左 1/6 色系入口**圆形按钮**（v0.5.8d 去文字定稿）：填充当前色系的代表色**双色半彩示意**（左半 =
 * 代表色 [familySwatch] / 右半 = 其加深半彩 [familyEntryDuo]，左右分半同场景「统一壁纸」圆钮语言），
 * 无任何文字；展开态（右区 = 色系列表模式）= primary 2dp 外环高亮（同具体色点选中态样式 [AccentSwatch]），
 * 收拢态 = outline 2dp 外环（v0.5.12 md3-audit-2 C2：outlineVariant 1dp → outline 2dp，浅/白系色面在浅
 * 表面上的可辨描边 ≥3:1）。触达 48dp / 视觉 40dp 圆 + 外环。
 * 读屏 contentDescription 带色系名（视觉无字，无障碍仍可辨）；点按 = 色系行/具体色行 toggle。
 */
@Composable
private fun FamilyEntryStrip(
    family: ColorFamily,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val duo = familyEntryDuo(family)
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(SwatchTouchSize) // SH2：色系入口触达命中区（48dp ≥48×48）
                .clip(CircleShape)
                .clickable(onClick = onClick)
                .semantics {
                    selected = expanded
                    // v0.5.12 md3-audit-2 K1/A2：自制无字圆钮补单选 role（读屏报「单选按钮…已选中/未选中」）
                    role = Role.RadioButton
                    contentDescription = "色系入口：当前${family.name}色系（点按切换色系列表/具体色列表）"
                },
            contentAlignment = Alignment.Center,
        ) {
            // 外环底衬（先画，被半彩圆盖住中心、露外圈环）：尺寸/色档走 SH2 常量——底衬 SwatchRingBacking 44
            // = 半彩圆 SwatchDotSize 40 + 2×SwatchRing* 2dp；色档 = swatchRingColor(expanded) = 展开（选中）
            // primary / 收拢 outline（v0.5.12 C1/C2：outlineVariant 1dp → outline 2dp 后收拢 outline 即
            // 原 highContrast 档，两主题 ≥3:1 图形；与 [AccentSwatch]/[SceneDotButton] 同款环语言）
            Box(
                modifier = Modifier
                    .size(SwatchRingBacking)
                    .background(
                        color = swatchRingColor(expanded),
                        shape = CircleShape,
                    ),
            )
            // 40dp（SwatchDotSize）双色半彩圆（左=代表色 / 右=加深半彩；无文字）
            Row(
                modifier = Modifier
                    .size(SwatchDotSize)
                    .clip(CircleShape),
            ) {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(Color(duo.first)),
                )
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(Color(duo.second)),
                )
            }
        }
    }
}

/**
 * 色系列表（展开态，v0.5.8d 同款切换）：一排**色系代表色大圆点**横滑行——圆点视觉/触达与具体色点
 * [AccentSwatch] 完全同款（40dp 视觉圆点 + 选中 2dp primary 环 / 未选 2dp outline 环（v0.5.12
 * md3-audit-2 C2，原 outlineVariant 1dp 升档），无卡片、无边框容器、无文字，LazyRow 可左右滑动），圆点色 = 各色系代表色 [familySwatch]，选中环高亮 = 当前
 * 色系；点选某色系 → 收拢并切回该色系的具体色横滑行（[onFamilySelected]；同款切换，无过渡动画，简单
 * 状态切换）。取代 v0.5.8「点左入口向右展开 FilterChip 行」交互。
 */
@Composable
private fun FamilyDotsRow(
    currentFamily: ColorFamily,
    onFamilySelected: (ColorFamily) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        // v0.5.12 md3-audit-2 K1/A2：色系代表色大圆点行 = 单选组容器（selectableGroup；子圆点 role=RadioButton）
        modifier = modifier.selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SpacingTokens.SpaceXs),
    ) {
        items(COLOR_FAMILIES, key = { it.name }) { family ->
            AccentSwatch(
                argb = familySwatch(family),
                isSelected = family == currentFamily,
                onClick = { onFamilySelected(family) },
                // 读屏用色系名（视觉同具体色点：纯色圆点无文字）
                semanticLabel = "色系：${family.name}",
            )
        }
    }
}

/** 右 5/6 具体颜色（收拢态）：当前色系 HSV 同 hue 明暗连续**大圆色点**（10 点，LazyRow 可左右滑动）；
 *  点选即选中（选中态 = primary 2dp 圆环即时预览）。v0.5.8b：方块/小格 → 40dp 大圆形色点视觉收尾。 */
@Composable
private fun ConcreteColorRow(
    family: ColorFamily,
    selected: Long?,
    onSelected: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        // v0.5.12 md3-audit-2 K1/A2：具体色大圆色点行 = 单选组容器（selectableGroup；子圆点 role=RadioButton）
        modifier = modifier.selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SpacingTokens.SpaceXs),
    ) {
        items(cellsOf(family), key = { it }) { argb ->
            AccentSwatch(
                argb = argb,
                isSelected = argb == selected,
                onClick = { onSelected(argb) },
            )
        }
    }
}

/**
 * 具体颜色**大圆色点**（v0.5.8b：视觉 40dp / 触达 48dp，纯色无文字）：选中态 = 2dp primary 圆环描边；
 * 未选 = 2dp outline 环（v0.5.12 md3-audit-2 C2：outlineVariant 1dp 环 1.66:1 → outline 2dp，浅
 * #74777F 3.39:1 / 深 #8F9099 4.29:1 ≥3:1 图形，修浅色主题白/浅灰系色点 1.06:1 盲选）。
 * 环以「大圆底衬 + 色点覆盖中心」实现——色点外缘正好露环宽，无需描边半宽换算。
 *
 * v0.5.8d：色系代表色圆点行 [FamilyDotsRow] 复用本件（保证与具体色点同尺寸同视觉）——仅读屏文案可经
 * [semanticLabel] 换色系名；具体色调用不传 → 默认 hex 文案（v0.5.8b 不变）。
 */
@Composable
private fun AccentSwatch(
    argb: Long,
    isSelected: Boolean,
    onClick: () -> Unit,
    // v0.5.8d：色系行圆点 [FamilyDotsRow] 复用同款视觉时传色系读屏文案（如「色系：红」）；null = 具体色 hex 默认
    semanticLabel: String? = null,
) {
    Box(
        modifier = Modifier
            .size(SwatchTouchSize) // SH2：色点触达命中区（48dp ≥48×48）
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = semanticLabel ?: if (isSelected) {
                    "已选强调色 ${accentHex(argb)}"
                } else {
                    "强调色 ${accentHex(argb)}"
                }
                selected = isSelected
                // v0.5.12 md3-audit-2 K1/A2：自制单选点补 role（读屏报「单选按钮…已选中/未选中」）
                role = Role.RadioButton
            },
        contentAlignment = Alignment.Center,
    ) {
        // 外环底衬（先画，被色点盖住中心、露外圈环）：尺寸/色档走 SH2——底衬 SwatchRingBacking 44 =
        // 色点 SwatchDotSize 40 + 2×SwatchRing 2dp；色档 swatchRingColor(isSelected) = 选中 primary /
        // 未选 outline（v0.5.12 C1/C2 未选 outlineVariant 1dp 1.66:1 → outline 2dp：浅 #74777F 3.39:1 /
        // 深 #8F9099 4.29:1 ≥3:1 图形，修浅色主题白/浅灰系色点盲选）
        Box(
            modifier = Modifier
                .size(SwatchRingBacking)
                .background(
                    color = swatchRingColor(isSelected),
                    shape = CircleShape,
                ),
        )
        // 40dp（SwatchDotSize）大圆色点（纯色填充，无文字）
        Box(
            modifier = Modifier
                .size(SwatchDotSize)
                .clip(CircleShape)
                .background(Color(argb)),
        )
    }
}

/**
 * 「从壁纸取色」入口（API27+ / O_MR1 门，26 无此入口；固定在右区末端，两态均保留）：v0.5.8b 由
 * 「渐变图标 + 取色字样」改为**彩虹 sweepGradient 圆形按钮**（44–48dp，圆内 8 色 sweep，仿 HTML
 * conic-gradient 同序：#ff0000→#ff8800→#ffff00→#00cc44→#0088ff→#6633ff→#ff00cc→#ff0000，首尾同红
 * 环向无缝），**无文字**（读屏 contentDescription =「从壁纸取色」）；点击行为不变（取系统壁纸主色
 * 入 accentDraft + Snackbar「已取壁纸主色（保存后生效）」）。
 */
@Composable
private fun WallpaperPickEntry(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(rainbowSweepBrush())
            .clickable(onClick = onClick)
            .semantics { contentDescription = "从壁纸取色" },
    )
}

/** 取色圆钮彩虹 brush：8 色均匀环布（同 conic-gradient 默认等分布），首尾同红（#ff0000）环向无缝。 */
private fun rainbowSweepBrush(): Brush = Brush.sweepGradient(
    colors = listOf(
        Color(0xFFFF0000),
        Color(0xFFFF8800),
        Color(0xFFFFFF00),
        Color(0xFF00CC44),
        Color(0xFF0088FF),
        Color(0xFF6633FF),
        Color(0xFFFF00CC),
        Color(0xFFFF0000),
    ),
)

/** 场景三按钮（v0.5.8b 无字圆形按钮，居中横排）：统一壁纸（兜底）= 左半深灰/右半浅灰圆（竖向中线分半）、
 *  深色模式壁纸 = 深灰圆、浅色模式壁纸 = 浅灰圆；当前场景 = primary 2dp 外环高亮；点击切 sceneSlot 不变。 */
@Composable
private fun SceneSwitchRow(
    sceneSlot: Int,
    onSceneSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        // v0.5.12 md3-audit-2 K1/A2：场景三钮行 = 单选组容器（selectableGroup；子钮 role=RadioButton）
        modifier = modifier.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(SpacingTokens.SpaceLg, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SceneDotButton(
            slotId = WallpaperStore.SLOT_UNIFIED,
            isSelected = sceneSlot == WallpaperStore.SLOT_UNIFIED,
            onClick = { onSceneSelected(WallpaperStore.SLOT_UNIFIED) },
        )
        SceneDotButton(
            slotId = WallpaperStore.SLOT_DARK,
            isSelected = sceneSlot == WallpaperStore.SLOT_DARK,
            onClick = { onSceneSelected(WallpaperStore.SLOT_DARK) },
        )
        SceneDotButton(
            slotId = WallpaperStore.SLOT_LIGHT,
            isSelected = sceneSlot == WallpaperStore.SLOT_LIGHT,
            onClick = { onSceneSelected(WallpaperStore.SLOT_LIGHT) },
        )
    }
}

/** 场景无字圆钮（视觉 52dp 圆 + 外环，触达 56dp）：选中 = primary 2dp 外环高亮；未选 = outline 2dp 外环
 * （v0.5.12 md3-audit-2 C1 修复：outlineVariant 1dp → outline 2dp——浅主题浅灰钮 on 浅底 1.19:1 / 深主题
 * 深灰钮 on 深底 1.63:1 的「同色调钮」兜底可辨，outline 环浅 #74777F 3.39:1 / 深 #8F9099 4.29:1 ≥3:1；
 * 两主题同加保持视觉一致；填充数据灰阶保留，见 [SceneDotFill] 注释）。无文字，读屏
 * contentDescription =「场景：…」。 */
@Composable
private fun SceneDotButton(
    slotId: Int,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(SceneDotTouch) // SH2：场景钮触达命中区 = 环底衬直径（56dp ≥48 触达）
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics {
                selected = isSelected
                // v0.5.12 md3-audit-2 K1/A2：场景三钮 = 单选组（读屏报「单选按钮…已选中/未选中」）
                role = Role.RadioButton
                contentDescription = "场景：${sceneName(slotId)}"
            },
        contentAlignment = Alignment.Center,
    ) {
        // 外环底衬（先画，被填充圆盖住中心、露外圈环）：尺寸/色档走 SH2——底衬 SceneDotTouch 56 =
        // 填充圆 SceneFillSize 52 + 2×SwatchRing 2dp；色档 swatchRingColor(isSelected) = 选中 primary /
        // 未选 outline（C1 方案② outline 2dp 外环兜底；填充数据灰阶保留，见 [SceneDotFill] 注释）
        Box(
            modifier = Modifier
                .size(SceneDotTouch)
                .background(
                    color = swatchRingColor(isSelected),
                    shape = CircleShape,
                ),
        )
        // 52dp（SceneFillSize）填充圆（统一 = 左右半灰分半）
        SceneDotFill(slotId = slotId, modifier = Modifier.size(SceneFillSize))
    }
}

/** 圆钮填充（统一槽 = 左半深灰/右半浅灰，竖向中线分半；深槽 = 深灰；浅槽 = 浅灰）。 */
@Composable
private fun SceneDotFill(slotId: Int, modifier: Modifier = Modifier) {
    Box(modifier = modifier.clip(CircleShape)) {
        when (slotId) {
            WallpaperStore.SLOT_DARK -> Box(Modifier.fillMaxSize().background(SCENE_DARK_GRAY))
            WallpaperStore.SLOT_LIGHT -> Box(Modifier.fillMaxSize().background(SCENE_LIGHT_GRAY))
            else -> Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight().background(SCENE_DARK_GRAY))
                Box(Modifier.weight(1f).fillMaxHeight().background(SCENE_LIGHT_GRAY))
            }
        }
    }
}

/** 场景圆钮图形灰阶（v0.5.8b 无字圆钮数据色，非语义 token；规格：#3A3A3A 深灰 / #E8E8E8 浅灰）。
 * 对比说明（v0.5.12 md3-audit-2 C1）：填充数据灰阶保留（数据色豁免语义 token）——浅灰 #E8E8E8 直贴浅底
 * 1.19:1、深灰 #3A3A3A 直贴深底 1.63:1，两主题各有「同色调钮」贴近背景；可辨性由 [SceneDotButton]
 * 未选态 outline 2dp 外环兜底（浅 #74777F 3.39:1 / 深 #8F9099 4.29:1 ≥3:1 图形），不靠改填充灰阶。 */
private val SCENE_DARK_GRAY = Color(0xFF3A3A3A)

private val SCENE_LIGHT_GRAY = Color(0xFFE8E8E8)

/** 当前场景说明句（v0.5.8b：无字圆钮行与预览区之间的一行；正文按规格原文，勿自改文案）。 */
private fun sceneHint(slotId: Int): String = when (slotId) {
    WallpaperStore.SLOT_DARK -> "深色模式：深色模式壁纸，可覆盖通用壁纸"
    WallpaperStore.SLOT_LIGHT -> "浅色模式：浅色模式壁纸，可覆盖通用壁纸"
    else -> "通用模式：可以通过上方按钮切换深浅模式壁纸"
}

/** 复用预览区（弹性占剩余大部）：渲染当前场景槽草稿 = 壁纸图 + 当前遮罩（复用 WallpaperBackdrop 同套
 *  rememberSlotDecode/WallpaperEffect，预览即真实背景效果——WallpaperEffect 为壁纸+遮罩两态，v0.5.12b
 *  无整区叠层）；壁纸解码成功后其上再叠 [HomeContainerMock]「模拟主页内容浮层卡」（v0.5.12b 方案 B：
 *  圆角表面块 = 主页同款色 surfaceContainerLow/Lowest，块 alpha 按透明度草稿
 *  (100f-transparencyDraft)/100f 实时变化，卡外/卡间露壁纸）。
 * 槽未设 → 占位「点击选择壁纸」；点预览区 → 打开 [WallpaperSourceSheet]。
 * v0.5.8b：解码失败不再无限「加载壁纸预览…」——failed 时显示可见失败文案 + 来源小字提示
 * （解码中的短暂空白可忽略：复制后才入槽，file 路径直读极快 <300ms）。 */
@Composable
private fun PreviewSection(
    slot: WallpaperSlot,
    sceneSlot: Int,
    maskAlpha: Int,
    // v0.5.12b：容器透明度草稿（5–50%，WallpaperStore.TRANSPARENCY_MIN..MAX；浮层卡 mock 换算 alpha 用）
    transparencyDraft: Int,
    onOpenSource: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // 同套解码（预览上限 PREVIEW_MAX_DIM=720px）：槽 type+uri 为 key——仅遮罩/场景外改动不重复解码
    val decode = rememberSlotDecode(context = context, slot = slot, maxDim = PREVIEW_MAX_DIM)
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onOpenSource)
                .semantics {
                    role = Role.Button
                    contentDescription = "壁纸预览：${sceneName(sceneSlot)}，点击选择壁纸来源"
                },
        ) {
            if (slot.isSet) {
                // 壁纸图 + 遮罩叠加（与主页面背景同函数同遮罩色；无整区叠层——WallpaperEffect 自
                // v0.5.12b 恢复两态）；解码成功后其上叠模拟主页浮层卡（卡块 alpha 随透明度草稿即时变化）
                WallpaperEffect(
                    wallpaper = decode.bitmap,
                    maskAlpha = maskAlpha,
                    modifier = Modifier.fillMaxSize(),
                )
                // v0.5.12b（方案 B）：壁纸（+遮罩）之上叠 [HomeContainerMock] 两块主页同款色圆角表面块，
                // 卡外/卡间露壁纸；解码未成功前不叠（避免盖住解码中观感，失败时下方文案不被遮挡）
                if (decode.bitmap != null && !decode.failed) {
                    HomeContainerMock(
                        transparencyDraft = transparencyDraft,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (decode.failed) {
                    // v0.5.8b：解码失败可见——失败被静默吞掉是 v0.5.8「两个来源都不显示」观感根因之一，
                    // 现显示主文案 + 来源小字提示（点按整区可更换来源）；解码中的短暂空白不提示
                    val hint = when (slot.type) {
                        WallpaperSlot.TYPE_SYSTEM ->
                            "跟随系统壁纸可能被系统限制，可改用自选图片"
                        else ->
                            "图片文件不可读或已移动，可重新自选一张"
                    }
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = "壁纸加载失败/不可用，点按更换来源",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(SpacingTokens.SpaceXs))
                        Text(
                            text = hint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                // 槽未设占位：图标 + 「点击选择壁纸」（v0.5.8d 文案居中核对：Column 水平居中排列 + 文案显式
                // TextAlign.Center，与场景说明句一致居中；解码失败文案同在此 Column 居中布局内）
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    PicturePlaceholderIcon()
                    Spacer(Modifier.height(SpacingTokens.SpaceMd))
                    Text(
                        text = "点击选择壁纸",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(SpacingTokens.SpaceXs))
                    Text(
                        text = "跟随系统壁纸或自选图片",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/** v0.5.12b（方案 B）模拟主页内容浮层卡：壁纸预览之上叠两块圆角表面块示意主页容器浮在壁纸上、
 *  卡外/卡间露壁纸（贴主页结构示意，不追求精确）：
 * - 上部大圆角块（弹性占余下大部，随预览区高度浮动 ≈ 高 60–75%）：模拟主页「两态」内容区主层次卡
 *   （本端设备区/对端列表，主页同款 [MaterialTheme.shapes.large] 块级圆角 10）→ 色 = 主页同款
 *   surfaceContainerLow（MainScreen SelfDevicePane/ScanListPanel 同色）；
 * - 下部窄横条（高 ≈ 预览高 18%，紧贴预览区底边）：模拟底部动作行/时间流 → 色 = surfaceContainerLowest
 *   （MainScreen BottomActionRow 同色）；
 * 两块均四周留 ~8dp 边距（圆角块与预览区边缘/两块之间露壁纸）；每块色
 * copy(alpha = (100f - transparencyDraft) / 100f)（透明度草稿越高卡越透明、壁纸透出越多；默认 20 →
 * 0.80 与主页一致）；滑杆拖动 → [PreviewSection] recompose → 块 alpha 即时变化，直观反映主页观感。
 */
@Composable
private fun HomeContainerMock(
    transparencyDraft: Int,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 上部大圆角块：模拟主页两态内容区主层次卡（surfaceContainerLow）；块外四周露壁纸
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(MaterialTheme.shapes.large)
                .background(
                    MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = (100f - transparencyDraft) / 100f),
                ),
        )
        // 下部窄横条（高 ≈ 预览高 18%）：模拟底部动作行/时间流（surfaceContainerLowest）；两卡间露壁纸
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.18f)
                .clip(MaterialTheme.shapes.large)
                .background(
                    MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = (100f - transparencyDraft) / 100f),
                ),
        )
    }
}

/** 占位「图片」装饰图标（外框 + 内圆，纯装饰不读屏）。 */
@Composable
private fun PicturePlaceholderIcon() {
    val line = MaterialTheme.colorScheme.outlineVariant
    Box(contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .border(2.dp, line, MaterialTheme.shapes.large),
        )
        Box(
            modifier = Modifier
                .size(18.dp)
                .border(2.dp, line, CircleShape),
        )
    }
}

/** 遮罩区（底部固定 · 全局共用）：「遮罩」+ Slider 0–80%（5% 步进，显示百分比）；拖动即页内预览。 */
@Composable
private fun MaskRow(
    maskAlpha: Int,
    onMaskChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "遮罩",
            style = MaterialTheme.typography.labelLarge,
            // S2（md3-audit-2 P2）：固定文本列 48dp → widthIn(min=48.dp)——min 保底使默认 1x 几何与固定列一致；
            // fontScale 超高（2x CJK）列随内容放宽不截断；maxLines=1+ellipsis 兜窄屏不折行不错位
            modifier = Modifier.widthIn(min = 48.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Slider(
            value = maskAlpha.toFloat(),
            onValueChange = { onMaskChange(it.toInt()) },
            valueRange = 0f..WallpaperStore.MASK_MAX.toFloat(),
            steps = WallpaperStore.MASK_MAX / 5 - 1, // 5% 步进（0/5/…/80）
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "$maskAlpha%",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // S2：% 列同改 widthIn(min=44.dp)（默认几何不变；2x「80%」需 ~54dp 全显示）+ ellipsis 兜窄屏
            modifier = Modifier.widthIn(min = 44.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** v0.5.11 UI1b-E 改④：容器透明度区（遮罩行下方 · 全局共用）：文字「容器透明度」+ Slider 5–50%
 * （5% 步进，显示百分比；同遮罩行样式）。语义 = 主页浮层容器「透明程度」：容器实际 alpha = 1−值/100
 * （5→0.95 … 50→0.50，默认 20 → 0.80 与 v0.5.8d 顶栏浮层规格一致）；拖动只改本地草稿
 * transparencyDraft（v0.5.12b 起上方壁纸预览区以浮层卡模拟同步实时预览——壁纸之上叠主页同款圆角
 * 表面块，卡 alpha 随草稿即时变化），保存写
 * store.containerTransparency 后主页生效。
 * v0.5.12 md3-audit-2 C4：透明度 ≥40%（容器 alpha ≤0.60）为文字可读风险区（深壁纸 + 低遮罩下容器文字
 * 对比可跌破 4.5:1 且无自动钳制）——滑杆下方常驻固定高度留白位内显示 warning 色风险提示（v0.5.13
 * 改固定留白位：动态出现 → 原位填入、行高不变；50% 档 = alpha 0.50 下限提示最强；阈值常量见
 * [WallpaperStore.RISK_TRANSPARENCY_THRESHOLD]）。 */
@Composable
private fun ContainerTransparencyRow(
    transparency: Int,
    onTransparencyChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "容器透明度",
                style = MaterialTheme.typography.labelLarge,
                // S2（md3-audit-2 P2）：固定文本列 96dp → widthIn(min=96.dp)——5 字 ×2x labelLarge ≈140dp，
                // 固定 96dp 原会折行错位；min 保底默认 1x 几何不变、超高列随内容放宽全显示，单行不折行
                modifier = Modifier.widthIn(min = 96.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Slider(
                value = transparency.toFloat(),
                onValueChange = { onTransparencyChange(it.toInt()) },
                valueRange = WallpaperStore.TRANSPARENCY_MIN.toFloat()..WallpaperStore.TRANSPARENCY_MAX.toFloat(),
                // 5% 步进（5/10/…/50）：steps = 中间离散点数 = (50−5)/5 − 1 = 8
                steps = (WallpaperStore.TRANSPARENCY_MAX - WallpaperStore.TRANSPARENCY_MIN) / 5 - 1,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "$transparency%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // S2：% 列同改 widthIn(min=44.dp)（默认几何不变）+ ellipsis 兜窄屏
                modifier = Modifier.widthIn(min = 44.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // v0.5.12 md3-audit-2 C4（v0.5.13 改固定留白位）：风险提示行——滑杆行下方常驻固定高度留白区
        // （20dp ≈ SpaceXs 顶距 4dp + labelSmall 单行 16sp 行高；无风险时空白保留）→ 透明度 ≥40%
        // （alpha ≤0.60 风险区）时提示文字原地填入该固定区（50% 档 = alpha 0.50 下限，提示最强；文案随档
        // 切换——取审计轻量组合①，不引入联动钳制机制）。提醒出现/消失/长短文案切换均不改变行高 →
        // 页面其它元素位置零变动。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(20.dp),
        ) {
            if (transparency >= WallpaperStore.RISK_TRANSPARENCY_THRESHOLD) {
                Text(
                    text = if (transparency >= WallpaperStore.TRANSPARENCY_MAX) {
                        "50% 时文字可读性下降风险较高"
                    } else {
                        "较高透明度可能影响文字可读"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.extended.warning,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 96.dp, top = SpacingTokens.SpaceXs),
                )
            }
        }
    }
}

/** 壁纸来源弹层（ModalBottomSheet 浮层面板，不透明）：跟随系统壁纸 / 自选图片（SAF）/ 清除（仅已设显示）。
 *  选择直接写当前场景槽草稿（页内即时预览），保存才写 prefs。 */
@Composable
private fun WallpaperSourceSheet(
    sceneSlot: Int,
    current: WallpaperSlot,
    onDismiss: () -> Unit,
    onFollowSystem: () -> Unit,
    onPickImage: () -> Unit,
    onClear: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SpacingTokens.SpaceLg)
                .padding(bottom = SpacingTokens.SpaceMd),
            verticalArrangement = Arrangement.spacedBy(SpacingTokens.SpaceXs),
        ) {
            Text(
                text = "${sceneName(sceneSlot)}：选择来源",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(SpacingTokens.SpaceXs))
            SourceSheetOption(
                title = "跟随系统壁纸",
                subtitle = "实时使用系统当前壁纸图片",
                onClick = onFollowSystem,
            )
            SourceSheetOption(
                title = "自选图片",
                subtitle = "从相册/文件选择（自动复制到应用目录）",
                onClick = onPickImage,
            )
            if (current.isSet) {
                SourceSheetOption(
                    title = "清除",
                    subtitle = "恢复默认纯色背景",
                    destructive = true,
                    onClick = onClear,
                )
            }
        }
    }
}

/** 来源弹层选项行（≥48dp 触达；title + subtitle 双行）。 */
@Composable
private fun SourceSheetOption(
    title: String,
    subtitle: String,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .semantics { role = Role.Button }
            .padding(horizontal = SpacingTokens.SpaceSm, vertical = SpacingTokens.SpaceSm),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = if (destructive) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/** 场景槽 id → 展示名（场景按钮 / 弹层标题共用）。 */
private fun sceneName(slotId: Int): String = when (slotId) {
    WallpaperStore.SLOT_DARK -> "深色模式壁纸"
    WallpaperStore.SLOT_LIGHT -> "浅色模式壁纸"
    else -> "统一壁纸"
}

/** SAF 选图后持久读授权（v0.5.7 起：修未持久授权、重启后 uri 失效问题；provider 不支持时忽略不崩溃。
 *  v0.5.8b 起选图即复制进私有目录、槽存文件路径，持久授权不再是新数据必需——保留尝试仅为兼容
 *  老存量 content:// 数据链路，失败无影响）。 */
private fun persistUriReadPermission(context: Context, uri: Uri) {
    try {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
    } catch (t: Throwable) {
        // 部分 provider（相册/DocumentsProvider 等）不支持持久授权：忽略（本次会话仍可读，重启后需重选图）
    }
}

// ==================== v0.5.8b 自选图 → 私有目录副本（修「自选图不显示 / 重启后丢失」） ====================

/** 私有壁纸副本子目录名（App filesDir 下；自选图选后立即复制至此，重启必在、读取无需 provider 授权）。 */
private const val WALLPAPERS_DIR = "wallpapers"

/** 复制缓冲 8KB（java.io 循环拷贝，无新依赖）。 */
private const val DEFAULT_COPY_BUFFER = 8 * 1024

/**
 * SAF content:// 自选图 → App 私有目录（filesDir/wallpapers/，不存在则 mkdirs）**立即复制**，
 * 文件名 `w_<System.currentTimeMillis()>.<ext>`（ext 按 mime 推断，见 [imageExtensionOf]），
 * 返回**文件绝对路径**。修 v0.5.8「SAF grant 重启失效（takePersistable 不一定成功）+ content:// 双次
 * openInputStream 部分 ROM 不稳 → 自选图不显示/重启丢」：存绝对路径后 BitmapFactory.decodeFile 直读
 * （无需授权、重启必在、读取最稳）。
 * 复制失败/文件不可读 → null（调用方 Snackbar 提示、不写槽；半成品文件尽力清理）。调用方保证 IO 线程。
 */
private fun copyPickedToPrivateDir(context: Context, uri: Uri): String? {
    val dir = File(context.filesDir, WALLPAPERS_DIR)
    var target: File? = null
    return try {
        if (!dir.exists() && !dir.mkdirs()) return null
        val dest = File(dir, "w_${System.currentTimeMillis()}.${imageExtensionOf(context, uri)}")
        target = dest
        val input = context.contentResolver.openInputStream(uri) ?: return null
        input.use { src ->
            FileOutputStream(dest).use { dst ->
                val buffer = ByteArray(DEFAULT_COPY_BUFFER)
                while (true) {
                    val read = src.read(buffer)
                    if (read < 0) break
                    dst.write(buffer, 0, read)
                }
            }
        }
        dest.absolutePath
    } catch (t: Throwable) {
        target?.delete() // 尽力清理半成品（忽略失败）
        if (t is CancellationException) throw t // 页面离开取消复制：向上传播（不写槽、不误报 Snackbar）
        null
    }
}

/** content:// mime → 副本扩展名（image/jpeg→jpg / image/png→png / image/webp→webp / 其它或取不到→img）。 */
private fun imageExtensionOf(context: Context, uri: Uri): String {
    val mime = try {
        context.contentResolver.getType(uri)
    } catch (t: Throwable) {
        null
    }
    return when (mime?.lowercase(Locale.US)) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        else -> "img"
    }
}

/** 槽 uri 是否指向私有壁纸副本（filesDir/wallpapers 下的绝对路径；清除槽时随槽 IO 删除本地文件用）。 */
private fun isPrivateWallpaperFile(context: Context, uri: String?): Boolean {
    if (uri == null || !uri.startsWith("/")) return false
    val dirPrefix = File(context.filesDir, WALLPAPERS_DIR).absolutePath
    return uri.startsWith("$dirPrefix/")
}

// ==================== 色系 / HSV 色板（用户可选数据值，非语义 token） ====================
// 无第三方色板库（无 material-color-utilities/coil）——色系与具体色用 HSV 生成：同 hue 固定饱和、
// value 明暗连续 10 格（色系内深浅/明暗渐变）；灰/白/黑为低饱和中性系。色值都是用户可选数据值，
// 与 theme 包的语义 token 不同层（裸 hex 限制不约束本文件色板数据）。

/** 色系定义：hue 基色相 / saturation 饱和 / valueHigh→valueLow 明度连续区间（中性系 sat≈0 忽略 hue）。 */
private data class ColorFamily(
    val name: String,
    val hue: Float,
    val saturation: Float,
    val valueHigh: Float,
    val valueLow: Float,
)

/** 每色系具体色格数（规格 8–12 格取 10）。 */
private const val CELL_COUNT = 10

// 13 色系（规格：红橙黄绿青蓝紫品粉棕灰白黑）；棕色 = 低明橙红（v≤0.62 判棕，见 accentFamilyOf）
private val FAMILY_RED = ColorFamily("红", 355f, 0.85f, 0.96f, 0.30f)
private val FAMILY_ORANGE = ColorFamily("橙", 30f, 0.90f, 0.96f, 0.30f)
private val FAMILY_YELLOW = ColorFamily("黄", 58f, 0.90f, 0.95f, 0.28f)
private val FAMILY_GREEN = ColorFamily("绿", 130f, 0.85f, 0.90f, 0.24f)
private val FAMILY_CYAN = ColorFamily("青", 180f, 0.85f, 0.93f, 0.28f)
private val FAMILY_BLUE = ColorFamily("蓝", 220f, 0.90f, 0.93f, 0.30f)
private val FAMILY_PURPLE = ColorFamily("紫", 268f, 0.80f, 0.94f, 0.28f)
private val FAMILY_MAGENTA = ColorFamily("品", 305f, 0.90f, 0.96f, 0.32f)
private val FAMILY_PINK = ColorFamily("粉", 338f, 0.55f, 0.98f, 0.55f)
private val FAMILY_BROWN = ColorFamily("棕", 28f, 0.75f, 0.72f, 0.20f)
// 中性系（s 近 0）：白（略冷 tint 保证渐变格可见）/ 灰 / 黑
private val FAMILY_WHITE = ColorFamily("白", 220f, 0.05f, 0.98f, 0.87f)
private val FAMILY_GRAY = ColorFamily("灰", 0f, 0f, 0.84f, 0.32f)
private val FAMILY_BLACK = ColorFamily("黑", 0f, 0f, 0.29f, 0.02f)

/** 色系展示顺序（规格序：红橙黄绿青蓝紫品粉棕灰白黑）。 */
private val COLOR_FAMILIES: List<ColorFamily> = listOf(
    FAMILY_RED, FAMILY_ORANGE, FAMILY_YELLOW, FAMILY_GREEN, FAMILY_CYAN, FAMILY_BLUE,
    FAMILY_PURPLE, FAMILY_MAGENTA, FAMILY_PINK, FAMILY_BROWN,
    FAMILY_GRAY, FAMILY_WHITE, FAMILY_BLACK,
)

/** HSV → ARGB Long（alpha FF；中性系 sat=0 忽略 hue）。 */
private fun hsvToArgb(hue: Float, saturation: Float, value: Float): Long =
    (AndroidColor.HSVToColor(floatArrayOf(hue, saturation, value)).toLong() and 0xFFFFFFFFL)

/** 某色系的明暗连续格（valueHigh→valueLow 等差 10 格；色调由色系 hue+sat 固定）。 */
private fun cellsOf(family: ColorFamily): List<Long> = List(CELL_COUNT) { i ->
    val t = i.toFloat() / (CELL_COUNT - 1)
    hsvToArgb(
        hue = family.hue,
        saturation = family.saturation,
        value = family.valueHigh + (family.valueLow - family.valueHigh) * t,
    )
}

/** 色系代表色（供色块/色点/取色图标；取第 5 格——亮度居中偏暗，深浅主题下都可见）。 */
private fun familySwatch(family: ColorFamily): Long = cellsOf(family)[4]

/** 色系入口圆钮双色半彩（v0.5.8d）：代表色 + 其加深半彩（同 hue/sat、value×0.5——两半示意色系深浅跨度；
 *  中性系 sat≈0 同样加深明度）。 */
private fun familyEntryDuo(family: ColorFamily): Pair<Long, Long> {
    val swatch = familySwatch(family)
    val hsv = FloatArray(3)
    AndroidColor.colorToHSV((swatch and 0xFFFFFFFFL).toInt(), hsv)
    return swatch to hsvToArgb(hsv[0], hsv[1], hsv[2] * 0.5f)
}

/** ARGB Long → 色系归类（进入页面初值用；HSV 色相分段 + 中性/棕特判）。 */
private fun accentFamilyOf(argb: Long): ColorFamily {
    val hsv = FloatArray(3)
    AndroidColor.colorToHSV((argb and 0xFFFFFFFFL).toInt(), hsv)
    val h = hsv[0]
    val s = hsv[1]
    val v = hsv[2]
    if (s < 0.08f) {
        // 中性系（灰/白/黑）：按明度分档
        return when {
            v >= 0.85f -> FAMILY_WHITE
            v <= 0.30f -> FAMILY_BLACK
            else -> FAMILY_GRAY
        }
    }
    if (v <= 0.62f && h in 8f..48f) return FAMILY_BROWN // 低明橙红 = 棕
    return when (h) {
        in 348f..360f, in 0f..14f -> FAMILY_RED
        in 14f..52f -> FAMILY_ORANGE
        in 52f..80f -> FAMILY_YELLOW
        in 80f..165f -> FAMILY_GREEN
        in 165f..205f -> FAMILY_CYAN
        in 205f..252f -> FAMILY_BLUE
        in 252f..288f -> FAMILY_PURPLE
        in 288f..320f -> FAMILY_MAGENTA
        in 320f..348f -> FAMILY_PINK
        else -> FAMILY_BLUE // 兜底（品牌默认蓝系）
    }
}

/** ARGB Long → #RRGGBB（无障碍内容描述用）。 */
private fun accentHex(argb: Long): String =
    String.format(Locale.US, "#%06X", (argb and 0xFFFFFFL))
