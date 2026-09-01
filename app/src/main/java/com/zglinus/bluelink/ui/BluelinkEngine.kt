package com.zglinus.bluelink.ui

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.zglinus.bluelink.ble.BleAdvertiser
import com.zglinus.bluelink.ble.BleScanner
import com.zglinus.bluelink.ble.GattClient
import com.zglinus.bluelink.ble.GattServer
import com.zglinus.bluelink.ble.HandshakeMessage
import com.zglinus.bluelink.ble.RootDetector
import com.zglinus.bluelink.diag.DiagLogger
import com.zglinus.bluelink.net.NetworkInfoProvider
import com.zglinus.bluelink.net.SameLanChecker

/**
 * 一期 BLE 链路接线（生命周期由 MainActivity 持有）：
 * - 广播 / 扫描 / GATT Server 随顶部开关启停；
 * - GATT Client 在点击设备时发起握手；
 * - 握手结果（Server 端收 + Client 端收）统一写入 [BluelinkUiState]。
 *
 * 所有 BLE 回调已由各封装切回主线程；UI 状态只在主线程写入。
 */
class BluelinkEngine(private val context: Context) {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private val bluetoothManager: BluetoothManager? =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    @Suppress("DEPRECATION")
    private val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()

    private val advertiser = BleAdvertiser(mainHandler, object : BleAdvertiser.Callbacks {
        override fun onAdvertisingStarted() {
            ui.advertising = true
            ui.advertiserError = null
            DiagLogger.log(TAG, "广播已启动（回调 UI）")
        }

        override fun onAdvertisingFailed(reason: String) {
            ui.advertising = false
            ui.advertiserError = reason
            DiagLogger.log(TAG, "广播启动失败回调 UI: $reason")
        }
    })

    private val scanner = BleScanner(mainHandler, object : BleScanner.Callbacks {
        override fun onScanResult(result: android.bluetooth.le.ScanResult) {
            handleScanResult(result)
        }

        override fun onScanFailed(reason: String) {
            ui.scanError = reason
            DiagLogger.log(TAG, "扫描失败回调 UI: $reason")
        }
    })

    private val gattServer = GattServer(appContext, mainHandler, object : GattServer.Callbacks {
        override fun onRemoteHandshake(deviceAddress: String, handshake: HandshakeMessage) {
            DiagLogger.log(TAG, "Server 收到远端握手: $deviceAddress alias=${handshake.alias}")
            applyRemoteHandshake(deviceAddress, handshake)
        }
    })

    private val gattClient = GattClient(appContext, mainHandler, object : GattClient.Callbacks {
        override fun onHandshakeCompleted(deviceAddress: String, handshake: HandshakeMessage) {
            ui.handshaking = false
            ui.handshakeError = null
            DiagLogger.log(TAG, "握手完成回调 UI: $deviceAddress alias=${handshake.alias}")
            applyRemoteHandshake(deviceAddress, handshake)
        }

        override fun onHandshakeFailed(deviceAddress: String, reason: String) {
            ui.handshaking = false
            ui.handshakeError = reason
            DiagLogger.log(TAG, "握手失败回调 UI: $deviceAddress reason=$reason")
        }
    })

    val ui = BluelinkUiState()

    private val bleStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val state = intent?.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            mainHandler.post {
                ui.btEnabled = state == BluetoothAdapter.STATE_ON
                DiagLogger.log(TAG, "蓝牙状态变更: ${btStateName(state ?: BluetoothAdapter.ERROR)}")
                if (ui.btEnabled) startBleIfNeeded() else stopAllBle()
            }
        }
    }

    /** 应用启动：root 探测 + 网络采集 + 蓝牙状态监听 + 启动 BLE（权限已授予时）。 */
    fun onStart() {
        RootDetector.init()
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        ContextCompat.registerReceiver(
            appContext, bleStateReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
        refreshNetwork()
        ui.btEnabled = adapter?.isEnabled == true
        if (ui.btEnabled) startBleIfNeeded()
    }

    /** 权限请求结果回填。 */
    fun onPermissionsResult(granted: Boolean) {
        ui.permissionsGranted = granted
        DiagLogger.log(TAG, "权限请求结果 granted=$granted")
        if (granted) {
            ui.btEnabled = adapter?.isEnabled == true
            if (ui.btEnabled) startBleIfNeeded()
        } else {
            stopAllBle()
        }
    }

    /** 顶部广播开关。 */
    fun setAdvertisingWanted(wanted: Boolean) {
        ui.advertisingWanted = wanted
        DiagLogger.log(TAG, "用户设置广播/扫描开关 wanted=$wanted")
        if (wanted && ui.permissionsGranted && ui.btEnabled) {
            startBleIfNeeded()
        } else if (!wanted) {
            stopAllBle()
        }
    }

    /** 刷新本机网络摘要，并重算所有已握手设备的同网判定。 */
    fun refreshNetwork() {
        ui.localNetwork = NetworkInfoProvider.collect(appContext)
        refreshAllLanStatus()
    }

    /** 点击设备：先展示弹层；无握手时发起 GATT 客户端握手。 */
    fun openDevice(entry: DeviceEntry) {
        ui.selectedDevice = entry
        if (entry.handshake != null) return // 已有握手，直接展示详情
        val a = adapter
        if (a == null || !a.isEnabled || !ui.permissionsGranted) return
        val device = try {
            a.getRemoteDevice(entry.address)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "非法 MAC: ${entry.address}")
            DiagLogger.log(TAG, "非法 MAC: ${entry.address}")
            return
        }
        ui.handshaking = true
        ui.handshakeError = null
        DiagLogger.log(TAG, "点设备发起握手 ${device.address}")
        // 握手连接仲裁：若本机 Server 已与该设备建立反连接，先断开对端反连，
        // 避免同一设备 Client→对端 + 对端 Client→本机 Server 双 GATT 连接并发（蓝牙栈写入挂起根因）；
        // 断开后延迟 200ms 等栈稳定再发起 Client 连接。
        if (gattServer.isDeviceConnected(device.address)) {
            DiagLogger.log(TAG, "为避免双连接，已断开对端反连 ${device.address}")
            gattServer.disconnectDevice(device.address)
            mainHandler.postDelayed({ gattClient.connect(device) }, 200L)
        } else {
            gattClient.connect(device)
        }
    }

    fun dismissSheet() {
        ui.selectedDevice = null
    }

    /** 退出：停掉所有 BLE 并注销接收器（防泄漏）。 */
    fun release() {
        stopAllBle()
        try {
            appContext.unregisterReceiver(bleStateReceiver)
        } catch (e: Exception) {
            // 未注册时忽略
        }
        gattClient.release()
    }

    // ---------- 内部 ----------

    private fun startBleIfNeeded() {
        if (!ui.permissionsGranted || !ui.btEnabled || !ui.advertisingWanted) return
        val a = adapter ?: return
        if (!a.isEnabled) return
        stopAllBle() // 幂等重启
        DiagLogger.log(TAG, "启动 BLE：广播/扫描/GATT Server")
        advertiser.start(a)
        scanner.start(a)
        bluetoothManager?.let { gattServer.start(it) }
        ui.scanning = true
    }

    private fun stopAllBle() {
        DiagLogger.log(TAG, "停止 BLE：广播/扫描/GATT Server/客户端")
        advertiser.stop()
        scanner.stop()
        gattServer.stop()
        gattClient.release() // 静默中断进行中的握手
        ui.advertising = false
        ui.scanning = false
    }

    private fun handleScanResult(result: android.bluetooth.le.ScanResult) {
        val device = result.device ?: return
        val addr = device.address ?: return
        if (addr == adapter?.address) return // 跳过本机
        val now = System.currentTimeMillis()
        val existing = ui.devices[addr]
        ui.devices[addr] = when {
            existing == null -> DeviceEntry(
                address = addr,
                rssi = result.rssi,
                firstSeen = now,
                lastSeen = now,
            )
            existing.rssi != result.rssi || now - existing.lastSeen > 5_000L ->
                existing.copy(rssi = result.rssi, lastSeen = now)
            else -> existing
        }
    }

    private fun applyRemoteHandshake(deviceAddress: String, handshake: HandshakeMessage) {
        val now = System.currentTimeMillis()
        val existing = ui.devices[deviceAddress]
        val entry = (existing ?: DeviceEntry(deviceAddress, 0, now, now))
            .copy(handshake = handshake, lastSeen = now)
        ui.devices[deviceAddress] = entry.copy(
            lanStatus = SameLanChecker.check(ui.localNetwork, handshake.net)
        )
        if (ui.selectedDevice?.address == deviceAddress) {
            ui.selectedDevice = ui.devices[deviceAddress]
        }
    }

    private fun refreshAllLanStatus() {
        ui.devices.keys.toList().forEach { addr ->
            val e = ui.devices[addr] ?: return@forEach
            val hs = e.handshake ?: return@forEach
            ui.devices[addr] = e.copy(lanStatus = SameLanChecker.check(ui.localNetwork, hs.net))
        }
    }

    companion object {
        private const val TAG = "BluelinkEngine"

        private fun btStateName(state: Int): String = when (state) {
            BluetoothAdapter.STATE_ON -> "开"
            BluetoothAdapter.STATE_OFF -> "关"
            BluetoothAdapter.STATE_TURNING_ON -> "正在开启"
            BluetoothAdapter.STATE_TURNING_OFF -> "正在关闭"
            else -> "未知($state)"
        }
    }
}
