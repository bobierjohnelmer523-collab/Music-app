package com.spotifyclone.app.extractor

import com.spotifyclone.app.model.TrackDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import java.util.concurrent.TimeUnit

object NewPipeExtractorHelper {

    private var initialized = false

    fun init() {
        if (initialized) return
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        val downloader = object : Downloader() {
            @Throws(IOException::class, ExtractionException::class)
            override fun execute(request: org.schabi.newpipe.extractor.downloader.Request): Response {
                val httpMethod = request.httpMethod()
                val url = request.url()
                val headers = request.headers()
                val dataToSend = request.dataToSend()

                val reqBuilder = Request.Builder().url(url)
                headers.forEach { (key, values) ->
                    values.forEach { v -> reqBuilder.addHeader(key, v) }
                }

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
                    results.add(
                        TrackDto(
                            youtubeId = item.url.substringAfter("v=").substringBefore("&"),
                            title = item.name ?: "Unknown Title",
                            artist = item.uploaderName ?: "Unknown Artist",
                            durationSeconds = item.duration,
                            thumbnailUrl = item.thumbnails.firstOrNull()?.url
                        )
                    )
                }
            }

            Result.success(results)
        } catch (e: ParsingException) {
            // Parsing failure (e.g. YouTube parser format changed)
            Result.failure(SourceBrokenException("YouTube Music parser broken: parsing error", e))
        } catch (e: ExtractionException) {
            Result.failure(SourceBrokenException("YouTube Music extraction failed", e))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Resolve a direct audio stream URL just-in-time before playback starts.
     * This ensures the expiring token in the audio stream URL is fresh.
     */
    suspend fun resolveStreamUrl(youtubeId: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            init()
            val service = ServiceList.YouTube
            val watchUrl = "https://www.youtube.com/watch?v=$youtubeId"
            val streamInfo = StreamInfo.getInfo(service, watchUrl)

            val audioStreams = streamInfo.audioStreams
            if (audioStreams.isNullOrEmpty()) {
                return@withContext Result.failure(SourceBrokenException("No audio streams available for track $youtubeId"))
            }

            // Pick the highest bitrate stream
            val bestAudioStream = audioStreams.maxByOrNull { it.averageBitrate }
                ?: audioStreams.first()

            val streamUrl = bestAudioStream.url
            if (streamUrl.isNullOrEmpty()) {
                return@withContext Result.failure(SourceBrokenException("Resolved audio stream URL is empty"))
            }

            Result.success(streamUrl)
        } catch (e: ParsingException) {
            Result.failure(SourceBrokenException("Parsing failure during stream URL extraction", e))
        } catch (e: ExtractionException) {
            Result.failure(SourceBrokenException("Fatal extraction failure during stream URL extraction", e))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

/**
 * Custom exception used to signal NewPipe parser breakage to the UI
 */
class SourceBrokenException(message: String, cause: Throwable? = null) : Exception(message, cause)
