package app.wayfinder.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import androidx.core.content.res.ResourcesCompat
import app.wayfinder.R
import app.wayfinder.data.LineInfo
import app.wayfinder.data.TransitRoutes

/** Renders each line's number badge (line colour, line number) as a ready-made map image. */
class LinePills(private val context: Context) {
    /** Small LRU cache: badges are cheap to redraw, memory is not. */
    private val cache = object : LinkedHashMap<String, Bitmap>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?) = size > 600
    }
    private val density = context.resources.displayMetrics.density
    private val dpi = context.resources.displayMetrics.densityDpi

    private val typeface: Typeface by lazy {
        val base = runCatching { ResourcesCompat.getFont(context, R.font.google_sans_flex_rounded) }.getOrNull()
            ?: Typeface.DEFAULT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Typeface.create(base, 750, false)
        else Typeface.create(base, Typeface.BOLD)
    }

    /** Bitmaps for [lines] that the current style does not have yet, keyed by image name. */
    fun imagesFor(lines: List<LineInfo>): HashMap<String, Bitmap> {
        val out = HashMap<String, Bitmap>()
        for (line in lines) {
            val id = TransitRoutes.pillId(line.key)
            out[id] = cache.getOrPut(id) { render(line) }
        }
        return out
    }

    private fun render(line: LineInfo): Bitmap {
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = this@LinePills.typeface
            textSize = 11.5f * density
            color = line.textColor
        }
        val padX = 6f * density
        val padY = 3f * density
        val stroke = 1.5f * density
        val fm = text.fontMetrics
        val w = (text.measureText(line.ref) + padX * 2 + stroke * 2).toInt().coerceAtLeast((20 * density).toInt())
        val h = (fm.descent - fm.ascent + padY * 2 + stroke * 2).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { this.density = dpi }
        val c = Canvas(bmp)
        val rect = RectF(stroke / 2, stroke / 2, w - stroke / 2, h - stroke / 2)
        val radius = 6f * density
        c.drawRoundRect(rect, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = line.color })
        c.drawRoundRect(rect, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE; style = Paint.Style.STROKE; strokeWidth = stroke
        })
        val tw = text.measureText(line.ref)
        c.drawText(line.ref, (w - tw) / 2f, h / 2f - (fm.ascent + fm.descent) / 2f, text)
        return bmp
    }
}
