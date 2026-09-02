package com.zglinus.bluelink

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color as ComposeColor
import com.zglinus.bluelink.ui.BluelinkEngine
import com.zglinus.bluelink.ui.BluelinkRoot
import com.zglinus.bluelink.ui.personalize.WallpaperStore
import com.zglinus.bluelink.ui.theme.BluelinkTheme

/**
 * 唯一 Activity。职责：
 * - 持有 [BluelinkEngine]（BLE 广播/扫描/GATT 服务端/客户端 + 网络采集接线）；
 * - 生命周期接线（权限请求、启动/停止广播扫描在 BluelinkRoot 中编排）；
 * - 状态保存由 UI 侧 rememberSaveable 最小化（仅广播开关）。
 *
 * v0.5.5c edge-to-edge 沉浸：onCreate 调 [enableEdgeToEdge]（androidx.activity 1.12.1，≥1.9 保证可用）。
 * 系统栏（状态栏/底部导航条）改为透明，Compose 内容区延伸到其下；状态栏图标/导航按钮明暗自适应
 * 系统深浅色 —— 与 BluelinkTheme 的 darkTheme = isSystemInDarkTheme() 同一判定源（跟随系统 uiMode），
 * 深浅两套 scheme 下图标均与 App surface 背景保持对比。
 *
 * v0.5.8 UI1b-B2 强调色运行态接线（theme 层之上）：setContent 内持强调色 state（初值 onCreate 读
 * WallpaperStore.accentColor）→ BluelinkTheme(accent) 派生 primary 系；个性化页「保存」经
 * BluelinkRoot.onAccentSaved 更新该 state → MaterialTheme 重算（深浅仍跟随系统，不做手动切换，留 UI1b-C）。
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
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )

        engine = BluelinkEngine(applicationContext)

        // v0.5.8 UI1b-B2：强调色初值（启动读 prefs 一次；null=未选 → 主题默认品牌蓝派生不变）
        val initialAccentArgb = WallpaperStore(applicationContext).accentColor

        setContent {
            // 主题强调色 state（运行态；theme 层之上）：个性化页保存后经 onAccentSaved 更新 → BluelinkTheme 重算
            var accentArgb by remember { mutableStateOf(initialAccentArgb) }
            // 主题接线（浅/深双 scheme + 语义 token + Shapes + 运行态 accent）见 ui/theme/BluelinkTheme.kt
            BluelinkTheme(
                accent = accentArgb?.let { ComposeColor((it and 0xFFFFFFL) or 0xFF000000L) }, // 归一 alpha FF
            ) {
                BluelinkRoot(
                    engine = engine,
                    onAccentSaved = { accentArgb = it }, // 保存后主题换强调色；主页面背景刷新走 ui.wallpaperTick
                )
            }
        }
    }

    override fun onDestroy() {
        engine.release()
        super.onDestroy()
    }
}
