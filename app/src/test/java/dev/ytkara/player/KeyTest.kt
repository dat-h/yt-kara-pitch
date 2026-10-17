package dev.ytkara.player

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyTest {
    @Test fun octaveUpDoublesPitch() = assertEquals(2.0f, Key.pitchOf(12), 1e-6f)
    @Test fun octaveDownHalvesPitch() = assertEquals(0.5f, Key.pitchOf(-12), 1e-6f)
    @Test fun zeroIsUnchanged() = assertEquals(1.0f, Key.pitchOf(0), 0f)
    @Test fun semitoneUp() = assertEquals(1.059463f, Key.pitchOf(1), 1e-5f)

    @Test fun clampsToSixSemitones() {
        assertEquals(6, Key.clamp(9))
        assertEquals(-6, Key.clamp(-7))
        assertEquals(3, Key.clamp(3))
    }

    @Test fun labels() {
        assertEquals("Key +2", Key.label(2))
        assertEquals("Key −3", Key.label(-3))
        assertEquals("Original key", Key.label(0))
    }
}
