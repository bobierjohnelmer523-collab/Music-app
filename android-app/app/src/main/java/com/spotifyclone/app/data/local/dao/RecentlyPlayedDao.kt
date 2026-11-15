package com.spotifyclone.app.data.local.dao

import androidx.room.*
import com.spotifyclone.app.data.local.entities.RecentlyPlayed
import kotlinx.coroutines.flow.Flow

@Dao
interface RecentlyPlayedDao {

    @Query("SELECT * FROM recently_played ORDER BY timestamp DESC LIMIT 8")
    fun getRecentItems(): Flow<List<RecentlyPlayed>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: RecentlyPlayed)

    @Query("DELETE FROM recently_played WHERE dataId = :dataId")
    suspend fun deleteByDataId(dataId: String)
}
