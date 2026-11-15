package com.spotifyclone.app.playback

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.spotifyclone.app.SpotifyApplication
import com.spotifyclone.app.extractor.NewPipeExtractorHelper
import com.spotifyclone.app.extractor.SourceBrokenException
import com.spotifyclone.app.model.TrackDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class RepeatMode {
    OFF,
    PLAY_TWICE,
    CONTINUOUS,
    PLAYLIST
}

class PlaybackManager(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.Main)
    private var exoPlayer: ExoPlayer? = null

    private val _currentTrack = MutableStateFlow<TrackDto?>(null)
    val currentTrack: StateFlow<TrackDto?> = _currentTrack

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _progressMs = MutableStateFlow(0L)
    val progressMs: StateFlow<Long> = _progressMs

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs

    private val _isSourceBroken = MutableStateFlow(false)
    val isSourceBroken: StateFlow<Boolean> = _isSourceBroken

    // Smart Shuffle & Repeat
    private val _isShuffleEnabled = MutableStateFlow(false)
    val isShuffleEnabled: StateFlow<Boolean> = _isShuffleEnabled

    private val _repeatMode = MutableStateFlow(RepeatMode.OFF)
    val repeatMode: StateFlow<RepeatMode> = _repeatMode

    private var repeatCountRemaining: Int = 0
    private val playedIndices = mutableSetOf<Int>()

    private var playlist: MutableList<TrackDto> = mutableListOf()
    private var currentIndex: Int = -1

    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            exoPlayer?.let { player ->
                if (player.isPlaying) {
                    _progressMs.value = player.currentPosition
                    _durationMs.value = if (player.duration > 0) player.duration else 0L
                }
            }
            progressHandler.postDelayed(this, 500)
        }
    }

    init {
        initPlayer()
    }

    fun getPlayer(): ExoPlayer {
        if (exoPlayer == null) {
            initPlayer()
        }
        return exoPlayer!!
    }

    private fun initPlayer() {
        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(15000)
            .setAllowCrossProtocolRedirects(true)

        val mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(httpDataSourceFactory)

        // Low-latency load control: starts playback after just 1 second buffer instead of 50s!
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                15_000, // minBufferMs
                50_000, // maxBufferMs
                1_000,  // bufferForPlaybackMs (instant startup)
                2_000   // bufferForPlaybackAfterRebufferMs
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        exoPlayer = ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .build().apply {
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    _isPlaying.value = playing
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        _durationMs.value = duration
                    } else if (playbackState == Player.STATE_ENDED) {
                        handleTrackCompletion()
                    }
                }
            })
        }
        progressHandler.post(progressRunnable)
    }

    fun setShuffle(enabled: Boolean) {
        _isShuffleEnabled.value = enabled
        playedIndices.clear()
        if (currentIndex != -1) {
            playedIndices.add(currentIndex)
        }
    }

    fun setRepeatMode(mode: RepeatMode) {
        _repeatMode.value = mode
        repeatCountRemaining = if (mode == RepeatMode.PLAY_TWICE) 1 else 0
    }

    private fun handleTrackCompletion() {
        when (_repeatMode.value) {
            RepeatMode.CONTINUOUS -> {
                exoPlayer?.seekTo(0)
                exoPlayer?.play()
            }
            RepeatMode.PLAY_TWICE -> {
                if (repeatCountRemaining > 0) {
                    repeatCountRemaining--
                    exoPlayer?.seekTo(0)
                    exoPlayer?.play()
                } else {
                    repeatCountRemaining = 1
                    playNext()
                }
            }
            RepeatMode.PLAYLIST -> {
                playNext()
            }
            RepeatMode.OFF -> {
                playNext()
            }
        }
    }

    /**
     * Start playing a track list from a specific index.
     */
    fun playQueue(tracks: List<TrackDto>, startIndex: Int = 0) {
        if (tracks.isEmpty()) return
        playlist = tracks.toMutableList()
        playedIndices.clear()
        currentIndex = if (startIndex in tracks.indices) startIndex else 0
        prepareAndPlayCurrent()
    }

    fun playSingle(track: TrackDto) {
        playlist = mutableListOf(track)
        playedIndices.clear()
        currentIndex = 0
        prepareAndPlayCurrent()
    }

    private fun prepareAndPlayCurrent() {
        if (currentIndex !in playlist.indices) return
        val track = playlist[currentIndex]
        _currentTrack.value = track
        playedIndices.add(currentIndex)

        if (_repeatMode.value == RepeatMode.PLAY_TWICE) {
            repeatCountRemaining = 1
        }

        // Record in recently played (deduplicated & pushed to position 0)
        scope.launch(Dispatchers.IO) {
            SpotifyApplication.instance.database.recentlyPlayedDao().recordRecentlyPlayed(
                com.spotifyclone.app.data.local.entities.RecentlyPlayed(
                    title = track.title,
                    subtitle = track.artist ?: "Unknown Artist",
                    thumbnailUrl = track.thumbnailUrl,
                    type = "track",
                    dataId = track.youtubeId
                )
            )
        }

        scope.launch {
            try {
                // 1. Check if track is downloaded locally
                val downloaded = SpotifyApplication.instance.database
                    .downloadedTrackDao()
                    .getDownloadedTrack(track.youtubeId)

                val mediaUri: Uri = if (downloaded != null && File(downloaded.localUri).exists()) {
                    Log.d("PlaybackManager", "Playing from offline storage: ${downloaded.localUri}")
                    Uri.fromFile(File(downloaded.localUri))
                } else {
                    // 2. Resolve stream URL just-in-time
                    val resolveResult = NewPipeExtractorHelper.resolveStreamUrl(track.youtubeId)
                    if (resolveResult.isFailure) {
                        val error = resolveResult.exceptionOrNull()
                        if (error is SourceBrokenException) {
                            Log.e("PlaybackManager", "Source broken exception detected!", error)
                            _isSourceBroken.value = true
                        }
                        return@launch
                    }
                    Uri.parse(resolveResult.getOrThrow())
                }

                // 3. Play stream in ExoPlayer
                val mediaItem = MediaItem.fromUri(mediaUri)
                exoPlayer?.apply {
                    setMediaItem(mediaItem)
                    prepare()
                    play()
                }
                _isSourceBroken.value = false

                // 4. Asynchronously pre-fetch next track in background for 0ms delay on skip/next
                val nextIdx = currentIndex + 1
                if (nextIdx in playlist.indices) {
                    val nextTrack = playlist[nextIdx]
                    scope.launch(Dispatchers.IO) {
                        try {
                            NewPipeExtractorHelper.resolveStreamUrl(nextTrack.youtubeId)
                        } catch (e: Exception) {
                            // Non-fatal prefetch failure
                        }
                    }
                }

            } catch (e: Exception) {
                Log.e("PlaybackManager", "Failed to play current track", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Could not play track: ${e.message}", Toast.LENGTH_SHORT).show()
                }
                if (e is SourceBrokenException) {
                    _isSourceBroken.value = true
                }
            }
        }
    }

    fun togglePlayPause() {
        exoPlayer?.let { player ->
            if (player.isPlaying) {
                player.pause()
            } else {
                player.play()
            }
        }
    }

    fun playNext() {
        if (playlist.isEmpty()) return

        scope.launch(Dispatchers.IO) {
            val blacklistedIds = SpotifyApplication.instance.database.blacklistedTrackDao().getAllBlacklistedIds().toSet()

            withContext(Dispatchers.Main) {
                if (_isShuffleEnabled.value) {
                    // Smart Shuffle: Pick from unplayed & unblacklisted tracks
                    val unplayed = playlist.indices.filter { idx ->
                        !playedIndices.contains(idx) && !blacklistedIds.contains(playlist[idx].youtubeId)
                    }

                    if (unplayed.isNotEmpty()) {
                        currentIndex = unplayed.random()
                    } else {
                        // All played! Reset and start new cycle
                        playedIndices.clear()
                        val valid = playlist.indices.filter { idx ->
                            !blacklistedIds.contains(playlist[idx].youtubeId)
                        }
                        if (valid.isNotEmpty()) {
                            currentIndex = valid.random()
                        }
                    }
                } else {
                    // Sequential with blacklist skipping
                    var nextIndex = currentIndex + 1
                    var found = false
                    while (nextIndex < playlist.size) {
                        if (!blacklistedIds.contains(playlist[nextIndex].youtubeId)) {
                            currentIndex = nextIndex
                            found = true
                            break
                        }
                        nextIndex++
                    }

                    if (!found) {
                        if (_repeatMode.value == RepeatMode.PLAYLIST) {
                            // Loop back to start, find first unblacklisted
                            for (i in playlist.indices) {
                                if (!blacklistedIds.contains(playlist[i].youtubeId)) {
                                    currentIndex = i
                                    found = true
                                    break
                                }
                            }
                        }
                    }
                }

                prepareAndPlayCurrent()
            }
        }
    }

    fun playPrevious() {
        if (playlist.isEmpty()) return
        if (currentIndex > 0) {
            currentIndex--
            prepareAndPlayCurrent()
        } else {
            exoPlayer?.seekTo(0)
        }
    }

    fun seekTo(positionMs: Long) {
        exoPlayer?.seekTo(positionMs)
    }

    fun blacklistCurrentTrack(onCompleted: () -> Unit = {}) {
        val current = _currentTrack.value ?: return
        scope.launch(Dispatchers.IO) {
            SpotifyApplication.instance.database.blacklistedTrackDao().insert(
                com.spotifyclone.app.data.local.entities.BlacklistedTrack(
                    youtubeId = current.youtubeId,
                    title = current.title,
                    artist = current.artist
                )
            )
            withContext(Dispatchers.Main) {
                onCompleted()
                playNext()
            }
        }
    }

    fun resetSourceBrokenState() {
        _isSourceBroken.value = false
    }

    fun release() {
        progressHandler.removeCallbacks(progressRunnable)
        exoPlayer?.release()
        exoPlayer = null
    }
}
