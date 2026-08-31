/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.playback

import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest

import android.content.Context
import android.net.ConnectivityManager
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import com.music.innertube.YouTube
import com.music.vivi.constants.AudioQuality
import com.music.vivi.constants.AudioQualityKey
import com.music.vivi.constants.DisableMobileDataKey
import com.music.vivi.constants.IpVersionKey
import com.music.vivi.utils.dataStore
import com.music.vivi.utils.get
import androidx.media3.exoplayer.scheduler.Requirements
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import com.music.innertube.models.IpVersion
import com.music.innertube.models.YouTubeClient
import com.music.innertube.strategy.ContentHints
import okhttp3.Dns
import java.net.InetAddress
import java.net.Inet4Address
import java.net.Inet6Address
import com.music.vivi.db.MusicDatabase
import com.music.vivi.db.entities.FormatEntity
import com.music.vivi.db.entities.SongEntity
import com.music.vivi.di.DownloadCache
import com.music.vivi.di.PlayerCache
import com.music.vivi.ui.utils.resize
import com.music.vivi.utils.YTPlayerUtils
import com.music.vivi.utils.enumPreference
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.time.LocalDateTime
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadUtil
@Inject
constructor(
    @ApplicationContext context: Context,
    val database: MusicDatabase,
    val databaseProvider: DatabaseProvider,
    @DownloadCache val downloadCache: SimpleCache,
    @PlayerCache val playerCache: SimpleCache,
) {
    private val connectivityManager = context.getSystemService<ConnectivityManager>()!!
    private val audioQuality by enumPreference(context, AudioQualityKey, AudioQuality.AUTO)
    private val ipVersion by enumPreference(context, IpVersionKey, IpVersion.AUTO)
    private val songUrlCache = StreamUrlCache()
    // Keep a reference to context so we can read DataStore prefs for JioSaavn support
    private val appContext: Context = context

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val downloads = MutableStateFlow<Map<String, Download>>(emptyMap())

    private val dataSourceFactory =
        ResolvingDataSource.Factory(
            CacheDataSource
                .Factory()
                .setCache(playerCache)
                .setUpstreamDataSourceFactory(
                    OkHttpDataSource.Factory(
                        OkHttpClient.Builder()
                            .dns(object : Dns {
                                override fun lookup(hostname: String): List<InetAddress> {
                                    val addresses = Dns.SYSTEM.lookup(hostname)
                                    return when (this@DownloadUtil.ipVersion) {
                                        IpVersion.IPV4 -> addresses.filter { it is Inet4Address }.ifEmpty { addresses }
                                        IpVersion.IPV6 -> addresses.filter { it is Inet6Address }.ifEmpty { addresses }
                                        IpVersion.AUTO -> addresses
                                    }
                                }
                            })
                            .proxy(YouTube.proxy)
                            .proxyAuthenticator { _, response ->
                                YouTube.proxyAuth?.let { auth ->
                                    response.request.newBuilder()
                                        .header("Proxy-Authorization", auth)
                                        .build()
                                } ?: response.request
                            }
                            .build(),
                    ),
                ),
        ) { dataSpec ->
            val mediaId = dataSpec.key ?: error("No media id")
            val length = if (dataSpec.length >= 0) dataSpec.length else 1

            if (playerCache.isCached(mediaId, dataSpec.position, length)) {
                return@Factory dataSpec
            }

            songUrlCache[mediaId]?.let { cachedStream ->
                return@Factory dataSpec
                    .withUri(cachedStream.url.toUri())
                    .withRequestHeaders(dataSpec.httpRequestHeaders + cachedStream.requestHeaders)
            }
            val cacheGeneration = songUrlCache.generation(mediaId)

            val playbackData = runBlocking(Dispatchers.IO) {
                val song = database.getSongByIdBlocking(mediaId)?.song
                var lastResult: Result<YTPlayerUtils.PlaybackData>? = null
                val maxRetries = 3
                for (attempt in 1..maxRetries) {
                    val result = YTPlayerUtils.playerResponseForPlayback(
                        mediaId,
                        audioQuality = audioQuality,
                        connectivityManager = connectivityManager,
                        context = appContext,
                        contentHints = ContentHints(
                            isExplicit = song?.explicit,
                            isUploaded = song?.isUploaded,
                        ),
                    )
                    if (result.isSuccess) {
                        lastResult = result
                        break
                    }
                    lastResult = result
                    if (attempt < maxRetries) {
                        kotlinx.coroutines.delay(attempt * 600L)
                    }
                }
                lastResult?.getOrThrow() ?: error("Failed to resolve playback stream for $mediaId after $maxRetries attempts")
            }
            val format = playbackData.format

            val actualContentLength = format.contentLength ?: run {
                var length: Long? = null
                val client = OkHttpClient.Builder()
                    .proxy(YouTube.proxy)
                    .proxyAuthenticator { _, response ->
                        YouTube.proxyAuth?.let { auth ->
                            response.request.newBuilder()
                                .header("Proxy-Authorization", auth)
                                .build()
                        } ?: response.request
                    }
                    .build()
                val request = okhttp3.Request.Builder()
                    .head()
                    .url(playbackData.streamUrl)
                    .apply {
                        playbackData.streamHeaders.forEach { (name, value) ->
                            header(name, value)
                        }
                    }
                    .build()
                client.newCall(request).execute().use { response ->
                    length = response.header("Content-Length")?.toLongOrNull()
                }
                length ?: 0L
            }

            database.query {
                upsert(
                    FormatEntity(
                        id = mediaId,
                        itag = format.itag,
                        mimeType = format.mimeType.split(";")[0],
                        codecs = format.mimeType.split("codecs=").getOrNull(1)?.removeSurrounding("\"") ?: "mp4a.40.2",
                        bitrate = format.bitrate,
                        sampleRate = format.audioSampleRate,
                        contentLength = actualContentLength,
                        loudnessDb = playbackData.audioConfig?.loudnessDb,
                        perceptualLoudnessDb = playbackData.audioConfig?.perceptualLoudnessDb,
                        playbackUrl = playbackData.playbackTracking?.videostatsPlaybackUrl?.baseUrl
                    ),
                )

                val now = LocalDateTime.now()
                val existing = getSongByIdBlocking(mediaId)?.song

                val updatedSong = if (existing != null) {
                    if (existing.dateDownload == null) {
                        existing.copy(dateDownload = now)
                    } else {
                        existing
                    }
                } else {
                    SongEntity(
                        id = mediaId,
                        title = playbackData.videoDetails?.title ?: "Unknown",
                        duration = playbackData.videoDetails?.lengthSeconds?.toIntOrNull() ?: 0,
                        thumbnailUrl = playbackData.videoDetails?.thumbnail?.thumbnails?.lastOrNull()?.url?.resize(1200, 1200),
                        dateDownload = now,
                        isDownloaded = false
                    )
                }

                upsert(updatedSong)

                // Pre-cache the high-res thumbnail immediately when download starts
                updatedSong.thumbnailUrl?.let { url ->
                    val request = ImageRequest.Builder(context)
                        .data(url)
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .build()
                    SingletonImageLoader.get(context).enqueue(request)
                }
            }

            // For YouTube streams: if contentLength is available, provide full range; otherwise stream unbounded
            // without hardcoding 10MB limit to prevent truncating large or high-bitrate files.
            val streamUrl = if (playbackData.isSaavnStream || format.contentLength == null) {
                playbackData.streamUrl
            } else {
                "${playbackData.streamUrl}&range=0-${format.contentLength}"
            }

            songUrlCache.put(
                mediaId = mediaId,
                url = streamUrl,
                requestHeaders = playbackData.streamHeaders,
                clientName = playbackData.streamClient,
                expiresInSeconds = playbackData.streamExpiresInSeconds,
                expectedGeneration = cacheGeneration,
            )

            val cachedStream = songUrlCache[mediaId]
            if (cachedStream != null) {
                return@Factory dataSpec
                    .withUri(cachedStream.url.toUri())
                    .withRequestHeaders(dataSpec.httpRequestHeaders + cachedStream.requestHeaders)
            }

            dataSpec.withUri(streamUrl.toUri())
        }

    val downloadNotificationHelper =
        DownloadNotificationHelper(context, ExoDownloadService.CHANNEL_ID)

    @OptIn(DelicateCoroutinesApi::class)
    val downloadManager: DownloadManager =
        DownloadManager(
            context,
            databaseProvider,
            downloadCache,
            dataSourceFactory,
            Executor(Runnable::run)
        ).apply {
            maxParallelDownloads = 3
            addListener(
                object : DownloadManager.Listener {
                    override fun onDownloadChanged(
                        downloadManager: DownloadManager,
                        download: Download,
                        finalException: Exception?,
                    ) {
                        downloads.update { map ->
                            map.toMutableMap().apply {
                                set(download.request.id, download)
                            }
                        }

                        scope.launch {
                            when (download.state) {
                                Download.STATE_COMPLETED -> {
                                    val downloadedBytes = download.bytesDownloaded
                                    val minValidAudioBytes = 100_000L // 100KB minimum threshold for a valid track
                                    if (downloadedBytes >= minValidAudioBytes) {
                                        database.updateDownloadedInfo(download.request.id, true, LocalDateTime.now())
                                    } else {
                                        timber.log.Timber.tag("DownloadUtil").w(
                                            "Download completed with insufficient bytes ($downloadedBytes) for ${download.request.id}, marking unverified"
                                        )
                                        database.updateDownloadedInfo(download.request.id, false, null)
                                    }
                                }
                                Download.STATE_FAILED,
                                Download.STATE_STOPPED,
                                Download.STATE_REMOVING -> {
                                    database.updateDownloadedInfo(download.request.id, false, null)
                                }
                                else -> {
                                }
                            }
                        }
                    }
                }
            )
        }

    init {
        val result = mutableMapOf<String, Download>()
        val cursor = downloadManager.downloadIndex.getDownloads()
        while (cursor.moveToNext()) {
            result[cursor.download.request.id] = cursor.download
        }
        downloads.value = result

        scope.launch {
            appContext.dataStore.data.map { it[DisableMobileDataKey] ?: false }.distinctUntilChanged().collect { disableMobileData ->
                downloadManager.requirements = if (disableMobileData) {
                    Requirements(Requirements.NETWORK_UNMETERED)
                } else {
                    Requirements(Requirements.NETWORK)
                }
            }
        }
    }

    fun getDownload(songId: String): Flow<Download?> = downloads.map { it[songId] }

    fun release() {
        scope.cancel()
    }

    data class DownloadScanReport(
        val totalScanned: Int,
        val healthyCount: Int,
        val incompleteSongs: List<SongEntity>,
    )

    companion object {
        fun autoDownloadIfLiked(context: Context, song: SongEntity) {
            if (!song.liked || song.id.isBlank()) return
            CoroutineScope(Dispatchers.IO).launch {
                val autoDownload = context.dataStore.get(com.music.vivi.constants.AutoDownloadOnLikeKey, false)
                if (autoDownload) {
                    val downloadRequest = androidx.media3.exoplayer.offline.DownloadRequest
                        .Builder(song.id, song.id.toUri())
                        .setCustomCacheKey(song.id)
                        .setData(song.title.toByteArray())
                        .build()
                    androidx.media3.exoplayer.offline.DownloadService.sendAddDownload(
                        context,
                        ExoDownloadService::class.java,
                        downloadRequest,
                        false
                    )
                }
            }
        }

        suspend fun scanDownloadsHealth(
            database: MusicDatabase,
            downloadCache: SimpleCache,
        ): DownloadScanReport = withContext(Dispatchers.IO) {
            val allDownloaded = try {
                database.downloadedSongsByNameAsc().first()
            } catch (e: Exception) {
                emptyList()
            }

            val incomplete = mutableListOf<SongEntity>()
            var healthy = 0

            for (item in allDownloaded) {
                val song = item.song
                val cachedBytes = downloadCache.getCachedBytes(song.id, 0, Long.MAX_VALUE)
                val durationSec = song.duration ?: 0
                val expectedMinBytes = if (durationSec > 0) {
                    (durationSec * 10_000L).coerceAtLeast(100_000L)
                } else {
                    100_000L
                }

                val isOldCutoff = cachedBytes in 9_950_000L..10_050_000L && durationSec > 200
                val isInsufficient = cachedBytes < expectedMinBytes

                if (isOldCutoff || isInsufficient) {
                    incomplete.add(song)
                } else {
                    healthy++
                }
            }

            DownloadScanReport(
                totalScanned = allDownloaded.size,
                healthyCount = healthy,
                incompleteSongs = incomplete
            )
        }

        suspend fun repairIncompleteDownloads(
            context: Context,
            database: MusicDatabase,
            downloadCache: SimpleCache,
            songsToRepair: List<SongEntity>,
        ) = withContext(Dispatchers.IO) {
            for (song in songsToRepair) {
                try {
                    downloadCache.removeResource(song.id)
                } catch (_: Exception) {}
                database.updateDownloadedInfo(song.id, false, null)

                val downloadRequest = androidx.media3.exoplayer.offline.DownloadRequest
                    .Builder(song.id, song.id.toUri())
                    .setCustomCacheKey(song.id)
                    .setData(song.title.toByteArray())
                    .build()
                androidx.media3.exoplayer.offline.DownloadService.sendAddDownload(
                    context,
                    ExoDownloadService::class.java,
                    downloadRequest,
                    false
                )
            }
        }

        fun redownloadSong(
            context: Context,
            database: MusicDatabase,
            downloadCache: SimpleCache,
            song: SongEntity,
        ) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    downloadCache.removeResource(song.id)
                } catch (_: Exception) {}
                database.updateDownloadedInfo(song.id, false, null)

                val downloadRequest = androidx.media3.exoplayer.offline.DownloadRequest
                    .Builder(song.id, song.id.toUri())
                    .setCustomCacheKey(song.id)
                    .setData(song.title.toByteArray())
                    .build()
                androidx.media3.exoplayer.offline.DownloadService.sendAddDownload(
                    context,
                    ExoDownloadService::class.java,
                    downloadRequest,
                    false
                )
            }
        }

        fun removeDownload(
            context: Context,
            database: MusicDatabase,
            downloadCache: SimpleCache,
            songId: String,
        ) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    androidx.media3.exoplayer.offline.DownloadService.sendRemoveDownload(
                        context,
                        ExoDownloadService::class.java,
                        songId,
                        false
                    )
                } catch (_: Exception) {}
                try {
                    downloadCache.removeResource(songId)
                } catch (_: Exception) {}
                database.updateDownloadedInfo(songId, false, null)
            }
        }

        fun removeDownloads(
            context: Context,
            database: MusicDatabase,
            downloadCache: SimpleCache,
            songIds: List<String>,
        ) {
            CoroutineScope(Dispatchers.IO).launch {
                songIds.forEach { songId ->
                    try {
                        androidx.media3.exoplayer.offline.DownloadService.sendRemoveDownload(
                            context,
                            ExoDownloadService::class.java,
                            songId,
                            false
                        )
                    } catch (_: Exception) {}
                    try {
                        downloadCache.removeResource(songId)
                    } catch (_: Exception) {}
                }
                database.clearDownloadedFlags(songIds)
            }
        }

        suspend fun cleanGhostDownloads(
            database: MusicDatabase,
            downloadCache: SimpleCache,
        ): Int = withContext(Dispatchers.IO) {
            val allDownloaded = try {
                database.downloadedSongsByNameAsc().first()
            } catch (e: Exception) {
                emptyList()
            }
            val ghostIds = mutableListOf<String>()
            for (item in allDownloaded) {
                val song = item.song
                val cachedBytes = downloadCache.getCachedBytes(song.id, 0, Long.MAX_VALUE)
                if (cachedBytes < 100_000L) {
                    ghostIds.add(song.id)
                }
            }
            if (ghostIds.isNotEmpty()) {
                database.clearDownloadedFlags(ghostIds)
            }
            ghostIds.size
        }

        fun clearAllDownloads(
            context: Context,
            database: MusicDatabase,
            downloadCache: SimpleCache,
        ) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    downloadCache.keys.forEach { key ->
                        try {
                            downloadCache.removeResource(key)
                        } catch (_: Exception) {}
                        try {
                            androidx.media3.exoplayer.offline.DownloadService.sendRemoveDownload(
                                context,
                                ExoDownloadService::class.java,
                                key,
                                false
                            )
                        } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
                database.clearAllDownloadedFlags()
            }
        }
    }
}
