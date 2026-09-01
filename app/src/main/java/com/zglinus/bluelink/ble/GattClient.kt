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
import com.zglinus.bluelink.diag.DiagLogger

/**
 * GATT Client 端：连接对方 → 订阅 NOTIFY → 写入本机握手 JSON → 等待对方握手通知。
 *
 * - 连接失败 / 服务缺失 / 超时（[Constants.HANDSHAKE_TIMEOUT_MS]，10s）自动断开并释放；
 * - 同一时间只处理一个握手会话，忙时直接拒绝新请求；
 * - MTU 协商先行：默认 ATT MTU=23 单包载荷仅 20B，150B 握手 JSON 传不过去，连接成功后先
 *   requestMtu(512)，onMtuChanged 后再 discoverServices；写入前按协商 MTU 做长度校验；
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

    /** 当前 ATT MTU（requestMtu 协商结果；未协商/协商失败兜底默认 23）。 */
    private var mtu: Int = DEFAULT_ATT_MTU

    private val timeoutRunnable = Runnable {
        fail("握手超时(${Constants.HANDSHAKE_TIMEOUT_MS}ms)")
    }

    /** 写入兜底：握手写发起后 3s 内 onCharacteristicWrite 未回调（蓝牙栈写入挂起）则判失败。 */
    private val writeTimeoutRunnable = Runnable {
        fail("握手写入 3s 无回调(status 未返回)")
    }

    fun connect(device: BluetoothDevice) {
        if (gatt != null) {
            Log.w(TAG, "已有进行中的握手会话，忽略 ${device.address}")
            DiagLogger.log(TAG, "已有进行中的握手会话，拒绝新请求 ${device.address}")
            callbacks.onHandshakeFailed(device.address, "本机握手会话忙")
            return
        }
        handshakeDone = false
        cleaned = false
        mtu = DEFAULT_ATT_MTU
        targetAddress = device.address
        Log.d(TAG, "连接 ${device.address} 开始握手")
        DiagLogger.log(TAG, "连接 ${device.address} 开始握手")
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
        if (addr != null) {
            DiagLogger.log(TAG, "主动取消握手 $addr")
            callbacks.onHandshakeFailed(addr, "已取消")
        }
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
                            DiagLogger.log(TAG, "已连接 ${targetAddress}，开始 MTU 协商")
                            // MTU 协商先行：默认 ATT MTU=23 时单包载荷仅 20B，150B 握手 JSON 传不过去；
                            // 先 requestMtu(512)，随后由 onMtuChanged 触发 discoverServices
                            if (!gatt.requestMtu(REQUESTED_MTU)) {
                                Log.w(TAG, "requestMtu 返回 false，按默认 MTU 继续服务发现")
                                DiagLogger.log(TAG, "requestMtu 返回 false，按默认 MTU 继续服务发现")
                                gatt.discoverServices()
                            }
                        }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        if (!handshakeDone && !cleaned) fail("连接断开")
                    }
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            mainHandler.post {
                if (cleaned) return@post
                // 成功取协商值；失败兜底默认 23（ATT 标准最小 MTU）。无论结果都继续服务发现
                this@GattClient.mtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu else DEFAULT_ATT_MTU
                Log.d(TAG, "MTU 协商: mtu=${this@GattClient.mtu} status=$status，200ms 后服务发现")
                // MTU 协商后给蓝牙栈 200ms 稳定窗口再 discoverServices，降低写入挂起概率
                DiagLogger.log(TAG, "onMtuChanged: mtu=${this@GattClient.mtu} status=$status，延迟 200ms 服务发现（栈稳定窗口）")
                mainHandler.postDelayed({ gatt.discoverServices() }, MTU_SETTLE_DELAY_MS)
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
                // 写入前长度校验（防御）：ATT 层 3 字节头开销，单包载荷上限 = mtu - 3
                val maxPayload = mtu - 3
                if (bytes.size > maxPayload) {
                    fail("握手消息 ${bytes.size}B 超出当前 MTU ${maxPayload}B")
                    return@post
                }
                writeCharacteristicCompat(gatt, write, bytes)
                DiagLogger.log(TAG, "握手写入已发起: ${bytes.size}B（MTU=$mtu，单包上限=${maxPayload}B）")
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "CCC 写入失败 status=$status")
                DiagLogger.log(TAG, "CCC 写入失败 status=$status")
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            mainHandler.post {
                // 写入兜底：无论 status 都撤掉 3s 写入超时（status 已返回，栈未挂起）
                mainHandler.removeCallbacks(writeTimeoutRunnable)
                // 握手写入结果确认：失败立即终止，避免干等 10s 超时；
                // fail 内部有 cleaned 防重入，握手成功路径 cleanup 后不会再走到
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    fail("握手消息发送失败(status=$status)")
                } else {
                    DiagLogger.log(TAG, "onCharacteristicWrite 握手写入确认成功 status=$status")
                }
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
                DiagLogger.log(TAG, "收到对方 ${addr} 握手: ${HandshakeProtocol.toJson(msg)}")
                cleanup()
                if (addr != null) callbacks.onHandshakeCompleted(addr, msg)
            } else {
                Log.w(TAG, "对方握手通知解析失败")
                DiagLogger.log(TAG, "对方握手通知解析失败（${value.size}B）")
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
            mainHandler.removeCallbacks(writeTimeoutRunnable)
            Log.w(TAG, "writeCharacteristic 异常: $e")
            DiagLogger.log(TAG, "writeCharacteristic 异常: $e")
            fail("写入异常: ${e.message}")
        }
        // 写入兜底：发起写后 3s 无 onCharacteristicWrite 回调（栈挂起）则判失败；回调/cleanup 撤除
        mainHandler.postDelayed(writeTimeoutRunnable, WRITE_TIMEOUT_MS)
        DiagLogger.log(TAG, "握手写入已发起，3s 写入兜底超时已启动")
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
            DiagLogger.log(TAG, "writeDescriptor 异常: $e")
        }
    }

    private fun fail(reason: String) {
        if (cleaned) return
        val addr = targetAddress
        Log.w(TAG, "握手失败 ${addr}: $reason")
        DiagLogger.log(TAG, "握手失败 ${addr}: $reason")
        cleanup()
        if (addr != null) callbacks.onHandshakeFailed(addr, reason)
    }

    private fun cleanup() {
        mainHandler.removeCallbacks(timeoutRunnable)
        mainHandler.removeCallbacks(writeTimeoutRunnable)
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
                DiagLogger.log(TAG, "disconnect 异常: $e")
            }
            try {
                g.close()
            } catch (e: Exception) {
                Log.w(TAG, "close 异常: $e")
                DiagLogger.log(TAG, "close 异常: $e")
            }
        }
    }

    companion object {
        private const val TAG = "GattClient"

        /** 请求协商的 ATT MTU（Android 常见上限 512，对端取较小值）。 */
        private const val REQUESTED_MTU = 512

        /** BLE 默认 ATT MTU（未协商/协商失败兜底，载荷上限 = mtu - 3 = 20B）。 */
        private const val DEFAULT_ATT_MTU = 23

        /** MTU 协商后给蓝牙栈的稳定窗口，之后才 discoverServices。 */
        private const val MTU_SETTLE_DELAY_MS = 200L

        /** 握手写入兜底超时：发起写后 3s 无回调判失败（早于 10s 总超时先失败）。 */
        private const val WRITE_TIMEOUT_MS = 3000L
    }
}
