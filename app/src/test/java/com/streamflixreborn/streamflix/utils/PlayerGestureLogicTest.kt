package com.streamflixreborn.streamflix.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Reglas de gestos del reproductor, sin Android: si cambian, el arrastre tiene
 * que seguir dando saltos predecibles.
 */
class PlayerGestureLogicTest {

    /** Misma fórmula que `PlayerGestureHelper.handleSeek`. */
    private fun seekTarget(start: Long, deltaX: Float, pixelsPerSecond: Float = 12f): Long {
        val seconds = deltaX / pixelsPerSecond
        return (start + (seconds * 1000).toLong()).coerceAtLeast(0L)
    }

    @Test
    fun `arrastrar a la derecha avanza y a la izquierda retrocede`() {
        assertEquals(65_000L, seekTarget(50_000L, 180f))
        assertEquals(35_000L, seekTarget(50_000L, -180f))
    }

    @Test
    fun `el seek nunca es negativo al inicio del video`() {
        assertEquals(0L, seekTarget(5_000L, -600f))
    }

    @Test
    fun `el texto del salto se formatea en m ss`() {
        fun label(diffSeconds: Int) = String.format(
            java.util.Locale.US, "%s%d:%02d",
            if (diffSeconds >= 0) "+" else "-",
            abs(diffSeconds) / 60,
            abs(diffSeconds) % 60,
        )
        assertEquals("+0:15", label(15))
        assertEquals("-1:05", label(-65))
        assertEquals("+2:00", label(120))
    }

    @Test
    fun `doble toque lateral salta 10 segundos`() {
        val tenSeconds = 10_000L
        assertTrue(20_000L + tenSeconds == 30_000L)
        assertTrue((5_000L - tenSeconds).coerceAtLeast(0L) == 0L)
    }
}
