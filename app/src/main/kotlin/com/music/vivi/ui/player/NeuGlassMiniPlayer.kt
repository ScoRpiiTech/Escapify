/**
 * Escapify Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.ui.player

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import coil3.compose.AsyncImage
import com.music.vivi.LocalDatabase
import com.music.vivi.LocalListenTogetherManager
import com.music.vivi.LocalPlayerConnection
import com.music.vivi.R
import com.music.vivi.constants.*
import com.music.vivi.models.MediaMetadata
import com.music.vivi.ui.screens.settings.DarkMode
import com.music.vivi.utils.rememberEnumPreference
import com.music.vivi.utils.rememberPreference
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

@Composable
fun NeuGlassMiniPlayer(
    progressState: ProgressState,
    modifier: Modifier = Modifier
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val database = LocalDatabase.current
    val haptic = LocalHapticFeedback.current

    // Theme settings
    val pureBlack by rememberPreference(PureBlackMiniPlayerKey, defaultValue = false)
    val isSystemInDarkTheme = isSystemInDarkTheme()
    val darkTheme by rememberEnumPreference(DarkModeKey, defaultValue = DarkMode.AUTO)
    val useDarkTheme = remember(darkTheme, isSystemInDarkTheme) {
        if (darkTheme == DarkMode.AUTO) isSystemInDarkTheme else darkTheme == DarkMode.ON
    }

    // Player states
    val playbackState by playerConnection.playbackState.collectAsState()
    val isPlaying by playerConnection.isPlaying.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val canSkipNext by playerConnection.canSkipNext.collectAsState()
    val canSkipPrevious by playerConnection.canSkipPrevious.collectAsState()

    // Swipe settings
    val swipeSensitivity by rememberPreference(SwipeSensitivityKey, 0.73f)
    val swipeThumbnailPref by rememberPreference(SwipeThumbnailKey, true)

    val listenTogetherManager = LocalListenTogetherManager.current
    val isListenTogetherGuest = listenTogetherManager?.let { it.isInRoom && !it.isHost } ?: false
    val swipeThumbnail = swipeThumbnailPref && !isListenTogetherGuest

    val layoutDirection = LocalLayoutDirection.current
    val coroutineScope = rememberCoroutineScope()

    // Swipe animation state
    val offsetXAnimatable = remember { Animatable(0f) }
    var dragStartTime by remember { mutableLongStateOf(0L) }
    var totalDragDistance by remember { mutableFloatStateOf(0f) }

    val animationSpec = remember {
        spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow)
    }

    val currentSong by playerConnection.currentSong.collectAsState(initial = null)

    // Palette accents
    val primaryColor = MaterialTheme.colorScheme.primary
    val surfaceBase = if (pureBlack && useDarkTheme) {
        Color(0xFF000000)
    } else if (useDarkTheme) {
        Color(0xFF16181C).copy(alpha = 0.92f)
    } else {
        Color(0xFFF0F3F8).copy(alpha = 0.94f)
    }

    val rimHighlight = if (useDarkTheme) {
        Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.22f),
                Color.White.copy(alpha = 0.05f),
                Color.Black.copy(alpha = 0.40f)
            )
        )
    } else {
        Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.85f),
                Color.White.copy(alpha = 0.30f),
                Color.Black.copy(alpha = 0.12f)
            )
        )
    }

    val textColor = if (useDarkTheme) Color(0xFFF1F5F9) else Color(0xFF0F172A)
    val subTextColor = if (useDarkTheme) Color(0xFF94A3B8) else Color(0xFF64748B)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 5.dp)
            .height(68.dp)
    ) {
        // Floating Neu-Glass Capsule
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = surfaceBase,
            shadowElevation = 8.dp,
            modifier = Modifier
                .fillMaxSize()
                .border(width = 1.2.dp, brush = rimHighlight, shape = RoundedCornerShape(22.dp))
                .clip(RoundedCornerShape(22.dp))
                .pointerInput(swipeThumbnail, isListenTogetherGuest) {
                    if (!swipeThumbnail || isListenTogetherGuest) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = {
                            dragStartTime = System.currentTimeMillis()
                            totalDragDistance = 0f
                        },
                        onDragCancel = {
                            coroutineScope.launch {
                                offsetXAnimatable.animateTo(0f, animationSpec)
                            }
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            totalDragDistance += dragAmount
                            coroutineScope.launch {
                                offsetXAnimatable.snapTo(offsetXAnimatable.value + dragAmount)
                            }
                        },
                        onDragEnd = {
                            val dragDuration = (System.currentTimeMillis() - dragStartTime).coerceAtLeast(1)
                            val velocity = (totalDragDistance / dragDuration) * 1000f
                            val threshold = 180f * (1f - (swipeSensitivity - 0.5f) * 0.5f)

                            val isRtl = layoutDirection == LayoutDirection.Rtl
                            val isLeftToRight = if (isRtl) totalDragDistance < 0 else totalDragDistance > 0

                            val shouldTrigger = totalDragDistance.absoluteValue > threshold || velocity.absoluteValue > 500f

                            if (shouldTrigger) {
                                if (isLeftToRight) {
                                    if (canSkipPrevious) {
                                        playerConnection.seekToPrevious()
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    }
                                } else {
                                    if (canSkipNext) {
                                        playerConnection.seekToNext()
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    }
                                }
                            }
                            coroutineScope.launch {
                                offsetXAnimatable.animateTo(0f, animationSpec)
                            }
                        }
                    )
                }
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Content Row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxSize()
                        .offset { IntOffset(offsetXAnimatable.value.roundToInt(), 0) }
                        .padding(start = 8.dp, end = 12.dp)
                ) {
                    // Album Artwork with soft Neu-rim
                    Box(
                        modifier = Modifier
                            .size(50.dp)
                            .shadow(4.dp, RoundedCornerShape(14.dp))
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        AsyncImage(
                            model = mediaMetadata?.thumbnailUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    Spacer(Modifier.width(12.dp))

                    // Title & Artist Info
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = mediaMetadata?.title.orEmpty().ifEmpty { "Escapify" },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = textColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.basicMarquee()
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = mediaMetadata?.artists?.joinToString { it.name }.orEmpty().ifEmpty { "Ready to play" },
                            style = MaterialTheme.typography.bodySmall,
                            color = subTextColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(Modifier.width(8.dp))

                    // Favorite / Like Button
                    IconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            playerConnection.toggleLike()
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        val isLiked = currentSong?.song?.liked == true
                        Icon(
                            painter = painterResource(if (isLiked) R.drawable.favorite else R.drawable.favorite_border),
                            contentDescription = null,
                            tint = if (isLiked) Color(0xFFEF4444) else subTextColor,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Spacer(Modifier.width(4.dp))

                    // Tactile Neu-Play/Pause Button
                    var isPlayPressed by remember { mutableStateOf(false) }
                    val buttonScale by animateFloatAsState(
                        targetValue = if (isPlayPressed) 0.92f else 1.0f,
                        animationSpec = spring(stiffness = Spring.StiffnessMedium),
                        label = "playScale"
                    )

                    val neuButtonGradient = if (useDarkTheme) {
                        Brush.linearGradient(
                            colors = listOf(
                                Color(0xFF282C34),
                                Color(0xFF1A1D23)
                            ),
                            start = Offset(0f, 0f),
                            end = Offset(45f, 45f)
                        )
                    } else {
                        Brush.linearGradient(
                            colors = listOf(
                                Color(0xFFFFFFFF),
                                Color(0xFFDFE4EC)
                            ),
                            start = Offset(0f, 0f),
                            end = Offset(45f, 45f)
                        )
                    }

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(44.dp)
                            .scale(buttonScale)
                            .shadow(elevation = if (isPlayPressed) 1.dp else 4.dp, shape = CircleShape)
                            .background(brush = neuButtonGradient, shape = CircleShape)
                            .border(
                                width = 1.dp,
                                brush = if (useDarkTheme) Brush.verticalGradient(
                                    listOf(Color.White.copy(alpha = 0.25f), Color.Black.copy(alpha = 0.6f))
                                ) else Brush.verticalGradient(
                                    listOf(Color.White, Color.Black.copy(alpha = 0.15f))
                                ),
                                shape = CircleShape
                            )
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                if (playbackState == Player.STATE_ENDED) {
                                    playerConnection.player.seekTo(0, 0)
                                    playerConnection.player.playWhenReady = true
                                } else {
                                    playerConnection.player.togglePlayPause()
                                }
                            }
                    ) {
                        Icon(
                            painter = painterResource(
                                if (playbackState == Player.STATE_ENDED) {
                                    R.drawable.replay
                                } else if (isPlaying) {
                                    R.drawable.pause
                                } else {
                                    R.drawable.play
                                }
                            ),
                            contentDescription = null,
                            tint = if (isPlaying) primaryColor else textColor,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Spacer(Modifier.width(4.dp))

                    // Skip Next Button
                    IconButton(
                        onClick = {
                            if (canSkipNext && !isListenTogetherGuest) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                playerConnection.seekToNext()
                            }
                        },
                        enabled = canSkipNext && !isListenTogetherGuest,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.skip_next),
                            contentDescription = null,
                            tint = if (canSkipNext) textColor else subTextColor.copy(alpha = 0.4f),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                // Glowing Neu Progress Bar along bottom rim
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .align(Alignment.BottomCenter)
                ) {
                    val currentProgress = progressState.progress
                    if (currentProgress > 0f) {
                        val progressWidth = size.width * currentProgress
                        // Ambient glow track
                        drawLine(
                            brush = Brush.horizontalGradient(
                                colors = listOf(
                                    primaryColor.copy(alpha = 0.6f),
                                    primaryColor
                                )
                            ),
                            start = Offset(0f, size.height / 2),
                            end = Offset(progressWidth, size.height / 2),
                            strokeWidth = size.height,
                            cap = StrokeCap.Round
                        )
                    }
                }
            }
        }
    }
}
