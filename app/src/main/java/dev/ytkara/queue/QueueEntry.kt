package dev.ytkara.queue

import kotlinx.serialization.Serializable

@Serializable
enum class EntryStatus { RESOLVING, READY, ERROR }

@Serializable
data class QueueEntry(
    val id: String,
    val url: String,
    val title: String = "Loading…",
    val uploader: String? = null,
    val thumb: String? = null,
    val durationSec: Long = 0,
    val singer: String = "",
    /** Semitones, applied when the song starts. */
    val presetKey: Int = 0,
    val status: EntryStatus = EntryStatus.RESOLVING,
    val error: String? = null,
)

/** What a URL resolves to before it is ever played. */
data class VideoMeta(
    val title: String,
    val uploader: String?,
    val thumb: String?,
    val durationSec: Long,
)

@Serializable
data class QueueState(
    /** Bumped on every change to [nowPlaying] or [queue]; clients poll with it. */
    val version: Long = 0,
    val nowPlaying: QueueEntry? = null,
    val queue: List<QueueEntry> = emptyList(),
) {
    fun firstReady(): QueueEntry? = queue.firstOrNull { it.status == EntryStatus.READY }
}
