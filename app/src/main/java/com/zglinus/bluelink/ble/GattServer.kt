package com.zglinus.bluelink.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.util.Log
import com.zglinus.bluelink.diag.DiagLogger
import java.util.concurrent.ConcurrentHashMap

/**
 * GATT Server 端：暴露 Service + WRITE 特征（收消息）+ NOTIFY 特征（发消息），无需配对。
 *
 * 交互时序：
 * 1. 客户端连上后先订阅 NOTIFY（写 CCC）；
 * 2. 客户端向 WRITE 特征写入本机握手 JSON → 服务端收到并解析；
 * 3. 服务端把自身握手 JSON 经 NOTIFY 特征回给客户端（若尚未订阅则挂起，待订阅后补发）。
 *
 * 回调运行在 Binder 线程，统一切回主线程；close/clearServices 防泄漏。
 */
class GattServer(
    private val context: Context,
    private val mainHandler: Handler,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun onRemoteHandshake(deviceAddress: String, handshake: HandshakeMessage)
    }

    private var gattServer: BluetoothGattServer? = null
    private val connectedDevices = ConcurrentHashMap<String, BluetoothDevice>()
    private val subscribedDevices = ConcurrentHashMap<String, Boolean>()
    private val pendingNotify = ConcurrentHashMap<String, ByteArray>()

    fun start(bluetoothManager: BluetoothManager) {
        stop()
        val server = bluetoothManager.openGattServer(context, serverCallback)
        if (server == null) {
            Log.w(TAG, "openGattServer 返回 null（蓝牙未开或权限不足）")
            DiagLogger.log(TAG, "openGattServer 返回 null（蓝牙未开或权限不足）")
            return
        }
        gattServer = server

        val service = BluetoothGattService(Constants.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        val writeChar = BluetoothGattCharacteristic(
            Constants.WRITE_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )

        val notifyChar = BluetoothGattCharacteristic(
            Constants.NOTIFY_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        val ccc = BluetoothGattDescriptor(
            Constants.CLIENT_CHARACTERISTIC_CONFIG_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
        )
        notifyChar.addDescriptor(ccc)

        service.addCharacteristic(writeChar)
        service.addCharacteristic(notifyChar)
        server.addService(service)
        DiagLogger.log(TAG, "GATT Server 已启动，Service 已注册")
    }

    fun stop() {
        val server = gattServer
        gattServer = null
        connectedDevices.clear()
        subscribedDevices.clear()
        pendingNotify.clear()
        if (server != null) {
            try {
                server.clearServices()
            } catch (e: Exception) {
                Log.w(TAG, "clearServices 异常: $e")
                DiagLogger.log(TAG, "clearServices 异常: $e")
            }
            try {
                server.close()
            } catch (e: Exception) {
                Log.w(TAG, "close 异常: $e")
                DiagLogger.log(TAG, "close 异常: $e")
            }
        }
        DiagLogger.log(TAG, "GATT Server 已停止")
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            mainHandler.post {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    connectedDevices[device.address] = device
                    DiagLogger.log(TAG, "${device.address} 已连接 Server")
                } else {
                    connectedDevices.remove(device.address)
                    subscribedDevices.remove(device.address)
                    pendingNotify.remove(device.address)
                    DiagLogger.log(TAG, "${device.address} 已断开 Server")
                }
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            mainHandler.post {
                if (characteristic.uuid != Constants.WRITE_CHARACTERISTIC_UUID) return@post
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                }
                val msg = HandshakeProtocol.decode(value)
                if (msg != null) {
                    Log.d(TAG, "收到 ${device.address} 握手: ${HandshakeProtocol.toJson(msg)}")
                    DiagLogger.log(TAG, "收到 ${device.address} 握手: ${HandshakeProtocol.toJson(msg)}")
                    callbacks.onRemoteHandshake(device.address, msg)
                    // 回发本机握手（尚未订阅则挂起，订阅后补发）
                    val reply = HandshakeProtocol.encode(HandshakeProtocol.buildLocal(context))
                    if (subscribedDevices[device.address] == true) {
                        DiagLogger.log(TAG, "${device.address} 已订阅，立即回发握手 ${reply.size}B")
                        sendNotify(device, reply)
                    } else {
                        DiagLogger.log(TAG, "${device.address} 尚未订阅，握手回复挂起 ${reply.size}B")
                        pendingNotify[device.address] = reply
                    }
                } else {
                    Log.w(TAG, "握手 JSON 无效，来自 ${device.address}")
                    DiagLogger.log(TAG, "握手 JSON 无效，来自 ${device.address}")
                }
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            mainHandler.post {
                if (descriptor.uuid != Constants.CLIENT_CHARACTERISTIC_CONFIG_UUID) return@post
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                }
                val enabled = value.isNotEmpty() && value[0].toInt() == 1
                subscribedDevices[device.address] = enabled
                DiagLogger.log(TAG, "${device.address} 订阅 NOTIFY: enabled=$enabled")
                if (enabled) {
                    pendingNotify.remove(device.address)?.let {
                        DiagLogger.log(TAG, "${device.address} 订阅后补发挂起握手 ${it.size}B")
                        sendNotify(device, it)
                    }
                }
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "onNotificationSent status=$status 到 ${device.address}")
                DiagLogger.log(TAG, "onNotificationSent 非成功 status=$status 到 ${device.address}")
            }
        }
    }

    private fun sendNotify(device: BluetoothDevice, bytes: ByteArray) {
        val server = gattServer ?: return
        val ch = server.getService(Constants.SERVICE_UUID)
            ?.getCharacteristic(Constants.NOTIFY_CHARACTERISTIC_UUID) ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                server.notifyCharacteristicChanged(device, ch, false, bytes)
            } else {
                @Suppress("DEPRECATION")
                ch.value = bytes
                @Suppress("DEPRECATION")
                server.notifyCharacteristicChanged(device, ch, false)
            }
        } catch (e: Exception) {
            Log.w(TAG, "notifyCharacteristicChanged 异常: $e")
            DiagLogger.log(TAG, "notifyCharacteristicChanged 异常: $e")
        }
    }

    companion object {
        private const val TAG = "GattServer"
    }
}
