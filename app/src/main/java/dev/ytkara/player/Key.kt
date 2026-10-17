package dev.ytkara.player

import kotlin.math.pow

/** A song's key offset in whole semitones. */
object Key {
    const val MIN = -6
    const val MAX = 6

    fun clamp(semitones: Int) = semitones.coerceIn(MIN, MAX)

    /** Media3 pitch multiplier for [semitones]: +12 is an octave up (2.0). Not clamped. */
    fun pitchOf(semitones: Int): Float = 2.0.pow(semitones / 12.0).toFloat()

    fun label(semitones: Int) = when {
        semitones > 0 -> "Key +$semitones"
        semitones < 0 -> "Key −${-semitones}"
        else -> "Original key"
    }
}
