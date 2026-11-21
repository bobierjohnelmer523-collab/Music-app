package com.spotifyclone.app.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.spotifyclone.app.R
import com.spotifyclone.app.databinding.ItemAlbumCardBinding
import com.spotifyclone.app.model.AlbumDto

class AlbumAdapter(
    private val albums: List<AlbumDto>,
    private val onAlbumClick: (AlbumDto) -> Unit
) : RecyclerView.Adapter<AlbumAdapter.AlbumViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AlbumViewHolder {
        val binding = ItemAlbumCardBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return AlbumViewHolder(binding)
    }

    override fun onBindViewHolder(holder: AlbumViewHolder, position: Int) {
        holder.bind(albums[position])
    }

    override fun getItemCount(): Int = albums.size

    inner class AlbumViewHolder(private val binding: ItemAlbumCardBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(album: AlbumDto) {
            binding.textAlbumTitle.text = album.name
            if (!album.coverUrl.isNullOrEmpty()) {
                Glide.with(binding.root.context)
                    .load(album.coverUrl)
                    .override(512, 512)
                    .placeholder(R.drawable.ic_library)
                    .error(R.drawable.ic_library)
                    .centerCrop()
                    .into(binding.imgAlbumCover)
            } else {
                binding.imgAlbumCover.setImageResource(R.drawable.ic_library)
            }
            binding.root.setOnClickListener { onAlbumClick(album) }
        }
    }
}
