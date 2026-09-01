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
 * - ATT 操作串行化：服务发现后只写 CCC 订阅，主握手写入等 onDescriptorWrite 确认
 *   CCC 写完后才发起，避免背靠背发起 CCC 与 WRITE 被蓝牙栈单请求串行模型静默丢弃
 *   （onCharacteristicWrite 永不回调）；
 * - 发送串行队列（同连接单写互斥）：握手写入与会话信令写入共用一条 FIFO 队列 +
 *   inFlight 标志——同一时刻至多一条 write 在途，其余入队等待，onCharacteristicWrite
 *   成功/失败回调后出队下一条，杜绝背靠背 writeCharacteristic 被栈单请求模型拒绝
 *   （真机实锤：pong 回写与定时 ping 背靠背时返回 false 被拒，offer/joined 等信令丢失）；
 * - 回调运行在 Binder 线程，统一切回主线程；disconnect/close 防泄漏；
 * - 持久信令会话（A2）：握手成功后由 SessionManager.attach → keepAlive() 保留连接进入会话
 *   模式（替代硬 cleanup），NOTIFY 收信令 / WRITE 发信令；detach/release 恢复原 cleanup；
 *   未 attach 时行为与一期完全一致（握手完成即清理）。
 */
class GattClient(
    private val context: Context,
    private val mainHandler: Handler,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun onHandshakeCompleted(deviceAddress: String, handshake: HandshakeMessage)
        fun onHandshakeFailed(deviceAddress: String, reason: String)

        /** 会话期收到远端信令（NOTIFY 字节经 SignalProtocol.decode 成功分流）。默认空实现保持兼容。 */
        fun onRemoteSignal(deviceAddress: String, msg: SignalMessage) = Unit

        /** 会话期底层连接断开（GattClient 已静默清理死连接），供 SessionManager 自动重连决策。 */
        fun onSessionDisconnected(deviceAddress: String) = Unit
    }

    private var gatt: BluetoothGatt? = null
    private var targetAddress: String? = null
    private var notifyChar: BluetoothGattCharacteristic? = null

    /** 服务发现时暂存的 WRITE 特征，供 onDescriptorWrite（CCC 写完）后发起主握手写入。 */
    private var pendingWriteChar: BluetoothGattCharacteristic? = null
    private var handshakeDone = false
    private var cleaned = false

    /** 持久信令会话模式：keepAlive() 进入（替代硬 cleanup），会话期保留连接收发信令。 */
    private var sessionMode = false

    /**
     * 发送串行队列（同连接单写互斥）：握手写入与信令写入共用，同一时刻至多一条在途，
     * 其余按序排队，onCharacteristicWrite 回调后出队下一条。解决背靠背 writeCharacteristic
     * 被 Android 单请求栈拒绝（返回 false）导致信令丢失的问题。
     */
    private val writeQueue = ArrayDeque<QueuedWrite>()

    /** 是否有写入在途（onCharacteristicWrite 尚未回调）：互斥核心，true 时新写入只入队。 */
    private var writeInFlight = false

    /** 在途写入是否为握手写入（决定失败语义与日志标签；仅 writeInFlight=true 时有意义）。 */
    private var inFlightHandshake = false

    /** 当前 ATT MTU（requestMtu 协商结果；未协商/协商失败兜底默认 23）。 */
    private var mtu: Int = DEFAULT_ATT_MTU

    private val timeoutRunnable = Runnable {
        fail("握手超时(${Constants.HANDSHAKE_TIMEOUT_MS}ms)")
    }

    /** 写入兜底：握手写发起后 3s 内 onCharacteristicWrite 未回调（蓝牙栈写入挂起）则判失败。 */
    private val writeTimeoutRunnable = Runnable {
        if (sessionMode) {
            // 会话期写入挂起（栈无回调）：视为链路异常，清理后走断线自动重连路径
            val addr = targetAddress
            Log.w(TAG, "信令写入 3s 无回调(status 未返回)，按链路异常处理")
            DiagLogger.log(TAG, "信令写入 3s 无回调(status 未返回)，按链路异常处理，交由自动重连")
            cleanup()
            if (addr != null) callbacks.onSessionDisconnected(addr)
        } else {
            fail("握手写入 3s 无回调(status 未返回)")
        }
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
        sessionMode = false
        mtu = DEFAULT_ATT_MTU
        targetAddress = device.address
        // 新连接重置发送队列（防御：上一会话遗留的未发送条目一律丢弃）
        writeQueue.clear()
        writeInFlight = false
        inFlightHandshake = false
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

    /** 会话模式是否激活（SessionManager 查询用）。 */
    fun isSessionActive(): Boolean = sessionMode

    /**
     * 握手成功后由 SessionManager.attach 调用：把底层连接从"一次性握手"切换为"持久会话"，
     * 保留 GATT 连接、NOTIFY 订阅与 WRITE 特征（替代原硬 cleanup）。
     * 返回是否成功进入会话模式（需 gatt 存活且握手已完成）。
     */
    fun keepAlive(): Boolean {
        if (gatt == null || !handshakeDone || cleaned) return false
        if (sessionMode) return true // 幂等
        sessionMode = true
        mainHandler.removeCallbacks(timeoutRunnable)
        mainHandler.removeCallbacks(writeTimeoutRunnable)
        DiagLogger.log(TAG, "会话模式：保留连接 ${targetAddress}（MTU=$mtu，NOTIFY 订阅持续有效）")
        return true
    }

    /**
     * 会话期信令发送：复用 WRITE 通道（对端 Server 的 WRITE 特征），经串行队列发送——
     * 有在途写入时入队等待（返回 true，排队不算失败），onCharacteristicWrite 回调后
     * 自动出队逐条发送；空闲时立即写入。
     * 未进入会话模式 / 无特征 / 超单包 MTU / 写入被栈立即拒绝 → false。
     */
    fun sendSignal(bytes: ByteArray): Boolean {
        if (!sessionMode) return false
        if (gatt == null) return false
        val w = pendingWriteChar ?: return false
        val maxPayload = mtu - 3
        if (bytes.size > maxPayload) {
            Log.w(TAG, "信令 ${bytes.size}B 超出会话单包上限 ${maxPayload}B（MTU=$mtu）")
            DiagLogger.log(TAG, "信令 ${bytes.size}B 超出会话单包上限 ${maxPayload}B（MTU=$mtu），发送失败")
            return false
        }
        DiagLogger.log(TAG, "信令发送: ${targetAddress} ${bytes.size}B（MTU=$mtu，单包上限=${maxPayload}B）")
        // 串行队列：有在途写入则入队（排队等待不算失败）；空闲则立即写入
        return enqueueWrite(w, bytes, handshake = false)
    }

    /**
     * 会话期断线重连（SessionManager 自动重连调用）：静默清理旧连接后按握手流程重新连接；
     * 重连握手完成会再次触发 onHandshakeCompleted → attach → keepAlive 恢复会话。
     */
    fun reconnectSession(device: BluetoothDevice) {
        cleanup()
        connect(device)
    }

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
                        if (sessionMode) {
                            // 会话期断线：静默清理死连接并上报，由 SessionManager 决定自动重连（一次）
                            val addr = targetAddress
                            Log.w(TAG, "会话连接断开 $addr")
                            DiagLogger.log(TAG, "会话连接断开 $addr：已清理，等待自动重连决策")
                            cleanup()
                            if (addr != null) callbacks.onSessionDisconnected(addr)
                        } else if (!handshakeDone && !cleaned) fail("连接断开")
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
                pendingWriteChar = write
                // 串行化 ATT 操作：这里只做订阅（写 CCC），主握手写入等 onDescriptorWrite
                // 确认 CCC 写完后才发起，避免背靠背发起 CCC 与 WRITE 被蓝牙栈
                // 单请求串行模型静默丢弃（onCharacteristicWrite 永不回调）
                gatt.setCharacteristicNotification(notify, true)
                val ccc = notify.getDescriptor(Constants.CLIENT_CHARACTERISTIC_CONFIG_UUID)
                if (ccc != null) {
                    writeDescriptorCompat(gatt, ccc, byteArrayOf(0x01, 0x00))
                } else {
                    // 无 CCC 描述符：没有可串行等待的写，直接发起主握手写入
                    DiagLogger.log(TAG, "NOTIFY 特征无 CCC 描述符，跳过订阅写入，直接发起主握手写入")
                    sendHandshake(write)
                }
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            mainHandler.post {
                // status 失败仅记录并继续：Server 端有订阅挂起补发兜底，主握手写入仍按序发起
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.w(TAG, "CCC 写入失败 status=$status")
                    DiagLogger.log(TAG, "CCC 写入失败 status=$status（仅记录，继续发起主写入）")
                }
                // 串行化：CCC 写回调确认后（无论成功与否）再发起主握手写入
                DiagLogger.log(TAG, "CCC 写完(status=$status)，发起主写入")
                val write = pendingWriteChar
                if (write != null) {
                    sendHandshake(write)
                } else {
                    Log.w(TAG, "onDescriptorWrite 无暂存写入特征，跳过握手写入")
                    DiagLogger.log(TAG, "onDescriptorWrite 无暂存写入特征，跳过握手写入")
                }
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
                // 串行队列驱动核心：当前写入完成（成功/失败均算完成）→ 清在途标志 → 出队下一条
                val wasHandshake = inFlightHandshake
                writeInFlight = false
                inFlightHandshake = false
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    if (wasHandshake) {
                        // 握手写入结果确认：失败立即终止，避免干等 10s 超时；
                        // fail 内部有 cleaned 防重入，握手成功路径 cleanup 后不会再走到
                        fail("握手消息发送失败(status=$status)")
                        return@post
                    }
                    // 会话期单条信令写失败：仅记录（不断链），链路是否异常由后续断线事件判定；
                    // 队列中后续信令仍按序继续发送
                    Log.w(TAG, "信令写入失败(status=$status)")
                    DiagLogger.log(TAG, "信令写入失败(status=$status)，队列剩余=${writeQueue.size}，继续发送下一条")
                } else {
                    DiagLogger.log(
                        TAG,
                        if (wasHandshake) "onCharacteristicWrite 握手写入确认成功 status=$status"
                        else "信令写入确认成功 status=$status，队列剩余=${writeQueue.size}"
                    )
                }
                pumpWriteQueue()
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
            if (cleaned) return@post
            if (notifyChar?.uuid != Constants.NOTIFY_CHARACTERISTIC_UUID) return@post

            // 信令分流：收到的字节先试 SignalProtocol.decode；解析成功且 type 有效才视为信令
            // （握手 JSON 无 type 键，decode 会得到 type=""，自动落回原握手逻辑）
            val signal = SignalProtocol.decode(value)
            if (signal != null && signal.type.isNotBlank()) {
                val addr = targetAddress
                DiagLogger.log(TAG, "Client 收到信令来自 $addr: type=${signal.type} payload=${signal.payload?.length() ?: "-"}")
                if (addr != null) callbacks.onRemoteSignal(addr, signal)
                return@post
            }

            // 会话期非信令字节：忽略（不再尝试握手解析）
            if (handshakeDone) return@post

            // 原握手逻辑
            val msg = HandshakeProtocol.decode(value)
            if (msg != null) {
                handshakeDone = true
                val addr = targetAddress
                Log.d(TAG, "收到 ${addr} 握手: ${HandshakeProtocol.toJson(msg)}")
                DiagLogger.log(TAG, "收到对方 ${addr} 握手: ${HandshakeProtocol.toJson(msg)}")
                if (addr != null) callbacks.onHandshakeCompleted(addr, msg)
                // 回调链内 SessionManager.attach → keepAlive() 已同步执行（主线程）；
                // 若未进入会话模式（未接线 / attach 失败）则恢复原行为：立即 cleanup
                if (!sessionMode) cleanup()
            } else {
                Log.w(TAG, "对方握手通知解析失败")
                DiagLogger.log(TAG, "对方握手通知解析失败（${value.size}B）")
            }
        }
    }

    /**
     * 串行化后的主握手写入：仅在 CCC 写完成后调用（onDescriptorWrite），
     * 避免与描述符写入背靠背被蓝牙栈单请求模型静默丢弃。
     * 与信令写入共用同一串行队列（[enqueueWrite]），保证握手/信令写入互斥。
     */
    private fun sendHandshake(write: BluetoothGattCharacteristic) {
        // 可能在 cleanup 之后才被回调触发（CCC 写回调延迟到达），入口先拦截
        if (cleaned) return
        val bytes = HandshakeProtocol.encode(HandshakeProtocol.buildLocal(context))
        // 写入前长度校验（防御）：ATT 层 3 字节头开销，单包载荷上限 = mtu - 3
        val maxPayload = mtu - 3
        if (bytes.size > maxPayload) {
            fail("握手消息 ${bytes.size}B 超出当前 MTU ${maxPayload}B")
            return
        }
        DiagLogger.log(TAG, "握手写入入队/发起: ${bytes.size}B（MTU=$mtu，单包上限=${maxPayload}B，与信令共用串行队列）")
        enqueueWrite(write, bytes, handshake = true)
    }

    /**
     * 串行写入统一入口（信令与握手共用，同连接单写互斥）：
     * - 已有写入在途（[writeInFlight]）→ 入队等待，返回 true（排队不算失败，
     *   onCharacteristicWrite 回调后自动出队发送）；
     * - 空闲 → 置在途标志并立即写入；
     * - 实际入栈被拒（writeCharacteristic 返回 false）/ 异常 → false（已清在途并尝试出队下一条）。
     */
    private fun enqueueWrite(
        ch: BluetoothGattCharacteristic,
        bytes: ByteArray,
        handshake: Boolean,
    ): Boolean {
        if (writeInFlight) {
            writeQueue.addLast(QueuedWrite(ch, bytes, handshake))
            DiagLogger.log(TAG, "写入入队: 队列长度=${writeQueue.size}（前一条在途，待完成回调后串行发送）")
            return true
        }
        return startWrite(QueuedWrite(ch, bytes, handshake))
    }

    /** 立即发起一条写入（调用方保证当前无在途写入）。返回是否成功入栈。 */
    private fun startWrite(q: QueuedWrite): Boolean {
        val g = gatt
        if (g == null || cleaned) {
            DiagLogger.log(TAG, "写入放弃: 连接已清理（${q.bytes.size}B ${if (q.handshake) "握手" else "信令"}）")
            return false
        }
        writeInFlight = true
        inFlightHandshake = q.handshake
        DiagLogger.log(TAG, "写入发起: ${if (q.handshake) "握手" else "信令"} ${q.bytes.size}B（队列剩余=${writeQueue.size}）")
        val ok = writeCharacteristicCompat(g, q.characteristic, q.bytes, q.handshake)
        if (!ok) {
            // 入栈被拒/异常：当前写入未在途，清标志后继续出队下一条（队列不因单条失败而阻塞）
            writeInFlight = false
            inFlightHandshake = false
            pumpWriteQueue()
            return false
        }
        return true
    }

    /**
     * 出队下一条并发送：由 onCharacteristicWrite 完成回调驱动（成功/失败均触发）；
     * 入栈被拒时（[startWrite] 返回 false）也会继续出队，直到队列耗尽。
     */
    private fun pumpWriteQueue() {
        if (writeInFlight) return
        val next = writeQueue.pollFirst() ?: return
        DiagLogger.log(TAG, "写入出队: 队列剩余=${writeQueue.size} ${if (next.handshake) "握手" else "信令"} ${next.bytes.size}B")
        startWrite(next)
    }

    private fun writeCharacteristicCompat(
        gatt: BluetoothGatt,
        ch: BluetoothGattCharacteristic,
        bytes: ByteArray,
        handshake: Boolean,
    ): Boolean {
        try {
            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(ch, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            } else {
                @Suppress("DEPRECATION")
                ch.value = bytes
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(ch)
            }
            if (ok == false) {
                // 写入被栈拒绝（未入队）：onCharacteristicWrite 永不会回调，立即失败，不再等 3s 兜底
                mainHandler.removeCallbacks(writeTimeoutRunnable)
                if (handshake) {
                    Log.w(TAG, "writeCharacteristic 返回 false，写入被栈拒绝（未入队）")
                    DiagLogger.log(TAG, "writeCharacteristic 返回 false，写入被栈拒绝（未入队），立即判失败")
                    mainHandler.post { fail("写入被栈拒绝(writeCharacteristic 返回 false)") }
                } else {
                    Log.w(TAG, "信令 writeCharacteristic 返回 false，写入被栈拒绝（未入队）")
                    DiagLogger.log(TAG, "信令 writeCharacteristic 返回 false，写入被栈拒绝（未入队），发送失败")
                }
                return false
            }
        } catch (e: Exception) {
            mainHandler.removeCallbacks(writeTimeoutRunnable)
            Log.w(TAG, "writeCharacteristic 异常: $e")
            DiagLogger.log(TAG, "writeCharacteristic 异常: $e")
            if (!handshake) return false
            fail("写入异常: ${e.message}")
            return false
        }
        // 写入兜底：仅当写入成功入栈后才启动 3s 超时；回调/cleanup 撤除
        mainHandler.postDelayed(writeTimeoutRunnable, WRITE_TIMEOUT_MS)
        if (handshake) {
            DiagLogger.log(TAG, "握手写入已发起，3s 写入兜底超时已启动")
        }
        return true
    }

    private fun writeDescriptorCompat(gatt: BluetoothGatt, desc: BluetoothGattDescriptor, bytes: ByteArray) {
        try {
            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(desc, bytes)
            } else {
                @Suppress("DEPRECATION")
                desc.value = bytes
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(desc)
            }
            if (ok == false) {
                // CCC 写入失败不阻断主写入，仅记录（订阅可能未生效，回复会走挂起补发路径）
                Log.w(TAG, "writeDescriptor 返回 false（CCC 写入被栈拒绝，仅记录）")
                DiagLogger.log(TAG, "writeDescriptor 返回 false（CCC 写入被栈拒绝，仅记录，不阻断主写入）")
            }
        } catch (e: Exception) {
            Log.w(TAG, "writeDescriptor 异常: $e")
            DiagLogger.log(TAG, "writeDescriptor 异常: $e")
        }
    }

    private fun fail(reason: String) {
        if (cleaned) return
        val addr = targetAddress
        if (sessionMode) {
            // 会话期意外失败：不回退到握手失败回调，按链路异常走自动重连路径
            Log.w(TAG, "会话异常 $addr: $reason")
            DiagLogger.log(TAG, "会话异常 $addr: $reason")
            cleanup()
            if (addr != null) callbacks.onSessionDisconnected(addr)
            return
        }
        Log.w(TAG, "握手失败 ${addr}: $reason")
        DiagLogger.log(TAG, "握手失败 ${addr}: $reason")
        cleanup()
        if (addr != null) callbacks.onHandshakeFailed(addr, reason)
    }

    private fun cleanup() {
        mainHandler.removeCallbacks(timeoutRunnable)
        mainHandler.removeCallbacks(writeTimeoutRunnable)
        // 清理发送队列：断链/超时/取消时丢弃全部未发送条目（含在途写入之后排队的）
        if (writeQueue.isNotEmpty()) {
            DiagLogger.log(TAG, "cleanup 清空发送队列（丢弃 ${writeQueue.size} 条未发送）")
            writeQueue.clear()
        }
        writeInFlight = false
        inFlightHandshake = false
        val g = gatt
        gatt = null
        cleaned = true
        sessionMode = false
        notifyChar = null
        pendingWriteChar = null
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

    /** 发送队列条目：目标 WRITE 特征 + 字节 + 是否握手写入（握手与信令失败语义不同）。 */
    private class QueuedWrite(
        val characteristic: BluetoothGattCharacteristic,
        val bytes: ByteArray,
        val handshake: Boolean,
    )

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
