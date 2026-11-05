@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.streamflixreborn.streamflix.tv

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamflixreborn.streamflix.profiles.KidsContentFilter
import com.streamflixreborn.streamflix.profiles.ParentalPinManager
import com.streamflixreborn.streamflix.profiles.ParentalProtectionConfig
import com.streamflixreborn.streamflix.profiles.TvCustomAddonEntity
import com.streamflixreborn.streamflix.profiles.TvProfileWatchHistoryEntity
import com.streamflixreborn.streamflix.profiles.TvUserProfileEntity
import com.streamflixreborn.streamflix.search.TvSearchDeepLinkTarget
import com.streamflixreborn.streamflix.tv.components.AppleTvContinueWatchingShelf
import com.streamflixreborn.streamflix.tv.components.AppleTvHeroBanner
import com.streamflixreborn.streamflix.tv.components.AppleTvPinDialog
import com.streamflixreborn.streamflix.tv.components.AppleTvShelfRow
import com.streamflixreborn.streamflix.tv.components.AppleTvTopNav
import com.streamflixreborn.streamflix.tv.components.PinDialogMode
import com.streamflixreborn.streamflix.tv.model.TvMediaCardItem
import com.streamflixreborn.streamflix.tv.model.TvPlaybackSessionRequest
import com.streamflixreborn.streamflix.tv.model.TvShelfModel
import com.streamflixreborn.streamflix.tv.model.TvTopSection
import com.streamflixreborn.streamflix.tv.player.AppleTvPlayerScreen
import com.streamflixreborn.streamflix.tv.screens.AppleTvAddonsScreen
import com.streamflixreborn.streamflix.tv.screens.AppleTvDetailScreen
import com.streamflixreborn.streamflix.tv.screens.AppleTvProfileGateScreen
import com.streamflixreborn.streamflix.tv.screens.AppleTvSearchScreen
import com.streamflixreborn.streamflix.tv.screens.AppleTvSettingsHubScreen
import com.streamflixreborn.streamflix.tv.theme.AppleTvColors
import com.streamflixreborn.streamflix.tv.theme.AppleTvTheme
import com.streamflixreborn.streamflix.tv.viewmodel.AppleTvMainViewModel
import com.streamflixreborn.streamflix.updater.TvAutoUpdater
import kotlinx.coroutines.delay

private sealed interface TvRoute {
    data object Catalog : TvRoute
    data object Detail : TvRoute
    data class Player(
        val session: TvPlaybackSessionRequest,
        val returnToDetail: Boolean,
    ) : TvRoute
}

@Composable
fun AppleTvApp(
    onExitApp: () -> Unit,
    pendingVoiceDeepLink: TvSearchDeepLinkTarget? = null,
    onConsumeVoiceDeepLink: () -> Unit = {},
    pendingVoiceSearchQuery: String? = null,
    onConsumeVoiceSearchQuery: () -> Unit = {},
    viewModel: AppleTvMainViewModel = viewModel(),
) {
    AppleTvTheme {
        val catalogState by viewModel.catalogState.collectAsState()
        val detailState by viewModel.detailState.collectAsState()
        val profiles by viewModel.profiles.collectAsState()
        val customAddons by viewModel.customAddons.collectAsState()
        val activeProfile by viewModel.activeProfile.collectAsState()
        val parentalConfig by viewModel.parentalConfig.collectAsState()
        val isSyncingTrakt by viewModel.isSyncingTrakt.collectAsState()
        val nuvioAvatars by viewModel.nuvioAvatars.collectAsState()
        val continueWatching by viewModel.continueWatching.collectAsState()
        val favoritesShelf by viewModel.favoritesShelf.collectAsState()

        var currentRoute by remember { mutableStateOf<TvRoute>(TvRoute.Catalog) }
        var toastBannerMessage by remember { mutableStateOf<String?>(null) }
        var globalPinDialog by remember { mutableStateOf<PinDialogMode?>(null) }

        LaunchedEffect(toastBannerMessage) {
            if (toastBannerMessage != null) {
                delay(3_800L)
                toastBannerMessage = null
            }
        }

        // Silent per-session GitHub Releases update check once a profile enters the catalog
        LaunchedEffect(activeProfile?.id) {
            if (activeProfile != null) {
                TvAutoUpdater.checkSilentlyOncePerSession { available ->
                    toastBannerMessage = "Nueva versión ${available.versionTag} disponible en Ajustes → Acerca de"
                }
            }
        }

        // Route incoming Android TV Global Voice Search deep-links ("Ver Interstellar" -> Detail)
        LaunchedEffect(pendingVoiceDeepLink, activeProfile) {
            val target = pendingVoiceDeepLink ?: return@LaunchedEffect
            val opened = viewModel.openFromVoiceDeepLink(target)
            if (opened) {
                currentRoute = TvRoute.Detail
                onConsumeVoiceDeepLink()
            }
        }

        // Route incoming voice search text queries straight to the two-column Live-Search screen
        LaunchedEffect(pendingVoiceSearchQuery, activeProfile) {
            val query = pendingVoiceSearchQuery?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
            if (activeProfile != null) {
                currentRoute = TvRoute.Catalog
                viewModel.selectSection(TvTopSection.SEARCH)
                viewModel.updateSearchQuery(query)
                onConsumeVoiceSearchQuery()
            }
        }

        val isKidsProfile = activeProfile?.isKidsProfile == true
        val filteredHeroItems = remember(catalogState.heroItems, isKidsProfile) {
            KidsContentFilter.filterCards(catalogState.heroItems, isKidsProfile)
        }
        val filteredHomeShelves = remember(catalogState.homeShelves, favoritesShelf, isKidsProfile) {
            val base = KidsContentFilter.filterShelves(catalogState.homeShelves, isKidsProfile)
            if (favoritesShelf != null) listOf(favoritesShelf!!) + base else base
        }
        val filteredMovieShelves = remember(catalogState.movieShelves, isKidsProfile) {
            KidsContentFilter.filterShelves(catalogState.movieShelves, isKidsProfile)
        }
        val filteredSeriesShelves = remember(catalogState.seriesShelves, isKidsProfile) {
            KidsContentFilter.filterShelves(catalogState.seriesShelves, isKidsProfile)
        }
        val filteredQuickGenres = remember(viewModel.quickGenres, isKidsProfile) {
            KidsContentFilter.filterQuickGenres(viewModel.quickGenres, isKidsProfile)
        }

        fun requireParentalPinIfActive(
            title: String,
            subtitle: String,
            action: () -> Unit,
        ) {
            val anyPinConfigured = parentalConfig.hasMasterPin || profiles.any { it.hasPinLock }
            val needsGate = parentalConfig.isSettingsProtectionActive ||
                (isKidsProfile && anyPinConfigured)
            if (!needsGate) {
                action()
            } else {
                globalPinDialog = PinDialogMode.Verify(
                    title = title,
                    subtitle = subtitle,
                    verify = { raw ->
                        ParentalPinManager.verifyMasterOrFallbackPin(raw, activeProfile) ||
                            profiles.any { p -> p.hasPinLock && ParentalPinManager.verifyProfilePin(raw, p) }
                    },
                    onVerified = {
                        globalPinDialog = null
                        action()
                    },
                )
            }
        }

        fun requestSwitchProfile() {
            val anyPinConfigured = parentalConfig.hasMasterPin || profiles.any { it.hasPinLock }
            if (isKidsProfile && anyPinConfigured) {
                globalPinDialog = PinDialogMode.Verify(
                    title = "Salir de Modo Niños",
                    subtitle = "Ingresa un PIN parental de 4 dígitos para cambiar de perfil.",
                    verify = { raw ->
                        ParentalPinManager.verifyMasterOrFallbackPin(raw, activeProfile) ||
                            profiles.any { p -> p.hasPinLock && ParentalPinManager.verifyProfilePin(raw, p) }
                    },
                    onVerified = {
                        globalPinDialog = null
                        currentRoute = TvRoute.Catalog
                        viewModel.openProfileGate()
                    },
                )
            } else {
                currentRoute = TvRoute.Catalog
                viewModel.openProfileGate()
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(AppleTvColors.Obsidian),
        ) {
            // Initial Profile Gate (strictly up to MAX_PROFILES = 5 with Nuvio Catalog avatars + PIN Lock)
            if (activeProfile == null) {
                AppleTvProfileGateScreen(
                    profiles = profiles,
                    nuvioAvatars = nuvioAvatars,
                    onProfileSelected = { selected ->
                        viewModel.selectProfile(selected)
                        currentRoute = TvRoute.Catalog
                    },
                    onCreateProfile = viewModel::createProfile,
                    onUpdateProfile = viewModel::updateProfile,
                    onDeleteProfile = viewModel::deleteProfile,
                    onExitApp = onExitApp,
                )
            } else {
                when (val route = currentRoute) {
                    is TvRoute.Catalog -> {
                        BackHandler {
                            if (globalPinDialog != null) {
                                globalPinDialog = null
                            } else if (catalogState.activeSection != TvTopSection.HOME) {
                                viewModel.selectSection(TvTopSection.HOME)
                            } else {
                                requestSwitchProfile()
                            }
                        }

                        CatalogShell(
                            activeSection = catalogState.activeSection,
                            activeProfile = activeProfile!!,
                            profiles = profiles,
                            nuvioAvatars = nuvioAvatars,
                            parentalConfig = parentalConfig,
                            isSyncingTrakt = isSyncingTrakt,
                            isLoading = catalogState.isLoading,
                            errorMessage = catalogState.errorMessage,
                            heroItems = filteredHeroItems,
                            continueWatchingItems = continueWatching,
                            homeShelves = filteredHomeShelves,
                            movieShelves = filteredMovieShelves,
                            seriesShelves = filteredSeriesShelves,
                            searchQuery = catalogState.searchQuery,
                            selectedGenre = catalogState.selectedGenreFilter,
                            quickGenres = filteredQuickGenres,
                            isSearching = catalogState.isSearching,
                            searchResults = catalogState.searchResults,
                            customAddons = customAddons,
                            onSelectSection = { targetSection ->
                                if (targetSection == TvTopSection.ADDONS) {
                                    requireParentalPinIfActive(
                                        title = "Sección de Add-ons Protegida",
                                        subtitle = "Ingresa el PIN de 4 dígitos para administrar o instalar add-ons.",
                                    ) {
                                        viewModel.selectSection(targetSection)
                                    }
                                } else {
                                    viewModel.selectSection(targetSection)
                                }
                            },
                            onSwitchProfile = ::requestSwitchProfile,
                            onSelectProfileFromHub = viewModel::selectProfile,
                            onCreateProfile = viewModel::createProfile,
                            onDeleteProfile = viewModel::deleteProfile,
                            onRetryLoad = viewModel::loadCatalogs,
                            onSearchQueryChange = viewModel::updateSearchQuery,
                            onSelectGenre = viewModel::selectGenreFilter,
                            onProtectedAddonAction = { action ->
                                requireParentalPinIfActive(
                                    title = "Control Parental Activo",
                                    subtitle = "Ingresa el PIN de 4 dígitos para autorizar cambios en add-ons.",
                                    action = action,
                                )
                            },
                            onToggleAddonEnabled = viewModel::toggleCustomAddonEnabled,
                            onDeleteAddon = viewModel::deleteCustomAddon,
                            onAddonInstalledSuccess = {
                                viewModel.onCustomAddonInstalled()
                                toastBannerMessage = "Add-on instalado correctamente"
                            },
                            onUpdateProfile = viewModel::updateProfile,
                            onSyncTraktNow = {
                                viewModel.syncActiveProfileTraktNow()
                                toastBannerMessage = "Sincronizando historial con Trakt.tv…"
                            },
                            onDisconnectTrakt = viewModel::disconnectActiveProfileTrakt,
                            onTraktLinkedSuccess = { username ->
                                toastBannerMessage = if (!username.isNullOrBlank()) {
                                    "Cuenta de Trakt.tv (@$username) vinculada"
                                } else {
                                    "Cuenta de Trakt.tv vinculada correctamente"
                                }
                            },
                            onNotifyToast = { msg -> toastBannerMessage = msg },
                            onPlayFromHero = { card ->
                                val session = viewModel.buildPlaybackFromCard(card)
                                currentRoute = TvRoute.Player(
                                    session = session,
                                    returnToDetail = false,
                                )
                            },
                            onResumeWatchHistory = { entry ->
                                val session = viewModel.buildPlaybackFromWatchHistory(entry)
                                currentRoute = TvRoute.Player(
                                    session = session,
                                    returnToDetail = false,
                                )
                            },
                            onOpenDetail = { card ->
                                viewModel.openMediaDetail(card)
                                currentRoute = TvRoute.Detail
                            },
                        )
                    }

                    is TvRoute.Detail -> {
                        AppleTvDetailScreen(
                            uiState = detailState,
                            onPlayPrimary = { detail ->
                                val session = viewModel.buildPlaybackFromDetail(
                                    detail = detail,
                                    episode = null,
                                )
                                currentRoute = TvRoute.Player(
                                    session = session,
                                    returnToDetail = true,
                                )
                            },
                            onPlayEpisode = { detail, episode ->
                                val session = viewModel.buildPlaybackFromDetail(
                                    detail = detail,
                                    episode = episode,
                                )
                                currentRoute = TvRoute.Player(
                                    session = session,
                                    returnToDetail = true,
                                )
                            },
                            onSelectSeason = viewModel::selectSeason,
                            onBack = {
                                currentRoute = TvRoute.Catalog
                            },
                        )
                    }

                    is TvRoute.Player -> {
                        AppleTvPlayerScreen(
                            session = route.session,
                            onBack = {
                                currentRoute = if (route.returnToDetail) {
                                    TvRoute.Detail
                                } else {
                                    TvRoute.Catalog
                                }
                            },
                        )
                    }
                }
            }

            // Global 4-Digit Parental PIN Gate Modal
            AnimatedVisibility(
                visible = globalPinDialog != null,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                globalPinDialog?.let { mode ->
                    AppleTvPinDialog(
                        mode = mode,
                        onDismiss = { globalPinDialog = null },
                    )
                }
            }

            // Floating Apple TV Success Toast Notification
            AnimatedVisibility(
                visible = toastBannerMessage != null,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(tween(220)),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(tween(220)),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 36.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color(0xF2181D2A))
                        .border(1.5.dp, Color(0x6681C784), RoundedCornerShape(50))
                        .padding(horizontal = 24.dp, vertical = 14.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF81C784),
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = toastBannerMessage.orEmpty(),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = AppleTvColors.PureWhite,
                    )
                }
            }
        }
    }
}

@Composable
private fun CatalogShell(
    activeSection: TvTopSection,
    activeProfile: TvUserProfileEntity,
    profiles: List<TvUserProfileEntity>,
    nuvioAvatars: List<com.streamflixreborn.streamflix.stremio.NuvioAvatarAsset>,
    parentalConfig: ParentalProtectionConfig,
    isSyncingTrakt: Boolean,
    isLoading: Boolean,
    errorMessage: String?,
    heroItems: List<TvMediaCardItem>,
    continueWatchingItems: List<TvProfileWatchHistoryEntity>,
    homeShelves: List<TvShelfModel>,
    movieShelves: List<TvShelfModel>,
    seriesShelves: List<TvShelfModel>,
    searchQuery: String,
    selectedGenre: String?,
    quickGenres: List<Pair<String, String>>,
    isSearching: Boolean,
    searchResults: List<TvMediaCardItem>,
    customAddons: List<TvCustomAddonEntity>,
    onSelectSection: (TvTopSection) -> Unit,
    onSwitchProfile: () -> Unit,
    onSelectProfileFromHub: (TvUserProfileEntity) -> Unit,
    onCreateProfile: (
        name: String,
        avatar: com.streamflixreborn.streamflix.stremio.NuvioAvatarAsset,
        preferredAudioLang: String,
        isKidsProfile: Boolean,
        rawPin: String?,
    ) -> Unit,
    onDeleteProfile: (Long) -> Unit,
    onRetryLoad: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSelectGenre: (String) -> Unit,
    onProtectedAddonAction: (() -> Unit) -> Unit,
    onToggleAddonEnabled: (String, Boolean) -> Unit,
    onDeleteAddon: (String) -> Unit,
    onAddonInstalledSuccess: (TvCustomAddonEntity) -> Unit,
    onUpdateProfile: (TvUserProfileEntity) -> Unit,
    onSyncTraktNow: () -> Unit,
    onDisconnectTrakt: () -> Unit,
    onTraktLinkedSuccess: (String?) -> Unit,
    onNotifyToast: (String) -> Unit,
    onPlayFromHero: (TvMediaCardItem) -> Unit,
    onResumeWatchHistory: (TvProfileWatchHistoryEntity) -> Unit,
    onOpenDetail: (TvMediaCardItem) -> Unit,
) {
    val homeListState = rememberLazyListState()
    val heroParallaxOffset by remember {
        derivedStateOf {
            if (homeListState.firstVisibleItemIndex == 0) {
                homeListState.firstVisibleItemScrollOffset.toFloat()
            } else {
                500f
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Crossfade(
            targetState = activeSection,
            animationSpec = tween(durationMillis = 240),
            label = "catalogSectionCrossfade",
        ) { section ->
            when {
                section == TvTopSection.SEARCH -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Spacer(modifier = Modifier.height(78.dp))
                        AppleTvSearchScreen(
                            query = searchQuery,
                            selectedGenre = selectedGenre,
                            quickGenres = quickGenres,
                            isSearching = isSearching,
                            results = searchResults,
                            onQueryChange = onSearchQueryChange,
                            onSelectGenre = onSelectGenre,
                            onOpenDetail = onOpenDetail,
                        )
                    }
                }

                section == TvTopSection.ADDONS -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Spacer(modifier = Modifier.height(78.dp))
                        AppleTvAddonsScreen(
                            installedAddons = customAddons,
                            onToggleAddonEnabled = onToggleAddonEnabled,
                            onDeleteAddon = onDeleteAddon,
                            onAddonInstalledSuccess = onAddonInstalledSuccess,
                            onProtectedAction = onProtectedAddonAction,
                        )
                    }
                }

                section == TvTopSection.SETTINGS -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Spacer(modifier = Modifier.height(78.dp))
                        AppleTvSettingsHubScreen(
                            activeProfile = activeProfile,
                            profiles = profiles,
                            nuvioAvatars = nuvioAvatars,
                            customAddons = customAddons,
                            parentalConfig = parentalConfig,
                            isSyncingTrakt = isSyncingTrakt,
                            onSelectProfile = onSelectProfileFromHub,
                            onCreateProfile = onCreateProfile,
                            onUpdateProfile = onUpdateProfile,
                            onDeleteProfile = onDeleteProfile,
                            onToggleAddonEnabled = onToggleAddonEnabled,
                            onDeleteAddon = onDeleteAddon,
                            onAddonInstalledSuccess = onAddonInstalledSuccess,
                            onSyncTraktNow = onSyncTraktNow,
                            onDisconnectTrakt = onDisconnectTrakt,
                            onTraktLinkedSuccess = onTraktLinkedSuccess,
                            onNotifyToast = onNotifyToast,
                        )
                    }
                }

                isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(
                                color = AppleTvColors.PureWhite,
                                strokeWidth = 2.6.dp,
                                modifier = Modifier.size(40.dp),
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                text = "Cargando catálogo…",
                                style = MaterialTheme.typography.titleMedium,
                                color = AppleTvColors.SecondaryText,
                            )
                        }
                    }
                }

                errorMessage != null && homeShelves.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Text(
                                text = errorMessage,
                                style = MaterialTheme.typography.titleMedium,
                                color = AppleTvColors.SecondaryText,
                            )
                            Button(
                                onClick = onRetryLoad,
                                shape = ButtonDefaults.shape(shape = RoundedCornerShape(50)),
                                colors = ButtonDefaults.colors(
                                    containerColor = AppleTvColors.PureWhite,
                                    contentColor = AppleTvColors.Obsidian,
                                    focusedContainerColor = AppleTvColors.PureWhite,
                                    focusedContentColor = AppleTvColors.Obsidian,
                                ),
                                contentPadding = PaddingValues(horizontal = 26.dp, vertical = 12.dp),
                            ) {
                                Text("Reintentar", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }

                section == TvTopSection.HOME -> {
                    LazyColumn(
                        state = homeListState,
                        contentPadding = PaddingValues(bottom = 52.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        if (heroItems.isNotEmpty()) {
                            item(key = "apple_tv_hero_banner") {
                                AppleTvHeroBanner(
                                    items = heroItems,
                                    verticalScrollOffsetPx = heroParallaxOffset,
                                    onPlayNow = onPlayFromHero,
                                    onOpenDetail = onOpenDetail,
                                )
                            }
                        } else {
                            item(key = "home_top_spacer") {
                                Spacer(modifier = Modifier.height(86.dp))
                            }
                        }

                        if (continueWatchingItems.isNotEmpty()) {
                            item(key = "profile_continue_watching") {
                                AppleTvContinueWatchingShelf(
                                    items = continueWatchingItems,
                                    onResumeItem = onResumeWatchHistory,
                                )
                            }
                        }

                        items(
                            items = homeShelves,
                            key = { it.id },
                        ) { shelf ->
                            AppleTvShelfRow(
                                shelf = shelf,
                                onItemSelected = onOpenDetail,
                            )
                        }
                    }
                }

                section == TvTopSection.MOVIES -> {
                    ShelvesSectionList(
                        shelves = movieShelves,
                        onOpenDetail = onOpenDetail,
                    )
                }

                section == TvTopSection.SERIES -> {
                    ShelvesSectionList(
                        shelves = seriesShelves,
                        onOpenDetail = onOpenDetail,
                    )
                }
            }
        }

        // Floating top gradient + Apple TV Frosted Pill Navigation Bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        0.0f to AppleTvColors.Obsidian.copy(alpha = 0.85f),
                        0.7f to AppleTvColors.Obsidian.copy(alpha = 0.32f),
                        1.0f to Color.Transparent,
                    )
                ),
        ) {
            AppleTvTopNav(
                activeSection = activeSection,
                activeProfile = activeProfile,
                onSelectSection = onSelectSection,
                onSwitchProfile = onSwitchProfile,
            )
        }
    }
}

@Composable
private fun ShelvesSectionList(
    shelves: List<TvShelfModel>,
    onOpenDetail: (TvMediaCardItem) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(top = 88.dp, bottom = 52.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(
            items = shelves,
            key = { it.id },
        ) { shelf ->
            AppleTvShelfRow(
                shelf = shelf,
                onItemSelected = onOpenDetail,
            )
        }
    }
}
