package com.spotifyclone.app.spotify

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Locally persisted list of Spotify playlists the user imported by link.
 * Works without Spotify login — public playlists are fetched through the
 * anonymous embed page when opened.
 */
object SpotifyImportStore {

    data class ImportedPlaylist(
        val id: String,
        val title: String,
        val coverUrl: String?,
        val addedAt: Long
    )

    private const val PREFS = "spotify_imports"
    private const val KEY = "playlists"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getAll(context: Context): List<ImportedPlaylist> {
        val raw = prefs(context).getString(KEY, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<ImportedPlaylist>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    ImportedPlaylist(
                        id = o.getString("id"),
                        title = o.optString("title"),
                        coverUrl = o.optString("coverUrl").takeIf { it.isNotBlank() },
                        addedAt = o.optLong("addedAt")
                    )
                )
            }
            list.sortedByDescending { it.addedAt }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun add(context: Context, playlist: ImportedPlaylist) {
        val list = getAll(context).filter { it.id != playlist.id }.toMutableList()
        list.add(0, playlist)
        save(context, list)
    }

    fun remove(context: Context, playlistId: String) {
        save(context, getAll(context).filter { it.id != playlistId })
    }

    fun contains(context: Context, playlistId: String): Boolean =
        getAll(context).any { it.id == playlistId }

    private fun save(context: Context, list: List<ImportedPlaylist>) {
        val arr = JSONArray()
        for (p in list.take(100)) {
            arr.put(
                JSONObject()
                    .put("id", p.id)
                    .put("title", p.title)
                    .put("coverUrl", p.coverUrl ?: "")
                    .put("addedAt", p.addedAt)
            )
        }
        prefs(context).edit().putString(KEY, arr.toString()).apply()
    }
}
