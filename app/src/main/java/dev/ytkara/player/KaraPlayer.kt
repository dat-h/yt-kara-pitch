package dev.ytkara.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import dev.ytkara.extract.Streams
import dev.ytkara.extract.YouTubeDataSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
data class PlayerSnapshot(
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val paused: Boolean = false,
    val buffering: Boolean = false,
    val key: Int = 0,
)

sealed interface Outcome {
    data object Ended : Outcome
    data object Skipped : Outcome
    /** The player went away (the app left the screen) mid-song. */
    data object Released : Outcome
    data class Error(val httpCode: Int?, val positionMs: Long, val message: String?) : Outcome
}

/** ExoPlayer for one song at a time, with the key in semitones. Main thread only. */
@OptIn(UnstableApi::class)
class KaraPlayer(context: Context) {
    val exo: ExoPlayer = ExoPlayer.Builder(context)
        // Small buffers: the stick has ~450 MB free.
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(15_000, 30_000, 2_000, 4_000)
                .build(),
        )
        .build()

    private val scope = MainScope()
    private val sourceFactory = ProgressiveMediaSource.Factory(YouTubeDataSource.Factory())
    private var current: CompletableDeferred<Outcome>? = null
    private var released = false

    private val _snapshot = MutableStateFlow(PlayerSnapshot())
    val snapshot: StateFlow<PlayerSnapshot> = _snapshot.asStateFlow()

    /** Emits the new key whenever the remote changes it, for the on-screen toast. */
    val keyChanges: SharedFlow<Int> get() = _keyChanges
    private val _keyChanges = MutableSharedFlow<Int>(extraBufferCapacity = 4)

    private var key = 0

    init {
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) current?.complete(Outcome.Ended)
                publish()
            }

            override fun onPlayerError(error: PlaybackException) {
                current?.complete(Outcome.Error(httpCodeOf(error), exo.currentPosition, error.message))
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) = publish()
        })
        scope.launch {
            while (isActive) {
                publish()
                delay(500)
            }
        }
    }

    /** Plays [streams] from [startMs] at [startKey] and suspends until the song is over. */
    suspend fun play(streams: Streams, startKey: Int, startMs: Long = 0): Outcome {
        check(!released)
        val done = CompletableDeferred<Outcome>()
        current = done
        exo.setMediaSource(sourceOf(streams))
        setKey(startKey, announce = false)
        exo.prepare()
        if (startMs > 0) exo.seekTo(startMs)
        exo.playWhenReady = true
        return try {
            done.await()
        } finally {
            current = null
            if (!released) {
                exo.stop()
                exo.clearMediaItems()
            }
            publish()
        }
    }

    private fun sourceOf(streams: Streams): MediaSource {
        val video = sourceFactory.createMediaSource(MediaItem.fromUri(streams.videoUrl))
        val audio = streams.audioUrl ?: return video
        return MergingMediaSource(video, sourceFactory.createMediaSource(MediaItem.fromUri(audio)))
    }

    val currentKey: Int get() = key

    fun setKey(semitones: Int, announce: Boolean = true) {
        key = Key.clamp(semitones)
        exo.playbackParameters = PlaybackParameters(1f, Key.pitchOf(key))
        if (announce) _keyChanges.tryEmit(key)
        publish()
    }

    fun adjustKey(delta: Int) = setKey(key + delta)

    fun togglePause() {
        exo.playWhenReady = !exo.playWhenReady
        publish()
    }

    fun seekBy(deltaMs: Long) {
        val duration = exo.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        exo.seekTo((exo.currentPosition + deltaMs).coerceIn(0, duration - 1_000))
        publish()
    }

    fun skip() {
        current?.complete(Outcome.Skipped)
    }

    fun release() {
        if (released) return
        current?.complete(Outcome.Released)
        released = true
        scope.cancel()
        exo.release()
    }

    private fun publish() {
        if (released) return
        _snapshot.value = PlayerSnapshot(
            positionMs = exo.currentPosition.coerceAtLeast(0),
            durationMs = exo.duration.coerceAtLeast(0),
            paused = !exo.playWhenReady,
            buffering = exo.playbackState == Player.STATE_BUFFERING,
            key = key,
        )
    }

    private fun httpCodeOf(error: Throwable): Int? {
        var t: Throwable? = error
        while (t != null) {
            if (t is HttpDataSource.InvalidResponseCodeException) return t.responseCode
            t = t.cause
        }
        return null
    }
}
