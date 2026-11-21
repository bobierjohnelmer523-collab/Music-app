package com.spotifyclone.app.playback

import android.content.Context
import android.util.Log
import com.spotifyclone.app.data.local.AppDatabase
import com.spotifyclone.app.data.local.entities.DownloadedTrack
import com.spotifyclone.app.extractor.NewPipeExtractorHelper
import com.spotifyclone.app.model.TrackDto
import com.spotifyclone.app.spotify.SpotifyTrackResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Call
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
    private val activeDownloadJobs = ConcurrentHashMap<String, Job>()
    private val activeDownloadResults = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val activeCalls = ConcurrentHashMap<String, Call>()

    /**
     * Download track audio file directly to local storage, broadcasting download progress.
     */
    fun downloadTrack(track: TrackDto, onComplete: (Boolean) -> Unit = {}) {
        // Spotify-sourced track: resolve the YouTube Music match first
        if (track.youtubeId.isBlank()) {
            scope.launch {
                try {
                    val resolved = SpotifyTrackResolver.resolve(track)
                    if (resolved != null) {
                        downloadTrack(resolved, onComplete)
                    } else {
                        Log.w("DownloadHelper", "No YouTube match for: ${track.title}")
                        notifyComplete(onComplete, false)
                    }
                } catch (error: Exception) {
                    Log.e("DownloadHelper", "Failed to resolve track for download", error)
                    notifyComplete(onComplete, false)
                }
            }
            return
        }

        scope.launch {
            try {
                val youtubeId = track.youtubeId
                val existing = database.downloadedTrackDao().getDownloadedTrack(youtubeId)
                val existingFile = existing?.let { File(it.localUri) }
                if (existingFile?.let { it.isFile && it.length() > 0L } == true) {
                    // A batch may have queued the same track while a second request
                    // arrives. Keep the stored copy and stop any redundant transfer.
                    cancelActiveDownload(youtubeId)
                    notifyComplete(onComplete, true)
                    return@launch
                }

                if (existing != null) {
                    existingFile?.delete()
                    database.downloadedTrackDao().delete(existing)
                }

                val result = CompletableDeferred<Boolean>()
                val activeResult = activeDownloadResults.putIfAbsent(youtubeId, result)
                if (activeResult != null) {
                    // Join an existing transfer rather than writing a second copy.
                    notifyComplete(onComplete, activeResult.await())
                    return@launch
                }

                val ownerJob = currentCoroutineContext()[Job]
                    ?: error("Download job is unavailable")
                activeDownloadJobs[youtubeId] = ownerJob
                try {
                    val success = downloadResolvedTrack(track)
                    result.complete(success)
                    notifyComplete(onComplete, success)
                } catch (cancelled: CancellationException) {
                    result.complete(false)
                    notifyComplete(onComplete, false)
                    throw cancelled
                } finally {
                    activeDownloadJobs.remove(youtubeId, ownerJob)
                    activeDownloadResults.remove(youtubeId, result)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Log.e("DownloadHelper", "Error preparing track download", e)
                notifyComplete(onComplete, false)
            }
        }
    }

    private suspend fun downloadResolvedTrack(track: TrackDto): Boolean {
        val youtubeId = track.youtubeId
        val outputFile = File(downloadDir, "$youtubeId.m4a")
        val tempFile = File(downloadDir, "$youtubeId.m4a.part")
        try {
            // Remove stale files left by an interrupted download before retrying.
            tempFile.delete()
            outputFile.delete()

            // Register as actively downloading
            progressMap[youtubeId] = 0
            downloadingTracksMap[youtubeId] = track
            updateProgressFlow()

            // 1. Resolve direct audio stream URL
            val urlResult = NewPipeExtractorHelper.resolveStreamUrl(youtubeId)
            if (urlResult.isFailure) {
                Log.e("DownloadHelper", "Failed to resolve stream URL: ${urlResult.exceptionOrNull()?.message}")
                removeDownloadState(youtubeId)
                return false
            }

            val streamUrl = urlResult.getOrThrow()

            // 2. Download file to local storage with real-time percentage tracking
            val request = Request.Builder()
                .url(streamUrl)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()

            var contentLength = -1L
            var totalRead = 0L
            val call = httpClient.newCall(request)
            activeCalls[youtubeId] = call
            currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
                if (cause is CancellationException) call.cancel()
            }
            try {
                call.execute().use { response ->
                    val body = response.body
                    if (!response.isSuccessful || body == null) {
                        removeDownloadState(youtubeId)
                        return false
                    }

                    contentLength = body.contentLength()
                    body.byteStream().use { input ->
                        FileOutputStream(tempFile).use { output ->
                            val buffer = ByteArray(8192)
                            var bytesRead: Int

                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
                                totalRead += bytesRead

                                if (contentLength > 0) {
                                    val percent = ((totalRead * 100) / contentLength).toInt().coerceIn(0, 99)
                                    if (percent != progressMap[youtubeId]) {
                                        progressMap[youtubeId] = percent
                                        updateProgressFlow()
                                    }
                                }
                            }
                        }
                    }
                }
            } finally {
                activeCalls.remove(youtubeId, call)
            }

            if (!tempFile.isFile || totalRead <= 0L ||
                (contentLength > 0 && totalRead != contentLength)
            ) {
                tempFile.delete()
                removeDownloadState(youtubeId)
                return false
            }
            if (outputFile.exists() && !outputFile.delete()) {
                tempFile.delete()
                removeDownloadState(youtubeId)
                return false
            }
            if (!tempFile.renameTo(outputFile)) {
                tempFile.delete()
                removeDownloadState(youtubeId)
                return false
            }

            // 3. Mark 100% and save metadata into Room
            progressMap[youtubeId] = 100
            updateProgressFlow()

            val downloadedTrack = DownloadedTrack(
                youtubeId = youtubeId,
                title = track.title,
                artist = track.artist,
                durationSeconds = track.durationSeconds,
                thumbnailUrl = track.thumbnailUrl,
                localUri = outputFile.absolutePath,
                downloadedAt = System.currentTimeMillis()
            )
            database.downloadedTrackDao().insert(downloadedTrack)

            Log.d("DownloadHelper", "Track downloaded successfully to: ${outputFile.absolutePath}")
            removeDownloadState(youtubeId)
            return true

        } catch (e: CancellationException) {
            tempFile.delete()
            deleteUnindexedOutput(youtubeId, outputFile)
            removeDownloadState(youtubeId)
            throw e
        } catch (e: Exception) {
            Log.e("DownloadHelper", "Error downloading track", e)
            tempFile.delete()
            deleteUnindexedOutput(youtubeId, outputFile)
            removeDownloadState(youtubeId)
            if (!currentCoroutineContext().isActive) {
                throw CancellationException("Download canceled", e)
            }
            return false
        }
    }

    private suspend fun deleteUnindexedOutput(youtubeId: String, outputFile: File) {
        val savedTrack = withContext(NonCancellable) {
            runCatching { database.downloadedTrackDao().getDownloadedTrack(youtubeId) }.getOrNull()
        }
        if (savedTrack?.localUri != outputFile.absolutePath) {
            outputFile.delete()
        }
    }

    private fun cancelActiveDownload(youtubeId: String) {
        activeDownloadJobs[youtubeId]?.cancel()
        activeCalls[youtubeId]?.cancel()
        File(downloadDir, "$youtubeId.m4a.part").delete()
    }

    private fun notifyComplete(onComplete: (Boolean) -> Unit, success: Boolean) {
        runCatching { onComplete(success) }
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
