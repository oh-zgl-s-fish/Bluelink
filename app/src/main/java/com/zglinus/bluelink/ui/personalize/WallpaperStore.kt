package com.zglinus.bluelink.ui.personalize

import android.content.Context
import android.net.Uri

/**
 * 个性化壁纸槽（v0.5.7 UI1b-B：三壁纸槽 + 遮罩 + 取色 + 预览 + 主页面背景应用）。
 * type 决定槽的来源：
 * - [TYPE_SYSTEM]：跟系统壁纸（WallpaperManager 实时取系统壁纸绘制，不持久化图片本身）；
 * - [TYPE_URI]：自选图片（SAF 选图，持久化 content:// uri 字符串）；
 * - [TYPE_NONE]：未设置（纯色背景现状）。
 */
data class WallpaperSlot(
    val type: String = TYPE_NONE,
    val uri: String? = null,
) {
    /** SAF/文件 uri 解析对象（无则 null）。 */
    val uriObj: Uri? get() = uri?.let(Uri::parse)

    /** 槽是否已设（跟系统壁纸 或 自选图片）。 */
    val isSet: Boolean get() = type != TYPE_NONE

    companion object {
        /** 未设置。 */
        const val TYPE_NONE = "none"

        /** 跟系统壁纸。 */
        const val TYPE_SYSTEM = "system"

        /** 自选图片（SAF，content:// uri 字符串）。 */
        const val TYPE_URI = "uri"

        /** 空槽常量（未设置）。 */
        val NONE = WallpaperSlot()
    }
}

/**
 * 壁纸/遮罩/强调色存储（纯 UI 存储层：SharedPreferences；引擎不感知，无新依赖，无 coil 等第三方）。
 *
 * 存储结构（prefs 名 [PREFS_NAME]）：
 * - 三槽：统一壁纸（兜底）[SLOT_UNIFIED] / 深色模式壁纸 [SLOT_DARK] / 浅色模式壁纸 [SLOT_LIGHT]，
 *   每槽持久化 {type, uri}（读 [slot] / 写 [setSlot] / 清 [clearSlot]）；uri 仅 TYPE_URI 时有值；
 * - [maskAlpha]：遮罩强度 0–[MASK_MAX]（%）；背景遮罩用当前主题 surfaceVariant 色按该百分比叠加；
 * - [accentColor]：强调色 ARGB Long（0xAARRGGBB；「从壁纸取色」/ 下方基础色板选择写入）。
 *   本版仅做「选中色 chip」预览展示与后续主题预留，不全局改 ColorScheme（见 PersonalizePage.kt）；
 * - [containerTransparency]：容器透明度 5–[TRANSPARENCY_MAX]（%）= 主页浮层容器「透明程度」，
 *   容器实际 alpha = 1f − value/100f（[containerAlpha]；默认 20 → alpha 0.80，范围 alpha 0.95–0.50）
 *   ——v0.5.11 UI1b-E 改③ 新增：HOME 顶栏/两态卡/底部动作行/时间流/横幅等浮层 alpha 由固定常量
 *   0.80 改为本值运行态可调（MainScreen 读本 store，个性化页「容器透明度」滑块保存后生效）；
 * - [effectiveSlot]：背景取槽规则——按当前深浅模式取对应槽（深→深槽 / 浅→浅槽），槽未设 → 统一槽（兜底）；
 *   统一槽也未设 → [WallpaperSlot.NONE]（App 根背景回纯色，维持现状）。
 */
class WallpaperStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 读取槽位（type/uri 双键，槽读取 helper）。 */
    fun slot(slotId: Int): WallpaperSlot {
        val type = prefs.getString(TYPE_KEYS[slotId], WallpaperSlot.TYPE_NONE) ?: WallpaperSlot.TYPE_NONE
        val uri = prefs.getString(URI_KEYS[slotId], null)
        return WallpaperSlot(type = type, uri = uri)
    }

    /** 写入槽位（自选 uri 槽持久化 uri 字符串；none/system 槽清 uri 残留）。 */
    fun setSlot(slotId: Int, slot: WallpaperSlot) {
        val editor = prefs.edit().putString(TYPE_KEYS[slotId], slot.type)
        if (slot.uri != null) {
            editor.putString(URI_KEYS[slotId], slot.uri)
        } else {
            editor.remove(URI_KEYS[slotId])
        }
        editor.apply()
    }

    /** 清除槽位（type → none，uri 残留一并清）。 */
    fun clearSlot(slotId: Int) = setSlot(slotId, WallpaperSlot.NONE)

    /** 遮罩强度（0–[MASK_MAX]，越界写入自动收拢）。 */
    var maskAlpha: Int
        get() = prefs.getInt(KEY_MASK, DEFAULT_MASK).coerceIn(0, MASK_MAX)
        set(value) {
            prefs.edit().putInt(KEY_MASK, value.coerceIn(0, MASK_MAX)).apply()
        }

    /** 强调色 ARGB Long（0xAARRGGBB；null = 未选）。 */
    var accentColor: Long?
        get() = if (prefs.contains(KEY_ACCENT)) prefs.getLong(KEY_ACCENT, 0L) else null
        set(value) {
            val editor = prefs.edit()
            if (value != null) {
                editor.putLong(KEY_ACCENT, value and 0xFFFFFFFFL)
            } else {
                editor.remove(KEY_ACCENT)
            }
            editor.apply()
        }

    /** 容器透明度（5–[TRANSPARENCY_MAX]%，越界写入自动收拢；v0.5.11 UI1b-E 改③）。
     * 语义 = 透明程度 %（数值越大容器越透明）；主页浮层容器实际 alpha 见 [containerAlpha]。 */
    var containerTransparency: Int
        get() = prefs.getInt(KEY_TRANSPARENCY, DEFAULT_TRANSPARENCY)
            .coerceIn(TRANSPARENCY_MIN, TRANSPARENCY_MAX)
        set(value) {
            prefs.edit().putInt(KEY_TRANSPARENCY, value.coerceIn(TRANSPARENCY_MIN, TRANSPARENCY_MAX)).apply()
        }

    /** 主页浮层容器实际 alpha = 1f − 透明度/100f（默认 20 → 0.80，范围 0.95–0.50；
     * 默认 0.80 与 v0.5.8d 顶栏浮层规格一致）。 */
    fun containerAlpha(): Float = 1f - containerTransparency / 100f

    /** 背景取槽 helper：当前深浅模式槽未设 → 统一槽兜底；仍未设 → 空槽（纯色）。 */
    fun effectiveSlot(isDark: Boolean): WallpaperSlot {
        val modeSlot = slot(if (isDark) SLOT_DARK else SLOT_LIGHT)
        if (modeSlot.isSet) return modeSlot
        return slot(SLOT_UNIFIED)
    }

    companion object {
        /** SharedPreferences 文件名（个性化/壁纸存储）。 */
        const val PREFS_NAME = "bluelink_wallpaper"

        /** 槽位 id：统一壁纸（兜底）。 */
        const val SLOT_UNIFIED = 0

        /** 槽位 id：深色模式壁纸。 */
        const val SLOT_DARK = 1

        /** 槽位 id：浅色模式壁纸。 */
        const val SLOT_LIGHT = 2

        /** 遮罩强度上限（0–80% 半透明遮罩，无保护下限——docs/ui-design.md ADR 13）。 */
        const val MASK_MAX = 80

        /** 遮罩强度默认值（无壁纸/不叠加）。 */
        const val DEFAULT_MASK = 0

        /** 容器透明度下限（5%；对应容器 alpha 0.95，下限保护：不透明上限 95%）。 */
        const val TRANSPARENCY_MIN = 5

        /** 容器透明度上限（50%；对应容器 alpha 0.50，至少保持半透明基底可读）。 */
        const val TRANSPARENCY_MAX = 50

        /** 容器透明度默认值（20% → 容器 alpha 0.80，同 v0.5.8d 顶栏浮层规格 0.80）。 */
        const val DEFAULT_TRANSPARENCY = 20

        private const val KEY_MASK = "mask_alpha"

        private const val KEY_ACCENT = "accent_color"

        private const val KEY_TRANSPARENCY = "container_transparency"

        private val TYPE_KEYS = arrayOf(
            "slot_unified_type",
            "slot_dark_type",
            "slot_light_type",
        )

        private val URI_KEYS = arrayOf(
            "slot_unified_uri",
            "slot_dark_uri",
            "slot_light_uri",
        )
    }
}
