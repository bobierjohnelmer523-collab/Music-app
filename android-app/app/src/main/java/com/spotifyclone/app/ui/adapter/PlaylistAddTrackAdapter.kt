package com.spotifyclone.app.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.spotifyclone.app.R
import com.spotifyclone.app.databinding.ItemAddPlaylistTrackBinding
import com.spotifyclone.app.model.TrackDto

class PlaylistAddTrackAdapter(
    private val onAddTrack: (TrackDto, (Boolean) -> Unit) -> Unit
) : RecyclerView.Adapter<PlaylistAddTrackAdapter.TrackViewHolder>() {

    private var tracks: List<TrackDto> = emptyList()
    private val addedIds = mutableSetOf<String>()
    private val addingIds = mutableSetOf<String>()

    fun submitTracks(newTracks: List<TrackDto>) {
        tracks = newTracks
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TrackViewHolder {
        val binding = ItemAddPlaylistTrackBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return TrackViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TrackViewHolder, position: Int) = holder.bind(tracks[position])

    override fun getItemCount(): Int = tracks.size

    inner class TrackViewHolder(private val binding: ItemAddPlaylistTrackBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(track: TrackDto) {
            binding.textTrackTitle.text = track.title
            binding.textTrackArtist.text = track.artist.orEmpty()
            TrackArtwork.load(binding.imgTrackCover, track)

            val alreadyAdded = track.youtubeId in addedIds
            val isAdding = track.youtubeId in addingIds
            binding.btnAddTrack.isEnabled = !alreadyAdded && !isAdding
            binding.btnAddTrack.setImageResource(
                if (alreadyAdded) R.drawable.ic_circle_checked else R.drawable.ic_add_circle_outline
            )
            binding.btnAddTrack.imageTintList = android.content.res.ColorStateList.valueOf(
                binding.root.context.getColor(
                    if (alreadyAdded) R.color.spotify_green else R.color.spotify_white
                )
            )
            binding.btnAddTrack.setOnClickListener {
                if (track.youtubeId in addingIds || track.youtubeId in addedIds) return@setOnClickListener
                addingIds.add(track.youtubeId)
                binding.btnAddTrack.isEnabled = false
                onAddTrack(track) { added ->
                    addingIds.remove(track.youtubeId)
                    if (added) addedIds.add(track.youtubeId)
                    val position = bindingAdapterPosition
                    if (position != RecyclerView.NO_POSITION) {
                        notifyItemChanged(position)
                    }
                }
            }
        }
    }
}
