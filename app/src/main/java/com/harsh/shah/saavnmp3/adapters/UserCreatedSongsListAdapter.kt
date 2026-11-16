package com.harsh.shah.saavnmp3.adapters

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.harsh.shah.saavnmp3.R
import com.harsh.shah.saavnmp3.activities.MusicOverviewActivity
import com.harsh.shah.saavnmp3.databinding.ActivityListSongItemBinding
import com.harsh.shah.saavnmp3.records.sharedpref.SavedLibraries.Library
import com.harsh.shah.saavnmp3.utils.MusicPlayerManager
import com.harsh.shah.saavnmp3.utils.toQueueTrack
import com.bumptech.glide.Glide
import androidx.core.net.toUri

class UserCreatedSongsListAdapter(private val data: MutableList<Library.Songs?>) :
    RecyclerView.Adapter<UserCreatedSongsListAdapter.ViewHolder?>() {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val _v = View.inflate(parent.context, R.layout.activity_list_song_item, null)
        _v.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        return ViewHolder(_v)
    }

    override fun getItemCount(): Int {
        return data.size
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.binding.title.text = data[position]!!.title
        holder.binding.artist.text = data[position]!!.description
        val imageUrl = data[position]?.image
        if (imageUrl?.isNotBlank() == true) {
            Glide.with(holder.binding.coverImage)
                .load(imageUrl)
                .placeholder(R.drawable.baseline_album_24)
                .error(R.drawable.baseline_album_24)
                .into(holder.binding.coverImage)
        } else {
            holder.binding.coverImage.setImageResource(R.drawable.baseline_album_24)
        }

        // Wire up the 3-dots menu
        val moreIcon = holder.itemView.findViewById<ImageView>(R.id.more)
        moreIcon?.setOnClickListener { v ->
            val popup = androidx.appcompat.widget.PopupMenu(v.context, v)
            popup.menu.add("Play Next")
            popup.menu.add("Add to Queue")
            popup.setOnMenuItemClickListener { menuItem ->
                when (menuItem.title) {
                    "Play Next" -> {
                        MusicPlayerManager.playNext(data.getOrNull(holder.bindingAdapterPosition)?.toQueueTrack())
                        Toast.makeText(v.context, "Song will play next", Toast.LENGTH_SHORT).show()
                        true
                    }
                    "Add to Queue" -> {
                        MusicPlayerManager.addToQueue(data.getOrNull(holder.bindingAdapterPosition)?.toQueueTrack())
                        Toast.makeText(v.context, "Song added to queue", Toast.LENGTH_SHORT).show()
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }

        holder.itemView.setOnClickListener { view: View? ->
            val selected = holder.bindingAdapterPosition
            if (selected == RecyclerView.NO_POSITION) return@setOnClickListener
            val id = MusicPlayerManager.setQueue(data.map { it?.toQueueTrack() }, selected)
                ?: return@setOnClickListener
            holder.itemView.context.startActivity(
                Intent(view!!.context, MusicOverviewActivity::class.java)
                    .putExtra("id", id)
            )
        }
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        var binding: ActivityListSongItemBinding = ActivityListSongItemBinding.bind(itemView)
    }
}
