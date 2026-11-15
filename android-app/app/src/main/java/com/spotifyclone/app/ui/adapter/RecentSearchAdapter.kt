package com.spotifyclone.app.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.spotifyclone.app.R
import com.spotifyclone.app.databinding.ItemRecentSearchBinding
import com.spotifyclone.app.model.TrackDto

data class RecentItemDisplay(
    val id: String,
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String? = null,
    val isQuery: Boolean = false,
    val queryText: String? = null,
    val trackDto: TrackDto? = null,
    val playlistId: String? = null,
    val playedId: Long? = null,
    val timestamp: Long
)

class RecentSearchAdapter(
    private var items: List<RecentItemDisplay>,
    private val onItemClick: (RecentItemDisplay) -> Unit,
    private val onAddToPlaylistClick: (RecentItemDisplay) -> Unit,
    private val onDeleteClick: (RecentItemDisplay) -> Unit
) : RecyclerView.Adapter<RecentSearchAdapter.RecentSearchViewHolder>() {

    fun updateItems(newItems: List<RecentItemDisplay>) {
        this.items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecentSearchViewHolder {
        val binding = ItemRecentSearchBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return RecentSearchViewHolder(binding)
    }

    override fun onBindViewHolder(holder: RecentSearchViewHolder, position: Int) {
        val item = items[position]

        holder.binding.textRecentTitle.text = item.title
        holder.binding.textRecentSubtitle.text = item.subtitle

        if (item.isQuery) {
            holder.binding.imgRecentThumbnail.visibility = View.GONE
            holder.binding.imgSearchIcon.visibility = View.VISIBLE
            holder.binding.btnRecentAddToPlaylist.visibility = View.GONE
        } else {
            holder.binding.imgSearchIcon.visibility = View.GONE
            holder.binding.imgRecentThumbnail.visibility = View.VISIBLE
            holder.binding.btnRecentAddToPlaylist.visibility = View.VISIBLE

            Glide.with(holder.itemView.context)
                .load(item.thumbnailUrl)
                .placeholder(R.drawable.ic_music_note)
                .error(R.drawable.ic_music_note)
                .centerCrop()
                .into(holder.binding.imgRecentThumbnail)
        }

        holder.binding.root.setOnClickListener {
            onItemClick(item)
        }

        holder.binding.btnRecentAddToPlaylist.setOnClickListener {
            onAddToPlaylistClick(item)
        }

        holder.binding.btnRemoveRecentSearch.setOnClickListener {
            onDeleteClick(item)
        }
    }

    override fun getItemCount(): Int = items.size

    class RecentSearchViewHolder(val binding: ItemRecentSearchBinding) :
        RecyclerView.ViewHolder(binding.root)
}
