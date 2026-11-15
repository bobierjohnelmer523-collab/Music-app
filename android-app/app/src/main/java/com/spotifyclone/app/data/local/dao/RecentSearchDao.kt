package com.spotifyclone.app.data.local.dao

import androidx.room.*
import com.spotifyclone.app.data.local.entities.RecentSearch
import kotlinx.coroutines.flow.Flow

@Dao
interface RecentSearchDao {

    @Query("SELECT * FROM recent_searches ORDER BY timestamp DESC LIMIT 20")
    fun getAllRecentSearches(): Flow<List<RecentSearch>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSearch(search: RecentSearch)

    @Query("DELETE FROM recent_searches WHERE query = :query")
    suspend fun deleteSearch(query: String)

    @Query("DELETE FROM recent_searches")
    suspend fun clearAll()
}
