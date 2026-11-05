@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)

package com.streamflixreborn.streamflix.tv.screens

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChildCare
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.addons.LocalNetworkIpResolver
import com.streamflixreborn.streamflix.profiles.MAX_PROFILES
import com.streamflixreborn.streamflix.profiles.ParentalPinManager
import com.streamflixreborn.streamflix.profiles.ParentalProtectionConfig
import com.streamflixreborn.streamflix.profiles.ProfileSubtitleStyle
import com.streamflixreborn.streamflix.profiles.TvCustomAddonEntity
import com.streamflixreborn.streamflix.profiles.TvProfileRepository
import com.streamflixreborn.streamflix.profiles.TvUserProfileEntity
import com.streamflixreborn.streamflix.stremio.NuvioAvatarAsset
import com.streamflixreborn.streamflix.trakt.TraktApiClient
import com.streamflixreborn.streamflix.tv.components.AppleTvKeyboardMode
import com.streamflixreborn.streamflix.tv.components.AppleTvPinDialog
import com.streamflixreborn.streamflix.tv.components.AppleTvVirtualKeyboard
import com.streamflixreborn.streamflix.tv.components.PinDialogMode
import com.streamflixreborn.streamflix.tv.theme.AppleTvColors
import com.streamflixreborn.streamflix.updater.ApkInstallLaunchResult
import com.streamflixreborn.streamflix.updater.TvAutoUpdater
import com.streamflixreborn.streamflix.updater.TvUpdateUiState
import com.streamflixreborn.streamflix.utils.TvImageOptimizer
import kotlinx.coroutines.launch

enum class SettingsHubCategory(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
) {
    PERFILES("Perfiles", "Avatares Nuvio y límite de 5", Icons.Rounded.Person),
    ADDONS("Add-ons", "Base, QR local y URL", Icons.Rounded.Extension),
    SINCRONIZACION("Sincronización", "Nube de Trakt.tv", Icons.Rounded.CloudSync),
    CONTROL_PARENTAL("Control Parental", "PIN de 4 dígitos y Niños", Icons.Rounded.Security),
    REPRODUCTOR_SUBTITULOS("Reproductor y Subtítulos", "Idioma, fuente y opacidad", Icons.Rounded.Subtitles),
    ACERCA_DE("Acerca de", "Versión y actualizador", Icons.Rounded.Info),
}

private sealed interface HubProfileModalState {
    data class Create(
        val suggestedName: String,
        val initialAvatar: NuvioAvatarAsset,
    ) : HubProfileModalState

    data class Edit(
        val profile: TvUserProfileEntity,
    ) : HubProfileModalState
}

/**
 * Unified Commercial Two-Column Settings Hub for Android TV:
 * - Left Column: D-Pad Navigation Rail across all 6 settings domains.
 * - Right Panel: Contextual controls for Profiles, Add-ons (QR + URL), Trakt.tv Sync,
 *   4-Digit Parental PIN, Player & Subtitles Styling, and In-App GitHub Auto-Updater.
 */
@Composable
fun AppleTvSettingsHubScreen(
    activeProfile: TvUserProfileEntity,
    profiles: List<TvUserProfileEntity>,
    nuvioAvatars: List<NuvioAvatarAsset>,
    customAddons: List<TvCustomAddonEntity>,
    parentalConfig: ParentalProtectionConfig,
    isSyncingTrakt: Boolean,
    initialCategory: SettingsHubCategory = SettingsHubCategory.PERFILES,
    onSelectProfile: (TvUserProfileEntity) -> Unit,
    onCreateProfile: (
        name: String,
        avatar: NuvioAvatarAsset,
        preferredAudioLang: String,
        isKidsProfile: Boolean,
        rawPin: String?,
    ) -> Unit,
    onUpdateProfile: (TvUserProfileEntity) -> Unit,
    onDeleteProfile: (Long) -> Unit,
    onToggleAddonEnabled: (String, Boolean) -> Unit,
    onDeleteAddon: (String) -> Unit,
    onAddonInstalledSuccess: (TvCustomAddonEntity) -> Unit,
    onSyncTraktNow: () -> Unit,
    onDisconnectTrakt: () -> Unit,
    onTraktLinkedSuccess: (String?) -> Unit,
    onNotifyToast: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val updateUiState by TvAutoUpdater.state.collectAsState()

    var selectedCategory by remember(initialCategory) { mutableStateOf(initialCategory) }
    var showQrAddonModal by remember { mutableStateOf(false) }
    var showManualAddonModal by remember { mutableStateOf(false) }
    var showTraktDeviceAuthModal by remember { mutableStateOf(false) }
    var showTraktApiKeysModal by remember { mutableStateOf(false) }
    var profileModalState by remember { mutableStateOf<HubProfileModalState?>(null) }
    var activePinDialog by remember { mutableStateOf<PinDialogMode?>(null) }

    val leftNavRequester = remember { FocusRequester() }
    val rightContentPrimaryRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        runCatching { leftNavRequester.requestFocus() }
    }

    fun guardSensitiveAction(
        promptTitle: String = "Control Parental Activo",
        promptSubtitle: String = "Ingresa el PIN de 4 dígitos para continuar.",
        action: () -> Unit,
    ) {
        val anyPinConfigured = parentalConfig.hasMasterPin || profiles.any { it.hasPinLock }
        val requiresPin = parentalConfig.isSettingsProtectionActive ||
            (activeProfile.isKidsProfile && anyPinConfigured)
        if (!requiresPin) {
            action()
        } else {
            activePinDialog = PinDialogMode.Verify(
                title = promptTitle,
                subtitle = promptSubtitle,
                verify = { raw ->
                    ParentalPinManager.verifyMasterOrFallbackPin(raw, activeProfile) ||
                        profiles.any { p -> p.hasPinLock && ParentalPinManager.verifyProfilePin(raw, p) }
                },
                onVerified = {
                    activePinDialog = null
                    action()
                },
            )
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 48.dp, end = 48.dp, top = 12.dp, bottom = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(32.dp),
        ) {
            // LEFT NAVIGATION COLUMN (Categories Rail)
            Column(
                modifier = Modifier
                    .width(308.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xD9121520))
                    .border(1.dp, Color(0x24FFFFFF), RoundedCornerShape(24.dp))
                    .padding(horizontal = 16.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "AJUSTES",
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontSize = 11.5.sp,
                        letterSpacing = 1.4.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = AppleTvColors.MutedText,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )

                SettingsHubCategory.entries.forEachIndexed { idx, category ->
                    val isSelected = selectedCategory == category
                    val hasUpdateBadge = category == SettingsHubCategory.ACERCA_DE &&
                        (updateUiState is TvUpdateUiState.UpdateAvailable ||
                            updateUiState is TvUpdateUiState.ReadyToInstall)

                    Button(
                        onClick = { selectedCategory = category },
                        modifier = Modifier
                            .fillMaxWidth()
                            .let { m -> if (idx == 0) m.focusRequester(leftNavRequester) else m }
                            .focusProperties {
                                right = rightContentPrimaryRequester
                            },
                        shape = ButtonDefaults.shape(shape = RoundedCornerShape(16.dp)),
                        scale = ButtonDefaults.scale(focusedScale = 1.04f),
                        glow = ButtonDefaults.glow(
                            focusedGlow = Glow(
                                elevationColor = AppleTvColors.FocusGlow,
                                elevation = 14.dp,
                            )
                        ),
                        border = ButtonDefaults.border(
                            border = Border(
                                border = BorderStroke(
                                    width = 1.dp,
                                    color = if (isSelected) Color(0x55FFFFFF) else Color.Transparent,
                                ),
                                shape = RoundedCornerShape(16.dp),
                            ),
                            focusedBorder = Border(
                                border = BorderStroke(2.dp, AppleTvColors.PureWhite),
                                shape = RoundedCornerShape(16.dp),
                            ),
                        ),
                        colors = ButtonDefaults.colors(
                            containerColor = if (isSelected) Color(0x2EFFFFFF) else Color.Transparent,
                            contentColor = if (isSelected) AppleTvColors.PureWhite else AppleTvColors.SecondaryText,
                            focusedContainerColor = AppleTvColors.PureWhite,
                            focusedContentColor = AppleTvColors.Obsidian,
                        ),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                imageVector = category.icon,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = category.title,
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = category.subtitle,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp),
                                    color = Color.Unspecified.copy(alpha = 0.72f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (hasUpdateBadge) {
                                Box(
                                    modifier = Modifier
                                        .size(9.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF81C784))
                                )
                            }
                        }
                    }
                }
            }

            // RIGHT CONTENT PANEL
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xB811141F))
                    .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(24.dp))
                    .padding(horizontal = 30.dp, vertical = 24.dp),
            ) {
                Crossfade(
                    targetState = selectedCategory,
                    animationSpec = tween(190),
                    label = "settingsHubPanelCrossfade",
                ) { cat ->
                    when (cat) {
                        SettingsHubCategory.PERFILES -> {
                            ProfilesHubPanel(
                                activeProfile = activeProfile,
                                profiles = profiles,
                                primaryRequester = rightContentPrimaryRequester,
                                leftNavRequester = leftNavRequester,
                                onActivateProfile = { target ->
                                    if (target.id == activeProfile.id) return@ProfilesHubPanel
                                    if (target.hasPinLock) {
                                        activePinDialog = PinDialogMode.Verify(
                                            title = "Perfil bloqueado (${target.name})",
                                            subtitle = "Ingresa el PIN de 4 dígitos para activar este perfil.",
                                            verify = { raw -> ParentalPinManager.verifyProfilePin(raw, target) },
                                            onVerified = {
                                                activePinDialog = null
                                                onSelectProfile(target)
                                                onNotifyToast("Perfil activo: ${target.name}")
                                            },
                                        )
                                    } else {
                                        guardSensitiveAction(
                                            promptTitle = "Cambiar de perfil",
                                            promptSubtitle = "Ingresa un PIN parental de 4 dígitos para salir de Modo Niños.",
                                        ) {
                                            onSelectProfile(target)
                                            onNotifyToast("Perfil activo: ${target.name}")
                                        }
                                    }
                                },
                                onEditProfile = { target ->
                                    if (target.hasPinLock) {
                                        activePinDialog = PinDialogMode.Verify(
                                            title = "Editar ${target.name}",
                                            subtitle = "Ingresa el PIN de 4 dígitos de este perfil.",
                                            verify = { raw -> ParentalPinManager.verifyProfilePin(raw, target) },
                                            onVerified = {
                                                activePinDialog = null
                                                profileModalState = HubProfileModalState.Edit(target)
                                            },
                                        )
                                    } else {
                                        guardSensitiveAction {
                                            profileModalState = HubProfileModalState.Edit(target)
                                        }
                                    }
                                },
                                onRequestAddProfile = {
                                    if (profiles.size < MAX_PROFILES) {
                                        guardSensitiveAction {
                                            val defaultAvatar = nuvioAvatars.getOrNull(
                                                profiles.size % nuvioAvatars.size.coerceAtLeast(1)
                                            ) ?: NuvioAvatarAsset(
                                                id = "default",
                                                label = "Perfil",
                                                imageUrl = activeProfile.avatarUrl,
                                                accentColorHex = "#1E88E5",
                                            )
                                            profileModalState = HubProfileModalState.Create(
                                                suggestedName = "Perfil ${profiles.size + 1}",
                                                initialAvatar = defaultAvatar,
                                            )
                                        }
                                    }
                                },
                            )
                        }

                        SettingsHubCategory.ADDONS -> {
                            AddonsHubPanel(
                                customAddons = customAddons,
                                primaryRequester = rightContentPrimaryRequester,
                                leftNavRequester = leftNavRequester,
                                onAddViaQr = {
                                    guardSensitiveAction(
                                        promptTitle = "Instalar Add-on vía QR",
                                        promptSubtitle = "Ingresa el PIN de 4 dígitos para iniciar el servidor local.",
                                    ) {
                                        showQrAddonModal = true
                                    }
                                },
                                onAddViaUrl = {
                                    guardSensitiveAction(
                                        promptTitle = "Instalar Add-on por URL",
                                        promptSubtitle = "Ingresa el PIN de 4 dígitos para añadir un manifiesto.",
                                    ) {
                                        showManualAddonModal = true
                                    }
                                },
                                onToggleAddon = { id, enabled ->
                                    guardSensitiveAction {
                                        onToggleAddonEnabled(id, enabled)
                                    }
                                },
                                onDeleteAddon = { id ->
                                    guardSensitiveAction {
                                        onDeleteAddon(id)
                                        onNotifyToast("Add-on eliminado")
                                    }
                                },
                            )
                        }

                        SettingsHubCategory.SINCRONIZACION -> {
                            TraktSyncHubPanel(
                                activeProfile = activeProfile,
                                isSyncingTrakt = isSyncingTrakt,
                                primaryRequester = rightContentPrimaryRequester,
                                leftNavRequester = leftNavRequester,
                                onLinkDeviceCode = {
                                    guardSensitiveAction {
                                        if (TraktApiClient.isConfigured(context)) {
                                            showTraktDeviceAuthModal = true
                                        } else {
                                            showTraktApiKeysModal = true
                                        }
                                    }
                                },
                                onOpenApiCredentials = {
                                    guardSensitiveAction {
                                        showTraktApiKeysModal = true
                                    }
                                },
                                onSyncNow = {
                                    if (!isSyncingTrakt) onSyncTraktNow()
                                },
                                onUnlink = {
                                    guardSensitiveAction {
                                        onDisconnectTrakt()
                                        onNotifyToast("Cuenta de Trakt.tv desvinculada")
                                    }
                                },
                            )
                        }

                        SettingsHubCategory.CONTROL_PARENTAL -> {
                            ParentalHubPanel(
                                activeProfile = activeProfile,
                                parentalConfig = parentalConfig,
                                primaryRequester = rightContentPrimaryRequester,
                                leftNavRequester = leftNavRequester,
                                onToggleKidsMode = {
                                    guardSensitiveAction {
                                        val nextKids = !activeProfile.isKidsProfile
                                        onUpdateProfile(activeProfile.copy(isKidsProfile = nextKids))
                                        onNotifyToast(
                                            if (nextKids) {
                                                "Modo Niños activado para ${activeProfile.name}"
                                            } else {
                                                "Modo Niños desactivado"
                                            }
                                        )
                                    }
                                },
                                onSetOrChangeProfilePin = {
                                    guardSensitiveAction {
                                        activePinDialog = PinDialogMode.Setup(
                                            title = "PIN de 4 dígitos para ${activeProfile.name}",
                                            subtitle = "Se solicitará al seleccionar este perfil en el inicio.",
                                            onPinConfirmed = { rawPin ->
                                                activePinDialog = null
                                                val hashed = ParentalPinManager.hashPin(rawPin)
                                                onUpdateProfile(activeProfile.copy(pinHash = hashed))
                                                onNotifyToast("PIN de perfil guardado")
                                            },
                                        )
                                    }
                                },
                                onClearProfilePin = {
                                    activePinDialog = PinDialogMode.Verify(
                                        title = "Quitar PIN de ${activeProfile.name}",
                                        subtitle = "Confirma el PIN actual de 4 dígitos.",
                                        verify = { raw -> ParentalPinManager.verifyProfilePin(raw, activeProfile) },
                                        onVerified = {
                                            activePinDialog = null
                                            onUpdateProfile(activeProfile.copy(pinHash = null))
                                            onNotifyToast("PIN eliminado del perfil")
                                        },
                                    )
                                },
                                onToggleGlobalSettingsLock = {
                                    if (!parentalConfig.hasMasterPin) {
                                        activePinDialog = PinDialogMode.Setup(
                                            title = "Crear PIN Maestro Parental",
                                            subtitle = "Protegerá la instalación de Add-ons y los ajustes avanzados.",
                                            onPinConfirmed = { rawPin ->
                                                activePinDialog = null
                                                ParentalPinManager.setMasterPin(
                                                    rawPin = rawPin,
                                                    protectAddonsAndSettings = true,
                                                    context = context,
                                                )
                                                onNotifyToast("Protección parental activada")
                                            },
                                        )
                                    } else {
                                        guardSensitiveAction {
                                            val next = !parentalConfig.protectAddonsAndSettings
                                            ParentalPinManager.setProtectAddonsAndSettings(next, context)
                                            onNotifyToast(
                                                if (next) "Bloqueo de ajustes activado" else "Bloqueo de ajustes pausado"
                                            )
                                        }
                                    }
                                },
                                onChangeMasterPin = {
                                    guardSensitiveAction {
                                        activePinDialog = PinDialogMode.Setup(
                                            title = "Nuevo PIN Maestro Parental",
                                            subtitle = "Ingresa un nuevo código numérico de 4 dígitos.",
                                            onPinConfirmed = { rawPin ->
                                                activePinDialog = null
                                                ParentalPinManager.setMasterPin(
                                                    rawPin = rawPin,
                                                    protectAddonsAndSettings = true,
                                                    context = context,
                                                )
                                                onNotifyToast("PIN Maestro actualizado")
                                            },
                                        )
                                    }
                                },
                                onRemoveMasterPin = {
                                    guardSensitiveAction {
                                        ParentalPinManager.clearMasterPin(context)
                                        onNotifyToast("PIN Maestro eliminado")
                                    }
                                },
                            )
                        }

                        SettingsHubCategory.REPRODUCTOR_SUBTITULOS -> {
                            PlayerAndSubtitlesHubPanel(
                                activeProfile = activeProfile,
                                primaryRequester = rightContentPrimaryRequester,
                                leftNavRequester = leftNavRequester,
                                onUpdateAudioLang = { lang ->
                                    coroutineScope.launch {
                                        TvProfileRepository.updateActiveProfilePlaybackPreferences(
                                            preferredAudioLang = lang,
                                            context = context,
                                        )
                                    }
                                },
                                onToggleSubtitlesDefault = { enabled ->
                                    coroutineScope.launch {
                                        TvProfileRepository.updateActiveProfilePlaybackPreferences(
                                            subtitlesEnabled = enabled,
                                            context = context,
                                        )
                                    }
                                },
                                onUpdateSubtitleStyle = { newStyle ->
                                    coroutineScope.launch {
                                        TvProfileRepository.updateActiveProfilePlaybackPreferences(
                                            subtitleStyle = newStyle,
                                            context = context,
                                        )
                                    }
                                },
                            )
                        }

                        SettingsHubCategory.ACERCA_DE -> {
                            AboutAndUpdaterHubPanel(
                                updateState = updateUiState,
                                primaryRequester = rightContentPrimaryRequester,
                                leftNavRequester = leftNavRequester,
                                onCheckUpdates = { TvAutoUpdater.checkForUpdatesNow() },
                                onStartDownload = { upd -> TvAutoUpdater.startDownload(upd, context) },
                                onCancelDownload = { TvAutoUpdater.cancelDownload() },
                                onInstallApk = { apkPath ->
                                    when (TvAutoUpdater.launchNativeApkInstaller(context, apkPath)) {
                                        ApkInstallLaunchResult.INSTALLER_OPENED -> {
                                            onNotifyToast("Abriendo instalador nativo de Android…")
                                        }
                                        ApkInstallLaunchResult.NEEDS_UNKNOWN_SOURCES_PERMISSION -> {
                                            onNotifyToast("Autoriza la instalación desde esta fuente y vuelve a pulsar Instalar")
                                        }
                                        ApkInstallLaunchResult.FAILED -> {
                                            onNotifyToast("No fue posible iniciar el instalador del paquete")
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        // MODALS OVERLAY LAYER
        AnimatedVisibility(visible = showQrAddonModal, enter = fadeIn(), exit = fadeOut()) {
            if (showQrAddonModal) {
                QrAddonPairingModal(
                    onDismiss = { showQrAddonModal = false },
                    onInstalled = { addon ->
                        showQrAddonModal = false
                        onAddonInstalledSuccess(addon)
                    },
                )
            }
        }

        AnimatedVisibility(visible = showManualAddonModal, enter = fadeIn(), exit = fadeOut()) {
            if (showManualAddonModal) {
                ManualAddonUrlModal(
                    onDismiss = { showManualAddonModal = false },
                    onInstalled = { addon ->
                        showManualAddonModal = false
                        onAddonInstalledSuccess(addon)
                    },
                )
            }
        }

        AnimatedVisibility(visible = showTraktDeviceAuthModal, enter = fadeIn(), exit = fadeOut()) {
            if (showTraktDeviceAuthModal) {
                TraktDeviceAuthModal(
                    profileId = activeProfile.id,
                    onDismiss = { showTraktDeviceAuthModal = false },
                    onAuthorized = { username ->
                        showTraktDeviceAuthModal = false
                        onTraktLinkedSuccess(username)
                    },
                    onOpenCredentialsSetup = {
                        showTraktDeviceAuthModal = false
                        showTraktApiKeysModal = true
                    },
                )
            }
        }

        AnimatedVisibility(visible = showTraktApiKeysModal, enter = fadeIn(), exit = fadeOut()) {
            if (showTraktApiKeysModal) {
                TraktCredentialsSetupModal(
                    onDismiss = { showTraktApiKeysModal = false },
                    onSavedAndProceed = {
                        showTraktApiKeysModal = false
                        showTraktDeviceAuthModal = true
                    },
                )
            }
        }

        AnimatedVisibility(visible = profileModalState != null, enter = fadeIn(), exit = fadeOut()) {
            profileModalState?.let { modal ->
                HubProfileEditorModal(
                    modalState = modal,
                    nuvioAvatars = nuvioAvatars,
                    canDelete = profiles.size > 1 && modal is HubProfileModalState.Edit,
                    onSaveCreate = { name, avatar, lang, kids, pin ->
                        onCreateProfile(name, avatar, lang, kids, pin)
                        profileModalState = null
                        onNotifyToast("Perfil creado")
                    },
                    onSaveEdit = { updated ->
                        onUpdateProfile(updated)
                        profileModalState = null
                        onNotifyToast("Perfil actualizado")
                    },
                    onDelete = { id ->
                        onDeleteProfile(id)
                        profileModalState = null
                        onNotifyToast("Perfil eliminado")
                    },
                    onRequestPin = { onPinReady ->
                        activePinDialog = PinDialogMode.Setup(
                            title = "PIN de 4 dígitos del perfil",
                            subtitle = "Configura un código numérico para este perfil.",
                            onPinConfirmed = { pin ->
                                activePinDialog = null
                                onPinReady(pin)
                            },
                        )
                    },
                    onDismiss = { profileModalState = null },
                )
            }
        }

        AnimatedVisibility(visible = activePinDialog != null, enter = fadeIn(), exit = fadeOut()) {
            activePinDialog?.let { pinMode ->
                AppleTvPinDialog(
                    mode = pinMode,
                    onDismiss = { activePinDialog = null },
                )
            }
        }
    }
}

// ============================================================================
// 1. PERFILES PANEL
// ============================================================================
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ProfilesHubPanel(
    activeProfile: TvUserProfileEntity,
    profiles: List<TvUserProfileEntity>,
    primaryRequester: FocusRequester,
    leftNavRequester: FocusRequester,
    onActivateProfile: (TvUserProfileEntity) -> Unit,
    onEditProfile: (TvUserProfileEntity) -> Unit,
    onRequestAddProfile: () -> Unit,
) {
    val context = LocalContext.current
    val canAdd = profiles.size < MAX_PROFILES

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Perfiles de Usuario (${profiles.size}/$MAX_PROFILES)",
                        style = MaterialTheme.typography.headlineSmall,
                        color = AppleTvColors.PureWhite,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (canAdd) {
                            "Cada perfil guarda su propio historial («Continuar viendo»), avatares de Nuvio y preferencias."
                        } else {
                            "Has alcanzado el límite máximo permitido de $MAX_PROFILES perfiles locales."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppleTvColors.SecondaryText,
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Button(
                    onClick = onRequestAddProfile,
                    enabled = canAdd,
                    modifier = Modifier
                        .focusRequester(primaryRequester)
                        .focusProperties { left = leftNavRequester }
                        .alpha(if (canAdd) 1f else 0.45f),
                    shape = ButtonDefaults.shape(shape = RoundedCornerShape(50)),
                    colors = ButtonDefaults.colors(
                        containerColor = AppleTvColors.PureWhite,
                        contentColor = AppleTvColors.Obsidian,
                        focusedContainerColor = AppleTvColors.PureWhite,
                        focusedContentColor = AppleTvColors.Obsidian,
                        disabledContainerColor = Color(0x33FFFFFF),
                        disabledContentColor = AppleTvColors.MutedText,
                    ),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 11.dp),
                ) {
                    Icon(
                        imageVector = if (canAdd) Icons.Rounded.Add else Icons.Rounded.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (canAdd) "Añadir perfil" else "Máx. $MAX_PROFILES perfiles",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }

        items(
            items = profiles.take(MAX_PROFILES),
            key = { it.id },
        ) { profile ->
            val isCurrent = profile.id == activeProfile.id
            val avatarReq = remember(profile.avatarUrl) {
                TvImageOptimizer.avatarRequest(context, profile.avatarUrl, sizePx = 140)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (isCurrent) Color(0x28FFFFFF) else AppleTvColors.GlassSurface)
                    .border(
                        width = if (isCurrent) 1.5.dp else 1.dp,
                        color = if (isCurrent) AppleTvColors.PureWhite else Color(0x22FFFFFF),
                        shape = RoundedCornerShape(18.dp),
                    )
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                ) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .border(2.dp, parseHexColor(profile.avatarColorHex), RoundedCornerShape(16.dp))
                            .background(AppleTvColors.DeepCharcoal),
                    ) {
                        if (profile.avatarUrl.isNotBlank()) {
                            AsyncImage(
                                model = avatarReq,
                                contentDescription = profile.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = profile.name,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = AppleTvColors.PureWhite,
                            )
                            if (isCurrent) {
                                HubBadgePill("ACTIVO", Color(0xFF81C784))
                            }
                            if (profile.isKidsProfile) {
                                HubBadgePill("NIÑOS", Color(0xFF81D4FA))
                            }
                            if (profile.hasPinLock) {
                                HubBadgePill("PIN", AppleTvColors.PureWhite)
                            }
                        }
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = buildString {
                                append(if (profile.preferredAudioLang == "es-ES") "Audio Castellano" else "Audio Latino")
                                if (profile.isTraktConnected) {
                                    append(" · Trakt.tv sincronizado")
                                }
                            },
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                            color = AppleTvColors.SecondaryText,
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!isCurrent) {
                        HubPillButton(
                            label = "Usar perfil",
                            primary = false,
                            modifier = Modifier.focusProperties { left = leftNavRequester },
                            onClick = { onActivateProfile(profile) },
                        )
                    }
                    HubPillButton(
                        label = "Editar",
                        icon = Icons.Rounded.Edit,
                        primary = isCurrent,
                        modifier = if (isCurrent) Modifier.focusProperties { left = leftNavRequester } else Modifier,
                        onClick = { onEditProfile(profile) },
                    )
                }
            }
        }
    }
}

// ============================================================================
// 2. ADD-ONS PANEL (Base Extensions + Custom QR / Manual URL)
// ============================================================================
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun AddonsHubPanel(
    customAddons: List<TvCustomAddonEntity>,
    primaryRequester: FocusRequester,
    leftNavRequester: FocusRequester,
    onAddViaQr: () -> Unit,
    onAddViaUrl: () -> Unit,
    onToggleAddon: (String, Boolean) -> Unit,
    onDeleteAddon: (String) -> Unit,
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(bottom = 28.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Gestor de Add-ons",
                        style = MaterialTheme.typography.headlineSmall,
                        color = AppleTvColors.PureWhite,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Administra las extensiones integradas del sistema y vincula nuevos manifiestos vía código QR local o URL.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppleTvColors.SecondaryText,
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HubPillButton(
                        label = "Añadir vía QR",
                        icon = Icons.Rounded.QrCode2,
                        primary = true,
                        modifier = Modifier
                            .focusRequester(primaryRequester)
                            .focusProperties { left = leftNavRequester },
                        onClick = onAddViaQr,
                    )
                    HubPillButton(
                        label = "Añadir por URL",
                        icon = Icons.Rounded.Keyboard,
                        primary = false,
                        onClick = onAddViaUrl,
                    )
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "EXTENSIONES BASE INTEGRADAS",
                style = MaterialTheme.typography.labelLarge.copy(
                    fontSize = 11.sp,
                    letterSpacing = 1.1.sp,
                ),
                color = AppleTvColors.MutedText,
            )
        }

        // Strict Zero-Provider compliant base system modules summary
        val baseModules = listOf(
            Triple(
                "Catálogo Central de Metadatos",
                "Índice universal de pósters, elencos, temporadas e identificadores IMDb.",
                "Base · Activo",
            ),
            Triple(
                "Catálogo Localizado en Español y Avatares",
                "Tendencias en español (es-MX / España) y galería gráfica para perfiles.",
                "Base · Activo",
            ),
            Triple(
                "Motor Automático de Subtítulos",
                "Búsqueda y descarga directa de subtítulos sincronizados en español y latino.",
                "Base · Activo",
            ),
        )

        items(baseModules, key = { it.first }) { (title, desc, badge) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0x18FFFFFF))
                    .border(1.dp, Color(0x1FFFFFFF), RoundedCornerShape(16.dp))
                    .padding(horizontal = 18.dp, vertical = 13.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp),
                        color = AppleTvColors.PureWhite,
                    )
                    Text(
                        text = desc,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                        color = AppleTvColors.SecondaryText,
                    )
                }
                HubBadgePill(badge, Color(0xFF81C784))
            }
        }

        item {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "ADD-ONS PERSONALIZADOS (${customAddons.size})",
                style = MaterialTheme.typography.labelLarge.copy(
                    fontSize = 11.sp,
                    letterSpacing = 1.1.sp,
                ),
                color = AppleTvColors.MutedText,
            )
        }

        if (customAddons.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(AppleTvColors.GlassSurface)
                        .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(16.dp))
                        .padding(22.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No hay add-ons personalizados instalados. Usa «Añadir vía QR» desde tu móvil o «Añadir por URL».",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppleTvColors.SecondaryText,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            items(customAddons, key = { it.addonId }) { addon ->
                InstalledAddonRowCard(
                    addon = addon,
                    onToggle = { onToggleAddon(addon.addonId, !addon.isEnabled) },
                    onDelete = { onDeleteAddon(addon.addonId) },
                )
            }
        }
    }
}

// ============================================================================
// 3. SINCRONIZACIÓN PANEL (Trakt.tv)
// ============================================================================
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TraktSyncHubPanel(
    activeProfile: TvUserProfileEntity,
    isSyncingTrakt: Boolean,
    primaryRequester: FocusRequester,
    leftNavRequester: FocusRequester,
    onLinkDeviceCode: () -> Unit,
    onOpenApiCredentials: () -> Unit,
    onSyncNow: () -> Unit,
    onUnlink: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        Text(
            text = "Sincronización en la Nube con Trakt.tv",
            style = MaterialTheme.typography.headlineSmall,
            color = AppleTvColors.PureWhite,
        )
        Text(
            text = "Vincula la cuenta de Trakt.tv del perfil «${activeProfile.name}» para activar Scrobbling automático en tiempo real y restaurar tus listas de «Continuar viendo» y favoritos en cualquier televisor.",
            style = MaterialTheme.typography.bodyMedium,
            color = AppleTvColors.SecondaryText,
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(AppleTvColors.GlassSurface)
                .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(20.dp))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (activeProfile.isTraktConnected) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CloudDone,
                        contentDescription = null,
                        tint = Color(0xFF81C784),
                        modifier = Modifier.size(24.dp),
                    )
                    Text(
                        text = "Conectado a Trakt.tv" +
                            (activeProfile.traktUsername?.let { " como @$it" } ?: ""),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = Color(0xFF81C784),
                    )
                }

                Text(
                    text = "• Scrobbling en vivo activado durante la reproducción.\n• Restauración automática de progreso y favoritos al iniciar sesión.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppleTvColors.SecondaryText,
                )

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    HubPillButton(
                        label = if (isSyncingTrakt) "Sincronizando…" else "Sincronizar ahora",
                        icon = Icons.Rounded.CloudSync,
                        primary = true,
                        modifier = Modifier
                            .focusRequester(primaryRequester)
                            .focusProperties { left = leftNavRequester },
                        onClick = onSyncNow,
                    )
                    HubPillButton(
                        label = "Desvincular cuenta",
                        icon = Icons.Rounded.LinkOff,
                        primary = false,
                        onClick = onUnlink,
                    )
                }
            } else {
                Text(
                    text = "Estado: Sin cuenta vinculada en este perfil",
                    style = MaterialTheme.typography.titleMedium,
                    color = AppleTvColors.PureWhite,
                )
                Text(
                    text = "Genera un código alfanumérico rápido en pantalla para autorizar tu cuenta desde el navegador de tu móvil o computadora.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppleTvColors.SecondaryText,
                )

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    HubPillButton(
                        label = "Vincular con código de dispositivo",
                        icon = Icons.Rounded.CloudSync,
                        primary = true,
                        modifier = Modifier
                            .focusRequester(primaryRequester)
                            .focusProperties { left = leftNavRequester },
                        onClick = onLinkDeviceCode,
                    )
                    HubPillButton(
                        label = "Credenciales API",
                        icon = Icons.Rounded.Key,
                        primary = false,
                        onClick = onOpenApiCredentials,
                    )
                }
            }
        }
    }
}

// ============================================================================
// 4. CONTROL PARENTAL PANEL (4-Digit PIN Lock & Kids Mode)
// ============================================================================
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ParentalHubPanel(
    activeProfile: TvUserProfileEntity,
    parentalConfig: ParentalProtectionConfig,
    primaryRequester: FocusRequester,
    leftNavRequester: FocusRequester,
    onToggleKidsMode: () -> Unit,
    onSetOrChangeProfilePin: () -> Unit,
    onClearProfilePin: () -> Unit,
    onToggleGlobalSettingsLock: () -> Unit,
    onChangeMasterPin: () -> Unit,
    onRemoveMasterPin: () -> Unit,
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(18.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Text(
                text = "Control Parental y Modo Niños",
                style = MaterialTheme.typography.headlineSmall,
                color = AppleTvColors.PureWhite,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Protege a los menores restringiendo catálogos maduros y bloqueando perfiles o ajustes críticos mediante un PIN numérico de 4 dígitos.",
                style = MaterialTheme.typography.bodyMedium,
                color = AppleTvColors.SecondaryText,
            )
        }

        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(AppleTvColors.GlassSurface)
                    .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(20.dp))
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = "Restricciones del Perfil «${activeProfile.name}»",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = AppleTvColors.PureWhite,
                )
                Text(
                    text = "El Modo Niños oculta géneros de terror, suspenso, crimen y +18 en Inicio, Buscador y Voz.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppleTvColors.SecondaryText,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HubPillButton(
                        label = if (activeProfile.isKidsProfile) "Modo Niños: Activado" else "Modo Niños: Desactivado",
                        icon = Icons.Rounded.ChildCare,
                        primary = activeProfile.isKidsProfile,
                        modifier = Modifier
                            .focusRequester(primaryRequester)
                            .focusProperties { left = leftNavRequester },
                        onClick = onToggleKidsMode,
                    )
                    HubPillButton(
                        label = if (activeProfile.hasPinLock) "Cambiar PIN del perfil" else "Configurar PIN de perfil",
                        icon = Icons.Rounded.Lock,
                        primary = false,
                        onClick = onSetOrChangeProfilePin,
                    )
                    if (activeProfile.hasPinLock) {
                        HubPillButton(
                            label = "Quitar PIN",
                            icon = Icons.Rounded.LockOpen,
                            primary = false,
                            onClick = onClearProfilePin,
                        )
                    }
                }
            }
        }

        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(AppleTvColors.GlassSurface)
                    .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(20.dp))
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = "Protección de Instalación de Add-ons y Ajustes",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = AppleTvColors.PureWhite,
                )
                Text(
                    text = "Exige el PIN Maestro de 4 dígitos antes de levantar el servidor QR local, añadir URLs de manifiestos o modificar controles parentales.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppleTvColors.SecondaryText,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HubPillButton(
                        label = if (parentalConfig.isSettingsProtectionActive) {
                            "Protección con PIN: Activa"
                        } else {
                            "Bloquear Add-ons y Ajustes"
                        },
                        icon = Icons.Rounded.Security,
                        primary = parentalConfig.isSettingsProtectionActive,
                        modifier = Modifier.focusProperties { left = leftNavRequester },
                        onClick = onToggleGlobalSettingsLock,
                    )
                    if (parentalConfig.hasMasterPin) {
                        HubPillButton(
                            label = "Cambiar PIN Maestro",
                            icon = Icons.Rounded.Key,
                            primary = false,
                            onClick = onChangeMasterPin,
                        )
                        HubPillButton(
                            label = "Eliminar PIN Maestro",
                            icon = Icons.Rounded.LockOpen,
                            primary = false,
                            onClick = onRemoveMasterPin,
                        )
                    }
                }
            }
        }
    }
}

// ============================================================================
// 5. REPRODUCTOR Y SUBTÍTULOS PANEL
// ============================================================================
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PlayerAndSubtitlesHubPanel(
    activeProfile: TvUserProfileEntity,
    primaryRequester: FocusRequester,
    leftNavRequester: FocusRequester,
    onUpdateAudioLang: (String) -> Unit,
    onToggleSubtitlesDefault: (Boolean) -> Unit,
    onUpdateSubtitleStyle: (ProfileSubtitleStyle) -> Unit,
) {
    val currentStyle = remember(
        activeProfile.subtitleSizeScale,
        activeProfile.subtitleTextColorHex,
        activeProfile.subtitleBgOpacity,
        activeProfile.subtitleSyncOffsetMs,
    ) {
        ProfileSubtitleStyle(
            sizeScale = activeProfile.subtitleSizeScale,
            textColorHex = activeProfile.subtitleTextColorHex,
            bgOpacity = activeProfile.subtitleBgOpacity,
            syncOffsetMs = activeProfile.subtitleSyncOffsetMs,
        )
    }

    val previewFg = remember(currentStyle.textColorHex) {
        runCatching { Color(android.graphics.Color.parseColor(currentStyle.textColorHex)) }
            .getOrDefault(Color.White)
    }
    val previewBg = remember(currentStyle.bgOpacity) {
        Color.Black.copy(alpha = currentStyle.bgOpacity.coerceIn(0f, 1f))
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Text(
                text = "Reproductor y Motor de Subtítulos",
                style = MaterialTheme.typography.headlineSmall,
                color = AppleTvColors.PureWhite,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Personaliza el idioma prioritario de audio en español y la apariencia de los subtítulos automáticos.",
                style = MaterialTheme.typography.bodyMedium,
                color = AppleTvColors.SecondaryText,
            )
        }

        // Live Subtitle Preview Box
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(92.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFF0A0D16))
                    .border(1.dp, Color(0x28FFFFFF), RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(previewBg)
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = "«Preparando la mejor experiencia en pantalla grande.»",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = (18f * currentStyle.sizeScale).sp,
                            fontWeight = FontWeight.SemiBold,
                            shadow = Shadow(Color.Black, Offset(1.5f, 1.5f), 4f),
                        ),
                        color = previewFg,
                    )
                }
            }
        }

        // Preferred Spanish Audio & Subtitles Toggle
        item {
            Text(
                text = "IDIOMA DE AUDIO PRIORITARIO Y SUBTÍTULOS",
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 11.sp, letterSpacing = 1.sp),
                color = AppleTvColors.MutedText,
            )
            Spacer(modifier = Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    HubPillButton(
                        label = "Español Latino (es-LA)",
                        primary = activeProfile.preferredAudioLang != "es-ES",
                        modifier = Modifier
                            .focusRequester(primaryRequester)
                            .focusProperties { left = leftNavRequester },
                        onClick = { onUpdateAudioLang("es-LA") },
                    )
                }
                item {
                    HubPillButton(
                        label = "Español Castellano (es-ES)",
                        primary = activeProfile.preferredAudioLang == "es-ES",
                        onClick = { onUpdateAudioLang("es-ES") },
                    )
                }
                item {
                    HubPillButton(
                        label = if (activeProfile.subtitlesEnabled) {
                            "Subtítulos: Activados"
                        } else {
                            "Subtítulos: Desactivados"
                        },
                        primary = activeProfile.subtitlesEnabled,
                        onClick = { onToggleSubtitlesDefault(!activeProfile.subtitlesEnabled) },
                    )
                }
            }
        }

        // Font Size
        item {
            Text(
                text = "TAMAÑO DE FUENTE",
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 11.sp, letterSpacing = 1.sp),
                color = AppleTvColors.MutedText,
            )
            Spacer(modifier = Modifier.height(8.dp))
            val sizes = listOf(0.82f to "Pequeño", 1.0f to "Normal", 1.25f to "Grande", 1.50f to "Extra Grande")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(sizes, key = { it.second }) { (scaleVal, label) ->
                    val active = kotlin.math.abs(currentStyle.sizeScale - scaleVal) < 0.05f
                    HubPillButton(
                        label = label,
                        primary = active,
                        modifier = if (scaleVal == 0.82f) Modifier.focusProperties { left = leftNavRequester } else Modifier,
                        onClick = { onUpdateSubtitleStyle(currentStyle.copy(sizeScale = scaleVal)) },
                    )
                }
            }
        }

        // Font Color & Background Opacity
        item {
            Text(
                text = "COLOR DE TEXTO Y OPACIDAD DE CAJA",
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 11.sp, letterSpacing = 1.sp),
                color = AppleTvColors.MutedText,
            )
            Spacer(modifier = Modifier.height(8.dp))
            val colors = listOf(
                "#FFFFFF" to "Blanco",
                "#FFD600" to "Amarillo",
                "#40C4FF" to "Cian",
                "#69F0AE" to "Verde",
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(colors, key = { it.first }) { (hex, label) ->
                    HubPillButton(
                        label = label,
                        primary = currentStyle.textColorHex.equals(hex, ignoreCase = true),
                        modifier = if (hex == "#FFFFFF") Modifier.focusProperties { left = leftNavRequester } else Modifier,
                        onClick = { onUpdateSubtitleStyle(currentStyle.copy(textColorHex = hex)) },
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            val opacities = listOf(
                0.0f to "Fondo 0%",
                0.40f to "Fondo 40%",
                0.75f to "Fondo 75%",
                1.0f to "Fondo 100%",
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(opacities, key = { it.second }) { (alphaVal, label) ->
                    HubPillButton(
                        label = label,
                        primary = kotlin.math.abs(currentStyle.bgOpacity - alphaVal) < 0.08f,
                        modifier = if (alphaVal == 0.0f) Modifier.focusProperties { left = leftNavRequester } else Modifier,
                        onClick = { onUpdateSubtitleStyle(currentStyle.copy(bgOpacity = alphaVal)) },
                    )
                }
                if (currentStyle.syncOffsetMs != 0L) {
                    item {
                        HubPillButton(
                            label = "Restablecer desfase (${currentStyle.syncOffsetMs} ms → 0 ms)",
                            primary = false,
                            onClick = { onUpdateSubtitleStyle(currentStyle.copy(syncOffsetMs = 0L)) },
                        )
                    }
                }
            }
        }
    }
}

// ============================================================================
// 6. ACERCA DE & IN-APP GITHUB RELEASES AUTO-UPDATER PANEL
// ============================================================================
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun AboutAndUpdaterHubPanel(
    updateState: TvUpdateUiState,
    primaryRequester: FocusRequester,
    leftNavRequester: FocusRequester,
    onCheckUpdates: () -> Unit,
    onStartDownload: (com.streamflixreborn.streamflix.updater.TvAvailableUpdate) -> Unit,
    onCancelDownload: () -> Unit,
    onInstallApk: (String) -> Unit,
) {
    val context = LocalContext.current
    val localIp = remember { LocalNetworkIpResolver.resolveLocalLanIpv4(context) ?: "Sin red local" }
    val primaryAbi = remember { Build.SUPPORTED_ABIS.firstOrNull() ?: "universal" }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(18.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Text(
                text = "Acerca de Streamflix TV",
                style = MaterialTheme.typography.headlineSmall,
                color = AppleTvColors.PureWhite,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Edición comercial nativa para Android TV optimizada para Latinoamérica y España.",
                style = MaterialTheme.typography.bodyMedium,
                color = AppleTvColors.SecondaryText,
            )
        }

        // System & Version Summary Card
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(AppleTvColors.GlassSurface)
                    .border(1.dp, Color(0x24FFFFFF), RoundedCornerShape(20.dp))
                    .padding(horizontal = 22.dp, vertical = 18.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AboutStatItem("Versión actual", "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                AboutStatItem("Red local (QR)", localIp)
                AboutStatItem("Arquitectura", primaryAbi)
                AboutStatItem("Base de datos", "Room Local Activa")
            }
        }

        // In-App Auto-Updater Card (GitHub Releases)
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(AppleTvColors.GlassSurface)
                    .border(1.dp, Color(0x28FFFFFF), RoundedCornerShape(20.dp))
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SystemUpdateAlt,
                        contentDescription = null,
                        tint = AppleTvColors.PureWhite,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        text = "Actualizador Automático In-App (GitHub Releases)",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = AppleTvColors.PureWhite,
                    )
                }

                when (updateState) {
                    is TvUpdateUiState.Idle,
                    is TvUpdateUiState.UpToDate -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = Color(0xFF81C784),
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = "Tienes instalada la versión más reciente (v${BuildConfig.VERSION_NAME}).",
                                style = MaterialTheme.typography.bodyMedium,
                                color = AppleTvColors.SecondaryText,
                            )
                        }

                        HubPillButton(
                            label = "Buscar actualizaciones",
                            icon = Icons.Rounded.Refresh,
                            primary = true,
                            modifier = Modifier
                                .focusRequester(primaryRequester)
                                .focusProperties { left = leftNavRequester },
                            onClick = onCheckUpdates,
                        )
                    }

                    is TvUpdateUiState.Checking -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator(
                                color = AppleTvColors.PureWhite,
                                strokeWidth = 2.4.dp,
                                modifier = Modifier.size(22.dp),
                            )
                            Text(
                                text = "Consultando última versión disponible en GitHub Releases…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = AppleTvColors.PureWhite,
                            )
                        }
                    }

                    is TvUpdateUiState.UpdateAvailable -> {
                        val upd = updateState.update
                        Text(
                            text = "Nueva versión disponible: ${upd.releaseTitle} (${TvAutoUpdater.formatMegabytes(upd.apkSizeBytes)})",
                            style = MaterialTheme.typography.titleMedium.copy(color = Color(0xFF81C784)),
                        )
                        Text(
                            text = upd.releaseNotes,
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppleTvColors.SecondaryText,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            HubPillButton(
                                label = "Descargar e instalar actualización",
                                icon = Icons.Rounded.Download,
                                primary = true,
                                modifier = Modifier
                                    .focusRequester(primaryRequester)
                                    .focusProperties { left = leftNavRequester },
                                onClick = { onStartDownload(upd) },
                            )
                            HubPillButton(
                                label = "Verificar de nuevo",
                                icon = Icons.Rounded.Refresh,
                                primary = false,
                                onClick = onCheckUpdates,
                            )
                        }
                    }

                    is TvUpdateUiState.Downloading -> {
                        val pctInt = (updateState.progressFraction * 100f).toInt().coerceIn(0, 100)
                        Text(
                            text = "Descargando ${updateState.update.apkName}… $pctInt%  " +
                                "(${TvAutoUpdater.formatMegabytes(updateState.downloadedBytes)} / ${TvAutoUpdater.formatMegabytes(updateState.totalBytes)})",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            color = AppleTvColors.PureWhite,
                        )

                        // Non-intrusive sleek Apple TV progress bar
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(50))
                                .background(Color(0x30FFFFFF)),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(updateState.progressFraction.coerceIn(0.03f, 1f))
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(AppleTvColors.PureWhite),
                            )
                        }

                        HubPillButton(
                            label = "Cancelar descarga",
                            primary = false,
                            modifier = Modifier
                                .focusRequester(primaryRequester)
                                .focusProperties { left = leftNavRequester },
                            onClick = onCancelDownload,
                        )
                    }

                    is TvUpdateUiState.ReadyToInstall -> {
                        Text(
                            text = "Actualización ${updateState.update.versionTag} descargada y lista para instalar.",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color(0xFF81C784),
                        )
                        Text(
                            text = "Tus perfiles locales, avatares, historial de reproducción y add-ons en Room se conservarán intactos.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppleTvColors.SecondaryText,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            HubPillButton(
                                label = "Instalar actualización ahora",
                                icon = Icons.Rounded.SystemUpdateAlt,
                                primary = true,
                                modifier = Modifier
                                    .focusRequester(primaryRequester)
                                    .focusProperties { left = leftNavRequester },
                                onClick = { onInstallApk(updateState.apkFilePath) },
                            )
                            HubPillButton(
                                label = "Volver a descargar",
                                icon = Icons.Rounded.Refresh,
                                primary = false,
                                onClick = { onStartDownload(updateState.update) },
                            )
                        }
                    }

                    is TvUpdateUiState.Error -> {
                        Text(
                            text = updateState.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFFFF8A80),
                        )
                        HubPillButton(
                            label = "Reintentar búsqueda de actualización",
                            icon = Icons.Rounded.Refresh,
                            primary = true,
                            modifier = Modifier
                                .focusRequester(primaryRequester)
                                .focusProperties { left = leftNavRequester },
                            onClick = onCheckUpdates,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AboutStatItem(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.5.sp),
            color = AppleTvColors.MutedText,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = 14.5.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            color = AppleTvColors.PureWhite,
        )
    }
}

@Composable
private fun HubBadgePill(text: String, tint: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(tint.copy(alpha = 0.18f))
            .border(1.dp, tint.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge.copy(
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            ),
            color = tint,
        )
    }
}

@Composable
private fun HubPillButton(
    label: String,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = ButtonDefaults.shape(shape = RoundedCornerShape(50)),
        scale = ButtonDefaults.scale(focusedScale = 1.06f),
        glow = ButtonDefaults.glow(
            focusedGlow = Glow(
                elevationColor = AppleTvColors.FocusGlow,
                elevation = 14.dp,
            )
        ),
        border = ButtonDefaults.border(
            border = Border(
                border = BorderStroke(1.dp, if (primary) AppleTvColors.PureWhite else Color(0x33FFFFFF)),
                shape = RoundedCornerShape(50),
            ),
            focusedBorder = Border(
                border = BorderStroke(2.dp, AppleTvColors.PureWhite),
                shape = RoundedCornerShape(50),
            ),
        ),
        colors = ButtonDefaults.colors(
            containerColor = if (primary) AppleTvColors.PureWhite else Color(0xFF191D2B),
            contentColor = if (primary) AppleTvColors.Obsidian else AppleTvColors.PureWhite,
            focusedContainerColor = AppleTvColors.PureWhite,
            focusedContentColor = AppleTvColors.Obsidian,
        ),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(17.dp))
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
        )
    }
}

// ============================================================================
// PROFILE EDITOR MODAL INSIDE SETTINGS HUB (D-Pad Keyboard + Nuvio Avatars)
// ============================================================================
@Composable
private fun HubProfileEditorModal(
    modalState: HubProfileModalState,
    nuvioAvatars: List<NuvioAvatarAsset>,
    canDelete: Boolean,
    onSaveCreate: (
        name: String,
        avatar: NuvioAvatarAsset,
        preferredAudioLang: String,
        isKidsProfile: Boolean,
        rawPin: String?,
    ) -> Unit,
    onSaveEdit: (TvUserProfileEntity) -> Unit,
    onDelete: (Long) -> Unit,
    onRequestPin: ((String) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val saveRequester = remember { FocusRequester() }

    var nameText by remember(modalState) {
        mutableStateOf(
            when (modalState) {
                is HubProfileModalState.Create -> modalState.suggestedName
                is HubProfileModalState.Edit -> modalState.profile.name
            }
        )
    }
    var selectedAvatarUrl by remember(modalState) {
        mutableStateOf(
            when (modalState) {
                is HubProfileModalState.Create -> modalState.initialAvatar.imageUrl
                is HubProfileModalState.Edit -> modalState.profile.avatarUrl
            }
        )
    }
    var selectedColorHex by remember(modalState) {
        mutableStateOf(
            when (modalState) {
                is HubProfileModalState.Create -> modalState.initialAvatar.accentColorHex
                is HubProfileModalState.Edit -> modalState.profile.avatarColorHex
            }
        )
    }
    var preferredAudioLang by remember(modalState) {
        mutableStateOf(
            when (modalState) {
                is HubProfileModalState.Create -> "es-LA"
                is HubProfileModalState.Edit -> modalState.profile.preferredAudioLang
            }
        )
    }
    var isKidsProfile by remember(modalState) {
        mutableStateOf(
            when (modalState) {
                is HubProfileModalState.Create -> false
                is HubProfileModalState.Edit -> modalState.profile.isKidsProfile
            }
        )
    }
    var pendingRawPin by remember(modalState) { mutableStateOf<String?>(null) }
    var existingPinHash by remember(modalState) {
        mutableStateOf(
            when (modalState) {
                is HubProfileModalState.Create -> null
                is HubProfileModalState.Edit -> modalState.profile.pinHash
            }
        )
    }
    var showKeyboardForName by remember { mutableStateOf(false) }

    BackHandler {
        if (showKeyboardForName) showKeyboardForName = false else onDismiss()
    }

    LaunchedEffect(showKeyboardForName) {
        if (!showKeyboardForName) {
            runCatching { saveRequester.requestFocus() }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xEB06070B))
            .padding(horizontal = 48.dp, vertical = 28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(26.dp))
                .background(Color(0xF5141722))
                .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(26.dp))
                .padding(28.dp),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.width(340.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = if (modalState is HubProfileModalState.Create) "Nuevo perfil" else "Editar perfil",
                    style = MaterialTheme.typography.headlineSmall,
                    color = AppleTvColors.PureWhite,
                )

                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .border(2.5.dp, parseHexColor(selectedColorHex), RoundedCornerShape(22.dp))
                        .background(AppleTvColors.DeepCharcoal),
                ) {
                    if (selectedAvatarUrl.isNotBlank()) {
                        val req = remember(selectedAvatarUrl) {
                            TvImageOptimizer.avatarRequest(context, selectedAvatarUrl, sizePx = 200)
                        }
                        AsyncImage(
                            model = req,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                HubPillButton(
                    label = "Nombre: ${nameText.ifBlank { "Perfil" }}",
                    icon = Icons.Rounded.Keyboard,
                    primary = showKeyboardForName,
                    onClick = { showKeyboardForName = !showKeyboardForName },
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HubPillButton(
                        label = "Latino",
                        primary = preferredAudioLang != "es-ES",
                        onClick = { preferredAudioLang = "es-LA" },
                    )
                    HubPillButton(
                        label = "Castellano",
                        primary = preferredAudioLang == "es-ES",
                        onClick = { preferredAudioLang = "es-ES" },
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HubPillButton(
                        label = if (isKidsProfile) "✓ Niños" else "Modo Niños",
                        primary = isKidsProfile,
                        onClick = { isKidsProfile = !isKidsProfile },
                    )
                    val hasPin = pendingRawPin != null || !existingPinHash.isNullOrBlank()
                    HubPillButton(
                        label = if (hasPin) "Quitar PIN" else "PIN 4 dígitos",
                        primary = hasPin,
                        onClick = {
                            if (hasPin) {
                                pendingRawPin = null
                                existingPinHash = null
                            } else {
                                onRequestPin { newPin -> pendingRawPin = newPin }
                            }
                        },
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HubPillButton(
                        label = "Guardar",
                        primary = true,
                        modifier = Modifier.focusRequester(saveRequester),
                        onClick = {
                            val clean = nameText.trim().take(18).ifBlank { "Perfil" }
                            when (modalState) {
                                is HubProfileModalState.Create -> {
                                    onSaveCreate(
                                        clean,
                                        NuvioAvatarAsset(
                                            id = "selected",
                                            label = clean,
                                            imageUrl = selectedAvatarUrl,
                                            accentColorHex = selectedColorHex,
                                        ),
                                        preferredAudioLang,
                                        isKidsProfile,
                                        pendingRawPin,
                                    )
                                }
                                is HubProfileModalState.Edit -> {
                                    val resolvedHash = pendingRawPin?.let { ParentalPinManager.hashPin(it) }
                                        ?: existingPinHash
                                    onSaveEdit(
                                        modalState.profile.copy(
                                            name = clean,
                                            avatarUrl = selectedAvatarUrl,
                                            avatarColorHex = selectedColorHex,
                                            preferredAudioLang = preferredAudioLang,
                                            isKidsProfile = isKidsProfile,
                                            pinHash = resolvedHash,
                                        )
                                    )
                                }
                            }
                        },
                    )
                    HubPillButton(
                        label = "Cancelar",
                        primary = false,
                        onClick = onDismiss,
                    )
                }

                if (canDelete && modalState is HubProfileModalState.Edit) {
                    HubPillButton(
                        label = "Eliminar perfil",
                        icon = Icons.Rounded.DeleteOutline,
                        primary = false,
                        onClick = { onDelete(modalState.profile.id) },
                    )
                }
            }

            // Right side of modal: either D-Pad Virtual Keyboard for Name OR Nuvio Avatar Grid
            Column(modifier = Modifier.weight(1f)) {
                if (showKeyboardForName) {
                    Text(
                        text = "Editar nombre del perfil",
                        style = MaterialTheme.typography.titleMedium,
                        color = AppleTvColors.PureWhite,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    AppleTvVirtualKeyboard(
                        value = nameText,
                        onValueChange = { if (it.length <= 18) nameText = it },
                        mode = AppleTvKeyboardMode.SEARCH,
                        rightExitFocusRequester = saveRequester,
                        canExitRight = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text(
                        text = "Selecciona un avatar de Nuvio Catalog",
                        style = MaterialTheme.typography.titleMedium,
                        color = AppleTvColors.PureWhite,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 84.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(6.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(280.dp),
                    ) {
                        gridItems(nuvioAvatars, key = { it.id }) { avatar ->
                            val isChosen = avatar.imageUrl == selectedAvatarUrl
                            val shape = RoundedCornerShape(18.dp)
                            val req = remember(avatar.imageUrl) {
                                TvImageOptimizer.avatarRequest(context, avatar.imageUrl, sizePx = 170)
                            }
                            Card(
                                onClick = {
                                    selectedAvatarUrl = avatar.imageUrl
                                    selectedColorHex = avatar.accentColorHex
                                },
                                modifier = Modifier.size(84.dp),
                                shape = CardDefaults.shape(shape = shape),
                                scale = CardDefaults.scale(focusedScale = 1.1f),
                                border = CardDefaults.border(
                                    border = Border(
                                        border = BorderStroke(
                                            if (isChosen) 2.5.dp else 1.dp,
                                            if (isChosen) AppleTvColors.PureWhite else Color(0x28FFFFFF),
                                        ),
                                        shape = shape,
                                    ),
                                    focusedBorder = Border(
                                        border = BorderStroke(2.5.dp, AppleTvColors.PureWhite),
                                        shape = shape,
                                    ),
                                ),
                            ) {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    AsyncImage(
                                        model = req,
                                        contentDescription = avatar.label,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                    if (isChosen) {
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.BottomEnd)
                                                .padding(4.dp)
                                                .size(18.dp)
                                                .clip(CircleShape)
                                                .background(AppleTvColors.PureWhite),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Check,
                                                contentDescription = null,
                                                tint = AppleTvColors.Obsidian,
                                                modifier = Modifier.size(12.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
