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
import androidx.compose.foundation.isSystemInDarkTheme
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 壁纸背景解码与渲染（v0.5.7 UI1b-B：主页面背景应用；手写 ContentResolver/BitmapFactory +
 * WallpaperManager，无 coil 等新依赖；回收/缓存单例简单化——不建全局缓存，按需解码、换槽时
 * 经 mainHandler 延后回收旧位图）。
 *
 * - [WallpaperBackdrop]：MainScreen 根背景（Scaffold 之下）。按当前深浅模式（isSystemInDarkTheme）
 *   → [WallpaperStore.effectiveSlot] 取槽 → [rememberSlotBitmap] 异步解码（produceState +
 *   Dispatchers.IO + BitmapFactory downsample 防 OOM）→ [WallpaperEffect] 绘制「壁纸 + surfaceVariant
 *   遮罩（mask%）」。无壁纸时不绘制任何内容（露出底层纯色 background，维持现状）；
 * - [tick]（BluelinkUiState.wallpaperTick）为刷新信号：槽/遮罩改动后 +1 触发重读 store 并重绘。
 *   解码以 slot（type+uri）为 produceState key——槽未变（仅遮罩/强调色变化）时不重复解码；
 * - 预览（个性化页预览块/槽缩略）与本文件同套解码/效果函数，保证预览 = 真实背景效果。
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
    // isSystemInDarkTheme() 为 Compose 状态：系统深浅切换自动重取槽（本版无手动模式开关）
    val dark = isSystemInDarkTheme()
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
 * 透明度 = maskAlpha%（0–80，滑块上限）。
 */
@Composable
internal fun WallpaperEffect(
    wallpaper: ImageBitmap?,
    maskAlpha: Int,
    modifier: Modifier = Modifier,
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
        }
    }
}

/**
 * 槽壁纸异步解码（produceState + IO 线程 decode；换槽/清槽自动重启）。
 * key = slot（type+uri data class equals）→ 槽未变不重复解码；旧位图值先置空，
 * 再经 mainHandler 延后回收（待当前帧绘完，避免回收仍被引用/绘制的位图）。
 */
@Composable
internal fun rememberSlotBitmap(
    context: Context,
    slot: WallpaperSlot,
    maxDim: Int,
): ImageBitmap? {
    return produceState<ImageBitmap?>(initialValue = null, key1 = slot) {
        val old = value
        if (!slot.isSet) {
            value = null
            recycleLater(old)
            return@produceState
        }
        val decoded = withContext(Dispatchers.IO) {
            decodeSlotWallpaper(context.applicationContext, slot, maxDim)
        }
        value = decoded?.asImageBitmap()
        recycleLater(old)
    }.value
}

/** 按槽来源解码（自选 uri → [decodeUriWallpaper]；跟系统壁纸 → [decodeSystemWallpaper]；异常 → null）。 */
private fun decodeSlotWallpaper(context: Context, slot: WallpaperSlot, maxDim: Int): Bitmap? = try {
    when (slot.type) {
        WallpaperSlot.TYPE_SYSTEM -> decodeSystemWallpaper(context, maxDim)
        WallpaperSlot.TYPE_URI -> slot.uri?.let { decodeUriWallpaper(context, Uri.parse(it), maxDim) }
        else -> null
    }
} catch (t: Throwable) {
    null
}

/**
 * 自选 uri（SAF content://）解码：先 inJustDecodeBounds 读宽高 → inSampleSize（2 幂 downsample，
 * 防 OOM）解码 → 长边超过 maxDim 再精确收边。decode 行均在 Dispatchers.IO（调用方保证）。
 */
private fun decodeUriWallpaper(context: Context, uri: Uri, maxDim: Int): Bitmap? {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
    val w = bounds.outWidth
    val h = bounds.outHeight
    if (w <= 0 || h <= 0) return null
    var sample = 1
    while (maxOf(w, h) / (sample * 2) >= maxDim) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    val raw = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
    val scaled = scaleToMax(raw, maxDim)
    if (scaled !== raw) raw.recycle()
    return scaled
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
