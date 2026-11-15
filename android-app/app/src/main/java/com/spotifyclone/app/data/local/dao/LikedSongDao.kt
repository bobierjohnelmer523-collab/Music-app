package com.spotifyclone.app.data.local.dao

import androidx.room.*
import com.spotifyclone.app.data.local.entities.LikedSong
import kotlinx.coroutines.flow.Flow

@Dao
interface LikedSongDao {

    @Query("SELECT * FROM liked_songs ORDER BY likedAt DESC")
    fun getAllLikedSongs(): Flow<List<LikedSong>>

    @Query("SELECT * FROM liked_songs ORDER BY likedAt DESC")
    suspend fun getAllLikedSongsOnce(): List<LikedSong>

    @Query("SELECT COUNT(*) FROM liked_songs")
    fun getLikedCount(): Flow<Int>

    @Query("SELECT EXISTS(SELECT 1 FROM liked_songs WHERE youtubeId = :youtubeId)")
    fun isLikedFlow(youtubeId: String): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM liked_songs WHERE youtubeId = :youtubeId)")
    suspend fun isLiked(youtubeId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(song: LikedSong)

    @Query("DELETE FROM liked_songs WHERE youtubeId = :youtubeId")
    suspend fun deleteByYoutubeId(youtubeId: String)
}
