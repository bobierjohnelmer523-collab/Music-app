package com.spotifyclone.app.ui.adapter

import android.view.LayoutInflater
import android.view.View
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
    private val onDeleteClick: (DownloadItem) -> Unit,
    private val onSelectionChanged: (Int) -> Unit = {}
) : RecyclerView.Adapter<DownloadedTrackAdapter.DownloadedViewHolder>() {

    var isSelectionMode: Boolean = false
        private set
    val selectedIds = mutableSetOf<String>()

    fun setSelectionMode(enabled: Boolean) {
        isSelectionMode = enabled
        if (!enabled) {
            selectedIds.clear()
        }
        notifyDataSetChanged()
    }

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
                binding.imgSelectCircle.visibility = View.GONE
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

                if (isSelectionMode) {
                    binding.imgSelectCircle.visibility = View.VISIBLE
                    val isSelected = selectedIds.contains(item.youtubeId)
                    binding.imgSelectCircle.setImageResource(
                        if (isSelected) R.drawable.ic_circle_checked else R.drawable.ic_circle_unchecked
                    )
                } else {
                    binding.imgSelectCircle.visibility = View.GONE
                }
            }

            Glide.with(context)
                .load(item.thumbnailUrl)
                .placeholder(R.drawable.ic_music_note)
                .into(binding.imageTrackThumbnail)

            binding.root.setOnClickListener {
                if (isSelectionMode && !item.isDownloading) {
                    if (selectedIds.contains(item.youtubeId)) {
                        selectedIds.remove(item.youtubeId)
                    } else {
                        selectedIds.add(item.youtubeId)
                    }
                    notifyItemChanged(position)
                    onSelectionChanged(selectedIds.size)
                } else if (item.isDownloading) {
                    Toast.makeText(context, "Download in progress (${item.progressPercent}%)", Toast.LENGTH_SHORT).show()
                } else {
                    onTrackClick(item, position)
                }
            }

            binding.root.setOnLongClickListener {
                if (!item.isDownloading) {
                    if (!isSelectionMode) {
                        isSelectionMode = true
                        selectedIds.add(item.youtubeId)
                        notifyDataSetChanged()
                        onSelectionChanged(selectedIds.size)
                    }
                    true
                } else {
                    false
                }
            }

            binding.btnTrackMenu.setOnClickListener {
                onDeleteClick(item)
            }
        }
    }
}
