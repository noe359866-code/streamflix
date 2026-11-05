@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.streamflixreborn.streamflix.tv.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme

object AppleTvColors {
    val Obsidian = Color(0xFF06070B)
    val DeepCharcoal = Color(0xFF0E1017)
    val GlassSurface = Color(0xCC181B24)
    val GlassElevated = Color(0xE6222633)
    val PureWhite = Color(0xFFF6F7FB)
    val SecondaryText = Color(0xFFB5BAC9)
    val MutedText = Color(0xFF7D8396)
    val AccentSilver = Color(0xFFFFFFFF)
    val AccentBlue = Color(0xFF3E8BFF)
    val FocusBorder = Color(0xF5FFFFFF)
    val FocusGlow = Color(0x88FFFFFF)
    val SubtleDivider = Color(0x29FFFFFF)
    val RatingGold = Color(0xFFF5C518)
}

private val AppleTvColorScheme = darkColorScheme(
    primary = AppleTvColors.PureWhite,
    onPrimary = AppleTvColors.Obsidian,
    primaryContainer = AppleTvColors.GlassElevated,
    onPrimaryContainer = AppleTvColors.PureWhite,
    secondary = AppleTvColors.AccentBlue,
    onSecondary = AppleTvColors.PureWhite,
    background = AppleTvColors.Obsidian,
    onBackground = AppleTvColors.PureWhite,
    surface = AppleTvColors.DeepCharcoal,
    onSurface = AppleTvColors.PureWhite,
    surfaceVariant = AppleTvColors.GlassSurface,
    onSurfaceVariant = AppleTvColors.SecondaryText,
)

private val AppleTvTypography = Typography(
    displayMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 40.sp,
        lineHeight = 46.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.3).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 19.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.2.sp,
    ),
)

@Composable
fun AppleTvTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = AppleTvColorScheme,
        typography = AppleTvTypography,
        content = content,
    )
}
