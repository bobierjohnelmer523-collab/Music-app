package com.spotifyclone.app.playback

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import com.spotifyclone.app.data.local.AppDatabase
import com.spotifyclone.app.data.local.entities.DownloadedTrack
import com.spotifyclone.app.extractor.NewPipeExtractorHelper
import com.spotifyclone.app.model.TrackDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

class DownloadHelper(
    private val context: Context,
    private val database: AppDatabase
) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private val downloadDir: File = File(context.filesDir, "downloads").apply {
        if (!exists()) mkdirs()
    }

    private val httpClient = OkHttpClient()

    /**
     * Download track audio file directly to local storage and index in Room database.
     */
    fun downloadTrack(track: TrackDto, onComplete: (Boolean) -> Unit) {
        scope.launch {
            try {
                // Check if already downloaded
                val existing = database.downloadedTrackDao().getDownloadedTrack(track.youtubeId)
                if (existing != null && File(existing.localUri).exists()) {
                    onComplete(true)
                    return@launch
                }

                // 1. Resolve direct audio stream URL just-in-time
                val urlResult = NewPipeExtractorHelper.resolveStreamUrl(track.youtubeId)
                if (urlResult.isFailure) {
                    Log.e("DownloadHelper", "Failed to resolve stream URL: ${urlResult.exceptionOrNull()?.message}")
                    onComplete(false)
                    return@launch
                }

                val streamUrl = urlResult.getOrThrow()

                // 2. Download file to local storage
                val outputFile = File(downloadDir, "${track.youtubeId}.m4a")
                val request = Request.Builder().url(streamUrl).build()
                val response = httpClient.newCall(request).execute()

                if (!response.isSuccessful || response.body == null) {
                    onComplete(false)
                    return@launch
                }

                response.body!!.byteStream().use { input ->
                    FileOutputStream(outputFile).use { output ->
                        input.copyTo(output)
                    }
                }

                // 3. Save metadata into Room
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
                onComplete(true)

            } catch (e: Exception) {
                Log.e("DownloadHelper", "Error downloading track", e)
                onComplete(false)
            }
        }
    }

    fun deleteDownloadedTrack(youtubeId: String, onComplete: () -> Unit) {
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
