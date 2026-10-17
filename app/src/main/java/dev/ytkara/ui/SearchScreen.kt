package dev.ytkara.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import dev.ytkara.extract.SearchResult
import dev.ytkara.extract.SearchService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface SearchState {
    data object Empty : SearchState
    data object Loading : SearchState
    data class Results(val items: List<SearchResult>) : SearchState
    data class Failed(val message: String) : SearchState
}

/**
 * Full-screen search on the TV, driven by the remote. Drawn over whatever is playing; Back closes
 * the add dialog first, then the screen.
 */
@Composable
fun SearchScreen(
    recentSingers: () -> List<String>,
    onAdd: suspend (SearchResult, singer: String, key: Int) -> Unit,
    onClose: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var karaoke by remember { mutableStateOf(true) }
    var state by remember { mutableStateOf<SearchState>(SearchState.Empty) }
    var picked by remember { mutableStateOf<SearchResult?>(null) }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val fieldFocus = remember { FocusRequester() }
    val firstResultFocus = remember { FocusRequester() }

    fun runSearch() {
        if (query.isBlank()) return
        keyboard?.hide()
        state = SearchState.Loading
        scope.launch {
            state = try {
                SearchState.Results(withContext(Dispatchers.IO) { SearchService.search(query, karaoke) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                SearchState.Failed("Search didn't work. Check the connection and try again.")
            }
        }
    }

    BackHandler(onBack = onClose)
    LaunchedEffect(Unit) {
        fieldFocus.requestFocus()
        keyboard?.show()
    }
    LaunchedEffect(state) {
        if ((state as? SearchState.Results)?.items?.isNotEmpty() == true) firstResultFocus.requestFocus()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Kara.Background)
            .padding(horizontal = 40.dp, vertical = 28.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Search songs", color = Kara.Text, fontSize = 30.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.weight(1f))
            Text("Back to close", color = Kara.TextDim, fontSize = 14.sp)
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            var fieldFocused by remember { mutableStateOf(false) }
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = TextStyle(color = Kara.Text, fontSize = 22.sp),
                cursorBrush = SolidColor(Kara.Pink),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                // The Fire TV keyboard labels its action key "Next"; whatever it sends, search.
                keyboardActions = KeyboardActions(
                    onSearch = { runSearch() }, onNext = { runSearch() }, onDone = { runSearch() },
                    onGo = { runSearch() }, onSend = { runSearch() },
                ),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(fieldFocus)
                    .onFocusChanged { fieldFocused = it.isFocused }
                    .clip(RoundedCornerShape(14.dp))
                    .background(Kara.Surface)
                    .border(2.dp, if (fieldFocused) Kara.Pink else Kara.SurfaceHigh, RoundedCornerShape(14.dp))
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                decorationBox = { inner ->
                    if (query.isEmpty()) Text("Song or artist", color = Kara.TextDim, fontSize = 22.sp)
                    inner()
                },
            )
            Spacer(Modifier.width(12.dp))
            FocusPill(if (karaoke) "+ karaoke  ✓" else "+ karaoke", selected = karaoke) {
                karaoke = !karaoke
                if (state is SearchState.Results) runSearch()
            }
            Spacer(Modifier.width(12.dp))
            FocusPill("Search", selected = false, onClick = ::runSearch)
        }
        Spacer(Modifier.height(18.dp))

        when (val s = state) {
            SearchState.Empty -> Hint("Type a song or artist, then press Next on the keyboard.")
            SearchState.Loading -> Hint("Searching…")
            is SearchState.Failed -> Column {
                Hint(s.message)
                Spacer(Modifier.height(12.dp))
                FocusPill("Retry", selected = false, onClick = ::runSearch)
            }
            is SearchState.Results -> if (s.items.isEmpty()) {
                Hint("No songs found. Try different words.")
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(6.dp),
                ) {
                    itemsIndexed(s.items, key = { _, r -> r.url }) { i, r ->
                        ResultCard(
                            r,
                            onClick = { picked = r },
                            modifier = if (i == 0) Modifier.focusRequester(firstResultFocus) else Modifier,
                        )
                    }
                }
            }
        }
    }

    picked?.let { result ->
        QuickAddDialog(
            result = result,
            singers = remember(result) { recentSingers() },
            onAdd = { singer, key ->
                scope.launch {
                    onAdd(result, singer, key)
                    picked = null
                }
            },
            onDismiss = { picked = null },
        )
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, color = Kara.TextDim, fontSize = 20.sp, modifier = Modifier.padding(top = 24.dp))
}

@Composable
private fun ResultCard(result: SearchResult, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "cardScale")
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier
            .scale(scale)
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(if (focused) Kara.SurfaceHigh else Kara.Surface)
            .then(if (focused) Modifier.border(3.dp, Kara.Accent, shape) else Modifier)
            .clickable(onClick = onClick),
    ) {
        Box {
            AsyncImage(
                model = result.thumb,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Kara.SurfaceHigh),
            )
            if (result.durationSec > 0) {
                Text(
                    formatDuration(result.durationSec),
                    color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Black.copy(alpha = 0.75f))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                result.title, color = Kara.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 18.sp,
            )
            result.channel?.let {
                Text(it, color = Kara.TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A focusable rounded button that lights up under the D-pad. */
@Composable
fun FocusPill(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Text(
        text,
        color = if (focused) Kara.Background else Kara.Text,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .then(
                when {
                    focused -> Modifier.background(Kara.Accent)
                    selected -> Modifier.background(Kara.SurfaceHigh).border(2.dp, Kara.Pink, shape)
                    else -> Modifier.background(Kara.SurfaceHigh)
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
    )
}

fun formatDuration(sec: Long): String = "%d:%02d".format(sec / 60, sec % 60)
