package com.harsh.peakbud.ui.theme

import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import kotlin.math.*

/** Colours for one pin: the dot and stick, the name half and the height half of the pill. */
data class PinStyle(val stick: Color, val dot: Color, val nameSurface: Color, val nameText: Color,
                    val heightSurface: Color, val heightText: Color, val ring: Color? = null)

/** A pin that won its place: where it is and the area that responds to a tap. */
class PlacedPin<T>(val item: T, val x: Float, val y: Float, val hit: RectF)

/**
 * Still, simple peak markers: a dot on the summit, a vertical stick of fixed length, and a pill tilted up and to the
 * right carrying the name and height. Nothing slides between rows; when two pins would collide, the less important one
 * is simply not labelled. Near the right edge the pill tilts up and to the left instead so it stays on screen.
 */
object PeakPins {
    private val stickLength = 56.dp
    private val pillHeight = 26.dp
    private val padding = 10.dp
    private const val TILT = 55f

    /** Keeps pins in the given priority order whose sticks are at least the pill's thickness apart horizontally. */
    fun <T> place(scope: DrawScope, candidates: List<Triple<T, Float, Float>>, maxPins: Int = 12): List<Triple<T, Float, Float>> = with(scope) {
        // Parallel tilted pills clear each other when their sticks are one pill-thickness apart along the normal.
        val gap = pillHeight.toPx() / sin(Math.toRadians(TILT.toDouble())).toFloat() + 4.dp.toPx()
        val kept = ArrayList<Triple<T, Float, Float>>()
        for (c in candidates) {
            if (kept.size >= maxPins) break
            if (kept.none { abs(it.second - c.second) < gap }) kept += c
        }
        kept
    }

    /** Draws one pin with its dot at ([x], [y]); [top] is the highest y the pill may reach. Returns the tap area. */
    fun draw(scope: DrawScope, x: Float, y: Float, name: String, height: String?, style: PinStyle, textSizePx: Float, top: Float): RectF = with(scope) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = textSizePx; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
        val pad = padding.toPx(); val thick = pillHeight.toPx()
        val nameWidth = paint.measureText(name) + pad * 2
        val heightWidth = height?.let { paint.measureText(it) + pad * 2 } ?: 0f
        val length = nameWidth + heightWidth
        val angle = Math.toRadians(TILT.toDouble())
        val rise = (length * sin(angle) + thick * cos(angle) / 2).toFloat()
        // Fixed stick, shortened only if the pill would leave the top of the view; it always stays vertical.
        val stickTop = max(y - stickLength.toPx(), min(y - 8.dp.toPx(), top + rise))
        drawLine(style.stick, Offset(x, y), Offset(x, stickTop), 1.5.dp.toPx())
        drawCircle(Color.Black.copy(alpha = .25f), 5.5.dp.toPx(), Offset(x, y))
        drawCircle(style.dot, 4.dp.toPx(), Offset(x, y))
        style.ring?.let { drawCircle(it, 6.dp.toPx(), Offset(x, y), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx())) }

        val flip = x + length * cos(angle).toFloat() > size.width - 4.dp.toPx()
        val canvas = drawContext.canvas.nativeCanvas
        canvas.save()
        if (!flip) { canvas.translate(x, stickTop); canvas.rotate(-TILT) }
        else {
            // Pill points up-left; text still reads left to right, ending at the stick.
            canvas.translate(x - length * cos(angle).toFloat(), stickTop - length * sin(angle).toFloat()); canvas.rotate(TILT)
        }
        val r = thick / 2
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        // Name half then height half, as one rounded pill.
        val path = android.graphics.Path().apply { addRoundRect(RectF(0f, -r, length, r), r, r, android.graphics.Path.Direction.CW) }
        canvas.save(); canvas.clipPath(path)
        fill.color = style.nameSurface.toArgb(); canvas.drawRect(0f, -r, nameWidth, r, fill)
        if (height != null) { fill.color = style.heightSurface.toArgb(); canvas.drawRect(nameWidth, -r, length, r, fill) }
        canvas.restore()
        val baseline = -(paint.ascent() + paint.descent()) / 2
        paint.color = style.nameText.toArgb(); canvas.drawText(name, pad, baseline, paint)
        if (height != null) { paint.color = style.heightText.toArgb(); canvas.drawText(height, nameWidth + pad, baseline, paint) }
        canvas.restore()

        // Axis-aligned bounds of the tilted pill plus the dot, for taps.
        val dx = (length * cos(angle)).toFloat(); val dy = (length * sin(angle)).toFloat()
        val left = if (flip) x - dx else x; val right = if (flip) x else x + dx
        return RectF(left - r, stickTop - dy - r, right + r, y + 8.dp.toPx())
    }
}
