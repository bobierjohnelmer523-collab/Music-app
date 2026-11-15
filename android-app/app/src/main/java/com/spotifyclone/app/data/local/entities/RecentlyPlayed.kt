package com.spotifyclone.app.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "recently_played")
data class RecentlyPlayed(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val subtitle: String?,
    val thumbnailUrl: String?,
    val type: String, // "track" or "playlist"
    val dataId: String, // youtubeId or playlistId
    val timestamp: Long = System.currentTimeMillis()
)
