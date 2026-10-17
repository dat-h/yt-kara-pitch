package dev.ytkara.queue

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QueueRepositoryTest {
    private fun TestScope.repo(resolve: suspend (String) -> VideoMeta = { VideoMeta("Title $it", "up", null, 200) }): QueueRepository {
        var n = 0
        return QueueRepository(backgroundScope, resolve, newId = { "e${n++}" })
    }

    private fun QueueRepository.ids() = state.value.queue.map { it.id }

    @Test fun addResolvesInBackground() = runTest {
        val r = repo()
        val e = r.add("u1", " Alex ", 9)
        assertEquals(EntryStatus.RESOLVING, r.state.value.queue.single().status)
        assertEquals("Alex", e.singer)
        assertEquals(6, e.presetKey)
        runCurrent()
        val resolved = r.state.value.queue.single()
        assertEquals(EntryStatus.READY, resolved.status)
        assertEquals("Title u1", resolved.title)
    }

    @Test fun resolveFailureMarksError() = runTest {
        val r = repo { throw IllegalStateException("blocked") }
        r.add("u1", null, null)
        runCurrent()
        val e = r.state.value.queue.single()
        assertEquals(EntryStatus.ERROR, e.status)
        assertEquals("blocked", e.error)
        assertNull(r.state.value.firstReady())
    }

    @Test fun everyChangeBumpsVersion() = runTest {
        val r = repo()
        val v0 = r.state.value.version
        r.add("u1", null, null)
        val v1 = r.state.value.version
        runCurrent()
        val v2 = r.state.value.version
        r.update("e0", "Sam", null)
        val v3 = r.state.value.version
        assertTrue(v0 < v1 && v1 < v2 && v2 < v3)
        assertFalse(r.remove("missing"))
        assertEquals(v3, r.state.value.version)
    }

    @Test fun moveClampsToBounds() = runTest {
        val r = repo()
        repeat(3) { r.add("u$it", null, null) }
        assertTrue(r.move("e2", 0))
        assertEquals(listOf("e2", "e0", "e1"), r.ids())
        assertTrue(r.move("e2", 99))
        assertEquals(listOf("e0", "e1", "e2"), r.ids())
        assertTrue(r.move("e1", -5))
        assertEquals(listOf("e1", "e0", "e2"), r.ids())
        assertFalse(r.move("nope", 0))
    }

    @Test fun startNextSkipsUnresolvedAndRequeues() = runTest {
        val slow = CompletableDeferred<VideoMeta>()
        val r = repo { if (it == "slow") slow.await() else VideoMeta(it, null, null, 1) }
        r.add("slow", null, null)
        r.add("fast", null, null)
        runCurrent()

        val first = r.startNext()!!
        assertEquals("e1", first.id)
        assertEquals("e1", r.state.value.nowPlaying?.id)
        assertEquals(listOf("e0"), r.ids())

        r.requeueNowPlaying()
        assertNull(r.state.value.nowPlaying)
        assertEquals(listOf("e1", "e0"), r.ids())

        r.startNext()
        r.finishNowPlaying()
        assertNull(r.state.value.nowPlaying)
        assertEquals(listOf("e0"), r.ids())
        assertNull(r.startNext()) // e0 is still resolving
        slow.complete(VideoMeta("Slow", null, null, 1))
    }

    @Test fun removedWhileResolvingStaysRemoved() = runTest {
        val gate = CompletableDeferred<VideoMeta>()
        val r = repo { gate.await() }
        r.add("u", null, null)
        runCurrent()
        r.remove("e0")
        gate.complete(VideoMeta("T", null, null, 1))
        runCurrent()
        assertTrue(r.state.value.queue.isEmpty())
    }
}
