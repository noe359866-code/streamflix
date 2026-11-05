@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.streamflixreborn.streamflix.tv.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Carousel
import androidx.tv.material3.CarouselDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.rememberCarouselState
import coil.compose.AsyncImage
import com.streamflixreborn.streamflix.tv.model.TvMediaCardItem
import com.streamflixreborn.streamflix.tv.model.TvMediaKind
import com.streamflixreborn.streamflix.tv.theme.AppleTvColors
import com.streamflixreborn.streamflix.utils.TvImageOptimizer

@Composable
fun AppleTvHeroBanner(
    items: List<TvMediaCardItem>,
    verticalScrollOffsetPx: Float,
    onPlayNow: (TvMediaCardItem) -> Unit,
    onOpenDetail: (TvMediaCardItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return

    val context = LocalContext.current
    val carouselState = rememberCarouselState()
    val activeIndex = carouselState.activeItemIndex.coerceIn(0, items.lastIndex)
    val activeItem = items[activeIndex]

    val parallaxShift by animateFloatAsState(
        targetValue = (verticalScrollOffsetPx * 0.36f).coerceIn(0f, 180f),
        animationSpec = tween(durationMillis = 120),
        label = "heroParallaxShift",
    )

    val bannerAlpha = (1f - (verticalScrollOffsetPx / 720f)).coerceIn(0.22f, 1f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(430.dp),
    ) {
        // Fluid crossfade background banner with parallax effect
        Crossfade(
            targetState = activeItem.backgroundUrl ?: activeItem.posterUrl,
            animationSpec = tween(durationMillis = 680),
            label = "heroBackgroundCrossfade",
        ) { backdropUrl ->
            val request = remember(backdropUrl) {
                TvImageOptimizer.backdropRequest(context, backdropUrl)
            }
            AsyncImage(
                model = request,
                contentDescription = activeItem.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationY = parallaxShift
                        scaleX = 1.05f
                        scaleY = 1.05f
                        alpha = bannerAlpha
                    },
            )
        }

        // Apple TV multi-layer cinematic dark vignette
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0.0f to AppleTvColors.Obsidian.copy(alpha = 0.94f),
                        0.45f to AppleTvColors.Obsidian.copy(alpha = 0.62f),
                        0.82f to Color.Transparent,
                    )
                )
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to AppleTvColors.Obsidian.copy(alpha = 0.58f),
                        0.32f to Color.Transparent,
                        0.72f to AppleTvColors.Obsidian.copy(alpha = 0.68f),
                        1.0f to AppleTvColors.Obsidian,
                    )
                )
        )

        Carousel(
            itemCount = items.size,
            carouselState = carouselState,
            autoScrollDurationMillis = 7_500L,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 68.dp),
            carouselIndicator = {
                CarouselDefaults.IndicatorRow(
                    itemCount = items.size,
                    activeItemIndex = carouselState.activeItemIndex,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 52.dp, bottom = 24.dp),
                )
            },
        ) { index ->
            val item = items[index]
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 52.dp, end = 52.dp, bottom = 26.dp),
                verticalArrangement = Arrangement.Bottom,
            ) {
                // Content type + Dorama / Rating metadata row
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HeroPillBadge(
                        text = if (item.kind == TvMediaKind.SERIES) "SERIE" else "PELÍCULA",
                    )
                    if (item.isDoramaOrAsian) {
                        HeroPillBadge(
                            text = "DORAMA",
                            backgroundColor = Color(0x443E8BFF),
                        )
                    }
                    item.year?.let { year ->
                        Text(
                            text = year,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            color = AppleTvColors.SecondaryText,
                        )
                    }
                    item.rating?.let { rating ->
                        Text(
                            text = "★ $rating",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = AppleTvColors.RatingGold,
                        )
                    }
                    if (item.genres.isNotEmpty()) {
                        Text(
                            text = item.genres.joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppleTvColors.SecondaryText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Title Logo or Display Headline
                if (!item.logoUrl.isNullOrBlank()) {
                    val logoReq = remember(item.logoUrl) {
                        TvImageOptimizer.logoRequest(context, item.logoUrl)
                    }
                    AsyncImage(
                        model = logoReq,
                        contentDescription = item.title,
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.CenterStart,
                        modifier = Modifier
                            .widthIn(max = 360.dp)
                            .heightIn(max = 86.dp),
                    )
                } else {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.displayMedium,
                        color = AppleTvColors.PureWhite,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 640.dp),
                    )
                }

                if (!item.overview.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = item.overview,
                        style = MaterialTheme.typography.bodyLarge,
                        color = AppleTvColors.SecondaryText,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 580.dp),
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Primary Direct Play CTA + Details CTA
                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = { onPlayNow(item) },
                        shape = ButtonDefaults.shape(shape = RoundedCornerShape(50)),
                        scale = ButtonDefaults.scale(focusedScale = 1.07f),
                        glow = ButtonDefaults.glow(
                            focusedGlow = Glow(
                                elevationColor = AppleTvColors.FocusGlow,
                                elevation = 18.dp,
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
                        contentPadding = PaddingValues(horizontal = 26.dp, vertical = 12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Reproducir",
                            style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp),
                        )
                    }

                    Button(
                        onClick = { onOpenDetail(item) },
                        shape = ButtonDefaults.shape(shape = RoundedCornerShape(50)),
                        scale = ButtonDefaults.scale(focusedScale = 1.07f),
                        glow = ButtonDefaults.glow(
                            focusedGlow = Glow(
                                elevationColor = Color(0x66FFFFFF),
                                elevation = 14.dp,
                            )
                        ),
                        border = ButtonDefaults.border(
                            border = Border(
                                border = BorderStroke(1.dp, Color(0x40FFFFFF)),
                                shape = RoundedCornerShape(50),
                            ),
                            focusedBorder = Border(
                                border = BorderStroke(2.dp, AppleTvColors.PureWhite),
                                shape = RoundedCornerShape(50),
                            ),
                        ),
                        colors = ButtonDefaults.colors(
                            containerColor = Color(0x991C202B),
                            contentColor = AppleTvColors.PureWhite,
                            focusedContainerColor = AppleTvColors.PureWhite,
                            focusedContentColor = AppleTvColors.Obsidian,
                        ),
                        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Info,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Detalles",
                            style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroPillBadge(
    text: String,
    backgroundColor: Color = Color(0x33FFFFFF),
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(backgroundColor)
            .border(0.75.dp, Color(0x38FFFFFF), RoundedCornerShape(6.dp))
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
