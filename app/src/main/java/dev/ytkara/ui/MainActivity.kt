package dev.ytkara.ui

import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import dev.ytkara.KaraApp
import dev.ytkara.KaraSession
import dev.ytkara.Phase
import dev.ytkara.net.LocalAddress
import dev.ytkara.player.Key
import dev.ytkara.player.KaraPlayer
import dev.ytkara.queue.EntryStatus
import dev.ytkara.queue.QueueEntry
import dev.ytkara.queue.VideoMeta
import dev.ytkara.server.KaraServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

class MainActivity : ComponentActivity() {
    private val session: KaraSession get() = (application as KaraApp).session
    private var player by mutableStateOf<KaraPlayer?>(null)
    private var searchOpen by mutableStateOf(false)

    private var forwardHeld = false
    private var lastBackAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keeps the Fire OS screensaver away on every screen, idle included.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            KaraRoot(session, player, searchOpen, onSearchOpen = { searchOpen = it })
        }
    }

    override fun onStart() {
        super.onStart()
        val p = KaraPlayer(this)
        player = p
        session.attach(p)
    }

    override fun onStop() {
        // Release the decoder and buffers whenever the app leaves the screen; a song cut off
        // this way goes back to the front of the queue.
        session.detach()
        player?.release()
        player = null
        super.onStop()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) searchOpen = true
            return true
        }
        // Search is a normal focus-driven screen: the remote navigates it instead of the player.
        if (searchOpen) return super.dispatchKeyEvent(event)
        val phase = session.phase.value
        val p = player
        if (phase is Phase.UpNext && event.keyCode in OK_KEYS) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) session.startNow()
            return true
        }
        if (phase !is Phase.Playing || p == null) return super.dispatchKeyEvent(event)

        val down = event.action == KeyEvent.ACTION_DOWN
        val first = down && event.repeatCount == 0
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> if (first) p.adjustKey(+1)
            KeyEvent.KEYCODE_DPAD_DOWN -> if (first) p.adjustKey(-1)
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> if (first) p.seekBy(-SEEK_MS)
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> when {
                first -> forwardHeld = false
                down && event.repeatCount >= HOLD_REPEATS && !forwardHeld -> {
                    forwardHeld = true
                    session.notify("Skipped")
                    p.skip()
                }
                !down && !forwardHeld -> p.seekBy(SEEK_MS)
            }
            in OK_KEYS -> if (first) p.togglePause()
            KeyEvent.KEYCODE_BACK -> if (first) {
                val now = SystemClock.uptimeMillis()
                if (now - lastBackAt < BACK_CONFIRM_MS) {
                    lastBackAt = 0
                    session.notify("Stopped")
                    p.skip()
                } else {
                    lastBackAt = now
                    session.notify("Press Back again to stop this song")
                }
            }
            else -> return super.dispatchKeyEvent(event)
        }
        return true
    }

    companion object {
        private const val SEEK_MS = 10_000L
        /** Key repeats arrive ~every 50 ms after the first 500 ms: about a 1 s hold. */
        private const val HOLD_REPEATS = 10
        private const val BACK_CONFIRM_MS = 3_000L
        private val OK_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
        )
    }
}

@Composable
private fun KaraRoot(
    session: KaraSession,
    player: KaraPlayer?,
    searchOpen: Boolean,
    onSearchOpen: (Boolean) -> Unit,
) {
    val phase by session.phase.collectAsState()
    val queue by session.repo.state.collectAsState()
    val snapshot by session.player.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current

    val url by produceState<String?>(null) {
        while (true) {
            value = LocalAddress.ipv4(context)?.let { "http://$it:${KaraServer.PORT}" }
            delay(15_000)
        }
    }

    var toast by remember { mutableStateOf<String?>(null) }

    // One banner at a time: songs queued in a burst each get their turn.
    var added by remember { mutableStateOf<QueueEntry?>(null) }
    var lastAdded by remember { mutableStateOf<QueueEntry?>(null) }
    LaunchedEffect(Unit) {
        session.repo.added.collect {
            added = it
            lastAdded = it
            delay(4_000)
            added = null
            delay(400) // let it slide out before the next one
        }
    }
    LaunchedEffect(player) {
        val keys = player?.keyChanges?.map { Key.label(it) }
        val all = if (keys != null) merge(keys, session.messages) else session.messages
        all.collectLatest {
            toast = it
            delay(2_000)
            toast = null
        }
    }

    Box(Modifier.fillMaxSize().background(Kara.Background)) {
        when (val p = phase) {
            Phase.Idle -> IdleScreen(url, queue.queue, onSearch = { onSearchOpen(true) })
            is Phase.UpNext -> UpNextCard(p.entry, p.secondsLeft)
            is Phase.Playing -> {
                if (player != null) VideoSurface(player)
                if (snapshot.durationMs == 0L) LoadingCard(p.entry)
                val upcoming = queue.queue.filter { it.status != EntryStatus.ERROR }
                if (upcoming.isNotEmpty()) UpNextTicker(upcoming, Modifier.align(Alignment.TopCenter))
                if (snapshot.paused && snapshot.durationMs > 0) Toast("Paused", Modifier.align(Alignment.Center))
            }
        }
        if (searchOpen) {
            SearchScreen(
                recentSingers = session::recentSingers,
                onAdd = { r, singer, key ->
                    try {
                        session.repo.add(r.url, singer, key, VideoMeta(r.title, r.channel, r.thumb, r.durationSec))
                    } catch (e: IllegalArgumentException) {
                        session.notify(e.message ?: "Couldn't add that song")
                    }
                },
                onClose = { onSearchOpen(false) },
            )
        }
        AnimatedVisibility(
            visible = added != null,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            // Keep drawing the last entry while the banner slides out.
            val shown = added ?: lastAdded
            if (shown != null) {
                AddedBanner(shown, position = queue.queue.indexOfFirst { it.id == shown.id } + 1)
            }
        }
        // Below the up-next ticker when one is showing.
        toast?.let { Toast(it, Modifier.align(Alignment.TopCenter).padding(top = 72.dp)) }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoSurface(player: KaraPlayer) {
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                isFocusable = false
            }
        },
        update = { it.player = player.exo },
        onRelease = { it.player = null },
        modifier = Modifier.fillMaxSize(),
    )
}
