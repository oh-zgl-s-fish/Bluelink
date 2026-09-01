package com.zglinus.bluelink.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.os.Handler
import android.util.Log
import com.zglinus.bluelink.diag.DiagLogger

/**
 * BLE 广播封装：载荷仅携带自定义 Service UUID（128 位，18 字节，单包内放得下），
 * 不带设备名（握手后由 GATT 交换得到）。
 * 回调统一切回主线程；stopAdvertising 防泄漏。
 */
class BleAdvertiser(
    private val mainHandler: Handler,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun onAdvertisingStarted()
        fun onAdvertisingFailed(reason: String)
    }

    private var advertiser: BluetoothLeAdvertiser? = null

    private val advCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            DiagLogger.log(TAG, "onStartSuccess 广播已启动")
            mainHandler.post { callbacks.onAdvertisingStarted() }
        }

        override fun onStartFailure(errorCode: Int) {
            DiagLogger.log(TAG, "onStartFailure code=$errorCode: ${errorCodeToString(errorCode)}")
            mainHandler.post { callbacks.onAdvertisingFailed(errorCodeToString(errorCode)) }
        }
    }

    fun start(adapter: BluetoothAdapter) {
        stop()
        val leAdvertiser = adapter.bluetoothLeAdvertiser
        if (leAdvertiser == null) {
            DiagLogger.log(TAG, "bluetoothLeAdvertiser 为 null，本机不支持 BLE 广播")
            mainHandler.post { callbacks.onAdvertisingFailed("本机不支持 BLE 广播") }
            return
        }
        advertiser = leAdvertiser
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(Constants.serviceParcelUuid())
            .build()
        try {
            leAdvertiser.startAdvertising(settings, data, advCallback)
        } catch (e: Exception) {
            Log.w(TAG, "startAdvertising 异常: $e")
            DiagLogger.log(TAG, "startAdvertising 异常: $e")
            mainHandler.post { callbacks.onAdvertisingFailed("startAdvertising: ${e.message}") }
            advertiser = null
        }
    }

    fun stop() {
        val a = advertiser
        advertiser = null
        if (a != null) {
            try {
                a.stopAdvertising(advCallback)
            } catch (e: Exception) {
                Log.w(TAG, "stopAdvertising 异常: $e")
                DiagLogger.log(TAG, "stopAdvertising 异常: $e")
            }
        }
    }

    private fun errorCodeToString(code: Int): String = when (code) {
        AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "广播已启动"
        AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "广播数据过大"
        AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "本机不支持该广播特性"
        AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "广播内部错误"
        AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "广播实例过多"
        else -> "广播失败($code)"
    }

    companion object {
        private const val TAG = "BleAdvertiser"
    }
}
