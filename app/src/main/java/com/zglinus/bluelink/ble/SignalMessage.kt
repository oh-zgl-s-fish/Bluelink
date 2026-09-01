package com.zglinus.bluelink.ble

import android.util.Log
import com.zglinus.bluelink.diag.DiagLogger
import org.json.JSONObject

/**
 * 组网信令消息（A1 小包）：org.json 内置实现（禁第三方 JSON 库），编解码风格同 HandshakeProtocol。
 *
 * 单条消息（UTF-8，静态上限 500B，与 [Constants.MAX_HANDSHAKE_BYTES] 对齐的安全上限）字段定稿：
 * {
 *   "type": "offer" | "joined" | "abort" | "ack",
 *   "payload": { ... } | null    // ack 无 payload
 * }
 *
 * 各类型 payload 字段：
 * - offer:  { "ssid": "<Wi-Fi SSID>", "pwd": "<密码>", "ip": "<热点 IPv4>", "hotspotType": "<类型>" }
 * - joined: { "ip": "<对端 IPv4>" }
 * - abort:  { "reason": "<原因>" }
 * - ack:    无 payload（encode 省略 payload 键；decode 对缺失/null 均还原为 null）
 *
 * 对称性：encode 产出的 JSON 均可被 decode 还原（type 非空、payload 缺失或 NULL 均还原为 null）。
 */
data class SignalMessage(
    val type: String,
    val payload: JSONObject? = null,
)

object SignalProtocol {

    const val TYPE_OFFER = "offer"
    const val TYPE_JOINED = "joined"
    const val TYPE_ABORT = "abort"
    const val TYPE_ACK = "ack"

    private const val TAG = "SignalProtocol"
    private const val F_TYPE = "type"
    private const val F_PAYLOAD = "payload"

    /** 信令单包静态上限（500B）。超限仅打警告不截断；真实单包长度校验由调用方按协商 MTU 动态执行。 */
    private const val MAX_SIGNAL_BYTES: Int = 500

    /** 序列化为单行 JSON（payload 为 null 时省略键，保证 decode 对称还原）。 */
    fun toJson(m: SignalMessage): String {
        val o = JSONObject()
        o.put(F_TYPE, m.type)
        if (m.payload != null) {
            o.put(F_PAYLOAD, m.payload)
        }
        return o.toString()
    }

    /**
     * 编码为 UTF-8 字节。超过 [MAX_SIGNAL_BYTES] 时仅打警告（Log + DiagLogger），
     * **不截断**、按原长度返回完整 bytes；单包长度校验由调用方按协商 MTU 动态执行。
     */
    fun encode(m: SignalMessage): ByteArray {
        val bytes = toJson(m).toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_SIGNAL_BYTES) {
            val msg = "信令消息 ${bytes.size}B 超过静态上限 ${MAX_SIGNAL_BYTES}B（将按原长度发送，由调用方按协商 MTU 校验，不再截断）"
            Log.w(TAG, msg)
            DiagLogger.log(TAG, msg)
        }
        return bytes
    }

    fun decode(bytes: ByteArray): SignalMessage? = parse(String(bytes, Charsets.UTF_8))

    fun parse(json: String): SignalMessage? = try {
        val o = JSONObject(json)
        SignalMessage(
            type = o.optString(F_TYPE, ""),
            // optJSONObject：payload 缺失或为 JSONObject.NULL 时返回 null，与 encode 省略键对称
            payload = o.optJSONObject(F_PAYLOAD),
        )
    } catch (e: Exception) {
        Log.w(TAG, "信令 JSON 解析失败: $e")
        DiagLogger.log(TAG, "信令 JSON 解析失败: $e，原始串前 80 字符: ${json.take(80)}")
        null
    }
}
