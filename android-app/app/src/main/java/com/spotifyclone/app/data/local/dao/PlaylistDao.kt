package com.spotifyclone.app.data.local.dao

import androidx.room.*
import com.spotifyclone.app.data.local.entities.LocalPlaylist
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {

    @Query("SELECT * FROM local_playlists ORDER BY updatedAt DESC")
    fun getAllPlaylists(): Flow<List<LocalPlaylist>>

    @Query("SELECT * FROM local_playlists ORDER BY updatedAt DESC")
    suspend fun getAllPlaylistsOnce(): List<LocalPlaylist>

    @Query("SELECT * FROM local_playlists WHERE isOfflineOnly = 1 ORDER BY updatedAt DESC")
    fun getOfflinePlaylists(): Flow<List<LocalPlaylist>>

    @Query("SELECT * FROM local_playlists WHERE id = :id LIMIT 1")
    suspend fun getPlaylistById(id: Long): LocalPlaylist?

    @Query("SELECT * FROM local_playlists WHERE remoteId = :remoteId LIMIT 1")
    suspend fun getPlaylistByRemoteId(remoteId: Long): LocalPlaylist?

    @Query("SELECT * FROM local_playlists WHERE title = :title LIMIT 1")
    suspend fun getPlaylistByTitle(title: String): LocalPlaylist?

    @Query("DELETE FROM local_playlists WHERE id = :id OR remoteId = :id OR title = :title")
    suspend fun deleteByIdOrRemoteIdOrTitle(id: Long, title: String)

    @Query("UPDATE local_playlists SET coverUrl = :coverUrl WHERE id = :id OR remoteId = :id")
    suspend fun updateCoverUrl(id: Long, coverUrl: String?)

    @Query("UPDATE local_playlists SET title = :title, description = :description, coverUrl = :coverUrl WHERE id = :id OR remoteId = :id")
    suspend fun updatePlaylistDetails(id: Long, title: String, description: String?, coverUrl: String?)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(playlist: LocalPlaylist): Long

    @Update
    suspend fun update(playlist: LocalPlaylist)

    @Delete
    suspend fun delete(playlist: LocalPlaylist)

    @Query("DELETE FROM local_playlists WHERE isOfflineOnly = 0")
    suspend fun clearRemotePlaylists()
}
