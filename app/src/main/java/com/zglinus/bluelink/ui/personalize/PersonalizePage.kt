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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zglinus.bluelink.ui.BluelinkUiState
import com.zglinus.bluelink.ui.theme.SpacingTokens
import java.util.Locale
import android.graphics.Color as AndroidColor

/**
 * 个性化页（抽屉 2 / BluelinkUiState.PAGE_PERSONAL；v0.5.8 UI1b-B2 整页重做，覆盖 v0.5.7 UI1b-B
 * 三槽长表单版——真机反馈 v0.5.7「主页面背景不变」由 MainScreen HOME 浮层化修复（另见
 * ui/MainScreen.kt RootWallpaperLayer/HOME_FLOAT_ALPHA），本页只管草稿编辑与保存）。
 *
 * 布局（docs/ui-design.md §4.1b v0.5.8 定稿；竖屏无上下滚动、一屏放完）：
 * - 顶部条：左「个性化」标题 / 右上「保存」（保存为最右角按钮；返回主页面入口与同级子页一致
 *   放标题右侧、保存左侧——规格图仅画 [保存]，本页为抽屉子页（主页面不列抽屉项、无 BackHandler），
 *   无返回即死胡同，故补 [返回]，保存仍保持右上角）；
 * - 颜色区（约占标题下内容区高 1/8，BoxWithConstraints 取 12.5% 收 80–128dp）：
 *   左 1/6「色系入口」窄条（当前色系色块 + 名称；点按展开/收拢色系列表）+ 中间竖分割线 +
 *   右 5/6「具体颜色」（当前色系 HSV 明暗连续 10 格，LazyRow 可左右滑动，点选即选中——
 *   选中格即时描边/色块预览）；色系展开态右区切换为横向色系 chips（红橙黄绿青蓝紫品粉棕灰白黑，
 *   选中后收拢并切换该色系取色）；API27+（O_MR1 门，26 隐藏）「从壁纸取色」入口固定在右区末端，
 *   取到的壁纸主色同样先入选中态（保存才生效）；
 * - 壁纸区：场景三按钮（统一壁纸（兜底）/ 深色模式壁纸 / 浅色模式壁纸，对应 [WallpaperStore]
 *   SLOT_UNIFIED/SLOT_DARK/SLOT_LIGHT，当前场景高亮）+ 弹性复用预览区：渲染当前场景槽草稿 =
 *   壁纸图 + 当前遮罩草稿叠加（复用 ui/personalize/WallpaperBackdrop.kt 同套渲染/解码函数
 *   [rememberSlotBitmap]/[WallpaperEffect]，预览即真实背景效果）；槽未设 → 占位（图标 +
 *   「点击选择壁纸」）；点预览区 → [WallpaperSourceSheet] 三选项：跟随系统壁纸 / 自选图片（SAF）/
 *   清除（清除仅已设时显示）；
 * - SAF 选图后必须 contentResolver.takePersistableUriPermission(uri, READ)（try/catch 包住不崩溃，
 *   修 v0.5.7 未持久授权导致重启失效问题；见 [persistUriReadPermission]）；
 * - 遮罩区（底部固定 · 全局共用）：「遮罩」+ Slider 0–80%（显示百分比），拖动即页内预览
 *   （预览区遮罩同步变）。
 *
 * 保存语义（v0.5.8 新交互）：页面持本地编辑态（三槽草稿 / maskAlpha 草稿 / accent 草稿 / 当前场景 /
 * 色系展开与选中态），进入页面从 [WallpaperStore] 读初值；任何改动只改本地态并即时页内预览
 * （不写 prefs，保存前主页面背景与主题不变）。右上「保存」一次性写 prefs（三槽 setSlot×3 +
 * maskAlpha + accentColor）→ `ui.wallpaperTick++`（主页面背景刷新，见 [BluelinkUiState.wallpaperTick]）
 * → [onSaved] 上抛强调色（MainActivity 主题 state → BluelinkTheme(accent) 重算 primary 系）
 * → Snackbar「已保存」。离开页面未保存 = 丢弃草稿（remember 随页面出组合失效，重进从 prefs 重读；
 * 首版不做未保存提示）。强调色未选/null → 主题用默认 M3 品牌蓝派生，不覆写。
 */
@Composable
fun PersonalizePage(
    ui: BluelinkUiState,
    // v0.5.8 UI1b-B2：保存回调（保存的强调色 ARGB Long？null=未选/清除）→ MainActivity 主题强调色 state
    onSaved: (Long?) -> Unit = {},
) {
    val context = LocalContext.current
    val store = remember { WallpaperStore(context.applicationContext) }

    // ==================== v0.5.8 本地编辑态（草稿；保存才写 prefs） ====================
    var unifiedDraft by remember { mutableStateOf(store.slot(WallpaperStore.SLOT_UNIFIED)) }
    var darkDraft by remember { mutableStateOf(store.slot(WallpaperStore.SLOT_DARK)) }
    var lightDraft by remember { mutableStateOf(store.slot(WallpaperStore.SLOT_LIGHT)) }
    var maskDraft by remember { mutableStateOf(store.maskAlpha) }
    var accentDraft by remember { mutableStateOf(store.accentColor) }
    // 当前场景（场景三按钮高亮 + 预览区渲染该槽草稿）
    var sceneSlot by remember { mutableStateOf(WallpaperStore.SLOT_UNIFIED) }
    // 色系展开态：false=右区显示当前色系具体色；true=右区展开色系列表 chips（选中后收拢切换）
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

    // 自选图片（SAF GetContent，image/*）：持久读授权（try/catch 不崩溃）→ 写目标场景槽草稿
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        val slotId = pickingSlot ?: return@rememberLauncherForActivityResult
        pickingSlot = null
        if (uri != null) {
            persistUriReadPermission(context, uri)
            setSlotDraft(slotId, WallpaperSlot(type = WallpaperSlot.TYPE_URI, uri = uri.toString()))
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

    // 「保存」：一次性写 prefs（三槽 + mask + accent）→ 主页面背景刷新信号 → 主题强调色上抛 → Snackbar
    fun save() {
        store.setSlot(WallpaperStore.SLOT_UNIFIED, unifiedDraft)
        store.setSlot(WallpaperStore.SLOT_DARK, darkDraft)
        store.setSlot(WallpaperStore.SLOT_LIGHT, lightDraft)
        store.maskAlpha = maskDraft
        store.accentColor = accentDraft
        ui.wallpaperTick++ // 主页面背景（WallpaperBackdrop 自订阅）重读 store 刷新
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
            TextButton(onClick = { ui.currentPage = BluelinkUiState.PAGE_HOME }) { Text("返回") }
            TextButton(onClick = { save() }) { Text("保存") }
        }
        Spacer(Modifier.height(SpacingTokens.SpaceSm))
        // ---- 内容区（标题下剩余空间）：颜色区 ~1/8 + 场景行 + 预览弹性大部 + 遮罩底部固定 ----
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            // 颜色区高度 ≈ 内容区高 1/8（收 80–128dp，防过小/过大；预览区弹性占剩余大部）
            val colorAreaHeight = (maxHeight * COLOR_AREA_FRACTION).coerceIn(80.dp, 128.dp)
            Column(modifier = Modifier.fillMaxSize()) {
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
                Spacer(Modifier.height(SpacingTokens.SpaceSm))
                SceneSwitchRow(
                    sceneSlot = sceneSlot,
                    onSceneSelected = { sceneSlot = it },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(SpacingTokens.SpaceSm))
                PreviewSection(
                    slot = slotDraft(sceneSlot),
                    sceneSlot = sceneSlot,
                    maskAlpha = maskDraft,
                    onOpenSource = { sourceSheet = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
                Spacer(Modifier.height(SpacingTokens.SpaceSm))
                MaskRow(
                    maskAlpha = maskDraft,
                    onMaskChange = { maskDraft = it },
                    modifier = Modifier.fillMaxWidth(),
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
                setSlotDraft(sceneSlot, WallpaperSlot.NONE)
                sourceSheet = false
            },
        )
    }
}

/** 颜色区占内容区高度的比例（≈1/8，规格「颜色区占页面内容高约 1/8」）。 */
private const val COLOR_AREA_FRACTION = 0.125f

/** 颜色区（占标题下内容区高约 1/8）：左 1/6 色系入口 / 中竖分割线 / 右 5/6 具体颜色（或展开的色系列表）。 */
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
        // 左 1/6：色系入口窄条（色块 + 名称；点按展开/收拢色系列表）
        FamilyEntryStrip(
            family = currentFamily,
            accentDraft = accentDraft,
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
        // 右 5/6：展开态 = 色系列表 chips（覆盖在右侧区域上层）；收拢态 = 具体颜色滑动条；最右固定「从壁纸取色」
        Row(
            modifier = Modifier
                .weight(5f)
                .fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (familyExpanded) {
                FamilyChipsOverlay(
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
            // API27+（O_MR1 门；26 隐藏）「从壁纸取色」固定在右区末端（两态均保留）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                WallpaperPickEntry(
                    onClick = onPickFromWallpaper,
                    modifier = Modifier
                        .width(46.dp)
                        .fillMaxHeight(),
                )
            }
        }
    }
}

/** 左 1/6 色系入口窄条：色块（已选强调色属本色系时用选中色，否则用色系代表色）+ 名称；展开态 primary 描边。 */
@Composable
private fun FamilyEntryStrip(
    family: ColorFamily,
    accentDraft: Long?,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dotArgb = if (accentDraft != null && accentFamilyOf(accentDraft) == family) {
        accentDraft // 已选色属于本色系 → 色块直接显示选中色（页内色块预览）
    } else {
        familySwatch(family)
    }
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .border(
                width = if (expanded) 2.dp else 1.dp,
                color = if (expanded) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                shape = MaterialTheme.shapes.small,
            )
            .clickable(onClick = onClick)
            .semantics { contentDescription = "色系入口：当前 ${family.name}（点击展开色系列表）" }
            .padding(horizontal = SpacingTokens.SpaceXs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // 色块（18dp 圆）：浅/白系描 outlineVariant 边保证可见
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(Color(dotArgb))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
        )
        Spacer(Modifier.height(SpacingTokens.SpaceXs))
        Text(
            text = family.name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Text(
            text = "色系",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** 色系列表（展开态）：横向滑动 FilterChip（带代表色点）；点击即选该色系并收拢、右区切换取色。 */
@Composable
private fun FamilyChipsOverlay(
    currentFamily: ColorFamily,
    onFamilySelected: (ColorFamily) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SpacingTokens.SpaceXs),
    ) {
        items(COLOR_FAMILIES, key = { it.name }) { family ->
            FilterChip(
                selected = family == currentFamily,
                onClick = { onFamilySelected(family) },
                label = { Text(family.name) },
                leadingIcon = {
                    // 色点：白/浅色系带描边环保证可见（10dp 内圆 + 14dp 外环）
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(Color(familySwatch(family))),
                        )
                    }
                },
            )
        }
    }
}

/** 右 5/6 具体颜色（收拢态）：当前色系 HSV 同 hue 明暗连续格（10 格，可左右滑动）；点选即选中（描边即时预览）。 */
@Composable
private fun ConcreteColorRow(
    family: ColorFamily,
    selected: Long?,
    onSelected: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier,
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

/** 具体颜色格（46dp 触达 / 视觉 34dp 圆角块）：选中 = primary 描边环 + 内容描述「已选 …」；未选细 outlineVariant 描边。 */
@Composable
private fun AccentSwatch(
    argb: Long,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(46.dp)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = if (isSelected) "已选强调色 ${accentHex(argb)}" else "强调色 ${accentHex(argb)}"
                selected = isSelected
            },
        contentAlignment = Alignment.Center,
    ) {
        if (isSelected) {
            // 选中描边环（primary）：即时页内预览「已选中此色」
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small),
            )
        }
        Box(
            modifier = Modifier
                .size(if (isSelected) 34.dp else 36.dp)
                .clip(MaterialTheme.shapes.small)
                .background(Color(argb))
                .border(
                    width = if (isSelected) 0.dp else 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = MaterialTheme.shapes.small,
                ),
        )
    }
}

/** 「从壁纸取色」入口（API27+ 固定在右区末端）：彩虹渐变取色图标 + 「取色」字样；读壁纸主色入选中态。 */
@Composable
private fun WallpaperPickEntry(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "从壁纸取色" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // 装饰取色图标：纵向彩虹渐变块（色值取色板代表色，非语义 token）
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(MaterialTheme.shapes.small)
                .background(
                    Brush.verticalGradient(
                        listOf(FAMILY_RED, FAMILY_ORANGE, FAMILY_YELLOW, FAMILY_BLUE, FAMILY_PURPLE)
                            .map { Color(familySwatch(it)) },
                    ),
                ),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = "取色",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
        )
    }
}

/** 场景三按钮（统一壁纸（兜底）/ 深色模式壁纸 / 浅色模式壁纸；对应 WallpaperStore 三槽，当前场景高亮）。 */
@Composable
private fun SceneSwitchRow(
    sceneSlot: Int,
    onSceneSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(SpacingTokens.SpaceSm),
    ) {
        SceneChip(
            label = "统一壁纸",
            isSelected = sceneSlot == WallpaperStore.SLOT_UNIFIED,
            onClick = { onSceneSelected(WallpaperStore.SLOT_UNIFIED) },
            modifier = Modifier.weight(1f),
        )
        SceneChip(
            label = "深色模式壁纸",
            isSelected = sceneSlot == WallpaperStore.SLOT_DARK,
            onClick = { onSceneSelected(WallpaperStore.SLOT_DARK) },
            modifier = Modifier.weight(1f),
        )
        SceneChip(
            label = "浅色模式壁纸",
            isSelected = sceneSlot == WallpaperStore.SLOT_LIGHT,
            onClick = { onSceneSelected(WallpaperStore.SLOT_LIGHT) },
            modifier = Modifier.weight(1f),
        )
    }
}

/** 场景切换小按钮（小件档 8dp 圆角；选中 = primaryContainer 对，未选 = surfaceContainerLow + outlineVariant 描边）。 */
@Composable
private fun SceneChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(40.dp)
            .clip(MaterialTheme.shapes.small)
            .background(
                if (isSelected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLow
                },
            )
            .border(
                width = if (isSelected) 0.dp else 1.dp,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                shape = MaterialTheme.shapes.small,
            )
            .clickable(onClick = onClick)
            .semantics {
                selected = isSelected
                contentDescription = "场景：$label"
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (isSelected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 复用预览区（弹性占剩余大部）：渲染当前场景槽草稿 = 壁纸图 + 当前遮罩（复用 WallpaperBackdrop 同套
 *  rememberSlotBitmap/WallpaperEffect，预览即真实背景效果）；槽未设 → 占位「点击选择壁纸」；
 * 点预览区 → 打开 [WallpaperSourceSheet]。 */
@Composable
private fun PreviewSection(
    slot: WallpaperSlot,
    sceneSlot: Int,
    maskAlpha: Int,
    onOpenSource: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // 同套解码（预览上限 PREVIEW_MAX_DIM=720px）：槽 type+uri 为 key——仅遮罩/场景外改动不重复解码
    val wallpaper = rememberSlotBitmap(context = context, slot = slot, maxDim = PREVIEW_MAX_DIM)
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
                // 壁纸图 + 遮罩叠加（与主页面背景同函数同遮罩色）
                WallpaperEffect(
                    wallpaper = wallpaper,
                    maskAlpha = maskAlpha,
                    modifier = Modifier.fillMaxSize(),
                )
                if (wallpaper == null) {
                    // 解码中（异步 IO）兜底提示
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "加载壁纸预览…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                // 槽未设占位：图标 + 「点击选择壁纸」
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
                    )
                    Spacer(Modifier.height(SpacingTokens.SpaceXs))
                    Text(
                        text = "跟随系统壁纸或自选图片",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
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
            modifier = Modifier.width(48.dp),
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
            modifier = Modifier.width(44.dp),
            maxLines = 1,
        )
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
                subtitle = "从相册/文件选择（SAF，自动保存授权）",
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

/** SAF 选图后持久读授权（修 v0.5.7 未持久授权、重启后 uri 失效问题；provider 不支持时忽略不崩溃）。 */
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

/** chips 展示顺序（规格序：红橙黄绿青蓝紫品粉棕灰白黑）。 */
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
