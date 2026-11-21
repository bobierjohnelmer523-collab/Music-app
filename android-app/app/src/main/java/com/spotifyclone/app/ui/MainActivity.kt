package com.spotifyclone.app.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.spotifyclone.app.R
import com.spotifyclone.app.SpotifyApplication
import com.spotifyclone.app.databinding.ActivityMainBinding
import com.spotifyclone.app.playback.MusicService
import com.spotifyclone.app.spotify.SpotifyAuthManager
import com.spotifyclone.app.ui.downloads.DownloadsFragment
import com.spotifyclone.app.ui.home.HomeFragment
import com.spotifyclone.app.ui.library.LibraryFragment
import com.spotifyclone.app.ui.player.NowPlayingFragment
import com.spotifyclone.app.ui.search.SearchFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val playbackManager get() = SpotifyApplication.instance.playbackManager
    private val updateManager get() = SpotifyApplication.instance.updateManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupBottomNavigation()
        setupMiniPlayer()
        setupSourceBrokenBanner()
        observePlaybackState()
        observeConnectionStatus()

        // Check for updates on startup
        updateManager.checkForUpdates(this, isManual = false)

        if (savedInstanceState == null) {
            loadFragment(HomeFragment())
        }

        handleSpotifyCallback(intent)
    }

    override fun onStart() {
        super.onStart()
        // Start playback service while the activity is visible. Android can reject
        // background service starts if this activity is launched from a background task.
        try {
            startService(Intent(this, MusicService::class.java))
        } catch (e: IllegalStateException) {
            Log.w("MainActivity", "Playback service start deferred while app is backgrounded", e)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleSpotifyCallback(intent)
    }

    /** Spotify OAuth redirect: spotifyclone://callback?code=...&state=... */
    private fun handleSpotifyCallback(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "spotifyclone" || uri.host != "callback") return

        lifecycleScope.launch {
            val success = SpotifyAuthManager.handleCallback(uri)
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@MainActivity,
                    if (success) "Spotify account connected" else "Spotify login failed",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun setupBottomNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            val fragment: Fragment = when (item.itemId) {
                R.id.navigation_home -> HomeFragment()
                R.id.navigation_search -> SearchFragment()
                R.id.navigation_library -> LibraryFragment()
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

                    com.spotifyclone.app.ui.adapter.TrackArtwork.load(binding.miniPlayerThumbnail, track)
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

    private fun observeConnectionStatus() {
        val connManager = SpotifyApplication.instance.connectionManager
        lifecycleScope.launch {
            connManager.status.collectLatest { status ->
                when (status.state) {
                    com.spotifyclone.app.api.ConnectionState.IDLE -> {
                        binding.layoutConnectionStatus.animate()
                            .alpha(0f)
                            .setDuration(400)
                            .withEndAction {
                                binding.layoutConnectionStatus.visibility = View.GONE
                                binding.layoutConnectionStatus.alpha = 1f
                            }
                            .start()
                    }
                    com.spotifyclone.app.api.ConnectionState.CONNECTING -> {
                        binding.layoutConnectionStatus.visibility = View.VISIBLE
                        binding.layoutConnectionStatus.alpha = 1f
                        binding.progressConnectionStatus.visibility = View.VISIBLE
                        binding.iconConnectionStatus.visibility = View.GONE
                        binding.textConnectionStatus.text = status.message
                    }
                    com.spotifyclone.app.api.ConnectionState.CONNECTED -> {
                        binding.layoutConnectionStatus.visibility = View.VISIBLE
                        binding.layoutConnectionStatus.alpha = 1f
                        binding.progressConnectionStatus.visibility = View.GONE
                        binding.iconConnectionStatus.visibility = View.VISIBLE
                        binding.iconConnectionStatus.setColorFilter(
                            getColor(R.color.spotify_green)
                        )
                        binding.textConnectionStatus.text = status.message
                    }
                    com.spotifyclone.app.api.ConnectionState.UNREACHABLE -> {
                        binding.layoutConnectionStatus.visibility = View.VISIBLE
                        binding.layoutConnectionStatus.alpha = 1f
                        binding.progressConnectionStatus.visibility = View.GONE
                        binding.iconConnectionStatus.visibility = View.VISIBLE
                        binding.iconConnectionStatus.setColorFilter(
                            getColor(R.color.spotify_light_grey)
                        )
                        binding.textConnectionStatus.text = status.message
                    }
                }
            }
        }

        binding.btnCloseConnectionStatus.setOnClickListener {
            connManager.dismiss()
        }

        binding.layoutConnectionStatus.setOnClickListener {
            if (connManager.status.value.state == com.spotifyclone.app.api.ConnectionState.UNREACHABLE) {
                connManager.checkConnection()
            }
        }
    }
}
