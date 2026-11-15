package com.spotifyclone.app.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.spotifyclone.app.databinding.ItemSearchSuggestionBinding

class SearchSuggestionAdapter(
    private var suggestions: List<String>,
    private val onSuggestionClick: (String) -> Unit
) : RecyclerView.Adapter<SearchSuggestionAdapter.SuggestionViewHolder>() {

    fun updateSuggestions(newSuggestions: List<String>) {
        this.suggestions = newSuggestions
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SuggestionViewHolder {
        val binding = ItemSearchSuggestionBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return SuggestionViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SuggestionViewHolder, position: Int) {
        val item = suggestions[position]
        holder.binding.textSuggestionQuery.text = item
        holder.binding.root.setOnClickListener {
            onSuggestionClick(item)
        }
    }

    override fun getItemCount(): Int = suggestions.size

    class SuggestionViewHolder(val binding: ItemSearchSuggestionBinding) :
        RecyclerView.ViewHolder(binding.root)
}
