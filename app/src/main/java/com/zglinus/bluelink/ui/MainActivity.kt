package com.zglinus.bluelink

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color as ComposeColor
import com.zglinus.bluelink.ui.BluelinkEngine
import com.zglinus.bluelink.ui.BluelinkRoot
import com.zglinus.bluelink.ui.personalize.WallpaperStore
import com.zglinus.bluelink.ui.theme.BluelinkTheme
import com.zglinus.bluelink.ui.theme.THEME_MODE_DARK
import com.zglinus.bluelink.ui.theme.THEME_MODE_LIGHT
import com.zglinus.bluelink.ui.theme.THEME_MODE_SYSTEM

/** v0.5.9 UI1b-C 深浅三态持久化：prefs 名（bluelink_theme/theme_mode，与 accent 同层分离存储）。 */
private const val PREFS_THEME = "bluelink_theme"

/** v0.5.9 UI1b-C 深浅三态持久化：theme_mode 键（取值 [THEME_MODE_SYSTEM]/[THEME_MODE_LIGHT]/[THEME_MODE_DARK]）。 */
private const val KEY_THEME_MODE = "theme_mode"

/**
 * 唯一 Activity。职责：
 * - 持有 [BluelinkEngine]（BLE 广播/扫描/GATT 服务端/客户端 + 网络采集接线）；
 * - 生命周期接线（权限请求、启动/停止广播扫描在 BluelinkRoot 中编排）；
 * - 状态保存由 UI 侧 rememberSaveable 最小化（仅广播开关）。
 *
 * v0.5.5c edge-to-edge 沉浸：onCreate 调 [enableEdgeToEdge]（androidx.activity 1.12.1，≥1.9 保证可用）。
 * 系统栏（状态栏/底部导航条）改为透明，Compose 内容区延伸到其下；状态栏图标/导航按钮明暗自适应
 * 系统深浅色。
 *
 * v0.5.8 UI1b-B2 强调色运行态接线（theme 层之上）：setContent 内持强调色 state（初值 onCreate 读
 * WallpaperStore.accentColor）→ BluelinkTheme(accent) 派生 primary 系；个性化页「保存」经
 * BluelinkRoot.onAccentSaved 更新该 state → MaterialTheme 重算。
 *
 * v0.5.9 UI1b-C 深浅三态（themeMode 全链路联动，主题层同层接线）：
 * - 本 Activity 持 themeMode state（三态 [THEME_MODE_SYSTEM]/[THEME_MODE_LIGHT]/[THEME_MODE_DARK]，
 *   默认 SYSTEM）并持久化到 prefs（bluelink_theme/theme_mode）；
 * - effectiveDark 按 themeMode 计算（SYSTEM→isSystemInDarkTheme / LIGHT→false / DARK→true）→
 *   BluelinkTheme(darkTheme=effectiveDark)（内部 Provide [com.zglinus.bluelink.ui.theme.LocalEffectiveDark]
 *   → 壁纸取槽等消费点经 rememberEffectiveDark() 联动，不再直读 isSystemInDarkTheme）；
 * - edge-to-edge 系统栏图标运行态适配：原 onCreate 一次 auto 保留；setContent 内 LaunchedEffect(themeMode)
 *   重设 enableEdgeToEdge（LIGHT→SystemBarStyle.light 黑图标 / DARK→SystemBarStyle.dark 白图标 /
 *   SYSTEM→auto 跟随系统；androidx.activity 支持重复调用，手动模式切换后图标明暗与 surface 背景保持对比）；
 * - themeMode 值 + 变更回调沿 BluelinkRoot → MainScreen → 新设置页外观区（PAGE_SETTINGS）传递（同 accent 链路）。
 */
class MainActivity : ComponentActivity() {

    private lateinit var engine: BluelinkEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // v0.5.5c edge-to-edge 沉浸（真机修复：此前未 enableEdgeToEdge，状态栏与底部导航小白条区域
        // 背景未延伸、出现白/色条）。两栏均设全透明 + 明暗自适应：
        // - SystemBarStyle.auto(TRANSPARENT, TRANSPARENT)：浅色模式用暗图标/深色模式用亮图标，
        //   由系统 uiMode 自动判定（不强制亮色徽标；minSdk 26，无需 systemUiVisibility 兼容分支）；
        // - scrim 全透明 → 系统栏区域直接透出下方 Compose 页面背景 surface
        //   （浅 #FDFBFF / 深 #111318，BluelinkTheme LightColorScheme/DarkColorScheme.background），
        //   状态栏下为同色系 TopAppBar、底部导航区为同背景色，无硬白/黑条、无 scrim 色差。
        // （兜底方案 WindowCompat.setDecorFitsSystemWindows(window,false) 仅在 enableEdgeToEdge
        //   不可用的旧 androidx.activity 下需要，本工程 activity-compose 1.12.1 已含，无需使用。）
        // v0.5.9 UI1b-C：手动深浅模式切换后的系统栏图标重设在 setContent 内 LaunchedEffect(themeMode)
        // 完成（onCreate 此处的 auto 为基线/首帧，避免首帧依赖组合时序）。
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )

        engine = BluelinkEngine(applicationContext)

        // v0.5.8 UI1b-B2：强调色初值（启动读 prefs 一次；null=未选 → 主题默认品牌蓝派生不变）
        val initialAccentArgb = WallpaperStore(applicationContext).accentColor

        // v0.5.9 UI1b-C：深浅三态初值（启动读 prefs 一次；默认 SYSTEM=跟随系统）
        val themePrefs = getSharedPreferences(PREFS_THEME, Context.MODE_PRIVATE)
        val initialThemeMode = themePrefs
            .getInt(KEY_THEME_MODE, THEME_MODE_SYSTEM)
            .coerceIn(THEME_MODE_SYSTEM, THEME_MODE_DARK)

        setContent {
            // 主题强调色 state（运行态；theme 层之上）：个性化页保存后经 onAccentSaved 更新 → BluelinkTheme 重算
            var accentArgb by remember { mutableStateOf(initialAccentArgb) }
            // v0.5.9 UI1b-C：深浅三态 state（运行态；主题层同层）：设置页外观区经 onThemeModeChange 更新 + 持久化
            var themeMode by remember { mutableStateOf(initialThemeMode) }
            // 全局生效深浅（effectiveDark）：SYSTEM 跟随系统 / LIGHT 恒浅 / DARK 恒深 → BluelinkTheme(darkTheme)
            val effectiveDark = when (themeMode) {
                THEME_MODE_DARK -> true
                THEME_MODE_LIGHT -> false
                else -> isSystemInDarkTheme()
            }
            // v0.5.9 UI1b-C edge-to-edge 系统栏运行态适配：themeMode 变化后重设图标明暗（可重复调用）：
            // - LIGHT（浅底）→ SystemBarStyle.light（黑图标） / DARK（深底）→ SystemBarStyle.dark（白图标）
            // - SYSTEM → auto（系统 uiMode 自动判定，系统深浅切换无需本处干预）
            LaunchedEffect(themeMode) {
                val style = when (themeMode) {
                    THEME_MODE_LIGHT -> SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    THEME_MODE_DARK -> SystemBarStyle.dark(Color.TRANSPARENT)
                    else -> SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            // 主题接线（浅/深双 scheme + 语义 token + Shapes + 运行态 accent + v0.5.9 手动深浅）
            // 见 ui/theme/BluelinkTheme.kt
            BluelinkTheme(
                darkTheme = effectiveDark,
                accent = accentArgb?.let { ComposeColor((it and 0xFFFFFFL) or 0xFF000000L) }, // 归一 alpha FF
            ) {
                BluelinkRoot(
                    engine = engine,
                    // v0.5.9 UI1b-C：深浅三态链（themeMode 值 + 变更回调 → 设置页外观区；持久化在此）
                    themeMode = themeMode,
                    onThemeModeChange = { mode ->
                        val m = mode.coerceIn(THEME_MODE_SYSTEM, THEME_MODE_DARK)
                        themeMode = m
                        themePrefs.edit().putInt(KEY_THEME_MODE, m).apply()
                    },
                    // v0.5.8 UI1b-B2：保存后主题换强调色；主页面背景刷新走 ui.wallpaperTick
                    onAccentSaved = { accentArgb = it },
                )
            }
        }
    }

    override fun onDestroy() {
        engine.release()
        super.onDestroy()
    }
}
