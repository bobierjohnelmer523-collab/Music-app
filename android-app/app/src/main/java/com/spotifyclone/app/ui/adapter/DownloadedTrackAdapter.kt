package com.spotifyclone.app.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.spotifyclone.app.R
import com.spotifyclone.app.data.local.entities.DownloadedTrack
import com.spotifyclone.app.databinding.ItemTrackBinding
import com.spotifyclone.app.model.TrackDto

class DownloadedTrackAdapter(
    private var tracks: List<DownloadedTrack>,
    private val onTrackClick: (DownloadedTrack, Int) -> Unit,
    private val onDeleteClick: (DownloadedTrack) -> Unit
) : RecyclerView.Adapter<DownloadedTrackAdapter.DownloadedViewHolder>() {

    fun updateTracks(newTracks: List<DownloadedTrack>) {
        this.tracks = newTracks
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DownloadedViewHolder {
        val binding = ItemTrackBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return DownloadedViewHolder(binding)
    }

    override fun onBindViewHolder(holder: DownloadedViewHolder, position: Int) {
        holder.bind(tracks[position], position)
    }

    override fun getItemCount(): Int = tracks.size

    inner class DownloadedViewHolder(private val binding: ItemTrackBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(track: DownloadedTrack, position: Int) {
            binding.textTrackTitle.text = track.title
            val artistText = track.artist ?: "Unknown Artist"
            binding.textTrackArtist.text = "$artistText • Offline Download"

            Glide.with(binding.root.context)
                .load(track.thumbnailUrl)
                .placeholder(R.drawable.ic_music_note)
                .into(binding.imageTrackThumbnail)

            binding.btnTrackMenu.setImageResource(R.drawable.ic_warning)

            binding.root.setOnClickListener {
                onTrackClick(track, position)
            }

            binding.btnTrackMenu.setOnClickListener {
                onDeleteClick(track)
            }
        }
    }
}
