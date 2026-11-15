package com.spotifyclone.app.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "offline_folders")
data class OfflineFolder(
    @PrimaryKey val name: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "offline_folder_songs",
    primaryKeys = ["folderName", "youtubeId"]
)
data class OfflineSongFolder(
    val folderName: String,
    val youtubeId: String
)
