@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.zglinus.bluelink.ui.personalize

import android.app.WallpaperManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.zglinus.bluelink.ui.BluelinkUiState
import com.zglinus.bluelink.ui.theme.SpacingTokens
import java.util.Locale

/**
 * 个性化页（抽屉 2 / BluelinkUiState.PAGE_PERSONAL；v0.5.7 UI1b-B 由占位页 → 真页：
 * 三壁纸槽 + 遮罩 + 取色 + 预览 + 主页面背景应用）。
 *
 * 布局（docs/ui-design.md §4.10 外观 + 用户定稿口径）：
 * - 顶部（占内容区约 1/6）取色区 [AccentColorSection]：API27+ 显示「从壁纸取色」
 *   （WallpaperManager.getWallpaperColors 主色；API26 及以下隐藏取色入口，按版本隐藏）+
 *   下方自选基础色板（8 色）+ 选中色 chip（[AccentChip]，仅预览/预留，不全局改 ColorScheme）；
 * - 主体（下滚）：三壁纸槽卡片 [WallpaperSlotCard]（统一壁纸（兜底）/ 深色模式壁纸 / 浅色模式壁纸；
 *   每槽：当前预览缩略 [SlotThumb] +「跟系统壁纸」/「自选图片(SAF)」选择 + 清除）→
 *   遮罩滑块 [MaskSliderSection]（0–80% 半透明遮罩强度）→ 预览块 [EffectPreviewSection]
 *   （按当前深浅模式选槽渲染效果：壁纸 + 遮罩，与主页面根背景同套 [WallpaperEffect]，所见即所得）。
 *
 * 存储：全部经 [WallpaperStore]（SharedPreferences）；任何槽/遮罩/强调色改动后
 * `ui.wallpaperTick++`（[BluelinkUiState.wallpaperTick]）——MainScreen 根背景 WallpaperBackdrop
 * 与页面本体重读 store 刷新（遮罩/强调色变化不触发重复解码，解码 key 只含槽内容）。
 */
@Composable
fun PersonalizePage(ui: BluelinkUiState) {
    val context = LocalContext.current
    // tick 为 Compose 状态：改动后自增 → 本页（与 MainScreen 根背景）重读 WallpaperStore 刷新
    val tick = ui.wallpaperTick
    val store = remember { WallpaperStore(context) }
    val dark = isSystemInDarkTheme()

    // 自选图片（SAF 系统 picker）：记录目标槽 id → 选中 content:// uri → 写入该槽
    var pickSlotId by remember { mutableStateOf<Int?>(null) }
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        val slotId = pickSlotId ?: return@rememberLauncherForActivityResult
        pickSlotId = null
        if (uri != null) {
            store.setSlot(slotId, WallpaperSlot(type = WallpaperSlot.TYPE_URI, uri = uri.toString()))
            ui.wallpaperTick++
            ui.showSnack("已选用图片壁纸")
        }
    }

    fun pickImage(slotId: Int) {
        pickSlotId = slotId
        imagePicker.launch("image/*")
    }

    // API27+ 「从壁纸取色」：WallpaperManager.getWallpaperColors 主色（try/catch；失败 Snackbar 提示）
    fun pickFromWallpaper() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return // API26 无取色入口，双保险
        try {
            val colors = WallpaperManager.getInstance(context).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
            val primary = colors?.primaryColor
            if (primary != null) {
                store.accentColor = primary.toArgb().toLong() and 0xFFFFFFFFL
                ui.wallpaperTick++
                ui.showSnack("已取壁纸主色")
            } else {
                ui.showSnack("未取到壁纸主色，可改用下方色板")
            }
        } catch (t: Throwable) {
            ui.showSnack("取壁纸主色失败：${t.message ?: "未知错误"}")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = SpacingTokens.SpaceLg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "个性化",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { ui.currentPage = BluelinkUiState.PAGE_HOME }) { Text("返回") }
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(SpacingTokens.SpaceMd),
        ) {
            // ---- 取色区（页面顶部紧凑取色条 + 8 基础色板 + 选中色 chip；约 1/6 区，主体下滚） ----
            AccentColorSection(
                store = store,
                onPickFromWallpaper = { pickFromWallpaper() },
                onChanged = { ui.wallpaperTick++ },
            )
            // ---- 主体：三壁纸槽（统一壁纸（兜底）/ 深色模式壁纸 / 浅色模式壁纸） ----
            WallpaperSlotCard(
                store = store,
                slotId = WallpaperStore.SLOT_UNIFIED,
                title = "统一壁纸",
                description = "兜底槽：深/浅色槽未设置时使用。",
                tick = tick,
                onPickImage = { pickImage(WallpaperStore.SLOT_UNIFIED) },
                onChanged = { ui.wallpaperTick++ },
            )
            WallpaperSlotCard(
                store = store,
                slotId = WallpaperStore.SLOT_DARK,
                title = "深色模式壁纸",
                description = "系统深色模式（isSystemInDarkTheme）时使用。",
                tick = tick,
                onPickImage = { pickImage(WallpaperStore.SLOT_DARK) },
                onChanged = { ui.wallpaperTick++ },
            )
            WallpaperSlotCard(
                store = store,
                slotId = WallpaperStore.SLOT_LIGHT,
                title = "浅色模式壁纸",
                description = "系统浅色模式时使用。",
                tick = tick,
                onPickImage = { pickImage(WallpaperStore.SLOT_LIGHT) },
                onChanged = { ui.wallpaperTick++ },
            )
            // ---- 遮罩滑块（0–80%） ----
            MaskSliderSection(
                store = store,
                onChanged = { ui.wallpaperTick++ },
            )
            // ---- 预览块（按当前模式选槽渲染：壁纸 + 遮罩） ----
            EffectPreviewSection(
                store = store,
                dark = dark,
                tick = tick,
            )
        }
    }
}

/** 基础色板（8 色，ARGB Long ↔ Compose Color 对；同值即选中态判定依据）。 */
private val ACCENT_SWATCHES: List<Pair<Long, Color>> = listOf(
    0xFF0B57D0L to Color(0xFF0B57D0), // 品牌蓝
    0xFF00639BL to Color(0xFF00639B), // 天蓝
    0xFF00838FL to Color(0xFF00838F), // 青
    0xFF2E7D32L to Color(0xFF2E7D32), // 绿
    0xFFB25E00L to Color(0xFFB25E00), // 琥珀
    0xFFEF6C00L to Color(0xFFEF6C00), // 橙
    0xFFC5221FL to Color(0xFFC5221F), // 红
    0xFF6B5778L to Color(0xFF6B5778), // 紫
)

/**
 * 取色区（页面顶部约 1/6）：
 * - 右侧「从壁纸取色」入口——API27+（WallpaperManager.getWallpaperColors）才显示；API26 隐藏（按版本隐藏）；
 * - 下方自选基础色板 8 色（点选写 accentColor）；
 * - 选中色显示 chip（[AccentChip]，点 chip 清除）。
 * accentColor 本版仅预览 chip 与后续主题预留——不全局改 ColorScheme（注释声明）。
 */
@Composable
private fun AccentColorSection(
    store: WallpaperStore,
    onPickFromWallpaper: () -> Unit,
    onChanged: () -> Unit,
) {
    val accent = store.accentColor
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = SpacingTokens.SpaceLg, vertical = SpacingTokens.SpaceMd),
            verticalArrangement = Arrangement.spacedBy(SpacingTokens.SpaceSm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "强调色",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                // API27 门：从壁纸取色（getWallpaperColors 主色）；API26 及以下隐藏此入口
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    TextButton(onClick = onPickFromWallpaper) { Text("从壁纸取色") }
                }
            }
            if (accent != null) {
                AccentChip(
                    accent = accent,
                    onClear = {
                        store.accentColor = null
                        onChanged()
                    },
                )
            }
            // 下方自选基础色板（8 色，两行四列）
            ACCENT_SWATCHES.chunked(4).forEach { rowColors ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    rowColors.forEach { (argb, color) ->
                        AccentSwatch(
                            color = color,
                            selected = accent == argb,
                            onClick = {
                                store.accentColor = argb
                                onChanged()
                            },
                        )
                    }
                }
            }
            Text(
                text = "强调色本版仅作选中色 chip 预览与后续主题预留，不全局改 ColorScheme。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 选中色显示 chip（色点 + 文字 + ✕；点按清除强调色）。 */
@Composable
private fun AccentChip(accent: Long, onClear: () -> Unit) {
    Surface(
        onClick = onClear,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.semantics { contentDescription = "清除强调色（当前 ${accentHex(accent)}）" },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = SpacingTokens.SpaceMd, vertical = SpacingTokens.SpaceXs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SpacingTokens.SpaceXs),
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(Color(accent)),
            )
            Text("选中色", style = MaterialTheme.typography.labelMedium)
            Text(
                text = accentHex(accent),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "✕",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** ARGB Long → "#RRGGBB" 显示串。 */
private fun accentHex(argb: Long): String = String.format(Locale.US, "#%06X", argb and 0xFFFFFFL)

/** 基础色板单个色块（48dp 触达区内 30dp 色圆；选中描 primary 圈）。 */
@Composable
private fun AccentSwatch(color: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .border(
                width = if (selected) 3.dp else 0.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = CircleShape,
            )
            .clickable(onClick = onClick)
            .semantics { contentDescription = if (selected) "强调色已选" else "选择强调色" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(color),
        )
    }
}

/** 壁纸槽来源标签（跟系统壁纸 / 自选图片 / 未设置）。 */
private fun slotTypeLabel(slot: WallpaperSlot): String = when (slot.type) {
    WallpaperSlot.TYPE_SYSTEM -> "跟系统壁纸"
    WallpaperSlot.TYPE_URI -> "自选图片"
    else -> "未设置"
}

/** 单槽设置详情（副文案）。 */
private fun slotSourceDetail(slot: WallpaperSlot): String = when (slot.type) {
    WallpaperSlot.TYPE_SYSTEM -> "壁纸来源：系统当前壁纸"
    WallpaperSlot.TYPE_URI -> "壁纸来源：自选图片"
    else -> "未设置 → 该模式回退统一槽/纯色"
}

/**
 * 壁纸槽卡片：槽名 + 状态标签 + 说明 + 当前预览缩略（[SlotThumb]）+
 * 选择（「跟系统壁纸」FilterChip / 「自选图片(SAF)」FilterChip）+ 清除。
 */
@Composable
private fun WallpaperSlotCard(
    store: WallpaperStore,
    slotId: Int,
    title: String,
    description: String,
    tick: Int,
    onPickImage: () -> Unit,
    onChanged: () -> Unit,
) {
    // 槽读取（重读 prefs；tick 变化驱动 recomposition）
    val slot = store.slot(slotId)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = SpacingTokens.SpaceLg, vertical = SpacingTokens.SpaceMd),
            verticalArrangement = Arrangement.spacedBy(SpacingTokens.SpaceSm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = slotTypeLabel(slot),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                SlotThumb(
                    store = store,
                    slot = slot,
                    tick = tick,
                    modifier = Modifier
                        .width(96.dp)
                        .height(60.dp),
                )
                Spacer(Modifier.width(SpacingTokens.SpaceMd))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(SpacingTokens.SpaceXs),
                ) {
                    // 「跟系统壁纸」（点选切换，再点取消）
                    FilterChip(
                        selected = slot.type == WallpaperSlot.TYPE_SYSTEM,
                        onClick = {
                            store.setSlot(
                                slotId,
                                if (slot.type == WallpaperSlot.TYPE_SYSTEM) WallpaperSlot.NONE
                                else WallpaperSlot(type = WallpaperSlot.TYPE_SYSTEM),
                            )
                            onChanged()
                        },
                        label = { Text("跟系统壁纸") },
                    )
                    // 「自选图片」→ SAF 系统图片选择器（GetContent image/*；结果写回本槽 uri）
                    FilterChip(
                        selected = slot.type == WallpaperSlot.TYPE_URI,
                        onClick = onPickImage,
                        label = { Text("自选图片") },
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = slotSourceDetail(slot),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        store.clearSlot(slotId)
                        onChanged()
                    },
                    enabled = slot.isSet,
                ) { Text("清除") }
            }
        }
    }
}

/** 槽当前壁纸缩略预览（异步解码；未设置显示占位文案）。 */
@Composable
private fun SlotThumb(
    store: WallpaperStore,
    slot: WallpaperSlot,
    tick: Int,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val bmp = rememberSlotBitmap(context = context, slot = slot, maxDim = THUMB_MAX_DIM)
    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .semantics { contentDescription = "壁纸槽预览" },
    ) {
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else if (!slot.isSet) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "未设置",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 遮罩滑块区（0–80% 半透明遮罩强度，即改即存；onChanged → wallpaperTick 重绘预览/背景）。 */
@Composable
private fun MaskSliderSection(
    store: WallpaperStore,
    onChanged: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = SpacingTokens.SpaceLg, vertical = SpacingTokens.SpaceMd),
            verticalArrangement = Arrangement.spacedBy(SpacingTokens.SpaceXs),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "遮罩强度",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${store.maskAlpha}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Slider(
                value = store.maskAlpha.toFloat(),
                onValueChange = { value ->
                    store.maskAlpha = value.toInt()
                    onChanged()
                },
                valueRange = 0f..WallpaperStore.MASK_MAX.toFloat(),
                steps = WallpaperStore.MASK_MAX - 1,
            )
            Text(
                text = "半透明遮罩（surfaceVariant 色）按百分比叠加在壁纸上，0–80% 无保护下限；文字可读性由内容容器承担。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 预览块：按当前深浅模式选槽（[WallpaperStore.effectiveSlot]）渲染真实效果
 * （壁纸 + 遮罩，与主页面根背景同一 [WallpaperEffect]）；无壁纸显示占位说明。
 */
@Composable
private fun EffectPreviewSection(
    store: WallpaperStore,
    dark: Boolean,
    tick: Int,
) {
    val context = LocalContext.current
    // 按当前模式取槽（深→深槽 / 浅→浅槽；槽未设→统一槽）
    val effective = store.effectiveSlot(dark)
    val bmp = rememberSlotBitmap(context = context, slot = effective, maxDim = PREVIEW_MAX_DIM)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = SpacingTokens.SpaceLg, vertical = SpacingTokens.SpaceMd),
            verticalArrangement = Arrangement.spacedBy(SpacingTokens.SpaceSm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "效果预览",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (dark) "深色模式（跟随系统）" else "浅色模式（跟随系统）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                if (bmp != null) {
                    // 壁纸 + 遮罩（同根背景渲染）
                    WallpaperEffect(
                        wallpaper = bmp,
                        maskAlpha = store.maskAlpha,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "无壁纸 → 纯色背景",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "当前深浅槽与统一槽均未设置",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Text(
                text = effectiveSourceText(dark, store),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 预览/取槽说明文案（当前模式 → 哪个槽 → 壁纸来源 + 遮罩百分比）。 */
private fun effectiveSourceText(dark: Boolean, store: WallpaperStore): String {
    val modeName = if (dark) "深色" else "浅色"
    val modeSlot = store.slot(if (dark) WallpaperStore.SLOT_DARK else WallpaperStore.SLOT_LIGHT)
    val effective = store.effectiveSlot(dark)
    if (!effective.isSet) {
        return "当前 $modeName 模式：壁纸槽未设置，App 根背景回纯色（现状）。"
    }
    val source = if (modeSlot.isSet) "${modeName}槽" else "${modeName}槽未设 → 统一槽兜底"
    return "当前 $modeName 模式取「$source」：${slotTypeLabel(effective)} + ${store.maskAlpha}% 遮罩。"
}
