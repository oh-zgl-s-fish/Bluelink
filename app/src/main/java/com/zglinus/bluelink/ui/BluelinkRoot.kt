package com.zglinus.bluelink.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * 所需运行时权限（按版本分轨，docs/ui-design.md §2 权限矩阵）：
 * - Android 12+：BLUETOOTH_SCAN / BLUETOOTH_CONNECT / BLUETOOTH_ADVERTISE；
 * - Android 11-：ACCESS_FINE_LOCATION。
 *
 * 注：Manifest 中 BLUETOOTH_SCAN 未声明 neverForLocation，部分 12+ 机型在未授予定位
 * 权限时可能不投递扫描结果；按主管权限矩阵一期只请求 BLE 三权限，后续如需可补定位。
 */
fun neededRuntimePermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
        )
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

/**
 * 根 Composable：启动时权限请求 + 生命周期接线 + 主界面 + 设备详情弹层。
 * 唯一跨配置变更保存的状态是广播开关（rememberSaveable 最小化）。
 */
@Composable
fun BluelinkRoot(engine: BluelinkEngine) {
    val context = LocalContext.current
    var advertisingWanted by rememberSaveable { mutableStateOf(true) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        engine.onPermissionsResult(result.values.all { it })
    }

    // 启动：初始化引擎（root 探测/网络采集/蓝牙监听），并按需弹运行时权限请求
    LaunchedEffect(Unit) {
        engine.onStart()
        val needed = neededRuntimePermissions()
        val granted = needed.all {
            context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
        if (granted) {
            engine.onPermissionsResult(true)
        } else {
            permissionLauncher.launch(needed)
        }
    }

    // 广播开关同步到引擎
    LaunchedEffect(advertisingWanted) {
        engine.setAdvertisingWanted(advertisingWanted)
    }

    MainScreen(
        ui = engine.ui,
        advertisingWanted = advertisingWanted,
        onAdvertisingWantedChange = { advertisingWanted = it },
        onDeviceClick = { engine.openDevice(it) },
        onRefreshNetwork = { engine.refreshNetwork() },
        onRequestPermissions = { permissionLauncher.launch(neededRuntimePermissions()) },
    )

    engine.ui.selectedDevice?.let { selected ->
        DeviceDetailSheet(
            entry = selected,
            handshaking = engine.ui.handshaking,
            handshakeError = engine.ui.handshakeError,
            onDismiss = { engine.dismissSheet() },
        )
    }
}
