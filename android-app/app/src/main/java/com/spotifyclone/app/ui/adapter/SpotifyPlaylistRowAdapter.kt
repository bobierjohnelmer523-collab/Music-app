package com.spotifyclone.app.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.spotifyclone.app.R
import com.spotifyclone.app.databinding.ItemSpotifyChartBinding
import com.spotifyclone.app.model.SpotifyPlaylistSummary
import com.spotifyclone.app.spotify.coverUrl

/** Horizontal row of Spotify playlist cards (search results / imports). */
class SpotifyPlaylistRowAdapter(
    private val playlists: List<SpotifyPlaylistSummary>,
    private val onPlaylistClick: (SpotifyPlaylistSummary) -> Unit
) : RecyclerView.Adapter<SpotifyPlaylistRowAdapter.PlaylistViewHolder>() {

    private var items: List<SpotifyPlaylistSummary> = playlists

    fun setItems(newItems: List<SpotifyPlaylistSummary>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaylistViewHolder {
        val binding = ItemSpotifyChartBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return PlaylistViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PlaylistViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class PlaylistViewHolder(private val binding: ItemSpotifyChartBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(playlist: SpotifyPlaylistSummary) {
            binding.textChartTitle.text = playlist.name
            val count = playlist.tracks?.total
            binding.textChartSubtitle.text = "Spotify Playlist" +
                (count?.let { " • $it songs" } ?: "")

            val cover = playlist.coverUrl()
            if (!cover.isNullOrEmpty()) {
                Glide.with(binding.root.context)
                    .load(cover)
                    .placeholder(R.drawable.ic_library)
                    .error(R.drawable.ic_library)
                    .centerCrop()
                    .into(binding.imgChartCover)
            } else {
                binding.imgChartCover.setImageResource(R.drawable.ic_library)
            }

            binding.root.setOnClickListener { onPlaylistClick(playlist) }
        }
    }
}
