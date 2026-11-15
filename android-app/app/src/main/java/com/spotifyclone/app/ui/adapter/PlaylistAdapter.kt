package com.spotifyclone.app.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.spotifyclone.app.R
import com.spotifyclone.app.databinding.ItemPlaylistBinding
import com.spotifyclone.app.databinding.ItemPlaylistGridBinding
import com.spotifyclone.app.model.PlaylistDto

class PlaylistAdapter(
    private var playlists: List<PlaylistDto>,
    private val onPlaylistClick: (PlaylistDto) -> Unit,
    private val onOptionsClick: (PlaylistDto, View) -> Unit,
    private var isGridView: Boolean = false
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val VIEW_TYPE_LIST = 0
        const val VIEW_TYPE_GRID = 1
    }

    fun setGridView(isGrid: Boolean) {
        this.isGridView = isGrid
        notifyDataSetChanged()
    }

    fun isGrid(): Boolean = isGridView

    fun updatePlaylists(newPlaylists: List<PlaylistDto>) {
        this.playlists = newPlaylists
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return if (isGridView) VIEW_TYPE_GRID else VIEW_TYPE_LIST
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == VIEW_TYPE_GRID) {
            val binding = ItemPlaylistGridBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            GridViewHolder(binding)
        } else {
            val binding = ItemPlaylistBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            ListViewHolder(binding)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val playlist = playlists[position]
        if (holder is ListViewHolder) {
            holder.bind(playlist)
        } else if (holder is GridViewHolder) {
            holder.bind(playlist)
        }
    }

    override fun getItemCount(): Int = playlists.size

    private fun loadCover(context: android.content.Context, playlist: PlaylistDto, imageView: ImageView) {
        val cover = playlist.coverUrl ?: playlist.tracks.firstOrNull()?.thumbnailUrl
        if (!cover.isNullOrEmpty()) {
            imageView.imageTintList = null
            Glide.with(context)
                .load(cover)
                .placeholder(R.drawable.ic_library)
                .error(R.drawable.ic_library)
                .centerCrop()
                .into(imageView)
        } else {
            imageView.setImageResource(R.drawable.ic_library)
            imageView.setColorFilter(context.getColor(R.color.spotify_green))
        }
    }

    inner class ListViewHolder(private val binding: ItemPlaylistBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(playlist: PlaylistDto) {
            binding.textPlaylistTitle.text = playlist.title
            val count = playlist.tracks.size
            binding.textPlaylistDetails.text = "Playlist • $count ${if (count == 1) "song" else "songs"}"

            loadCover(itemView.context, playlist, binding.imgPlaylistCover)

            binding.root.setOnClickListener {
                onPlaylistClick(playlist)
            }

            binding.btnPlaylistOptionsMenu.setOnClickListener { view ->
                onOptionsClick(playlist, view)
            }
        }
    }

    inner class GridViewHolder(private val binding: ItemPlaylistGridBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(playlist: PlaylistDto) {
            binding.textPlaylistGridTitle.text = playlist.title
            val count = playlist.tracks.size
            binding.textPlaylistGridDetails.text = "Playlist • $count ${if (count == 1) "song" else "songs"}"

            loadCover(itemView.context, playlist, binding.imgPlaylistGridCover)

            binding.root.setOnClickListener {
                onPlaylistClick(playlist)
            }

            binding.root.setOnLongClickListener { view ->
                onOptionsClick(playlist, view)
                true
            }
        }
    }
}
