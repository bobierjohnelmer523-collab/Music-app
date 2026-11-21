package com.spotifyclone.app.playback

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.spotifyclone.app.SpotifyApplication
import com.spotifyclone.app.extractor.NewPipeExtractorHelper
import com.spotifyclone.app.extractor.SourceBrokenException
import com.spotifyclone.app.model.TrackDto
import com.spotifyclone.app.data.local.entities.DownloadedTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
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

    private var currentPlayJob: Job? = null
    private var repeatCountRemaining: Int = 0
    private val playedIndices = mutableSetOf<Int>()

    private var playlist: MutableList<TrackDto> = mutableListOf()
    private var offlineQueueLocalUris: Map<String, String>? = null
    private var currentIndex: Int = -1
    private var isPlaylistIsolated: Boolean = false

    fun isIsolatedPlaylistMode(): Boolean = isPlaylistIsolated

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

        // DefaultDataSource routes file:// URIs to local storage and HTTP(S) to
        // the configured network source, so downloaded tracks work offline.
        val mediaSourceFactory = DefaultMediaSourceFactory(
            DefaultDataSource.Factory(context, httpDataSourceFactory)
        )

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
    fun playQueue(tracks: List<TrackDto>, startIndex: Int = 0, isIsolated: Boolean = false) {
        startQueue(tracks, startIndex, isIsolated, null)
    }

    fun playDownloadedQueue(tracks: List<DownloadedTrack>, startIndex: Int = 0) {
        if (tracks.isEmpty()) return
        val trackDtos = tracks.map { track ->
            TrackDto(
                youtubeId = track.youtubeId,
                title = track.title,
                artist = track.artist,
                durationSeconds = track.durationSeconds,
                thumbnailUrl = track.thumbnailUrl
            )
        }
        val localUris = tracks.associate { it.youtubeId to it.localUri }
        startQueue(trackDtos, startIndex, true, localUris)
    }

    private fun startQueue(
        tracks: List<TrackDto>,
        startIndex: Int,
        isIsolated: Boolean,
        offlineLocalUris: Map<String, String>?
    ) {
        if (tracks.isEmpty()) return
        isPlaylistIsolated = isIsolated
        offlineQueueLocalUris = offlineLocalUris
        playlist = tracks.toMutableList()
        playedIndices.clear()
        currentIndex = if (startIndex in tracks.indices) startIndex else 0
        prepareAndPlayCurrent()
    }

    /**
     * Start playing a playlist whose playback is isolated to only its tracks.
     */
    fun playPlaylistQueue(tracks: List<TrackDto>, startIndex: Int = 0) {
        playQueue(tracks, startIndex, isIsolated = true)
    }

    fun playSingle(track: TrackDto) {
        offlineQueueLocalUris = null
        isPlaylistIsolated = false
        playlist = mutableListOf(track)
        playedIndices.clear()
        currentIndex = 0
        prepareAndPlayCurrent()
    }

    fun addToQueue(track: TrackDto) {
        offlineQueueLocalUris = null
        if (playlist.isEmpty()) {
            playSingle(track)
        } else {
            playlist.add(track)
        }
    }

    private fun prepareAndPlayCurrent() {
        if (currentIndex !in playlist.indices) return
        val track = playlist[currentIndex]

        // 1. Cancel any active playback preparation job immediately
        currentPlayJob?.cancel()

        // 2. Immediately stop/pause previous audio to eliminate overlapping background playback
        exoPlayer?.pause()
        exoPlayer?.clearMediaItems()

        // 3. Reset playback position and state immediately
        _progressMs.value = 0L
        _durationMs.value = (track.durationSeconds ?: 0) * 1000L
        _isPlaying.value = false

        // 4. Update current track immediately for instant UI feedback
        _currentTrack.value = track
        playedIndices.add(currentIndex)

        if (_repeatMode.value == RepeatMode.PLAY_TWICE) {
            repeatCountRemaining = 1
        }

        // Record in recently played async
        scope.launch(Dispatchers.IO) {
            try {
                SpotifyApplication.instance.database.recentlyPlayedDao().recordRecentlyPlayed(
                    com.spotifyclone.app.data.local.entities.RecentlyPlayed(
                        title = track.title,
                        subtitle = track.artist ?: "Unknown Artist",
                        thumbnailUrl = track.thumbnailUrl,
                        type = "track",
                        dataId = if (track.youtubeId.isNotBlank()) track.youtubeId else (track.spotifyId ?: track.title)
                    )
                )
            } catch (e: Exception) {
                // History is optional; a database write must not terminate playback.
                Log.w("PlaybackManager", "Could not record recently played track", e)
            }
        }

        currentPlayJob = scope.launch {
            try {
                var currentTrackDto = track
                // Spotify-sourced track: resolve YouTube Music audio match
                if (currentTrackDto.youtubeId.isBlank()) {
                    val resolved = com.spotifyclone.app.spotify.SpotifyTrackResolver.resolve(currentTrackDto)
                    if (resolved != null) {
                        currentTrackDto = resolved
                        if (currentIndex in playlist.indices) {
                            playlist[currentIndex] = resolved
                        }
                        _currentTrack.value = resolved
                    } else {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                context,
                                "Couldn't find \"${track.title}\" on YouTube Music",
                                Toast.LENGTH_SHORT
                            ).show()
                            playNext()
                        }
                        return@launch
                    }
                }

                if (!isActive) return@launch

                // 1. Check if track is downloaded locally
                val offlineUris = offlineQueueLocalUris
                val mediaUri: Uri = if (offlineUris != null) {
                    val localPath = offlineUris[currentTrackDto.youtubeId]
                    val localFile = localPath?.let(::File)
                    if (localFile == null || !localFile.isFile || localFile.length() <= 0L) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Downloaded file is missing. Download this song again.", Toast.LENGTH_LONG).show()
                        }
                        return@launch
                    }
                    Log.d("PlaybackManager", "Playing from offline playlist: ${localFile.absolutePath}")
                    Uri.fromFile(localFile)
                } else {
                    val downloaded = withContext(Dispatchers.IO) {
                        SpotifyApplication.instance.database
                            .downloadedTrackDao()
                            .getDownloadedTrack(currentTrackDto.youtubeId)
                    }
                    if (downloaded != null && File(downloaded.localUri).let { it.isFile && it.length() > 0L }) {
                        Uri.fromFile(File(downloaded.localUri))
                    } else {
                        // 2. Resolve stream URL just-in-time for tracks not downloaded locally.
                        val resolveResult = NewPipeExtractorHelper.resolveStreamUrl(currentTrackDto.youtubeId)
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
                }

                if (!isActive) return@launch

                // 3. Play stream in ExoPlayer
                val mediaItem = MediaItem.fromUri(mediaUri)
                exoPlayer?.apply {
                    setMediaItem(mediaItem)
                    prepare()
                    play()
                }
                _isSourceBroken.value = false

                // 4. Asynchronously pre-fetch next tracks in background for instant playback on skip/next
                val nextIdx = currentIndex + 1
                if (offlineQueueLocalUris == null && nextIdx in playlist.indices) {
                    val nextTrack = playlist[nextIdx]
                    scope.launch(Dispatchers.IO) {
                        try {
                            val targetId = if (nextTrack.youtubeId.isBlank()) {
                                val resolvedNext = com.spotifyclone.app.spotify.SpotifyTrackResolver.resolve(nextTrack)
                                if (resolvedNext != null) {
                                    withContext(Dispatchers.Main) {
                                        if (nextIdx in playlist.indices &&
                                            playlist[nextIdx].youtubeId.isBlank()
                                        ) {
                                            playlist[nextIdx] = resolvedNext
                                        }
                                    }
                                    resolvedNext.youtubeId
                                } else null
                            } else nextTrack.youtubeId

                            if (!targetId.isNullOrBlank()) {
                                NewPipeExtractorHelper.resolveStreamUrl(targetId)
                            }
                        } catch (e: Exception) {
                            // Non-fatal prefetch failure
                        }
                    }
                }

            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) {
                    return@launch
                }
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
                        prepareAndPlayCurrent()
                        return@withContext
                    } else if (isPlaylistIsolated) {
                        // Isolated playlist: If repeat enabled, reset and restart cycle within playlist
                        if (_repeatMode.value != RepeatMode.OFF) {
                            playedIndices.clear()
                            val valid = playlist.indices.filter { idx ->
                                !blacklistedIds.contains(playlist[idx].youtubeId)
                            }
                            if (valid.isNotEmpty()) {
                                currentIndex = valid.random()
                                prepareAndPlayCurrent()
                            }
                        }
                        return@withContext
                    }
                } else {
                    // Sequential playback with blacklist skipping
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

                    if (found) {
                        prepareAndPlayCurrent()
                        return@withContext
                    } else if (isPlaylistIsolated) {
                        // Isolated playlist reached end: loop back only if repeat enabled
                        if (_repeatMode.value == RepeatMode.PLAYLIST || _repeatMode.value == RepeatMode.CONTINUOUS) {
                            for (i in playlist.indices) {
                                if (!blacklistedIds.contains(playlist[i].youtubeId)) {
                                    currentIndex = i
                                    prepareAndPlayCurrent()
                                    return@withContext
                                }
                            }
                        }
                        return@withContext
                    }
                }

                // If NOT isolated (e.g. played from Home/Search), fetch recommendations dynamically!
                if (!isPlaylistIsolated) {
                    val current = _currentTrack.value
                    val artistQuery = current?.artist?.takeIf { it.isNotBlank() } ?: current?.title
                    if (!artistQuery.isNullOrBlank()) {
                        scope.launch(Dispatchers.IO) recommendations@{
                            val recResult = com.spotifyclone.app.extractor.NewPipeExtractorHelper.searchYouTubeMusic("$artistQuery songs")
                            if (recResult.isSuccess) {
                                val existingIds = playlist.map { it.youtubeId }.toSet()
                                val newRecs = recResult.getOrDefault(emptyList()).filter {
                                    !existingIds.contains(it.youtubeId) && !blacklistedIds.contains(it.youtubeId)
                                }
                                if (newRecs.isNotEmpty()) {
                                    withContext(Dispatchers.Main) {
                                        val startIdx = playlist.size
                                        playlist.addAll(newRecs)
                                        currentIndex = startIdx
                                        prepareAndPlayCurrent()
                                    }
                                    return@recommendations
                                }
                            }
                            // Fallback to repeat if no recommendations found
                            withContext(Dispatchers.Main) {
                                if (playlist.isNotEmpty()) {
                                    currentIndex = 0
                                    prepareAndPlayCurrent()
                                }
                            }
                        }
                    }
                }
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
