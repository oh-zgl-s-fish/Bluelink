package com.zglinus.bluelink

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import com.zglinus.bluelink.ui.BluelinkEngine
import com.zglinus.bluelink.ui.BluelinkRoot

/**
 * 唯一 Activity。职责：
 * - 持有 [BluelinkEngine]（BLE 广播/扫描/GATT 服务端/客户端 + 网络采集接线）；
 * - 生命周期接线（权限请求、启动/停止广播扫描在 BluelinkRoot 中编排）；
 * - 状态保存由 UI 侧 rememberSaveable 最小化（仅广播开关）。
 */
class MainActivity : ComponentActivity() {

    private lateinit var engine: BluelinkEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = BluelinkEngine(applicationContext)
        setContent {
            BluelinkTheme {
                BluelinkRoot(engine)
            }
        }
    }

    override fun onDestroy() {
        engine.release()
        super.onDestroy()
    }
}

/**
 * 应用主题：深色跟随系统（Material 3）。
 * 一期为占位配色（lightColorScheme/darkColorScheme 默认值），后续按 docs/ui-design.md §4.10 扩展。
 */
@Composable
fun BluelinkTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme(),
        content = content
    )
}
