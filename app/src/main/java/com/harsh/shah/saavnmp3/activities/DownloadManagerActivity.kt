package com.harsh.shah.saavnmp3.activities

import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.harsh.shah.saavnmp3.adapters.ActivityDownloadManagerListAdapter
import com.harsh.shah.saavnmp3.databinding.ActivityDownloadManagerBinding
import com.harsh.shah.saavnmp3.utils.ThemeManager
import com.harsh.shah.saavnmp3.utils.TrackDownloader
import kotlinx.coroutines.launch

class DownloadManagerActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        val binding = ActivityDownloadManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val widthDp = resources.configuration.screenWidthDp
        val spanCount = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE || widthDp >= 600) {
            maxOf(2, widthDp / 320)
        } else {
            1
        }
        val adapter = ActivityDownloadManagerListAdapter(lifecycleScope)
        binding.recyclerView.layoutManager = GridLayoutManager(this, spanCount)
        binding.recyclerView.adapter = adapter
        binding.recyclerView.setHasFixedSize(true)

        lifecycleScope.launch {
            TrackDownloader.downloadedTrackBatches(applicationContext).collect { batch ->
                adapter.append(batch)
                if (adapter.itemCount > 0) binding.downloadsProgress.isVisible = false
            }
            binding.downloadsProgress.isVisible = false
            binding.downloadsEmpty.isVisible = adapter.itemCount == 0
        }
        adapter.registerAdapterDataObserver(object : androidx.recyclerview.widget.RecyclerView.AdapterDataObserver() {
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
                binding.downloadsEmpty.isVisible = adapter.itemCount == 0 && !binding.downloadsProgress.isVisible
            }

            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                binding.downloadsEmpty.isVisible = false
            }
        })
    }

    fun backPress(view: View?) {
        finish()
    }
}
