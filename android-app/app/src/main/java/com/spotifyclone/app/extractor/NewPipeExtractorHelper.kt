package com.spotifyclone.app.extractor

import com.spotifyclone.app.model.AlbumDto
import com.spotifyclone.app.model.ArtistChannel
import com.spotifyclone.app.model.ArtistDto
import com.spotifyclone.app.model.TrackDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.search.SearchExtractor
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object NewPipeExtractorHelper {

    private var initialized = false

    // Cache resolved stream URLs: Key = youtubeId, Value = Pair(streamUrl, expiryTimestampMs)
    // YouTube stream tokens typically expire in 6 hours; we cache for 4 hours
    private val streamUrlCache = ConcurrentHashMap<String, Pair<String, Long>>()
    private const val CACHE_TTL_MS = 4 * 60 * 60 * 1000L

    private const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val okHttpClient = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(10, 5, TimeUnit.MINUTES))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Convert low resolution image/thumbnail URLs to high definition (HD 800x800 / 720p). */
    fun getHighQualityImageUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null

        // Google / YouTube Music image servers (lh3.googleusercontent.com, yt3.ggpht.com)
        if (url.contains("googleusercontent.com") || url.contains("ggpht.com")) {
            var hdUrl = url.replace(Regex("=w\\d+-h\\d+[^=\\s]*"), "=w800-h800-s800-rw")
            hdUrl = hdUrl.replace(Regex("=s\\d+[^=\\s]*"), "=s800-rw")
            return hdUrl
        }

        // YouTube video thumbnails
        if (url.contains("i.ytimg.com")) {
            val videoId = url.substringAfter("/vi/").substringAfter("/vi_webp/").substringBefore("/")
            if (videoId.isNotEmpty() && !videoId.contains("http")) {
                return "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg"
            }
        }

        return url
    }

    @Synchronized
    fun init() {
        if (initialized) return

        val downloader = object : Downloader() {
            @Throws(IOException::class, ExtractionException::class)
            override fun execute(request: org.schabi.newpipe.extractor.downloader.Request): Response {
                val httpMethod = request.httpMethod()
                val url = request.url()
                val headers = request.headers()
                val dataToSend = request.dataToSend()

                val reqBuilder = Request.Builder().url(url)

                var hasUserAgent = false
                headers.forEach { (key, values) ->
                    if (key.equals("User-Agent", ignoreCase = true)) hasUserAgent = true
                    values.forEach { v -> reqBuilder.addHeader(key, v) }
                }
                if (!hasUserAgent) {
                    reqBuilder.addHeader("User-Agent", DEFAULT_USER_AGENT)
                }
                reqBuilder.addHeader("Accept-Language", "en-US,en;q=0.9")

                when (httpMethod) {
                    "POST" -> {
                        val body = dataToSend?.toRequestBody() ?: ByteArray(0).toRequestBody()
                        reqBuilder.post(body)
                    }
                    "HEAD" -> reqBuilder.head()
                    else -> reqBuilder.get()
                }

                val response = okHttpClient.newCall(reqBuilder.build()).execute()
                val responseBody = response.body?.string() ?: ""
                val responseHeaders = response.headers.toMultimap()

                return Response(
                    response.code,
                    response.message,
                    responseHeaders,
                    responseBody,
                    response.request.url.toString()
                )
            }
        }

        NewPipe.init(downloader)
        initialized = true
    }

    /**
     * Search YouTube Music for artists matching the query.
     * Returns artist names that can be used with fetchArtistSongs.
     */
    suspend fun searchYouTubeMusicArtists(query: String): Result<List<ArtistDto>> = withContext(Dispatchers.IO) {
        try {
            init()
            val service = ServiceList.YouTube
            val searchExtractor: SearchExtractor = service.getSearchExtractor(
                query,
                listOf("music_artists"),
                ""
            )
            searchExtractor.fetchPage()

            val results = mutableListOf<ArtistDto>()
            val items = searchExtractor.initialPage.items

            for (item in items) {
                val thumb = item.thumbnails.lastOrNull()?.url ?: item.thumbnails.firstOrNull()?.url
                results.add(
                    ArtistDto(
                        name = item.name ?: "Unknown Artist",
                        avatarUrl = getHighQualityImageUrl(thumb),
                        bannerUrl = null,
                        description = null,
                        channelUrl = item.url
                    )
                )
            }

            Result.success(results)
        } catch (e: ParsingException) {
            Result.failure(SourceBrokenException("YouTube Music artist search parser broken", e))
        } catch (e: ExtractionException) {
            Result.failure(SourceBrokenException("YouTube Music artist search extraction failed", e))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Fetch a channel tab with continuation pages, capped to keep profile loads responsive. */
    private fun fetchArtistChannelTabItems(
        channelUrl: String,
        tabName: String,
        limit: Int
    ): Result<List<InfoItem>> {
        return try {
            val service = ServiceList.YouTube
            val channel = service.getChannelExtractor(channelUrl)
            channel.fetchPage()
            val tabLink = channel.tabs.firstOrNull { tab ->
                tab.contentFilters.any { it.equals(tabName, ignoreCase = true) } ||
                    tab.id.equals(tabName, ignoreCase = true)
            } ?: return Result.success(emptyList())

            val extractor = service.getChannelTabExtractor(tabLink)
            extractor.fetchPage()
            val results = mutableListOf<InfoItem>()
            var page = extractor.initialPage
            var pagesFetched = 0
            do {
                results.addAll(page.items.take((limit - results.size).coerceAtLeast(0)))
                pagesFetched++
                if (results.size >= limit || pagesFetched >= 10 || !page.hasNextPage()) break
                page = extractor.getPage(page.nextPage)
            } while (true)
            Result.success(results)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun StreamInfoItem.toTrackDto(artistName: String, position: Int): TrackDto? {
        val watchUrl = url ?: return null
        val videoId = Regex("[?&]v=([^&]+)").find(watchUrl)?.groupValues?.getOrNull(1)
            ?: return null
        val thumbnail = thumbnails.lastOrNull()?.url ?: getHighResThumbnail(videoId)
        return TrackDto(
            youtubeId = videoId,
            title = name ?: "Unknown Title",
            artist = uploaderName ?: artistName,
            durationSeconds = duration,
            thumbnailUrl = getHighQualityImageUrl(thumbnail),
            position = position
        )
    }

    private suspend fun resolveArtistChannelUrl(artistName: String, knownUrl: String?): String? {
        if (!knownUrl.isNullOrBlank()) return knownUrl
        val artists = searchYouTubeMusicArtists(artistName).getOrDefault(emptyList())
        val normalizedTarget = artistName.lowercase().filter { it.isLetterOrDigit() }
        return artists.firstOrNull {
            it.name.lowercase().filter { character -> character.isLetterOrDigit() } == normalizedTarget
        }?.channelUrl
    }

    /**
     * Checks if a track strictly belongs to or features the target artist.
     * Prevents false positives like Justin Timberlake matching Justin Bieber.
     */
    private fun isTrackForArtist(track: TrackDto, targetArtistName: String): Boolean {
        val targetLower = targetArtistName.lowercase().trim()
        val titleLower = track.title.lowercase().trim()
        val artistCreditsArtist = artistNameMatches(track.artist.orEmpty(), targetArtistName)
        val escapedName = Regex.escape(targetLower)
        val featureCredit = Regex("(?:feat(?:uring)?\\.?|with)\\s+[^)]*${escapedName}", RegexOption.IGNORE_CASE)
            .containsMatchIn(titleLower)
        return artistCreditsArtist || featureCredit
    }

    private fun artistNameMatches(credits: String, artistName: String): Boolean {
        if (credits.isBlank() || artistName.isBlank()) return false
        val escapedName = Regex.escape(artistName.trim().lowercase())
        return Regex("(?<![\\p{L}\\p{N}])$escapedName(?![\\p{L}\\p{N}])")
            .containsMatchIn(credits.lowercase())
    }

    /**
     * Fetch artist Top Songs directly from YouTube Music search.
     * Collects up to 50 official artist songs in YouTube Music popularity order.
     */
    suspend fun fetchArtistSongs(artistName: String, channelUrl: String? = null): Result<ArtistChannel> = withContext(Dispatchers.IO) {
        try {
            init()
            val resolvedChannelUrl = resolveArtistChannelUrl(artistName, channelUrl)
            val allCollected = mutableListOf<TrackDto>()
            val seenIds = mutableSetOf<String>()

            // Read the artist's own YouTube Music Tracks tab first.
            if (!resolvedChannelUrl.isNullOrBlank()) {
                val officialItems = fetchArtistChannelTabItems(resolvedChannelUrl, ChannelTabs.TRACKS, 50)
                officialItems.getOrDefault(emptyList()).filterIsInstance<StreamInfoItem>().forEach { item ->
                    val track = item.toTrackDto(artistName, allCollected.size) ?: return@forEach
                    if (seenIds.add(track.youtubeId)) allCollected.add(track)
                }
            }

            // Artist tabs can be short for some profiles. Supplement them with
            // search only when the artist is explicitly in the song credits or
            // named in a feature credit, never with loose token matches.
            val searchQueries = listOf("$artistName popular songs", "$artistName top songs")
            for (query in searchQueries) {
                if (allCollected.size >= 50) break
                val searchRes = fetchSearchTracks(query, 50 - allCollected.size)
                if (searchRes.isSuccess) {
                    searchRes.getOrDefault(emptyList())
                        .filter { isTrackForArtist(it, artistName) }
                        .forEach { track ->
                            if (seenIds.add(track.youtubeId)) allCollected.add(track)
                        }
                }
                if (allCollected.size >= 50) break
            }

            Result.success(
                ArtistChannel(
                    artist = ArtistDto(
                        name = artistName,
                        avatarUrl = null,
                        bannerUrl = null,
                        description = null,
                        channelUrl = resolvedChannelUrl
                    ),
                    tracks = allCollected
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun fetchArtistReleases(channelUrl: String, artistName: String): List<AlbumDto> {
        val items = fetchArtistChannelTabItems(channelUrl, ChannelTabs.ALBUMS, 200)
            .getOrDefault(emptyList())
        val releases = mutableListOf<AlbumDto>()
        val seen = mutableSetOf<String>()
        for (item in items) {
            val playlist = item as? PlaylistInfoItem ?: continue
            val title = playlist.name ?: continue
            val id = playlist.url ?: continue
            if (!seen.add(id)) continue
            val thumbnail = playlist.thumbnails.lastOrNull()?.url
                ?: playlist.thumbnails.firstOrNull()?.url
            val count = playlist.streamCount.takeIf { it > 0L }?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
            releases.add(
                AlbumDto(
                    id = id,
                    name = title,
                    artistName = playlist.uploaderName?.takeIf { it.isNotBlank() } ?: artistName,
                    coverUrl = getHighQualityImageUrl(thumbnail),
                    tracks = emptyList(),
                    trackCount = count,
                    channelUrl = channelUrl
                )
            )
        }
        return releases
    }

    /** Fetch an artist's albums and singles from one channel tab when possible. */
    suspend fun fetchArtistDiscography(
        artistName: String,
        channelUrl: String? = null
    ): Result<Pair<List<AlbumDto>, List<AlbumDto>>> = withContext(Dispatchers.IO) {
        try {
            init()
            val resolvedChannelUrl = resolveArtistChannelUrl(artistName, channelUrl)
            if (!resolvedChannelUrl.isNullOrBlank()) {
                val releases = fetchArtistReleases(resolvedChannelUrl, artistName)
                if (releases.isNotEmpty()) {
                    return@withContext Result.success(
                        releases.filterNot(::isSingleOrEp) to releases.filter(::isSingleOrEp)
                    )
                }
            }

            coroutineScope {
                val albums = async { searchArtistAlbums(artistName, resolvedChannelUrl) }
                val singles = async { searchArtistSinglesAndEps(artistName, resolvedChannelUrl) }
                Result.success(
                    albums.await().getOrDefault(emptyList()) to
                        singles.await().getOrDefault(emptyList())
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun isSingleOrEp(album: AlbumDto): Boolean {
        val title = album.name.lowercase()
        return Regex("\\b(single|ep)\\b").containsMatchIn(title) ||
            (album.trackCount != null && album.trackCount <= 6)
    }

    /**
     * Search for artist official full albums on YouTube Music.
     * Filters out non-matching artists and excludes single releases.
     */
    suspend fun searchArtistAlbums(artistName: String, channelUrl: String? = null): Result<List<AlbumDto>> = withContext(Dispatchers.IO) {
        try {
            init()
            val resolvedChannelUrl = resolveArtistChannelUrl(artistName, channelUrl)
            if (!resolvedChannelUrl.isNullOrBlank()) {
                val releases = fetchArtistReleases(resolvedChannelUrl, artistName)
                val albums = releases.filterNot(::isSingleOrEp)
                if (albums.isNotEmpty()) return@withContext Result.success(albums)
            }
            val service = ServiceList.YouTube
            val results = mutableListOf<AlbumDto>()
            val seenNames = mutableSetOf<String>()

            val queries = listOf("$artistName full album", "$artistName album", "$artistName studio album")

            for (query in queries) {
                try {
                    val searchExtractor: SearchExtractor = service.getSearchExtractor(
                        query,
                        listOf("music_albums"),
                        ""
                    )
                    searchExtractor.fetchPage()

                    var page = searchExtractor.initialPage
                    var pagesFetched = 0
                    do {
                    for (item in page.items) {
                        val albumName = item.name ?: continue
                        val albumLower = albumName.lowercase()

                        // Exclude obvious standalone single remixes from full albums section
                        if (albumLower.contains("(single)") || albumLower.contains(" - single")) {
                            continue
                        }

                        val uploader = (item as? PlaylistInfoItem)?.uploaderName.orEmpty()
                        if (!artistNameMatches(uploader, artistName)) continue
                        val trackCount = (item as? PlaylistInfoItem)?.streamCount
                            ?.takeIf { it > 0L }?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
                        if (isSingleOrEp(AlbumDto(name = albumName, coverUrl = null, trackCount = trackCount))) continue

                        val key = albumName.lowercase().trim()
                        if (!seenNames.contains(key)) {
                            seenNames.add(key)
                            val thumb = item.thumbnails.lastOrNull()?.url ?: item.thumbnails.firstOrNull()?.url
                            results.add(
                                AlbumDto(
                                    id = item.url,
                                    name = albumName,
                                    artistName = artistName,
                                    year = null,
                                    coverUrl = getHighQualityImageUrl(thumb),
                                    tracks = emptyList(),
                                    trackCount = trackCount,
                                    channelUrl = resolvedChannelUrl
                                )
                            )
                        }
                    }
                    pagesFetched++
                    if (pagesFetched >= 4 || !page.hasNextPage()) break
                    page = searchExtractor.getPage(page.nextPage)
                    } while (true)
                } catch (e: Exception) {
                    // Ignore
                }
            }

            Result.success(results)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Search for artist Singles & EPs on YouTube Music.
     */
    suspend fun searchArtistSinglesAndEps(artistName: String, channelUrl: String? = null): Result<List<AlbumDto>> = withContext(Dispatchers.IO) {
        try {
            init()
            val resolvedChannelUrl = resolveArtistChannelUrl(artistName, channelUrl)
            if (!resolvedChannelUrl.isNullOrBlank()) {
                val releases = fetchArtistReleases(resolvedChannelUrl, artistName)
                val singles = releases.filter(::isSingleOrEp)
                if (singles.isNotEmpty()) return@withContext Result.success(singles)
            }
            val service = ServiceList.YouTube
            val results = mutableListOf<AlbumDto>()
            val seenNames = mutableSetOf<String>()

            val queries = listOf("$artistName single", "$artistName EP", "$artistName")

            for (query in queries) {
                try {
                    val searchExtractor: SearchExtractor = service.getSearchExtractor(
                        query,
                        listOf("music_albums"),
                        ""
                    )
                    searchExtractor.fetchPage()

                    var page = searchExtractor.initialPage
                    var pagesFetched = 0
                    do {
                    for (item in page.items) {
                        val albumName = item.name ?: continue
                        val uploader = (item as? PlaylistInfoItem)?.uploaderName.orEmpty()
                        if (!artistNameMatches(uploader, artistName)) continue
                        val trackCount = (item as? PlaylistInfoItem)?.streamCount
                            ?.takeIf { it > 0L }?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
                        if (!isSingleOrEp(AlbumDto(name = albumName, coverUrl = null, trackCount = trackCount))) continue

                        val key = albumName.lowercase().trim()
                        if (!seenNames.contains(key)) {
                            seenNames.add(key)
                            val thumb = item.thumbnails.lastOrNull()?.url ?: item.thumbnails.firstOrNull()?.url
                            results.add(
                                AlbumDto(
                                    id = item.url,
                                    name = albumName,
                                    artistName = artistName,
                                    year = null,
                                    coverUrl = getHighQualityImageUrl(thumb),
                                    tracks = emptyList(),
                                    trackCount = trackCount,
                                    channelUrl = resolvedChannelUrl
                                )
                            )
                        }
                    }
                    pagesFetched++
                    if (pagesFetched >= 4 || !page.hasNextPage()) break
                    page = searchExtractor.getPage(page.nextPage)
                    } while (true)
                } catch (e: Exception) {
                    // Ignore
                }
            }

            Result.success(results)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Fetch all tracks for a specific album or single/EP using its playlist URL or album search.
     */
    suspend fun fetchAlbumTracks(
        albumUrl: String?,
        albumName: String,
        artistName: String?,
        expectedTrackCountHint: Int? = null
    ): Result<List<TrackDto>> = withContext(Dispatchers.IO) {
        try {
            init()
            val playlistTracks = mutableListOf<TrackDto>()
            var expectedTrackCount = expectedTrackCountHint
            if (!albumUrl.isNullOrEmpty()) {
                try {
                    val service = ServiceList.YouTube
                    val playlistExtractor = service.getPlaylistExtractor(albumUrl)
                    playlistExtractor.fetchPage()

                    var page = playlistExtractor.initialPage
                    expectedTrackCount = playlistExtractor.streamCount.toInt().takeIf { it > 0 }
                        ?: expectedTrackCountHint
                    var pagesFetched = 0
                    do {
                        for (item in page.items) {
                            if (item is StreamInfoItem) {
                                val track = item.toTrackDto(artistName ?: "Unknown Artist", playlistTracks.size)
                                if (track != null && playlistTracks.none { it.youtubeId == track.youtubeId }) {
                                    playlistTracks.add(track)
                                }
                            }
                        }
                        pagesFetched++
                        if (playlistTracks.size >= 100 || pagesFetched >= 8 || !page.hasNextPage()) break
                        page = playlistExtractor.getPage(page.nextPage)
                    } while (true)
                } catch (e: Exception) {
                    // Keep any tracks already extracted and merge in the search fallback below.
                }
            }

            val query = if (!artistName.isNullOrBlank()) "\"$albumName\" $artistName" else "\"$albumName\""
            val combined = playlistTracks.toMutableList()
            val seenIds = combined.mapTo(mutableSetOf()) { it.youtubeId }
            var searchFailure: Throwable? = null
            val needsFallback = expectedTrackCount?.let { playlistTracks.size < it }
                ?: (playlistTracks.size < 6)
            if (needsFallback) {
                val searchLimit = ((expectedTrackCount ?: 100) - playlistTracks.size).coerceAtLeast(1)
                val searchResult = fetchSearchTracks(query, searchLimit)
                if (searchResult.isSuccess) {
                    searchResult.getOrDefault(emptyList())
                        .filter { artistName.isNullOrBlank() || isTrackForArtist(it, artistName) }
                        .forEach { track ->
                            if (seenIds.add(track.youtubeId)) {
                                combined.add(track.copy(position = combined.size))
                            }
                        }
                } else {
                    searchFailure = searchResult.exceptionOrNull()
                }
            }

            if (combined.isNotEmpty()) {
                val expected = expectedTrackCount
                return@withContext Result.success(
                    if (expected != null && expected < combined.size) combined.take(expected) else combined
                )
            }
            Result.failure(searchFailure ?: Exception("Failed to fetch album tracks"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Fetch related/similar artists for "Fans might also like".
     */
    suspend fun fetchRelatedArtists(artistName: String): Result<List<ArtistDto>> = withContext(Dispatchers.IO) {
        try {
            init()
            val artistLower = artistName.lowercase().trim()

            val query = "$artistName"
            val result = searchYouTubeMusicArtists(query)
            if (result.isSuccess) {
                val list = result.getOrDefault(emptyList())
                val related = list.filter { artist ->
                    val nameLower = artist.name.lowercase().trim()
                    nameLower != artistLower && !nameLower.contains(artistLower) && !artistLower.contains(nameLower)
                }
                if (related.isNotEmpty()) {
                    return@withContext Result.success(related)
                }
            }

            val fallbackResult = searchYouTubeMusicArtists("$artistName related")
            if (fallbackResult.isSuccess) {
                val list = fallbackResult.getOrDefault(emptyList())
                val related = list.filter { artist ->
                    val nameLower = artist.name.lowercase().trim()
                    nameLower != artistLower && !nameLower.contains(artistLower) && !artistLower.contains(nameLower)
                }
                return@withContext Result.success(related)
            }

            Result.failure(Exception("No related artists found"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Search YouTube Music for tracks matching the query.
     */
    suspend fun searchYouTubeMusic(query: String): Result<List<TrackDto>> = withContext(Dispatchers.IO) {
        try {
            init()
            val service = ServiceList.YouTube
            val searchExtractor: SearchExtractor = service.getSearchExtractor(
                query,
                listOf("music_songs"),
                ""
            )
            searchExtractor.fetchPage()
            val results = mutableListOf<TrackDto>()
            var page = searchExtractor.initialPage
            var pagesFetched = 0
            do {
                for (item in page.items) {
                    if (item is StreamInfoItem) {
                        val id = item.url.substringAfter("v=").substringBefore("&")
                        if (id.isNotEmpty() && results.none { it.youtubeId == id }) {
                            val thumb = item.thumbnails.lastOrNull()?.url ?: getHighResThumbnail(id)
                            results.add(
                                TrackDto(
                                    youtubeId = id,
                                    title = item.name ?: "Unknown Title",
                                    artist = item.uploaderName ?: "Unknown Artist",
                                    durationSeconds = item.duration,
                                    thumbnailUrl = getHighQualityImageUrl(thumb),
                                    position = results.size
                                )
                            )
                        }
                    }
                }
                pagesFetched++
                if (results.size >= 50 || pagesFetched >= 5 || !page.hasNextPage()) break
                page = searchExtractor.getPage(page.nextPage)
            } while (true)
            Result.success(results)
        } catch (e: ParsingException) {
            Result.failure(SourceBrokenException("YouTube Music parser broken: parsing error", e))
        } catch (e: ExtractionException) {
            Result.failure(SourceBrokenException("YouTube Music extraction failed", e))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun fetchSearchTracks(query: String, limit: Int): Result<List<TrackDto>> = try {
        val extractor = ServiceList.YouTube.getSearchExtractor(query, listOf("music_songs"), "")
        extractor.fetchPage()
        val results = mutableListOf<TrackDto>()
        var page = extractor.initialPage
        var pagesFetched = 0
        do {
            for (item in page.items) {
                if (item is StreamInfoItem) {
                    val id = item.url.substringAfter("v=").substringBefore("&")
                    if (id.isNotEmpty() && results.none { it.youtubeId == id }) {
                        val thumb = item.thumbnails.lastOrNull()?.url ?: getHighResThumbnail(id)
                        results.add(
                            TrackDto(
                                youtubeId = id,
                                title = item.name ?: "Unknown Title",
                                artist = item.uploaderName ?: "Unknown Artist",
                                durationSeconds = item.duration,
                                thumbnailUrl = getHighQualityImageUrl(thumb),
                                position = results.size
                            )
                        )
                    }
                }
            }
            pagesFetched++
            if (results.size >= limit || pagesFetched >= 5 || !page.hasNextPage()) break
            page = extractor.getPage(page.nextPage)
        } while (true)
        Result.success(results.take(limit))
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** Get highest resolution YouTube thumbnail for a video ID. */
    private fun getHighResThumbnail(videoId: String): String {
        // Try maxres, fall back to hqdefault
        return "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg"
    }

    /**
     * Resolve a direct audio stream URL just-in-time before playback starts.
     * Uses memory cache to provide 0ms latency on repeated plays and pre-resolved songs.
     */
    suspend fun resolveStreamUrl(youtubeId: String): Result<String> = withContext(Dispatchers.IO) {
        // 1. Check in-memory cache for valid unexpired URL
        val now = System.currentTimeMillis()
        val cached = streamUrlCache[youtubeId]
        if (cached != null && cached.second > now) {
            return@withContext Result.success(cached.first)
        }

        try {
            init()
            val service = ServiceList.YouTube
            val watchUrl = "https://www.youtube.com/watch?v=$youtubeId"
            val streamInfo = StreamInfo.getInfo(service, watchUrl)

            val audioStreams = streamInfo.audioStreams
            if (audioStreams.isNullOrEmpty()) {
                return@withContext Result.failure(SourceBrokenException("No audio streams available for track $youtubeId"))
            }

            val bestAudio = audioStreams
                .filter { it.getFormat() == org.schabi.newpipe.extractor.MediaFormat.M4A }
                .maxByOrNull { it.bitrate }
                ?: audioStreams.maxByOrNull { it.bitrate }
                ?: return@withContext Result.failure(SourceBrokenException("No playable audio stream found for $youtubeId"))

            val streamUrl = bestAudio.content
            streamUrlCache[youtubeId] = Pair(streamUrl, now + CACHE_TTL_MS)

            Result.success(streamUrl)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Fetch search auto-complete predictions / suggestions as the user types.
     */
    suspend fun fetchSearchSuggestions(query: String): List<String> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext emptyList()
        try {
            val encoded = java.net.URLEncoder.encode(trimmed, "UTF-8")
            val url = "https://suggestqueries.google.com/complete/search?client=firefox&ds=yt&q=$encoded"
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", DEFAULT_USER_AGENT)
                .build()

            val response = okHttpClient.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext emptyList()

            val tokener = org.json.JSONTokener(body)
            val jsonArray = tokener.nextValue() as? org.json.JSONArray ?: return@withContext emptyList()
            if (jsonArray.length() >= 2) {
                val suggestionsArray = jsonArray.getJSONArray(1)
                val list = mutableListOf<String>()
                for (i in 0 until suggestionsArray.length()) {
                    list.add(suggestionsArray.getString(i))
                }
                return@withContext list
            }
            emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
}

/**
 * Custom exception used to signal NewPipe parser breakage to the UI
 */
class SourceBrokenException(message: String, cause: Throwable? = null) : Exception(message, cause)
