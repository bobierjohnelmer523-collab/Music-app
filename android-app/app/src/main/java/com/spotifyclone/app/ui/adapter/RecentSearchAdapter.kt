package com.spotifyclone.app.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.spotifyclone.app.data.local.entities.RecentSearch
import com.spotifyclone.app.databinding.ItemRecentSearchBinding

class RecentSearchAdapter(
    private var searches: List<RecentSearch>,
    private val onSearchClick: (String) -> Unit,
    private val onDeleteClick: (String) -> Unit
) : RecyclerView.Adapter<RecentSearchAdapter.RecentSearchViewHolder>() {

    fun updateSearches(newSearches: List<RecentSearch>) {
        this.searches = newSearches
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecentSearchViewHolder {
        val binding = ItemRecentSearchBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return RecentSearchViewHolder(binding)
    }

    override fun onBindViewHolder(holder: RecentSearchViewHolder, position: Int) {
        val item = searches[position]
        holder.binding.textRecentSearchQuery.text = item.query

        holder.binding.root.setOnClickListener {
            onSearchClick(item.query)
        }

        holder.binding.btnRemoveRecentSearch.setOnClickListener {
            onDeleteClick(item.query)
        }
    }

    override fun getItemCount(): Int = searches.size

    class RecentSearchViewHolder(val binding: ItemRecentSearchBinding) :
        RecyclerView.ViewHolder(binding.root)
}
