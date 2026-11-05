@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.streamflixreborn.streamflix.tv.player

import android.graphics.Typeface
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.streamflixreborn.streamflix.profiles.ProfileSubtitleStyle
import com.streamflixreborn.streamflix.profiles.TvProfileRepository
import com.streamflixreborn.streamflix.profiles.TvProfileWatchHistoryEntity
import com.streamflixreborn.streamflix.stream.AutoStreamResolver
import com.streamflixreborn.streamflix.stream.ResolvedStreamCandidate
import com.streamflixreborn.streamflix.stream.StreamResolutionEvent
import com.streamflixreborn.streamflix.stream.StreamUrlMemoryCache
import com.streamflixreborn.streamflix.subtitles.ResolvedExternalSubtitle
import com.streamflixreborn.streamflix.subtitles.SubtitleCueParser
import com.streamflixreborn.streamflix.subtitles.TimedSubtitleCue
import com.streamflixreborn.streamflix.subtitles.TvSubtitleEngine
import com.streamflixreborn.streamflix.trakt.TraktScrobbleAction
import com.streamflixreborn.streamflix.trakt.TraktSyncManager
import com.streamflixreborn.streamflix.tv.model.TvMediaKind
import com.streamflixreborn.streamflix.tv.model.TvPlaybackSessionRequest
import com.streamflixreborn.streamflix.tv.theme.AppleTvColors
import com.streamflixreborn.streamflix.utils.DnsResolver
import com.streamflixreborn.streamflix.utils.NetworkClient
import com.streamflixreborn.streamflix.utils.TvImageOptimizer
import com.streamflixreborn.streamflix.utils.toSubtitleMimeType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.Locale
import java.util.concurrent.TimeUnit

private const val TAG = "AppleTvPlayer"
private const val CONTROLS_AUTO_HIDE_MS = 3_000L
private const val SEEK_STEP_MS = 10_000L

private data class PlayerTrackOption(
    val id: String,
    val label: String,
    val isSelected: Boolean,
    val groupIndex: Int = -1,
    val trackIndex: Int = -1,
)

private enum class QuickPanelTab {
    TRACKS,
    SUBTITLE_STYLE,
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun AppleTvPlayerScreen(
    session: TvPlaybackSessionRequest,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val mediaKey = remember(session.videoType) {
        StreamUrlMemoryCache.buildMediaKey(session.videoType)
    }

    val surfaceFocusRequester = remember { FocusRequester() }
    val quickPanelFirstChipRequester = remember { FocusRequester() }
    val retryFocusRequester = remember { FocusRequester() }
    val profilePrefs = remember { TvProfileRepository.getActiveProfilePreferences(context) }

    var isResolving by remember { mutableStateOf(true) }
    var resolvingStatus by remember { mutableStateOf("Preparando tu transmisión...") }
    var fatalErrorMessage by remember { mutableStateOf<String?>(null) }

    val candidates = remember { mutableStateListOf<ResolvedStreamCandidate>() }
    val failedUrls = remember { mutableSetOf<String>() }
    var activeCandidate by remember { mutableStateOf<ResolvedStreamCandidate?>(null) }
    var refreshAttempt by remember { mutableIntStateOf(0) }
    var retriedFreshOnce by remember { mutableStateOf(false) }

    var isPlaying by remember { mutableStateOf(false) }
    var isBuffering by remember { mutableStateOf(true) }
    var positionMs by remember {
        mutableLongStateOf(
            maxOf(
                session.initialResumePositionMs,
                StreamUrlMemoryCache.getSavedPlaybackPosition(mediaKey),
            )
        )
    }
    var bufferedPositionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }

    // Apple TV Overlay & Quick Panel state
    var controlsVisible by remember { mutableStateOf(true) }
    var quickPanelOpen by remember { mutableStateOf(false) }
    var lastUserActivityTick by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // Embedded + External OpenSubtitles tracks & real-time Subtitle Styling
    var audioTracks by remember { mutableStateOf<List<PlayerTrackOption>>(emptyList()) }
    var embeddedSubtitleTracks by remember { mutableStateOf<List<PlayerTrackOption>>(emptyList()) }
    var externalSubtitles by remember { mutableStateOf<List<ResolvedExternalSubtitle>>(emptyList()) }
    var selectedExternalSubId by remember { mutableStateOf<String?>(null) }
    var parsedExternalCues by remember { mutableStateOf<List<TimedSubtitleCue>>(emptyList()) }
    var activeExternalCueText by remember { mutableStateOf<String?>(null) }

    var subtitlesDisabled by remember { mutableStateOf(!profilePrefs.subtitlesEnabled) }
    var subtitleStyle by remember { mutableStateOf(profilePrefs.subtitleStyle) }

    // Build Media3 ExoPlayer + OkHttpDataSource (DoH + dynamic per-stream headers)
    val httpDataSourceFactory = remember {
        val okHttp = OkHttpClient.Builder()
            .dns(DnsResolver.doh)
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
        OkHttpDataSource.Factory(okHttp)
    }

    val exoPlayer = remember {
        val renderersFactory = DefaultRenderersFactory(context).apply {
            setEnableDecoderFallback(true)
        }
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                1_500,
                3_000,
            )
            .build()
        val dataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)

        ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setLoadControl(loadControl)
            .build()
            .apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    true,
                )
                trackSelectionParameters = trackSelectionParameters
                    .buildUpon()
                    .setPreferredAudioLanguage("spa")
                    .setPreferredTextLanguage(profilePrefs.preferredSubtitleLang)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !profilePrefs.subtitlesEnabled)
                    .build()
                playWhenReady = true
            }
    }

    val ioPersistScope = remember { CoroutineScope(Dispatchers.IO) }

    fun updateSubtitleStyle(newStyle: ProfileSubtitleStyle) {
        subtitleStyle = newStyle
        ioPersistScope.launch {
            TvProfileRepository.updateActiveProfilePlaybackPreferences(
                subtitleStyle = newStyle,
                context = context,
            )
        }
    }

    fun persistProfileWatchProgress(posMs: Long, durMs: Long) {
        if (posMs <= 3_000L) return
        val activeProfileId = TvProfileRepository.activeProfile.value?.id ?: return
        val entry = TvProfileWatchHistoryEntity(
            profileId = activeProfileId,
            mediaKey = mediaKey,
            contentId = session.contentId,
            imdbId = session.imdbId,
            mediaKind = if (session.mediaKind == TvMediaKind.SERIES) "SERIES" else "MOVIE",
            title = session.displayTitle,
            subtitle = session.displaySubtitle,
            overview = session.overview,
            posterUrl = session.posterUrl,
            backgroundUrl = session.backdropUrl,
            logoUrl = session.logoUrl,
            year = session.year,
            rating = session.rating,
            isDoramaOrAsian = session.isDoramaOrAsian,
            seasonNumber = session.seasonNumber,
            episodeNumber = session.episodeNumber,
            positionMs = posMs,
            durationMs = durMs,
        )
        ioPersistScope.launch {
            TvProfileRepository.recordPlaybackProgress(entry, context)
        }
    }

    fun loadCandidateIntoPlayer(
        candidate: ResolvedStreamCandidate,
        seekToMs: Long,
    ) {
        activeCandidate = candidate
        isBuffering = true
        fatalErrorMessage = null

        val headers = LinkedHashMap<String, String>().apply {
            put("User-Agent", NetworkClient.USER_AGENT)
            candidate.video.headers?.forEach { (k, v) ->
                if (k.isNotBlank() && v.isNotBlank()) put(k, v)
            }
        }
        httpDataSourceFactory.setDefaultRequestProperties(headers)

        // Also promote any stream-provided SRT/VTT URLs into our live-syncable external list
        val streamSubs = candidate.video.subtitles.mapIndexedNotNull { idx, sub ->
            if (sub.file.isBlank()) return@mapIndexedNotNull null
            ResolvedExternalSubtitle(
                id = "stream_sub_$idx",
                label = sub.label.ifBlank { "Español" },
                langCode = "es",
                uriOrUrl = sub.file,
                isSpanishLatino = sub.label.contains("latino", ignoreCase = true),
                isSpanish = true,
            )
        }
        if (streamSubs.isNotEmpty()) {
            val existingUrls = externalSubtitles.map { it.uriOrUrl }.toSet()
            val merged = (streamSubs.filter { it.uriOrUrl !in existingUrls } + externalSubtitles)
            externalSubtitles = merged
        }

        val subtitleConfigs = candidate.video.subtitles.mapNotNull { sub ->
            if (sub.file.isBlank()) return@mapNotNull null
            MediaItem.SubtitleConfiguration.Builder(sub.file.toUri())
                .setMimeType(sub.file.toSubtitleMimeType())
                .setLanguage("es")
                .setLabel(sub.label.ifBlank { "Español" })
                .build()
        }

        val mediaItemBuilder = MediaItem.Builder()
            .setUri(candidate.video.source.toUri())
            .setSubtitleConfigurations(subtitleConfigs)

        if (!candidate.video.type.isNullOrBlank()) {
            mediaItemBuilder.setMimeType(candidate.video.type)
        }

        exoPlayer.setMediaItem(mediaItemBuilder.build(), seekToMs.coerceAtLeast(0L))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    // Automatically download Spanish/Latino subtitles via OpenSubtitles + Stremio Subtitle Add-ons
    LaunchedEffect(session.imdbId, session.seasonNumber, session.episodeNumber) {
        val downloaded = TvSubtitleEngine.fetchSubtitlesForSession(
            imdbId = session.imdbId ?: session.contentId.takeIf { it.startsWith("tt") },
            mediaKind = session.mediaKind,
            seasonNumber = session.seasonNumber,
            episodeNumber = session.episodeNumber,
            context = context,
        )
        if (downloaded.isNotEmpty()) {
            val currentUrls = externalSubtitles.map { it.uriOrUrl }.toSet()
            val combined = externalSubtitles + downloaded.filter { it.uriOrUrl !in currentUrls }
            externalSubtitles = combined
            if (!subtitlesDisabled && selectedExternalSubId == null) {
                selectedExternalSubId = combined.first().id
            }
        }
    }

    // Parse cues when user selects an external OpenSubtitles/SRT/VTT track so ±ms Sync Offset works live!
    LaunchedEffect(selectedExternalSubId, subtitlesDisabled, externalSubtitles) {
        if (subtitlesDisabled || selectedExternalSubId == null) {
            parsedExternalCues = emptyList()
            activeExternalCueText = null
            return@LaunchedEffect
        }
        val chosen = externalSubtitles.firstOrNull { it.id == selectedExternalSubId }
        if (chosen == null) {
            parsedExternalCues = emptyList()
            activeExternalCueText = null
            return@LaunchedEffect
        }
        // Mute ExoPlayer's internal text renderer while our live-synced external renderer is active
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        parsedExternalCues = TvSubtitleEngine.loadParsedCues(chosen.uriOrUrl)
    }

    // High-precision 60ms subtitle cue synchronizer (`positionMs - subtitleStyle.syncOffsetMs`)
    LaunchedEffect(parsedExternalCues, subtitlesDisabled, subtitleStyle.syncOffsetMs, exoPlayer) {
        if (subtitlesDisabled || parsedExternalCues.isEmpty()) {
            activeExternalCueText = null
            return@LaunchedEffect
        }
        while (true) {
            val nowPos = exoPlayer.currentPosition.coerceAtLeast(0L)
            activeExternalCueText = SubtitleCueParser.findActiveCueText(
                cues = parsedExternalCues,
                videoPositionMs = nowPos,
                syncOffsetMs = subtitleStyle.syncOffsetMs,
            )
            delay(65L)
        }
    }

    val latestActiveCandidate by rememberUpdatedState(activeCandidate)

    // Attach ExoPlayer listener including silent PlaybackException fallback + Trakt.tv Scrobbling
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                val cur = exoPlayer.currentPosition.coerceAtLeast(0L)
                val dur = exoPlayer.duration.coerceAtLeast(durationMs)
                if (dur > 0L && activeCandidate != null) {
                    ioPersistScope.launch {
                        TraktSyncManager.scrobblePlayback(
                            session = session,
                            action = if (playing) TraktScrobbleAction.START else TraktScrobbleAction.PAUSE,
                            positionMs = cur,
                            durationMs = dur,
                            context = context,
                        )
                    }
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) {
                    durationMs = exoPlayer.duration.coerceAtLeast(0L)
                } else if (playbackState == Player.STATE_ENDED) {
                    val dur = exoPlayer.duration.coerceAtLeast(durationMs)
                    StreamUrlMemoryCache.clearPlaybackPosition(mediaKey)
                    ioPersistScope.launch {
                        TvProfileRepository.clearWatchEntry(mediaKey, context)
                        TraktSyncManager.scrobblePlayback(
                            session = session,
                            action = TraktScrobbleAction.STOP,
                            positionMs = dur,
                            durationMs = dur,
                            context = context,
                        )
                    }
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                audioTracks = extractTrackOptions(tracks, C.TRACK_TYPE_AUDIO, "Pista de audio")
                embeddedSubtitleTracks = extractTrackOptions(tracks, C.TRACK_TYPE_TEXT, "Subtítulo")
                if (selectedExternalSubId == null) {
                    subtitlesDisabled = C.TRACK_TYPE_TEXT in exoPlayer.trackSelectionParameters.disabledTrackTypes
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val failing = latestActiveCandidate
                val failedSource = failing?.video?.source.orEmpty()
                val resumeAt = exoPlayer.currentPosition.coerceAtLeast(positionMs).coerceAtLeast(0L)
                positionMs = resumeAt

                Log.w(TAG, "PlaybackException (${error.errorCodeName}) — executing silent fallback")

                if (failedSource.isNotEmpty()) {
                    failedUrls.add(failedSource)
                    StreamUrlMemoryCache.markUrlFailed(mediaKey, failedSource)
                }

                val nextCandidate = candidates.firstOrNull { cand ->
                    cand.video.source.isNotBlank() && cand.video.source !in failedUrls
                }

                if (nextCandidate != null) {
                    loadCandidateIntoPlayer(nextCandidate, resumeAt)
                } else if (!retriedFreshOnce) {
                    retriedFreshOnce = true
                    isResolving = true
                    resolvingStatus = "Preparando tu transmisión..."
                    refreshAttempt += 1
                } else {
                    isResolving = false
                    isBuffering = false
                    fatalErrorMessage = "No fue posible reproducir este título en este momento."
                }
            }
        }

        exoPlayer.addListener(listener)

        onDispose {
            val lastPos = exoPlayer.currentPosition
            val lastDur = exoPlayer.duration.coerceAtLeast(durationMs)
            if (lastPos > 2_000L) {
                StreamUrlMemoryCache.savePlaybackPosition(mediaKey, lastPos)
                persistProfileWatchProgress(lastPos, lastDur)
                ioPersistScope.launch {
                    TraktSyncManager.scrobblePlayback(
                        session = session,
                        action = TraktScrobbleAction.STOP,
                        positionMs = lastPos,
                        durationMs = lastDur,
                        context = context,
                    )
                }
            }
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    // Automated blind stream resolution pipeline (Memory Cache -> Primary + Custom Add-ons + Closed Pool of 6)
    LaunchedEffect(mediaKey, refreshAttempt) {
        val dbSavedResume = TvProfileRepository.getSavedResumePositionMs(mediaKey, context)
        if (dbSavedResume > positionMs) {
            positionMs = dbSavedResume
        }
        AutoStreamResolver.resolveStreams(
            videoType = session.videoType,
            forceRefresh = refreshAttempt > 0,
            doramaTagHint = session.isDoramaOrAsian,
        ).collect { event ->
            when (event) {
                is StreamResolutionEvent.Loading -> {
                    if (activeCandidate == null) {
                        isResolving = true
                        resolvingStatus = event.statusText
                    }
                }
                is StreamResolutionEvent.FirstReady -> {
                    isResolving = false
                    fatalErrorMessage = null
                    candidates.clear()
                    candidates.addAll(event.candidates.filter { it.video.source !in failedUrls })

                    val current = activeCandidate
                    if (current == null || current.video.source in failedUrls) {
                        val firstValid = candidates.firstOrNull() ?: event.primary
                        val initialSeek = maxOf(
                            positionMs,
                            StreamUrlMemoryCache.getSavedPlaybackPosition(mediaKey),
                        )
                        loadCandidateIntoPlayer(firstValid, initialSeek)
                        controlsVisible = true
                        lastUserActivityTick = System.currentTimeMillis()
                    }
                }
                is StreamResolutionEvent.FallbacksUpdated -> {
                    val validNew = event.candidates.filter { it.video.source !in failedUrls }
                    candidates.clear()
                    candidates.addAll(validNew)
                }
                is StreamResolutionEvent.Failed -> {
                    if (activeCandidate == null) {
                        isResolving = false
                        isBuffering = false
                        fatalErrorMessage = event.message
                    }
                }
            }
        }
    }

    // Poll playback progress smoothly while active & persist resume position in memory + Room profile DB
    LaunchedEffect(exoPlayer, isPlaying) {
        var tickCount = 0
        while (true) {
            val cur = exoPlayer.currentPosition.coerceAtLeast(0L)
            val dur = exoPlayer.duration.coerceAtLeast(0L)
            val buf = exoPlayer.bufferedPosition.coerceAtLeast(cur)
            positionMs = cur
            bufferedPositionMs = buf
            if (dur > 0L) durationMs = dur
            if (cur > 2_000L) {
                StreamUrlMemoryCache.savePlaybackPosition(mediaKey, cur)
                tickCount += 1
                if (tickCount % 12 == 0) {
                    persistProfileWatchProgress(cur, dur)
                }
            }
            delay(400L)
        }
    }

    // Strict 3-second Auto-Hide Timer for Apple TV overlay controls
    LaunchedEffect(controlsVisible, lastUserActivityTick, quickPanelOpen) {
        if (controlsVisible && !quickPanelOpen && fatalErrorMessage == null) {
            delay(CONTROLS_AUTO_HIDE_MS)
            controlsVisible = false
        }
    }

    // Focus management between player surface, Quick Panel, and Retry state
    LaunchedEffect(quickPanelOpen, fatalErrorMessage) {
        when {
            fatalErrorMessage != null -> runCatching { retryFocusRequester.requestFocus() }
            quickPanelOpen -> {
                delay(60L)
                runCatching { quickPanelFirstChipRequester.requestFocus() }
            }
            else -> runCatching { surfaceFocusRequester.requestFocus() }
        }
    }

    BackHandler {
        if (quickPanelOpen) {
            quickPanelOpen = false
            lastUserActivityTick = System.currentTimeMillis()
            controlsVisible = true
        } else {
            val pos = exoPlayer.currentPosition
            val dur = exoPlayer.duration.coerceAtLeast(durationMs)
            if (pos > 2_000L) {
                StreamUrlMemoryCache.savePlaybackPosition(mediaKey, pos)
                persistProfileWatchProgress(pos, dur)
            }
            onBack()
        }
    }

    fun notifyDpadActivity() {
        lastUserActivityTick = System.currentTimeMillis()
        controlsVisible = true
    }

    val resolvedSubtitleFgColor = remember(subtitleStyle.textColorHex) {
        parseSubtitleHexColor(subtitleStyle.textColorHex)
    }
    val resolvedSubtitleBgColor = remember(subtitleStyle.bgOpacity) {
        Color.Black.copy(alpha = subtitleStyle.bgOpacity.coerceIn(0f, 1f))
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(surfaceFocusRequester)
            .focusable()
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false

                notifyDpadActivity()

                if (fatalErrorMessage != null) {
                    return@onPreviewKeyEvent false
                }

                if (quickPanelOpen) {
                    if (keyEvent.key == Key.Back || keyEvent.key == Key.Escape) {
                        quickPanelOpen = false
                        return@onPreviewKeyEvent true
                    }
                    return@onPreviewKeyEvent false
                }

                when (keyEvent.key) {
                    Key.DirectionUp -> {
                        quickPanelOpen = true
                        true
                    }
                    Key.DirectionCenter,
                    Key.Enter,
                    Key.NumPadEnter,
                    Key.MediaPlayPause,
                    Key.Spacebar -> {
                        if (exoPlayer.isPlaying) {
                            exoPlayer.pause()
                        } else {
                            exoPlayer.play()
                        }
                        true
                    }
                    Key.MediaPlay -> {
                        exoPlayer.play()
                        true
                    }
                    Key.MediaPause -> {
                        exoPlayer.pause()
                        true
                    }
                    Key.DirectionLeft,
                    Key.MediaRewind -> {
                        val target = (exoPlayer.currentPosition - SEEK_STEP_MS).coerceAtLeast(0L)
                        exoPlayer.seekTo(target)
                        positionMs = target
                        true
                    }
                    Key.DirectionRight,
                    Key.MediaFastForward -> {
                        val maxDur = exoPlayer.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
                        val target = (exoPlayer.currentPosition + SEEK_STEP_MS).coerceAtMost(maxDur)
                        exoPlayer.seekTo(target)
                        positionMs = target
                        true
                    }
                    else -> false
                }
            },
    ) {
        // Media3 Hardware Video Surface + Styled Embedded SubtitleView
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    keepScreenOn = true
                    subtitleView?.apply {
                        setApplyEmbeddedStyles(false)
                        setApplyEmbeddedFontSizes(false)
                    }
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
            },
            update = { view ->
                if (view.player !== exoPlayer) {
                    view.player = exoPlayer
                }
                view.keepScreenOn = isPlaying || isBuffering || isResolving
                view.subtitleView?.apply {
                    visibility = if (subtitlesDisabled || selectedExternalSubId != null) {
                        View.GONE
                    } else {
                        View.VISIBLE
                    }
                    setFixedTextSize(
                        TypedValue.COMPLEX_UNIT_SP,
                        23f * subtitleStyle.sizeScale.coerceIn(0.75f, 1.7f),
                    )
                    setStyle(
                        CaptionStyleCompat(
                            resolvedSubtitleFgColor.toArgb(),
                            resolvedSubtitleBgColor.toArgb(),
                            android.graphics.Color.TRANSPARENT,
                            CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW,
                            android.graphics.Color.BLACK,
                            Typeface.SANS_SERIF,
                        )
                    )
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Live-Synced External Subtitle Renderer (supports real-time ±ms Sync Offset + Size/Color/Box Opacity)
        if (!subtitlesDisabled && !activeExternalCueText.isNullOrBlank() && fatalErrorMessage == null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(
                        start = 96.dp,
                        end = 96.dp,
                        bottom = if (controlsVisible) 138.dp else 54.dp,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .widthIn(max = 980.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(resolvedSubtitleBgColor)
                        .padding(
                            horizontal = if (subtitleStyle.bgOpacity > 0.05f) 18.dp else 4.dp,
                            vertical = if (subtitleStyle.bgOpacity > 0.05f) 8.dp else 2.dp,
                        ),
                ) {
                    val fontSizeSp = (23f * subtitleStyle.sizeScale.coerceIn(0.75f, 1.7f)).sp
                    Text(
                        text = activeExternalCueText.orEmpty(),
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontSize = fontSizeSp,
                            lineHeight = (fontSizeSp.value * 1.32f).sp,
                            fontWeight = FontWeight.SemiBold,
                            shadow = Shadow(
                                color = Color.Black,
                                offset = Offset(1.5f, 2f),
                                blurRadius = 5f,
                            ),
                        ),
                        color = resolvedSubtitleFgColor,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        // Subtle darkened backdrop while resolving first stream
        AnimatedVisibility(
            visible = isResolving && activeCandidate == null,
            enter = fadeIn(tween(220)),
            exit = fadeOut(tween(320)),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (!session.backdropUrl.isNullOrBlank()) {
                    val req = remember(session.backdropUrl) {
                        TvImageOptimizer.backdropRequest(context, session.backdropUrl)
                    }
                    AsyncImage(
                        model = req,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xD906070B)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            color = AppleTvColors.PureWhite,
                            strokeWidth = 2.6.dp,
                            modifier = Modifier.size(42.dp),
                        )
                        Spacer(modifier = Modifier.height(18.dp))
                        Text(
                            text = session.displayTitle,
                            style = MaterialTheme.typography.headlineSmall,
                            color = AppleTvColors.PureWhite,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = resolvingStatus,
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppleTvColors.SecondaryText,
                        )
                    }
                }
            }
        }

        // Subtle mid-stream buffering spinner
        if (!isResolving && isBuffering && fatalErrorMessage == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    color = AppleTvColors.PureWhite.copy(alpha = 0.85f),
                    strokeWidth = 2.4.dp,
                    modifier = Modifier.size(36.dp),
                )
            }
        }

        // Inline Apple TV retry overlay ONLY if every source + fresh retry failed
        if (fatalErrorMessage != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xE006070B)),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 64.dp),
                ) {
                    Text(
                        text = session.displayTitle,
                        style = MaterialTheme.typography.headlineSmall,
                        color = AppleTvColors.PureWhite,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = fatalErrorMessage.orEmpty(),
                        style = MaterialTheme.typography.bodyLarge,
                        color = AppleTvColors.SecondaryText,
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Button(
                            onClick = {
                                failedUrls.clear()
                                fatalErrorMessage = null
                                retriedFreshOnce = false
                                isResolving = true
                                refreshAttempt += 1
                            },
                            modifier = Modifier.focusRequester(retryFocusRequester),
                            shape = ButtonDefaults.shape(shape = RoundedCornerShape(50)),
                            colors = ButtonDefaults.colors(
                                containerColor = AppleTvColors.PureWhite,
                                contentColor = AppleTvColors.Obsidian,
                                focusedContainerColor = AppleTvColors.PureWhite,
                                focusedContentColor = AppleTvColors.Obsidian,
                            ),
                            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                        ) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Reintentar", style = MaterialTheme.typography.labelLarge)
                        }

                        Button(
                            onClick = onBack,
                            shape = ButtonDefaults.shape(shape = RoundedCornerShape(50)),
                            colors = ButtonDefaults.colors(
                                containerColor = AppleTvColors.GlassSurface,
                                contentColor = AppleTvColors.PureWhite,
                                focusedContainerColor = AppleTvColors.PureWhite,
                                focusedContentColor = AppleTvColors.Obsidian,
                            ),
                            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                        ) {
                            Text("Volver", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }

        // Top Floating Quick Panel (Up Arrow): Audio, OpenSubtitles Tracks & Real-Time Subtitle Style/Sync
        AnimatedVisibility(
            visible = quickPanelOpen && fatalErrorMessage == null,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(tween(200)),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(tween(200)),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            AppleTvQuickTrackPanel(
                audioTracks = audioTracks,
                embeddedSubtitleTracks = embeddedSubtitleTracks,
                externalSubtitles = externalSubtitles,
                selectedExternalSubId = selectedExternalSubId,
                subtitlesDisabled = subtitlesDisabled,
                subtitleStyle = subtitleStyle,
                streamVariants = candidates,
                activeCandidate = activeCandidate,
                firstChipRequester = quickPanelFirstChipRequester,
                onSelectAudioTrack = { option ->
                    notifyDpadActivity()
                    applyTrackSelection(exoPlayer, C.TRACK_TYPE_AUDIO, option)
                },
                onSelectStreamVariant = { candidate ->
                    notifyDpadActivity()
                    ioPersistScope.launch {
                        TvProfileRepository.updateActiveProfilePlaybackPreferences(
                            preferredAudioLang = candidate.audioVariant.langCode.takeIf { it != "es" },
                            context = context,
                        )
                    }
                    if (candidate.video.source != activeCandidate?.video?.source) {
                        val resumePos = exoPlayer.currentPosition.coerceAtLeast(0L)
                        loadCandidateIntoPlayer(candidate, resumePos)
                    }
                },
                onDisableSubtitles = {
                    notifyDpadActivity()
                    subtitlesDisabled = true
                    selectedExternalSubId = null
                    exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .build()
                    ioPersistScope.launch {
                        TvProfileRepository.updateActiveProfilePlaybackPreferences(
                            subtitlesEnabled = false,
                            context = context,
                        )
                    }
                },
                onSelectEmbeddedSubtitleTrack = { option ->
                    notifyDpadActivity()
                    subtitlesDisabled = false
                    selectedExternalSubId = null
                    applyTrackSelection(exoPlayer, C.TRACK_TYPE_TEXT, option)
                    ioPersistScope.launch {
                        TvProfileRepository.updateActiveProfilePlaybackPreferences(
                            subtitlesEnabled = true,
                            context = context,
                        )
                    }
                },
                onSelectExternalSubtitle = { external ->
                    notifyDpadActivity()
                    subtitlesDisabled = false
                    selectedExternalSubId = external.id
                    ioPersistScope.launch {
                        TvProfileRepository.updateActiveProfilePlaybackPreferences(
                            subtitlesEnabled = true,
                            preferredSubtitleLang = external.langCode,
                            context = context,
                        )
                    }
                },
                onUpdateSubtitleStyle = { updatedStyle ->
                    notifyDpadActivity()
                    updateSubtitleStyle(updatedStyle)
                },
                onClosePanel = {
                    quickPanelOpen = false
                    notifyDpadActivity()
                },
            )
        }

        // Bottom Apple TV Minimalist Scrubber Bar & Metadata Overlay (3s Auto-Hide)
        AnimatedVisibility(
            visible = controlsVisible && !isResolving && fatalErrorMessage == null,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(280)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            AppleTvBottomScrubberOverlay(
                title = session.displayTitle,
                subtitle = session.displaySubtitle,
                audioBadge = activeCandidate?.audioVariant?.badge ?: "Español",
                isPlaying = isPlaying,
                positionMs = positionMs,
                bufferedPositionMs = bufferedPositionMs,
                durationMs = durationMs,
            )
        }
    }
}

@Composable
private fun AppleTvBottomScrubberOverlay(
    title: String,
    subtitle: String?,
    audioBadge: String,
    isPlaying: Boolean,
    positionMs: Long,
    bufferedPositionMs: Long,
    durationMs: Long,
) {
    val safeDuration = durationMs.coerceAtLeast(1L)
    val progressFraction = if (durationMs > 0L) {
        (positionMs.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val bufferedFraction = if (durationMs > 0L) {
        (bufferedPositionMs.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    0.0f to Color.Transparent,
                    0.35f to Color(0x9906070B),
                    1.0f to Color(0xEB06070B),
                )
            )
            .padding(horizontal = 56.dp, vertical = 34.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Color(0x30FFFFFF)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            tint = AppleTvColors.PureWhite,
                            modifier = Modifier.size(22.dp),
                        )
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.headlineSmall.copy(fontSize = 20.sp),
                            color = AppleTvColors.PureWhite,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (!subtitle.isNullOrBlank()) {
                                Text(
                                    text = subtitle,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = AppleTvColors.SecondaryText,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                text = audioBadge,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                ),
                                color = AppleTvColors.PureWhite.copy(alpha = 0.85f),
                            )
                        }
                    }
                }

                // Subtle tvOS Up-Arrow Quick Panel affordance
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color(0x29FFFFFF))
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowUp,
                        contentDescription = null,
                        tint = AppleTvColors.PureWhite,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Audio, Subtítulos y Estilos",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        color = AppleTvColors.PureWhite,
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(14.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                val trackWidth = maxWidth

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color(0x38FFFFFF)),
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth(bufferedFraction)
                        .height(4.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color(0x59FFFFFF)),
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth(progressFraction)
                        .height(4.dp)
                        .clip(RoundedCornerShape(50))
                        .background(AppleTvColors.PureWhite),
                )

                if (durationMs > 0L) {
                    val dotOffset = (trackWidth * progressFraction - 5.dp).coerceAtLeast(0.dp)
                    Box(
                        modifier = Modifier
                            .offset(x = dotOffset)
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(AppleTvColors.PureWhite),
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = formatPlayerTimestamp(positionMs),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.4.sp,
                    ),
                    color = AppleTvColors.SecondaryText,
                )

                val rightTimestamp = if (durationMs > 0L) {
                    val remaining = (durationMs - positionMs).coerceAtLeast(0L)
                    "-${formatPlayerTimestamp(remaining)}  ·  ${formatPlayerTimestamp(durationMs)}"
                } else {
                    "EN VIVO"
                }

                Text(
                    text = rightTimestamp,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.4.sp,
                    ),
                    color = AppleTvColors.SecondaryText,
                )
            }
        }
    }
}

@Composable
private fun AppleTvQuickTrackPanel(
    audioTracks: List<PlayerTrackOption>,
    embeddedSubtitleTracks: List<PlayerTrackOption>,
    externalSubtitles: List<ResolvedExternalSubtitle>,
    selectedExternalSubId: String?,
    subtitlesDisabled: Boolean,
    subtitleStyle: ProfileSubtitleStyle,
    streamVariants: List<ResolvedStreamCandidate>,
    activeCandidate: ResolvedStreamCandidate?,
    firstChipRequester: FocusRequester,
    onSelectAudioTrack: (PlayerTrackOption) -> Unit,
    onSelectStreamVariant: (ResolvedStreamCandidate) -> Unit,
    onDisableSubtitles: () -> Unit,
    onSelectEmbeddedSubtitleTrack: (PlayerTrackOption) -> Unit,
    onSelectExternalSubtitle: (ResolvedExternalSubtitle) -> Unit,
    onUpdateSubtitleStyle: (ProfileSubtitleStyle) -> Unit,
    onClosePanel: () -> Unit,
) {
    var activeTab by remember { mutableStateOf(QuickPanelTab.TRACKS) }
    val distinctVariants = remember(streamVariants.size, activeCandidate?.id) {
        streamVariants.distinctBy { it.audioVariant }
    }

    Column(
        modifier = Modifier
            .padding(horizontal = 56.dp, vertical = 24.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0xEB151822))
            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(22.dp))
            .padding(horizontal = 28.dp, vertical = 20.dp)
            .onPreviewKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown && ev.key == Key.Back) {
                    onClosePanel()
                    true
                } else {
                    false
                }
            },
    ) {
        // Segmented Quick Panel Header: Pistas vs Estilo y Sincronización en Tiempo Real
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            QuickPanelTabButton(
                label = "Pistas de Audio y Subtítulos",
                icon = Icons.Rounded.Subtitles,
                selected = activeTab == QuickPanelTab.TRACKS,
                modifier = Modifier.focusRequester(firstChipRequester),
                onClick = { activeTab = QuickPanelTab.TRACKS },
            )
            QuickPanelTabButton(
                label = "Estilo y Sincronización",
                icon = Icons.Rounded.Tune,
                selected = activeTab == QuickPanelTab.SUBTITLE_STYLE,
                onClick = { activeTab = QuickPanelTab.SUBTITLE_STYLE },
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (activeTab == QuickPanelTab.TRACKS) {
            // Audio Section
            QuickSectionHeader("AUDIO")
            Spacer(modifier = Modifier.height(8.dp))

            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (audioTracks.size > 1) {
                    itemsIndexed(audioTracks, key = { _, t -> t.id }) { _, option ->
                        QuickPanelChip(
                            label = option.label,
                            selected = option.isSelected,
                            onClick = { onSelectAudioTrack(option) },
                        )
                    }
                } else if (distinctVariants.isNotEmpty()) {
                    itemsIndexed(distinctVariants, key = { _, c -> c.id }) { _, candidate ->
                        val isCurrent = candidate.audioVariant == activeCandidate?.audioVariant
                        QuickPanelChip(
                            label = candidate.audioVariant.badge,
                            selected = isCurrent,
                            onClick = { onSelectStreamVariant(candidate) },
                        )
                    }
                } else {
                    item {
                        QuickPanelChip(
                            label = activeCandidate?.audioVariant?.badge ?: "Español Principal",
                            selected = true,
                            onClick = {},
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Subtitles Section (OpenSubtitles + Stream/Embedded)
            QuickSectionHeader("SUBTÍTULOS (AUTOMÁTICOS EN ESPAÑOL / LATINO)")
            Spacer(modifier = Modifier.height(8.dp))

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.onKeyEvent { ev ->
                    if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionDown) {
                        onClosePanel()
                        true
                    } else {
                        false
                    }
                },
            ) {
                item(key = "sub_off") {
                    QuickPanelChip(
                        label = "Desactivados",
                        selected = subtitlesDisabled,
                        onClick = onDisableSubtitles,
                    )
                }
                itemsIndexed(externalSubtitles, key = { _, s -> "ext:${s.id}" }) { _, extSub ->
                    QuickPanelChip(
                        label = extSub.label,
                        selected = !subtitlesDisabled && selectedExternalSubId == extSub.id,
                        onClick = { onSelectExternalSubtitle(extSub) },
                    )
                }
                itemsIndexed(embeddedSubtitleTracks, key = { _, t -> "emb:${t.id}" }) { _, subOption ->
                    QuickPanelChip(
                        label = subOption.label,
                        selected = !subtitlesDisabled && selectedExternalSubId == null && subOption.isSelected,
                        onClick = { onSelectEmbeddedSubtitleTrack(subOption) },
                    )
                }
            }
        } else {
            // REAL-TIME SUBTITLE STYLE & MILLISECOND SYNC OFFSET PANEL
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                // Column 1: Font Size & Color
                Column(modifier = Modifier.weight(1f)) {
                    QuickSectionHeader("TAMAÑO DE FUENTE")
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val sizeOptions = listOf(
                            0.82f to "Pequeño",
                            1.0f to "Normal",
                            1.25f to "Grande",
                            1.50f to "Extra",
                        )
                        itemsIndexed(sizeOptions) { _, (scaleVal, label) ->
                            val chosen = kotlin.math.abs(subtitleStyle.sizeScale - scaleVal) < 0.05f
                            QuickPanelChip(
                                label = label,
                                selected = chosen,
                                onClick = {
                                    onUpdateSubtitleStyle(subtitleStyle.copy(sizeScale = scaleVal))
                                },
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    QuickSectionHeader("COLOR DE FUENTE")
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val colorOptions = listOf(
                            "#FFFFFF" to "Blanco",
                            "#FFD600" to "Amarillo",
                            "#40C4FF" to "Cian",
                            "#69F0AE" to "Verde",
                        )
                        itemsIndexed(colorOptions) { _, (hex, label) ->
                            val chosen = subtitleStyle.textColorHex.equals(hex, ignoreCase = true)
                            QuickPanelChip(
                                label = label,
                                selected = chosen,
                                onClick = {
                                    onUpdateSubtitleStyle(subtitleStyle.copy(textColorHex = hex))
                                },
                            )
                        }
                    }
                }

                // Column 2: Background Box Opacity & Millisecond Sync Offset
                Column(modifier = Modifier.weight(1f)) {
                    QuickSectionHeader("OPACIDAD DEL FONDO")
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val opacityOptions = listOf(
                            0.0f to "0%",
                            0.40f to "40%",
                            0.75f to "75%",
                            1.0f to "100%",
                        )
                        itemsIndexed(opacityOptions) { _, (alphaVal, label) ->
                            val chosen = kotlin.math.abs(subtitleStyle.bgOpacity - alphaVal) < 0.08f
                            QuickPanelChip(
                                label = label,
                                selected = chosen,
                                onClick = {
                                    onUpdateSubtitleStyle(subtitleStyle.copy(bgOpacity = alphaVal))
                                },
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    val signPrefix = if (subtitleStyle.syncOffsetMs > 0L) "+" else ""
                    QuickSectionHeader("SINCRONIZACIÓN DE TIEMPO (${signPrefix}${subtitleStyle.syncOffsetMs} ms)")
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.onKeyEvent { ev ->
                            if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionDown) {
                                onClosePanel()
                                true
                            } else {
                                false
                            }
                        },
                    ) {
                        item {
                            QuickPanelChip(
                                label = "-250 ms",
                                selected = false,
                                onClick = {
                                    val next = (subtitleStyle.syncOffsetMs - 250L).coerceIn(-15_000L, 15_000L)
                                    onUpdateSubtitleStyle(subtitleStyle.copy(syncOffsetMs = next))
                                },
                            )
                        }
                        item {
                            QuickPanelChip(
                                label = "-100 ms",
                                selected = false,
                                onClick = {
                                    val next = (subtitleStyle.syncOffsetMs - 100L).coerceIn(-15_000L, 15_000L)
                                    onUpdateSubtitleStyle(subtitleStyle.copy(syncOffsetMs = next))
                                },
                            )
                        }
                        item {
                            QuickPanelChip(
                                label = "0 ms",
                                selected = subtitleStyle.syncOffsetMs == 0L,
                                onClick = {
                                    onUpdateSubtitleStyle(subtitleStyle.copy(syncOffsetMs = 0L))
                                },
                            )
                        }
                        item {
                            QuickPanelChip(
                                label = "+100 ms",
                                selected = false,
                                onClick = {
                                    val next = (subtitleStyle.syncOffsetMs + 100L).coerceIn(-15_000L, 15_000L)
                                    onUpdateSubtitleStyle(subtitleStyle.copy(syncOffsetMs = next))
                                },
                            )
                        }
                        item {
                            QuickPanelChip(
                                label = "+250 ms",
                                selected = false,
                                onClick = {
                                    val next = (subtitleStyle.syncOffsetMs + 250L).coerceIn(-15_000L, 15_000L)
                                    onUpdateSubtitleStyle(subtitleStyle.copy(syncOffsetMs = next))
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.bodyMedium.copy(
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.0.sp,
        ),
        color = AppleTvColors.MutedText,
    )
}

@Composable
private fun QuickPanelTabButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = ButtonDefaults.shape(shape = RoundedCornerShape(50)),
        scale = ButtonDefaults.scale(focusedScale = 1.05f),
        border = ButtonDefaults.border(
            border = Border(
                border = BorderStroke(1.dp, if (selected) AppleTvColors.PureWhite else Color(0x2EFFFFFF)),
                shape = RoundedCornerShape(50),
            ),
            focusedBorder = Border(
                border = BorderStroke(2.dp, AppleTvColors.PureWhite),
                shape = RoundedCornerShape(50),
            ),
        ),
        colors = ButtonDefaults.colors(
            containerColor = if (selected) Color(0x3BFFFFFF) else Color(0x330D0F16),
            contentColor = if (selected) AppleTvColors.PureWhite else AppleTvColors.SecondaryText,
            focusedContainerColor = AppleTvColors.PureWhite,
            focusedContentColor = AppleTvColors.Obsidian,
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 12.5.sp),
        )
    }
}

@Composable
private fun QuickPanelChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = ButtonDefaults.shape(shape = RoundedCornerShape(50)),
        scale = ButtonDefaults.scale(focusedScale = 1.06f),
        glow = ButtonDefaults.glow(
            focusedGlow = Glow(
                elevationColor = Color(0x66FFFFFF),
                elevation = 12.dp,
            )
        ),
        border = ButtonDefaults.border(
            border = Border(
                border = BorderStroke(1.dp, if (selected) AppleTvColors.PureWhite else Color(0x2EFFFFFF)),
                shape = RoundedCornerShape(50),
            ),
            focusedBorder = Border(
                border = BorderStroke(2.dp, AppleTvColors.PureWhite),
                shape = RoundedCornerShape(50),
            ),
        ),
        colors = ButtonDefaults.colors(
            containerColor = if (selected) Color(0x3BFFFFFF) else Color(0x4D0D0F16),
            contentColor = if (selected) AppleTvColors.PureWhite else AppleTvColors.SecondaryText,
            focusedContainerColor = AppleTvColors.PureWhite,
            focusedContentColor = AppleTvColors.Obsidian,
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 7.dp),
    ) {
        Text(
            text = if (selected) "✓  $label" else label,
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 12.5.sp),
        )
    }
}

private fun parseSubtitleHexColor(hex: String): Color {
    return runCatching {
        Color(android.graphics.Color.parseColor(hex))
    }.getOrDefault(Color.White)
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun extractTrackOptions(
    tracks: Tracks,
    trackType: Int,
    fallbackPrefix: String,
): List<PlayerTrackOption> {
    val options = mutableListOf<PlayerTrackOption>()
    tracks.groups.forEachIndexed { groupIdx, group ->
        if (group.type != trackType) return@forEachIndexed
        for (trackIdx in 0 until group.length) {
            if (!group.isTrackSupported(trackIdx)) continue
            val format = group.getTrackFormat(trackIdx)
            val langDisplay = format.language?.let { code ->
                Locale.forLanguageTag(code)
                    .getDisplayLanguage(Locale("es"))
                    .replaceFirstChar { ch -> ch.titlecase(Locale("es")) }
                    .takeIf { it.isNotBlank() && it != "Und" }
            }
            val labelText = listOfNotNull(
                format.label?.takeIf { it.isNotBlank() },
                langDisplay,
            ).distinct().joinToString(" · ").ifBlank {
                "$fallbackPrefix ${options.size + 1}"
            }

            options.add(
                PlayerTrackOption(
                    id = "$trackType:$groupIdx:$trackIdx",
                    label = labelText,
                    isSelected = group.isTrackSelected(trackIdx),
                    groupIndex = groupIdx,
                    trackIndex = trackIdx,
                )
            )
        }
    }
    return options
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun applyTrackSelection(
    player: ExoPlayer,
    trackType: Int,
    option: PlayerTrackOption,
) {
    val group = player.currentTracks.groups.getOrNull(option.groupIndex) ?: return
    if (option.trackIndex !in 0 until group.length) return
    val override = TrackSelectionOverride(group.mediaTrackGroup, listOf(option.trackIndex))
    player.trackSelectionParameters = player.trackSelectionParameters
        .buildUpon()
        .setOverrideForType(override)
        .setTrackTypeDisabled(trackType, false)
        .build()
}

internal fun formatPlayerTimestamp(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0L)) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}
