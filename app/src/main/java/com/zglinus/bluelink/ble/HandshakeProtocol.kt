package com.zglinus.bluelink.ble

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import com.zglinus.bluelink.diag.DiagLogger
import com.zglinus.bluelink.net.NetworkInfoProvider
import com.zglinus.bluelink.net.NetworkSummary
import com.zglinus.bluelink.security.PinStore
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 一期握手协议：org.json 内置实现（禁第三方 JSON 库）。
 *
 * 单条消息（UTF-8，静态兜底上限 [Constants.MAX_HANDSHAKE_BYTES]）字段定稿：
 * {
 *   "v": 1,
 *   "alias": "<本机别名，默认 Build.MODEL>",
 *   "model": "<Build.MODEL>",
 *   "root": <Boolean 本机 Magisk 探测结果: 可执行 su -c id 校验 uid=0；探测失败/未授权=false>,
 *   "battery": <Int? 本机电量百分比 0-100；BatteryManager 不可用/异常时为 null>,
 *   "fp": <String? 本端指纹，v0.5.9 UI1b-C 可选字段——本机安装级随机标识（PinStore.localFingerprint，16 hex 大写）>,
 *         对端解析优先取 fp 作「对端指纹」；对端未带/旧版（缺字段）回落对端 deviceAddress/MAC（向后兼容）,
 *   "net": {
 *     "wifi": <bool>, "ssid": "<可空>", "ip": "<IPv4 地址,可空>",
 *     "mask": "<子网掩码,可空>", "cellular": <bool>
 *   }
 * }
 */
data class NetInfo(
    val wifi: Boolean = false,
    val ssid: String? = null,
    val ip: String? = null,
    val mask: String? = null,
    val cellular: Boolean = false,
)

data class HandshakeMessage(
    val v: Int = HandshakeProtocol.VERSION,
    val alias: String = "",
    val model: String = "",
    val root: Boolean = false,
    val battery: Int? = null,
    val fp: String? = null,
    val net: NetworkSummary = NetworkSummary(),
)

object HandshakeProtocol {
    const val VERSION = 1
    private const val TAG = "HandshakeProtocol"

    private const val F_V = "v"
    private const val F_ALIAS = "alias"
    private const val F_MODEL = "model"
    private const val F_ROOT = "root"
    private const val F_BATTERY = "battery"
    private const val F_FP = "fp"
    private const val F_NET = "net"
    private const val F_WIFI = "wifi"
    private const val F_SSID = "ssid"
    private const val F_IP = "ip"
    private const val F_MASK = "mask"
    private const val F_CELLULAR = "cellular"

    /** 构造本机握手消息（网络信息实时采集；root 用缓存探测结果，不阻塞）。
     * v0.5.9 UI1b-C：携带本端指纹 fp（PinStore.localFingerprint，首次读取自动生成并持久化），
     * 对端解析优先取 fp 作对端指纹（配对表/免验依据），缺失回落 deviceAddress（旧版对端兼容）。
     */
    fun buildLocal(context: Context): HandshakeMessage {
        val net = NetworkInfoProvider.collect(context)
        return HandshakeMessage(
            v = VERSION,
            alias = Build.MODEL,
            model = Build.MODEL,
            root = RootDetector.isRoot(),
            battery = readBattery(context),
            fp = PinStore(context).localFingerprint(),
            net = net,
        )
    }

    /** 读取本机电量百分比（0-100）；BatteryManager 不可用或异常时返回 null。 */
    private fun readBattery(context: Context): Int? = try {
        val bm = context.getSystemService(BatteryManager::class.java)
        bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
    } catch (e: Exception) {
        Log.w(TAG, "读取电量失败: $e")
        null
    }

    /** 序列化为单行 JSON。 */
    fun toJson(m: HandshakeMessage): String {
        val net = JSONObject()
        net.put(F_WIFI, m.net.wifi)
        net.put(F_SSID, m.net.ssid ?: JSONObject.NULL)
        net.put(F_IP, m.net.ip ?: JSONObject.NULL)
        net.put(F_MASK, m.net.mask ?: JSONObject.NULL)
        net.put(F_CELLULAR, m.net.cellular)

        val o = JSONObject()
        o.put(F_V, m.v)
        o.put(F_ALIAS, m.alias)
        o.put(F_MODEL, m.model)
        o.put(F_ROOT, m.root)
        o.put(F_BATTERY, m.battery ?: JSONObject.NULL)
        // fp 可选字段：null 时序列化为 JSONObject.NULL，解析还原 null（旧版对端缺失回落 deviceAddress）
        o.put(F_FP, m.fp ?: JSONObject.NULL)
        o.put(F_NET, net)
        return o.toString()
    }

    /**
     * 编码为 UTF-8 字节。超过静态上限 [Constants.MAX_HANDSHAKE_BYTES] 时仅打警告
     * （Log + DiagLogger），**不截断**、按原长度返回完整 bytes；
     * 真实单包长度校验由 GattClient#sendHandshake 按协商 MTU（mtu-3）动态执行，超限即 fail。
     */
    fun encode(m: HandshakeMessage): ByteArray {
        val bytes = toJson(m).toByteArray(Charsets.UTF_8)
        if (bytes.size > Constants.MAX_HANDSHAKE_BYTES) {
            val msg = "握手消息 ${bytes.size}B 超过静态上限 ${Constants.MAX_HANDSHAKE_BYTES}B（将按原长度发送，由调用方按协商 MTU 校验，不再截断）"
            Log.w(TAG, msg)
            DiagLogger.log(TAG, msg)
        }
        return bytes
    }

    fun decode(bytes: ByteArray): HandshakeMessage? = parse(String(bytes, Charsets.UTF_8))

    fun parse(json: String): HandshakeMessage? = try {
        val o = JSONObject(json)
        val net = o.optJSONObject(F_NET)
        HandshakeMessage(
            v = o.optInt(F_V, 0),
            alias = o.optString(F_ALIAS, ""),
            model = o.optString(F_MODEL, ""),
            root = o.optBoolean(F_ROOT, false),
            battery = o.optString(F_BATTERY)
                .takeIf { it.isNotBlank() && it != "null" }
                ?.toIntOrNull()
                ?.takeIf { it in 0..100 },
            // fp 可选字段：缺失/null → null（引擎回落 deviceAddress 作对端指纹，旧版对端兼容）
            fp = o.optString(F_FP).takeIf { it.isNotBlank() && it != "null" },
            net = NetworkSummary(
                wifi = net?.optBoolean(F_WIFI, false) ?: false,
                ssid = net?.optString(F_SSID)?.takeIf { it.isNotBlank() && it != "null" },
                ip = net?.optString(F_IP)?.takeIf { it.isNotBlank() && it != "null" },
                mask = net?.optString(F_MASK)?.takeIf { it.isNotBlank() && it != "null" },
                cellular = net?.optBoolean(F_CELLULAR, false) ?: false,
            ),
        )
    } catch (e: Exception) {
        Log.w(TAG, "握手 JSON 解析失败: $e")
        DiagLogger.log(TAG, "握手 JSON 解析失败: $e，原始串前 80 字符: ${json.take(80)}")
        null
    }

    /** 详情弹层用的缩进 JSON 渲染。 */
    fun prettyJson(m: HandshakeMessage): String = JSONObject(toJson(m)).toString(2)
}

/**
 * Magisk root 探测（一期）。
 *
 * 原则：不主动进入交互式 root 授权流程。
 * 1) 本机不存在常见 su 二进制 → 直接返回 false，绝不执行任何 su；
 * 2) 存在 su → 做一次带超时的静默探测（`su -c id`），stdout 含 "uid=0" 才算 root；
 *    未授权时 Magisk 可能在首次探测弹授权对话框，本探测单次执行 + 2s 超时快速失败
 *    （探测失败/未授权一律 = false），不进入交互授权流程。
 * 探测结果缓存，仅应用启动时后台执行一次。
 */
internal object RootDetector {
    private const val TAG = "RootDetector"
    private const val PROBE_TIMEOUT_SECONDS = 2L

    @Volatile
    private var cached: Boolean? = null

    private val lock = Any()

    /** 启动时在后台触发一次探测（非阻塞）。 */
    fun init() {
        if (cached != null) return
        synchronized(lock) {
            if (cached != null) return
            cached = false // 先占位，避免并发重复探测
            Thread {
                try {
                    cached = probe()
                    Log.d(TAG, "root 探测结果: $cached")
                } catch (e: Exception) {
                    Log.w(TAG, "root 探测异常: $e")
                    cached = false
                }
            }.apply {
                isDaemon = true
                name = "bluelink-root-probe"
                start()
            }
        }
    }

    fun isRoot(): Boolean = cached ?: false

    private fun probe(): Boolean {
        if (!suBinaryExists()) return false
        return try {
            val p = ProcessBuilder("su", "-c", "id")
                .redirectErrorStream(true)
                .start()
            p.outputStream.close() // 关闭 stdin，避免探测进程挂起等待输入
            val done = p.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!done) {
                Log.w(TAG, "su -c id 超时，判定未授权")
                p.destroy()
                return false
            }
            val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
            out.contains("uid=0")
        } catch (e: Exception) {
            Log.w(TAG, "su 执行失败: $e")
            false
        }
    }

    private fun suBinaryExists(): Boolean {
        val paths = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/system/bin/.magisk/su",
            "/system/xbin/.magisk/su",
            "/data/adb/magisk/su",
        )
        return paths.any { File(it).exists() }
    }
}
