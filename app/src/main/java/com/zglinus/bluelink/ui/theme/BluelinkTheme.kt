package com.zglinus.bluelink.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

// ============ v0.5.9 UI1b-C：深浅三态（themeMode）常量 ============

/** 深浅三态：跟随系统（默认）。 */
const val THEME_MODE_SYSTEM = 0

/** 深浅三态：强制浅色。 */
const val THEME_MODE_LIGHT = 1

/** 深浅三态：强制深色。 */
const val THEME_MODE_DARK = 2

/**
 * 全局深浅「判定源」（v0.5.9 UI1b-C 深浅三态全链路联动）：当前全局生效深浅（true=深色）。
 *
 * 原各处直读 `isSystemInDarkTheme()` 的消费点（壁纸取槽 [WallpaperBackdrop]、系统栏图标适配等）
 * 一律经 [rememberEffectiveDark] 读取本源——由 MainActivity 主题层按 themeMode 计算 effectiveDark
 * → [BluelinkTheme](darkTheme) 参数 → 本层 Provide（SYSTEM→isSystemInDarkTheme / LIGHT→false / DARK→true），
 * 全链路（主题 scheme / 壁纸槽与遮罩 / 系统栏图标明暗）随手动模式同步切换，消费点零分散判定。
 * 未 Provide（孤立预览等异常场景）时 [rememberEffectiveDark] 回落 isSystemInDarkTheme()（保持跟随系统现状）。
 */
val LocalEffectiveDark = compositionLocalOf<Boolean?> { null }

/** 读取全局生效深浅（[LocalEffectiveDark] 未 Provide 时回落 isSystemInDarkTheme()，跟随系统）。 */
@Composable
fun rememberEffectiveDark(): Boolean = LocalEffectiveDark.current ?: isSystemInDarkTheme()

/**
 * 应用主题（docs/md3-audit.md §3 P0-4 接线；替代原 MainActivity 内 lightColorScheme()/darkColorScheme()
 * 全默认占位——默认紫 → 品牌蓝 #0B57D0 seed 双 scheme）。
 *
 * - colorScheme：[LightColorScheme] / [DarkColorScheme]（语义 token 全量，含 surfaceContainer 系列/outlineVariant）；
 *   深浅跟随 [darkTheme] 参数——v0.5.9 UI1b-C 起由 MainActivity 按 themeMode 三态推导（SYSTEM→跟随系统 /
 *   LIGHT→浅 / DARK→深），不再直接依赖 isSystemInDarkTheme 的系统值；默认关闭 dynamic color 以保品牌蓝；
 * - darkTheme（v0.5.9 UI1b-C 全链路联动判定源）：本函数同时向树内 Provide [LocalEffectiveDark]（= darkTheme），
 *   壁纸取槽/遮罩等原直读 isSystemInDarkTheme() 的消费点改读 [rememberEffectiveDark]——主题 scheme /
 *   壁纸槽与遮罩/系统栏图标明暗同源，随手动深浅模式同步切换（消费点替换见 WallpaperBackdrop.kt）；
 * - accent（v0.5.8 UI1b-B2）：运行态强调色（ARGB Color）。非空 → 以它派生并覆写 primary 系
 *   （primary/onPrimary/primaryContainer/onPrimaryContainer，浅/深各自派生，见 [ColorScheme.withAccentPrimary]）；
 *   null → 默认品牌蓝派生不变。接线：MainActivity 持强调色 state（初值读 WallpaperStore.accentColor），
 *   个性化页「保存」后更新 state → 本函数重算 → MaterialTheme 全树 primary 系换色；
 * - shapes：ShapeTokens 显式接 Shapes（audit S7；v0.5.4a 圆角归一两档——xs4/sm8/md12 原样，
 *   large/extraLarge（抽屉容器/AlertDialog/ModalBottomSheet）→ ShapeTokens.Modal=10；v0.5.4b 块级内容容器
 *   亦消费 shapes.large＝10 档）；
 * - typography：M3 五组语义角色默认（audit T1：不自定义字重/字号）；
 * - 扩展语义色（success/warning 家族）经 [LocalExtendedColors] 随主题下发（`MaterialTheme.extended.xxx`）。
 */
@Composable
fun BluelinkTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // v0.5.8 UI1b-B2：运行态强调色（非空 → 覆写 primary 系；null → 默认品牌蓝派生不变）
    accent: Color? = null,
    content: @Composable () -> Unit,
) {
    val baseScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val colorScheme = if (accent != null) {
        baseScheme.withAccentPrimary(accent = accent, dark = darkTheme)
    } else {
        baseScheme
    }
    val extended = if (darkTheme) DarkExtendedColors else LightExtendedColors
    CompositionLocalProvider(
        LocalExtendedColors provides extended,
        // v0.5.9 UI1b-C：全局生效深浅 Provide（= darkTheme；themeMode 已由 MainActivity 推导进 darkTheme 参数），
        // 壁纸取槽等消费点经 rememberEffectiveDark() 读取，保证全链路联动（未 Provide 回落系统深浅）
        LocalEffectiveDark provides darkTheme,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            shapes = Shapes(
                extraSmall = RoundedCornerShape(ShapeTokens.ExtraSmall),
                small = RoundedCornerShape(ShapeTokens.Small),
                medium = RoundedCornerShape(ShapeTokens.Medium),
                // v0.5.4a 圆角归一 + v0.5.4b 块级容器：10dp 档（ShapeTokens.Modal）——M3 large 槽位=导航抽屉容器（原 16）、
                // extraLarge 槽位=AlertDialog/ModalBottomSheet（原 28）；v0.5.4b 块级内容容器直接消费 shapes.large（＝10）；
                // 小件（badge/按钮/输入框）仍走 small=8
                large = RoundedCornerShape(ShapeTokens.Modal),
                extraLarge = RoundedCornerShape(ShapeTokens.Modal),
            ),
            content = content,
        )
    }
}
