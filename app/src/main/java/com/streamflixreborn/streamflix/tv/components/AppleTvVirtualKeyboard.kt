@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)

package com.streamflixreborn.streamflix.tv.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.SpaceBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
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
import java.util.Locale

enum class AppleTvKeyboardMode {
    SEARCH,
    URL,
}

/**
 * Native D-Pad Optimized In-App QWERTY Virtual Keyboard for Android TV.
 * - Instant `1.15x` scale and crisp luminous glow on focus (`ButtonDefaults.scale(focusedScale = 1.15f)`).
 * - Seamless right-edge D-Pad exit (`rightExitFocusRequester`) to jump directly into Live-Search posters.
 * - Dual usability: supports both Spanish Live-Search (`SEARCH`) and manual Stremio `manifest.json` URL input (`URL`).
 */
@Composable
fun AppleTvVirtualKeyboard(
    value: String,
    onValueChange: (String) -> Unit,
    mode: AppleTvKeyboardMode = AppleTvKeyboardMode.SEARCH,
    initialFocusRequester: FocusRequester? = null,
    rightExitFocusRequester: FocusRequester? = null,
    canExitRight: Boolean = false,
    onVoiceSearchClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var showNumbersAndSymbols by remember { mutableStateOf(false) }
    var uppercaseMode by remember(mode) {
        mutableStateOf(mode == AppleTvKeyboardMode.SEARCH)
    }

    val qwertyRows = remember(mode, showNumbersAndSymbols, uppercaseMode) {
        if (showNumbersAndSymbols) {
            if (mode == AppleTvKeyboardMode.URL) {
                listOf(
                    listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
                    listOf(":", "/", ".", "-", "_", "?", "=", "&", "%", "#"),
                    listOf("+", "@", "~", "http://", "https://", ".com", ".io", ".org"),
                )
            } else {
                listOf(
                    listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
                    listOf("Á", "É", "Í", "Ó", "Ú", "Ü", "Ñ", "-", ":", "."),
                    listOf("&", "'", "!", "?", "/", "+", "(", ")"),
                )
            }
        } else {
            val baseRows = if (mode == AppleTvKeyboardMode.URL) {
                listOf(
                    listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
                    listOf("a", "s", "d", "f", "g", "h", "j", "k", "l", "-"),
                    listOf("z", "x", "c", "v", "b", "n", "m", ".", "/", ":"),
                )
            } else {
                listOf(
                    listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
                    listOf("a", "s", "d", "f", "g", "h", "j", "k", "l", "ñ"),
                    listOf("z", "x", "c", "v", "b", "n", "m", "1", "2", "3"),
                )
            }
            baseRows.map { row ->
                row.map { token ->
                    if (uppercaseMode && token.length == 1) {
                        token.uppercase(Locale("es"))
                    } else if (!uppercaseMode && token.length == 1) {
                        token.lowercase(Locale("es"))
                    } else {
                        token
                    }
                }
            }
        }
    }

    fun appendToken(token: String) {
        val next = if (mode == AppleTvKeyboardMode.SEARCH) {
            // Keep search query natural case (lowercase after space or continuous typing works cleanly with search APIs)
            value + token.lowercase(Locale("es"))
        } else {
            value + token
        }
        onValueChange(next)
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(7.dp),
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xD9121520))
            .border(1.dp, Color(0x28FFFFFF), RoundedCornerShape(20.dp))
            .padding(horizontal = 14.dp, vertical = 14.dp),
    ) {
        // URL Quick-Insert Shortcuts Row (only in URL mode for fast manifest entry)
        if (mode == AppleTvKeyboardMode.URL) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                val urlShortcuts = listOf("https://", ".com", ".strem.io", "/manifest.json")
                urlShortcuts.forEachIndexed { idx, token ->
                    val isRightEdge = idx == urlShortcuts.lastIndex
                    DpadKeyButton(
                        label = token,
                        onClick = {
                            val cleanedBase = if (token == "https://" && value.startsWith("https://")) {
                                value
                            } else if (token == "/manifest.json" && value.endsWith("/manifest.json")) {
                                value
                            } else {
                                value + token
                            }
                            onValueChange(cleanedBase)
                        },
                        fontSizeSp = 11f,
                        height = 34.dp,
                        rightExitFocusRequester = if (isRightEdge && canExitRight) rightExitFocusRequester else null,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // Main QWERTY / Symbols Matrix
        qwertyRows.forEachIndexed { rowIndex, rowTokens ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                rowTokens.forEachIndexed { colIndex, keyLabel ->
                    val isInitialFocusKey = rowIndex == 0 && colIndex == 0 && initialFocusRequester != null
                    val isRightmostKey = colIndex == rowTokens.lastIndex
                    DpadKeyButton(
                        label = keyLabel,
                        onClick = { appendToken(keyLabel) },
                        height = 38.dp,
                        fontSizeSp = if (keyLabel.length > 2) 10.5f else 14f,
                        focusRequester = if (isInitialFocusKey) initialFocusRequester else null,
                        rightExitFocusRequester = if (isRightmostKey && canExitRight) rightExitFocusRequester else null,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(2.dp))

        // Bottom Utility / Action Row (123/ABC, Case/Voice, Space, Backspace, Clear)
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            // 123 / ABC Toggle
            DpadKeyButton(
                label = if (showNumbersAndSymbols) "ABC" else "123",
                highlighted = showNumbersAndSymbols,
                onClick = { showNumbersAndSymbols = !showNumbersAndSymbols },
                height = 40.dp,
                fontSizeSp = 12f,
                modifier = Modifier.weight(1.1f),
            )

            if (mode == AppleTvKeyboardMode.URL) {
                DpadKeyButton(
                    label = if (uppercaseMode) "MAYÚS" else "minús",
                    highlighted = uppercaseMode,
                    onClick = { uppercaseMode = !uppercaseMode },
                    height = 40.dp,
                    fontSizeSp = 11.5f,
                    modifier = Modifier.weight(1.2f),
                )
            } else if (onVoiceSearchClick != null) {
                DpadKeyButton(
                    label = "Voz",
                    icon = Icons.Rounded.Mic,
                    onClick = onVoiceSearchClick,
                    height = 40.dp,
                    fontSizeSp = 12f,
                    modifier = Modifier.weight(1.25f),
                )
            }

            if (mode == AppleTvKeyboardMode.SEARCH) {
                DpadKeyButton(
                    label = "Espacio",
                    icon = Icons.Rounded.SpaceBar,
                    onClick = {
                        if (value.isNotEmpty() && !value.endsWith(" ")) {
                            onValueChange("$value ")
                        }
                    },
                    height = 40.dp,
                    fontSizeSp = 12f,
                    modifier = Modifier.weight(2.1f),
                )
            }

            // Backspace
            DpadKeyButton(
                label = "",
                icon = Icons.AutoMirrored.Rounded.Backspace,
                contentDescription = "Borrar carácter",
                onClick = {
                    if (value.isNotEmpty()) {
                        onValueChange(value.dropLast(1))
                    }
                },
                height = 40.dp,
                modifier = Modifier.weight(1.05f),
            )

            // Clear all (rightmost key in utility row -> jumps right to results!)
            DpadKeyButton(
                label = "",
                icon = Icons.Rounded.DeleteSweep,
                contentDescription = "Limpiar todo",
                onClick = {
                    if (value.isNotEmpty()) {
                        onValueChange("")
                    }
                },
                height = 40.dp,
                rightExitFocusRequester = if (canExitRight) rightExitFocusRequester else null,
                modifier = Modifier.weight(1.05f),
            )
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun DpadKeyButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    contentDescription: String? = null,
    highlighted: Boolean = false,
    height: Dp = 38.dp,
    fontSizeSp: Float = 14f,
    focusRequester: FocusRequester? = null,
    rightExitFocusRequester: FocusRequester? = null,
) {
    val keyShape = RoundedCornerShape(10.dp)

    val resolvedModifier = modifier
        .height(height)
        .let { m -> if (focusRequester != null) m.focusRequester(focusRequester) else m }
        .focusProperties {
            if (rightExitFocusRequester != null) {
                right = rightExitFocusRequester
            }
        }

    Button(
        onClick = onClick,
        modifier = resolvedModifier,
        shape = ButtonDefaults.shape(shape = keyShape),
        // Strict 1.15x scale + instant glow on D-Pad focus per specification
        scale = ButtonDefaults.scale(
            scale = 1.0f,
            focusedScale = 1.15f,
            pressedScale = 1.06f,
        ),
        glow = ButtonDefaults.glow(
            focusedGlow = Glow(
                elevationColor = AppleTvColors.FocusGlow,
                elevation = 16.dp,
            )
        ),
        border = ButtonDefaults.border(
            border = Border(
                border = BorderStroke(
                    width = 1.dp,
                    color = if (highlighted) AppleTvColors.PureWhite else Color(0x26FFFFFF),
                ),
                shape = keyShape,
            ),
            focusedBorder = Border(
                border = BorderStroke(2.dp, AppleTvColors.PureWhite),
                shape = keyShape,
            ),
        ),
        colors = ButtonDefaults.colors(
            containerColor = if (highlighted) Color(0x38FFFFFF) else Color(0xFF1A1E2C),
            contentColor = AppleTvColors.PureWhite,
            focusedContainerColor = AppleTvColors.PureWhite,
            focusedContentColor = AppleTvColors.Obsidian,
        ),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription ?: label,
                    modifier = Modifier.size(16.dp),
                )
                if (label.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(4.dp))
                }
            }
            if (label.isNotEmpty()) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontSize = fontSizeSp.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    maxLines = 1,
                )
            }
        }
    }
}
