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
import com.zglinus.bluelink.ui.theme.THEME_MODE_SYSTEM

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
 *
 * v0.5.8 UI1b-B2：新增 [onAccentSaved]——个性化页「保存」回调（保存的强调色 ARGB Long？null=清除/未选）。
 * MainActivity 持主题强调色 state（theme 层之上）：保存后经此更新 → BluelinkTheme(accent) 重算；
 * 主页面背景刷新走既有 ui.wallpaperTick（本层不处理）。
 *
 * v0.5.9 UI1b-C：新增 [themeMode]/[onThemeModeChange]——深浅三态链（MainActivity 主题层持 state，
 * 设置页外观区三态 chips 经此读/改；本层透传 MainScreen → SettingsPage，同 accent 回调链路）。
 */
@Composable
fun BluelinkRoot(
    engine: BluelinkEngine,
    onAccentSaved: (Long?) -> Unit = {},
    // v0.5.9 UI1b-C：深浅三态（SYSTEM/LIGHT/DARK；MainActivity 持久化持有，设置页外观区消费）
    themeMode: Int = THEME_MODE_SYSTEM,
    onThemeModeChange: (Int) -> Unit = {},
) {
    val context = LocalContext.current
    var advertisingWanted by rememberSaveable { mutableStateOf(true) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        // WifiJoiner 权限前置（onNeedPermission → ui.requestedPermission）：本次请求若正是该权限，
        // 结果只回灌 join 自动重试（engine.onJoinPermissionResult）；不误入 onPermissionsResult——
        // 其 startBleIfNeeded → stopAllBle 会取消组网/接入。BLE 权限请求（不含该权限）仍走既有 onPermissionsResult。
        val joinPermission = engine.ui.requestedPermission
        if (joinPermission != null && result.containsKey(joinPermission)) {
            engine.onJoinPermissionResult()
        } else {
            engine.onPermissionsResult(result.values.all { it })
        }
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

    // WifiJoiner 权限前置：onNeedPermission 置 ui.requestedPermission → 发起该权限的系统授权弹窗；
    // 授权结果经 permissionLauncher 回调 → engine.onJoinPermissionResult 自动重试 join
    // （拒绝则 engine 清 requestedPermission 保持挂起，下次 join 再次触发弹窗）
    LaunchedEffect(engine.ui.requestedPermission) {
        val p = engine.ui.requestedPermission ?: return@LaunchedEffect
        permissionLauncher.launch(arrayOf(p))
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
        // v0.5.8 UI1b-B2：个性化页保存 → MainActivity 主题强调色 state
        onAccentSaved = onAccentSaved,
        // v0.5.9 UI1b-C：深浅三态链 → MainScreen → 设置页外观区
        themeMode = themeMode,
        onThemeModeChange = onThemeModeChange,
    )

    engine.ui.detailDevice?.let { selected ->
        DeviceDetailSheet(
            entry = selected,
            onDismiss = { engine.dismissSheet() },
        )
    }
}
