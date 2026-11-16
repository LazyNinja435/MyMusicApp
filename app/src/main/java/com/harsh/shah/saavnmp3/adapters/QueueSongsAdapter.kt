package com.harsh.shah.saavnmp3.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.card.MaterialCardView
import com.harsh.shah.saavnmp3.R
import com.harsh.shah.saavnmp3.utils.PlaybackQueue
import com.harsh.shah.saavnmp3.utils.QueueTrack
import com.harsh.shah.saavnmp3.utils.ThemeManager

internal class QueueSongsAdapter(
    private val onPlay: (PlaybackQueue.Entry) -> Unit,
    private val onRemove: (PlaybackQueue.Entry) -> Unit,
    private val onMove: (PlaybackQueue.Entry, PlaybackQueue.Entry) -> Boolean
) : RecyclerView.Adapter<QueueSongsAdapter.ViewHolder>() {
    data class Row(
        val entry: PlaybackQueue.Entry,
        val track: QueueTrack,
        val current: Boolean,
        val editable: Boolean,
        val showRecommendationsHeading: Boolean
    )

    private var rows: List<Row> = emptyList()

    init {
        setHasStableIds(true)
    }

    fun submit(newRows: List<Row>) {
        val oldRows = rows
        if (oldRows == newRows) return
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = oldRows.size
            override fun getNewListSize() = newRows.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                oldRows[oldItemPosition].entry == newRows[newItemPosition].entry
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                oldRows[oldItemPosition] == newRows[newItemPosition]
        })
        rows = newRows
        diff.dispatchUpdatesTo(this)
    }

    fun attachDragHelper(recyclerView: RecyclerView) {
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun getDragDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int =
                if (rows.getOrNull(viewHolder.bindingAdapterPosition)?.editable == true) {
                    super.getDragDirs(recyclerView, viewHolder)
                } else {
                    0
                }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = rows.getOrNull(viewHolder.bindingAdapterPosition) ?: return false
                val to = rows.getOrNull(target.bindingAdapterPosition) ?: return false
                return onMove(from.entry, to.entry)
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit
        }).attachToRecyclerView(recyclerView)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder = ViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.queue_song_item, parent, false)
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val row = rows[position]
        val context = holder.itemView.context
        holder.heading.isVisible = row.showRecommendationsHeading
        holder.title.text = row.track.title?.takeIf(String::isNotBlank) ?: context.getString(R.string.queue_track)
        holder.artist.text = row.track.artist
        holder.artist.isVisible = !row.track.artist.isNullOrBlank()
        holder.title.isSelected = true
        holder.artist.isSelected = true
        holder.title.setTextColor(
            if (row.current) ThemeManager.getOnPrimaryColor(context)
            else ContextCompat.getColor(context, R.color.textMain)
        )
        holder.artist.setTextColor(
            if (row.current) ThemeManager.getOnPrimaryColor(context)
            else ContextCompat.getColor(context, R.color.textSec)
        )
        holder.card.setCardBackgroundColor(
            if (row.current) ThemeManager.getPrimaryColor(context) else android.graphics.Color.TRANSPARENT
        )
        ViewCompat.setStateDescription(holder.card, if (row.current) context.getString(R.string.queue_current_track) else null)

        Glide.with(holder.cover).load(row.track.artwork)
            .placeholder(R.drawable.baseline_album_24)
            .error(R.drawable.baseline_album_24)
            .into(holder.cover)

        holder.remove.isVisible = row.editable
        holder.remove.setImageResource(R.drawable.baseline_clear_24)
        holder.remove.contentDescription = context.getString(R.string.queue_remove_track)
        holder.remove.setOnClickListener { onRemove(row.entry) }
        holder.card.setOnClickListener { if (!row.current) onPlay(row.entry) }
    }

    override fun getItemId(position: Int): Long = rows[position].entry.key
    override fun getItemCount(): Int = rows.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val heading: TextView = view.findViewById(R.id.queue_recommendations_heading)
        val card: MaterialCardView = view.findViewById(R.id.queue_song_card)
        val title: TextView = view.findViewById(R.id.title)
        val artist: TextView = view.findViewById(R.id.artist)
        val cover: ImageView = view.findViewById(R.id.coverImage)
        val remove: ImageView = view.findViewById(R.id.more)
    }
}
