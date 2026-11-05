@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.streamflixreborn.streamflix.tv.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
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
import coil.compose.AsyncImage
import com.streamflixreborn.streamflix.profiles.TvUserProfileEntity
import com.streamflixreborn.streamflix.tv.model.TvTopSection
import com.streamflixreborn.streamflix.tv.theme.AppleTvColors
import com.streamflixreborn.streamflix.utils.TvImageOptimizer

@Composable
fun AppleTvTopNav(
    activeSection: TvTopSection,
    activeProfile: TvUserProfileEntity?,
    onSelectSection: (TvTopSection) -> Unit,
    onSwitchProfile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 48.dp, vertical = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // Minimalist Apple TV-inspired brand mark
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(AppleTvColors.PureWhite),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "Streamflix TV",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.4.sp,
                ),
                color = AppleTvColors.PureWhite,
            )
        }

        // Floating tvOS frosted-glass pill switcher
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Color(0xAD141720))
                .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(50))
                .padding(horizontal = 6.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TvTopSection.entries.forEach { section ->
                val isSelected = section == activeSection
                Button(
                    onClick = { onSelectSection(section) },
                    shape = ButtonDefaults.shape(shape = RoundedCornerShape(50)),
                    scale = ButtonDefaults.scale(focusedScale = 1.06f),
                    glow = ButtonDefaults.glow(
                        focusedGlow = Glow(
                            elevationColor = Color(0x66FFFFFF),
                            elevation = 12.dp,
                        )
                    ),
                    border = ButtonDefaults.border(
                        focusedBorder = Border(
                            border = BorderStroke(1.5.dp, AppleTvColors.PureWhite),
                            shape = RoundedCornerShape(50),
                        )
                    ),
                    colors = ButtonDefaults.colors(
                        containerColor = if (isSelected) Color(0x30FFFFFF) else Color.Transparent,
                        contentColor = if (isSelected) AppleTvColors.PureWhite else AppleTvColors.SecondaryText,
                        focusedContainerColor = AppleTvColors.PureWhite,
                        focusedContentColor = AppleTvColors.Obsidian,
                    ),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
                ) {
                    if (section == TvTopSection.SEARCH) {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = section.title,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    } else if (section == TvTopSection.ADDONS) {
                        Icon(
                            imageVector = Icons.Rounded.Extension,
                            contentDescription = section.title,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    } else if (section == TvTopSection.SETTINGS) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = section.title,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        text = section.title,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }

        // Active Profile Pill (switches back to Profile Gate)
        Button(
            onClick = onSwitchProfile,
            shape = ButtonDefaults.shape(shape = RoundedCornerShape(50)),
            scale = ButtonDefaults.scale(focusedScale = 1.06f),
            glow = ButtonDefaults.glow(
                focusedGlow = Glow(
                    elevationColor = Color(0x66FFFFFF),
                    elevation = 12.dp,
                )
            ),
            border = ButtonDefaults.border(
                border = Border(
                    border = BorderStroke(1.dp, Color(0x2EFFFFFF)),
                    shape = RoundedCornerShape(50),
                ),
                focusedBorder = Border(
                    border = BorderStroke(2.dp, AppleTvColors.PureWhite),
                    shape = RoundedCornerShape(50),
                ),
            ),
            colors = ButtonDefaults.colors(
                containerColor = Color(0x99161922),
                contentColor = AppleTvColors.PureWhite,
                focusedContainerColor = AppleTvColors.PureWhite,
                focusedContentColor = AppleTvColors.Obsidian,
            ),
            contentPadding = PaddingValues(start = 6.dp, end = 14.dp, top = 5.dp, bottom = 5.dp),
        ) {
            if (!activeProfile?.avatarUrl.isNullOrBlank()) {
                val req = remember(activeProfile?.avatarUrl) {
                    TvImageOptimizer.avatarRequest(context, activeProfile?.avatarUrl, sizePx = 96)
                }
                AsyncImage(
                    model = req,
                    contentDescription = activeProfile?.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape),
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = activeProfile?.name ?: "Perfil",
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
