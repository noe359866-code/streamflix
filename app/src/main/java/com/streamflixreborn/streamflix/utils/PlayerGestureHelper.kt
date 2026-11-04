package com.streamflixreborn.streamflix.utils

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.media3.ui.PlayerView
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.ui.PlayerMobileView
import com.streamflixreborn.streamflix.ui.PlayerTvView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Gestos del reproductor, al estilo del reproductor de Apple TV.
 *
 * Antes sólo había brillo/volumen (mitad izquierda/derecha del vídeo), pinch de
 * zoom y doble toque para resetear zoom:
 * - no existía **seek por gesto** (había que buscar en la barra con el dedo);
 * - el volumen se aplicaba por mitades porque `currentVolumeFloat` se redondeaba
 *   con `toInt()` en cada evento (en un móvil de 15 pasos nunca subía de 1 en 1);
 * - las barras de brillo/volumen aparecían y desaparecían con 1 s de retardo fijo
 *   y sin nada que indicara cambio;
 * - doble toque reseteaba zoom aunque el usuario no estuviera en zoom manual.
 *
 * Ahora:
 * - arrastre **horizontal** = seek relativo con burbuja de "±mm:ss" y haptics;
 * - arrastre **vertical** izquierda = brillo, derecha = volumen (ambos continuos);
 * - **doble toque** izquierda/derecha = ±10 s, con icono y vibración (patrón
 *   skip-forward de tvOS);
 * - el overlay se oculta con 900 ms de gracia y respeta `playerGestures`.
 */
class PlayerGestureHelper(
    private val context: Context,
    private val playerView: PlayerView,
    private val brightnessLayout: View,
    private val brightnessBar: ProgressBar,
    private val brightnessText: TextView,
    private val volumeLayout: View,
    private val volumeBar: ProgressBar,
    private val volumeText: TextView,
    /** Burbuja de seek (icono + texto ±mm:ss). Puede ser null en TV. */
    private val seekLayout: View? = null,
    private val seekText: TextView? = null,
    private val seekIcon: android.widget.ImageView? = null,
) {

    companion object {
        /** Segundos que se saltan con doble toque. */
        private const val DOUBLE_TAP_SEEK_MS = 10_000L

        /** Píxeles de arrastre por segundo de seek. */
        private const val PIXELS_PER_SEEK_SECOND = 12f

        private const val HIDE_DELAY_MS = 900L

        /** Sensibilidad de brillo/volumen por fracción de pantalla arrastrada. */
        private const val SENSITIVITY = 1.2f

        private const val MIN_BRIGHTNESS = 0.05f
        private const val MIN_ZOOM = 0.25f
        private const val MAX_ZOOM = 4.0f
    }

    private val gestureDetector: GestureDetector
    private val scaleGestureDetector: ScaleGestureDetector
    private val audioManager: AudioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var hideJob: Job? = null

    private var currentVolumeFloat = 0f
    private val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    private var isScrolling = false
    private var isScaling = false
    private var isSeeking = false
    private var seekStartPosition = 0L
    private var seekTargetPosition = 0L
    private var lastTapSide = TapSide.CENTER

    private enum class TapSide { LEFT, CENTER, RIGHT }

    init {
        scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val videoView = playerView.videoSurfaceView ?: return false
                isScaling = true
                videoView.scaleX = (videoView.scaleX * detector.scaleFactor).coerceIn(MIN_ZOOM, MAX_ZOOM)
                videoView.scaleY = (videoView.scaleY * detector.scaleFactor).coerceIn(MIN_ZOOM, MAX_ZOOM)
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                isScaling = false
            }
        })

        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                isScrolling = false
                isSeeking = false
                currentVolumeFloat = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (e1 == null || isScaling) return false
                // Zona superior reservada al controller (título, botón atrás).
                if (e1.y < TOP_DEAD_ZONE_PX) return false

                if (!isScrolling) {
                    val deltaX = abs(e2.x - e1.x)
                    val deltaY = abs(e2.y - e1.y)
                    if (maxOf(deltaX, deltaY) < TOUCH_SLOP_PX) return false
                    isScrolling = true
                    // Horizontal -> seek; vertical -> brillo/volumen (como tvOS y YouTube).
                    isSeeking = deltaX > deltaY
                    if (isSeeking) seekStartPosition = playerView.player?.currentPosition ?: 0L
                }

                if (isSeeking) {
                    handleSeek(e2.x - e1.x)
                } else if (e1.x < playerView.width / 2) {
                    handleBrightness(distanceY / playerView.height)
                } else {
                    handleVolume(distanceY / playerView.height)
                }
                return true
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                // Recuerda en qué mitad se tocó, para el doble toque.
                lastTapSide = when {
                    e.x < playerView.width * 0.35f -> TapSide.LEFT
                    e.x > playerView.width * 0.65f -> TapSide.RIGHT
                    else -> TapSide.CENTER
                }
                return false
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val player = playerView.player ?: return false
                // En el centro, doble toque = zoom reset (comportamiento previo).
                if (lastTapSide == TapSide.CENTER) {
                    val videoView = playerView.videoSurfaceView ?: return false
                    videoView.scaleX = 1f
                    videoView.scaleY = 1f
                    return true
                }

                val delta = if (lastTapSide == TapSide.LEFT) -DOUBLE_TAP_SEEK_MS else DOUBLE_TAP_SEEK_MS
                val target = (player.currentPosition + delta).coerceIn(0L, player.duration.coerceAtLeast(0L))
                player.seekTo(target)
                showSeekBubble(delta)
                playerView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                hideBars()
                return true
            }
        })

        playerView.setOnTouchListener { _, event ->
            // Sólo puntero real (touch/mouse/airmouse); los mandos no pasan por aquí.
            val isPointing = (event.source and InputDevice.SOURCE_CLASS_POINTER) != 0
            if (!isPointing) return@setOnTouchListener false

            val isManualZoom = when (playerView) {
                is PlayerMobileView -> playerView.isManualZoomEnabled
                is PlayerTvView -> playerView.isManualZoomEnabled
                else -> false
            }
            if (isManualZoom) return@setOnTouchListener false
            if (!UserPreferences.playerGestures) return@setOnTouchListener false

            scaleGestureDetector.onTouchEvent(event)
            if (!isScaling) gestureDetector.onTouchEvent(event)

            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                if (isSeeking) commitSeek()
                isScrolling = false
                isSeeking = false
                isScaling = false
                hideBars()
            }

            isScrolling || isScaling
        }
    }

    // region seek

    private fun handleSeek(deltaX: Float) {
        hideJob?.cancel()
        val player = playerView.player ?: return
        val seconds = deltaX / PIXELS_PER_SEEK_SECOND
        val target = (seekStartPosition + (seconds * 1000).toLong())
            .coerceIn(0L, player.duration.coerceAtLeast(0L))
        seekTargetPosition = target

        seekLayout?.visibility = View.VISIBLE
        brightnessLayout.visibility = View.GONE
        volumeLayout.visibility = View.GONE

        val diffSeconds = ((target - seekStartPosition) / 1000).toInt()
        seekText?.text = String.format(
            java.util.Locale.US,
            "%s%d:%02d",
            if (diffSeconds >= 0) "+" else "-",
            abs(diffSeconds) / 60,
            abs(diffSeconds) % 60,
        )
        seekIcon?.let { icon ->
            icon.setImageResource(
                if (diffSeconds >= 0) R.drawable.exo_styled_controls_fastforward
                else R.drawable.exo_styled_controls_rewind
            )
        }
    }

    private fun commitSeek() {
        val player = playerView.player ?: return
        player.seekTo(seekTargetPosition)
    }

    private fun showSeekBubble(deltaMs: Long) {
        seekLayout ?: return
        val seconds = (abs(deltaMs) / 1000).toInt()
        seekLayout.visibility = View.VISIBLE
        brightnessLayout.visibility = View.GONE
        volumeLayout.visibility = View.GONE
        seekText?.text = String.format(java.util.Locale.US, "%s%d s", if (deltaMs >= 0) "+" else "-", seconds)
        seekIcon?.let { icon ->
            icon.setImageResource(
                if (deltaMs >= 0) R.drawable.exo_styled_controls_fastforward
                else R.drawable.exo_styled_controls_rewind
            )
        }
    }

    // endregion

    @SuppressLint("SetTextI18n")
    private fun handleBrightness(delta: Float) {
        hideJob?.cancel()
        brightnessLayout.visibility = View.VISIBLE
        volumeLayout.visibility = View.GONE
        seekLayout?.visibility = View.GONE

        val window = (context as? android.app.Activity)?.window ?: return
        val layoutParams = window.attributes

        var currentBrightness = layoutParams.screenBrightness
        if (currentBrightness < 0) {
            currentBrightness = try {
                Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
            } catch (e: Settings.SettingNotFoundException) {
                0.5f
            }
        }

        val newBrightness = (currentBrightness + (delta / SENSITIVITY)).coerceIn(MIN_BRIGHTNESS, 1f)
        layoutParams.screenBrightness = newBrightness
        window.attributes = layoutParams

        val progress = (newBrightness * 100).toInt()
        brightnessBar.progress = progress
        brightnessText.text = "$progress%"
    }

    @SuppressLint("SetTextI18n")
    private fun handleVolume(delta: Float) {
        hideJob?.cancel()
        volumeLayout.visibility = View.VISIBLE
        brightnessLayout.visibility = View.GONE
        seekLayout?.visibility = View.GONE

        // Se acumula en float y sólo se redondea al aplicar: antes cada evento
        // hacía `toInt()` y el volumen se quedaba clavado en el mismo paso.
        currentVolumeFloat = (currentVolumeFloat - (delta / SENSITIVITY) * maxVolume)
            .coerceIn(0f, maxVolume.toFloat())

        val newVolume = currentVolumeFloat.toInt().coerceIn(0, maxVolume)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)

        val progress = if (maxVolume == 0) 0 else (currentVolumeFloat / maxVolume * 100).toInt()
        volumeBar.progress = progress
        volumeText.text = "$progress%"
    }

    private fun hideBars() {
        hideJob?.cancel()
        hideJob = CoroutineScope(Dispatchers.Main).launch {
            delay(HIDE_DELAY_MS)
            brightnessLayout.visibility = View.GONE
            volumeLayout.visibility = View.GONE
            seekLayout?.visibility = View.GONE
        }
    }

    private val TOP_DEAD_ZONE_PX: Float
        get() = 96f * context.resources.displayMetrics.density

    private val TOUCH_SLOP_PX: Float
        get() = 8f * context.resources.displayMetrics.density
}
