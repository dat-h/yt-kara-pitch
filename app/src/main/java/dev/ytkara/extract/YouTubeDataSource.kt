package dev.ytkara.extract

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource

/**
 * Reads googlevideo URLs in fixed-size pieces using the `range=` query parameter instead of an
 * open-ended Range header: YouTube throttles or refuses long unbounded reads. Also sends the
 * User-Agent of the YouTube client the URL was issued to (its `c=` parameter), which some
 * clients' URLs require.
 */
@UnstableApi
class YouTubeDataSource(private val upstream: HttpDataSource) : BaseDataSource(/* isNetwork= */ true) {

    private var spec: DataSpec? = null
    private var position = 0L
    /** Exclusive end of what was asked for, or -1 when unknown. */
    private var end = -1L
    private var chunkRemaining = 0L
    private var chunkOpen = false

    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec
        transferInitializing(dataSpec)
        val total = dataSpec.uri.getQueryParameter("clen")?.toLongOrNull() ?: -1L
        position = dataSpec.position
        end = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> position + dataSpec.length
            else -> total
        }
        openChunk()
        transferStarted(dataSpec)
        return if (end >= 0) end - position else C.LENGTH_UNSET.toLong()
    }

    private fun openChunk() {
        val base = spec!!
        val chunkEnd = if (end >= 0) minOf(position + CHUNK, end) else position + CHUNK
        val uri = base.uri.buildUpon()
            .appendQueryParameter("range", "$position-${chunkEnd - 1}")
            .build()
        upstream.open(
            DataSpec.Builder()
                .setUri(uri)
                .setHttpRequestHeaders(mapOf("User-Agent" to userAgentFor(base.uri)))
                .build(),
        )
        chunkRemaining = chunkEnd - position
        chunkOpen = true
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        while (true) {
            if (end in 0..position) return C.RESULT_END_OF_INPUT
            if (!chunkOpen) openChunk()
            val read = upstream.read(buffer, offset, length)
            if (read == C.RESULT_END_OF_INPUT) {
                upstream.close()
                chunkOpen = false
                // A short chunk is the end of the file when its size isn't known up front.
                if (end < 0 && chunkRemaining > 0) return C.RESULT_END_OF_INPUT
                continue
            }
            position += read
            chunkRemaining -= read
            bytesTransferred(read)
            return read
        }
    }

    override fun getUri(): Uri? = spec?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        if (spec != null) {
            spec = null
            chunkOpen = false
            try {
                upstream.close()
            } finally {
                transferEnded()
            }
        }
    }

    class Factory : DataSource.Factory {
        private val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(15_000)

        override fun createDataSource(): DataSource = YouTubeDataSource(http.createDataSource())
    }

    companion object {
        private const val CHUNK = 4L * 1024 * 1024

        private fun userAgentFor(uri: Uri): String = when (uri.getQueryParameter("c")?.uppercase()) {
            "ANDROID" -> "com.google.android.youtube/20.10.38 (Linux; U; Android 11) gzip"
            "ANDROID_VR" -> "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"
            "IOS" -> "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)"
            else -> OkHttpDownloader.DESKTOP_USER_AGENT
        }
    }
}
