package com.zglinus.bluelink.security

import android.content.Context
import android.content.SharedPreferences
import com.zglinus.bluelink.diag.DiagLogger

/**
 * v0.4.9 PIN 配对验证存储（SharedPreferences，App 私有数据，无第三方依赖）：
 *
 * - 模式 [mode]（[KEY_MODE]）：
 *   - [MODE_OFF]（0=关）：不校验（现状，直接放行）；
 *   - [MODE_FIRST]（1=仅首次）：首次握手 PIN 匹配成功后按对端指纹记入配对表，后续同指纹免验；
 *   - [MODE_EVERY]（2=每次）：每次握手均校验，匹配成功**不**记配对表；
 * - 配对指纹表 [pairedFingerprints]（[KEY_PAIRED]，StringSet）：仅首次模式（[MODE_FIRST]）
 *   匹配成功时由引擎 [PinStore.addPaired] 写入。指纹 = 对端握手指纹——本项目握手 JSON
 *   （HandshakeProtocol v1）无 fingerprint 字段，以对端 BLE 设备地址（deviceAddress/MAC）为
 *   每设备稳定指纹，配对表按此记忆；[isPaired] 供「已配对免验」放行判定；[clearAll] 供
 *   设置区「清除配对列表」。
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
        private const val TAG = "PinStore"
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 已配对指纹表（内存镜像，读写一致；addPaired/clearAll 同步持久化）。 */
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

    /** 对端指纹是否已配对（「已配对免验」放行判定）。 */
    fun isPaired(fingerprint: String): Boolean = fingerprint in pairedFingerprints

    /**
     * 记录配对（仅首次模式由引擎在 PIN 匹配成功后调用）。
     * 幂等：已存在返回 false（不重复记）；新记入返回 true。
     */
    fun addPaired(fingerprint: String): Boolean {
        val added = pairedFingerprints.add(fingerprint)
        if (added) {
            persistPaired()
            DiagLogger.log(TAG, "已记录配对指纹：$fingerprint")
        } else {
            DiagLogger.log(TAG, "配对指纹已存在（幂等）：$fingerprint")
        }
        return added
    }

    /** 清空配对列表（设置区「清除配对列表」）。 */
    fun clearAll() {
        pairedFingerprints.clear()
        persistPaired()
        DiagLogger.log(TAG, "配对列表已清空")
    }

    /** 已配对指纹数量（设置区 UI 展示）。 */
    val pairedCount: Int get() = pairedFingerprints.size

    private fun persistPaired() {
        prefs.edit().putStringSet(KEY_PAIRED, pairedFingerprints).apply()
    }
}
