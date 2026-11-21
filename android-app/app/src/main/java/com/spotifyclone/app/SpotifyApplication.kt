package com.spotifyclone.app

import android.app.Application
import com.spotifyclone.app.data.local.AppDatabase
import com.spotifyclone.app.extractor.NewPipeExtractorHelper
import com.spotifyclone.app.playback.DownloadHelper
import com.spotifyclone.app.playback.PlaybackManager
import com.spotifyclone.app.update.AppUpdateManager

class SpotifyApplication : Application() {

    lateinit var database: AppDatabase
        private set

    lateinit var playbackManager: PlaybackManager
        private set

    lateinit var downloadHelper: DownloadHelper
        private set

    lateinit var updateManager: AppUpdateManager
        private set

    lateinit var connectionManager: com.spotifyclone.app.api.BackendConnectionManager
        private set

    companion object {
        lateinit var instance: SpotifyApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 1. Initialize NewPipe Extractor
        NewPipeExtractorHelper.init()

        // 2. Initialize Room Database
        database = AppDatabase.getDatabase(this)

        // 3. Initialize Media3 Download Manager
        downloadHelper = DownloadHelper(this, database)

        // 4. Initialize Playback Manager
        playbackManager = PlaybackManager(this)

        // 5. Initialize App Update Manager
        updateManager = AppUpdateManager(this)

        // 6. Initialize Spotify OAuth session
        com.spotifyclone.app.spotify.SpotifyAuthManager.init(this)

        // 6. Initialize Neon + Render Connection Monitor
        connectionManager = com.spotifyclone.app.api.BackendConnectionManager(this)
    }
}
