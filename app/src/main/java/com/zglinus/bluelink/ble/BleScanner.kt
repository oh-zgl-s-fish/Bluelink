package com.zglinus.bluelink.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Handler
import android.util.Log
import com.zglinus.bluelink.diag.DiagLogger

/**
 * BLE 扫描封装：ScanFilter 按 [Constants.SERVICE_UUID] 过滤，只关注 Bluelink 设备。
 * 扫描回调运行在 Binder 线程，统一经 mainHandler 切回主线程再交给上层；stopScan 防泄漏。
 */
class BleScanner(
    private val mainHandler: Handler,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun onScanResult(result: ScanResult)
        fun onScanFailed(reason: String)
    }

    private var scanner: BluetoothLeScanner? = null

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val msg = "onScanResult device=${result.device?.address} rssi=${result.rssi}"
            Log.i(TAG, msg)
            DiagLogger.log(TAG, msg)
            mainHandler.post { callbacks.onScanResult(result) }
        }

        override fun onScanFailed(errorCode: Int) {
            DiagLogger.log(TAG, "onScanFailed code=$errorCode: ${errorCodeToString(errorCode)}")
            mainHandler.post { callbacks.onScanFailed(errorCodeToString(errorCode)) }
        }
    }

    fun start(adapter: BluetoothAdapter) {
        stop()
        val leScanner = adapter.bluetoothLeScanner
        if (leScanner == null) {
            DiagLogger.log(TAG, "bluetoothLeScanner 为 null，本机不支持 BLE 扫描")
            mainHandler.post { callbacks.onScanFailed("本机不支持 BLE 扫描") }
            return
        }
        scanner = leScanner
        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(Constants.serviceParcelUuid())
                .build(),
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            leScanner.startScan(filters, settings, scanCallback)
        } catch (e: Exception) {
            Log.w(TAG, "startScan 异常: $e")
            DiagLogger.log(TAG, "startScan 异常: $e")
            mainHandler.post { callbacks.onScanFailed("startScan: ${e.message}") }
            scanner = null
        }
    }

    fun stop() {
        val s = scanner
        scanner = null
        if (s != null) {
            try {
                s.stopScan(scanCallback)
            } catch (e: Exception) {
                Log.w(TAG, "stopScan 异常: $e")
                DiagLogger.log(TAG, "stopScan 异常: $e")
            }
        }
    }

    private fun errorCodeToString(code: Int): String = when (code) {
        ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "扫描已启动"
        ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "扫描注册失败"
        ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "扫描内部错误"
        ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "本机不支持该扫描特性"
        ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES -> "扫描硬件资源不足"
        else -> "扫描失败($code)"
    }

    companion object {
        private const val TAG = "BleScanner"
    }
}
