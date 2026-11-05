@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.streamflixreborn.streamflix.tv.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Glow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.streamflixreborn.streamflix.profiles.TvProfileWatchHistoryEntity
import com.streamflixreborn.streamflix.tv.model.TvMediaCardItem
import com.streamflixreborn.streamflix.tv.model.TvShelfModel
import com.streamflixreborn.streamflix.tv.theme.AppleTvColors
import com.streamflixreborn.streamflix.utils.TvImageOptimizer

@Composable
fun AppleTvShelfRow(
    shelf: TvShelfModel,
    onItemSelected: (TvMediaCardItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
    ) {
        Text(
            text = shelf.title,
            style = MaterialTheme.typography.headlineSmall,
            color = AppleTvColors.PureWhite,
            modifier = Modifier.padding(horizontal = 52.dp, vertical = 6.dp),
        )

        Spacer(modifier = Modifier.height(6.dp))

        LazyRow(
            contentPadding = PaddingValues(horizontal = 52.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
                items(
                    items = shelf.items,
                    key = { "${shelf.id}:${it.id}" },
                    contentType = { "poster_card" },
                ) { item ->
                    AppleTvPosterCard(
                        item = item,
                        onClick = { onItemSelected(item) },
                    )
                }
            }
        }
    }

@Composable
fun AppleTvContinueWatchingShelf(
    items: List<TvProfileWatchHistoryEntity>,
    onResumeItem: (TvProfileWatchHistoryEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
    ) {
        Text(
            text = "Continuar viendo",
            style = MaterialTheme.typography.headlineSmall,
            color = AppleTvColors.PureWhite,
            modifier = Modifier.padding(horizontal = 52.dp, vertical = 6.dp),
        )

        Spacer(modifier = Modifier.height(6.dp))

        LazyRow(
            contentPadding = PaddingValues(horizontal = 52.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            items(
                items = items,
                key = { "cw:${it.profileId}:${it.mediaKey}" },
                contentType = { "continue_watching_card" },
            ) { entry ->
                var isFocused by remember { mutableStateOf(false) }
                val cardShape = RoundedCornerShape(14.dp)
                val imgUrl = entry.backgroundUrl ?: entry.posterUrl
                val imgReq = remember(imgUrl) {
                    TvImageOptimizer.episodeThumbRequest(context, imgUrl)
                }

                Column(modifier = Modifier.width(246.dp)) {
                    Card(
                        onClick = { onResumeItem(entry) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .onFocusChanged { isFocused = it.isFocused },
                        shape = CardDefaults.shape(shape = cardShape),
                        scale = CardDefaults.scale(focusedScale = 1.1f),
                        border = CardDefaults.border(
                            border = Border(
                                border = BorderStroke(1.dp, Color(0x22FFFFFF)),
                                shape = cardShape,
                            ),
                            focusedBorder = Border(
                                border = BorderStroke(2.4.dp, AppleTvColors.FocusBorder),
                                shape = cardShape,
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
                                model = imgReq,
                                contentDescription = entry.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.verticalGradient(
                                            0.45f to Color.Transparent,
                                            1.0f to Color(0xD906070B),
                                        )
                                    )
                            )
                            // Minimalist bottom progress bar inside the card
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                                    .fillMaxWidth()
                                    .height(3.5.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(Color(0x45FFFFFF)),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(entry.progressFraction.coerceIn(0.05f, 1f))
                                        .height(3.5.dp)
                                        .clip(RoundedCornerShape(50))
                                        .background(AppleTvColors.PureWhite),
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = entry.title,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = 14.sp,
                            fontWeight = if (isFocused) FontWeight.SemiBold else FontWeight.Medium,
                        ),
                        color = if (isFocused) AppleTvColors.PureWhite else AppleTvColors.SecondaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!entry.subtitle.isNullOrBlank()) {
                        Text(
                            text = entry.subtitle,
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                            color = AppleTvColors.MutedText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AppleTvPosterCard(
    item: TvMediaCardItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    cardFocusModifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var isFocused by remember { mutableStateOf(false) }
    val cardShape = RoundedCornerShape(14.dp)

    val titleColor by animateColorAsState(
        targetValue = if (isFocused) AppleTvColors.PureWhite else AppleTvColors.SecondaryText.copy(alpha = 0.82f),
        animationSpec = tween(durationMillis = 160),
        label = "posterTitleColor",
    )

    val posterRequest = remember(item.posterUrl) {
        TvImageOptimizer.posterRequest(context, item.posterUrl)
    }

    Column(
        modifier = modifier.width(154.dp),
    ) {
        Card(
            onClick = onClick,
            modifier = cardFocusModifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .onFocusChanged { isFocused = it.isFocused },
            shape = CardDefaults.shape(shape = cardShape),
            // Smooth 1.1x zoom-in on focus
            scale = CardDefaults.scale(
                scale = 1.0f,
                focusedScale = 1.1f,
                pressedScale = 1.05f,
            ),
            // Highlighted white border on focus
            border = CardDefaults.border(
                border = Border(
                    border = BorderStroke(1.dp, Color(0x1FFFFFFF)),
                    shape = cardShape,
                ),
                focusedBorder = Border(
                    border = BorderStroke(2.4.dp, AppleTvColors.FocusBorder),
                    inset = 0.dp,
                    shape = cardShape,
                ),
            ),
            // Elevated shadow glow on focus
            glow = CardDefaults.glow(
                focusedGlow = Glow(
                    elevationColor = AppleTvColors.FocusGlow,
                    elevation = 20.dp,
                )
            ),
            colors = CardDefaults.colors(
                containerColor = AppleTvColors.DeepCharcoal,
            ),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (!item.posterUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = posterRequest,
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color(0xFF1E2230), Color(0xFF0E1017))
                                )
                            )
                            .padding(14.dp),
                        contentAlignment = Alignment.BottomStart,
                    ) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = AppleTvColors.PureWhite,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                // Subtle rating pill at top-right when focused
                if (isFocused && !item.rating.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xCC0B0D14))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = "★ ${item.rating}",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                            color = AppleTvColors.RatingGold,
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = item.title,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = if (isFocused) FontWeight.SemiBold else FontWeight.Normal,
            ),
            color = titleColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
