package com.zglinus.bluelink.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

/**
 * 应用主题（docs/md3-audit.md §3 P0-4 接线；替代原 MainActivity 内 lightColorScheme()/darkColorScheme()
 * 全默认占位——默认紫 → 品牌蓝 #0B57D0 seed 双 scheme）。
 *
 * - colorScheme：[LightColorScheme] / [DarkColorScheme]（语义 token 全量，含 surfaceContainer 系列/outlineVariant），
 *   深色跟随系统；默认关闭 dynamic color 以保品牌蓝；
 * - accent（v0.5.8 UI1b-B2 新增）：运行态强调色（ARGB Color）。非空 → 以它派生并覆写 primary 系
 *   （primary/onPrimary/primaryContainer/onPrimaryContainer，浅/深各自派生，见 [ColorScheme.withAccentPrimary]）；
 *   null → 默认品牌蓝派生不变。接线：MainActivity 持强调色 state（初值读 WallpaperStore.accentColor），
 *   个性化页「保存」后更新 state → 本函数重算 → MaterialTheme 全树 primary 系换色；深浅仍跟随系统
 *   （不做手动切换，留 UI1b-C）；
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
    CompositionLocalProvider(LocalExtendedColors provides extended) {
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
