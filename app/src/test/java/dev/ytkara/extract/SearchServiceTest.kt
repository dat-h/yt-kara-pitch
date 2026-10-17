package dev.ytkara.extract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.stream.StreamType

class SearchServiceTest {
    @Test fun appendsKaraoke() = assertEquals("bohemian rhapsody karaoke", SearchService.buildQuery("  bohemian   rhapsody ", true))
    @Test fun noDuplicateKaraoke() = assertEquals("abba Karaoke version", SearchService.buildQuery("abba Karaoke version", true))
    @Test fun exactWhenOff() = assertEquals("abba", SearchService.buildQuery("abba", false))

    @Test fun keepsSongLengthVideos() {
        assertTrue(SearchService.keep(240, StreamType.VIDEO_STREAM))
        assertTrue(SearchService.keep(15 * 60, StreamType.VIDEO_STREAM))
    }

    @Test fun dropsLiveLongAndUnknown() {
        assertFalse(SearchService.keep(240, StreamType.LIVE_STREAM))
        assertFalse(SearchService.keep(240, StreamType.AUDIO_LIVE_STREAM))
        assertFalse(SearchService.keep(15 * 60 + 1, StreamType.VIDEO_STREAM))
        assertFalse(SearchService.keep(0, StreamType.VIDEO_STREAM))
        assertFalse(SearchService.keep(-1, StreamType.VIDEO_STREAM))
    }
}
