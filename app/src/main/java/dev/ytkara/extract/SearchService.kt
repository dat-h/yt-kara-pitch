package dev.ytkara.extract

import kotlinx.serialization.Serializable
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

@Serializable
data class SearchResult(
    val url: String,
    val title: String,
    val channel: String? = null,
    val thumb: String? = null,
    val durationSec: Long = 0,
)

/** YouTube video search. [search] blocks; run it off the main thread. */
object SearchService {
    private const val MAX_RESULTS = 20
    private const val MAX_DURATION_SEC = 15 * 60L

    fun search(query: String, addKaraoke: Boolean): List<SearchResult> {
        val handler = YoutubeSearchQueryHandlerFactory.getInstance()
            .fromQuery(buildQuery(query, addKaraoke), listOf(YoutubeSearchQueryHandlerFactory.VIDEOS), "")
        return SearchInfo.getInfo(ServiceList.YouTube, handler).relatedItems
            .filterIsInstance<StreamInfoItem>()
            .filter { keep(it.durationSec(), it.streamType) }
            .mapNotNull { item ->
                val url = YouTubeUrls.normalize(item.url) ?: return@mapNotNull null
                SearchResult(url, item.name, item.uploaderName, StreamResolver.thumbOf(item.thumbnails), item.duration)
            }
            .distinctBy { it.url }
            .take(MAX_RESULTS)
    }

    /** Appends "karaoke" unless it's off or the person already typed it. */
    fun buildQuery(query: String, addKaraoke: Boolean): String {
        val q = query.trim().replace(Regex("\\s+"), " ")
        return if (addKaraoke && !q.contains("karaoke", ignoreCase = true)) "$q karaoke" else q
    }

    /** Songs only: no live streams, nothing over 15 minutes (full albums, compilations). */
    fun keep(durationSec: Long, type: StreamType): Boolean =
        type != StreamType.LIVE_STREAM && type != StreamType.AUDIO_LIVE_STREAM &&
            durationSec in 1..MAX_DURATION_SEC

    private fun StreamInfoItem.durationSec() = duration
}
