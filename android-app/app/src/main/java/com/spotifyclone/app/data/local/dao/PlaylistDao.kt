package com.spotifyclone.app.data.local.dao

import androidx.room.*
import com.spotifyclone.app.data.local.entities.LocalPlaylist
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {

    @Query("SELECT * FROM local_playlists ORDER BY updatedAt DESC")
    fun getAllPlaylists(): Flow<List<LocalPlaylist>>

    @Query("SELECT * FROM local_playlists WHERE isOfflineOnly = 1 ORDER BY updatedAt DESC")
    fun getOfflinePlaylists(): Flow<List<LocalPlaylist>>

    @Query("SELECT * FROM local_playlists WHERE id = :id LIMIT 1")
    suspend fun getPlaylistById(id: Long): LocalPlaylist?

    @Query("SELECT * FROM local_playlists WHERE remoteId = :remoteId LIMIT 1")
    suspend fun getPlaylistByRemoteId(remoteId: Long): LocalPlaylist?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(playlist: LocalPlaylist): Long

    @Update
    suspend fun update(playlist: LocalPlaylist)

    @Delete
    suspend fun delete(playlist: LocalPlaylist)

    @Query("DELETE FROM local_playlists WHERE isOfflineOnly = 0")
    suspend fun clearRemotePlaylists()
}
