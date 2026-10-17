package dev.ytkara.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key as KeyCode
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.ytkara.extract.SearchResult
import dev.ytkara.player.Key

/**
 * Who's singing and in which key, with as few remote presses as possible: OK on a singer, OK on
 * Add. Names can be fixed later from a phone.
 */
@Composable
fun QuickAddDialog(
    result: SearchResult,
    singers: List<String>,
    onAdd: (singer: String, key: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var singer by remember { mutableStateOf("") }
    var key by remember { mutableIntStateOf(0) }
    val firstChip = remember { FocusRequester() }
    val addButton = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstChip.requestFocus() }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .width(620.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Kara.Surface)
                .padding(28.dp),
        ) {
            Text("Add to queue", color = Kara.Pink, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Spacer(Modifier.height(6.dp))
            Text(result.title, color = Kara.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)

            Spacer(Modifier.height(20.dp))
            Text("Who's singing?", color = Kara.TextDim, fontSize = 15.sp)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                (singers + "").forEachIndexed { i, name ->
                    FocusPill(
                        name.ifEmpty { "No name" },
                        selected = singer == name,
                        modifier = if (i == 0) Modifier.focusRequester(firstChip) else Modifier,
                    ) {
                        singer = name
                        addButton.requestFocus()
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Text("Key", color = Kara.TextDim, fontSize = 15.sp)
            Spacer(Modifier.height(8.dp))
            KeyStepper(key) { key = Key.clamp(it) }

            Spacer(Modifier.height(26.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FocusPill("Add", selected = true, modifier = Modifier.focusRequester(addButton)) { onAdd(singer, key) }
                FocusPill("Cancel", selected = false, onClick = onDismiss)
            }
        }
    }
}

/** ◀ Key +2 ▶ — Left/Right change it while focused. */
@Composable
private fun KeyStepper(value: Int, onChange: (Int) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    KeyCode.DirectionLeft -> { onChange(value - 1); true }
                    KeyCode.DirectionRight -> { onChange(value + 1); true }
                    else -> false
                }
            }
            .focusable()
            .clip(shape)
            .background(Kara.SurfaceHigh)
            .then(if (focused) Modifier.border(3.dp, Kara.Accent, shape) else Modifier)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("◀", color = if (value > Key.MIN) Kara.Text else Kara.SurfaceHigh, fontSize = 18.sp)
        Text(
            Key.label(value),
            color = Kara.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 18.dp),
        )
        Text("▶", color = if (value < Key.MAX) Kara.Text else Kara.SurfaceHigh, fontSize = 18.sp)
        if (focused) Text("  ← → to change", color = Kara.TextDim, fontSize = 13.sp)
    }
}
