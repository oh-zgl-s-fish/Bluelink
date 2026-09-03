package com.zglinus.bluelink.networking

import android.content.Context
import android.content.SharedPreferences
import com.zglinus.bluelink.diag.DiagLogger

/**
 * v0.5.9 UI1b-C 热点预设存储（SharedPreferences，App 私有数据，无第三方依赖）：
 *
 * 用户预设热点 SSID/密码（设置区「热点 SSID 随机/自定义」），热点方自设 SSID 路径消费：
 *
 * - [ssid]（String，1–32 校验由 UI/写入处做，此处存原值）；
 * - [password]（String?，null=留空随机——热点启动时沿用原「随机生成密码」逻辑）；
 * - [enabled]（Boolean，默认 false；false 时热点路径完全维持现行为，不消费本存储）；
 * - [privateApiEnabled]（Boolean，默认 true；v0.5.14c：② 私有 API 热点运行时开关——false 时
 *   HotspotManager 的 ② 入口守卫直接失败降级 ③ LocalOnly，设置页「② 私有 API 热点」Switch 读写；
 *   存放于本 prefs 文件，与热点预设同属设置页热点区）。
 *
 * 消费点（HotspotManager）：私有 API/root 自设 SSID 处——enabled 且 ssid 非空时用预设
 * ssid/password，password 空则沿用随机生成；enabled=false 完全现行为。
 * ② 开关消费点（HotspotManager.tryPrivateApiHotspot 入口守卫）：privateApiEnabled=false → ② 直接失败降级 ③。
 * LocalOnly 本地热点（③，系统生成 SSID/密码）**不适用**（不消费预设——SSID/密码由系统生成）。
 * 手动④ 流程预设仅用于「预填提示」（defaultSsid/存储值 UI 展示），由设置页 UI 任务消费。
 *
 * 写入语义：[save] 直接存传入值（enabled=false 仅关闭开关，不清空已存 ssid/password，
 * 下次开启仍可复用）；空 ssid 的「不覆盖旧值/清空」由调用方控制。
 */
class HotspotPresetStore(context: Context) {

    companion object {
        private const val PREFS_NAME = "bluelink_hotspot_preset"
        private const val KEY_SSID = "ssid"
        private const val KEY_PASSWORD = "password"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_PRIVATE_API_ENABLED = "private_api_enabled"
        private const val TAG = "HotspotPresetStore"

        /** 建议默认 SSID（UI 展示/预填提示用）：如 `Bluelink-<别名>`。 */
        fun defaultSsid(alias: String): String =
            "Bluelink-${alias.takeIf { it.isNotBlank() } ?: "Device"}"
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 预设 SSID（原值，1–32 校验由 UI/写入处保证；空串=未设置）。 */
    fun ssid(): String = prefs.getString(KEY_SSID, "") ?: ""

    /** 预设密码（null/空=留空随机——热点启动时沿用随机生成逻辑）。 */
    fun password(): String? = prefs.getString(KEY_PASSWORD, null)?.takeIf { it.isNotBlank() }

    /** 预设是否启用（默认 false=关闭，热点路径完全现行为）。 */
    fun enabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    /**
     * ② 私有 API 热点开关（v0.5.14c，运行时 prefs，默认 true=开）。
     * false → HotspotManager ② 入口守卫失败（「② 已关闭(设置开关)，降级 ③」），组网直接用 LocalOnly(③)。
     * 与 [enabled]/[ssid]/[password] 的「保存预设」独立：Switch 改动即写，不需点「保存预设」。
     */
    var privateApiEnabled: Boolean
        get() = prefs.getBoolean(KEY_PRIVATE_API_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_PRIVATE_API_ENABLED, value).apply()
            DiagLogger.log(TAG, "② 私有 API 热点开关已写入 privateApiEnabled=$value（false → ② 降级 ③ LocalOnly）")
        }

    /**
     * 保存预设（直接存传入值，不做越界裁剪——1–32 与空值处理由 UI/写入处保证）。
     * @param ssid 预设 SSID（空 ssid 的「不覆盖旧值/清空」由调用方控制，此处原样存）。
     * @param password 预设密码；null=留空随机（热点启动时沿用随机生成）。
     * @param enabled 是否启用预设。
     */
    fun save(ssid: String, password: String?, enabled: Boolean) {
        val editor = prefs.edit()
        editor.putString(KEY_SSID, ssid)
        editor.putString(KEY_PASSWORD, password?.takeIf { it.isNotBlank() })
        editor.putBoolean(KEY_ENABLED, enabled)
        editor.apply()
        DiagLogger.log(
            TAG,
            "热点预设已保存：enabled=$enabled ssidLen=${ssid.length} " +
                "pwdLen=${(password ?: "").length}（密码不回显）",
        )
    }
}
