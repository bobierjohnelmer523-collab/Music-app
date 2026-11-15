package com.spotifyclone.app.extractor

import com.spotifyclone.app.model.TrackDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.search.SearchExtractor
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
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
            val items = searchExtractor.initialPage.items

            for (item in items) {
                if (item is StreamInfoItem) {
                    val id = item.url.substringAfter("v=").substringBefore("&")
                    if (id.isNotEmpty()) {
                        results.add(
                            TrackDto(
                                youtubeId = id,
                                title = item.name ?: "Unknown Title",
                                artist = item.uploaderName ?: "Unknown Artist",
                                durationSeconds = item.duration,
                                thumbnailUrl = item.thumbnails.firstOrNull()?.url
                            )
                        )
                    }
                }
            }

            Result.success(results)
        } catch (e: ParsingException) {
            Result.failure(SourceBrokenException("YouTube Music parser broken: parsing error", e))
        } catch (e: ExtractionException) {
            Result.failure(SourceBrokenException("YouTube Music extraction failed", e))
        } catch (e: Exception) {
            Result.failure(e)
        }
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

            // Pick M4A/AAC stream if available for faster instant decoding, or highest bitrate
            val bestAudioStream = audioStreams
                .filter { it.format?.name.equals("M4A", ignoreCase = true) || it.url?.contains(".m4a") == true }
                .maxByOrNull { it.averageBitrate }
                ?: audioStreams.maxByOrNull { it.averageBitrate }
                ?: audioStreams.first()

            val streamUrl = bestAudioStream.url
            if (streamUrl.isNullOrEmpty()) {
                return@withContext Result.failure(SourceBrokenException("Resolved audio stream URL is empty"))
            }

            // Store in cache for 4 hours
            streamUrlCache[youtubeId] = Pair(streamUrl, now + CACHE_TTL_MS)

            Result.success(streamUrl)
        } catch (e: ParsingException) {
            Result.failure(SourceBrokenException("Parsing failure during stream URL extraction", e))
        } catch (e: ExtractionException) {
            Result.failure(SourceBrokenException("Fatal extraction failure during stream URL extraction", e))
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
