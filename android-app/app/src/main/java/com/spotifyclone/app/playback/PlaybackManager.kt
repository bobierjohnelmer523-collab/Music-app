package com.spotifyclone.app.playback

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.spotifyclone.app.SpotifyApplication
import com.spotifyclone.app.extractor.NewPipeExtractorHelper
import com.spotifyclone.app.extractor.SourceBrokenException
import com.spotifyclone.app.model.TrackDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

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
        exoPlayer = ExoPlayer.Builder(context).build().apply {
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    _isPlaying.value = playing
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        _durationMs.value = duration
                    } else if (playbackState == Player.STATE_ENDED) {
                        playNext()
                    }
                }
            })
        }
        progressHandler.post(progressRunnable)
    }

    /**
     * Start playing a track list from a specific index.
     */
    fun playQueue(tracks: List<TrackDto>, startIndex: Int = 0) {
        if (tracks.isEmpty()) return
        playlist = tracks.toMutableList()
        currentIndex = if (startIndex in tracks.indices) startIndex else 0
        prepareAndPlayCurrent()
    }

    fun playSingle(track: TrackDto) {
        playlist = mutableListOf(track)
        currentIndex = 0
        prepareAndPlayCurrent()
    }

    private fun prepareAndPlayCurrent() {
        if (currentIndex !in playlist.indices) return
        val track = playlist[currentIndex]
        _currentTrack.value = track

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

            } catch (e: Exception) {
                Log.e("PlaybackManager", "Failed to play current track", e)
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
        if (currentIndex + 1 < playlist.size) {
            currentIndex++
            prepareAndPlayCurrent()
        } else {
            // Loop back or stop
            currentIndex = 0
            prepareAndPlayCurrent()
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

    fun resetSourceBrokenState() {
        _isSourceBroken.value = false
    }

    fun release() {
        progressHandler.removeCallbacks(progressRunnable)
        exoPlayer?.release()
        exoPlayer = null
    }
}
