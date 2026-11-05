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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import coil.compose.AsyncImage
import com.streamflixreborn.streamflix.addons.CustomAddonRepository
import com.streamflixreborn.streamflix.addons.QrAddonPairingServer
import com.streamflixreborn.streamflix.addons.QrPairingStatus
import com.streamflixreborn.streamflix.profiles.TvCustomAddonEntity
import com.streamflixreborn.streamflix.tv.components.AppleTvKeyboardMode
import com.streamflixreborn.streamflix.tv.components.AppleTvVirtualKeyboard
import com.streamflixreborn.streamflix.tv.theme.AppleTvColors
import com.streamflixreborn.streamflix.utils.TvImageOptimizer
import kotlinx.coroutines.launch

@Composable
fun AppleTvAddonsScreen(
    installedAddons: List<TvCustomAddonEntity>,
    onToggleAddonEnabled: (addonId: String, enabled: Boolean) -> Unit,
    onDeleteAddon: (addonId: String) -> Unit,
    onAddonInstalledSuccess: (TvCustomAddonEntity) -> Unit,
    onProtectedAction: ((action: () -> Unit) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var showQrModal by remember { mutableStateOf(false) }
    var showManualUrlModal by remember { mutableStateOf(false) }
    val addBtnRequester = remember { FocusRequester() }

    fun runGuarded(action: () -> Unit) {
        if (onProtectedAction != null) {
            onProtectedAction(action)
        } else {
            action()
        }
    }

    LaunchedEffect(showQrModal, showManualUrlModal) {
        if (!showQrModal && !showManualUrlModal) {
            runCatching { addBtnRequester.requestFocus() }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 56.dp, vertical = 18.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Add-ons Personalizados",
                        style = MaterialTheme.typography.headlineLarge,
                        color = AppleTvColors.PureWhite,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Vincula nuevos catálogos o fuentes compatibles desde el navegador de tu teléfono escaneando un código QR.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = AppleTvColors.SecondaryText,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Spacer(modifier = Modifier.width(24.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { runGuarded { showQrModal = true } },
                        modifier = Modifier.focusRequester(addBtnRequester),
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
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.QrCode2,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Añadir Add-on vía QR",
                            style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp),
                        )
                    }

                    // Dual usability: Optional manual URL input via native D-Pad QWERTY keyboard
                    Button(
                        onClick = { runGuarded { showManualUrlModal = true } },
                        shape = ButtonDefaults.shape(shape = RoundedCornerShape(50)),
                        scale = ButtonDefaults.scale(focusedScale = 1.07f),
                        glow = ButtonDefaults.glow(
                            focusedGlow = Glow(
                                elevationColor = AppleTvColors.FocusGlow,
                                elevation = 16.dp,
                            )
                        ),
                        border = ButtonDefaults.border(
                            border = Border(
                                border = BorderStroke(1.dp, Color(0x36FFFFFF)),
                                shape = RoundedCornerShape(50),
                            ),
                            focusedBorder = Border(
                                border = BorderStroke(2.dp, AppleTvColors.PureWhite),
                                shape = RoundedCornerShape(50),
                            ),
                        ),
                        colors = ButtonDefaults.colors(
                            containerColor = AppleTvColors.GlassSurface,
                            contentColor = AppleTvColors.PureWhite,
                            focusedContainerColor = AppleTvColors.PureWhite,
                            focusedContentColor = AppleTvColors.Obsidian,
                        ),
                        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 14.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Keyboard,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "URL manual",
                            style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            if (installedAddons.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(22.dp))
                        .background(AppleTvColors.GlassSurface)
                        .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(22.dp))
                        .padding(36.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Rounded.Extension,
                            contentDescription = null,
                            tint = AppleTvColors.MutedText,
                            modifier = Modifier.size(44.dp),
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "Aún no has añadido add-ons personalizados",
                            style = MaterialTheme.typography.headlineSmall,
                            color = AppleTvColors.PureWhite,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Selecciona «Añadir Add-on vía QR» para enviar un archivo manifest.json desde tu móvil en segundos.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppleTvColors.SecondaryText,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.widthIn(max = 520.dp),
                        )
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = PaddingValues(bottom = 42.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    items(
                        items = installedAddons,
                        key = { it.addonId },
                    ) { addon ->
                        InstalledAddonRowCard(
                            addon = addon,
                            onToggle = { runGuarded { onToggleAddonEnabled(addon.addonId, !addon.isEnabled) } },
                            onDelete = { runGuarded { onDeleteAddon(addon.addonId) } },
                        )
                    }
                }
            }
        }

        // Centered Local QR Pairing Modal (Ktor CIO + ZXing)
        AnimatedVisibility(
            visible = showQrModal,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            if (showQrModal) {
                QrAddonPairingModal(
                    onDismiss = { showQrModal = false },
                    onInstalled = { installedAddon ->
                        showQrModal = false
                        onAddonInstalledSuccess(installedAddon)
                    },
                )
            }
        }

        AnimatedVisibility(
            visible = showManualUrlModal,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            if (showManualUrlModal) {
                ManualAddonUrlModal(
                    onDismiss = { showManualUrlModal = false },
                    onInstalled = { installedAddon ->
                        showManualUrlModal = false
                        onAddonInstalledSuccess(installedAddon)
                    },
                )
            }
        }
    }
}

@Composable
internal fun InstalledAddonRowCard(
    addon: TvCustomAddonEntity,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(AppleTvColors.GlassSurface)
            .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(18.dp))
            .padding(horizontal = 22.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f),
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(AppleTvColors.DeepCharcoal)
                    .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (!addon.logoUrl.isNullOrBlank()) {
                    val req = remember(addon.logoUrl) {
                        TvImageOptimizer.avatarRequest(context, addon.logoUrl, sizePx = 120)
                    }
                    AsyncImage(
                        model = req,
                        contentDescription = addon.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Extension,
                        contentDescription = null,
                        tint = AppleTvColors.PureWhite,
                        modifier = Modifier.size(26.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = addon.name,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = AppleTvColors.PureWhite,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "v${addon.version}",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp),
                        color = AppleTvColors.MutedText,
                    )
                }
                if (!addon.description.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = addon.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppleTvColors.SecondaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = addon.resourcesCsv.split(",")
                        .filter { it.isNotBlank() }
                        .joinToString(" · ") { it.uppercase() },
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.6.sp,
                    ),
                    color = AppleTvColors.MutedText,
                )
            }
        }

        Spacer(modifier = Modifier.width(18.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onToggle,
                shape = ButtonDefaults.shape(shape = RoundedCornerShape(50)),
                colors = ButtonDefaults.colors(
                    containerColor = if (addon.isEnabled) Color(0x3343A047) else Color(0x26FFFFFF),
                    contentColor = if (addon.isEnabled) Color(0xFFA5D6A7) else AppleTvColors.SecondaryText,
                    focusedContainerColor = AppleTvColors.PureWhite,
                    focusedContentColor = AppleTvColors.Obsidian,
                ),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 9.dp),
            ) {
                Text(
                    text = if (addon.isEnabled) "Activo" else "Pausado",
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
                )
            }

            Button(
                onClick = onDelete,
                shape = ButtonDefaults.shape(shape = RoundedCornerShape(50)),
                colors = ButtonDefaults.colors(
                    containerColor = Color(0x29E53935),
                    contentColor = Color(0xFFFF8A80),
                    focusedContainerColor = Color(0xFFE53935),
                    focusedContentColor = AppleTvColors.PureWhite,
                ),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 9.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.DeleteOutline,
                    contentDescription = "Eliminar",
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
internal fun QrAddonPairingModal(
    onDismiss: () -> Unit,
    onInstalled: (TvCustomAddonEntity) -> Unit,
) {
    val context = LocalContext.current
    val closeBtnRequester = remember { FocusRequester() }
    val pairingServer = remember { QrAddonPairingServer(context.applicationContext) }
    val pairingStatus by pairingServer.status.collectAsState()

    BackHandler(onBack = onDismiss)

    DisposableEffect(pairingServer) {
        pairingServer.startPairingSession()
        onDispose {
            pairingServer.stopPairingSession()
        }
    }

    LaunchedEffect(Unit) {
        runCatching { closeBtnRequester.requestFocus() }
    }

    LaunchedEffect(pairingStatus) {
        val current = pairingStatus
        if (current is QrPairingStatus.Installed) {
            pairingServer.stopPairingSession()
            onInstalled(current.addon)
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
                .widthIn(max = 560.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(Color(0xF5141722))
                .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(28.dp))
                .padding(horizontal = 40.dp, vertical = 34.dp),
        ) {
            Text(
                text = "Añadir Add-on vía QR",
                style = MaterialTheme.typography.headlineSmall,
                color = AppleTvColors.PureWhite,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Escanea el código con la cámara de tu teléfono conectado a la misma red Wi-Fi e introduce la URL de manifest.json.",
                style = MaterialTheme.typography.bodyMedium,
                color = AppleTvColors.SecondaryText,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(26.dp))

            when (val state = pairingStatus) {
                is QrPairingStatus.Idle,
                is QrPairingStatus.Starting -> {
                    Box(
                        modifier = Modifier.size(240.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            color = AppleTvColors.PureWhite,
                            strokeWidth = 2.6.dp,
                        )
                    }
                    Spacer(modifier = Modifier.height(18.dp))
                    StatusIndicatorPill(text = "Iniciando servidor local...", isBusy = true)
                }

                is QrPairingStatus.WaitingConnection -> {
                    QrBitmapCard(bitmap = state.qrBitmap)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = state.serverUrl,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.4.sp,
                        ),
                        color = AppleTvColors.PureWhite,
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    StatusIndicatorPill(text = state.statusMessage, isBusy = true)
                }

                is QrPairingStatus.ValidatingManifest -> {
                    QrBitmapCard(bitmap = state.qrBitmap)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = state.serverUrl,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = AppleTvColors.PureWhite,
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    StatusIndicatorPill(text = state.statusMessage, isBusy = true)
                }

                is QrPairingStatus.Error -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0x29E53935))
                            .border(1.dp, Color(0x55E53935), RoundedCornerShape(16.dp))
                            .padding(20.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color(0xFFFF9E99),
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                is QrPairingStatus.Installed -> {
                    StatusIndicatorPill(text = "Add-on instalado correctamente", isBusy = false)
                }
            }

            Spacer(modifier = Modifier.height(26.dp))

            Button(
                onClick = onDismiss,
                modifier = Modifier.focusRequester(closeBtnRequester),
                shape = ButtonDefaults.shape(shape = RoundedCornerShape(50)),
                colors = ButtonDefaults.colors(
                    containerColor = AppleTvColors.GlassSurface,
                    contentColor = AppleTvColors.PureWhite,
                    focusedContainerColor = AppleTvColors.PureWhite,
                    focusedContentColor = AppleTvColors.Obsidian,
                ),
                contentPadding = PaddingValues(horizontal = 28.dp, vertical = 11.dp),
            ) {
                Text(
                    text = "Cancelar",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
private fun QrBitmapCard(bitmap: android.graphics.Bitmap) {
    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
    Box(
        modifier = Modifier
            .size(236.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White)
            .padding(14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = imageBitmap,
            contentDescription = "Código QR de emparejamiento local",
            filterQuality = FilterQuality.None,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun StatusIndicatorPill(
    text: String,
    isBusy: Boolean,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0x24FFFFFF))
            .border(1.dp, Color(0x2EFFFFFF), RoundedCornerShape(50))
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        if (isBusy) {
            CircularProgressIndicator(
                color = AppleTvColors.PureWhite,
                strokeWidth = 2.dp,
                modifier = Modifier.size(14.dp),
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = Color(0xFF81C784),
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = AppleTvColors.PureWhite,
        )
    }
}

@Composable
internal fun ManualAddonUrlModal(
    onDismiss: () -> Unit,
    onInstalled: (TvCustomAddonEntity) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboardRequester = remember { FocusRequester() }
    val installButtonRequester = remember { FocusRequester() }

    var manifestUrl by remember { mutableStateOf("https://") }
    var isValidating by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    BackHandler {
        if (!isValidating) onDismiss()
    }

    LaunchedEffect(Unit) {
        runCatching { keyboardRequester.requestFocus() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xEB06070B))
            .padding(horizontal = 48.dp, vertical = 30.dp),
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
            // Left Column: Native D-Pad URL Virtual Keyboard (1.15x scale + instant glow)
            Column(modifier = Modifier.width(460.dp)) {
                Text(
                    text = "Ingresar URL de manifest.json",
                    style = MaterialTheme.typography.headlineSmall,
                    color = AppleTvColors.PureWhite,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Escribe la dirección del add-on con el teclado optimizado para control remoto.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppleTvColors.SecondaryText,
                )
                Spacer(modifier = Modifier.height(14.dp))

                AppleTvVirtualKeyboard(
                    value = manifestUrl,
                    onValueChange = {
                        errorMessage = null
                        manifestUrl = it
                    },
                    mode = AppleTvKeyboardMode.URL,
                    initialFocusRequester = keyboardRequester,
                    rightExitFocusRequester = installButtonRequester,
                    canExitRight = !isValidating,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Right Column: Live URL Preview + Async Validation + Install Action
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = "VISTA PREVIA DE URL",
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontSize = 11.sp,
                        letterSpacing = 1.1.sp,
                    ),
                    color = AppleTvColors.MutedText,
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF0A0C12))
                        .border(1.5.dp, Color(0x38FFFFFF), RoundedCornerShape(14.dp))
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                ) {
                    Text(
                        text = "$manifestUrl▏",
                        style = MaterialTheme.typography.titleMedium,
                        color = AppleTvColors.PureWhite,
                    )
                }

                if (errorMessage != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0x2EE53935))
                            .border(1.dp, Color(0x66E53935), RoundedCornerShape(12.dp))
                            .padding(14.dp),
                    ) {
                        Text(
                            text = errorMessage.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFFFF9E99),
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = {
                            if (isValidating) return@Button
                            isValidating = true
                            errorMessage = null
                            scope.launch {
                                val res = CustomAddonRepository.validateAndInstallAddon(manifestUrl, context)
                                isValidating = false
                                res.fold(
                                    onSuccess = { entity -> onInstalled(entity) },
                                    onFailure = { err ->
                                        errorMessage = err.message ?: "No se pudo validar el archivo manifest.json."
                                    },
                                )
                            }
                        },
                        enabled = !isValidating && manifestUrl.length > 8,
                        modifier = Modifier.focusRequester(installButtonRequester),
                        shape = ButtonDefaults.shape(shape = RoundedCornerShape(50)),
                        scale = ButtonDefaults.scale(focusedScale = 1.08f),
                        colors = ButtonDefaults.colors(
                            containerColor = AppleTvColors.PureWhite,
                            contentColor = AppleTvColors.Obsidian,
                            focusedContainerColor = AppleTvColors.PureWhite,
                            focusedContentColor = AppleTvColors.Obsidian,
                        ),
                        contentPadding = PaddingValues(horizontal = 26.dp, vertical = 13.dp),
                    ) {
                        if (isValidating) {
                            CircularProgressIndicator(
                                color = AppleTvColors.Obsidian,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("Validando…", style = MaterialTheme.typography.labelLarge)
                        } else {
                            Text("Instalar Add-on", style = MaterialTheme.typography.labelLarge)
                        }
                    }

                    Button(
                        onClick = { if (!isValidating) onDismiss() },
                        shape = ButtonDefaults.shape(shape = RoundedCornerShape(50)),
                        colors = ButtonDefaults.colors(
                            containerColor = AppleTvColors.GlassSurface,
                            contentColor = AppleTvColors.PureWhite,
                            focusedContainerColor = AppleTvColors.PureWhite,
                            focusedContentColor = AppleTvColors.Obsidian,
                        ),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 13.dp),
                    ) {
                        Text("Cancelar", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}
