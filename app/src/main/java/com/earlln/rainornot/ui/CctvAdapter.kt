package com.earlln.rainornot.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.earlln.rainornot.data.CctvData
import com.earlln.rainornot.databinding.ItemCctvBinding

class CctvAdapter(
    private val onAnalyzeClick: (CctvData) -> Unit,
    private val onWatchClick: (CctvData) -> Unit,
    private val onFavoriteClick: (CctvData) -> Unit,
    private val isFavorite: (CctvData) -> Boolean,
) : ListAdapter<CctvData, CctvAdapter.ViewHolder>(DIFF) {

    var userLat = 0.0
    var userLon = 0.0

    inner class ViewHolder(private val b: ItemCctvBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: CctvData) {
            b.tvName.text = item.displayName
            b.tvType.text = buildString {
                append(item.typeLabel)
                if (item.hasCrosswalk()) append("  ⊕교차로")
            }
            b.tvDistance.text = item.formattedDistance(userLat, userLon)
            updateFavStar(item)
            b.btnAnalyze.setOnClickListener { onAnalyzeClick(item) }
            b.btnWatch.setOnClickListener { onWatchClick(item) }
            b.btnFavorite.setOnClickListener {
                onFavoriteClick(item)
                updateFavStar(item)
            }
        }

        private fun updateFavStar(item: CctvData) {
            b.btnFavorite.text = if (isFavorite(item)) "⭐" else "☆"
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemCctvBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<CctvData>() {
            override fun areItemsTheSame(a: CctvData, b: CctvData) = a.bestUrl == b.bestUrl
            override fun areContentsTheSame(a: CctvData, b: CctvData) = a == b
        }
    }
}
