package com.spotifyclone.app.data.local.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "local_tracks",
    foreignKeys = [
        ForeignKey(
            entity = LocalPlaylist::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["playlistId"])]
)
data class LocalTrack(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val youtubeId: String,
    val title: String,
    val artist: String?,
    val durationSeconds: Long?,
    val thumbnailUrl: String?,
    val position: Int = 0
)
