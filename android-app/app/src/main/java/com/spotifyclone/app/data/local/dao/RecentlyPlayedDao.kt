package com.spotifyclone.app.data.local.dao

import androidx.room.*
import com.spotifyclone.app.data.local.entities.RecentlyPlayed
import kotlinx.coroutines.flow.Flow

@Dao
interface RecentlyPlayedDao {

    @Query("""
        SELECT r.* FROM recently_played r
        INNER JOIN (
            SELECT title, MAX(timestamp) as max_time 
            FROM recently_played 
            GROUP BY title
        ) latest ON r.title = latest.title AND r.timestamp = latest.max_time
        ORDER BY r.timestamp DESC 
        LIMIT 8
    """)
    fun getRecentItems(): Flow<List<RecentlyPlayed>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: RecentlyPlayed)

    @Query("DELETE FROM recently_played WHERE dataId = :dataId OR title = :title")
    suspend fun deleteByDataIdOrTitle(dataId: String, title: String)

    @Transaction
    suspend fun recordRecentlyPlayed(item: RecentlyPlayed) {
        deleteByDataIdOrTitle(item.dataId, item.title)
        insert(item.copy(timestamp = System.currentTimeMillis()))
    }

    @Query("DELETE FROM recently_played WHERE dataId = :dataId")
    suspend fun deleteByDataId(dataId: String)
}
