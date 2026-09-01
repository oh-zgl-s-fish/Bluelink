package com.zglinus.bluelink.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import com.zglinus.bluelink.diag.DiagLogger

/**
 * GATT 持久信令会话（A2）：握手成功后保留底层 GATT 连接，复用 WRITE/NOTIFY 通道
 * 传输 [SignalMessage] 组网信令，替代一期"握手完成即 cleanup"的一次性链路。
 *
 * 生命周期与接线：
 * - [attach]：握手成功回调（BluelinkEngine.applyRemoteHandshake）后调用；本机为 Client
 *   角色时同步调用 GattClient.keepAlive() 把连接从"一次性握手"切换为"会话模式"
 *   （保留连接、NOTIFY 订阅与 WRITE 特征，替代硬 cleanup，必须在握手完成回调链内同步执行）；
 * - [sendSignal]：复用 WRITE 通道——优先本机 Client 腿（写入对端 Server 的 WRITE 特征），
 *   clientLeg 有连接即走 GattClient 串行队列（同连接单写互斥，背靠背写入入队等待不丢信令）；
 *   仅当 clientLeg 完全不可用（未入会话/无连接/无特征/超单包 MTU）时才回落本机 Server 的
 *   NOTIFY 通道（对端需已订阅）；
 * - 远程信令分流：GattClient.onCharacteristicChanged / GattServer.onCharacteristicWriteRequest
 *   收到的字节先试 SignalProtocol.decode，成功 → [Callbacks.onRemoteSignal]（经 engine 转发）；
 *   失败 → 维持原握手逻辑（向后兼容，未 attach 时 GattClient/GattServer 行为与一期一致）；
 * - [detach]：恢复原 cleanup（断开并释放保留的连接）；
 * - 断线且 attached：Client 腿断线自动重连一次（复用握手流程，重连握手完成后 engine
 *   再次 attach 恢复会话）；Server 腿断开仅记录，若会话已无任何收发通道则自动结束会话。
 *
 * 线程模型：所有公开方法由主线程调用（engine 的 BLE 回调均已切回主线程；
 * sendSignal 亦须主线程调用，内部直接访问 GattClient 的会话状态）。
 */
class SessionManager(
    private val context: Context,
    private val gattClient: GattClient,
    private val gattServer: GattServer,
) {
    interface Callbacks {
        /** 会话期收到远端信令（Client NOTIFY 或 Server WRITE 通道分流而来）。 */
        fun onRemoteSignal(peerAddress: String, msg: SignalMessage)
    }

    private var callbacks: Callbacks? = null

    /** 当前会话对端地址；null 表示未附着。 */
    private var peerAddress: String? = null

    /** 是否处于持久会话中（attach 后 true，detach/断线结束 false）。 */
    private var attached = false

    /** 断线自动重连已尝试标记：attach 时重置，避免重连失败后无限循环。 */
    private var autoReconnectDone = false

    /** 自动重连进行中（用于握手失败回调时收敛会话状态）。 */
    private var reconnecting = false

    val isAttached: Boolean get() = attached

    fun currentPeer(): String? = peerAddress

    /** 注册信令上抛回调（engine 接线用）。 */
    fun setCallbacks(cb: Callbacks?) {
        callbacks = cb
    }

    /**
     * 握手成功后建立持久信令会话。若已有其他对端的会话，先 detach 旧会话。
     * Client 侧同步调用 gattClient.keepAlive() 保留连接（必须在握手完成回调链内同步执行，
     * 否则 GattClient 会按原逻辑 cleanup）。
     */
    fun attach(peerAddress: String) {
        val prev = this.peerAddress
        if (prev != null && prev != peerAddress) {
            DiagLogger.log(TAG, "attach 新对端 $peerAddress：先 detach 旧会话 $prev")
            doDetach(prev, logDetach = true)
        }
        this.peerAddress = peerAddress
        attached = true
        autoReconnectDone = false
        reconnecting = false
        val clientKept = gattClient.keepAlive()
        val serverLeg = gattServer.isDeviceConnected(peerAddress)
        DiagLogger.log(
            TAG,
            "持久信令会话已建立 peer=$peerAddress clientLeg=${if (clientKept) "保留(WRITE/NOTIFY 通道可用)" else "无"} serverLeg=${if (serverLeg) "保留(WRITE 收/NOTIFY 发)" else "无"}",
        )
        if (!clientKept && !serverLeg) {
            Log.w(TAG, "attach 时 Client/Server 均无 $peerAddress 连接，会话无收发通道")
            DiagLogger.log(TAG, "attach 时 Client/Server 均无 $peerAddress 连接，会话无收发通道")
        }
    }

    /**
     * 结束会话并恢复原 cleanup：断开并释放 GattClient 保留的连接（若存在）。
     * 幂等：未附着时仅记录。
     */
    fun detach() {
        val peer = peerAddress
        if (!attached && peer == null) {
            DiagLogger.log(TAG, "detach：当前无活动会话，忽略")
            return
        }
        doDetach(peer, logDetach = true)
    }

    /**
     * 发送组网信令。clientLeg（本机为 Client，写入对端 Server 的 WRITE 特征）优先：
     * 有连接即走 GattClient 串行队列（[GattClient.sendSignal]）——背靠背写入入队等待、
     * onCharacteristicWrite 回调后逐条发送，不再重复走 serverLeg，避免同连接双写踩踏；
     * 仅当 clientLeg 完全不可用（未入会话 / 无连接 / 无特征 / 超单包 MTU / 写入被栈立即拒绝）
     * 时才回落本机 Server 的 NOTIFY 通道兜底（对端需已订阅）。
     * 未 attach / 无可用通道 → false。
     */
    fun sendSignal(msg: SignalMessage): Boolean {
        val peer = peerAddress ?: return false
        if (!attached) return false
        val bytes = SignalProtocol.encode(msg)
        DiagLogger.log(TAG, "sendSignal peer=$peer type=${msg.type} ${bytes.size}B")
        // clientLeg 有连接即走串行队列（入队等待不算失败）；不可用才回落 serverLeg 兜底
        if (gattClient.sendSignal(bytes)) return true
        DiagLogger.log(TAG, "sendSignal: clientLeg 不可用（未入会话/无连接/无特征/超单包/被拒），回落 serverLeg 兜底 peer=$peer type=${msg.type}")
        return gattServer.sendSignal(peer, bytes)
    }

    /** GattClient/GattServer 信令分流后的远端信令入口（engine 转发，主线程）。 */
    fun onRemoteSignal(peerAddress: String, msg: SignalMessage) {
        DiagLogger.log(TAG, "远端信令到达 peer=$peerAddress type=${msg.type}")
        if (!attached || peerAddress != this.peerAddress) {
            DiagLogger.log(
                TAG,
                "信令来自非会话对端 $peerAddress（当前会话 peer=${this.peerAddress} attached=$attached），仅记录不转发",
            )
            return
        }
        callbacks?.onRemoteSignal(peerAddress, msg)
    }

    /**
     * Client 腿断线入口（GattClient.onSessionDisconnected 转发，主线程）：
     * 断线且 attached 时自动重连一次（复用握手流程，完成后 engine 会再次 attach 恢复会话）。
     */
    fun onSessionDisconnected(peerAddress: String) {
        if (!attached || peerAddress != this.peerAddress) {
            DiagLogger.log(TAG, "连接断开 $peerAddress：非当前会话（attached=$attached peer=${this.peerAddress}），忽略")
            return
        }
        if (autoReconnectDone) {
            DiagLogger.log(TAG, "会话断线 $peerAddress：自动重连已尝试过，会话结束")
            doDetach(peerAddress, logDetach = true)
            return
        }
        autoReconnectDone = true
        reconnecting = true
        DiagLogger.log(TAG, "会话断线 $peerAddress：自动重连一次（复用握手流程）")
        val device = remoteDevice(peerAddress)
        if (device == null) {
            DiagLogger.log(TAG, "会话断线 $peerAddress：无法解析设备对象，重连放弃，会话结束")
            doDetach(peerAddress, logDetach = true)
            return
        }
        gattClient.reconnectSession(device)
    }

    /** Server 腿断开入口（GattServer.onDeviceDisconnected 转发，主线程）。 */
    fun onServerLegLost(peerAddress: String) {
        if (!attached || peerAddress != this.peerAddress) return
        if (gattClient.isSessionActive()) {
            DiagLogger.log(TAG, "会话 Server 腿断开 $peerAddress：Client 腿仍可用，会话继续")
        } else {
            DiagLogger.log(TAG, "会话 Server 腿断开 $peerAddress：无可用收发通道，会话结束")
            doDetach(peerAddress, logDetach = true)
        }
    }

    /** 握手失败入口（engine 转发）：仅自动重连流程中的失败收敛会话状态（结束会话）。 */
    fun onHandshakeFailed(peerAddress: String) {
        if (reconnecting && attached && peerAddress == this.peerAddress) {
            DiagLogger.log(TAG, "自动重连握手失败 $peerAddress：会话结束，恢复原 cleanup")
            doDetach(peerAddress, logDetach = true)
        }
    }

    // ---------- 内部 ----------

    private fun doDetach(peer: String?, logDetach: Boolean) {
        attached = false
        autoReconnectDone = false
        reconnecting = false
        peerAddress = null
        // 恢复原 cleanup：断开并释放 GattClient 保留的连接（若存在）
        gattClient.release()
        if (logDetach) {
            DiagLogger.log(TAG, "会话已 detach peer=$peer：恢复原 cleanup（连接已释放）")
        }
    }

    private fun remoteDevice(address: String): BluetoothDevice? = try {
        val bm = context.getSystemService(BluetoothManager::class.java)
        bm?.adapter?.getRemoteDevice(address)
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, "非法 MAC: $address")
        DiagLogger.log(TAG, "重连失败：非法 MAC $address")
        null
    }

    companion object {
        private const val TAG = "SessionManager"
    }
}
