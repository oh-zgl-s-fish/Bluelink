package com.zglinus.bluelink.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.zglinus.bluelink.networking.HotspotPresetStore
import com.zglinus.bluelink.security.PinStore
import com.zglinus.bluelink.ui.theme.SpacingTokens
import com.zglinus.bluelink.ui.theme.THEME_MODE_DARK
import com.zglinus.bluelink.ui.theme.THEME_MODE_LIGHT
import com.zglinus.bluelink.ui.theme.THEME_MODE_SYSTEM
import com.zglinus.bluelink.ui.theme.extended

/**
 * 设置页（抽屉 3 / BluelinkUiState.PAGE_SETTINGS；v0.5.9 UI1b-C 五区真页，取代旧 MainScreen 内
 * SettingsPage 单容器版——旧页 PIN 配对验证能力并入本页「安全」区（PinStore 直驱），信令自测 /
 * LocalOnly 自测 / 诊断三块迁入关于页「开发者」区）。
 *
 * 五区（每区独立 surfaceContainerLowest 分组容器 + HorizontalDivider 分节；v0.5.4b 分层：
 * 页内常规内容块 → surfaceContainerLowest，无 elevation（不设阴影）、无边框，块级圆角 10）：
 * 1. 安全：PIN 验证模式三态分段钮（PinStore.getMode/setMode）/ 已配对设备列表（pairedEntries +
 *    removePaired 逐项移除，空态文案）/ 重置本端指纹（确认弹窗 + resetLocalFingerprint +
 *    Snackbar；指纹不对用户展示）/ 清空全部配对（确认弹窗 + clearAll）；
 * 2. 热点：SSID/密码预设草稿 + 自动使用开关 + 「保存预设」（HotspotPresetStore.save；1–32 / 8–63
 *    校验；密码留空=开热点随机）+ 「② 私有 API 热点」运行时开关（v0.5.14c，HotspotPresetStore.
 *    privateApiEnabled 直驱；关 → 组网 ② 直接降级 ③）；LocalOnly 场景系统生成不受预设影响（说明）；
 * 3. 传输：接收目录行——当前目录名（engine.receiveDirUri → DocumentFile 取 name，失败回退 uri
 *    尾段/「默认（Download/Bluelink）」）+ [更改]（页内 OpenDocumentTree launcher →
 *    engine.onReceiveDirPicked）+ [恢复默认]（engine.resetReceiveDir + Snackbar）；
 * 4. 外观：深浅三态分段钮 [跟随系统/浅色/深色]（themeMode 值高亮 + onThemeModeChange 回调——
 *    state 由 MainActivity 主题层持有并持久化；旁注「影响壁纸槽位与遮罩配色」）+ 强调色/壁纸
 *    入口提示（跳个性化页）；
 * 5. 权限检测：五项清单（按设备 SDK 列适用项，不适用显示「本版本不需要」）：蓝牙扫描+连接
 *    （12+ 运行时 / 11- 安装即授 BLUETOOTH+位置说明）、附近 Wi-Fi 设备（13+）、通知（13+）、
 *    修改系统设置（Settings.System.canWrite AppOps）、位置（ACCESS_FINE_LOCATION；12+ 经
 *    neverForLocation 无需）；未授予项右侧 [去设置]（通用走 ACTION_APPLICATION_DETAILS_SETTINGS
 *    package uri；修改系统设置走 ACTION_MANAGE_WRITE_SETTINGS data 包 uri；通知走
 *    ACTION_APP_NOTIFICATION_SETTINGS）；顶部「打开应用权限页」汇总按钮；自系统设置返回后
 *   经 StartActivityForResult 回调自动刷新状态。
 *
 * 权限判断用 Android 原生 context.checkSelfPermission / Settings.System.canWrite（无新依赖）。
 * 布局：标题「设置」+ 返回（同 LOG 页风格）；Column verticalScroll。
 */
@Composable
fun SettingsPage(
    ui: BluelinkUiState,
    engine: BluelinkEngine?,
    // v0.5.9 UI1b-C：深浅三态（themeMode 当前值 + 变更回调；state 由 MainActivity 主题层持有并持久化）
    themeMode: Int = THEME_MODE_SYSTEM,
    onThemeModeChange: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val pinStore = remember { PinStore(context.applicationContext) }
    val presetStore = remember { HotspotPresetStore(context.applicationContext) }

    // ===== 安全：已配对设备列表（PinStore 镜像；本地增删后刷新；engine 侧配对变化（ui.pairedCount）也刷新）=====
    var pairedItems by remember { mutableStateOf(pinStore.pairedEntries()) }
    fun refreshPaired() {
        pairedItems = pinStore.pairedEntries()
    }
    LaunchedEffect(ui.pairedCount) { refreshPaired() }

    // ===== 安全：破坏性动作确认弹窗 =====
    var resetFpDialog by remember { mutableStateOf(false) }
    var clearPairedDialog by remember { mutableStateOf(false) }

    // ===== 热点：草稿（进入页从 HotspotPresetStore 读初值；保存才写存储）=====
    // SSID 默认值 = HotspotPresetStore.defaultSsid(本机别名)；别名来源 ui.selfCard.alias（engine 填 Build.MODEL）
    val alias = ui.selfCard.alias.ifBlank { Build.MODEL }
    var ssidInput by remember { mutableStateOf(presetStore.ssid().ifBlank { HotspotPresetStore.defaultSsid(alias) }) }
    var pwdInput by remember { mutableStateOf(presetStore.password() ?: "") }
    var presetEnabled by remember { mutableStateOf(presetStore.enabled()) }
    // v0.5.14c：② 私有 API 热点开关（运行时 prefs 直驱，默认开；Switch 改动即写 privateApiEnabled，不依赖「保存预设」）
    var privateApiEnabled by remember { mutableStateOf(presetStore.privateApiEnabled) }
    fun savePreset() {
        val ssid = ssidInput.trim()
        val pwd = pwdInput.trim()
        when {
            ssid.isEmpty() || ssid.length > 32 ->
                ui.showSnack("热点名称（SSID）需 1–32 个字符")
            pwd.isNotEmpty() && (pwd.length < 8 || pwd.length > 63) ->
                ui.showSnack("热点密码需 8–63 位，留空则开热点时随机生成")
            else -> {
                presetStore.save(ssid = ssid, password = pwd.ifEmpty { null }, enabled = presetEnabled)
                ui.showSnack("热点预设已保存")
            }
        }
    }

    // ===== 传输：接收目录（engine.receiveDirUri → DocumentFile 取目录名，失败回退 uri 尾段/默认）=====
    val receiveDirLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) engine?.onReceiveDirPicked(uri)
    }
    val receiveDirLabel = run {
        val uri = engine?.receiveDirUri()
        when {
            uri == null -> "默认（Download/Bluelink）"
            else -> queryTreeDisplayName(context, uri) ?: ui.receiveDirName ?: "已选择目录"
        }
    }

    // ===== 权限检测：系统设置页返回后自动刷新（StartActivityForResult 回调 bump permTick →
    // remember(permTick) 重算 → checkSelfPermission 重读，无需生命周期 API）=====
    var permTick by remember { mutableStateOf(0) }
    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { permTick++ }
    fun openSystemSettings(intent: Intent) {
        try {
            settingsLauncher.launch(intent)
        } catch (e: Exception) {
            ui.showSnack("无法打开系统设置：${e.message ?: "未知错误"}")
        }
    }
    // permTick 作为 key：系统设置返回后重算清单（各权限状态随组合重读刷新）
    val permItems = remember(permTick, context) { buildPermChecks(context) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(SpacingTokens.SpaceLg),
        verticalArrangement = Arrangement.spacedBy(SpacingTokens.SpaceMd),
    ) {
        // 标题行（同 LOG 页风格）：左侧「设置」标题 + 右侧「返回」回主页面
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "设置",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { ui.currentPage = BluelinkUiState.PAGE_HOME }) { Text("返回") }
        }

        // ============ 1. 安全 ============
        SettingsGroup(title = "安全") {
            Text("PIN 验证模式", style = MaterialTheme.typography.titleSmall)
            // 三态分段钮 [关/仅首次/每次]（PinStore.getMode 高亮 / setMode 直写持久化；
            // 引擎 pinRequired()/beginPinVerification 每次读 store 实时生效）
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val modes = listOf(
                    PinStore.MODE_OFF to "关",
                    PinStore.MODE_FIRST to "仅首次",
                    PinStore.MODE_EVERY to "每次",
                )
                modes.forEachIndexed { idx, (m, label) ->
                    SegmentedButton(
                        selected = pinStore.getMode() == m,
                        onClick = { pinStore.setMode(m) },
                        shape = SegmentedButtonDefaults.itemShape(index = idx, count = modes.size),
                        modifier = Modifier.weight(1f),
                    ) { Text(label) }
                }
            }
            Text(
                text = "关=不校验；仅首次=首配后按指纹免验；每次=每会话必验（对端输入配对码）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()
            Text(
                text = "已配对设备（${pairedItems.size}）",
                style = MaterialTheme.typography.titleSmall,
            )
            if (pairedItems.isEmpty()) {
                Text(
                    text = "暂无已配对设备——「仅首次」模式配对成功后记入，同指纹后续免验",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                pairedItems.forEach { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = SpacingTokens.SpaceXs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = entry.alias?.takeIf { it.isNotBlank() } ?: "未知设备",
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "指纹 …${entry.fingerprint.takeLast(6)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = {
                            pinStore.removePaired(entry.fingerprint)
                            refreshPaired()
                            ui.showSnack("已移除配对：${entry.alias?.takeIf { it.isNotBlank() } ?: "未知设备"}")
                        }) { Text("移除") }
                    }
                }
            }

            HorizontalDivider()
            Text("本端指纹", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "重新生成本端设备指纹，已配对设备需重新互认（指纹本身不对用户展示，仅对端识别用）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { resetFpDialog = true }) { Text("重置本端指纹") }

            HorizontalDivider()
            OutlinedButton(
                onClick = { clearPairedDialog = true },
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error),
            ) { Text("清空全部配对") }
            Text(
                text = "清空已配对设备列表（含「仅首次」免验记忆），此操作不可撤销",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ============ 2. 热点 ============
        SettingsGroup(title = "热点") {
            Text(
                text = "预设 SSID/密码：热点方自设 SSID 路径消费；LocalOnly 本地热点（③）由系统生成，不受预设影响",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = ssidInput,
                onValueChange = { ssidInput = it.take(32) },
                label = { Text("热点名称 (SSID)") },
                singleLine = true,
                // v0.5.13 md3-audit-2 FI1：SSID supportingText 实时 inline 长度校验红字（与下方密码字段一致——
                // trim 后 1–32 越界（空/纯空白）→ error 红字 + 已给恢复范围；输入恢复合法即自动还原常规提示）
                supportingText = {
                    val ssid = ssidInput.trim()
                    val ssidInvalid = ssid.isEmpty() || ssid.length > 32
                    Text(
                        text = if (ssidInvalid) {
                            "SSID 需 1–32 个字符"
                        } else {
                            "1–32 字符；默认建议：${HotspotPresetStore.defaultSsid(alias)}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (ssidInvalid) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = pwdInput,
                onValueChange = { pwdInput = it.take(63) },
                label = { Text("热点密码") },
                singleLine = true,
                supportingText = {
                    val pwd = pwdInput.trim()
                    Text(
                        text = if (pwd.isNotEmpty() && (pwd.length < 8 || pwd.length > 63)) {
                            "密码需 8–63 位"
                        } else {
                            "8–63 位；留空=开热点时随机生成"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (pwd.isNotEmpty() && (pwd.length < 8 || pwd.length > 63)) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
            // 自动使用开关：组网时 offer 自动携带预设（enabled=true 才消费；false=热点路径完全现行为）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = SpacingTokens.SpaceXs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "组网时自动用预设",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "开启后 offer 自动携带预设 SSID/密码；关闭=沿用现行为（随机生成）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // v0.5.12 md3-audit-2 A3：Switch 语义名称——左侧标题/说明文字为独立文本节点未绑定，读屏孤立
                // 播「开关」；给 Switch 自身补 contentDescription「组网时自动用预设」（role/checked 由 Switch 自带）
                Switch(
                    checked = presetEnabled,
                    onCheckedChange = { presetEnabled = it },
                    modifier = Modifier.semantics { contentDescription = "组网时自动用预设" },
                )
            }
            Button(
                onClick = { savePreset() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("保存预设") }
            // v0.5.14c：② 私有 API 热点运行时开关（HotspotPresetStore.privateApiEnabled 直驱，默认开）——
            // 关闭 → HotspotManager ② 入口守卫直接失败降级 ③（LocalOnly），不重编译即生效；
            // 样式对齐上方「组网时自动用预设」Switch 行（含 A3 读屏语义 contentDescription）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = SpacingTokens.SpaceXs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "② 私有 API 热点",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "关闭后组网直接用 LocalOnly（③）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // A3（同「组网时自动用预设」行）：Switch 语义名称——左侧标题/说明文字为独立文本节点
                // 未绑定，读屏孤立播「开关」；补 contentDescription「② 私有 API 热点」（role/checked 由 Switch 自带）
                Switch(
                    checked = privateApiEnabled,
                    onCheckedChange = { v ->
                        privateApiEnabled = v
                        presetStore.privateApiEnabled = v
                    },
                    modifier = Modifier.semantics { contentDescription = "② 私有 API 热点" },
                )
            }
        }

        // ============ 3. 传输 ============
        SettingsGroup(title = "传输") {
            Text("接收目录", style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = receiveDirLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (engine?.receiveDirUri() != null) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(
                    onClick = { receiveDirLauncher.launch(initialReceiveDirUri()) },
                    enabled = engine != null,
                ) { Text("更改") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (engine?.receiveDirUri() == null) {
                        "默认：文件先入 App 暂存目录，收到文件后提示选择保存位置（Download 初始目录）"
                    } else {
                        "自定义目录经 SAF 持久授权，重启保持；收到的文件自动转存至该目录"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        engine?.resetReceiveDir()
                        ui.showSnack("已恢复默认接收目录（已存文件不删）")
                    },
                    enabled = engine != null,
                ) { Text("恢复默认") }
            }
        }

        // ============ 4. 外观 ============
        SettingsGroup(title = "外观") {
            Text("深浅模式", style = MaterialTheme.typography.titleSmall)
            // 三态分段钮 [跟随系统/浅色/深色]：themeMode 高亮，回调链 → MainActivity 主题 state + 持久化
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val modes = listOf(
                    THEME_MODE_SYSTEM to "跟随系统",
                    THEME_MODE_LIGHT to "浅色",
                    THEME_MODE_DARK to "深色",
                )
                modes.forEachIndexed { idx, (m, label) ->
                    SegmentedButton(
                        selected = themeMode == m,
                        onClick = { onThemeModeChange(m) },
                        shape = SegmentedButtonDefaults.itemShape(index = idx, count = modes.size),
                        modifier = Modifier.weight(1f),
                    ) { Text(label) }
                }
            }
            Text(
                text = "影响主题配色与壁纸槽位/遮罩（深浅各有一档独立壁纸槽，见个性化页）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = SpacingTokens.SpaceXs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "强调色 / 壁纸",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "强调色（primary 系）与三档壁纸槽在「个性化」页编辑",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { ui.currentPage = BluelinkUiState.PAGE_PERSONAL }) {
                    Text("前往个性化")
                }
            }
        }

        // ============ 5. 权限检测 ============
        SettingsGroup(title = "权限检测") {
            Button(
                onClick = { openSystemSettings(appDetailsIntent(context)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("打开应用权限页") }
            Text(
                text = "未授予项可点右侧「去设置」直达系统相应页；从系统设置返回后自动刷新",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()
            permItems.forEach { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = SpacingTokens.SpaceXs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = item.note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = item.statusText(),
                        style = MaterialTheme.typography.bodySmall,
                        color = item.statusColor(),
                    )
                    if (item.applicable && !item.granted && item.settingsAction != null) {
                        TextButton(onClick = { openSystemSettings(item.settingsAction) }) {
                            Text("去设置")
                        }
                    }
                }
            }
        }
    }

    // ===== 弹窗：重置本端指纹确认（破坏性动作显式确认，audit K12/P1-1）=====
    if (resetFpDialog) {
        AlertDialog(
            onDismissRequest = { resetFpDialog = false },
            title = { Text("重置本端指纹？") },
            text = {
                Text(
                    text = "将重新生成本端设备指纹，旧指纹作废——已配对的对端无法再按旧指纹免验，需重新互认。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pinStore.resetLocalFingerprint()
                    resetFpDialog = false
                    ui.showSnack("本端指纹已重置（对端需重新互认）")
                }) { Text("重置") }
            },
            dismissButton = {
                TextButton(onClick = { resetFpDialog = false }) { Text("取消") }
            },
        )
    }

    // ===== 弹窗：清空全部配对确认 =====
    if (clearPairedDialog) {
        AlertDialog(
            onDismissRequest = { clearPairedDialog = false },
            title = { Text("清空全部配对？") },
            text = {
                Text(
                    text = if (pairedItems.isNotEmpty()) {
                        "将清除已配对的 ${pairedItems.size} 台设备（含仅首次免验记忆），此操作不可撤销。"
                    } else {
                        "将清除全部配对记录（含仅首次免验记忆），此操作不可撤销。"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pinStore.clearAll()
                        clearPairedDialog = false
                        refreshPaired()
                        ui.showSnack("已清空全部配对")
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("清除") }
            },
            dismissButton = {
                TextButton(onClick = { clearPairedDialog = false }) { Text("取消") }
            },
        )
    }
}

/**
 * 设置分组容器（v0.5.4b 分层：页内常规内容块 → surfaceContainerLowest；无 elevation（不设阴影）、
 * 无边框；块级圆角 10（MaterialTheme.shapes.large = ShapeTokens.Modal））。标题行下接
 * HorizontalDivider，区内各分节再以 HorizontalDivider 分隔（audit K11 容器内分节）。
 */
@Composable
private fun SettingsGroup(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SpacingTokens.SpaceLg, vertical = SpacingTokens.SpaceMd),
            verticalArrangement = Arrangement.spacedBy(SpacingTokens.SpaceSm),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            HorizontalDivider()
            content()
        }
    }
}

// ==================== 权限检测清单（按设备 SDK 列适用项） ====================

/** 权限检测单项（applicable=false →「本版本不需要」；settingsAction=null → 无需去设置）。 */
private data class PermCheckItem(
    val title: String,
    val applicable: Boolean,
    val granted: Boolean,
    val note: String,
    val settingsAction: Intent?,
) {
    /** 状态文本（✓/✗ 语义字形 + 文案；色不单独表达状态，audit P1-4）。 */
    fun statusText(): String = when {
        !applicable -> "✓ 本版本不需要"
        granted -> "✓ 已授予"
        else -> "✗ 未授予"
    }

    @Composable
    fun statusColor(): androidx.compose.ui.graphics.Color = when {
        !applicable -> MaterialTheme.colorScheme.onSurfaceVariant
        granted -> MaterialTheme.extended.success
        else -> MaterialTheme.colorScheme.error
    }
}

/** 通用应用详情设置页（BLE/位置/附近设备等未授予项的「去设置」目标）。 */
private fun appDetailsIntent(context: Context): Intent = Intent(
    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
    Uri.fromParts("package", context.packageName, null),
)

/** 通知设置页（Android 13+ POST_NOTIFICATIONS）。 */
private fun appNotificationIntent(context: Context): Intent = Intent(
    Settings.ACTION_APP_NOTIFICATION_SETTINGS,
).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

/** 修改系统设置（AppOps）授权页（WRITE_SETTINGS）。 */
private fun manageWriteSettingsIntent(context: Context): Intent = Intent(
    Settings.ACTION_MANAGE_WRITE_SETTINGS,
    Uri.parse("package:${context.packageName}"),
)

/**
 * 五项权限检测状态（每次组合重读——系统设置返回（permTick）或任何重组合即刷新）。
 * 按设备 SDK 列适用项：蓝牙（12+ 运行时 / 11- BLUETOOTH 安装即授 + 位置说明）、
 * 附近 Wi-Fi 设备（13+）、通知（13+）、修改系统设置（AppOps canWrite 全版本）、
 * 位置（12+ 经 neverForLocation 无需 / 11- 运行时）。
 */
private fun buildPermChecks(context: Context): List<PermCheckItem> {
    val sdk = Build.VERSION.SDK_INT
    fun granted(p: String) =
        context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    return buildList {
        // 1 蓝牙扫描+连接
        if (sdk >= Build.VERSION_CODES.S) {
            val ok = granted(Manifest.permission.BLUETOOTH_SCAN) && granted(Manifest.permission.BLUETOOTH_CONNECT)
            add(
                PermCheckItem(
                    title = "蓝牙扫描 / 连接",
                    applicable = true,
                    granted = ok,
                    note = "12+ 运行时权限；扫描/连接/广播随授权一并请求",
                    settingsAction = appDetailsIntent(context),
                ),
            )
        } else {
            add(
                PermCheckItem(
                    title = "蓝牙扫描 / 连接",
                    applicable = true,
                    granted = true,
                    note = "8–11：BLUETOOTH 安装即授；扫描结果依赖位置授权（见下）",
                    settingsAction = null,
                ),
            )
        }
        // 2 附近 Wi-Fi 设备（13+；LocalOnly/网络直连场景，neverForLocation）
        if (sdk >= Build.VERSION_CODES.TIRAMISU) {
            val ok = granted(Manifest.permission.NEARBY_WIFI_DEVICES)
            add(
                PermCheckItem(
                    title = "附近 Wi-Fi 设备",
                    applicable = true,
                    granted = ok,
                    note = "13+ 运行时权限（经 neverForLocation 声明，不用于定位）",
                    settingsAction = appDetailsIntent(context),
                ),
            )
        } else {
            add(
                PermCheckItem(
                    title = "附近 Wi-Fi 设备",
                    applicable = false,
                    granted = true,
                    note = "13+ 才要求；本版本系统（${sdk}）不需要",
                    settingsAction = null,
                ),
            )
        }
        // 3 通知（13+）
        if (sdk >= Build.VERSION_CODES.TIRAMISU) {
            val ok = granted(Manifest.permission.POST_NOTIFICATIONS)
            add(
                PermCheckItem(
                    title = "通知",
                    applicable = true,
                    granted = ok,
                    note = "13+ 运行时权限（传输状态/自测通知）",
                    settingsAction = appNotificationIntent(context),
                ),
            )
        } else {
            add(
                PermCheckItem(
                    title = "通知",
                    applicable = false,
                    granted = true,
                    note = "13 以下通知默认授予；本版本系统不需要",
                    settingsAction = null,
                ),
            )
        }
        // 4 修改系统设置（AppOps 特殊权限，非运行时弹窗；全版本适用）
        val canWrite = Settings.System.canWrite(context)
        add(
            PermCheckItem(
                title = "修改系统设置",
                applicable = true,
                granted = canWrite,
                note = "AppOps 特殊权限：热点 Binder 直呼 / 8–10 接入路径需启用",
                settingsAction = manageWriteSettingsIntent(context),
            ),
        )
        // 5 位置（12+ 经 neverForLocation 无需定位 / 11- 运行时）
        if (sdk >= Build.VERSION_CODES.S) {
            add(
                PermCheckItem(
                    title = "位置（ACCESS_FINE_LOCATION）",
                    applicable = false,
                    granted = true,
                    note = "12+：BLE 扫描经 neverForLocation 声明，无需定位",
                    settingsAction = null,
                ),
            )
        } else {
            val ok = granted(Manifest.permission.ACCESS_FINE_LOCATION)
            add(
                PermCheckItem(
                    title = "位置（ACCESS_FINE_LOCATION）",
                    applicable = true,
                    granted = ok,
                    note = "8–11：扫描结果投递依赖定位授权",
                    settingsAction = appDetailsIntent(context),
                ),
            )
        }
    }
}

/** 查询 SAF tree uri 的目录显示名（尽力；失败回退 uri 末段解码；与 BluelinkEngine 内同名 helper 同语义）。 */
private fun queryTreeDisplayName(context: Context, uri: Uri): String? {
    val name = try {
        DocumentFile.fromTreeUri(context.applicationContext, uri)?.name?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }
    return name ?: uri.lastPathSegment?.substringAfterLast(':')?.let { Uri.decode(it) }
}

/**
 * 尽力构造 OpenDocumentTree 初始目录 Uri（Downloads；Android 8+ 经 EXTRA_INITIAL_URI 尽力，
 * 不支持则系统默认；同 MainScreen 顶层 helper 语义）。
 */
private fun initialReceiveDirUri(): Uri? = try {
    android.provider.DocumentsContract.buildDocumentUri(
        "com.android.externalstorage.documents",
        "primary:${android.os.Environment.DIRECTORY_DOWNLOADS}",
    )
} catch (e: Exception) {
    null
}
