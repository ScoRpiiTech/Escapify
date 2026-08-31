/**
 * Escapify Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.vivi.R
import com.music.vivi.db.entities.Playlist

@Composable
fun CompactPlaylistTile(
    playlist: Playlist,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    autoPlaylist: Boolean = false,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp))
            ) {
                PlaylistThumbnail(
                    thumbnails = playlist.thumbnails,
                    size = 56.dp,
                    placeHolder = {
                        val (painterRes, iconTint) = when (playlist.playlist.name) {
                            stringResource(R.string.liked) -> Pair(R.drawable.favorite, Color(0xFFEF4444))
                            stringResource(R.string.offline) -> Pair(R.drawable.offline, Color(0xFF22C55E))
                            stringResource(R.string.cached_playlist) -> Pair(R.drawable.cached, Color(0xFF3B82F6))
                            stringResource(R.string.uploaded_playlist) -> Pair(R.drawable.backup, Color(0xFFA855F7))
                            else -> if (autoPlaylist) Pair(R.drawable.trending_up, Color(0xFFEAB308)) else Pair(R.drawable.queue_music, LocalContentColor.current.copy(alpha = 0.8f))
                        }
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Icon(
                                painter = painterResource(painterRes),
                                contentDescription = null,
                                tint = iconTint,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    },
                    shape = RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp)
                )
            }

            Spacer(Modifier.width(12.dp))

            Text(
                text = playlist.playlist.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
            )
        }
    }
}
