package dev.ytkara.queue

import dev.ytkara.player.Key
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * The in-memory queue. Safe to call from any thread: the web server mutates it from Ktor's
 * threads while the TV session reads and advances it on the main thread.
 */
class QueueRepository(
    private val scope: CoroutineScope,
    private val resolveMeta: suspend (url: String) -> VideoMeta,
    private val newId: () -> String = { UUID.randomUUID().toString().take(8) },
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(QueueState())
    val state: StateFlow<QueueState> = _state.asStateFlow()

    private val _added = MutableSharedFlow<QueueEntry>(extraBufferCapacity = 16)
    /** Each entry once it is READY: at once for search results, after the lookup for pasted links. */
    val added: SharedFlow<QueueEntry> = _added.asSharedFlow()

    private suspend inline fun <T> mutate(block: (QueueState) -> Pair<QueueState?, T>): T =
        mutex.withLock {
            val (next, result) = block(_state.value)
            if (next != null) {
                _state.value = next.copy(version = _state.value.version + 1)
            }
            result
        }

    /**
     * Adds [url] (already normalized). Without [meta] its title, thumbnail and length are resolved
     * in the background; with it (a search result already carries them) the entry is READY at once.
     */
    suspend fun add(url: String, singer: String?, key: Int?, meta: VideoMeta? = null): QueueEntry {
        val base = QueueEntry(
            id = newId(),
            url = url,
            singer = cleanSinger(singer),
            presetKey = Key.clamp(key ?: 0),
        )
        val entry = if (meta != null) base.resolvedWith(Result.success(meta)) else base
        mutate { s ->
            require(s.queue.size < MAX_QUEUE) { "The queue is full" }
            s.copy(queue = s.queue + entry) to Unit
        }
        if (meta != null) {
            _added.tryEmit(entry)
            return entry
        }
        scope.launch {
            val meta: Result<VideoMeta> = try {
                Result.success(resolveMeta(url))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Throwable, not Exception: a library linkage Error must fail the song, not the app.
                Result.failure(e)
            }
            val resolved = mutate { s ->
                val current = s.queue.firstOrNull { it.id == entry.id }
                    ?: return@mutate null to null // removed while resolving
                val updated = current.resolvedWith(meta)
                s.copy(queue = s.queue.map { if (it.id == entry.id) updated else it }) to updated
            }
            if (resolved?.status == EntryStatus.READY) _added.tryEmit(resolved)
        }
        return entry
    }

    suspend fun remove(id: String): Boolean = mutate { s ->
        if (s.queue.none { it.id == id }) null to false
        else s.copy(queue = s.queue.filterNot { it.id == id }) to true
    }

    /** Moves [id] to [index], clamped to the queue's bounds. */
    suspend fun move(id: String, index: Int): Boolean = mutate { s ->
        val from = s.queue.indexOfFirst { it.id == id }
        if (from < 0) return@mutate null to false
        val list = s.queue.toMutableList()
        val entry = list.removeAt(from)
        list.add(index.coerceIn(0, list.size), entry)
        s.copy(queue = list) to true
    }

    suspend fun update(id: String, singer: String?, key: Int?): QueueEntry? = mutate { s ->
        val old = s.queue.firstOrNull { it.id == id } ?: return@mutate null to null
        val new = old.copy(
            singer = if (singer != null) cleanSinger(singer) else old.singer,
            presetKey = if (key != null) Key.clamp(key) else old.presetKey,
        )
        s.copy(queue = s.queue.map { if (it.id == id) new else it }) to new
    }

    /** Takes the first READY entry off the queue and makes it the song now playing. */
    suspend fun startNext(): QueueEntry? = mutate { s ->
        val next = s.firstReady() ?: return@mutate null to null
        s.copy(nowPlaying = next, queue = s.queue.filterNot { it.id == next.id }) to next
    }

    /** The song now playing is over (finished, skipped or failed). */
    suspend fun finishNowPlaying() = mutate { s ->
        if (s.nowPlaying == null) null to Unit else s.copy(nowPlaying = null) to Unit
    }

    /** The song was interrupted (the app left the screen): put it back at the front. */
    suspend fun requeueNowPlaying() = mutate { s ->
        val np = s.nowPlaying ?: return@mutate null to Unit
        s.copy(nowPlaying = null, queue = listOf(np) + s.queue) to Unit
    }

    private fun QueueEntry.resolvedWith(meta: Result<VideoMeta>): QueueEntry = meta.fold(
        onSuccess = { m ->
            copy(title = m.title, uploader = m.uploader, thumb = m.thumb, durationSec = m.durationSec, status = EntryStatus.READY)
        },
        onFailure = { e ->
            copy(title = "Couldn't load this video", status = EntryStatus.ERROR, error = e.message ?: e.javaClass.simpleName)
        },
    )

    private fun cleanSinger(singer: String?) = singer.orEmpty().trim().take(MAX_SINGER)

    companion object {
        const val MAX_QUEUE = 200
        const val MAX_SINGER = 40
    }
}
