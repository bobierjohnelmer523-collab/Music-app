package com.spotifyclone.app.ui.adapter

import android.graphics.Color
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

enum class LibraryItemType {
    LIKED_SONGS,
    DOWNLOADED_SONGS,
    CUSTOM_PLAYLIST,
    SPOTIFY_PLAYLIST,
    CONNECT_SPOTIFY
}

data class LibraryItem(
    val type: LibraryItemType,
    val title: String,
    val details: String,
    val coverUrl: String? = null,
    val playlistDto: PlaylistDto? = null,
    val spotifyPlaylistId: String? = null,
    val isImportedSpotifyPlaylist: Boolean = false
)

class PlaylistAdapter(
    private var items: List<LibraryItem>,
    private val onItemClick: (LibraryItem) -> Unit,
    private val onOptionsClick: (LibraryItem, View) -> Unit,
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

    fun updateItems(newItems: List<LibraryItem>) {
        this.items = newItems
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
        val item = items[position]
        if (holder is ListViewHolder) {
            holder.bind(item)
        } else if (holder is GridViewHolder) {
            holder.bind(item)
        }
    }

    override fun getItemCount(): Int = items.size

    inner class ListViewHolder(private val binding: ItemPlaylistBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: LibraryItem) {
            val context = itemView.context
            binding.textPlaylistTitle.text = item.title
            binding.textPlaylistDetails.text = item.details

            when (item.type) {
                LibraryItemType.LIKED_SONGS -> {
                    binding.imgPinIcon.visibility = View.VISIBLE
                    binding.btnPlaylistOptionsMenu.visibility = View.GONE
                    binding.cardPlaylistCover.setBackgroundResource(R.drawable.bg_liked_songs_gradient)
                    binding.imgPlaylistCover.setBackgroundResource(R.drawable.bg_liked_songs_gradient)
                    binding.imgPlaylistCover.setImageResource(R.drawable.ic_favorite)
                    binding.imgPlaylistCover.scaleType = ImageView.ScaleType.CENTER
                    binding.imgPlaylistCover.imageTintList = null
                    binding.imgPlaylistCover.setColorFilter(Color.WHITE)
                }
                LibraryItemType.DOWNLOADED_SONGS -> {
                    binding.imgPinIcon.visibility = View.VISIBLE
                    binding.btnPlaylistOptionsMenu.visibility = View.GONE
                    binding.cardPlaylistCover.setBackgroundResource(R.drawable.bg_downloaded_songs_tile)
                    binding.imgPlaylistCover.setBackgroundResource(R.drawable.bg_downloaded_songs_tile)
                    binding.imgPlaylistCover.setImageResource(R.drawable.ic_download)
                    binding.imgPlaylistCover.scaleType = ImageView.ScaleType.CENTER
                    binding.imgPlaylistCover.imageTintList = null
                    binding.imgPlaylistCover.setColorFilter(Color.WHITE)
                }
                LibraryItemType.CUSTOM_PLAYLIST, LibraryItemType.SPOTIFY_PLAYLIST -> {
                    binding.imgPinIcon.visibility = View.GONE
                    binding.btnPlaylistOptionsMenu.visibility =
                        if (item.type == LibraryItemType.CUSTOM_PLAYLIST || item.isImportedSpotifyPlaylist) View.VISIBLE else View.GONE
                    binding.cardPlaylistCover.setBackgroundColor(context.getColor(R.color.spotify_card_grey))
                    binding.imgPlaylistCover.background = null
                    binding.imgPlaylistCover.scaleType = ImageView.ScaleType.CENTER_CROP

                    val cover = item.coverUrl ?: item.playlistDto?.tracks?.firstOrNull()?.thumbnailUrl
                    if (!cover.isNullOrEmpty()) {
                        binding.imgPlaylistCover.imageTintList = null
                        binding.imgPlaylistCover.clearColorFilter()
                        Glide.with(context)
                            .load(cover)
                            .override(512, 512)
                            .placeholder(R.drawable.ic_library)
                            .error(R.drawable.ic_library)
                            .centerCrop()
                            .into(binding.imgPlaylistCover)
                    } else {
                        binding.imgPlaylistCover.setImageResource(R.drawable.ic_library)
                        binding.imgPlaylistCover.setColorFilter(context.getColor(R.color.spotify_green))
                    }
                }
                LibraryItemType.CONNECT_SPOTIFY -> {
                    binding.imgPinIcon.visibility = View.GONE
                    binding.btnPlaylistOptionsMenu.visibility = View.GONE
                    binding.cardPlaylistCover.setBackgroundColor(context.getColor(R.color.spotify_card_grey))
                    binding.imgPlaylistCover.background = null
                    binding.imgPlaylistCover.scaleType = ImageView.ScaleType.CENTER
                    binding.imgPlaylistCover.setImageResource(R.drawable.ic_music_note)
                    binding.imgPlaylistCover.imageTintList = null
                    binding.imgPlaylistCover.clearColorFilter()
                    binding.imgPlaylistCover.setColorFilter(context.getColor(R.color.spotify_green))
                }
            }

            binding.root.setOnClickListener {
                onItemClick(item)
            }

            binding.btnPlaylistOptionsMenu.setOnClickListener { view ->
                onOptionsClick(item, view)
            }
        }
    }

    inner class GridViewHolder(private val binding: ItemPlaylistGridBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: LibraryItem) {
            val context = itemView.context
            binding.btnPlaylistGridOptions.visibility =
                if (item.type == LibraryItemType.CUSTOM_PLAYLIST || item.isImportedSpotifyPlaylist) View.VISIBLE else View.GONE
            binding.textPlaylistGridTitle.text = item.title
            binding.textPlaylistGridDetails.text = item.details

            when (item.type) {
                LibraryItemType.LIKED_SONGS -> {
                    binding.imgGridPinIcon.visibility = View.VISIBLE
                    binding.cardPlaylistGridCover.setBackgroundResource(R.drawable.bg_liked_songs_gradient)
                    binding.imgPlaylistGridCover.setBackgroundResource(R.drawable.bg_liked_songs_gradient)
                    binding.imgPlaylistGridCover.setImageResource(R.drawable.ic_favorite)
                    binding.imgPlaylistGridCover.scaleType = ImageView.ScaleType.CENTER
                    binding.imgPlaylistGridCover.imageTintList = null
                    binding.imgPlaylistGridCover.setColorFilter(Color.WHITE)
                }
                LibraryItemType.DOWNLOADED_SONGS -> {
                    binding.imgGridPinIcon.visibility = View.VISIBLE
                    binding.cardPlaylistGridCover.setBackgroundResource(R.drawable.bg_downloaded_songs_tile)
                    binding.imgPlaylistGridCover.setBackgroundResource(R.drawable.bg_downloaded_songs_tile)
                    binding.imgPlaylistGridCover.setImageResource(R.drawable.ic_download)
                    binding.imgPlaylistGridCover.scaleType = ImageView.ScaleType.CENTER
                    binding.imgPlaylistGridCover.imageTintList = null
                    binding.imgPlaylistGridCover.setColorFilter(Color.WHITE)
                }
                LibraryItemType.CUSTOM_PLAYLIST, LibraryItemType.SPOTIFY_PLAYLIST -> {
                    binding.imgGridPinIcon.visibility = View.GONE
                    binding.cardPlaylistGridCover.setBackgroundColor(context.getColor(R.color.spotify_card_grey))
                    binding.imgPlaylistGridCover.background = null
                    binding.imgPlaylistGridCover.scaleType = ImageView.ScaleType.CENTER_CROP

                    val cover = item.coverUrl ?: item.playlistDto?.tracks?.firstOrNull()?.thumbnailUrl
                    if (!cover.isNullOrEmpty()) {
                        binding.imgPlaylistGridCover.imageTintList = null
                        binding.imgPlaylistGridCover.clearColorFilter()
                        Glide.with(context)
                            .load(cover)
                            .override(512, 512)
                            .placeholder(R.drawable.ic_library)
                            .error(R.drawable.ic_library)
                            .centerCrop()
                            .into(binding.imgPlaylistGridCover)
                    } else {
                        binding.imgPlaylistGridCover.setImageResource(R.drawable.ic_library)
                        binding.imgPlaylistGridCover.setColorFilter(context.getColor(R.color.spotify_green))
                    }
                }
                LibraryItemType.CONNECT_SPOTIFY -> {
                    binding.imgGridPinIcon.visibility = View.GONE
                    binding.cardPlaylistGridCover.setBackgroundColor(context.getColor(R.color.spotify_card_grey))
                    binding.imgPlaylistGridCover.background = null
                    binding.imgPlaylistGridCover.scaleType = ImageView.ScaleType.CENTER
                    binding.imgPlaylistGridCover.setImageResource(R.drawable.ic_music_note)
                    binding.imgPlaylistGridCover.imageTintList = null
                    binding.imgPlaylistGridCover.clearColorFilter()
                    binding.imgPlaylistGridCover.setColorFilter(context.getColor(R.color.spotify_green))
                }
            }

            binding.root.setOnClickListener {
                onItemClick(item)
            }

            binding.btnPlaylistGridOptions.setOnClickListener { view ->
                onOptionsClick(item, view)
            }

            binding.root.setOnLongClickListener { view ->
                if (item.playlistDto != null || item.isImportedSpotifyPlaylist) {
                    onOptionsClick(item, view)
                }
                item.playlistDto != null || item.isImportedSpotifyPlaylist
            }
        }
    }
}
