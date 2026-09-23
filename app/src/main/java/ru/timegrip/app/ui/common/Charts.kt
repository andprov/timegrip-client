package ru.timegrip.app.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import ru.timegrip.app.R
import ru.timegrip.app.domain.TimeSeriesPoint
import ru.timegrip.app.ui.theme.LocalExtraColors
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.roundToInt

data class DonutSlice(val key: String, val label: String, val value: Long, val color: Color)

/**
 * Share of time per project (web: components/ui/PieChart.tsx). Tapping a
 * segment dims the others and shows its name, value and share in the centre;
 * tapping it again or outside the ring goes back to the total.
 */
@Composable
fun DonutChart(
    slices: List<DonutSlice>,
    formatValue: (Long) -> String,
    centerLabel: String,
    modifier: Modifier = Modifier,
) {
    val total = slices.sumOf { it.value }.coerceAtLeast(1)
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    var selectedKey by remember(slices) { mutableStateOf<String?>(null) }
    val selected = slices.find { it.key == selectedKey }
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(slices) {
                    detectTapGestures { offset ->
                        val minSide = minOf(size.width, size.height).toFloat()
                        val stroke = minSide * 0.16f
                        val radius = (minSide - stroke) / 2
                        val dx = offset.x - size.width / 2f
                        val dy = offset.y - size.height / 2f
                        val distance = hypot(dx, dy)
                        // Angle clockwise from 12 o'clock, matching how the arcs are drawn.
                        val angle = (Math.toDegrees(atan2(dy, dx).toDouble()) + 90 + 360) % 360
                        var start = 0.0
                        val hit = if (abs(distance - radius) <= stroke / 2 + 8.dp.toPx()) {
                            slices.firstOrNull { slice ->
                                val sweep = 360.0 * slice.value / total
                                (angle >= start && angle < start + sweep).also { start += sweep }
                            }
                        } else {
                            null
                        }
                        selectedKey = if (hit == null || hit.key == selectedKey) null else hit.key
                    }
                },
        ) {
            val stroke = size.minDimension * 0.16f
            val diameter = size.minDimension - stroke
            val topLeft = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)
            val arcSize = Size(diameter, diameter)
            drawArc(track, 0f, 360f, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(stroke))
            val gap = if (slices.count { it.value > 0 } > 1) 1.5f else 0f
            var start = -90f
            for (slice in slices) {
                val sweep = 360f * slice.value / total
                if (sweep > gap) {
                    val alpha = if (selectedKey == null || selectedKey == slice.key) 1f else 0.35f
                    drawArc(slice.color.copy(alpha = alpha), start + gap / 2, sweep - gap, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(stroke))
                }
                start += sweep
            }
        }
        val caption = MaterialTheme.typography.labelMedium
        val captionColor = MaterialTheme.colorScheme.onSurfaceVariant
        Column(
            Modifier.fillMaxWidth(0.62f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (selected != null) {
                Text(selected.label, style = caption, color = captionColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(formatValue(selected.value), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("${(selected.value * 100.0 / total).roundToInt()}%", style = caption, color = captionColor)
            } else {
                Text(stringResource(R.string.total), style = caption, color = captionColor)
                Text(centerLabel, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * Time per hour/day/month (web: components/ui/BarChart.tsx). Scrolls
 * sideways when the bars do not fit; tapping a bar shows its value.
 */
@Composable
fun BarChart(points: List<TimeSeriesPoint>, formatValue: (Long) -> String, modifier: Modifier = Modifier) {
    val accent = LocalExtraColors.current.chartAccent
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = labelColor)
    val textMeasurer = rememberTextMeasurer()
    var selected by remember(points) { mutableStateOf<Int?>(null) }
    val maxValue = (points.maxOfOrNull { it.totalSeconds } ?: 0L).coerceAtLeast(1L)

    Column(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                selected?.let { "${points[it].label}: ${formatValue(points[it].totalSeconds)}" } ?: " ",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            Text("max ${formatValue(if (points.any { it.totalSeconds > 0 }) maxValue else 0)}", style = labelStyle)
        }
        Spacer(Modifier.height(8.dp))
        if (points.isEmpty()) return@Column
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val slot = max(maxWidth / points.size, 18.dp)
            val chartWidth = slot * points.size
            Canvas(
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .width(chartWidth)
                    .height(180.dp)
                    .pointerInput(points) {
                        detectTapGestures { offset ->
                            val index = (offset.x / slot.toPx()).toInt().coerceIn(points.indices)
                            selected = if (selected == index) null else index
                        }
                    },
            ) {
                val slotPx = slot.toPx()
                val labelBand = 20.dp.toPx()
                val plotHeight = size.height - labelBand
                for (fraction in listOf(0f, 0.5f, 1f)) {
                    val y = plotHeight * (1 - fraction)
                    drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                }
                val labelEvery = ceil(40.dp.toPx() / slotPx).toInt().coerceAtLeast(1)
                val barWidth = minOf(slotPx * 0.6f, 24.dp.toPx())
                points.forEachIndexed { index, point ->
                    val height = plotHeight * point.totalSeconds / maxValue
                    val x = index * slotPx + (slotPx - barWidth) / 2
                    if (height > 0) {
                        drawRoundRect(
                            color = if (selected == null || selected == index) accent else accent.copy(alpha = 0.45f),
                            topLeft = Offset(x, plotHeight - height),
                            size = Size(barWidth, height),
                            cornerRadius = CornerRadius(4.dp.toPx()),
                        )
                    }
                    if (index % labelEvery == 0) {
                        val measured = textMeasurer.measure(point.label, labelStyle)
                        drawText(
                            measured,
                            topLeft = Offset(
                                (index * slotPx + slotPx / 2 - measured.size.width / 2).coerceAtLeast(0f),
                                plotHeight + 4.dp.toPx(),
                            ),
                        )
                    }
                }
            }
        }
    }
}
