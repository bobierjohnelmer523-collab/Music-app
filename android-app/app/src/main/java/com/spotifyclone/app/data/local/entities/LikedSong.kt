package com.spotifyclone.app.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "liked_songs")
data class LikedSong(
    @PrimaryKey val youtubeId: String,
    val title: String,
    val artist: String?,
    val durationSeconds: Long?,
    val thumbnailUrl: String?,
    val likedAt: Long = System.currentTimeMillis()
)
