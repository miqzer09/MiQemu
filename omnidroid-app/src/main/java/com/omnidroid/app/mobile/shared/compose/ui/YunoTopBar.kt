package com.omnidroid.app.mobile.shared.compose.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omnidroid.R
import com.omnidroid.lib.library.db.entity.Game
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun YunoTopBar(
    modifier: Modifier = Modifier,
    focusedGame: Game? = null,
    customTitle: String? = null,
    showBackButton: Boolean = false,
    onBackClick: () -> Unit = {},
    searchQuery: String = "",
    searchActive: Boolean = false,
    showSearchAndSettings: Boolean = true,
    onSearchQueryChange: (String) -> Unit = {},
    onClearSearch: () -> Unit = {},
    onToggleSearch: () -> Unit = {},
    onMenuClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    onProfileClick: () -> Unit = {},
    onJoystickClick: () -> Unit = {},
    gamepadConnected: Boolean = false,
) {
    val displayTitle = customTitle
        ?: focusedGame?.customName?.takeIf { it.isNotBlank() }
        ?: focusedGame?.title
        ?: "Home"

    val searchFocusRequester = remember { FocusRequester() }

    LaunchedEffect(searchActive) {
        if (searchActive) {
            searchFocusRequester.requestFocus()
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 16.dp, start = 20.dp, end = 20.dp, bottom = 4.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Back button or hamburger menu button (larger 28dp icon inside 42dp button)
            if (showBackButton) {
                IconButton(
                    onClick = { YunoSoundManager.playEnter(); onBackClick() },
                    modifier = Modifier.size(42.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp),
                    )
                }
            } else {
                IconButton(
                    onClick = { YunoSoundManager.playOpenRail(); onMenuClick() },
                    modifier = Modifier.size(42.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Menu,
                        contentDescription = "Menu",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            // MiQemu stylized logo & dynamic uppercase game title (or Search box)
            if (searchActive && showSearchAndSettings) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp)
                        .background(Color(0xFF2B2830), RoundedCornerShape(21.dp))
                        .border(1.dp, YunoPurplePrimary, RoundedCornerShape(21.dp))
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = null,
                        tint = YunoPurplePrimary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (searchQuery.isEmpty()) {
                            Text(
                                text = "Search games...",
                                color = Color.White.copy(alpha = 0.45f),
                                fontSize = 15.sp,
                            )
                        }
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = onSearchQueryChange,
                            singleLine = true,
                            textStyle = TextStyle(
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Normal,
                            ),
                            cursorBrush = SolidColor(YunoPurplePrimary),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(searchFocusRequester),
                        )
                    }
                    if (searchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = onClearSearch,
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Clear",
                                tint = Color.White.copy(alpha = 0.7f),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "MiQemu",
                        color = Color.White,
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.SansSerif,
                        letterSpacing = 0.5.sp,
                    )
                    Text(
                        text = " - ",
                        color = Color.White.copy(alpha = 0.65f),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Normal,
                    )
                    AnimatedContent(
                        targetState = displayTitle.uppercase(),
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "miqemuTitle",
                        modifier = Modifier.weight(1f, fill = false),
                    ) { titleText ->
                        Text(
                            text = titleText,
                            color = Color.White.copy(alpha = 0.95f),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            // Upper right controls & indicators: Time, Search, Joystick, Gear, Profile icon
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // 1. Time (Clock) - white color
                val clockTime = rememberCurrentTime()
                Text(
                    text = clockTime,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.SansSerif,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )

                // 2. Search button
                if (showSearchAndSettings) {
                    IconButton(
                        onClick = { YunoSoundManager.playEnter(); onToggleSearch() },
                        modifier = Modifier.size(42.dp),
                    ) {
                        Icon(
                            imageVector = if (searchActive) Icons.Filled.Close else Icons.Filled.Search,
                            contentDescription = if (searchActive) "Close Search" else "Search",
                            tint = if (searchActive) YunoPurplePrimary else Color.White,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }

                // 3. Joystick (gamepad icon) - white color
                IconButton(
                    onClick = { YunoSoundManager.playOpenRail(); onJoystickClick() },
                    modifier = Modifier.size(42.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.SportsEsports,
                        contentDescription = "Gamepad",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp),
                    )
                }

                // 4. Gear (Settings button) - white color
                if (showSearchAndSettings) {
                    IconButton(
                        onClick = { YunoSoundManager.playOpenRail(); onSettingsClick() },
                        modifier = Modifier.size(42.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "Settings",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }

                // 5. Profile robot icon - keeps theme purple color (YunoPurplePrimary)
                IconButton(
                    onClick = { YunoSoundManager.playOpenRail(); onProfileClick() },
                    modifier = Modifier.size(42.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_profile_robot),
                        contentDescription = stringResource(R.string.title_profile),
                        tint = YunoPurplePrimary,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun rememberCurrentTime(): String {
    var time by remember { mutableStateOf(formatStatusTime()) }
    LaunchedEffect(Unit) {
        while (true) {
            time = formatStatusTime()
            delay(15_000)
        }
    }
    return time
}

private fun formatStatusTime(): String {
    return SimpleDateFormat("h:mm a", Locale.getDefault())
        .format(Date())
        .uppercase(Locale.getDefault())
}
