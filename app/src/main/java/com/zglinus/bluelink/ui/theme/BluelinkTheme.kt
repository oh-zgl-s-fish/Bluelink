package com.zglinus.bluelink.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * 应用主题（docs/md3-audit.md §3 P0-4 接线；替代原 MainActivity 内 lightColorScheme()/darkColorScheme()
 * 全默认占位——默认紫 → 品牌蓝 #0B57D0 seed 双 scheme）。
 *
 * - colorScheme：[LightColorScheme] / [DarkColorScheme]（语义 token 全量，含 surfaceContainer 系列/outlineVariant），
 *   深色跟随系统；默认关闭 dynamic color 以保品牌蓝；
 * - shapes：ShapeTokens 显式接 Shapes（audit S7；v0.5.4a 圆角归一两档——xs4/sm8/md12 原样，
 *   浮层槽位 large（抽屉容器）/extraLarge（AlertDialog/ModalBottomSheet）→ ShapeTokens.Modal=10）；
 * - typography：M3 五组语义角色默认（audit T1：不自定义字重/字号）；
 * - 扩展语义色（success/warning 家族）经 [LocalExtendedColors] 随主题下发（`MaterialTheme.extended.xxx`）。
 */
@Composable
fun BluelinkTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val extended = if (darkTheme) DarkExtendedColors else LightExtendedColors
    CompositionLocalProvider(LocalExtendedColors provides extended) {
        MaterialTheme(
            colorScheme = colorScheme,
            shapes = Shapes(
                extraSmall = RoundedCornerShape(ShapeTokens.ExtraSmall),
                small = RoundedCornerShape(ShapeTokens.Small),
                medium = RoundedCornerShape(ShapeTokens.Medium),
                // v0.5.4a 圆角归一：保留浮层用 10dp（ShapeTokens.Modal）——M3 large 槽位=导航抽屉容器（原 16）、
                // extraLarge 槽位=AlertDialog/ModalBottomSheet（原 28），两槽位在本 App 仅浮层消费 → 一并接 Modal；
                // 小件（badge/按钮/输入框）仍走 small=8
                large = RoundedCornerShape(ShapeTokens.Modal),
                extraLarge = RoundedCornerShape(ShapeTokens.Modal),
            ),
            content = content,
        )
    }
}
