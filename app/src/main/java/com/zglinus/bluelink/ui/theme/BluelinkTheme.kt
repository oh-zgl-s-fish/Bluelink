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
 * - shapes：ShapeTokens 显式接 Shapes（xs4/sm8/md12/lg16/xl28，M3 默认 scale，audit S7）；
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
                large = RoundedCornerShape(ShapeTokens.Large),
                extraLarge = RoundedCornerShape(ShapeTokens.ExtraLarge),
            ),
            content = content,
        )
    }
}
