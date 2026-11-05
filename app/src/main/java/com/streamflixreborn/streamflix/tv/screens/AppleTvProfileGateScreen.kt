@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.streamflixreborn.streamflix.tv.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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
import com.streamflixreborn.streamflix.profiles.MAX_PROFILES
import com.streamflixreborn.streamflix.profiles.ParentalPinManager
import com.streamflixreborn.streamflix.profiles.TvUserProfileEntity
import com.streamflixreborn.streamflix.stremio.NuvioAvatarAsset
import com.streamflixreborn.streamflix.tv.components.AppleTvPinDialog
import com.streamflixreborn.streamflix.tv.components.PinDialogMode
import com.streamflixreborn.streamflix.tv.theme.AppleTvColors
import com.streamflixreborn.streamflix.utils.TvImageOptimizer

@Composable
fun AppleTvProfileGateScreen(
    profiles: List<TvUserProfileEntity>,
    nuvioAvatars: List<NuvioAvatarAsset>,
    onProfileSelected: (TvUserProfileEntity) -> Unit,
    onCreateProfile: (
        name: String,
        avatar: NuvioAvatarAsset,
        preferredAudioLang: String,
        isKidsProfile: Boolean,
        rawPin: String?,
    ) -> Unit,
    onUpdateProfile: (TvUserProfileEntity) -> Unit,
    onDeleteProfile: (Long) -> Unit,
    onExitApp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var manageMode by remember { mutableStateOf(false) }
    var editorTarget by remember { mutableStateOf<ProfileEditorState?>(null) }
    var activePinDialog by remember { mutableStateOf<PinDialogMode?>(null) }
    val firstProfileRequester = remember { FocusRequester() }

    val canAddProfile = profiles.size < MAX_PROFILES

    BackHandler {
        when {
            activePinDialog != null -> activePinDialog = null
            editorTarget != null -> editorTarget = null
            manageMode -> manageMode = false
            else -> onExitApp()
        }
    }

    LaunchedEffect(profiles.size, editorTarget, activePinDialog) {
        if (editorTarget == null && activePinDialog == null && profiles.isNotEmpty()) {
            runCatching { firstProfileRequester.requestFocus() }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF151926),
                        AppleTvColors.Obsidian,
                    ),
                    radius = 1200f,
                )
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 56.dp),
        ) {
            Text(
                text = if (manageMode) "Administrar perfiles" else "¿Quién está viendo?",
                style = MaterialTheme.typography.displayMedium,
                color = AppleTvColors.PureWhite,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = if (canAddProfile) {
                    "Selecciona tu perfil para personalizar tu historial (${profiles.size}/$MAX_PROFILES)"
                } else {
                    "Límite máximo alcanzado ($MAX_PROFILES/$MAX_PROFILES perfiles)"
                },
                style = MaterialTheme.typography.bodyLarge,
                color = AppleTvColors.SecondaryText,
            )

            Spacer(modifier = Modifier.height(42.dp))

            // Centered Profile Grid (up to MAX_PROFILES = 5 + Add Profile button)
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally),
                contentPadding = PaddingValues(horizontal = 32.dp, vertical = 18.dp),
            ) {
                itemsIndexed(
                    items = profiles.take(MAX_PROFILES),
                    key = { _, profile -> profile.id },
                ) { idx, profile ->
                    ProfileGateCard(
                        profile = profile,
                        manageMode = manageMode,
                        modifier = if (idx == 0) Modifier.focusRequester(firstProfileRequester) else Modifier,
                        onClick = {
                            if (manageMode) {
                                if (profile.hasPinLock) {
                                    activePinDialog = PinDialogMode.Verify(
                                        title = "Editar ${profile.name}",
                                        subtitle = "Ingresa el PIN de 4 dígitos de este perfil.",
                                        verify = { raw -> ParentalPinManager.verifyProfilePin(raw, profile) },
                                        onVerified = {
                                            activePinDialog = null
                                            editorTarget = ProfileEditorState.Edit(profile)
                                        },
                                    )
                                } else {
                                    editorTarget = ProfileEditorState.Edit(profile)
                                }
                            } else if (profile.hasPinLock) {
                                activePinDialog = PinDialogMode.Verify(
                                    title = "Perfil bloqueado (${profile.name})",
                                    subtitle = "Ingresa tu PIN de 4 dígitos para acceder.",
                                    verify = { raw -> ParentalPinManager.verifyProfilePin(raw, profile) },
                                    onVerified = {
                                        activePinDialog = null
                                        onProfileSelected(profile)
                                    },
                                )
                            } else {
                                onProfileSelected(profile)
                            }
                        },
                    )
                }

                item(key = "add_profile_slot") {
                    AddProfileGateButton(
                        enabled = canAddProfile,
                        onClick = {
                            if (canAddProfile) {
                                val defaultAvatar = nuvioAvatars.getOrNull(profiles.size % nuvioAvatars.size.coerceAtLeast(1))
                                    ?: NuvioAvatarAsset(
                                        id = "default_avatar",
                                        label = "Perfil",
                                        imageUrl = "",
                                        accentColorHex = "#1E88E5",
                                    )
                                editorTarget = ProfileEditorState.Create(
                                    suggestedName = "Perfil ${profiles.size + 1}",
                                    initialAvatar = defaultAvatar,
                                )
                            }
                        },
                    )
                }
            }

            Spacer(modifier = Modifier.height(36.dp))

            // Manage Profiles toggle button
            if (profiles.isNotEmpty()) {
                Button(
                    onClick = { manageMode = !manageMode },
                    shape = ButtonDefaults.shape(shape = RoundedCornerShape(50)),
                    scale = ButtonDefaults.scale(focusedScale = 1.06f),
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
                        containerColor = if (manageMode) AppleTvColors.PureWhite else AppleTvColors.GlassSurface,
                        contentColor = if (manageMode) AppleTvColors.Obsidian else AppleTvColors.PureWhite,
                        focusedContainerColor = AppleTvColors.PureWhite,
                        focusedContentColor = AppleTvColors.Obsidian,
                    ),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 11.dp),
                ) {
                    Icon(
                        imageVector = if (manageMode) Icons.Rounded.Check else Icons.Rounded.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (manageMode) "Listo" else "Administrar perfiles",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }

        // Create / Edit Profile Modal with Nuvio Catalog Avatar Picker
        AnimatedVisibility(
            visible = editorTarget != null,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            editorTarget?.let { state ->
                ProfileEditorOverlay(
                    editorState = state,
                    nuvioAvatars = nuvioAvatars,
                    canDelete = profiles.size > 1 && state is ProfileEditorState.Edit,
                    onSaveCreate = { name, avatar, audioLang, isKids, rawPin ->
                        onCreateProfile(name, avatar, audioLang, isKids, rawPin)
                        editorTarget = null
                    },
                    onSaveEdit = { updated ->
                        onUpdateProfile(updated)
                        editorTarget = null
                    },
                    onRequestPinSetup = { onPinReady ->
                        activePinDialog = PinDialogMode.Setup(
                            title = "PIN de 4 dígitos del perfil",
                            subtitle = "Protege el acceso a este perfil con un código numérico.",
                            onPinConfirmed = { pin ->
                                activePinDialog = null
                                onPinReady(pin)
                            },
                        )
                    },
                    onDelete = { profileId ->
                        onDeleteProfile(profileId)
                        editorTarget = null
                    },
                    onDismiss = { editorTarget = null },
                )
            }
        }

        // 4-Digit Parental PIN Lock Modal
        AnimatedVisibility(
            visible = activePinDialog != null,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            activePinDialog?.let { pinMode ->
                AppleTvPinDialog(
                    mode = pinMode,
                    onDismiss = { activePinDialog = null },
                )
            }
        }
    }
}

private sealed interface ProfileEditorState {
    data class Create(
        val suggestedName: String,
        val initialAvatar: NuvioAvatarAsset,
    ) : ProfileEditorState

    data class Edit(
        val profile: TvUserProfileEntity,
    ) : ProfileEditorState
}

@Composable
private fun ProfileGateCard(
    profile: TvUserProfileEntity,
    manageMode: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var isFocused by remember { mutableStateOf(false) }
    val avatarShape = RoundedCornerShape(28.dp)
    val accentColor = remember(profile.avatarColorHex) {
        parseHexColor(profile.avatarColorHex)
    }
    val avatarReq = remember(profile.avatarUrl) {
        TvImageOptimizer.avatarRequest(context, profile.avatarUrl, sizePx = 240)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.width(148.dp),
    ) {
        Card(
            onClick = onClick,
            modifier = Modifier
                .size(136.dp)
                .onFocusChanged { isFocused = it.isFocused },
            shape = CardDefaults.shape(shape = avatarShape),
            scale = CardDefaults.scale(focusedScale = 1.1f),
            border = CardDefaults.border(
                border = Border(
                    border = BorderStroke(2.dp, accentColor.copy(alpha = 0.65f)),
                    shape = avatarShape,
                ),
                focusedBorder = Border(
                    border = BorderStroke(3.dp, AppleTvColors.PureWhite),
                    shape = avatarShape,
                ),
            ),
            glow = CardDefaults.glow(
                focusedGlow = Glow(
                    elevationColor = accentColor.copy(alpha = 0.6f),
                    elevation = 22.dp,
                )
            ),
            colors = CardDefaults.colors(containerColor = AppleTvColors.DeepCharcoal),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (profile.avatarUrl.isNotBlank()) {
                    AsyncImage(
                        model = avatarReq,
                        contentDescription = profile.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(accentColor),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = profile.name.take(1).uppercase(),
                            style = MaterialTheme.typography.displayMedium,
                            color = AppleTvColors.PureWhite,
                        )
                    }
                }

                if (profile.hasPinLock && !manageMode) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(Color(0xCC06070B))
                            .border(1.dp, Color(0x55FFFFFF), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Lock,
                            contentDescription = "Perfil con PIN",
                            tint = AppleTvColors.PureWhite,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }

                if (manageMode) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0x9906070B)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Edit,
                            contentDescription = "Editar",
                            tint = AppleTvColors.PureWhite,
                            modifier = Modifier.size(32.dp),
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Text(
            text = profile.name,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Medium,
            ),
            color = if (isFocused) AppleTvColors.PureWhite else AppleTvColors.SecondaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )

        val subtitleBadge = buildString {
            if (profile.isKidsProfile) append("NIÑOS · ")
            append(if (profile.preferredAudioLang == "es-ES") "Castellano" else "Latino")
        }
        Text(
            text = subtitleBadge,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp),
            color = if (profile.isKidsProfile) Color(0xFF81D4FA) else AppleTvColors.MutedText,
        )
    }
}

@Composable
private fun AddProfileGateButton(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(28.dp)
    var isFocused by remember { mutableStateOf(false) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(148.dp)
            .alpha(if (enabled) 1f else 0.42f),
    ) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier
                .size(136.dp)
                .onFocusChanged { isFocused = it.isFocused },
            shape = ButtonDefaults.shape(shape = shape),
            scale = ButtonDefaults.scale(focusedScale = if (enabled) 1.1f else 1.0f),
            glow = ButtonDefaults.glow(
                focusedGlow = Glow(
                    elevationColor = if (enabled) AppleTvColors.FocusGlow else Color.Transparent,
                    elevation = if (enabled) 18.dp else 0.dp,
                )
            ),
            border = ButtonDefaults.border(
                border = Border(
                    border = BorderStroke(1.5.dp, Color(0x38FFFFFF)),
                    shape = shape,
                ),
                focusedBorder = Border(
                    border = BorderStroke(2.5.dp, AppleTvColors.PureWhite),
                    shape = shape,
                ),
                disabledBorder = Border(
                    border = BorderStroke(1.dp, Color(0x22FFFFFF)),
                    shape = shape,
                ),
            ),
            colors = ButtonDefaults.colors(
                containerColor = AppleTvColors.GlassSurface,
                contentColor = AppleTvColors.PureWhite,
                focusedContainerColor = AppleTvColors.PureWhite,
                focusedContentColor = AppleTvColors.Obsidian,
                disabledContainerColor = Color(0x6612151E),
                disabledContentColor = AppleTvColors.MutedText,
            ),
            contentPadding = PaddingValues(0.dp),
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (enabled) Icons.Rounded.Add else Icons.Rounded.Lock,
                    contentDescription = if (enabled) "Añadir perfil" else "Límite de 5 perfiles alcanzado",
                    modifier = Modifier.size(38.dp),
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Text(
            text = if (enabled) "Añadir perfil" else "Máx. 5 perfiles",
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = if (isFocused && enabled) FontWeight.Bold else FontWeight.Medium,
            ),
            color = if (!enabled) {
                AppleTvColors.MutedText
            } else if (isFocused) {
                AppleTvColors.PureWhite
            } else {
                AppleTvColors.SecondaryText
            },
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ProfileEditorOverlay(
    editorState: ProfileEditorState,
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
    onRequestPinSetup: (onPinReady: (String) -> Unit) -> Unit,
    onDelete: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val saveRequester = remember { FocusRequester() }

    var nameText by remember(editorState) {
        mutableStateOf(
            when (editorState) {
                is ProfileEditorState.Create -> editorState.suggestedName
                is ProfileEditorState.Edit -> editorState.profile.name
            }
        )
    }
    var selectedAvatarUrl by remember(editorState) {
        mutableStateOf(
            when (editorState) {
                is ProfileEditorState.Create -> editorState.initialAvatar.imageUrl
                is ProfileEditorState.Edit -> editorState.profile.avatarUrl
            }
        )
    }
    var selectedColorHex by remember(editorState) {
        mutableStateOf(
            when (editorState) {
                is ProfileEditorState.Create -> editorState.initialAvatar.accentColorHex
                is ProfileEditorState.Edit -> editorState.profile.avatarColorHex
            }
        )
    }
    var preferredAudioLang by remember(editorState) {
        mutableStateOf(
            when (editorState) {
                is ProfileEditorState.Create -> "es-LA"
                is ProfileEditorState.Edit -> editorState.profile.preferredAudioLang
            }
        )
    }
    var isKidsProfile by remember(editorState) {
        mutableStateOf(
            when (editorState) {
                is ProfileEditorState.Create -> false
                is ProfileEditorState.Edit -> editorState.profile.isKidsProfile
            }
        )
    }
    var pendingRawPin by remember(editorState) { mutableStateOf<String?>(null) }
    var existingPinHash by remember(editorState) {
        mutableStateOf(
            when (editorState) {
                is ProfileEditorState.Create -> null
                is ProfileEditorState.Edit -> editorState.profile.pinHash
            }
        )
    }

    var nameFieldFocused by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        runCatching { saveRequester.requestFocus() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xEB06070B))
            .padding(horizontal = 64.dp, vertical = 38.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xF5141722))
                .border(1.dp, Color(0x30FFFFFF), RoundedCornerShape(24.dp))
                .padding(32.dp),
            horizontalArrangement = Arrangement.spacedBy(36.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Left Column: Preview + Name + Audio Preference + Actions
            Column(
                modifier = Modifier.width(310.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = if (editorState is ProfileEditorState.Create) "Nuevo perfil" else "Editar perfil",
                    style = MaterialTheme.typography.headlineSmall,
                    color = AppleTvColors.PureWhite,
                )

                Spacer(modifier = Modifier.height(18.dp))

                Box(
                    modifier = Modifier
                        .size(112.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .border(2.5.dp, parseHexColor(selectedColorHex), RoundedCornerShape(24.dp))
                        .background(AppleTvColors.DeepCharcoal),
                ) {
                    if (selectedAvatarUrl.isNotBlank()) {
                        val previewReq = remember(selectedAvatarUrl) {
                            TvImageOptimizer.avatarRequest(context, selectedAvatarUrl, sizePx = 220)
                        }
                        AsyncImage(
                            model = previewReq,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Name Field
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF0B0D14))
                        .border(
                            width = if (nameFieldFocused) 2.dp else 1.dp,
                            color = if (nameFieldFocused) AppleTvColors.PureWhite else Color(0x33FFFFFF),
                            shape = RoundedCornerShape(12.dp),
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    BasicTextField(
                        value = nameText,
                        onValueChange = { if (it.length <= 18) nameText = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.titleMedium.copy(color = AppleTvColors.PureWhite),
                        cursorBrush = SolidColor(AppleTvColors.PureWhite),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { nameFieldFocused = it.isFocused },
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Preferred Spanish Region Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf("es-LA" to "Audio Latino", "es-ES" to "Castellano").forEach { (code, label) ->
                        val selected = preferredAudioLang == code
                        Button(
                            onClick = { preferredAudioLang = code },
                            modifier = Modifier.weight(1f),
                            shape = ButtonDefaults.shape(shape = RoundedCornerShape(50)),
                            colors = ButtonDefaults.colors(
                                containerColor = if (selected) Color(0x38FFFFFF) else Color(0xFF0B0D14),
                                contentColor = if (selected) AppleTvColors.PureWhite else AppleTvColors.SecondaryText,
                                focusedContainerColor = AppleTvColors.PureWhite,
                                focusedContentColor = AppleTvColors.Obsidian,
                            ),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelLarge.copy(fontSize = 12.sp),
                                maxLines = 1,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Kids Mode + 4-Digit PIN Controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { isKidsProfile = !isKidsProfile },
                        modifier = Modifier.weight(1f),
                        shape = ButtonDefaults.shape(shape = RoundedCornerShape(50)),
                        colors = ButtonDefaults.colors(
                            containerColor = if (isKidsProfile) Color(0x3881D4FA) else Color(0xFF0B0D14),
                            contentColor = if (isKidsProfile) Color(0xFF81D4FA) else AppleTvColors.SecondaryText,
                            focusedContainerColor = AppleTvColors.PureWhite,
                            focusedContentColor = AppleTvColors.Obsidian,
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = if (isKidsProfile) "✓ Modo Niños" else "Modo Niños",
                            style = MaterialTheme.typography.labelLarge.copy(fontSize = 11.5.sp),
                            maxLines = 1,
                        )
                    }

                    val hasConfiguredPin = pendingRawPin != null || !existingPinHash.isNullOrBlank()
                    Button(
                        onClick = {
                            if (hasConfiguredPin) {
                                pendingRawPin = null
                                existingPinHash = null
                            } else {
                                onRequestPinSetup { newPin ->
                                    pendingRawPin = newPin
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = ButtonDefaults.shape(shape = RoundedCornerShape(50)),
                        colors = ButtonDefaults.colors(
                            containerColor = if (hasConfiguredPin) Color(0x38FFFFFF) else Color(0xFF0B0D14),
                            contentColor = if (hasConfiguredPin) AppleTvColors.PureWhite else AppleTvColors.SecondaryText,
                            focusedContainerColor = AppleTvColors.PureWhite,
                            focusedContentColor = AppleTvColors.Obsidian,
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = if (hasConfiguredPin) "Quitar PIN" else "PIN 4 dígitos",
                            style = MaterialTheme.typography.labelLarge.copy(fontSize = 11.5.sp),
                            maxLines = 1,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = {
                            when (editorState) {
                                is ProfileEditorState.Create -> {
                                    onSaveCreate(
                                        nameText,
                                        NuvioAvatarAsset(
                                            id = "selected",
                                            label = nameText,
                                            imageUrl = selectedAvatarUrl,
                                            accentColorHex = selectedColorHex,
                                        ),
                                        preferredAudioLang,
                                        isKidsProfile,
                                        pendingRawPin,
                                    )
                                }
                                is ProfileEditorState.Edit -> {
                                    val resolvedHash = pendingRawPin?.let { ParentalPinManager.hashPin(it) }
                                        ?: existingPinHash
                                    onSaveEdit(
                                        editorState.profile.copy(
                                            name = nameText,
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
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(saveRequester),
                        shape = ButtonDefaults.shape(shape = RoundedCornerShape(50)),
                        colors = ButtonDefaults.colors(
                            containerColor = AppleTvColors.PureWhite,
                            contentColor = AppleTvColors.Obsidian,
                            focusedContainerColor = AppleTvColors.PureWhite,
                            focusedContentColor = AppleTvColors.Obsidian,
                        ),
                        contentPadding = PaddingValues(vertical = 11.dp),
                    ) {
                        Text("Guardar", style = MaterialTheme.typography.labelLarge)
                    }

                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        shape = ButtonDefaults.shape(shape = RoundedCornerShape(50)),
                        colors = ButtonDefaults.colors(
                            containerColor = AppleTvColors.GlassSurface,
                            contentColor = AppleTvColors.PureWhite,
                            focusedContainerColor = AppleTvColors.PureWhite,
                            focusedContentColor = AppleTvColors.Obsidian,
                        ),
                        contentPadding = PaddingValues(vertical = 11.dp),
                    ) {
                        Text("Cancelar", style = MaterialTheme.typography.labelLarge)
                    }
                }

                if (canDelete && editorState is ProfileEditorState.Edit) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = { onDelete(editorState.profile.id) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = ButtonDefaults.shape(shape = RoundedCornerShape(50)),
                        colors = ButtonDefaults.colors(
                            containerColor = Color(0x33E53935),
                            contentColor = Color(0xFFFF8A80),
                            focusedContainerColor = Color(0xFFE53935),
                            focusedContentColor = AppleTvColors.PureWhite,
                        ),
                        contentPadding = PaddingValues(vertical = 9.dp),
                    ) {
                        Icon(Icons.Rounded.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Eliminar perfil", style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp))
                    }
                }
            }

            // Right Column: Nuvio Avatar Picker Grid
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Elige un avatar",
                    style = MaterialTheme.typography.headlineSmall.copy(fontSize = 18.sp),
                    color = AppleTvColors.PureWhite,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Selecciona un ícono gráfico para tu perfil",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppleTvColors.MutedText,
                )
                Spacer(modifier = Modifier.height(14.dp))

                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 86.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = PaddingValues(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(290.dp),
                ) {
                    gridItems(
                        items = nuvioAvatars,
                        key = { it.id },
                    ) { avatar ->
                        val isChosen = avatar.imageUrl == selectedAvatarUrl
                        val itemShape = RoundedCornerShape(18.dp)
                        val avatarReq = remember(avatar.imageUrl) {
                            TvImageOptimizer.avatarRequest(context, avatar.imageUrl, sizePx = 180)
                        }
                        Card(
                            onClick = {
                                selectedAvatarUrl = avatar.imageUrl
                                selectedColorHex = avatar.accentColorHex
                            },
                            modifier = Modifier.size(86.dp),
                            shape = CardDefaults.shape(shape = itemShape),
                            scale = CardDefaults.scale(focusedScale = 1.1f),
                            border = CardDefaults.border(
                                border = Border(
                                    border = BorderStroke(
                                        width = if (isChosen) 2.5.dp else 1.dp,
                                        color = if (isChosen) AppleTvColors.PureWhite else Color(0x28FFFFFF),
                                    ),
                                    shape = itemShape,
                                ),
                                focusedBorder = Border(
                                    border = BorderStroke(2.5.dp, AppleTvColors.PureWhite),
                                    shape = itemShape,
                                ),
                            ),
                            glow = CardDefaults.glow(
                                focusedGlow = Glow(
                                    elevationColor = AppleTvColors.FocusGlow,
                                    elevation = 14.dp,
                                )
                            ),
                        ) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                AsyncImage(
                                    model = avatarReq,
                                    contentDescription = avatar.label,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                                if (isChosen) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd)
                                            .padding(5.dp)
                                            .size(20.dp)
                                            .clip(CircleShape)
                                            .background(AppleTvColors.PureWhite),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Check,
                                            contentDescription = null,
                                            tint = AppleTvColors.Obsidian,
                                            modifier = Modifier.size(13.dp),
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

internal fun parseHexColor(hex: String): Color {
    return runCatching {
        Color(android.graphics.Color.parseColor(hex))
    }.getOrDefault(AppleTvColors.AccentBlue)
}
