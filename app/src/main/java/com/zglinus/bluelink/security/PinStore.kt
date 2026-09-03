package com.zglinus.bluelink.security

import android.content.Context
import android.content.SharedPreferences
import com.zglinus.bluelink.diag.DiagLogger
import java.security.SecureRandom
import java.util.Locale

/**
 * v0.4.9 PIN 配对验证存储（SharedPreferences，App 私有数据，无第三方依赖）：
 *
 * - 模式 [mode]（[KEY_MODE]）：
 *   - [MODE_OFF]（0=关）：不校验（现状，直接放行）；
 *   - [MODE_FIRST]（1=仅首次）：首次握手 PIN 匹配成功后按对端指纹记入配对表，后续同指纹免验；
 *   - [MODE_EVERY]（2=每次）：每次握手均校验，匹配成功**不**记配对表；
 * - 本端指纹 [localFingerprint]（[KEY_LOCAL_FP]，String）：本机安装级随机标识（16 hex 大写），
 *   首次读取时生成并持久化；随握手信令（fp 字段）携带给对端，作为对端视角的「对端指纹」，
 *   替代设备地址/MAC 作稳定指纹（重置 MAC 随机化/换设备名不影响互认）。[resetLocalFingerprint]
 *   重新生成（旧值作废，调用方负责提示对端需重新互认）。
 * - 配对指纹表 [pairedFingerprints]（[KEY_PAIRED]，StringSet）：仅首次模式（[MODE_FIRST]）
 *   匹配成功时由引擎 [PinStore.addPaired] 写入。v0.5.9 起条目为序列化 `指纹` 或 `指纹|别名`
 *   （别名可空）；读取兼容旧纯指纹条目（无 `|` → alias=null）。指纹 = 对端握手指纹——本项目
 *   握手 JSON（HandshakeProtocol v1 + fp 可选字段）优先取对端携带的本端指纹（fp），对端未带
 *   （旧版）回落对端 BLE 设备地址（deviceAddress/MAC）；[isPaired] 供「已配对免验」放行判定；
 *   [clearAll] 供设置区「清除配对列表」。
 *
 * 线程模型：SharedPreferences 自身线程安全（内部锁）；全部写入走 `apply()`（异步落盘，
 * 不阻塞主线程）；指纹表在内存持有 [MutableSet] 镜像、写入时同步持久化。
 */
class PinStore(context: Context) {

    companion object {
        /** 模式 0=关：不校验，直接放行（现状）。 */
        const val MODE_OFF = 0

        /** 模式 1=仅首次：首次匹配后按指纹记配对表，后续同指纹免验。 */
        const val MODE_FIRST = 1

        /** 模式 2=每次：每次握手均校验，不记配对表。 */
        const val MODE_EVERY = 2

        private const val PREFS_NAME = "bluelink_pin_store"
        private const val KEY_MODE = "pin_mode"
        private const val KEY_PAIRED = "paired_fingerprints"

        /** v0.5.9 UI1b-C：本端指纹（本机安装级随机标识，16 hex 大写）。 */
        private const val KEY_LOCAL_FP = "local_fingerprint"

        /** 本端指纹生成参数：8 随机字节 → 16 hex 大写。 */
        private const val FP_BYTES = 8

        private const val TAG = "PinStore"
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 配对表内存镜像（读写一致；条目 = `指纹` 或 `指纹|别名`，addPaired/removePaired/clearAll 同步持久化）。 */
    val pairedFingerprints: MutableSet<String> = mutableSetOf<String>().apply {
        prefs.getStringSet(KEY_PAIRED, emptySet())?.let { addAll(it) }
    }

    /** 当前 PIN 验证模式（0=关 1=仅首次 2=每次；默认 0=关）。 */
    fun getMode(): Int = prefs.getInt(KEY_MODE, MODE_OFF)

    /** 设置 PIN 验证模式并持久化。 */
    fun setMode(mode: Int) {
        val m = mode.coerceIn(MODE_OFF, MODE_EVERY)
        prefs.edit().putInt(KEY_MODE, m).apply()
        DiagLogger.log(TAG, "PIN 验证模式已保存：$m")
    }

    // ---------- v0.5.9 UI1b-C：本端指纹 ----------

    /**
     * 本端指纹（本机安装级随机标识，16 hex 大写）：不存在则生成并持久化（[KEY_LOCAL_FP]）。
     * 随握手信令 fp 字段携带给对端；本端视角的「对端指纹」= 对端握手里带的本端指纹。
     * 幂等：已生成过直接返回持久值（重启不变，保证与历史配对互认稳定）。
     */
    fun localFingerprint(): String {
        prefs.getString(KEY_LOCAL_FP, null)
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        return generateAndPersistLocalFingerprint()
    }

    /**
     * 重置本端指纹：重新生成并持久化（旧值作废——对端配对表里的旧指纹不再匹配，
     * 调用方负责提示对端需重新互认）。返回新指纹。
     */
    fun resetLocalFingerprint(): String {
        val fresh = generateAndPersistLocalFingerprint()
        DiagLogger.log(TAG, "本端指纹已重置：$fresh（旧值作废，对端需重新互认）")
        return fresh
    }

    private fun generateAndPersistLocalFingerprint(): String {
        val fresh = newFingerprint()
        prefs.edit().putString(KEY_LOCAL_FP, fresh).apply()
        return fresh
    }

    /** 随机 16 hex 大写（SecureRandom 8 字节 → hex）。 */
    private fun newFingerprint(): String {
        val bytes = ByteArray(FP_BYTES)
        SecureRandom().nextBytes(bytes)
        val sb = StringBuilder(FP_BYTES * 2)
        for (b in bytes) {
            sb.append(String.format(Locale.US, "%02X", b.toInt() and 0xFF))
        }
        return sb.toString()
    }

    // ---------- v0.5.9 UI1b-C：配对表条目（指纹[|别名]，读取兼容旧纯指纹条目） ----------

    /**
     * 配对表条目：指纹 = 对端握手指纹（fp 优先，缺失回落 deviceAddress）；alias = 配对时
     * 对端信令携带的别名（握手 alias），可为 null（旧纯指纹条目 / 对端未带）。
     */
    data class PairedEntry(val fingerprint: String, val alias: String?)

    /** 对端指纹是否已配对（「已配对免验」放行判定；兼容旧纯指纹条目：按 `|` 前段匹配）。 */
    fun isPaired(fingerprint: String): Boolean =
        pairedFingerprints.any { it.substringBefore('|') == fingerprint }

    /**
     * 记录配对（仅首次模式由引擎在 PIN 匹配成功后调用；可携带对端别名写入条目 `指纹|别名`）。
     * 幂等：同指纹已存在返回 false（不重复记）；别名变更/补充时更新条目内容（仍返回 false）；
     * 新记入返回 true。
     */
    fun addPaired(fingerprint: String, alias: String? = null): Boolean {
        val aliasNorm = alias?.takeIf { it.isNotBlank() }
        val entry = if (aliasNorm == null) fingerprint else "$fingerprint|$aliasNorm"
        val existing = pairedFingerprints.firstOrNull { it.substringBefore('|') == fingerprint }
        if (existing != null) {
            if (existing != entry) {
                // 同指纹重复配对：条目内容变化（补充/更新别名）→ 更新条目，仍按幂等处理（返回 false）
                pairedFingerprints.remove(existing)
                pairedFingerprints.add(entry)
                persistPaired()
                DiagLogger.log(TAG, "配对指纹已存在，更新条目（幂等）：$fingerprint${if (aliasNorm != null) " 别名=$aliasNorm" else ""}")
            } else {
                DiagLogger.log(TAG, "配对指纹已存在（幂等）：$fingerprint")
            }
            return false
        }
        pairedFingerprints.add(entry)
        persistPaired()
        DiagLogger.log(TAG, "已记录配对指纹：$fingerprint${if (aliasNorm != null) "（别名：$aliasNorm）" else ""}")
        return true
    }

    /**
     * 单项移除配对（v0.5.9 UI1b-C：配对列表管理/身份页逐项解绑）：按指纹（`|` 前段）匹配移除，
     * 含同指纹别名条目。移除成功返回 true；不存在返回 false。
     */
    fun removePaired(fingerprint: String): Boolean {
        val hit = pairedFingerprints.firstOrNull { it.substringBefore('|') == fingerprint }
        if (hit == null) {
            DiagLogger.log(TAG, "移除配对：指纹不存在（忽略）：$fingerprint")
            return false
        }
        pairedFingerprints.remove(hit)
        persistPaired()
        DiagLogger.log(TAG, "已移除配对指纹：$fingerprint")
        return true
    }

    /** 清空配对列表（设置区「清除配对列表」）。 */
    fun clearAll() {
        pairedFingerprints.clear()
        persistPaired()
        DiagLogger.log(TAG, "配对列表已清空")
    }

    /**
     * 已配对条目列表（解析 StringSet；兼容旧纯指纹条目——无 `|` 的条目 alias=null）。
     * 按指纹排序，供设置区配对列表展示/逐项管理。
     */
    fun pairedEntries(): List<PairedEntry> = pairedFingerprints
        .map { raw ->
            val idx = raw.indexOf('|')
            if (idx < 0) {
                PairedEntry(raw, null)
            } else {
                PairedEntry(
                    fingerprint = raw.substring(0, idx),
                    alias = raw.substring(idx + 1).takeIf { it.isNotBlank() },
                )
            }
        }
        .sortedBy { it.fingerprint }

    /** 已配对指纹数量（设置区 UI 展示）。 */
    val pairedCount: Int get() = pairedFingerprints.size

    private fun persistPaired() {
        prefs.edit().putStringSet(KEY_PAIRED, pairedFingerprints).apply()
    }
}
