package com.spotifyclone.app.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "blacklisted_tracks")
data class BlacklistedTrack(
    @PrimaryKey val youtubeId: String,
    val title: String,
    val artist: String?,
    val blacklistedAt: Long = System.currentTimeMillis()
)
