package com.spotifyclone.app.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "local_playlists")
data class LocalPlaylist(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val remoteId: Long? = null,
    val title: String,
    val description: String? = null,
    val isOfflineOnly: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
)
