package dev.ytkara

import android.util.Log
import dev.ytkara.extract.StreamResolver
import dev.ytkara.player.KaraPlayer
import dev.ytkara.player.Outcome
import dev.ytkara.player.PlayerSnapshot
import dev.ytkara.queue.QueueEntry
import dev.ytkara.queue.QueueRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

sealed interface Phase {
    data object Idle : Phase
    data class UpNext(val entry: QueueEntry, val secondsLeft: Int) : Phase
    data class Playing(val entry: QueueEntry) : Phase
}

/**
 * Runs the night: waits for a ready song, counts it in, plays it, and moves on. The activity
 * attaches a [KaraPlayer] while it is on screen; with none attached the session waits.
 * Lives on the main thread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KaraSession(val repo: QueueRepository, private val scope: CoroutineScope) {

    private val _phase = MutableStateFlow<Phase>(Phase.Idle)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    private val attached = MutableStateFlow<KaraPlayer?>(null)

    val player: StateFlow<PlayerSnapshot> = attached
        .flatMapLatest { it?.snapshot ?: flowOf(PlayerSnapshot()) }
        .stateIn(scope, SharingStarted.Eagerly, PlayerSnapshot())

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Short notices for the TV screen. */
    val messages: SharedFlow<String> = _messages

    private val startNow = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch { loop() }
    }

    fun attach(player: KaraPlayer) {
        attached.value = player
    }

    fun detach() {
        attached.value = null
    }

    /** OK on the remote during the up-next countdown. */
    fun startNow() {
        startNow.trySend(Unit)
    }

    /** Singer names in the queue, the most recently queued first, then the one singing now. */
    fun recentSingers(limit: Int = 6): List<String> {
        val s = repo.state.value
        return (s.queue.asReversed().map { it.singer } + listOfNotNull(s.nowPlaying?.singer))
            .filter { it.isNotEmpty() }
            .distinct()
            .take(limit)
    }

    fun notify(message: String) {
        _messages.tryEmit(message)
    }

    private suspend fun loop() {
        while (true) {
            val player = attached.filterNotNull().first()
            repo.state.first { it.firstReady() != null }
            if (!countIn() || attached.value !== player) continue
            val entry = repo.startNext() ?: continue
            val outcome = play(player, entry)
            if (outcome == Outcome.Released) repo.requeueNowPlaying() else repo.finishNowPlaying()
            _phase.value = Phase.Idle
        }
    }

    /** The 5 s "Up next" card. False when there is nothing left to start or the player went away. */
    private suspend fun countIn(): Boolean {
        while (startNow.tryReceive().isSuccess) Unit // drop a stale press
        for (left in COUNT_IN_SECONDS downTo 1) {
            val next = repo.state.value.firstReady()
            if (next == null || attached.value == null) {
                _phase.value = Phase.Idle
                return false
            }
            _phase.value = Phase.UpNext(next, left)
            if (withTimeoutOrNull(1_000) { startNow.receive() } != null) break
        }
        return true
    }

    private suspend fun play(player: KaraPlayer, entry: QueueEntry): Outcome {
        _phase.value = Phase.Playing(entry)
        var key = entry.presetKey
        var startMs = 0L
        for (attempt in 0..1) {
            val streams = try {
                withContext(Dispatchers.IO) { StreamResolver.streams(entry.url) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "resolve failed for ${entry.url}", e)
                notify("Couldn't load “${entry.title}”. Skipping it.")
                return Outcome.Error(null, 0, e.message)
            }
            if (attached.value !== player) return Outcome.Released
            val outcome = player.play(streams, key, startMs)
            if (outcome is Outcome.Error) {
                Log.w(TAG, "playback failed for ${entry.url}: ${outcome.httpCode} ${outcome.message}")
                if (attempt == 0 && outcome.httpCode in RETRY_CODES) {
                    key = player.currentKey
                    startMs = outcome.positionMs
                    continue
                }
                notify("Couldn't play “${entry.title}”. Skipping it.")
            }
            return outcome
        }
        return Outcome.Error(null, 0, null)
    }

    companion object {
        private const val TAG = "ytkara"
        const val COUNT_IN_SECONDS = 5
        private val RETRY_CODES = setOf(403, 410)
    }
}
