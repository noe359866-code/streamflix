@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.streamflixreborn.streamflix.tv.components

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
import com.streamflixreborn.streamflix.tv.theme.AppleTvColors

sealed interface PinDialogMode {
    data class Verify(
        val title: String = "Ingresa el PIN de 4 dígitos",
        val subtitle: String = "Este acceso está protegido por Control Parental.",
        val verify: (String) -> Boolean,
        val onVerified: () -> Unit,
    ) : PinDialogMode

    data class Setup(
        val title: String = "Configurar PIN de 4 dígitos",
        val subtitle: String = "Elige un código numérico para proteger el perfil o los ajustes.",
        val onPinConfirmed: (String) -> Unit,
    ) : PinDialogMode
}

/**
 * Apple TV frosted 4-digit numeric PIN Lock modal optimized for D-Pad and remote number buttons.
 */
@Composable
fun AppleTvPinDialog(
    mode: PinDialogMode,
    onDismiss: () -> Unit,
) {
    val firstDigitRequester = remember { FocusRequester() }
    var enteredDigits by remember(mode) { mutableStateOf("") }
    var firstSetupStagePin by remember(mode) { mutableStateOf<String?>(null) }
    var errorMessage by remember(mode) { mutableStateOf<String?>(null) }

    BackHandler {
        onDismiss()
    }

    LaunchedEffect(mode) {
        runCatching { firstDigitRequester.requestFocus() }
    }

    fun submitDigit(digit: Char) {
        if (enteredDigits.length >= 4) return
        errorMessage = null
        val updated = enteredDigits + digit
        enteredDigits = updated

        if (updated.length == 4) {
            when (mode) {
                is PinDialogMode.Verify -> {
                    if (mode.verify(updated)) {
                        mode.onVerified()
                    } else {
                        enteredDigits = ""
                        errorMessage = "PIN incorrecto. Inténtalo nuevamente."
                    }
                }

                is PinDialogMode.Setup -> {
                    val stageOne = firstSetupStagePin
                    if (stageOne == null) {
                        firstSetupStagePin = updated
                        enteredDigits = ""
                    } else if (stageOne == updated) {
                        mode.onPinConfirmed(updated)
                    } else {
                        firstSetupStagePin = null
                        enteredDigits = ""
                        errorMessage = "Los códigos no coinciden. Vuelve a escribir el nuevo PIN."
                    }
                }
            }
        }
    }

    val headerTitle = when (mode) {
        is PinDialogMode.Verify -> mode.title
        is PinDialogMode.Setup -> {
            if (firstSetupStagePin == null) mode.title else "Confirma tu nuevo PIN"
        }
    }

    val headerSubtitle = when (mode) {
        is PinDialogMode.Verify -> mode.subtitle
        is PinDialogMode.Setup -> {
            if (firstSetupStagePin == null) {
                mode.subtitle
            } else {
                "Ingresa los mismos 4 dígitos para confirmar."
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xEB06070B))
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val hwDigit = mapHardwareDigitKey(ev.key)
                if (hwDigit != null) {
                    submitDigit(hwDigit)
                    true
                } else if (ev.key == Key.Backspace || ev.key == Key.Delete) {
                    if (enteredDigits.isNotEmpty()) {
                        enteredDigits = enteredDigits.dropLast(1)
                    }
                    true
                } else {
                    false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .widthIn(max = 440.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(Color(0xF5141722))
                .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(28.dp))
                .padding(horizontal = 36.dp, vertical = 30.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color(0x26FFFFFF)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Lock,
                    contentDescription = null,
                    tint = AppleTvColors.PureWhite,
                    modifier = Modifier.size(24.dp),
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = headerTitle,
                style = MaterialTheme.typography.headlineSmall,
                color = AppleTvColors.PureWhite,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = headerSubtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = AppleTvColors.SecondaryText,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(22.dp))

            // 4 Masked PIN Dots
            Row(
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(4) { idx ->
                    val filled = idx < enteredDigits.length
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(if (filled) AppleTvColors.PureWhite else Color(0x22FFFFFF))
                            .border(
                                width = 1.5.dp,
                                color = if (filled) AppleTvColors.PureWhite else Color(0x55FFFFFF),
                                shape = CircleShape,
                            )
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (errorMessage != null) {
                Text(
                    text = errorMessage.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.5.sp),
                    color = Color(0xFFFF8A80),
                    textAlign = TextAlign.Center,
                )
            } else {
                Spacer(modifier = Modifier.height(16.dp))
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 3x4 Numeric Keypad
            val keypadRows = listOf(
                listOf("1", "2", "3"),
                listOf("4", "5", "6"),
                listOf("7", "8", "9"),
                listOf("CLR", "0", "DEL"),
            )

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                keypadRows.forEachIndexed { rIdx, row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEachIndexed { cIdx, token ->
                            val isFirst = rIdx == 0 && cIdx == 0
                            Button(
                                onClick = {
                                    when (token) {
                                        "CLR" -> {
                                            enteredDigits = ""
                                            errorMessage = null
                                        }
                                        "DEL" -> {
                                            if (enteredDigits.isNotEmpty()) {
                                                enteredDigits = enteredDigits.dropLast(1)
                                            }
                                        }
                                        else -> submitDigit(token.first())
                                    }
                                },
                                modifier = Modifier
                                    .width(82.dp)
                                    .height(48.dp)
                                    .let { m -> if (isFirst) m.focusRequester(firstDigitRequester) else m },
                                shape = ButtonDefaults.shape(shape = RoundedCornerShape(14.dp)),
                                scale = ButtonDefaults.scale(focusedScale = 1.15f),
                                glow = ButtonDefaults.glow(
                                    focusedGlow = Glow(
                                        elevationColor = AppleTvColors.FocusGlow,
                                        elevation = 16.dp,
                                    )
                                ),
                                border = ButtonDefaults.border(
                                    border = Border(
                                        border = BorderStroke(1.dp, Color(0x28FFFFFF)),
                                        shape = RoundedCornerShape(14.dp),
                                    ),
                                    focusedBorder = Border(
                                        border = BorderStroke(2.dp, AppleTvColors.PureWhite),
                                        shape = RoundedCornerShape(14.dp),
                                    ),
                                ),
                                colors = ButtonDefaults.colors(
                                    containerColor = Color(0xFF1A1E2C),
                                    contentColor = AppleTvColors.PureWhite,
                                    focusedContainerColor = AppleTvColors.PureWhite,
                                    focusedContentColor = AppleTvColors.Obsidian,
                                ),
                                contentPadding = PaddingValues(0.dp),
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    when (token) {
                                        "DEL" -> Icon(
                                            imageVector = Icons.AutoMirrored.Rounded.Backspace,
                                            contentDescription = "Borrar",
                                            modifier = Modifier.size(19.dp),
                                        )
                                        "CLR" -> Text(
                                            text = "Limpiar",
                                            style = MaterialTheme.typography.labelLarge.copy(fontSize = 11.5.sp),
                                        )
                                        else -> Text(
                                            text = token,
                                            style = MaterialTheme.typography.headlineSmall.copy(
                                                fontSize = 20.sp,
                                                fontWeight = FontWeight.Bold,
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = onDismiss,
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

private fun mapHardwareDigitKey(key: Key): Char? {
    return when (key) {
        Key.Zero, Key.NumPad0 -> '0'
        Key.One, Key.NumPad1 -> '1'
        Key.Two, Key.NumPad2 -> '2'
        Key.Three, Key.NumPad3 -> '3'
        Key.Four, Key.NumPad4 -> '4'
        Key.Five, Key.NumPad5 -> '5'
        Key.Six, Key.NumPad6 -> '6'
        Key.Seven, Key.NumPad7 -> '7'
        Key.Eight, Key.NumPad8 -> '8'
        Key.Nine, Key.NumPad9 -> '9'
        else -> null
    }
}
