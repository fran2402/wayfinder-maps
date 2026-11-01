package app.wayfinder.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import app.wayfinder.ui.Ph
import org.maplibre.android.maps.ImageContent
import org.maplibre.android.maps.ImageStretches
import org.maplibre.android.maps.Style

/** Renders Phosphor icons into map sprites (POI badges, station badges, destination pin). */
object MapIcons {

    private val poiIcons: Map<String, () -> ImageVector> = mapOf(
        "food" to { Ph.Filled.ForkKnife },
        "shop" to { Ph.Filled.ShoppingBag },
        "nature" to { Ph.Filled.Tree },
        "health" to { Ph.Filled.FirstAid },
        "culture" to { Ph.Filled.MaskHappy },
        "worship" to { Ph.Filled.Church },
        "education" to { Ph.Filled.GraduationCap },
        "lodging" to { Ph.Filled.Bed },
        "money" to { Ph.Filled.Bank },
        "fuel" to { Ph.Filled.GasPump },
        "default" to { Ph.Filled.Star },
    )

    private val stations: Map<String, Pair<() -> ImageVector, String>> = mapOf(
        "station-train" to ({ Ph.Filled.Train } to "#3D4A5C"),
        "station-subway" to ({ Ph.Filled.Subway } to "#1E63E9"),
        "station-tram" to ({ Ph.Filled.Tram } to "#E8473F"),
        "station-bus" to ({ Ph.Filled.Bus } to "#7E57C2"),
        "station-ferry" to ({ Ph.Filled.Boat } to "#0091D5"),
    )

    fun install(context: Context, style: Style, palette: MapPalette) {
        val density = context.resources.displayMetrics.density
        val dpi = context.resources.displayMetrics.densityDpi
        val images = HashMap<String, Bitmap>()
        poiIcons.forEach { (group, icon) ->
            val color = parse(MapStyle.poiColorsLight.getValue(group))
            images["poi-$group"] = badge(icon(), color, density, dpi, round = true)
        }
        stations.forEach { (name, def) ->
            images[name] = badge(def.first(), parse(def.second), density, dpi, round = false)
        }
        images["pin-destination"] = pin(parse(palette.destination), density, dpi)
        style.addImages(images)
        installLinePill(style, density, dpi)
    }

    /**
     * A stretchable rounded rectangle, added as an SDF so the style can tint it per line
     * (`icon-color`) and size it around the line number (`icon-text-fit`).
     */
    private fun installLinePill(style: Style, density: Float, dpi: Int) {
        val radius = 7f * density
        val w = (radius * 2 + 4 * density).toInt()
        val h = (radius * 2 + 4 * density).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { this.density = dpi }
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.BLACK }
        c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), radius, radius, paint)
        val stretchX = listOf(ImageStretches(radius, w - radius))
        val stretchY = listOf(ImageStretches(radius, h - radius))
        val content = ImageContent(radius * 0.6f, radius * 0.4f, w - radius * 0.6f, h - radius * 0.4f)
        style.addImage("line-pill", bmp, true, stretchX, stretchY, content)
    }

    private fun parse(hex: String) = android.graphics.Color.parseColor(hex)

    private fun badge(icon: ImageVector, color: Int, density: Float, dpi: Int, round: Boolean): Bitmap {
        val size = (22 * density).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply { this.density = dpi }
        val c = Canvas(bmp)
        val border = 1.6f * density
        val rect = RectF(border / 2, border / 2, size - border / 2, size - border / 2)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = android.graphics.Color.WHITE; style = Paint.Style.STROKE; strokeWidth = border
        }
        val radius = if (round) size / 2f else 6.5f * density
        c.drawRoundRect(rect, radius, radius, fill)
        c.drawRoundRect(rect, radius, radius, stroke)
        val iconSize = 13 * density
        drawVector(c, icon, (size - iconSize) / 2f, (size - iconSize) / 2f, iconSize, android.graphics.Color.WHITE)
        return bmp
    }

    private fun pin(color: Int, density: Float, dpi: Int): Bitmap {
        val size = (46 * density).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply { this.density = dpi }
        val c = Canvas(bmp)
        // White disc behind the pin's hole, then the pin with a soft shadow.
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = android.graphics.Color.WHITE }
        c.drawCircle(size / 2f, size * 0.42f, size * 0.17f, dot)
        drawVector(c, Ph.Filled.MapPin, 0f, 0f, size.toFloat(), color, shadow = 3f * density)
        return bmp
    }

    private fun drawVector(c: Canvas, icon: ImageVector, x: Float, y: Float, size: Float, color: Int, shadow: Float = 0f) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
            if (shadow > 0f) setShadowLayer(shadow, 0f, shadow / 2, 0x55000000)
        }
        c.save()
        c.translate(x, y)
        c.scale(size / icon.viewportWidth, size / icon.viewportHeight)
        drawGroup(c, icon.root, paint)
        c.restore()
    }

    private fun drawGroup(c: Canvas, group: VectorGroup, paint: Paint) {
        for (node in group) {
            when (node) {
                is VectorPath -> {
                    val path = PathParser().addPathNodes(node.pathData).toPath().asAndroidPath()
                    path.fillType = if (node.pathFillType == PathFillType.EvenOdd) {
                        android.graphics.Path.FillType.EVEN_ODD
                    } else {
                        android.graphics.Path.FillType.WINDING
                    }
                    c.drawPath(path, paint)
                }
                is VectorGroup -> drawGroup(c, node, paint)
            }
        }
    }
}
