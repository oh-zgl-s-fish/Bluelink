package com.zglinus.bluelink.ble

import android.os.Handler
import com.zglinus.bluelink.diag.DiagLogger
import org.json.JSONObject

/**
 * 信令自测（Bluelink 验证包）：GATT 持久信令会话的「长期双向收发自测」。
 *
 * 用途：真机验证信令通道在连接保持数分钟（默认 120s）下是否可靠双工。
 * 机制：attach 后由 engine 自动 [start]；定时（默认每 5s）经 [SessionManager.sendSignal]
 * 发一条 ping（payload 约定 {seq: Int, t: Long}，t 为发送时刻毫秒时间戳），对端收到 ping
 * 原样回显 seq/t 组成 pong；本端 [onRemoteSignal] 收到 pong 后按 seq 匹配记 RTT=now-t。
 * 对端同样在跑本测试（双方 attach 均自动 start），故两端同时互发 ping/互回 pong，覆盖双工。
 *
 * 统计口径（sent/ok/fail）：
 * - sent：发出的 ping 总数（含发送失败/超时的尝试）；
 * - ok：收到匹配的 pong（seq 在待确认集合中）并记录 RTT；
 * - fail：ping 发送被拒/异常、pong 与待确认 seq 不匹配（重复/未知）、
 *   以及 ping 超时（超过 2×interval 未收到 pong，由定时器扫描归账）。
 *
 * 线程模型：与 SessionManager 一致，全部由主线程驱动（ticker 经 [mainHandler] 排程，
 * onRemoteSignal 由 engine 主线程转发）；[DiagLogger] 线程安全可双写。
 */
class SignalTest(
    private val session: SessionManager,
    private val mainHandler: Handler,
    private val onStatus: ((running: Boolean, status: String) -> Unit)? = null,
) {

    private var running = false
    private var totalMs = 120_000L
    private var intervalMs = 5_000L
    private var stopAt = 0L
    private var sent = 0
    private var ok = 0
    private var fail = 0
    private var seq = 0
    private var lastRttMs = -1L

    /** 待确认 ping：seq → 发送时刻 t（毫秒）。收到匹配 pong 移除；超时由扫描归账为 fail。 */
    private val pending = LinkedHashMap<Int, Long>()

    val isRunning: Boolean get() = running

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            if (System.currentTimeMillis() >= stopAt) {
                finish("达 ${totalMs}ms 时限自动停止")
                return
            }
            sweepPending()
            sendPing()
            mainHandler.postDelayed(this, intervalMs)
        }
    }

    /**
     * 开始信令自测：立即发首条 ping，随后每 [intervalMs] 一条，总时长 [totalMs] 后自动停止。
     * 幂等：若已在运行则先停止并重置统计（重新开始）。
     */
    fun start(totalMs: Long = 120_000, intervalMs: Long = 5_000) {
        stop()
        if (totalMs <= 0 || intervalMs <= 0) {
            DiagLogger.log(TAG, "start 参数非法 totalMs=$totalMs intervalMs=$intervalMs，忽略")
            return
        }
        this.totalMs = totalMs
        this.intervalMs = intervalMs
        stopAt = System.currentTimeMillis() + totalMs
        sent = 0
        ok = 0
        fail = 0
        seq = 0
        lastRttMs = -1L
        pending.clear()
        running = true
        DiagLogger.log(TAG, "信令自测开始：totalMs=${totalMs}ms interval=${intervalMs}ms")
        notifyStatus()
        sendPing() // 立即发首条（快速反馈），随后按 interval 排程
        mainHandler.postDelayed(ticker, intervalMs)
    }

    /** 停止信令自测（停定时器防泄漏；幂等）。 */
    fun stop() {
        if (!running) return
        finish("手动停止")
    }

    /**
     * 引擎转发入口：type=ping → 回 pong（同 seq/t）；type=pong → 交给收发统计；
     * 其它类型（offer/joined/abort/ack）不属信令自测，忽略（引擎已分流，正常不会到达）。
     */
    fun onRemoteSignal(msg: SignalMessage) {
        when (msg.type) {
            SignalProtocol.TYPE_PING -> replyPong(msg)
            SignalProtocol.TYPE_PONG -> onPong(msg.payload)
            else -> Unit
        }
    }

    /** 状态行文本：`信令测试: 发送X/成功Y/失败Z RTT xxms`（未测出 RTT 时显示 -）。 */
    fun status(): String = "信令测试: 发送$sent/成功$ok/失败$fail RTT ${rttText()}"

    // ---------- 内部 ----------

    private fun sendPing() {
        if (!running) return
        if (!session.isAttached) {
            DiagLogger.log(TAG, "信令自测：会话已结束（未 attach），自动停止")
            finish("会话未附着")
            return
        }
        val now = System.currentTimeMillis()
        seq++
        sent++
        val payload = JSONObject()
        payload.put(F_SEQ, seq)
        payload.put(F_T, now)
        val msg = SignalMessage(SignalProtocol.TYPE_PING, payload)
        val okSent = try {
            session.sendSignal(msg)
        } catch (e: Exception) {
            DiagLogger.log(TAG, "发送 ping seq=$seq 异常: $e")
            null
        }
        if (okSent == true) {
            pending[seq] = now
            DiagLogger.log(TAG, "发送 ping seq=$seq ok（t=$now）")
        } else {
            fail++
            DiagLogger.log(TAG, "发送 ping seq=$seq 失败（${if (okSent == null) "异常" else "无通道/写入被拒"}），记为失败")
        }
        notifyStatus()
    }

    /** pong 统计：按 seq 匹配待确认集合，命中记 RTT=now-t、ok++；不匹配（未知/重复 seq）记 fail。 */
    fun onPong(payload: JSONObject?) {
        if (!running) {
            DiagLogger.log(TAG, "收到 pong 但自测未在运行，忽略")
            return
        }
        val s = payload?.optInt(F_SEQ, -1) ?: -1
        val t = payload?.optLong(F_T, -1L) ?: -1L
        val sentAt = pending.remove(s)
        if (sentAt != null && t > 0) {
            val rtt = System.currentTimeMillis() - t
            lastRttMs = rtt
            ok++
            DiagLogger.log(TAG, "收到 pong seq=$s RTT=${rtt}ms")
        } else {
            fail++
            DiagLogger.log(TAG, "收到 pong 与待确认 ping 不匹配 seq=$s t=$t，记为失败")
        }
        notifyStatus()
    }

    /** ping → 回 pong：原样回显对端 seq/t（不进入本端收发统计）。 */
    private fun replyPong(ping: SignalMessage) {
        val p = ping.payload
        val replyPayload = JSONObject()
            .put(F_SEQ, p?.optInt(F_SEQ, -1) ?: -1)
            .put(F_T, p?.optLong(F_T, -1L) ?: -1L)
        val reply = SignalMessage(SignalProtocol.TYPE_PONG, replyPayload)
        val okSent = try {
            session.sendSignal(reply)
        } catch (e: Exception) {
            DiagLogger.log(TAG, "回 pong 异常: $e")
            false
        }
        DiagLogger.log(
            TAG,
            "收到 ping（seq=${p?.optInt(F_SEQ, -1)} t=${p?.optLong(F_T, -1L)}）→ 回 pong ${if (okSent) "ok" else "失败"}",
        )
    }

    /** 超时归账：待确认 ping 超过 2×interval（下限 10s）未收到 pong → 记为失败（如实记录断线/丢失）。 */
    private fun sweepPending() {
        val now = System.currentTimeMillis()
        val timeout = maxOf(intervalMs * 2, 10_000L)
        var swept = 0
        val it = pending.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (now - e.value > timeout) {
                it.remove()
                fail++
                swept++
            }
        }
        if (swept > 0) {
            DiagLogger.log(TAG, "有 $swept 条 ping 超时未收到 pong，记为失败")
            notifyStatus()
        }
    }

    private fun finish(reason: String) {
        running = false
        mainHandler.removeCallbacks(ticker)
        DiagLogger.log(TAG, "信令自测停止（$reason）：${status()}")
        notifyStatus()
    }

    private fun rttText(): String = if (lastRttMs >= 0) "${lastRttMs}ms" else "-"

    private fun notifyStatus() {
        onStatus?.invoke(running, status())
    }

    companion object {
        private const val TAG = "SignalTest"
        private const val F_SEQ = "seq"
        private const val F_T = "t"
    }
}
