package com.spotifyclone.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.spotifyclone.app.data.local.dao.BlacklistedTrackDao
import com.spotifyclone.app.data.local.dao.DownloadedTrackDao
import com.spotifyclone.app.data.local.dao.LikedSongDao
import com.spotifyclone.app.data.local.dao.PlaylistDao
import com.spotifyclone.app.data.local.dao.RecentlyPlayedDao
import com.spotifyclone.app.data.local.dao.TrackDao
import com.spotifyclone.app.data.local.entities.BlacklistedTrack
import com.spotifyclone.app.data.local.entities.DownloadedTrack
import com.spotifyclone.app.data.local.entities.LikedSong
import com.spotifyclone.app.data.local.entities.LocalPlaylist
import com.spotifyclone.app.data.local.entities.LocalTrack
import com.spotifyclone.app.data.local.entities.RecentlyPlayed

@Database(
    entities = [
        LocalPlaylist::class,
        LocalTrack::class,
        DownloadedTrack::class,
        BlacklistedTrack::class,
        RecentlyPlayed::class,
        LikedSong::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun playlistDao(): PlaylistDao
    abstract fun trackDao(): TrackDao
    abstract fun downloadedTrackDao(): DownloadedTrackDao
    abstract fun blacklistedTrackDao(): BlacklistedTrackDao
    abstract fun recentlyPlayedDao(): RecentlyPlayedDao
    abstract fun likedSongDao(): LikedSongDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "spotify_clone_local.db"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}
