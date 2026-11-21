package com.spotifyclone.app.ui.player

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.spotifyclone.app.R
import com.spotifyclone.app.SpotifyApplication
import com.spotifyclone.app.databinding.DialogFullscreenLyricsBinding
import com.spotifyclone.app.model.TrackDto
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class LyricsFullscreenDialogFragment : DialogFragment() {

    private var _binding: DialogFullscreenLyricsBinding? = null
    private val binding get() = _binding!!
    private val playbackManager get() = SpotifyApplication.instance.playbackManager

    private lateinit var track: TrackDto
    private var lyrics: TrackLyrics? = null
    private var activeIndex = -1
    private val lineViews = mutableListOf<TextView>()
    private var lyricsRequestId = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val args = requireArguments()
        track = TrackDto(
            youtubeId = args.getString(ARG_YOUTUBE_ID).orEmpty(),
            title = args.getString(ARG_TITLE).orEmpty(),
            artist = args.getString(ARG_ARTIST),
            durationSeconds = args.getLong(ARG_DURATION).takeIf { it > 0L },
            thumbnailUrl = null
        )
        val texts = args.getStringArrayList(ARG_LYRIC_TEXTS).orEmpty()
        val times = args.getLongArray(ARG_LYRIC_TIMES) ?: longArrayOf()
        if (texts.isNotEmpty()) {
            lyrics = TrackLyrics(
                lines = texts.mapIndexed { index, text ->
                    TimedLyricLine(times.getOrElse(index) { 0L }, text)
                },
                isSynced = args.getBoolean(ARG_IS_SYNCED)
            )
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogFullscreenLyricsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setBackgroundDrawable(ColorDrawable(BACKGROUND_COLOR))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            statusBarColor = BACKGROUND_COLOR
            navigationBarColor = BACKGROUND_COLOR
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnCloseFullscreenLyrics.setOnClickListener { dismiss() }
        binding.btnFullscreenLyricsPlayPause.setOnClickListener { playbackManager.togglePlayPause() }
        binding.btnMoreFullscreenLyrics.setOnClickListener {
            com.spotifyclone.app.ui.dialog.TrackOptionsHelper.showTrackOptions(requireContext(), track)
        }
        updateTrackHeader(track)
        lyrics?.let(::renderLyrics) ?: loadLyrics(track)
        observePlayback()
    }

    private fun observePlayback() {
        viewLifecycleOwner.lifecycleScope.launch {
            playbackManager.isPlaying.collectLatest { playing ->
                binding.btnFullscreenLyricsPlayPause.setImageResource(
                    if (playing) R.drawable.ic_pause else R.drawable.ic_play
                )
                binding.btnFullscreenLyricsPlayPause.contentDescription =
                    if (playing) "Pause" else "Play"
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            playbackManager.progressMs.collectLatest { position ->
                updateActiveLine(position)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            playbackManager.currentTrack.collectLatest { current ->
                if (current != null && current.youtubeId != track.youtubeId) {
                    track = current
                    lyrics = null
                    activeIndex = -1
                    lineViews.clear()
                    binding.layoutFullscreenLyricsLines.removeAllViews()
                    updateTrackHeader(current)
                    loadLyrics(current)
                }
            }
        }
    }

    private fun updateTrackHeader(current: TrackDto) {
        binding.textFullscreenLyricsTitle.text = current.title
        binding.textFullscreenLyricsArtist.text = current.artist ?: "Unknown Artist"
    }

    private fun loadLyrics(current: TrackDto) {
        val requestId = ++lyricsRequestId
        binding.textFullscreenLyricsStatus.visibility = View.VISIBLE
        binding.textFullscreenLyricsStatus.text = "Loading synced lyrics…"
        viewLifecycleOwner.lifecycleScope.launch {
            val result = LyricsRepository.fetch(current.title, current.artist.orEmpty(), current.durationSeconds)
            if (requestId != lyricsRequestId || playbackManager.currentTrack.value?.youtubeId != current.youtubeId) {
                return@launch
            }
            lyrics = result
            if (result == null) {
                binding.textFullscreenLyricsStatus.text = "Lyrics aren’t available for this song yet."
                return@launch
            }
            renderLyrics(result)
        }
    }

    private fun renderLyrics(currentLyrics: TrackLyrics) {
        val container = binding.layoutFullscreenLyricsLines
        container.removeAllViews()
        lineViews.clear()
        activeIndex = -1
        val verticalPadding = (10 * resources.displayMetrics.density).toInt()
        for (lyric in currentLyrics.lines) {
            val line = TextView(requireContext()).apply {
                text = lyric.text
                textSize = 29f
                maxLines = 4
                setTextColor(Color.BLACK)
                setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD))
                setPadding(0, verticalPadding, 0, verticalPadding)
            }
            container.addView(line)
            lineViews.add(line)
        }
        binding.textFullscreenLyricsStatus.visibility = if (currentLyrics.isSynced) View.GONE else View.VISIBLE
        if (!currentLyrics.isSynced) {
            binding.textFullscreenLyricsStatus.text = "Lyrics found · timing unavailable"
        }
        updateActiveLine(playbackManager.progressMs.value, force = true)
    }

    private fun updateActiveLine(positionMs: Long, force: Boolean = false) {
        val currentLyrics = lyrics ?: return
        // Advance lyric sync by 0.3s (300ms) to ensure smooth lead-in and eliminate delay
        val adjustedPositionMs = positionMs + 300L
        val index = if (currentLyrics.isSynced) {
            currentLyrics.lines.indexOfLast { it.timeMs <= adjustedPositionMs }
        } else {
            -1
        }
        if (!force && index == activeIndex) return
        activeIndex = index
        lineViews.forEachIndexed { lineIndex, line ->
            val isActive = lineIndex == index
            line.setTextColor(if (isActive) Color.WHITE else Color.BLACK)
            line.alpha = if (isActive || index < 0) 1f else 0.72f
        }
        if (index >= 0) {
            binding.scrollFullscreenLyrics.post {
                val activeLine = lineViews.getOrNull(index) ?: return@post
                val y = (activeLine.top - binding.scrollFullscreenLyrics.height / 3).coerceAtLeast(0)
                binding.scrollFullscreenLyrics.smoothScrollTo(0, y)
            }
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_YOUTUBE_ID = "lyrics_youtube_id"
        private const val ARG_TITLE = "lyrics_title"
        private const val ARG_ARTIST = "lyrics_artist"
        private const val ARG_DURATION = "lyrics_duration"
        private const val ARG_IS_SYNCED = "lyrics_is_synced"
        private const val ARG_LYRIC_TEXTS = "lyrics_texts"
        private const val ARG_LYRIC_TIMES = "lyrics_times"
        private const val BACKGROUND_COLOR = 0xFF737373.toInt()

        fun forTrack(track: TrackDto, lyrics: TrackLyrics?): LyricsFullscreenDialogFragment {
            return LyricsFullscreenDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_YOUTUBE_ID, track.youtubeId)
                    putString(ARG_TITLE, track.title)
                    putString(ARG_ARTIST, track.artist)
                    putLong(ARG_DURATION, track.durationSeconds ?: 0L)
                    putBoolean(ARG_IS_SYNCED, lyrics?.isSynced == true)
                    putStringArrayList(ARG_LYRIC_TEXTS, ArrayList(lyrics?.lines.orEmpty().map { it.text }))
                    putLongArray(ARG_LYRIC_TIMES, lyrics?.lines?.map { it.timeMs }?.toLongArray())
                }
            }
        }
    }
}
