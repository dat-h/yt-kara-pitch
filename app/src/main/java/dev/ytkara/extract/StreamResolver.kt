package dev.ytkara.extract

import android.util.Log
import dev.ytkara.queue.VideoMeta
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream

/** Media URLs for one playback. [audioUrl] is null when [videoUrl] already carries the audio. */
data class Streams(val videoUrl: String, val audioUrl: String?, val description: String)

/** Blocking NewPipeExtractor calls; run them off the main thread. */
object StreamResolver {
    private const val TAG = "ytkara"
    private const val MAX_HEIGHT = 1080

    fun meta(url: String): VideoMeta {
        val info = StreamInfo.getInfo(ServiceList.YouTube, url)
        return VideoMeta(
            title = info.name,
            uploader = info.uploaderName,
            thumb = info.thumbnails.filter { it.height in 1..480 }.maxByOrNull { it.height }?.url
                ?: info.thumbnails.firstOrNull()?.url,
            durationSec = info.duration,
        )
    }

    /** Resolved fresh for every play: googlevideo URLs expire after a few hours. */
    fun streams(url: String): Streams {
        val info = StreamInfo.getInfo(ServiceList.YouTube, url)

        val video = pickVideoOnly(info.videoOnlyStreams)
        val audio = pickAudio(info.audioStreams)
        val streams = if (video != null && audio != null) {
            Streams(video.content, audio.content, "${video.height}p ${video.codec} + ${audio.codec ?: audio.format}")
        } else {
            val muxed = info.videoStreams
                .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.height <= MAX_HEIGHT }
                .maxByOrNull { it.height }
                ?: throw IllegalStateException("No playable stream")
            Streams(muxed.content, null, "${muxed.height}p muxed ${muxed.format}")
        }
        Log.i(TAG, "streams for $url: ${streams.description}")
        return streams
    }

    /** H.264 first (the stick's hardware decoder handles it best), then VP9, never AV1. */
    private fun pickVideoOnly(all: List<VideoStream>): VideoStream? {
        val usable = all.filter {
            it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.height in 1..MAX_HEIGHT
        }
        fun best(match: (VideoStream) -> Boolean) =
            usable.filter(match).maxWithOrNull(compareBy<VideoStream>({ it.height }, { it.fps }))
        return best { it.codec?.startsWith("avc1") == true || (it.codec == null && it.format == MediaFormat.MPEG_4) }
            ?: best { it.codec?.startsWith("vp9") == true || it.codec?.startsWith("vp09") == true }
    }

    /** The original-language track: AAC first, then Opus. */
    private fun pickAudio(all: List<AudioStream>): AudioStream? {
        val usable = all
            .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .filter { it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL }
            .ifEmpty { all.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP } }
        return usable.filter { it.format == MediaFormat.M4A }.maxByOrNull { it.averageBitrate }
            ?: usable.maxByOrNull { it.averageBitrate }
    }
}
