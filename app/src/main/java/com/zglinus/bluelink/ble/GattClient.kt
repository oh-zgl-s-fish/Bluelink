package com.zglinus.bluelink.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.util.Log

/**
 * GATT Client 端：连接对方 → 订阅 NOTIFY → 写入本机握手 JSON → 等待对方握手通知。
 *
 * - 连接失败 / 服务缺失 / 超时（[Constants.HANDSHAKE_TIMEOUT_MS]，10s）自动断开并释放；
 * - 同一时间只处理一个握手会话，忙时直接拒绝新请求；
 * - 回调运行在 Binder 线程，统一切回主线程；disconnect/close 防泄漏。
 */
class GattClient(
    private val context: Context,
    private val mainHandler: Handler,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun onHandshakeCompleted(deviceAddress: String, handshake: HandshakeMessage)
        fun onHandshakeFailed(deviceAddress: String, reason: String)
    }

    private var gatt: BluetoothGatt? = null
    private var targetAddress: String? = null
    private var notifyChar: BluetoothGattCharacteristic? = null
    private var handshakeDone = false
    private var cleaned = false

    private val timeoutRunnable = Runnable {
        fail("握手超时(${Constants.HANDSHAKE_TIMEOUT_MS}ms)")
    }

    fun connect(device: BluetoothDevice) {
        if (gatt != null) {
            Log.w(TAG, "已有进行中的握手会话，忽略 ${device.address}")
            callbacks.onHandshakeFailed(device.address, "本机握手会话忙")
            return
        }
        handshakeDone = false
        cleaned = false
        targetAddress = device.address
        Log.d(TAG, "连接 ${device.address} 开始握手")
        @Suppress("DEPRECATION")
        val g = device.connectGatt(context, false, gattCallback)
        if (g == null) {
            fail("connectGatt 返回 null")
            return
        }
        gatt = g
        mainHandler.postDelayed(timeoutRunnable, Constants.HANDSHAKE_TIMEOUT_MS)
    }

    /** 主动取消当前会话（静默，不回调失败）。 */
    fun cancel() {
        val addr = targetAddress
        cleanup()
        if (addr != null) callbacks.onHandshakeFailed(addr, "已取消")
    }

    /** 释放资源（静默）。 */
    fun release() = cleanup()

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            mainHandler.post {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            fail("连接失败(status=$status)")
                        } else {
                            gatt.discoverServices()
                        }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        if (!handshakeDone && !cleaned) fail("连接断开")
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            mainHandler.post {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    fail("服务发现失败(status=$status)")
                    return@post
                }
                val service = gatt.getService(Constants.SERVICE_UUID)
                if (service == null) {
                    fail("对方未暴露 Bluelink 服务")
                    return@post
                }
                val write = service.getCharacteristic(Constants.WRITE_CHARACTERISTIC_UUID)
                val notify = service.getCharacteristic(Constants.NOTIFY_CHARACTERISTIC_UUID)
                if (write == null || notify == null) {
                    fail("对方服务缺少握手特征")
                    return@post
                }
                notifyChar = notify
                // 先订阅 NOTIFY（写 CCC），随后写入本机握手
                gatt.setCharacteristicNotification(notify, true)
                val ccc = notify.getDescriptor(Constants.CLIENT_CHARACTERISTIC_CONFIG_UUID)
                if (ccc != null) {
                    writeDescriptorCompat(gatt, ccc, byteArrayOf(0x01, 0x00))
                }
                val bytes = HandshakeProtocol.encode(HandshakeProtocol.buildLocal(context))
                writeCharacteristicCompat(gatt, write, bytes)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "CCC 写入失败 status=$status")
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            handleNotify(characteristic.value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleNotify(value)
        }
    }

    private fun handleNotify(value: ByteArray) {
        mainHandler.post {
            if (handshakeDone || cleaned) return@post
            if (notifyChar?.uuid != Constants.NOTIFY_CHARACTERISTIC_UUID) return@post
            val msg = HandshakeProtocol.decode(value)
            if (msg != null) {
                handshakeDone = true
                val addr = targetAddress
                Log.d(TAG, "收到 ${addr} 握手: ${HandshakeProtocol.toJson(msg)}")
                cleanup()
                if (addr != null) callbacks.onHandshakeCompleted(addr, msg)
            } else {
                Log.w(TAG, "对方握手通知解析失败")
            }
        }
    }

    private fun writeCharacteristicCompat(
        gatt: BluetoothGatt,
        ch: BluetoothGattCharacteristic,
        bytes: ByteArray,
    ) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(ch, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            } else {
                @Suppress("DEPRECATION")
                ch.value = bytes
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(ch)
            }
        } catch (e: Exception) {
            Log.w(TAG, "writeCharacteristic 异常: $e")
            fail("写入异常: ${e.message}")
        }
    }

    private fun writeDescriptorCompat(gatt: BluetoothGatt, desc: BluetoothGattDescriptor, bytes: ByteArray) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(desc, bytes)
            } else {
                @Suppress("DEPRECATION")
                desc.value = bytes
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(desc)
            }
        } catch (e: Exception) {
            Log.w(TAG, "writeDescriptor 异常: $e")
        }
    }

    private fun fail(reason: String) {
        if (cleaned) return
        val addr = targetAddress
        Log.w(TAG, "握手失败 ${addr}: $reason")
        cleanup()
        if (addr != null) callbacks.onHandshakeFailed(addr, reason)
    }

    private fun cleanup() {
        mainHandler.removeCallbacks(timeoutRunnable)
        val g = gatt
        gatt = null
        cleaned = true
        notifyChar = null
        targetAddress = null
        if (g != null) {
            try {
                @Suppress("DEPRECATION")
                g.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "disconnect 异常: $e")
            }
            try {
                g.close()
            } catch (e: Exception) {
                Log.w(TAG, "close 异常: $e")
            }
        }
    }

    companion object {
        private const val TAG = "GattClient"
    }
}
