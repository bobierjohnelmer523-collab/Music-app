package com.spotifyclone.app.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.spotifyclone.app.R
import com.spotifyclone.app.data.local.entities.RecentlyPlayed
import com.spotifyclone.app.databinding.ItemRecentGridBinding

class RecentGridAdapter(
    private var items: List<RecentlyPlayed>,
    private val onItemClick: (RecentlyPlayed) -> Unit
) : RecyclerView.Adapter<RecentGridAdapter.RecentViewHolder>() {

    fun updateItems(newItems: List<RecentlyPlayed>) {
        this.items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecentViewHolder {
        val binding = ItemRecentGridBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return RecentViewHolder(binding)
    }

    override fun onBindViewHolder(holder: RecentViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class RecentViewHolder(private val binding: ItemRecentGridBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: RecentlyPlayed) {
            binding.textRecentTitle.text = item.title

            Glide.with(binding.root.context)
                .load(item.thumbnailUrl)
                .placeholder(R.drawable.ic_music_note)
                .error(R.drawable.ic_music_note)
                .into(binding.imageRecentArt)

            binding.root.setOnClickListener {
                onItemClick(item)
            }
        }
    }
}
