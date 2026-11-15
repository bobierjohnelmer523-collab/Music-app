package com.spotifyclone.app.data.local.dao

import androidx.room.*
import com.spotifyclone.app.data.local.entities.DownloadedTrack
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadedTrackDao {

    @Query("SELECT * FROM downloaded_tracks ORDER BY downloadedAt DESC")
    fun getAllDownloadedTracks(): Flow<List<DownloadedTrack>>

    @Query("SELECT * FROM downloaded_tracks WHERE youtubeId = :youtubeId LIMIT 1")
    suspend fun getDownloadedTrack(youtubeId: String): DownloadedTrack?

    @Query("SELECT EXISTS(SELECT 1 FROM downloaded_tracks WHERE youtubeId = :youtubeId)")
    suspend fun isDownloaded(youtubeId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(track: DownloadedTrack)

    @Delete
    suspend fun delete(track: DownloadedTrack)

    @Query("DELETE FROM downloaded_tracks WHERE youtubeId = :youtubeId")
    suspend fun deleteByYoutubeId(youtubeId: String)
}
