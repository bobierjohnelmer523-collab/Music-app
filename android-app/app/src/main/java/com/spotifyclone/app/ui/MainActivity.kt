package com.spotifyclone.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.spotifyclone.app.R
import com.spotifyclone.app.SpotifyApplication
import com.spotifyclone.app.databinding.ActivityMainBinding
import com.spotifyclone.app.playback.MusicService
import com.spotifyclone.app.ui.downloads.DownloadsFragment
import com.spotifyclone.app.ui.home.HomeFragment
import com.spotifyclone.app.ui.library.LibraryFragment
import com.spotifyclone.app.ui.player.NowPlayingFragment
import com.spotifyclone.app.ui.search.SearchFragment
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val playbackManager get() = SpotifyApplication.instance.playbackManager
    private val updateManager get() = SpotifyApplication.instance.updateManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Start media service for background playback
        startService(Intent(this, MusicService::class.java))

        setupBottomNavigation()
        setupMiniPlayer()
        setupSourceBrokenBanner()
        observePlaybackState()

        // Check for updates on startup
        updateManager.checkForUpdates(this, isManual = false)

        if (savedInstanceState == null) {
            loadFragment(HomeFragment())
        }
    }

    private fun setupBottomNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            val fragment: Fragment = when (item.itemId) {
                R.id.navigation_home -> HomeFragment()
                R.id.navigation_search -> SearchFragment()
                R.id.navigation_library -> LibraryFragment()
                R.id.navigation_downloads -> DownloadsFragment()
                else -> HomeFragment()
            }
            loadFragment(fragment)
            true
        }
    }

    private fun loadFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
    }

    private fun setupMiniPlayer() {
        binding.miniPlayerContainer.setOnClickListener {
            val nowPlayingFragment = NowPlayingFragment()
            nowPlayingFragment.show(supportFragmentManager, "NowPlayingDialog")
        }

        binding.miniPlayerPlayPause.setOnClickListener {
            playbackManager.togglePlayPause()
        }
    }

    private fun setupSourceBrokenBanner() {
        binding.btnBannerUpdate.setOnClickListener {
            updateManager.checkForUpdates(this, isManual = true)
        }
    }

    private fun observePlaybackState() {
        lifecycleScope.launch {
            playbackManager.currentTrack.collectLatest { track ->
                if (track != null) {
                    binding.miniPlayerContainer.visibility = View.VISIBLE
                    binding.miniPlayerTitle.text = track.title
                    binding.miniPlayerArtist.text = track.artist ?: "Unknown Artist"

                    Glide.with(this@MainActivity)
                        .load(track.thumbnailUrl)
                        .placeholder(R.drawable.ic_music_note)
                        .into(binding.miniPlayerThumbnail)
                } else {
                    binding.miniPlayerContainer.visibility = View.GONE
                }
            }
        }

        lifecycleScope.launch {
            playbackManager.isPlaying.collectLatest { isPlaying ->
                binding.miniPlayerPlayPause.setImageResource(
                    if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                )
            }
        }

        lifecycleScope.launch {
            playbackManager.progressMs.collectLatest { progress ->
                val duration = playbackManager.durationMs.value
                if (duration > 0) {
                    val percent = ((progress * 100) / duration).toInt()
                    binding.miniPlayerProgress.progress = percent
                }
            }
        }

        lifecycleScope.launch {
            playbackManager.isSourceBroken.collectLatest { isBroken ->
                binding.bannerSourceBroken.visibility = if (isBroken) View.VISIBLE else View.GONE
                if (isBroken) {
                    // Automatically prompt the user for an update check
                    updateManager.checkForUpdates(this@MainActivity, isManual = false)
                }
            }
        }
    }
}
