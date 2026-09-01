package com.zglinus.bluelink.ui

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.zglinus.bluelink.ble.HandshakeMessage
import com.zglinus.bluelink.ble.HandshakeProtocol
import com.zglinus.bluelink.net.LanStatus

/**
 * 主页面（docs/ui-design.md §4.1 一期最简版）：
 * 权限/蓝牙引导卡 → 本机状态卡（广播开关 + 网络摘要）→ 附近设备列表 → 空态文案。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    ui: BluelinkUiState,
    advertisingWanted: Boolean,
    onAdvertisingWantedChange: (Boolean) -> Unit,
    onDeviceClick: (DeviceEntry) -> Unit,
    onRefreshNetwork: () -> Unit,
    onRequestPermissions: () -> Unit,
) {
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
        ) {
            if (!ui.permissionsGranted) {
                PermissionBanner(onRequestPermissions)
            } else if (!ui.btEnabled) {
                BluetoothOffBanner()
            }

            Spacer(Modifier.height(12.dp))

            Text("Bluelink", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))

            LocalStatusCard(
                ui = ui,
                advertisingWanted = advertisingWanted,
                onAdvertisingWantedChange = onAdvertisingWantedChange,
                onRefreshNetwork = onRefreshNetwork,
            )

            Spacer(Modifier.height(8.dp))

            if (ui.devices.isEmpty()) {
                EmptyState()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        Text("附近的设备", style = MaterialTheme.typography.titleMedium)
                    }
                    items(
                        ui.devices.values.sortedBy { it.firstSeen },
                        key = { it.address },
                    ) { entry ->
                        DeviceRow(entry = entry, onClick = { onDeviceClick(entry) })
                    }
                }
            }
        }
    }
}

/** 本机状态卡：广播开关（Switch）+ 本机网络摘要（Wi-Fi/蜂窝/IP/子网）。 */
@Composable
private fun LocalStatusCard(
    ui: BluelinkUiState,
    advertisingWanted: Boolean,
    onAdvertisingWantedChange: (Boolean) -> Unit,
    onRefreshNetwork: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("本机", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = Build.MODEL,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = advertisingWanted,
                    onCheckedChange = onAdvertisingWantedChange,
                )
            }
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = ui.localNetwork.describe(),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRefreshNetwork) { Text("刷新") }
            }
            if (ui.advertising) {
                Text(
                    text = "广播中 · 扫描中",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Text(
                    text = "广播已停止",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ui.advertiserError?.let {
                Text(
                    text = "广播异常: $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            ui.scanError?.let {
                Text(
                    text = "扫描异常: $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** 设备行：握手后显示别名/型号/MAC/RSSI/网络徽标/同网标记；握手前显示 MAC+RSSI。 */
@Composable
private fun DeviceRow(entry: DeviceEntry, onClick: () -> Unit) {
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val hs = entry.handshake
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = hs?.alias?.takeIf { it.isNotBlank() } ?: "未知设备",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (hs != null) {
                    NetworkBadge(hs)
                } else {
                    Text(
                        text = "扫描中…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (hs != null) {
                Text(
                    text = listOfNotNull(
                        hs.model.takeIf { it.isNotBlank() },
                        entry.displayMac,
                        "${entry.rssi} dBm",
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = "${entry.displayMac} · ${entry.rssi} dBm",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = when (entry.lanStatus) {
                    LanStatus.SAME_LAN -> "✅ 同网"
                    LanStatus.DIFFERENT_NETWORK -> "🌐 异网"
                    LanStatus.UNKNOWN -> "❔ 未知"
                },
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

/** 网络徽标：同Wi-Fi / 蜂窝 / 未知（取自对方握手 net 字段）。 */
@Composable
private fun NetworkBadge(hs: HandshakeMessage) {
    val (text, color) = when {
        hs.net.wifi -> "同Wi-Fi" to Color(0xFF2E7D32)
        hs.net.cellular -> "蜂窝" to Color(0xFFEF6C00)
        else -> "未知" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = MaterialTheme.shapes.small,
        color = color.copy(alpha = 0.15f),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

/** 权限引导卡（未授权时置顶）。 */
@Composable
private fun PermissionBanner(onRequestPermissions: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "需要权限: 蓝牙 + 位置",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRequestPermissions) { Text("去授权") }
        }
    }
}

/** 蓝牙未开提示（不自动开，仅提示）。 */
@Composable
private fun BluetoothOffBanner() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Text(
            text = "请在系统设置开启蓝牙",
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** 无设备空态。 */
@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "等待周围设备…",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "确保对方已打开 Bluelink 广播",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 设备详情弹层：握手详情 JSON 渲染 + 同网判定结果。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceDetailSheet(
    entry: DeviceEntry,
    handshaking: Boolean,
    handshakeError: String?,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val hs = entry.handshake
            Text(
                text = hs?.alias?.takeIf { it.isNotBlank() } ?: "未知设备",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = "${entry.displayMac} · ${entry.rssi} dBm",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()

            Text("同网判定", style = MaterialTheme.typography.titleSmall)
            Text(
                text = when (entry.lanStatus) {
                    LanStatus.SAME_LAN -> "✅ 同网（同一子网，可直连传输）"
                    LanStatus.DIFFERENT_NETWORK -> "🌐 异网（需二期组网）"
                    LanStatus.UNKNOWN -> "❔ 未知（信息不足）"
                },
                style = MaterialTheme.typography.bodyMedium,
            )

            HorizontalDivider()

            Text("握手详情", style = MaterialTheme.typography.titleSmall)
            when {
                hs != null -> Text(
                    text = HandshakeProtocol.prettyJson(hs),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                handshaking -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("正在握手…", style = MaterialTheme.typography.bodyMedium)
                }
                handshakeError != null -> Text(
                    text = "握手失败: $handshakeError",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                else -> Text(
                    text = "点击设备后发起 GATT 握手",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("关闭") }
        }
    }
}
