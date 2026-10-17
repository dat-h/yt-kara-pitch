package dev.ytkara.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import dev.ytkara.player.Key
import dev.ytkara.queue.EntryStatus
import dev.ytkara.queue.QueueEntry

@Composable
fun IdleScreen(url: String?, queue: List<QueueEntry>, onSearch: () -> Unit) {
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { searchFocus.requestFocus() }
    Row(
        Modifier.fillMaxSize().background(Kara.Background).padding(horizontal = 56.dp, vertical = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Karaoke", color = Kara.Text, fontSize = 52.sp, fontWeight = FontWeight.Black)
            Box(Modifier.padding(top = 6.dp).width(120.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Kara.Accent))
            Spacer(Modifier.height(28.dp))
            Text(
                "Search on the TV, or add from your phone",
                color = Kara.Text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(14.dp))
            FocusPill("🔍  Search songs", selected = true, modifier = Modifier.focusRequester(searchFocus), onClick = onSearch)
            Spacer(Modifier.height(14.dp))
            Text("On your phone: scan the code, or open", color = Kara.TextDim, fontSize = 18.sp)
            Text(url ?: "No network", color = Kara.Orange, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(28.dp))
            UpNextList(queue)
        }
        if (url != null) {
            val qr = remember(url) { qrBitmap(url) }
            Image(qr, contentDescription = "QR code for $url", Modifier.size(260.dp).clip(RoundedCornerShape(16.dp)))
        }
    }
}

@Composable
private fun UpNextList(queue: List<QueueEntry>) {
    if (queue.isEmpty()) return
    Text("UP NEXT", color = Kara.TextDim, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
    Spacer(Modifier.height(8.dp))
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        queue.take(5).forEachIndexed { i, e ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", color = Kara.Pink, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(28.dp))
                Text(
                    buildString {
                        if (e.singer.isNotEmpty()) append(e.singer).append(" · ")
                        append(if (e.status == EntryStatus.RESOLVING) "Getting ready…" else e.title)
                    },
                    color = if (e.status == EntryStatus.ERROR) Kara.TextDim else Kara.Text,
                    fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                if (e.presetKey != 0) KeyChip(e.presetKey, Modifier.padding(start = 10.dp))
            }
        }
        if (queue.size > 5) Text("+ ${queue.size - 5} more", color = Kara.TextDim, fontSize = 16.sp)
    }
}

@Composable
fun KeyChip(key: Int, modifier: Modifier = Modifier, big: Boolean = false) {
    Text(
        Key.label(key),
        color = Kara.Text,
        fontSize = if (big) 22.sp else 14.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Kara.SurfaceHigh)
            .padding(horizontal = if (big) 18.dp else 10.dp, vertical = if (big) 6.dp else 3.dp),
    )
}

@Composable
fun UpNextCard(entry: QueueEntry, secondsLeft: Int) {
    Column(
        Modifier.fillMaxSize().background(Kara.Background).padding(56.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("UP NEXT", color = Kara.Pink, fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
        Spacer(Modifier.height(12.dp))
        Text(
            entry.singer.ifEmpty { "Next singer" },
            color = Kara.Text, fontSize = 72.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
        Text(entry.title, color = Kara.TextDim, fontSize = 26.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(20.dp))
        KeyChip(entry.presetKey, big = true)
        Spacer(Modifier.height(36.dp))
        Text("Starting in $secondsLeft", color = Kara.Orange, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text("Press OK to start now", color = Kara.TextDim, fontSize = 16.sp)
    }
}

/** The strip across the top of the video, scrolling the next [entries] (up to 3) in the queue. */
@Composable
fun UpNextTicker(entries: List<QueueEntry>, modifier: Modifier = Modifier) {
    val text = entries.take(3).withIndex().joinToString(TICKER_GAP) { (i, e) ->
        buildString {
            append(i + 1).append(". ")
            if (e.singer.isNotEmpty()) append(e.singer).append(" — ")
            append(e.title)
            if (e.presetKey != 0) append(" (").append(Key.label(e.presetKey)).append(")")
        }
    }
    Row(
        modifier
            .fillMaxWidth()
            .background(Kara.Background.copy(alpha = 0.78f))
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "UP NEXT", color = Kara.Pink, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
            modifier = Modifier.padding(start = 32.dp, end = 16.dp),
        )
        Text(
            text,
            color = Kara.Text, fontSize = 18.sp, maxLines = 1,
            modifier = Modifier
                .weight(1f)
                .basicMarquee(iterations = Int.MAX_VALUE, spacing = MarqueeSpacing(120.dp), velocity = 50.dp),
        )
    }
}

private const val TICKER_GAP = "      ♪      "

/** Centered pill for key changes and notices. */
@Composable
fun Toast(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = Kara.Text,
        fontSize = 28.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Kara.Surface.copy(alpha = 0.92f))
            .padding(horizontal = 28.dp, vertical = 12.dp),
    )
}

@Composable
fun LoadingCard(entry: QueueEntry) {
    Column(
        Modifier.fillMaxSize().background(Kara.Background),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(entry.singer.ifEmpty { "Here we go" }, color = Kara.Text, fontSize = 48.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(8.dp))
        Text("Loading “${entry.title}”…", color = Kara.TextDim, fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Slides up from the bottom for a few seconds when someone queues a song. */
@Composable
fun AddedBanner(entry: QueueEntry, position: Int, modifier: Modifier = Modifier) {
    Row(
        modifier
            .padding(bottom = 32.dp)
            .width(620.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Kara.Surface.copy(alpha = 0.95f))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = entry.thumb,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .width(128.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(10.dp))
                .background(Kara.SurfaceHigh),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (position > 0) "ADDED TO QUEUE · #$position" else "ADDED TO QUEUE",
                color = Kara.Pink, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
            )
            Text(entry.title, color = Kara.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entry.singer.ifEmpty { "No name" }, color = Kara.TextDim, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (entry.presetKey != 0) KeyChip(entry.presetKey, Modifier.padding(start = 10.dp))
            }
        }
    }
}
