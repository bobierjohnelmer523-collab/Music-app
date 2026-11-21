package com.spotifyclone.app.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.spotifyclone.app.R
import com.spotifyclone.app.databinding.ItemArtistCardBinding
import com.spotifyclone.app.model.ArtistDto

class ArtistAdapter(
    private val artists: List<ArtistDto>,
    private val onArtistClick: (ArtistDto) -> Unit
) : RecyclerView.Adapter<ArtistAdapter.ArtistViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ArtistViewHolder {
        val binding = ItemArtistCardBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ArtistViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ArtistViewHolder, position: Int) {
        holder.bind(artists[position])
    }

    override fun getItemCount(): Int = artists.size

    inner class ArtistViewHolder(private val binding: ItemArtistCardBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(artist: ArtistDto) {
            binding.textArtistName.text = artist.name
            if (!artist.avatarUrl.isNullOrEmpty()) {
                Glide.with(binding.root.context)
                    .load(artist.avatarUrl)
                    .placeholder(R.drawable.ic_person)
                    .error(R.drawable.ic_person)
                    .circleCrop()
                    .into(binding.imgArtistAvatar)
            } else {
                binding.imgArtistAvatar.setImageResource(R.drawable.ic_person)
            }
            binding.root.setOnClickListener { onArtistClick(artist) }
        }
    }
}
