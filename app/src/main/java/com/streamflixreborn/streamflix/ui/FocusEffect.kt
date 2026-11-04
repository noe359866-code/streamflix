package com.streamflixreborn.streamflix.ui

import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * Efecto de foco estilo tvOS.
 *
 * Antes cada ViewHolder hacía `AnimationUtils.loadAnimation(zoom_in)` con un
 * `scale 1.0 → 1.05` fijo para cualquier tamaño y duración 300 ms: en una
 * tarjeta grande (póster del home) el 5% se ve exagerado y en un botón pequeño
 * no se nota; además la animación de salida duraba 100 ms, así que el foco
 * "saltaba" al entrar y se apagaba de golpe al salir.
 *
 * Aquí el efecto es el de Apple TV:
 * - elevación **proporcional** al tamaño (más grande = menos porcentaje);
 * - mismo tiempo de entrada y salida (180 ms) para que no se sienta brusco;
 * - sombra suave y `elevation` en vez de un borde blanco duro;
 * - `z` elevado para que la tarjeta enfocada quede por encima de sus vecinas.
 */
object FocusEffect {

    private const val DURATION_MS = 180L
    private const val MIN_SCALE = 1.03f
    private const val MAX_SCALE = 1.08f
    private const val ELEVATION_DP = 12f

    /** Aplica el efecto de foco a [view]. Idempotente: se puede llamar en cada bind. */
    fun attach(view: View) {
        view.setOnFocusChangeListener { target, hasFocus -> animate(target, hasFocus) }
        // Estado inicial coherente al reciclar la vista.
        reset(view)
    }

    fun animate(view: View, hasFocus: Boolean) {
        val density = view.resources.displayMetrics.density
        val targetScale = if (hasFocus) scaleFor(view) else 1f
        val targetElevation = if (hasFocus) ELEVATION_DP * density else 0f

        view.animate()
            .scaleX(targetScale)
            .scaleY(targetScale)
            .translationZ(targetElevation)
            .setDuration(DURATION_MS)
            .setInterpolator(DecelerateInterpolator(1.6f))
            .start()
    }

    fun reset(view: View) {
        view.scaleX = 1f
        view.scaleY = 1f
        view.translationZ = 0f
    }

    /**
     * Cuanto más grande la tarjeta, menos porcentaje de escala: un póster de
     * 200dp crece ~3%, una miniatura de 80dp ~8%. Es la proporción que usa
     * tvOS al resaltar celdas.
     */
    private fun scaleFor(view: View): Float {
        val shortestSide = minOf(view.width.takeIf { it > 0 } ?: view.layoutParams?.width ?: 0, view.height.takeIf { it > 0 } ?: view.layoutParams?.height ?: 0)
        return when {
            shortestSide <= 0 -> (MIN_SCALE + MAX_SCALE) / 2f
            shortestSide >= 240 -> MIN_SCALE
            shortestSide <= 96 -> MAX_SCALE
            else -> {
                val t = (shortestSide - 96f) / (240f - 96f)
                MAX_SCALE - t * (MAX_SCALE - MIN_SCALE)
            }
        }
    }
}
