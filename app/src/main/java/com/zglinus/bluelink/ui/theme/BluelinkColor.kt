package com.zglinus.bluelink.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/**
 * Bluelink 语义色 token（docs/md3-audit.md §3 P0-1 初值表落地；种子 = 品牌蓝 #0B57D0）。
 *
 * - [LightColorScheme] / [DarkColorScheme]：M3 语义角色全量（primary/secondary/tertiary/error 家族、
 *   surface 家族含 surfaceContainerLowest..Highest / surfaceDim / surfaceBright、onSurface/onSurfaceVariant、
 *   outline/outlineVariant、inverseSurface/inverseOnSurface、scrim、background/onBackground）。
 *   仅主题接线处引用（见 [BluelinkTheme]）；裸 hex 只允许存在于本 token 文件内（audit §1.1 例外）。
 * - [BluelinkExtendedColors]：M3 官方无 success/warning 角色，按 audit §3 P0-1 作为扩展语义对下发；
 *   颜色永远与 icon/文字并用以表达状态（audit §1.1）。读取方式：`MaterialTheme.extended.xxx`。
 *
 * 深色同色相提亮；关键对已按 audit §7.3 基线手算 ≥4.5:1（正文）：浅 success #188038/白 5.0、
 * 浅 successContainer #CDE9CD/on #0A3D0F 9.6、深 successContainer #2E4D2E/on #CDE9CD 7.3、
 * 浅 warningContainer #FFDCC2/on #321600 13.0、深 warningContainer #4A2800/on #FFDCC2 10.2 等。
 * error 家族沿用 M3 基线（#BA1A1A/#FFDAD6 与 #FFB4AB/#93000A）。
 */

// ===================== 浅色 scheme（品牌蓝 seed #0B57D0） =====================

val LightColorScheme = lightColorScheme(
    primary = Color(0xFF0B57D0),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD7E2FF),
    onPrimaryContainer = Color(0xFF001945),
    secondary = Color(0xFF565E71),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDAE2F9),
    onSecondaryContainer = Color(0xFF131C2B),
    tertiary = Color(0xFF6B5778),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF1DDFE),
    onTertiaryContainer = Color(0xFF2B1733),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFDFBFF),
    onBackground = Color(0xFF1A1C20),
    surface = Color(0xFFFDFBFF),
    onSurface = Color(0xFF1A1C20),
    surfaceVariant = Color(0xFFE6E8EF),
    onSurfaceVariant = Color(0xFF44474F),
    outline = Color(0xFF74777F),
    outlineVariant = Color(0xFFC4C6D0),
    inverseSurface = Color(0xFF2E3138),
    inverseOnSurface = Color(0xFFF0F1F6),
    scrim = Color(0xFF000000),
    surfaceDim = Color(0xFFDEDFE5),
    surfaceBright = Color(0xFFFDFBFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F8FD),
    surfaceContainer = Color(0xFFF2F3F9),
    surfaceContainerHigh = Color(0xFFECEEF4),
    surfaceContainerHighest = Color(0xFFE6E8EF),
)

// ===================== 深色 scheme（同一色相系） =====================

val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFAAC7FF),
    onPrimary = Color(0xFF003060),
    primaryContainer = Color(0xFF00418E),
    onPrimaryContainer = Color(0xFFD7E2FF),
    secondary = Color(0xFFBEC6DC),
    onSecondary = Color(0xFF252B3D),
    secondaryContainer = Color(0xFF3E4759),
    onSecondaryContainer = Color(0xFFDAE2F9),
    tertiary = Color(0xFFD5BCE3),
    onTertiary = Color(0xFF3B2348),
    tertiaryContainer = Color(0xFF523F60),
    onTertiaryContainer = Color(0xFFF1DDFE),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE2E2E9),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE2E2E9),
    surfaceVariant = Color(0xFF44474F),
    onSurfaceVariant = Color(0xFFC4C6D0),
    outline = Color(0xFF8F9099),
    outlineVariant = Color(0xFF44474F),
    inverseSurface = Color(0xFFE2E2E9),
    inverseOnSurface = Color(0xFF31343A),
    scrim = Color(0xFF000000),
    surfaceDim = Color(0xFF111318),
    surfaceBright = Color(0xFF37393E),
    surfaceContainerLowest = Color(0xFF0C0E13),
    surfaceContainerLow = Color(0xFF191B20),
    surfaceContainer = Color(0xFF1D1F24),
    surfaceContainerHigh = Color(0xFF27292F),
    surfaceContainerHighest = Color(0xFF32343A),
)

// ===================== M3 之外的扩展语义对（success / warning） =====================

/** 扩展语义对：M3 无 success/warning 角色（audit §3 P0-1 状态扩展对）。 */
data class BluelinkExtendedColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
)

/** 浅色扩展对：success #188038 系（container #CDE9CD，audit 浅绿系）/ warning #B25000 系（container #FFDCC2）。 */
val LightExtendedColors = BluelinkExtendedColors(
    success = Color(0xFF188038),
    onSuccess = Color(0xFFFFFFFF),
    successContainer = Color(0xFFCDE9CD),
    onSuccessContainer = Color(0xFF0A3D0F),
    warning = Color(0xFFB25000),
    onWarning = Color(0xFFFFFFFF),
    warningContainer = Color(0xFFFFDCC2),
    onWarningContainer = Color(0xFF321600),
)

/** 深色扩展对：同色相提亮（success #81C995 / warning #F2CC8C，audit 深色亮系）。 */
val DarkExtendedColors = BluelinkExtendedColors(
    success = Color(0xFF81C995),
    onSuccess = Color(0xFF00391C),
    successContainer = Color(0xFF2E4D2E),
    onSuccessContainer = Color(0xFFCDE9CD),
    warning = Color(0xFFF2CC8C),
    onWarning = Color(0xFF3A2400),
    warningContainer = Color(0xFF4A2800),
    onWarningContainer = Color(0xFFFFDCC2),
)

/** 扩展对随主题下发（默认浅色；[BluelinkTheme] 按 darkTheme 提供对应实例）。 */
val LocalExtendedColors = staticCompositionLocalOf { LightExtendedColors }

/** 便捷读取：`MaterialTheme.extended.successContainer` 等（与 colorScheme 同用法）。 */
val MaterialTheme.extended: BluelinkExtendedColors
    @Composable
    @ReadOnlyComposable
    get() = LocalExtendedColors.current

// ===================== v0.5.8 UI1b-B2：运行态强调色 → primary 系派生 =====================

/** primary 系派生结果（[ColorScheme.withAccentPrimary] 覆写的四元组）。 */
private data class AccentPrimaryRoles(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
)

/**
 * 以运行态强调色覆写 scheme 的 primary 系（浅/深各自派生），其余角色保持默认 scheme 不变。
 * 无第三方色板库（无 material-color-utilities/coil）——按亮度启发式手写派生，近似 M3 色调映射：
 * - 浅色：primary 需足够暗（白底正文/按钮白字可读，亮度上限 ~0.18），container 提浅 tint、container 字压深；
 * - 深色：primary 需足够亮（深底可读，亮度下限 ~0.5），container 压深、container 字提亮；
 * 极端强调色（白/黑系）经 [clampLuminance] 双向收进可读亮度区间；向黑/白混合保留色相近似。
 * 接线：MainActivity 持强调色 state（初值读 WallpaperStore.accentColor）→ [BluelinkTheme](accent)
 * 保存后更新触发本函数重算；null → 调用方直接用默认 scheme（本文件 [LightColorScheme]/[DarkColorScheme]）。
 */
fun ColorScheme.withAccentPrimary(accent: Color, dark: Boolean): ColorScheme {
    val roles = if (dark) deriveAccentPrimaryDark(accent) else deriveAccentPrimaryLight(accent)
    return copy(
        primary = roles.primary,
        onPrimary = roles.onPrimary,
        primaryContainer = roles.primaryContainer,
        onPrimaryContainer = roles.onPrimaryContainer,
    )
}

/** 浅色 primary 系派生（primary 亮度 ≤0.18 保白字/白底 ≥4.5:1 级；container 提浅、container 字压深）。 */
private fun deriveAccentPrimaryLight(accent: Color): AccentPrimaryRoles = AccentPrimaryRoles(
    primary = clampLuminance(accent, minLum = 0f, maxLum = 0.18f),
    onPrimary = Color.White,
    primaryContainer = clampLuminance(accent, minLum = 0.72f, maxLum = 0.9f),
    onPrimaryContainer = clampLuminance(accent, minLum = 0f, maxLum = 0.12f),
)

/** 深色 primary 系派生（primary 亮度 ≥0.5 提亮；onPrimary 压深、container 压深、container 字提亮）。 */
private fun deriveAccentPrimaryDark(accent: Color): AccentPrimaryRoles = AccentPrimaryRoles(
    primary = clampLuminance(accent, minLum = 0.5f, maxLum = 1f),
    onPrimary = clampLuminance(accent, minLum = 0f, maxLum = 0.05f),
    primaryContainer = clampLuminance(accent, minLum = 0f, maxLum = 0.16f),
    onPrimaryContainer = clampLuminance(accent, minLum = 0.6f, maxLum = 1f),
)

/** 向黑/白 25% 步进混合，把亮度收进 [minLum, maxLum]（上限 16 步收敛；色相近似保留，无精确色调表）。 */
private fun clampLuminance(accent: Color, minLum: Float, maxLum: Float): Color {
    var current = accent
    repeat(16) {
        val lum = current.luminance()
        if (lum >= minLum && lum <= maxLum) return current
        current = if (lum > maxLum) lerp(current, Color.Black, 0.25f) else lerp(current, Color.White, 0.25f)
    }
    return current
}
