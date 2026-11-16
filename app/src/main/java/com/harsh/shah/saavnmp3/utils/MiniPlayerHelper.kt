package com.harsh.shah.saavnmp3.utils

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.LinearLayoutManager
import com.harsh.shah.saavnmp3.adapters.QueueSongsAdapter
import com.harsh.shah.saavnmp3.databinding.DialogQueueBinding
import com.harsh.shah.saavnmp3.R
import com.harsh.shah.saavnmp3.activities.MusicOverviewActivity
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetDialog

object MiniPlayerHelper {
    private val handler = Handler(Looper.getMainLooper())
    private var activeActivity: AppCompatActivity? = null
    private var lastLoadedImageUrl: String? = null
    private var lastBgColor: Int? = null
    
    private val updateRunnable = object : Runnable {
        override fun run() {
            val act = activeActivity
            if (act != null && !act.isFinishing && !act.isDestroyed) {
                updatePlayBar(act)
                handler.postDelayed(this, 1000)
            }
        }
    }
    
    fun initMiniPlayer(activity: AppCompatActivity) {
        val playBar = activity.findViewById<View>(R.id.play_bar_background) 
            ?: activity.findViewById<View>(R.id.mini_player) 
            ?: return
        
        playBar.setOnClickListener {
            if (!MusicPlayerManager.MUSIC_ID.isNullOrBlank()) {
                activity.startActivity(
                    Intent(activity, MusicOverviewActivity::class.java).apply {
                        putExtra("id", MusicPlayerManager.MUSIC_ID)
                        putExtra("from_mini_player", true)
                    }
                )
            }
        }
        
        activity.findViewById<View>(R.id.play_bar_play_pause_icon)?.setOnClickListener {
            MusicPlayerManager.togglePlayPause()
            updatePlayBar(activity)
        }
        
        activity.findViewById<View>(R.id.play_bar_prev_icon)?.setOnClickListener { view ->
            view.alpha = 0.5f
            view.animate().alpha(1.0f).setDuration(200).start()
            MusicPlayerManager.prevTrack()
            handler.postDelayed({ updatePlayBar(activity) }, 100)
        }
        
        activity.findViewById<View>(R.id.play_bar_next_icon)?.setOnClickListener { view ->
            view.alpha = 0.5f
            view.animate().alpha(1.0f).setDuration(200).start()
            MusicPlayerManager.nextTrack()
            handler.postDelayed({ updatePlayBar(activity) }, 100)
        }

        activity.findViewById<View>(R.id.play_bar_queue_icon)?.setOnClickListener {
            showQueueBottomSheet(activity)
        }
    }
    
    fun onActivityResume(activity: AppCompatActivity) {
        activeActivity = activity
        handler.removeCallbacks(updateRunnable)
        handler.post(updateRunnable)
    }
    
    fun onActivityPause(activity: AppCompatActivity) {
        if (activeActivity == activity) {
            activeActivity = null
            handler.removeCallbacks(updateRunnable)
        }
    }
    
    fun updatePlayBar(activity: AppCompatActivity) {
        val playBar = activity.findViewById<View>(R.id.play_bar_background) 
            ?: activity.findViewById<View>(R.id.mini_player) 
            ?: return
        
        if (MusicPlayerManager.MUSIC_ID.isNullOrBlank()) {
            playBar.visibility = View.GONE
            return
        } else {
            playBar.visibility = View.VISIBLE
        }
        
        val titleText = activity.findViewById<TextView>(R.id.play_bar_music_title)
        val descText = activity.findViewById<TextView>(R.id.play_bar_music_desc)
        val coverImage = activity.findViewById<ImageView>(R.id.play_bar_cover_image)
        val playPauseIcon = activity.findViewById<ImageView>(R.id.play_bar_play_pause_icon)
        val prevIcon = activity.findViewById<ImageView>(R.id.play_bar_prev_icon)
        val nextIcon = activity.findViewById<ImageView>(R.id.play_bar_next_icon)
        
        titleText?.text = MusicPlayerManager.MUSIC_TITLE
        descText?.text = MusicPlayerManager.MUSIC_DESCRIPTION
        
        if (coverImage != null) {
            val imageUrl = MusicPlayerManager.IMAGE_URL
            if (imageUrl != lastLoadedImageUrl || coverImage.drawable == null) {
                lastLoadedImageUrl = imageUrl
                if (!imageUrl.isNullOrBlank()) {
                    try {
                        Glide.with(coverImage)
                            .load(imageUrl)
                            .placeholder(R.drawable.baseline_album_24)
                            .error(R.drawable.baseline_album_24)
                            .into(coverImage)
                    } catch (e: Exception) {
                        Log.e("MiniPlayerHelper", "Error loading image: ${e.message}")
                        coverImage.setImageResource(R.drawable.baseline_album_24)
                    }
                } else {
                    coverImage.setImageResource(R.drawable.baseline_album_24)
                }
            }
        }

        val p = MusicPlayerManager.player
        if (p != null && playPauseIcon != null) {
            playPauseIcon.setImageResource(
                if (MusicPlayerManager.isPlayerPlaying()) R.drawable.baseline_pause_24 else R.drawable.play_arrow_24px
            )
        }

        if (lastBgColor != MusicPlayerManager.IMAGE_BG_COLOR || playBar.background == null) {
            lastBgColor = MusicPlayerManager.IMAGE_BG_COLOR
            val gradientDrawable = GradientDrawable()
            gradientDrawable.setColor(MusicPlayerManager.IMAGE_BG_COLOR)
            gradientDrawable.cornerRadius = 20f * activity.resources.displayMetrics.density
            playBar.background = gradientDrawable
        }
        
        titleText?.setTextColor(MusicPlayerManager.TEXT_ON_IMAGE_COLOR1)
        descText?.setTextColor(MusicPlayerManager.TEXT_ON_IMAGE_COLOR1)
        
        val tintList = ColorStateList.valueOf(MusicPlayerManager.TEXT_ON_IMAGE_COLOR)
        playPauseIcon?.imageTintList = tintList
        prevIcon?.imageTintList = tintList
        nextIcon?.imageTintList = tintList
        activity.findViewById<ImageView>(R.id.play_bar_queue_icon)?.imageTintList = tintList

        // Update the thin progress bar at the bottom of the mini-player
        val progressBar = activity.findViewById<android.widget.ProgressBar>(R.id.play_bar_progress)
        if (progressBar != null) {
            val p = MusicPlayerManager.player
            if (p != null && p.duration > 0) {
                progressBar.progress = ((p.currentPosition.toFloat() / p.duration) * 1000).toInt()
            } else {
                progressBar.progress = 0
            }
            // Tint the progress bar to match the current album colour
            progressBar.progressTintList = tintList
        }
    }

    fun showQueueBottomSheet(activity: AppCompatActivity) {
        val dialog = BottomSheetDialog(activity, R.style.MyBottomSheetDialogTheme)
        val binding = DialogQueueBinding.inflate(activity.layoutInflater)
        dialog.setContentView(binding.root)
        binding.queueFrame.layoutParams.height = (activity.resources.displayMetrics.heightPixels * 0.58f)
            .toInt().coerceAtMost((480 * activity.resources.displayMetrics.density).toInt())
        val layout = LinearLayoutManager(activity)
        binding.queueRecyclerView.layoutManager = layout
        val adapter = QueueSongsAdapter(
            onPlay = MusicPlayerManager::playQueueEntry,
            onRemove = MusicPlayerManager::removeQueueEntry,
            onMove = MusicPlayerManager::moveQueueEntry
        )
        binding.queueRecyclerView.adapter = adapter
        adapter.attachDragHelper(binding.queueRecyclerView)

        var lastCurrent: PlaybackQueue.Entry? = null
        var lastShuffle: Boolean? = null
        fun render() {
            val entries = MusicPlayerManager.queueEntries
            val current = MusicPlayerManager.currentQueueEntry
            val shuffle = MusicPlayerManager.player?.shuffleModeEnabled == true
            val currentIndex = entries.indexOf(current)
            val lastPlaylistTrack = entries.indexOfLast { !it.recommended }
            val recommendationsStart = lastPlaylistTrack + 1
            adapter.submit(entries.mapIndexed { index, entry ->
                QueueSongsAdapter.Row(
                    entry, entry.track, entry == current,
                    MusicPlayerManager.canEditQueueEntry(entry, index, currentIndex),
                    entry.recommended && index == recommendationsStart
                )
            })
            binding.queueEmptyTv.isVisible = entries.isEmpty()
            binding.queueLoadingRecommendations.isVisible = MusicPlayerManager.isLoadingQueueRecommendations
            binding.queueShuffle.isEnabled = entries.size > 1
            binding.queueShuffle.imageTintList = ColorStateList.valueOf(
                if (shuffle) ThemeManager.getPrimaryColor(activity)
                else ContextCompat.getColor(activity, R.color.textSec)
            )
            ViewCompat.setStateDescription(binding.queueShuffle, activity.getString(
                if (shuffle) R.string.queue_shuffle_on else R.string.queue_shuffle_off
            ))

            val visible = layout.findFirstVisibleItemPosition()..layout.findLastVisibleItemPosition()
            val shuffleChanged = lastShuffle != null && shuffle != lastShuffle
            if (currentIndex >= 0 && (shuffleChanged || (current != lastCurrent && currentIndex !in visible))) {
                binding.queueRecyclerView.scrollToPosition(currentIndex)
            }
            lastCurrent = current
            lastShuffle = shuffle
        }

        binding.queueShuffle.setOnClickListener {
            MusicPlayerManager.player?.let { player ->
                player.shuffleModeEnabled = !player.shuffleModeEnabled
            }
        }
        val listener = object : MusicPlayerManager.PlaybackListener {
            override fun onPlaybackStateUpdated() = render()
        }
        val observer = object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                MusicPlayerManager.removePlaybackListener(listener)
                dialog.dismiss()
            }
        }
        dialog.setOnDismissListener {
            MusicPlayerManager.removePlaybackListener(listener)
            activity.lifecycle.removeObserver(observer)
        }
        dialog.show()
        activity.lifecycle.addObserver(observer)
        MusicPlayerManager.addPlaybackListener(listener)
        render()
    }
}
