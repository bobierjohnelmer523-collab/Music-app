package com.spotifyclone.app.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.spotifyclone.app.databinding.ItemPlaylistBinding
import com.spotifyclone.app.model.PlaylistDto

class PlaylistAdapter(
    private var playlists: List<PlaylistDto>,
    private val onPlaylistClick: (PlaylistDto) -> Unit,
    private val onDeleteClick: (PlaylistDto) -> Unit
) : RecyclerView.Adapter<PlaylistAdapter.PlaylistViewHolder>() {

    fun updatePlaylists(newPlaylists: List<PlaylistDto>) {
        this.playlists = newPlaylists
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaylistViewHolder {
        val binding = ItemPlaylistBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return PlaylistViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PlaylistViewHolder, position: Int) {
        holder.bind(playlists[position])
    }

    override fun getItemCount(): Int = playlists.size

    inner class PlaylistViewHolder(private val binding: ItemPlaylistBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(playlist: PlaylistDto) {
            binding.textPlaylistTitle.text = playlist.title
            val count = playlist.tracks.size
            binding.textPlaylistDetails.text = "Playlist • $count ${if (count == 1) "song" else "songs"}"

            binding.root.setOnClickListener {
                onPlaylistClick(playlist)
            }

            binding.btnPlaylistDelete.setOnClickListener {
                onDeleteClick(playlist)
            }
        }
    }
}
