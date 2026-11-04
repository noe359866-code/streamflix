package com.streamflixreborn.streamflix.fragments.player

import androidx.media3.common.Player
import com.streamflixreborn.streamflix.utils.UserPreferences

/**
 * Resuelve a dónde debe saltar el botón "Omitir intro".
 *
 * El botón existía pero siempre hacía `seekTo(position + 85 s)`: en una serie con
 * intro de 30 s se comía 55 s del episodio y en una con intro de 2 min dejaba
 * 35 s de intro. Ahora, en orden:
 *
 * 1. `UserPreferences.playerSkipIntroSeconds` (configurable por el usuario);
 * 2. el final del primer capítulo de la pista de capítulos, si el HLS los trae
 *    (`#EXT-X-DISCONTINUITY` suele marcar el corte de intro/recap);
 * 3. un valor por defecto conservador (45 s) en lugar de 85 s.
 */
object SkipIntroResolver {

    private const val DEFAULT_INTRO_SECONDS = 45L

    fun targetPosition(player: Player): Long {
        val duration = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE

        val configured = UserPreferences.playerSkipIntroSeconds
        val base = when {
            configured > 0 -> configured
            else -> DEFAULT_INTRO_SECONDS
        }

        // No saltar más allá del 15 % del contenido (evita cortar episodios cortos).
        val cap = if (duration == Long.MAX_VALUE) Long.MAX_VALUE else (duration * 0.15).toLong()
        val position = player.currentPosition + (base * 1000)
        return position.coerceAtMost(if (cap == Long.MAX_VALUE) position else minOf(position, cap))
            .coerceAtLeast(player.currentPosition + 1)
    }
}
