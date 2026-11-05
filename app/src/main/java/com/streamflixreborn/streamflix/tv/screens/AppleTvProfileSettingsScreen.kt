@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.streamflixreborn.streamflix.tv.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChildCare
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import com.streamflixreborn.streamflix.profiles.ParentalPinManager
import com.streamflixreborn.streamflix.profiles.ParentalProtectionConfig
import com.streamflixreborn.streamflix.profiles.TvUserProfileEntity
import com.streamflixreborn.streamflix.trakt.TraktApiClient
import com.streamflixreborn.streamflix.trakt.TraktDeviceCodeResponse
import com.streamflixreborn.streamflix.trakt.TraktDevicePollResult
import com.streamflixreborn.streamflix.trakt.TraktSyncManager
import com.streamflixreborn.streamflix.tv.components.AppleTvKeyboardMode
import com.streamflixreborn.streamflix.tv.components.AppleTvPinDialog
import com.streamflixreborn.streamflix.tv.components.AppleTvVirtualKeyboard
import com.streamflixreborn.streamflix.tv.components.PinDialogMode
import com.streamflixreborn.streamflix.tv.theme.AppleTvColors
import com.streamflixreborn.streamflix.utils.QrUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Dedicated Profile Settings, Trakt.tv Cloud Device Auth Flow, and 4-Digit PIN Parental Control Screen.
 */
@Composable
fun AppleTvProfileSettingsScreen(
    activeProfile: TvUserProfileEntity,
    parentalConfig: ParentalProtectionConfig,
    isSyncingTrakt: Boolean,
    onUpdateProfile: (TvUserProfileEntity) -> Unit,
    onSyncTraktNow: () -> Unit,
    onDisconnectTrakt: () -> Unit,
    onTraktLinkedSuccess: (username: String?) -> Unit,
    onNotifyToast: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var showTraktDeviceAuthModal by remember { mutableStateOf(false) }
    var showTraktApiKeysModal by remember { mutableStateOf(false) }
    var activePinDialog by remember { mutableStateOf<PinDialogMode?>(null) }

    val primaryFocusRequester = remember { FocusRequester() }

    LaunchedEffect(showTraktDeviceAuthModal, showTraktApiKeysModal, activePinDialog) {
        if (!showTraktDeviceAuthModal && !showTraktApiKeysModal && activePinDialog == null) {
            runCatching { primaryFocusRequester.requestFocus() }
        }
    }

    fun guardAdvancedAction(action: () -> Unit) {
        val requiresPin = parentalConfig.isSettingsProtectionActive ||
            (activeProfile.isKidsProfile && (parentalConfig.hasMasterPin || activeProfile.hasPinLock))
        if (!requiresPin) {
            action()
        } else {
            activePinDialog = PinDialogMode.Verify(
                title = "Control Parental Activo",
                subtitle = "Ingresa el PIN de 4 dígitos para modificar ajustes protegidos.",
                verify = { raw -> ParentalPinManager.verifyMasterOrFallbackPin(raw, activeProfile) },
                onVerified = {
                    activePinDialog = null
                    action()
                },
            )
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(20.dp),
            contentPadding = PaddingValues(start = 56.dp, top = 16.dp, end = 56.dp, bottom = 52.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "header") {
                Column {
                    Text(
                        text = "Ajustes de Perfil y Control Parental",
                        style = MaterialTheme.typography.headlineLarge,
                        color = AppleTvColors.PureWhite,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Perfil activo: ${activeProfile.name}" +
                            if (activeProfile.isKidsProfile) " · Modo Niños" else "",
                        style = MaterialTheme.typography.bodyLarge,
                        color = AppleTvColors.SecondaryText,
                    )
                }
            }

            // 1. TRAKT.TV CLOUD SYNC CARD
            item(key = "trakt_section") {
                SettingsCardContainer(
                    title = "Sincronización en la Nube con Trakt.tv",
                    subtitle = "Sincroniza automáticamente tu progreso («Continuar viendo»), scrobbling en tiempo real y lista de favoritos.",
                ) {
                    if (activeProfile.isTraktConnected) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CloudDone,
                                contentDescription = null,
                                tint = Color(0xFF81C784),
                                modifier = Modifier.size(22.dp),
                            )
                            Text(
                                text = "Cuenta vinculada" +
                                    (activeProfile.traktUsername?.let { " (@$it)" } ?: ""),
                                style = MaterialTheme.typography.titleMedium,
                                color = Color(0xFF81C784),
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            SettingsActionButton(
                                label = if (isSyncingTrakt) "Sincronizando…" else "Sincronizar ahora",
                                icon = Icons.Rounded.CloudSync,
                                primary = true,
                                modifier = Modifier.focusRequester(primaryFocusRequester),
                                onClick = {
                                    if (!isSyncingTrakt) onSyncTraktNow()
                                },
                            )
                            SettingsActionButton(
                                label = "Desvincular Trakt.tv",
                                icon = Icons.Rounded.LinkOff,
                                primary = false,
                                onClick = {
                                    guardAdvancedAction {
                                        onDisconnectTrakt()
                                        onNotifyToast("Cuenta de Trakt.tv desvinculada")
                                    }
                                },
                            )
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            SettingsActionButton(
                                label = "Vincular con código de dispositivo",
                                icon = Icons.Rounded.CloudSync,
                                primary = true,
                                modifier = Modifier.focusRequester(primaryFocusRequester),
                                onClick = {
                                    guardAdvancedAction {
                                        if (TraktApiClient.isConfigured(context)) {
                                            showTraktDeviceAuthModal = true
                                        } else {
                                            showTraktApiKeysModal = true
                                        }
                                    }
                                },
                            )
                            SettingsActionButton(
                                label = "Credenciales API Trakt",
                                icon = Icons.Rounded.Key,
                                primary = false,
                                onClick = {
                                    guardAdvancedAction {
                                        showTraktApiKeysModal = true
                                    }
                                },
                            )
                        }
                    }
                }
            }

            // 2. PROFILE PARENTAL LOCK & KIDS MODE CARD
            item(key = "profile_parental_section") {
                SettingsCardContainer(
                    title = "Control Parental del Perfil (${activeProfile.name})",
                    subtitle = "Activa el Modo Niños para restringir catálogos maduros o protege el ingreso a este perfil con un PIN de 4 dígitos.",
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SettingsActionButton(
                            label = if (activeProfile.isKidsProfile) {
                                "Modo Niños: Activado"
                            } else {
                                "Modo Niños: Desactivado"
                            },
                            icon = Icons.Rounded.ChildCare,
                            primary = activeProfile.isKidsProfile,
                            onClick = {
                                guardAdvancedAction {
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
                        )

                        SettingsActionButton(
                            label = if (activeProfile.hasPinLock) {
                                "Cambiar PIN del perfil"
                            } else {
                                "Bloquear perfil con PIN (4 dígitos)"
                            },
                            icon = Icons.Rounded.Lock,
                            primary = false,
                            onClick = {
                                guardAdvancedAction {
                                    activePinDialog = PinDialogMode.Setup(
                                        title = "PIN de 4 dígitos para ${activeProfile.name}",
                                        subtitle = "Se solicitará este código cada vez que alguien seleccione este perfil.",
                                        onPinConfirmed = { rawPin ->
                                            activePinDialog = null
                                            val hashed = ParentalPinManager.hashPin(rawPin)
                                            onUpdateProfile(activeProfile.copy(pinHash = hashed))
                                            onNotifyToast("PIN de perfil configurado")
                                        },
                                    )
                                }
                            },
                        )

                        if (activeProfile.hasPinLock) {
                            SettingsActionButton(
                                label = "Quitar PIN del perfil",
                                icon = Icons.Rounded.LockOpen,
                                primary = false,
                                onClick = {
                                    activePinDialog = PinDialogMode.Verify(
                                        title = "Quitar PIN de ${activeProfile.name}",
                                        subtitle = "Confirma el PIN actual para desbloquear este perfil.",
                                        verify = { raw -> ParentalPinManager.verifyProfilePin(raw, activeProfile) },
                                        onVerified = {
                                            activePinDialog = null
                                            onUpdateProfile(activeProfile.copy(pinHash = null))
                                            onNotifyToast("PIN eliminado del perfil")
                                        },
                                    )
                                },
                            )
                        }
                    }
                }
            }

            // 3. ADD-ONS & ADVANCED SETTINGS PROTECTION CARD
            item(key = "global_settings_lock_section") {
                SettingsCardContainer(
                    title = "Protección de Instalación de Add-ons y Ajustes Avanzados",
                    subtitle = "Exige un PIN maestro de 4 dígitos antes de usar el servidor QR local, añadir URLs de manifiestos o cambiar controles parentales.",
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SettingsActionButton(
                            label = if (parentalConfig.isSettingsProtectionActive) {
                                "Bloqueo de Add-ons y Ajustes: Activo"
                            } else {
                                "Bloquear Add-ons y Ajustes con PIN"
                            },
                            icon = Icons.Rounded.Security,
                            primary = parentalConfig.isSettingsProtectionActive,
                            onClick = {
                                if (!parentalConfig.hasMasterPin) {
                                    activePinDialog = PinDialogMode.Setup(
                                        title = "Crear PIN Parental Maestro",
                                        subtitle = "Protegerá la instalación de Add-ons por QR/URL y los ajustes avanzados.",
                                        onPinConfirmed = { rawPin ->
                                            activePinDialog = null
                                            ParentalPinManager.setMasterPin(
                                                rawPin = rawPin,
                                                protectAddonsAndSettings = true,
                                                context = context,
                                            )
                                            onNotifyToast("Protección de Add-ons y Ajustes activada")
                                        },
                                    )
                                } else {
                                    guardAdvancedAction {
                                        val nextState = !parentalConfig.protectAddonsAndSettings
                                        ParentalPinManager.setProtectAddonsAndSettings(nextState, context)
                                        onNotifyToast(
                                            if (nextState) {
                                                "Protección con PIN activada"
                                            } else {
                                                "Protección con PIN pausada"
                                            }
                                        )
                                    }
                                }
                            },
                        )

                        if (parentalConfig.hasMasterPin) {
                            SettingsActionButton(
                                label = "Cambiar PIN Maestro",
                                icon = Icons.Rounded.Key,
                                primary = false,
                                onClick = {
                                    guardAdvancedAction {
                                        activePinDialog = PinDialogMode.Setup(
                                            title = "Nuevo PIN Parental Maestro",
                                            subtitle = "Introduce un nuevo código numérico de 4 dígitos.",
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
                            )

                            SettingsActionButton(
                                label = "Eliminar PIN Maestro",
                                icon = Icons.Rounded.LockOpen,
                                primary = false,
                                onClick = {
                                    guardAdvancedAction {
                                        ParentalPinManager.clearMasterPin(context)
                                        onNotifyToast("PIN Maestro eliminado")
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        // Trakt.tv Device Auth Flow Modal
        AnimatedVisibility(
            visible = showTraktDeviceAuthModal,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
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

        // Optional Trakt Client ID / Secret Setup Modal (powered by native D-Pad Virtual Keyboard)
        AnimatedVisibility(
            visible = showTraktApiKeysModal,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
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

        // 4-Digit PIN Modal
        AnimatedVisibility(
            visible = activePinDialog != null,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            activePinDialog?.let { dialogMode ->
                AppleTvPinDialog(
                    mode = dialogMode,
                    onDismiss = { activePinDialog = null },
                )
            }
        }
    }
}

@Composable
private fun SettingsCardContainer(
    title: String,
    subtitle: String,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(AppleTvColors.GlassSurface)
            .border(1.dp, Color(0x24FFFFFF), RoundedCornerShape(22.dp))
            .padding(horizontal = 26.dp, vertical = 22.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = AppleTvColors.PureWhite,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = AppleTvColors.SecondaryText,
        )
        Spacer(modifier = Modifier.height(18.dp))
        content()
    }
}

@Composable
private fun SettingsActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    primary: Boolean,
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
                elevationColor = AppleTvColors.FocusGlow,
                elevation = 16.dp,
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
            containerColor = if (primary) AppleTvColors.PureWhite else Color(0xFF181C28),
            contentColor = if (primary) AppleTvColors.Obsidian else AppleTvColors.PureWhite,
            focusedContainerColor = AppleTvColors.PureWhite,
            focusedContentColor = AppleTvColors.Obsidian,
        ),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 11.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
internal fun TraktDeviceAuthModal(
    profileId: Long,
    onDismiss: () -> Unit,
    onAuthorized: (username: String?) -> Unit,
    onOpenCredentialsSetup: () -> Unit,
) {
    val context = LocalContext.current
    val closeRequester = remember { FocusRequester() }
    var codeData by remember { mutableStateOf<TraktDeviceCodeResponse?>(null) }
    var isLoadingCode by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var secondsRemaining by remember { mutableIntStateOf(600) }
    var retryCounter by remember { mutableIntStateOf(0) }

    BackHandler {
        onDismiss()
    }

    LaunchedEffect(Unit) {
        runCatching { closeRequester.requestFocus() }
    }

    LaunchedEffect(retryCounter) {
        isLoadingCode = true
        errorMessage = null
        codeData = null

        val result = TraktApiClient.requestDeviceCode(context)
        isLoadingCode = false
        val response = result.getOrElse { err ->
            errorMessage = err.message ?: "No se pudo conectar con Trakt.tv."
            return@LaunchedEffect
        }

        codeData = response
        secondsRemaining = response.expiresIn.coerceAtLeast(60)
        var pollIntervalSec = response.interval.coerceAtLeast(5)

        while (isActive && secondsRemaining > 0) {
            delay(pollIntervalSec * 1_000L)
            secondsRemaining = (secondsRemaining - pollIntervalSec).coerceAtLeast(0)

            when (val poll = TraktApiClient.pollDeviceToken(response.deviceCode, context)) {
                is TraktDevicePollResult.Pending -> Unit
                is TraktDevicePollResult.SlowDown -> {
                    pollIntervalSec += 5
                }
                is TraktDevicePollResult.Authorized -> {
                    val username = TraktSyncManager.completeDeviceAuthForProfile(
                        profileId = profileId,
                        token = poll.token,
                        context = context,
                    )
                    onAuthorized(username)
                    return@LaunchedEffect
                }
                is TraktDevicePollResult.Failed -> {
                    errorMessage = poll.message
                    return@LaunchedEffect
                }
            }
        }
        if (secondsRemaining <= 0) {
            errorMessage = "El código expiró. Genera un nuevo código para continuar."
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xEB06070B))
            .padding(48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .widthIn(max = 640.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(Color(0xF5141722))
                .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(28.dp))
                .padding(horizontal = 40.dp, vertical = 32.dp),
        ) {
            Text(
                text = "Vincular cuenta de Trakt.tv",
                style = MaterialTheme.typography.headlineSmall,
                color = AppleTvColors.PureWhite,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Escanea el código QR o abre el enlace desde tu teléfono o computadora e ingresa el código alfanumérico:",
                style = MaterialTheme.typography.bodyMedium,
                color = AppleTvColors.SecondaryText,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(24.dp))

            when {
                isLoadingCode -> {
                    CircularProgressIndicator(
                        color = AppleTvColors.PureWhite,
                        strokeWidth = 2.5.dp,
                        modifier = Modifier.size(38.dp),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Solicitando código de activación a Trakt.tv…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppleTvColors.SecondaryText,
                    )
                }

                errorMessage != null -> {
                    Text(
                        text = errorMessage.orEmpty(),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color(0xFFFF8A80),
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = { retryCounter += 1 },
                            shape = ButtonDefaults.shape(shape = RoundedCornerShape(50)),
                            colors = ButtonDefaults.colors(
                                containerColor = AppleTvColors.PureWhite,
                                contentColor = AppleTvColors.Obsidian,
                                focusedContainerColor = AppleTvColors.PureWhite,
                                focusedContentColor = AppleTvColors.Obsidian,
                            ),
                            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 10.dp),
                        ) {
                            Text("Generar nuevo código", style = MaterialTheme.typography.labelLarge)
                        }
                        Button(
                            onClick = onOpenCredentialsSetup,
                            shape = ButtonDefaults.shape(shape = RoundedCornerShape(50)),
                            colors = ButtonDefaults.colors(
                                containerColor = AppleTvColors.GlassSurface,
                                contentColor = AppleTvColors.PureWhite,
                                focusedContainerColor = AppleTvColors.PureWhite,
                                focusedContentColor = AppleTvColors.Obsidian,
                            ),
                            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 10.dp),
                        ) {
                            Text("Configurar API Key", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }

                codeData != null -> {
                    val activeCode = codeData!!
                    val qrBitmap = remember(activeCode.verificationUrl) {
                        QrUtils.generateOrNull(activeCode.verificationUrl, size = 420)
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(28.dp),
                    ) {
                        if (qrBitmap != null) {
                            Box(
                                modifier = Modifier
                                    .size(170.dp)
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(Color.White)
                                    .padding(10.dp),
                            ) {
                                Image(
                                    bitmap = qrBitmap.asImageBitmap(),
                                    contentDescription = activeCode.verificationUrl,
                                    contentScale = ContentScale.Fit,
                                    filterQuality = FilterQuality.None,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }

                        Column(horizontalAlignment = Alignment.Start) {
                            Text(
                                text = activeCode.verificationUrl.removePrefix("https://"),
                                style = MaterialTheme.typography.titleMedium,
                                color = AppleTvColors.SecondaryText,
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Color(0xFF0B0D14))
                                    .border(2.dp, AppleTvColors.PureWhite, RoundedCornerShape(14.dp))
                                    .padding(horizontal = 24.dp, vertical = 14.dp),
                            ) {
                                Text(
                                    text = activeCode.userCode,
                                    style = MaterialTheme.typography.displayMedium.copy(
                                        fontSize = 32.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 4.sp,
                                    ),
                                    color = AppleTvColors.PureWhite,
                                )
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "Esperando autorización… (${secondsRemaining}s)",
                                style = MaterialTheme.typography.bodyMedium,
                                color = AppleTvColors.MutedText,
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = onDismiss,
                modifier = Modifier.focusRequester(closeRequester),
                shape = ButtonDefaults.shape(shape = RoundedCornerShape(50)),
                colors = ButtonDefaults.colors(
                    containerColor = AppleTvColors.GlassSurface,
                    contentColor = AppleTvColors.PureWhite,
                    focusedContainerColor = AppleTvColors.PureWhite,
                    focusedContentColor = AppleTvColors.Obsidian,
                ),
                contentPadding = PaddingValues(horizontal = 28.dp, vertical = 10.dp),
            ) {
                Text("Cancelar", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
internal fun TraktCredentialsSetupModal(
    onDismiss: () -> Unit,
    onSavedAndProceed: () -> Unit,
) {
    val context = LocalContext.current
    val kbRequester = remember { FocusRequester() }
    val saveBtnRequester = remember { FocusRequester() }

    var editingSecret by remember { mutableStateOf(false) }
    var clientId by remember { mutableStateOf(TraktApiClient.resolveClientId(context)) }
    var clientSecret by remember { mutableStateOf(TraktApiClient.resolveClientSecret(context)) }

    BackHandler { onDismiss() }

    LaunchedEffect(Unit) {
        runCatching { kbRequester.requestFocus() }
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
            horizontalArrangement = Arrangement.spacedBy(26.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.width(450.dp)) {
                Text(
                    text = "Credenciales API de Trakt.tv",
                    style = MaterialTheme.typography.headlineSmall,
                    color = AppleTvColors.PureWhite,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = if (!editingSecret) "1. Ingresa tu Trakt Client ID" else "2. Ingresa tu Trakt Client Secret",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppleTvColors.SecondaryText,
                )
                Spacer(modifier = Modifier.height(12.dp))

                AppleTvVirtualKeyboard(
                    value = if (!editingSecret) clientId else clientSecret,
                    onValueChange = { updated ->
                        if (!editingSecret) clientId = updated.trim() else clientSecret = updated.trim()
                    },
                    mode = AppleTvKeyboardMode.URL,
                    initialFocusRequester = kbRequester,
                    rightExitFocusRequester = saveBtnRequester,
                    canExitRight = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { editingSecret = false },
                        shape = ButtonDefaults.shape(shape = RoundedCornerShape(50)),
                        colors = ButtonDefaults.colors(
                            containerColor = if (!editingSecret) Color(0x38FFFFFF) else AppleTvColors.GlassSurface,
                            contentColor = AppleTvColors.PureWhite,
                            focusedContainerColor = AppleTvColors.PureWhite,
                            focusedContentColor = AppleTvColors.Obsidian,
                        ),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text("Client ID", style = MaterialTheme.typography.labelLarge)
                    }
                    Button(
                        onClick = { editingSecret = true },
                        shape = ButtonDefaults.shape(shape = RoundedCornerShape(50)),
                        colors = ButtonDefaults.colors(
                            containerColor = if (editingSecret) Color(0x38FFFFFF) else AppleTvColors.GlassSurface,
                            contentColor = AppleTvColors.PureWhite,
                            focusedContainerColor = AppleTvColors.PureWhite,
                            focusedContentColor = AppleTvColors.Obsidian,
                        ),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text("Client Secret", style = MaterialTheme.typography.labelLarge)
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF0A0C12))
                        .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(12.dp))
                        .padding(14.dp),
                ) {
                    Column {
                        Text(
                            text = "Client ID: ${clientId.ifBlank { "(vacío)" }}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppleTvColors.PureWhite,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        val maskedSecret = if (clientSecret.isBlank()) {
                            "(vacío)"
                        } else {
                            "${clientSecret.take(6)}••••••••"
                        }
                        Text(
                            text = "Client Secret: $maskedSecret",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppleTvColors.SecondaryText,
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = {
                            TraktApiClient.saveCustomCredentials(clientId, clientSecret, context)
                            onSavedAndProceed()
                        },
                        enabled = clientId.isNotBlank() && clientSecret.isNotBlank(),
                        modifier = Modifier.focusRequester(saveBtnRequester),
                        shape = ButtonDefaults.shape(shape = RoundedCornerShape(50)),
                        colors = ButtonDefaults.colors(
                            containerColor = AppleTvColors.PureWhite,
                            contentColor = AppleTvColors.Obsidian,
                            focusedContainerColor = AppleTvColors.PureWhite,
                            focusedContentColor = AppleTvColors.Obsidian,
                        ),
                        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 11.dp),
                    ) {
                        Text("Guardar y Vincular", style = MaterialTheme.typography.labelLarge)
                    }

                    Button(
                        onClick = onDismiss,
                        shape = ButtonDefaults.shape(shape = RoundedCornerShape(50)),
                        colors = ButtonDefaults.colors(
                            containerColor = AppleTvColors.GlassSurface,
                            contentColor = AppleTvColors.PureWhite,
                            focusedContainerColor = AppleTvColors.PureWhite,
                            focusedContentColor = AppleTvColors.Obsidian,
                        ),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 11.dp),
                    ) {
                        Text("Cancelar", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}
