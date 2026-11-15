package com.spotifyclone.app.playback

import android.content.Context
import android.util.Log
import com.spotifyclone.app.data.local.AppDatabase
import com.spotifyclone.app.data.local.entities.DownloadedTrack
import com.spotifyclone.app.extractor.NewPipeExtractorHelper
import com.spotifyclone.app.model.TrackDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class DownloadHelper(
    private val context: Context,
    private val database: AppDatabase
) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private val downloadDir: File = File(context.filesDir, "downloads").apply {
        if (!exists()) mkdirs()
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    // Map of youtubeId -> progress percentage (0..100)
    private val progressMap = ConcurrentHashMap<String, Int>()
    private val _downloadProgress = MutableStateFlow<Map<String, Int>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, Int>> = _downloadProgress

    // Track metadata currently downloading: youtubeId -> TrackDto
    private val downloadingTracksMap = ConcurrentHashMap<String, TrackDto>()
    private val _downloadingTracks = MutableStateFlow<List<TrackDto>>(emptyList())
    val downloadingTracks: StateFlow<List<TrackDto>> = _downloadingTracks

    /**
     * Download track audio file directly to local storage, broadcasting download progress.
     */
    fun downloadTrack(track: TrackDto, onComplete: (Boolean) -> Unit = {}) {
        scope.launch {
            try {
                // Check if already downloaded
                val existing = database.downloadedTrackDao().getDownloadedTrack(track.youtubeId)
                if (existing != null && File(existing.localUri).exists()) {
                    onComplete(true)
                    return@launch
                }

                // Register as actively downloading
                progressMap[track.youtubeId] = 0
                downloadingTracksMap[track.youtubeId] = track
                updateProgressFlow()

                // 1. Resolve direct audio stream URL
                val urlResult = NewPipeExtractorHelper.resolveStreamUrl(track.youtubeId)
                if (urlResult.isFailure) {
                    Log.e("DownloadHelper", "Failed to resolve stream URL: ${urlResult.exceptionOrNull()?.message}")
                    removeDownloadState(track.youtubeId)
                    onComplete(false)
                    return@launch
                }

                val streamUrl = urlResult.getOrThrow()

                // 2. Download file to local storage with real-time percentage tracking
                val outputFile = File(downloadDir, "${track.youtubeId}.m4a")
                val request = Request.Builder()
                    .url(streamUrl)
                    .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()

                val response = httpClient.newCall(request).execute()

                if (!response.isSuccessful || response.body == null) {
                    removeDownloadState(track.youtubeId)
                    onComplete(false)
                    return@launch
                }

                val body = response.body!!
                val contentLength = body.contentLength()

                body.byteStream().use { input ->
                    FileOutputStream(outputFile).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var totalRead = 0L

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalRead += bytesRead

                            if (contentLength > 0) {
                                val percent = ((totalRead * 100) / contentLength).toInt().coerceIn(0, 99)
                                if (percent != progressMap[track.youtubeId]) {
                                    progressMap[track.youtubeId] = percent
                                    updateProgressFlow()
                                }
                            }
                        }
                    }
                }

                // 3. Mark 100% and save metadata into Room
                progressMap[track.youtubeId] = 100
                updateProgressFlow()

                val downloadedTrack = DownloadedTrack(
                    youtubeId = track.youtubeId,
                    title = track.title,
                    artist = track.artist,
                    durationSeconds = track.durationSeconds,
                    thumbnailUrl = track.thumbnailUrl,
                    localUri = outputFile.absolutePath,
                    downloadedAt = System.currentTimeMillis()
                )
                database.downloadedTrackDao().insert(downloadedTrack)

                Log.d("DownloadHelper", "Track downloaded successfully to: ${outputFile.absolutePath}")
                removeDownloadState(track.youtubeId)
                onComplete(true)

            } catch (e: Exception) {
                Log.e("DownloadHelper", "Error downloading track", e)
                removeDownloadState(track.youtubeId)
                onComplete(false)
            }
        }
    }

    private fun updateProgressFlow() {
        _downloadProgress.value = HashMap(progressMap)
        _downloadingTracks.value = ArrayList(downloadingTracksMap.values)
    }

    private fun removeDownloadState(youtubeId: String) {
        progressMap.remove(youtubeId)
        downloadingTracksMap.remove(youtubeId)
        updateProgressFlow()
    }

    fun deleteDownloadedTrack(youtubeId: String, onComplete: () -> Unit = {}) {
        scope.launch {
            val track = database.downloadedTrackDao().getDownloadedTrack(youtubeId)
            if (track != null) {
                val file = File(track.localUri)
                if (file.exists()) {
                    file.delete()
                }
                database.downloadedTrackDao().delete(track)
            }
            onComplete()
        }
    }
}
