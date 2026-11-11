package dev.couchtv.ontv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * An app's own icon as a square PNG: the TV home's dock tiles (HomeActivity) and the phone
 * remote's app row (Server, /icon/<package>) draw the same picture.
 */
object AppArt {
    private val icons = ConcurrentHashMap<String, ByteArray>()

    fun icon(ctx: Context, pkg: String): ByteArray? {
        icons[pkg]?.let { return it }
        // The app's own icon at the highest density it ships (xxxhdpi: 192 px for a classic icon,
        // 432 px for an adaptive one), drawn at its own size and never enlarged: the home shows it
        // at 132 px on a 1080p TV, so it is only ever scaled down.
        val d = hiResIcon(ctx, pkg) ?: return null
        val src = (d as? android.graphics.drawable.BitmapDrawable)?.bitmap
        // A best icon that is not square (DirecTV ships a 1280x720 one at xxxhdpi, the same art as
        // its square icon) is cropped to a square from the middle, filling the tile like the square
        // icon does: never stretched, never shrunk into a band.
        val w = src?.width ?: d.intrinsicWidth.takeIf { it > 0 } ?: 192
        val h = src?.height ?: d.intrinsicHeight.takeIf { it > 0 } ?: 192
        val size = minOf(w, h).coerceIn(48, 512)
        val k = size.toFloat() / minOf(w, h)
        val dw = Math.round(w * k); val dh = Math.round(h * k)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        d.setBounds((size - dw) / 2, (size - dh) / 2, (size - dw) / 2 + dw, (size - dh) / 2 + dh); d.draw(Canvas(bmp))
        val bytes = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it); bmp.recycle() }.toByteArray()
        icons[pkg] = bytes
        return bytes
    }

    private fun hiResIcon(ctx: Context, pkg: String): Drawable? {
        val pm = ctx.packageManager
        try {
            val ai = pm.getApplicationInfo(pkg, 0)
            if (ai.icon != 0) {
                val res = pm.getResourcesForApplication(ai)
                @Suppress("DEPRECATION")
                res.getDrawableForDensity(ai.icon, android.util.DisplayMetrics.DENSITY_XXXHIGH, null)?.let { return it }
            }
        } catch (e: Exception) { /* fall back to what Android picks */ }
        return try { pm.getApplicationIcon(pkg) } catch (e: Exception) { null }
    }
}
