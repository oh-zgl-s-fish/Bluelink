package com.zglinus.bluelink

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.zglinus.bluelink.ui.BluelinkEngine
import com.zglinus.bluelink.ui.BluelinkRoot
import com.zglinus.bluelink.ui.theme.BluelinkTheme

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
            // 主题接线（浅/深双 scheme + 语义 token + Shapes）见 ui/theme/BluelinkTheme.kt（docs/md3-audit.md §3 P0-4）
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
