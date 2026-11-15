package com.spotifyclone.app.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.spotifyclone.app.R
import com.spotifyclone.app.databinding.ItemTrackBinding

data class DownloadItem(
    val youtubeId: String,
    val title: String,
    val artist: String?,
    val thumbnailUrl: String?,
    val durationSeconds: Long?,
    val isDownloading: Boolean = false,
    val progressPercent: Int = 100
)

class DownloadedTrackAdapter(
    private var tracks: List<DownloadItem>,
    private val onTrackClick: (DownloadItem, Int) -> Unit,
    private val onDeleteClick: (DownloadItem) -> Unit
) : RecyclerView.Adapter<DownloadedTrackAdapter.DownloadedViewHolder>() {

    fun updateTracks(newTracks: List<DownloadItem>) {
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

        fun bind(item: DownloadItem, position: Int) {
            val context = binding.root.context
            binding.textTrackTitle.text = item.title

            if (item.isDownloading) {
                binding.textTrackArtist.text = "Downloading... ${item.progressPercent}%"
                binding.textTrackArtist.setTextColor(
                    ContextCompat.getColor(context, R.color.spotify_green)
                )
                binding.btnTrackMenu.setImageResource(R.drawable.ic_close)
                binding.btnTrackMenu.setColorFilter(
                    ContextCompat.getColor(context, R.color.spotify_light_grey)
                )
            } else {
                val artistText = item.artist ?: "Unknown Artist"
                binding.textTrackArtist.text = "$artistText • Downloaded"
                binding.textTrackArtist.setTextColor(
                    ContextCompat.getColor(context, R.color.spotify_light_grey)
                )
                binding.btnTrackMenu.setImageResource(R.drawable.ic_download)
                binding.btnTrackMenu.setColorFilter(
                    ContextCompat.getColor(context, R.color.spotify_green)
                )
            }

            Glide.with(context)
                .load(item.thumbnailUrl)
                .placeholder(R.drawable.ic_music_note)
                .into(binding.imageTrackThumbnail)

            binding.root.setOnClickListener {
                if (item.isDownloading) {
                    Toast.makeText(context, "Download in progress (${item.progressPercent}%)", Toast.LENGTH_SHORT).show()
                } else {
                    onTrackClick(item, position)
                }
            }

            binding.btnTrackMenu.setOnClickListener {
                onDeleteClick(item)
            }
        }
    }
}
