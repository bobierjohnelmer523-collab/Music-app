package com.spotifyclone.app.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.spotifyclone.app.R
import com.spotifyclone.app.databinding.ItemSpotifyChartBinding
import com.spotifyclone.app.spotify.SpotifyChartsFetcher

class SpotifyChartAdapter(
    private val charts: List<SpotifyChartsFetcher.ChartDescriptor>,
    private val onChartClick: (SpotifyChartsFetcher.ChartDescriptor) -> Unit
) : RecyclerView.Adapter<SpotifyChartAdapter.ChartViewHolder>() {

    // chartId -> cover url (loaded async by the fragment)
    private val covers = mutableMapOf<String, String?>()

    fun updateCover(chartId: String, coverUrl: String?) {
        covers[chartId] = coverUrl
        val idx = charts.indexOfFirst { it.id == chartId }
        if (idx != -1) notifyItemChanged(idx)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChartViewHolder {
        val binding = ItemSpotifyChartBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ChartViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ChartViewHolder, position: Int) {
        holder.bind(charts[position])
    }

    override fun getItemCount(): Int = charts.size

    inner class ChartViewHolder(private val binding: ItemSpotifyChartBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(chart: SpotifyChartsFetcher.ChartDescriptor) {
            binding.textChartTitle.text = chart.title
            binding.textChartSubtitle.text = chart.subtitle

            val cover = covers[chart.id]
            if (!cover.isNullOrEmpty()) {
                Glide.with(binding.root.context)
                    .load(cover)
                    .placeholder(R.drawable.ic_music_note)
                    .error(R.drawable.ic_music_note)
                    .centerCrop()
                    .into(binding.imgChartCover)
            } else {
                binding.imgChartCover.setImageResource(R.drawable.ic_music_note)
            }

            binding.root.setOnClickListener { onChartClick(chart) }
        }
    }
}
