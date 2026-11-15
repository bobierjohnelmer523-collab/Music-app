package com.spotifyclone.app.data.local.dao

import androidx.room.*
import com.spotifyclone.app.data.local.entities.OfflineFolder
import com.spotifyclone.app.data.local.entities.OfflineSongFolder
import kotlinx.coroutines.flow.Flow

@Dao
interface OfflineFolderDao {

    @Query("SELECT * FROM offline_folders ORDER BY createdAt ASC")
    fun getAllFolders(): Flow<List<OfflineFolder>>

    @Query("SELECT * FROM offline_folders ORDER BY createdAt ASC")
    suspend fun getAllFoldersOnce(): List<OfflineFolder>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFolder(folder: OfflineFolder)

    @Query("DELETE FROM offline_folders WHERE name = :name")
    suspend fun deleteFolder(name: String)

    @Query("SELECT youtubeId FROM offline_folder_songs WHERE folderName = :folderName")
    fun getTrackIdsInFolder(folderName: String): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addSongsToFolder(items: List<OfflineSongFolder>)

    @Query("DELETE FROM offline_folder_songs WHERE folderName = :folderName AND youtubeId = :youtubeId")
    suspend fun removeSongFromFolder(folderName: String, youtubeId: String)
}
