package com.spotifyclone.app.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.spotifyclone.app.databinding.ItemTrackBinding
import com.spotifyclone.app.model.TrackDto

class TrackAdapter(
    private var tracks: List<TrackDto>,
    private val onTrackClick: (TrackDto, Int) -> Unit,
    private val onMenuClick: (TrackDto) -> Unit
) : RecyclerView.Adapter<TrackAdapter.TrackViewHolder>() {

    fun updateTracks(newTracks: List<TrackDto>) {
        this.tracks = newTracks
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TrackViewHolder {
        val binding = ItemTrackBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return TrackViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TrackViewHolder, position: Int) {
        holder.bind(tracks[position], position)
    }

    override fun getItemCount(): Int = tracks.size

    inner class TrackViewHolder(private val binding: ItemTrackBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(track: TrackDto, position: Int) {
            binding.textTrackTitle.text = track.title

            val durationText = formatDuration(track.durationSeconds ?: 0)
            binding.textTrackArtist.text = if (track.artist != null) {
                "${track.artist} • $durationText"
            } else {
                durationText
            }

            TrackArtwork.load(binding.imageTrackThumbnail, track)

            binding.root.setOnClickListener {
                onTrackClick(track, position)
            }

            binding.btnTrackMenu.setOnClickListener {
                onMenuClick(track)
            }
        }

        private fun formatDuration(seconds: Long): String {
            val minutes = seconds / 60
            val remainingSec = seconds % 60
            return String.format("%d:%02d", minutes, remainingSec)
        }
    }
}
