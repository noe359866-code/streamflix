@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.streamflixreborn.streamflix.tv.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.streamflixreborn.streamflix.tv.model.TvEpisodeModel
import com.streamflixreborn.streamflix.tv.model.TvMediaDetail
import com.streamflixreborn.streamflix.tv.model.TvMediaKind
import com.streamflixreborn.streamflix.tv.theme.AppleTvColors
import com.streamflixreborn.streamflix.tv.viewmodel.AppleTvDetailUiState
import com.streamflixreborn.streamflix.utils.TvImageOptimizer

@Composable
fun AppleTvDetailScreen(
    uiState: AppleTvDetailUiState,
    onPlayPrimary: (TvMediaDetail) -> Unit,
    onPlayEpisode: (TvMediaDetail, TvEpisodeModel) -> Unit,
    onSelectSeason: (Int) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)

    val detail = uiState.detail ?: return
    val context = LocalContext.current
    val playFocusRequester = remember { FocusRequester() }

    LaunchedEffect(detail.id) {
        runCatching { playFocusRequester.requestFocus() }
    }

    val backdropRequest = remember(detail.backgroundUrl) {
        TvImageOptimizer.backdropRequest(context, detail.backgroundUrl)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AppleTvColors.Obsidian),
    ) {
        // Immersive full-bleed background artwork
        AsyncImage(
            model = backdropRequest,
            contentDescription = detail.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )

        // Deep Apple TV dark vignette
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0.0f to AppleTvColors.Obsidian.copy(alpha = 0.96f),
                        0.48f to AppleTvColors.Obsidian.copy(alpha = 0.78f),
                        0.85f to AppleTvColors.Obsidian.copy(alpha = 0.35f),
                        1.0f to AppleTvColors.Obsidian.copy(alpha = 0.55f),
                    )
                )
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to AppleTvColors.Obsidian.copy(alpha = 0.38f),
                        0.45f to AppleTvColors.Obsidian.copy(alpha = 0.65f),
                        0.82f to AppleTvColors.Obsidian.copy(alpha = 0.94f),
                        1.0f to AppleTvColors.Obsidian,
                    )
                )
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 54.dp, bottom = 56.dp),
        ) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 56.dp),
                ) {
                    // Title Logo or Display Title
                    if (!detail.logoUrl.isNullOrBlank()) {
                        val logoReq = remember(detail.logoUrl) {
                            TvImageOptimizer.logoRequest(context, detail.logoUrl)
                        }
                        AsyncImage(
                            model = logoReq,
                            contentDescription = detail.title,
                            contentScale = ContentScale.Fit,
                            alignment = Alignment.CenterStart,
                            modifier = Modifier
                                .widthIn(max = 400.dp)
                                .heightIn(max = 98.dp),
                        )
                    } else {
                        Text(
                            text = detail.title,
                            style = MaterialTheme.typography.displayMedium,
                            color = AppleTvColors.PureWhite,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 680.dp),
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Concise Apple TV metadata strip
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DetailBadge(
                            text = if (detail.kind == TvMediaKind.SERIES) "SERIE" else "PELÍCULA",
                        )
                        DetailBadge(text = "ESPAÑOL")
                        if (detail.isDoramaOrAsian) {
                            DetailBadge(text = "DORAMA")
                        }
                        detail.rating?.let { rating ->
                            Text(
                                text = "★ $rating",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = AppleTvColors.RatingGold,
                            )
                        }
                        detail.year?.let { year ->
                            Text(
                                text = year,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                color = AppleTvColors.SecondaryText,
                            )
                        }
                        detail.runtimeFormatted?.let { runtime ->
                            Text(
                                text = runtime,
                                style = MaterialTheme.typography.bodyMedium,
                                color = AppleTvColors.SecondaryText,
                            )
                        }
                        if (detail.genres.isNotEmpty()) {
                            Text(
                                text = detail.genres.joinToString(" · "),
                                style = MaterialTheme.typography.bodyMedium,
                                color = AppleTvColors.SecondaryText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Concise Spanish synopsis
                    Text(
                        text = detail.overview,
                        style = MaterialTheme.typography.bodyLarge,
                        color = AppleTvColors.PureWhite.copy(alpha = 0.88f),
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 650.dp),
                    )

                    if (detail.castNames.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Reparto: ${detail.castNames.joinToString(", ")}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppleTvColors.MutedText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 650.dp),
                        )
                    }

                    Spacer(modifier = Modifier.height(26.dp))

                    // Single prominent action button: "Reproducir" (Zero-Provider, auto-resolves in background)
                    Button(
                        onClick = { onPlayPrimary(detail) },
                        modifier = Modifier.focusRequester(playFocusRequester),
                        shape = ButtonDefaults.shape(shape = RoundedCornerShape(50)),
                        scale = ButtonDefaults.scale(focusedScale = 1.07f),
                        glow = ButtonDefaults.glow(
                            focusedGlow = Glow(
                                elevationColor = AppleTvColors.FocusGlow,
                                elevation = 20.dp,
                            )
                        ),
                        border = ButtonDefaults.border(
                            focusedBorder = Border(
                                border = BorderStroke(2.dp, AppleTvColors.PureWhite),
                                shape = RoundedCornerShape(50),
                            )
                        ),
                        colors = ButtonDefaults.colors(
                            containerColor = AppleTvColors.PureWhite,
                            contentColor = AppleTvColors.Obsidian,
                            focusedContainerColor = AppleTvColors.PureWhite,
                            focusedContentColor = AppleTvColors.Obsidian,
                        ),
                        contentPadding = PaddingValues(horizontal = 32.dp, vertical = 14.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Reproducir",
                            style = MaterialTheme.typography.labelLarge.copy(fontSize = 16.sp),
                        )
                    }
                }
            }

            // Series Season Selector + 16:9 Episode Cards Shelf
            if (detail.kind == TvMediaKind.SERIES && detail.seasons.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(34.dp))

                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 56.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(detail.seasons, key = { it.id }) { season ->
                            val isSelected = season.number == detail.selectedSeasonNumber
                            Button(
                                onClick = { onSelectSeason(season.number) },
                                shape = ButtonDefaults.shape(shape = RoundedCornerShape(50)),
                                scale = ButtonDefaults.scale(focusedScale = 1.06f),
                                border = ButtonDefaults.border(
                                    border = Border(
                                        border = BorderStroke(
                                            1.dp,
                                            if (isSelected) AppleTvColors.PureWhite else Color(0x28FFFFFF),
                                        ),
                                        shape = RoundedCornerShape(50),
                                    ),
                                    focusedBorder = Border(
                                        border = BorderStroke(2.dp, AppleTvColors.PureWhite),
                                        shape = RoundedCornerShape(50),
                                    ),
                                ),
                                colors = ButtonDefaults.colors(
                                    containerColor = if (isSelected) Color(0x36FFFFFF) else AppleTvColors.GlassSurface,
                                    contentColor = if (isSelected) AppleTvColors.PureWhite else AppleTvColors.SecondaryText,
                                    focusedContainerColor = AppleTvColors.PureWhite,
                                    focusedContentColor = AppleTvColors.Obsidian,
                                ),
                                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
                            ) {
                                Text(
                                    text = season.title,
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 56.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        items(
                            items = detail.episodes,
                            key = { it.id },
                        ) { episode ->
                            AppleTvEpisodeCard(
                                episode = episode,
                                onClick = { onPlayEpisode(detail, episode) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppleTvEpisodeCard(
    episode: TvEpisodeModel,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(14.dp)
    val thumbReq = remember(episode.thumbnailUrl) {
        TvImageOptimizer.episodeThumbRequest(context, episode.thumbnailUrl)
    }

    Column(
        modifier = Modifier.width(248.dp),
    ) {
        Card(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
            shape = CardDefaults.shape(shape = shape),
            scale = CardDefaults.scale(focusedScale = 1.1f),
            border = CardDefaults.border(
                border = Border(
                    border = BorderStroke(1.dp, Color(0x24FFFFFF)),
                    shape = shape,
                ),
                focusedBorder = Border(
                    border = BorderStroke(2.4.dp, AppleTvColors.FocusBorder),
                    shape = shape,
                ),
            ),
            glow = CardDefaults.glow(
                focusedGlow = Glow(
                    elevationColor = AppleTvColors.FocusGlow,
                    elevation = 18.dp,
                )
            ),
            colors = CardDefaults.colors(containerColor = AppleTvColors.DeepCharcoal),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                AsyncImage(
                    model = thumbReq,
                    contentDescription = episode.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0.45f to Color.Transparent,
                                1.0f to Color(0xCC06070B),
                            )
                        )
                )
                Text(
                    text = "EPISODIO ${episode.episodeNumber}",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.7.sp,
                    ),
                    color = AppleTvColors.PureWhite.copy(alpha = 0.9f),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(12.dp),
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = "${episode.episodeNumber}. ${episode.title}",
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp),
            color = AppleTvColors.PureWhite,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        if (!episode.overview.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = episode.overview,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                color = AppleTvColors.MutedText,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DetailBadge(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0x28FFFFFF))
            .border(0.75.dp, Color(0x36FFFFFF), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
            ),
            color = AppleTvColors.PureWhite,
        )
    }
}
