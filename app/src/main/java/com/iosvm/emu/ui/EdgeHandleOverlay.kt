package com.iosvm.emu.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

data class MenuAction(val label: String, val active: Boolean = false, val onClick: () -> Unit)

/**
 * Floating edge handle: drag it anywhere along (and across) the screen edges, tap to expand the
 * quick-action column. It snaps to whichever side it is released nearest to.
 */
@Composable
fun EdgeHandleMenu(actions: List<MenuAction>, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    var onRight by remember { mutableStateOf(true) }
    var offsetY by remember { mutableFloatStateOf(240f) }
    var dragX by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current

    BoxWithConstraints(modifier.fillMaxSize()) {
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }

        val handle = @Composable {
            Column(
                Modifier
                    .width(18.dp).height(84.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .alpha(if (expanded) 0.95f else 0.55f)
                    .pointerInput(Unit) { detectTapGestures(onTap = { expanded = !expanded }) }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDrag = { change, d ->
                                change.consume()
                                offsetY = (offsetY + d.y).coerceIn(0f, heightPx - 260f)
                                dragX += d.x
                            },
                            onDragEnd = {
                                val cx = (if (onRight) widthPx else 0f) + dragX
                                onRight = cx > widthPx / 2f
                                dragX = 0f
                            },
                        )
                    },
            ) {}
        }
        val menu = @Composable {
            if (expanded) {
                Column(
                    Modifier.padding(horizontal = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    actions.forEach { a ->
                        FilledTonalButton(
                            onClick = { a.onClick() },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                            colors = if (a.active) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors(),
                        ) { Text(a.label) }
                    }
                }
            }
        }

        Row(
            Modifier
                .align(if (onRight) Alignment.TopEnd else Alignment.TopStart)
                .offset { IntOffset(dragX.roundToInt(), offsetY.roundToInt()) },
            verticalAlignment = Alignment.Top,
        ) {
            if (onRight) { menu(); handle() } else { handle(); menu() }
        }
    }
}
