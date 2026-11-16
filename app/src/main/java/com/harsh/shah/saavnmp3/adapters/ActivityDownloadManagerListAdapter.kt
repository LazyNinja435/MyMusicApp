package com.harsh.shah.saavnmp3.adapters

import android.content.Intent
import android.graphics.Bitmap
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.harsh.shah.saavnmp3.R
import com.harsh.shah.saavnmp3.activities.MusicOverviewActivity
import com.harsh.shah.saavnmp3.databinding.DownloadManagerMoreViewBinding
import com.harsh.shah.saavnmp3.utils.TrackDownloader
import com.harsh.shah.saavnmp3.utils.TrackDownloader.DownloadedTrack
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ActivityDownloadManagerListAdapter(private val scope: CoroutineScope) :
    RecyclerView.Adapter<ActivityDownloadManagerListAdapter.ViewHolder>() {
    private val tracks = mutableListOf<DownloadedTrack>()
    private val artworkDispatcher = Dispatchers.IO.limitedParallelism(2)

    private data class Artwork(val bitmap: Bitmap?)
    private val artworkCache = object : LruCache<File, Artwork>(8 * 1024) {
        override fun sizeOf(key: File, value: Artwork): Int =
            maxOf(1, (value.bitmap?.allocationByteCount ?: 0) / 1024)
    }

    fun append(batch: List<DownloadedTrack>) {
        if (batch.isEmpty()) return
        val start = tracks.size
        tracks.addAll(batch)
        notifyItemRangeInserted(start, batch.size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.download_manager_list_item, parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val track = tracks[position]
        holder.title.text = track.title
        holder.artist.text = track.artist
        holder.title.isSelected = true
        holder.artist.isSelected = true
        holder.artworkJob?.cancel()
        holder.artworkJob = loadArtwork(holder.cover, track.file)

        holder.itemView.setOnClickListener { view -> showDialog(track, view) }
        holder.itemView.setOnLongClickListener { view ->
            showDeleteConfirmation(view, track)
            true
        }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        holder.artworkJob?.cancel()
        holder.artworkJob = null
        holder.cover.setImageDrawable(null)
        super.onViewRecycled(holder)
    }

    private fun loadArtwork(image: ImageView, file: File?): Job? {
        image.setImageResource(R.drawable.baseline_album_24)
        if (file == null) return null
        artworkCache.get(file)?.let {
            it.bitmap?.let(image::setImageBitmap)
            return null
        }
        return scope.launch {
            val bitmap = withContext(artworkDispatcher) { TrackDownloader.readDownloadArtwork(file) }
            artworkCache.put(file, Artwork(bitmap))
            bitmap?.let(image::setImageBitmap)
        }
    }

    private fun showDialog(track: DownloadedTrack, view: View) {
        val context = view.context
        val dialog = BottomSheetDialog(context, R.style.MyBottomSheetDialogTheme)
        val binding = DownloadManagerMoreViewBinding.inflate(LayoutInflater.from(context))
        binding.songTitle.text = track.title
        binding.songSubTitle.text = track.artist
        binding.albumTitle.text = track.album
        binding.songYear.text = track.year
        binding.bitrate.text = track.bitrate + " kbps"
        binding.duration.text = track.trackLength + " Seconds"
        val artworkJob = loadArtwork(binding.coverImage, track.file)
        dialog.setOnDismissListener { artworkJob?.cancel() }
        binding.button.isVisible = !track.trackUID.isNullOrEmpty()
        binding.button.setOnClickListener {
            context.startActivity(Intent(context, MusicOverviewActivity::class.java)
                .putExtra("type", "clear").putExtra("id", track.trackUID))
            dialog.dismiss()
        }
        binding.deleteButton.setOnClickListener { showDeleteConfirmation(view, track, dialog) }
        dialog.setContentView(binding.root)
        dialog.show()
    }

    private fun showDeleteConfirmation(view: View, track: DownloadedTrack, bottomSheet: BottomSheetDialog? = null) {
        val context = view.context
        MaterialAlertDialogBuilder(context)
            .setTitle("Delete Downloaded Song")
            .setMessage("Are you sure you want to delete \"${track.title}\" from your device?")
            .setPositiveButton("Delete") { _, _ ->
                scope.launch {
                    val deleted = withContext(Dispatchers.IO) {
                        TrackDownloader.deleteDownloadedTrack(context.applicationContext, track)
                    }
                    if (deleted) {
                        bottomSheet?.dismiss()
                        track.file?.let(artworkCache::remove)
                        val position = tracks.indexOf(track)
                        if (position >= 0) {
                            tracks.removeAt(position)
                            notifyItemRemoved(position)
                        }
                    }
                    Toast.makeText(context, if (deleted) "Song deleted from device" else "Failed to delete file",
                        Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun getItemCount(): Int = tracks.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val title: TextView = itemView.findViewById(R.id.title)
        val artist: TextView = itemView.findViewById(R.id.artist)
        val cover: ImageView = itemView.findViewById(R.id.coverImage)
        var artworkJob: Job? = null
    }
}
