package com.spotifyclone.app.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "downloaded_tracks")
data class DownloadedTrack(
    @PrimaryKey val youtubeId: String,
    val title: String,
    val artist: String?,
    val durationSeconds: Long?,
    val thumbnailUrl: String?,
    val localUri: String,
    val downloadedAt: Long = System.currentTimeMillis()
)
