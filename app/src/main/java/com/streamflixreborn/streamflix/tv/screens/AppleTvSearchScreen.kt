@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)

package com.streamflixreborn.streamflix.tv.screens

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed as rowItemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamflixreborn.streamflix.search.TvVoiceQueryNormalizer
import com.streamflixreborn.streamflix.search.TvVoiceSearchActivity
import com.streamflixreborn.streamflix.tv.components.AppleTvKeyboardMode
import com.streamflixreborn.streamflix.tv.components.AppleTvPosterCard
import com.streamflixreborn.streamflix.tv.components.AppleTvVirtualKeyboard
import com.streamflixreborn.streamflix.tv.model.TvMediaCardItem
import com.streamflixreborn.streamflix.tv.theme.AppleTvColors

private const val LIVE_SEARCH_GRID_COLUMNS = 4

/**
 * Two-Column Native D-Pad Search Screen:
 * - Left Column: Live query display + In-App QWERTY Virtual Keyboard (`1.15x` focus scale + instant glow) + Voice Mic.
 * - Right Column: Quick Spanish Genre Pills + Real-Time Live-Search Poster Grid with instant Left/Right D-Pad focus handoff.
 */
@Composable
fun AppleTvSearchScreen(
    query: String,
    selectedGenre: String?,
    quickGenres: List<Pair<String, String>>,
    isSearching: Boolean,
    results: List<TvMediaCardItem>,
    onQueryChange: (String) -> Unit,
    onSelectGenre: (String) -> Unit,
    onOpenDetail: (TvMediaCardItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val keyboardEntryRequester = remember { FocusRequester() }
    val firstResultRequester = remember { FocusRequester() }

    val voiceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val matches = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val rawSpoken = matches?.firstOrNull()?.trim().orEmpty()
            if (rawSpoken.isNotEmpty()) {
                onQueryChange(TvVoiceQueryNormalizer.normalizeSpokenQuery(rawSpoken))
            }
        }
    }

    fun launchVoiceSearch() {
        val recognizer = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-419")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Di el nombre de una película o serie…")
        }
        val launchedInline = runCatching {
            voiceLauncher.launch(recognizer)
            true
        }.getOrDefault(false)

        if (!launchedInline) {
            runCatching {
                context.startActivity(
                    Intent(context, TvVoiceSearchActivity::class.java).apply {
                        putExtra(TvVoiceSearchActivity.EXTRA_AUTO_START_SPEECH, true)
                    }
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        runCatching { keyboardEntryRequester.requestFocus() }
    }

    Row(
        modifier = modifier
            .fillMaxSize()
            .padding(start = 44.dp, end = 44.dp, top = 8.dp, bottom = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        // LEFT COLUMN: Live Query Readout + Native In-App QWERTY D-Pad Keyboard
        Column(
            modifier = Modifier
                .width(406.dp)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Frosted Live Query Display Box (never invokes sluggish OS IME)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xE6181C2A))
                    .border(
                        width = 1.5.dp,
                        color = if (query.isNotEmpty()) AppleTvColors.PureWhite else Color(0x36FFFFFF),
                        shape = RoundedCornerShape(16.dp),
                    )
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = "Buscar",
                    tint = if (query.isNotEmpty()) AppleTvColors.PureWhite else AppleTvColors.SecondaryText,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = if (query.isEmpty()) "Películas, series o doramas…" else "$query▏",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = if (query.isEmpty()) FontWeight.Normal else FontWeight.SemiBold,
                    ),
                    color = if (query.isEmpty()) AppleTvColors.MutedText else AppleTvColors.PureWhite,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Rounded.Mic,
                    contentDescription = "Búsqueda por voz",
                    tint = AppleTvColors.SecondaryText,
                    modifier = Modifier.size(18.dp),
                )
            }

            // Native D-Pad QWERTY Virtual Keyboard (1.15x focus scale + instant glow + Right handoff to posters)
            AppleTvVirtualKeyboard(
                value = query,
                onValueChange = onQueryChange,
                mode = AppleTvKeyboardMode.SEARCH,
                initialFocusRequester = keyboardEntryRequester,
                rightExitFocusRequester = firstResultRequester,
                canExitRight = results.isNotEmpty() && !isSearching,
                onVoiceSearchClick = ::launchVoiceSearch,
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                text = "Pulsa ▶ en el control remoto para saltar directo a los pósters.",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.5.sp),
                color = AppleTvColors.MutedText,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
        }

        // RIGHT COLUMN: Quick Spanish Genre Pills + Real-Time Live-Search Poster Grid
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        ) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                rowItemsIndexed(quickGenres, key = { _, pair -> pair.first }) { idx, (genreId, spanishName) ->
                    val active = selectedGenre == genreId && query.isBlank()
                    Button(
                        onClick = { onSelectGenre(genreId) },
                        modifier = if (idx == 0) {
                            Modifier.focusProperties { left = keyboardEntryRequester }
                        } else {
                            Modifier
                        },
                        shape = ButtonDefaults.shape(shape = RoundedCornerShape(50)),
                        scale = ButtonDefaults.scale(focusedScale = 1.08f),
                        glow = ButtonDefaults.glow(
                            focusedGlow = Glow(
                                elevationColor = Color(0x66FFFFFF),
                                elevation = 12.dp,
                            )
                        ),
                        border = ButtonDefaults.border(
                            border = Border(
                                border = BorderStroke(
                                    1.dp,
                                    if (active) AppleTvColors.PureWhite else Color(0x26FFFFFF),
                                ),
                                shape = RoundedCornerShape(50),
                            ),
                            focusedBorder = Border(
                                border = BorderStroke(2.dp, AppleTvColors.PureWhite),
                                shape = RoundedCornerShape(50),
                            ),
                        ),
                        colors = ButtonDefaults.colors(
                            containerColor = if (active) Color(0x38FFFFFF) else AppleTvColors.GlassSurface,
                            contentColor = if (active) AppleTvColors.PureWhite else AppleTvColors.SecondaryText,
                            focusedContainerColor = AppleTvColors.PureWhite,
                            focusedContentColor = AppleTvColors.Obsidian,
                        ),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 7.dp),
                    ) {
                        Text(
                            text = spanishName,
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold,
                            ),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            when {
                isSearching -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(
                                color = AppleTvColors.PureWhite,
                                strokeWidth = 2.5.dp,
                                modifier = Modifier.size(34.dp),
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Buscando en tiempo real…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = AppleTvColors.SecondaryText,
                            )
                        }
                    }
                }

                results.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Sin resultados para la búsqueda actual.",
                            style = MaterialTheme.typography.titleMedium,
                            color = AppleTvColors.SecondaryText,
                        )
                    }
                }

                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(LIVE_SEARCH_GRID_COLUMNS),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                        verticalArrangement = Arrangement.spacedBy(22.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    ) {
                        itemsIndexed(
                            items = results,
                            key = { _, item -> "search:${item.kind}:${item.id}" },
                        ) { index, item ->
                            val isFirstPoster = index == 0
                            val isLeftmostColumn = index % LIVE_SEARCH_GRID_COLUMNS == 0
                            val focusMod = Modifier
                                .let { m -> if (isFirstPoster) m.focusRequester(firstResultRequester) else m }
                                .focusProperties {
                                    if (isLeftmostColumn) {
                                        left = keyboardEntryRequester
                                    }
                                }

                            AppleTvPosterCard(
                                item = item,
                                onClick = { onOpenDetail(item) },
                                cardFocusModifier = focusMod,
                            )
                        }
                    }
                }
            }
        }
    }
}
