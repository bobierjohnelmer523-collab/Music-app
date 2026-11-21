package com.spotifyclone.app.ui.player

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs

data class TimedLyricLine(val timeMs: Long, val text: String)

data class TrackLyrics(
    val lines: List<TimedLyricLine>,
    val isSynced: Boolean
)

object LyricsRepository {
    private const val TAG = "LyricsRepository"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val cache = ConcurrentHashMap<String, CachedLyrics>()

    suspend fun fetch(title: String, artist: String, durationSeconds: Long?): TrackLyrics? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (title.isBlank()) return@withContext null
            val cleanTitle = sanitizeTitle(title)
            val cleanArtist = sanitizeArtist(artist)

            val cacheKey = "${normalize(cleanTitle)}|${normalize(cleanArtist)}|${durationSeconds ?: 0}"
            cache[cacheKey]?.takeIf { it.expiresAtMs > System.currentTimeMillis() }?.let {
                if (it.lyrics != null) return@withContext it.lyrics
            }

            // Strategy 1: Targeted search with cleaned track_name and artist_name
            var records = queryLrclib(cleanTitle, cleanArtist, isExactQuery = true)

            // Strategy 2: If no matches, fallback to general free-text search `q=cleanTitle cleanArtist`
            if (records.isEmpty() && cleanArtist.isNotBlank()) {
                records = queryLrclibFreeText("$cleanTitle $cleanArtist")
            }

            // Strategy 3: If still no matches and original title differed, search original title
            if (records.isEmpty() && title != cleanTitle) {
                records = queryLrclibFreeText(title)
            }

            val lyrics = records.asSequence()
                .mapNotNull { record -> score(record, cleanTitle, cleanArtist, durationSeconds)?.let { it to record } }
                .maxByOrNull { it.first }
                ?.second
                ?.let { parse(it.syncedLyrics, it.plainLyrics) }

            // Only cache successful non-null lyrics for 12 hours.
            // Do NOT cache nulls for long periods so transient network failures or rapid retries work.
            if (lyrics != null) {
                cache[cacheKey] = CachedLyrics(lyrics, System.currentTimeMillis() + TimeUnit.HOURS.toMillis(12))
            }

            lyrics
        }

    private fun queryLrclib(trackName: String, artistName: String, isExactQuery: Boolean): List<LyricsRecord> {
        val urlBuilder = HttpUrl.Builder()
            .scheme("https")
            .host("lrclib.net")
            .addPathSegments("api/search")

        if (trackName.isNotBlank()) urlBuilder.addQueryParameter("track_name", trackName)
        if (artistName.isNotBlank()) urlBuilder.addQueryParameter("artist_name", artistName)

        val request = Request.Builder()
            .url(urlBuilder.build())
            .header("User-Agent", "SpotifyClone/1.0 (Android lyrics preview)")
            .header("Accept", "application/json")
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) emptyList()
                else {
                    val body = response.body?.string().orEmpty()
                    gson.fromJson(body, Array<LyricsRecord>::class.java)?.toList().orEmpty()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "LRCLIB query failed: ${e.message}")
            emptyList()
        }
    }

    private fun queryLrclibFreeText(query: String): List<LyricsRecord> {
        if (query.isBlank()) return emptyList()
        val url = HttpUrl.Builder()
            .scheme("https")
            .host("lrclib.net")
            .addPathSegments("api/search")
            .addQueryParameter("q", query)
            .build()

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "SpotifyClone/1.0 (Android lyrics preview)")
            .header("Accept", "application/json")
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) emptyList()
                else {
                    val body = response.body?.string().orEmpty()
                    gson.fromJson(body, Array<LyricsRecord>::class.java)?.toList().orEmpty()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "LRCLIB free-text query failed: ${e.message}")
            emptyList()
        }
    }

    private fun score(
        record: LyricsRecord,
        title: String,
        artist: String,
        durationSeconds: Long?
    ): Int? {
        val targetTitle = normalize(title)
        val resultTitle = normalize(record.trackName.orEmpty())
        val titleScore = when {
            targetTitle == resultTitle -> 100
            targetTitle.length >= 4 && (targetTitle.contains(resultTitle) || resultTitle.contains(targetTitle)) -> 80
            else -> return null
        }

        val targetArtist = normalize(artist)
        val resultArtist = normalize(record.artistName.orEmpty())
        val artistScore = when {
            targetArtist.isBlank() -> 20
            targetArtist == resultArtist -> 40
            targetArtist.isNotBlank() && resultArtist.isNotBlank() &&
                (targetArtist.contains(resultArtist) || resultArtist.contains(targetArtist)) -> 30
            else -> 10 // Soft penalty instead of strict rejection if title is strong
        }

        // Strongly prefer synced lyrics
        val syncBonus = if (!record.syncedLyrics.isNullOrBlank()) 50 else 0

        val durationScore = if (durationSeconds != null && record.durationSeconds != null && record.durationSeconds > 0) {
            val difference = abs(durationSeconds - record.durationSeconds.toLong())
            when {
                difference <= 5 -> 35
                difference <= 15 -> 25
                difference <= 30 -> 15
                difference <= 60 -> 5
                else -> 0
            }
        } else 0

        return titleScore + artistScore + syncBonus + durationScore
    }

    private fun sanitizeTitle(title: String): String {
        return title
            .replace(Regex("(?i)\\s*[\\[(](?:feat|ft|with)[^\\])]*[\\])]"), "") // (feat. ...)
            .replace(Regex("(?i)\\s*-\\s*(?:feat|ft|with)\\b.*"), "")
            .replace(Regex("(?i)\\s*[\\[(](?:official|music|video|audio|lyrics?|visualizer|remastered|version)[^\\])]*[\\])]"), "")
            .replace(Regex("(?i)\\s*-\\s*(?:official|music|video|audio|lyrics?|visualizer|remastered|version)\\b.*"), "")
            .trim()
    }

    private fun sanitizeArtist(artist: String): String {
        return artist
            .replace(Regex("(?i)\\s*[\\[(](?:feat|ft|with)[^\\])]*[\\])]"), "")
            .split(Regex("[,&/]|(?i)\\s+feat\\.?\\s+|\\s+ft\\.?\\s+"))
            .firstOrNull()
            ?.trim()
            ?: artist.trim()
    }

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("\\([^)]*\\)|\\[[^]]*]"), " ")
        .replace(Regex("\\b(feat(?:uring)?|ft)\\b.*$", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("[^a-z0-9]"), "")

    private fun parse(synced: String?, plain: String?): TrackLyrics? {
        if (!synced.isNullOrBlank()) {
            val timestamp = Regex("\\[(\\d{1,2}):(\\d{2})(?:[.](\\d{1,3}))?]")
            val lines = synced.lineSequence().flatMap { raw ->
                val matches = timestamp.findAll(raw).toList()
                if (matches.isEmpty()) emptySequence()
                else {
                    val text = raw.substring(matches.last().range.last + 1).trim()
                    if (text.isBlank()) emptySequence()
                    else matches.asSequence().map { match ->
                        val minutes = match.groupValues[1].toLongOrNull() ?: 0L
                        val seconds = match.groupValues[2].toLongOrNull() ?: 0L
                        val fraction = match.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
                        TimedLyricLine((minutes * 60 + seconds) * 1000 + fraction, text)
                    }
                }
            }.sortedBy { it.timeMs }.toList()
            if (lines.isNotEmpty()) return TrackLyrics(lines, isSynced = true)
        }

        val fallback = plain.orEmpty().lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .mapIndexed { index, text -> TimedLyricLine(index * 5000L, text) }
            .toList()
        return fallback.takeIf { it.isNotEmpty() }?.let { TrackLyrics(it, isSynced = false) }
    }

    private data class LyricsRecord(
        @SerializedName("trackName") val trackName: String? = null,
        @SerializedName("artistName") val artistName: String? = null,
        @SerializedName("duration") val durationSeconds: Double? = null,
        @SerializedName("syncedLyrics") val syncedLyrics: String? = null,
        @SerializedName("plainLyrics") val plainLyrics: String? = null
    )

    private data class CachedLyrics(val lyrics: TrackLyrics?, val expiresAtMs: Long)
}
