package com.spotifyclone.app.data.local.dao

import androidx.room.*
import com.spotifyclone.app.data.local.entities.BlacklistedTrack
import kotlinx.coroutines.flow.Flow

@Dao
interface BlacklistedTrackDao {

    @Query("SELECT youtubeId FROM blacklisted_tracks")
    suspend fun getAllBlacklistedIds(): List<String>

    @Query("SELECT EXISTS(SELECT 1 FROM blacklisted_tracks WHERE youtubeId = :youtubeId)")
    suspend fun isBlacklisted(youtubeId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(track: BlacklistedTrack)

    @Delete
    suspend fun delete(track: BlacklistedTrack)

    @Query("DELETE FROM blacklisted_tracks WHERE youtubeId = :youtubeId")
    suspend fun removeByYoutubeId(youtubeId: String)
}
