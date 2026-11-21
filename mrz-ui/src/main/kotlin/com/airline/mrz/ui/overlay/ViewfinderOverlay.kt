package com.airline.mrz.ui.overlay

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.airline.mrz.ScanState
import com.airline.mrz.engine.Quad
import com.airline.mrz.ui.theme.MrzTheme

/**
 * Draws the passport guide (a TD3 page is 125 x 88 mm; the MRZ band sits in the bottom
 * quarter) and highlights the detected band. The band arrives in upright analysis-frame
 * coordinates; it is mapped onto the view assuming the preview is centre-cropped
 * (`PreviewView.ScaleType.FILL_CENTER`).
 */
@Composable
public fun ViewfinderOverlay(
    state: ScanState,
    theme: MrzTheme,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        val w = size.width
        val h = size.height
        // Guide: passport page in landscape, 125:88, centred, 90% of the width.
        val guideW = w * 0.9f
        val guideH = guideW * 88f / 125f
        val guide = Rect(Offset((w - guideW) / 2f, (h - guideH) / 2f), Size(guideW, guideH))

        drawRect(theme.scrim)
        drawRoundRect(
            color = Color.Transparent,
            topLeft = guide.topLeft,
            size = guide.size,
            cornerRadius = CornerRadius(16.dp.toPx()),
            blendMode = BlendMode.Clear,
        )
        val active = state is ScanState.Reading
        drawRoundRect(
            color = if (active) theme.guideActive else theme.guide,
            topLeft = guide.topLeft,
            size = guide.size,
            cornerRadius = CornerRadius(16.dp.toPx()),
            style = Stroke(width = 3.dp.toPx()),
        )
        // MRZ zone marker inside the guide: bottom 28% of the page.
        val mrzTop = guide.top + guide.height * 0.72f
        drawRect(
            color = (if (active) theme.guideActive else theme.guide).copy(alpha = 0.35f),
            topLeft = Offset(guide.left, mrzTop),
            size = Size(guide.width, guide.height * 0.28f),
            style = Stroke(width = 1.dp.toPx()),
        )

        val band: Quad? = when (state) {
            is ScanState.Aligning -> state.band
            is ScanState.Reading -> state.band
            else -> null
        }
        val frameW = when (state) {
            is ScanState.Aligning -> state.frameWidth
            is ScanState.Reading -> state.frameWidth
            else -> 0
        }
        val frameH = when (state) {
            is ScanState.Aligning -> state.frameHeight
            is ScanState.Reading -> state.frameHeight
            else -> 0
        }
        if (band != null && frameW > 0 && frameH > 0) {
            val scale = maxOf(w / frameW, h / frameH)
            val dx = (w - frameW * scale) / 2f
            val dy = (h - frameH * scale) / 2f
            val path = Path().apply {
                val c = band.corners
                moveTo(c[0].x * scale + dx, c[0].y * scale + dy)
                for (i in 1 until 4) lineTo(c[i].x * scale + dx, c[i].y * scale + dy)
                close()
            }
            val color = when (state) {
                is ScanState.Reading -> theme.bandReading
                is ScanState.Aligning -> theme.bandRejected
                else -> theme.bandSearching
            }
            drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
            if (state is ScanState.Reading) {
                val barW = guide.width * state.progress
                drawRect(theme.progress, Offset(guide.left, guide.bottom + 12.dp.toPx()), Size(barW, 4.dp.toPx()))
            }
        }
    }
}
