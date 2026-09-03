package com.zglinus.bluelink.ui.personalize

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.zglinus.bluelink.ui.theme.rememberEffectiveDark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 壁纸背景解码与渲染（v0.5.7 UI1b-B：主页面背景应用；手写 ContentResolver/BitmapFactory +
 * WallpaperManager，无 coil 等新依赖；回收/缓存单例简单化——不建全局缓存，按需解码、换槽时
 * 经 mainHandler 延后回收旧位图）。
 *
 * - [WallpaperBackdrop]：MainScreen 根背景（Scaffold 之下）。按当前全局生效深浅（[rememberEffectiveDark]，
 *   v0.5.9 UI1b-C 起由 themeMode 推导 Provide——不再直读 isSystemInDarkTheme）
 *   → [WallpaperStore.effectiveSlot] 取槽 → [rememberSlotBitmap] 异步解码（produceState +
 *   Dispatchers.IO + BitmapFactory downsample 防 OOM）→ [WallpaperEffect] 绘制「壁纸 + surfaceVariant
 *   遮罩（mask%）」。无壁纸时不绘制任何内容（露出底层纯色 background，维持现状）；
 * - [tick]（BluelinkUiState.wallpaperTick）为刷新信号：槽/遮罩改动后 +1 触发重读 store 并重绘。
 *   解码以 slot（type+uri）为 produceState key——槽未变（仅遮罩/强调色变化）时不重复解码；
 * - 预览（个性化页预览块/槽缩略）与本文件同套解码/效果函数，保证预览 = 真实背景效果。
 *
 * v0.5.8b 自选图来源形态：[PersonalizePage] 选图后立即复制到 App 私有目录
 * （filesDir/wallpapers/）并**存文件绝对路径**（无 scheme 直存）——重启必在、BitmapFactory.decodeFile
 * 直读无需 provider 授权（修 v0.5.8「content:// grant 重启失效 / 双次 openInputStream 不稳 → 自选图
 * 不显示」）；[decodeUriWallpaper] 兼容三种 uri 字符串：绝对路径 / `file://`（Uri.parse(...).path 取
 * 路径）/ 老存量 `content://`（保留 openInputStream 双次读路径与既有 takePersistable 授权尝试，不删）。
 * 另：解码结果三态化（[rememberSlotDecode] 带 failed）——预览区失败给可见文案而非无限「加载中」
 * （见 PersonalizePage.PreviewSection）；主页面根背景仍按失败 = null 回纯色，无需改。
 */

/** 主页面背景根层可解码目标尺寸上限（最长边；继续 downsample 防 OOM）。 */
private const val BACKDROP_MAX_DIM = 1440

/** 槽缩略/预览用的解码上限（小于根背景，省内存）。 */
internal const val THUMB_MAX_DIM = 480

internal const val PREVIEW_MAX_DIM = 720

/**
 * 根背景 Composable：由 MainScreen 垫在 Scaffold 之下（App 根背景）。
 * 仅在有壁纸时绘制；无壁纸返回空（纯色 background 由外层 Box 提供）。
 */
@Composable
fun WallpaperBackdrop(
    store: WallpaperStore,
    tick: Int,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // 全局生效深浅（v0.5.9 UI1b-C 判定源：themeMode 手动模式 / 跟随系统；不再直读 isSystemInDarkTheme）——
    // 深浅切换（系统或手动）自动重取槽；themeMode 变化时 BluelinkTheme 重算 → 本读取点随重组合刷新
    val dark = rememberEffectiveDark()
    // 每次 recomposition 重读 prefs（tick 变化驱动本层重绘；遮罩/强调色变化走同路径）
    val slot = store.effectiveSlot(dark)
    val wallpaper = rememberSlotBitmap(context = context, slot = slot, maxDim = BACKDROP_MAX_DIM)
    WallpaperEffect(
        wallpaper = wallpaper,
        maskAlpha = store.maskAlpha,
        modifier = modifier,
    )
}

/**
 * 「壁纸 + 遮罩」渲染单元（主页面根背景 / 个性化页预览块共用，保证预览一致）：
 * 壁纸图 ContentScale.Crop 铺满；其上叠加半透明遮罩 Box —— 遮罩色 = 当前主题 surfaceVariant，
 * 透明度 = maskAlpha%（0–80，滑块上限）。**遮罩色 = surfaceVariant，随系统深浅模式自动切换
 * （v0.5.8b 确认：M3 语义 token 浅深各派生，无需按深浅分支改代码）**。
 * v0.5.11b：可选 [containerOverlayAlpha]（默认 0 = 不叠）——>0 时在遮罩层之上再叠一层当前主题
 * background 色 Box（alpha = containerOverlayAlpha），模拟主页浮层容器盖住壁纸后的整区观感；
 * 语义 = 容器不透明度 alpha 0.5–0.95（个性化页预览由调用方按容器透明度草稿换算传入）。
 */
@Composable
internal fun WallpaperEffect(
    wallpaper: ImageBitmap?,
    maskAlpha: Int,
    modifier: Modifier = Modifier,
    // v0.5.11b：容器 overlay alpha（0=不叠，主页背景等既有调用不变；个性化页预览传 (100f-transparencyDraft)/100f）
    containerOverlayAlpha: Float = 0f,
) {
    val maskColor = MaterialTheme.colorScheme.surfaceVariant
    Box(modifier = modifier) {
        if (wallpaper != null) {
            Image(
                bitmap = wallpaper,
                contentDescription = null, // 装饰性背景，不读屏（无障碍）
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            if (maskAlpha > 0) {
                // 半透明遮罩 Box：surfaceVariant 按 mask% 叠加在壁纸上
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(maskColor.copy(alpha = maskAlpha / 100f)),
                )
            }
            if (containerOverlayAlpha > 0f) {
                // v0.5.11b：遮罩层之上再叠当前主题背景色层（alpha = 容器不透明度）——模拟主页浮层
                // 容器盖壁纸后的整区观感；透明度草稿越高容器层越透明、壁纸透出越多（与主页一致）
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background.copy(alpha = containerOverlayAlpha)),
                )
            }
        }
    }
}

/** 槽解码结果（v0.5.8b）：区分解码中（bitmap/failed 皆否）/ 成功 / 失败（解码完成但不可用）——
 *  预览区据此给可见失败文案，不再把失败当「加载中」无限提示；主页面背景仍按 bitmap==null 回纯色。 */
internal data class SlotDecode(
    val bitmap: ImageBitmap?,
    val failed: Boolean,
)

/**
 * 槽壁纸三态异步解码（produceState + IO 线程 decode；换槽/清槽自动重启）。
 * key = slot（type+uri data class equals）→ 槽未变不重复解码；旧位图值先经 mainHandler 延后回收
 * （待当前帧绘完，避免回收仍被引用/绘制的位图）。
 * v0.5.8b：解码完成但不可用（null）→ failed=true（预览区显示失败文案）；主页面根背景仍走
 * [rememberSlotBitmap]（失败 = null → 回纯色）。
 */
@Composable
internal fun rememberSlotDecode(
    context: Context,
    slot: WallpaperSlot,
    maxDim: Int,
): SlotDecode {
    return produceState<SlotDecode>(initialValue = SlotDecode(bitmap = null, failed = false), key1 = slot) {
        val old = value
        if (!slot.isSet) {
            value = SlotDecode(bitmap = null, failed = false)
            recycleLater(old.bitmap)
            return@produceState
        }
        val decoded = withContext(Dispatchers.IO) {
            decodeSlotWallpaper(context.applicationContext, slot, maxDim)
        }
        value = if (decoded != null) {
            SlotDecode(bitmap = decoded.asImageBitmap(), failed = false)
        } else {
            SlotDecode(bitmap = null, failed = true)
        }
        recycleLater(old.bitmap)
    }.value
}

/** 槽壁纸异步解码（主页面根背景用；未设/失败 → null —— 主页面回纯色）。解码逻辑同 [rememberSlotDecode]。 */
@Composable
internal fun rememberSlotBitmap(
    context: Context,
    slot: WallpaperSlot,
    maxDim: Int,
): ImageBitmap? = rememberSlotDecode(context = context, slot = slot, maxDim = maxDim).bitmap

/** 按槽来源解码（自选 uri → [decodeUriWallpaper]；跟系统壁纸 → [decodeSystemWallpaper]；异常 → null）。 */
private fun decodeSlotWallpaper(context: Context, slot: WallpaperSlot, maxDim: Int): Bitmap? = try {
    when (slot.type) {
        WallpaperSlot.TYPE_SYSTEM -> decodeSystemWallpaper(context, maxDim)
        WallpaperSlot.TYPE_URI -> slot.uri?.let { decodeUriWallpaper(context, it, maxDim) }
        else -> null
    }
} catch (t: Throwable) {
    null
}

/**
 * 自选图解码入口：uri 字符串三种形态——
 * - 绝对路径（v0.5.8b 起私有目录副本，无 scheme 直存）/ `file://` scheme（Uri.parse(...).path 取路径）
 *   → [decodeFile] 直读（重启必在、无需 provider 授权、最稳）；
 * - 老存量 `content://` → [decodeContentUri]（保留 openInputStream bounds 预读 + 实解双次读路径，
 *   与既有 takePersistableUriPermission 授权尝试不变，兼容不删）。
 * decode 行均在 Dispatchers.IO（调用方保证）。
 */
private fun decodeUriWallpaper(context: Context, uriString: String, maxDim: Int): Bitmap? {
    val filePath = when {
        uriString.startsWith("file://") -> Uri.parse(uriString).path
        uriString.startsWith("/") -> uriString
        else -> null
    }
    if (filePath != null) {
        return decodeFile(filePath, maxDim)
    }
    return decodeContentUri(context, Uri.parse(uriString), maxDim)
}

/** 文件路径直读解码（v0.5.8b 私有副本 / file:// 兼容）：bounds 预读 → inSampleSize → 实解 → 长边收边。 */
private fun decodeFile(path: String, maxDim: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    val w = bounds.outWidth
    val h = bounds.outHeight
    if (w <= 0 || h <= 0) return null
    val opts = BitmapFactory.Options().apply { inSampleSize = sampleOf(w, h, maxDim) }
    val raw = BitmapFactory.decodeFile(path, opts) ?: return null
    val scaled = scaleToMax(raw, maxDim)
    if (scaled !== raw) raw.recycle()
    return scaled
}

/**
 * content://（老存量 v0.5.8 及更早）解码：先 inJustDecodeBounds 读宽高 → inSampleSize（2 幂 downsample，
 * 防 OOM）解码 → 长边超过 maxDim 再精确收边。decode 行均在 Dispatchers.IO（调用方保证）。
 */
private fun decodeContentUri(context: Context, uri: Uri, maxDim: Int): Bitmap? {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
    val w = bounds.outWidth
    val h = bounds.outHeight
    if (w <= 0 || h <= 0) return null
    val opts = BitmapFactory.Options().apply { inSampleSize = sampleOf(w, h, maxDim) }
    val raw = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
    val scaled = scaleToMax(raw, maxDim)
    if (scaled !== raw) raw.recycle()
    return scaled
}

/** 2 幂 downsample（长边 ≤ maxDim → 1；防 OOM；bounds 已读宽高）。 */
private fun sampleOf(w: Int, h: Int, maxDim: Int): Int {
    var sample = 1
    while (maxOf(w, h) / (sample * 2) >= maxDim) sample *= 2
    return sample
}

/** 跟系统壁纸：WallpaperManager.drawable → 缩到 maxDim 内的位图（不在本进程缓存原图）。 */
private fun decodeSystemWallpaper(context: Context, maxDim: Int): Bitmap? {
    val drawable = WallpaperManager.getInstance(context).drawable ?: return null
    val w = drawable.intrinsicWidth
    val h = drawable.intrinsicHeight
    if (w <= 0 || h <= 0) return null
    val scale = minOf(1f, maxDim.toFloat() / maxOf(w, h))
    val tw = (w * scale).toInt().coerceAtLeast(1)
    val th = (h * scale).toInt().coerceAtLeast(1)
    val bmp = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    drawable.setBounds(0, 0, tw, th)
    drawable.draw(canvas)
    return bmp
}

/** 长边超过 maxDim 时等比收边（否则原样返回）。 */
private fun scaleToMax(src: Bitmap, maxDim: Int): Bitmap {
    val longSide = maxOf(src.width, src.height)
    if (longSide <= maxDim) return src
    val scale = maxDim.toFloat() / longSide
    val w = (src.width * scale).toInt().coerceAtLeast(1)
    val h = (src.height * scale).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(src, w, h, true)
}

/** mainHandler 延后回收旧位图（post 到下一主循环：当前帧已绘制完旧图后才回收，避免崩溃）。 */
private fun recycleLater(bitmap: ImageBitmap?) {
    val bmp = bitmap ?: return
    Handler(Looper.getMainLooper()).post { bmp.asAndroidBitmap().recycle() }
}
