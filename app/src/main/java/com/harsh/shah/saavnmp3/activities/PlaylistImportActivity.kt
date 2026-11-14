package com.harsh.shah.saavnmp3.activities

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.gson.Gson
import com.harsh.shah.saavnmp3.R
import com.harsh.shah.saavnmp3.databinding.ActivityPlaylistImportBinding
import com.harsh.shah.saavnmp3.databinding.BottomSheetCandidatePickerBinding
import com.harsh.shah.saavnmp3.databinding.ItemYoutubeImportTrackBinding
import com.harsh.shah.saavnmp3.importer.ApiManagerJioSaavnSearch
import com.harsh.shah.saavnmp3.importer.ImportItemStatus
import com.harsh.shah.saavnmp3.importer.ImportPhase
import com.harsh.shah.saavnmp3.importer.ImportTrackItemState
import com.harsh.shah.saavnmp3.importer.JioSaavnSongMatcher
import com.harsh.shah.saavnmp3.importer.PlaylistImportCoordinator
import com.harsh.shah.saavnmp3.importer.PlaylistImportRepository
import com.harsh.shah.saavnmp3.importer.PlaylistImportService
import com.harsh.shah.saavnmp3.importer.PlaylistImportUiState
import com.harsh.shah.saavnmp3.importer.ScoredSongCandidate
import com.harsh.shah.saavnmp3.importer.SongTitleNormalizer
import com.harsh.shah.saavnmp3.importer.YouTubePlaylistClient
import com.harsh.shah.saavnmp3.model.AlbumItem
import com.harsh.shah.saavnmp3.records.SongResponse.Song
import com.harsh.shah.saavnmp3.utils.ThemeManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class PlaylistImportActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlaylistImportBinding
    private lateinit var repository: PlaylistImportRepository
    private lateinit var coordinator: PlaylistImportCoordinator
    private lateinit var adapter: ImportTracksAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityPlaylistImportBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = PlaylistImportRepository(applicationContext)
        coordinator = PlaylistImportService.getOrCreateCoordinator(applicationContext)

        adapter = ImportTracksAdapter { item ->
            showCandidatePicker(item)
        }
        binding.tracksRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.tracksRecyclerView.adapter = adapter

        binding.backBtn.setOnClickListener { finish() }

        binding.apiKeyBtn.setOnClickListener { showApiKeyDialog() }

        binding.startImportBtn.setOnClickListener {
            val url = binding.urlEditText.text?.toString().orEmpty()
            if (repository.getYouTubeApiKey().isBlank()) {
                showApiKeyDialog {
                    PlaylistImportService.startImport(this, url)
                }
            } else {
                PlaylistImportService.startImport(this, url)
            }
        }

        binding.cancelImportBtn.setOnClickListener {
            PlaylistImportService.cancelImport(this)
        }

        binding.retryFailedBtn.setOnClickListener {
            PlaylistImportService.retryFailedMatches(this)
        }

        binding.savePlaylistBtn.setOnClickListener {
            val customName = binding.playlistNameEditText.text?.toString()
            val saved = coordinator.saveImportedPlaylist(customPlaylistName = customName)
            if (saved == null) {
                Snackbar.make(binding.root, "No matched songs available to save.", Snackbar.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Toast.makeText(this, "Imported '${saved.name}' (${saved.songs?.size ?: 0} songs)", Toast.LENGTH_SHORT).show()
            val albumItem = AlbumItem(saved.name, saved.description, saved.image, saved.id)
            startActivity(
                Intent(this, ListActivity::class.java)
                    .putExtra("id", saved.id)
                    .putExtra("data", Gson().toJson(albumItem))
                    .putExtra("type", "playlist")
                    .putExtra("createdByUser", true)
            )
            finish()
        }

        // Pre-fill URL if launched from share/intent
        intent?.getStringExtra(Intent.EXTRA_TEXT)?.let { sharedText ->
            if (sharedText.contains("youtu", ignoreCase = true)) {
                binding.urlEditText.setText(sharedText.trim())
            }
        }

        lifecycleScope.launch {
            coordinator.uiState.collectLatest { state ->
                renderState(state)
            }
        }
    }

    override fun onDestroy() {
        // Keep background import running in PlaylistImportService when Activity is closed.
        super.onDestroy()
    }

    private fun renderState(state: PlaylistImportUiState) {
        if (state.playlistTitle.isNotBlank() && binding.playlistNameEditText.text.isNullOrBlank()) {
            binding.playlistNameEditText.setText(state.playlistTitle)
        }

        val isRunning = state.phase == ImportPhase.FETCHING_PLAYLIST || state.phase == ImportPhase.MATCHING_SONGS
        binding.startImportBtn.isEnabled = !isRunning
        binding.cancelImportBtn.visibility = if (isRunning) View.VISIBLE else View.GONE
        binding.retryFailedBtn.visibility = if (!isRunning && state.failedCount > 0) View.VISIBLE else View.GONE

        if (state.phase == ImportPhase.IDLE) {
            binding.progressSection.visibility = View.GONE
            binding.errorText.visibility = View.GONE
            binding.bottomActionBar.visibility = View.GONE
            return
        }

        if (state.phase == ImportPhase.ERROR) {
            binding.errorText.visibility = View.VISIBLE
            binding.errorText.text = state.errorMessage ?: "Import failed."
        } else {
            binding.errorText.visibility = View.GONE
        }

        binding.progressSection.visibility = View.VISIBLE
        if (state.phase == ImportPhase.FETCHING_PLAYLIST) {
            binding.importProgressBar.isIndeterminate = true
            binding.statusText.text = "Fetching public YouTube playlist..."
        } else {
            binding.importProgressBar.isIndeterminate = false
            val max = state.totalTracks.coerceAtLeast(1)
            binding.importProgressBar.max = max
            binding.importProgressBar.progress = state.processedTracks.coerceIn(0, max)
            binding.statusText.text = when (state.phase) {
                ImportPhase.MATCHING_SONGS -> "Matching songs (${state.processedTracks}/${state.totalTracks})..."
                ImportPhase.COMPLETED -> "Import complete (${state.processedTracks}/${state.totalTracks})"
                ImportPhase.CANCELLED -> "Import cancelled (${state.processedTracks}/${state.totalTracks})"
                ImportPhase.ERROR -> "Error during import"
                else -> ""
            }
        }

        binding.statsText.text =
            "Matched: ${state.matchedCount} • Review: ${state.needsReviewCount} • Failed: ${state.failedCount} • Unavailable: ${state.unavailableCount}" +
                if (state.duplicateCount > 0) " • Duplicates: ${state.duplicateCount}" else ""

        adapter.submitList(state.items)

        val saveableCount = state.items.count {
            (it.status == ImportItemStatus.MATCHED || it.status == ImportItemStatus.NEEDS_REVIEW) &&
                it.selectedSong != null
        }
        val canSave = !isRunning && saveableCount > 0
        binding.bottomActionBar.visibility = if (canSave) View.VISIBLE else View.GONE
        binding.saveSummaryText.text = if (state.needsReviewCount > 0) {
            "Ready to save $saveableCount song(s) (${state.needsReviewCount} auto-selected; review optional)"
        } else {
            "Ready to save $saveableCount matched song(s)"
        }
    }

    private fun showApiKeyDialog(onSaved: (() -> Unit)? = null) {
        val input = EditText(this).apply {
            hint = "Enter YouTube Data API v3 Key"
            setText(repository.getYouTubeApiKey())
            setPadding(48, 32, 48, 16)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("YouTube Data API v3 Key")
            .setMessage("Melotune uses the official YouTube Data API v3 to read public playlist metadata without scraping. Enter your API key:")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val key = input.text?.toString()?.trim().orEmpty()
                repository.setYouTubeApiKey(key)
                Toast.makeText(this, if (key.isNotEmpty()) "API key saved" else "API key cleared", Toast.LENGTH_SHORT).show()
                if (key.isNotEmpty()) onSaved?.invoke()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCandidatePicker(item: ImportTrackItemState) {
        val bottomSheet = BottomSheetDialog(this, R.style.MyBottomSheetDialogTheme)
        val sheetBinding = BottomSheetCandidatePickerBinding.inflate(layoutInflater)
        bottomSheet.setContentView(sheetBinding.root)

        sheetBinding.sheetSubtitle.text = item.youtubeTrack.title
        val cleanQuery = SongTitleNormalizer.normalize(
            item.youtubeTrack.title,
            item.youtubeTrack.videoOwnerChannelTitle ?: item.youtubeTrack.channelTitle
        ).cleanTitle
        sheetBinding.manualSearchInput.setText(cleanQuery)

        val candidateAdapter = CandidateListAdapter { chosenSong ->
            coordinator.selectCandidate(item.index, chosenSong)
            bottomSheet.dismiss()
        }
        sheetBinding.candidatesRecyclerView.layoutManager = LinearLayoutManager(this)
        sheetBinding.candidatesRecyclerView.adapter = candidateAdapter
        candidateAdapter.submitList(item.candidates.map { it.song to "${(it.score * 100).toInt()}% • ${it.explanation}" })

        sheetBinding.manualSearchBtn.setOnClickListener {
            val query = sheetBinding.manualSearchInput.text?.toString()?.trim().orEmpty()
            if (query.isEmpty()) return@setOnClickListener
            sheetBinding.manualSearchBtn.isEnabled = false
            lifecycleScope.launch {
                val results = coordinator.searchManualCandidates(query)
                sheetBinding.manualSearchBtn.isEnabled = true
                candidateAdapter.submitList(results.map { it to "Manual search result" })
            }
        }

        bottomSheet.show()
    }

    private class ImportTracksAdapter(
        private val onSelectClick: (ImportTrackItemState) -> Unit
    ) : RecyclerView.Adapter<ImportTracksAdapter.ViewHolder>() {

        private var items: List<ImportTrackItemState> = emptyList()

        fun submitList(newItems: List<ImportTrackItemState>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemYoutubeImportTrackBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position], onSelectClick)
        }

        override fun getItemCount(): Int = items.size

        class ViewHolder(private val b: ItemYoutubeImportTrackBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(item: ImportTrackItemState, onSelectClick: (ImportTrackItemState) -> Unit) {
                b.ytTitle.text = item.youtubeTrack.title

                val song = item.selectedSong
                b.matchedTitle.text = if (song != null) {
                    val artists = song.artists?.primary?.mapNotNull { it?.name() }?.joinToString(", ") ?: ""
                    "JioSaavn: ${song.name()}" + if (artists.isNotBlank()) " — $artists" else ""
                } else {
                    item.note ?: "No match selected"
                }

                val pct = (item.confidenceScore * 100).toInt()
                when (item.status) {
                    ImportItemStatus.MATCHED -> {
                        b.statusBadge.text = "MATCHED ($pct%)"
                        b.statusBadge.setTextColor(Color.parseColor("#1DB954"))
                        b.reviewBtn.visibility = View.VISIBLE
                        b.reviewBtn.text = "Change"
                    }
                    ImportItemStatus.NEEDS_REVIEW -> {
                        b.statusBadge.text = "NEEDS REVIEW ($pct%)"
                        b.statusBadge.setTextColor(Color.parseColor("#FFA000"))
                        b.reviewBtn.visibility = View.VISIBLE
                        b.reviewBtn.text = "Review"
                    }
                    ImportItemStatus.FAILED -> {
                        b.statusBadge.text = "FAILED"
                        b.statusBadge.setTextColor(Color.parseColor("#D32F2F"))
                        b.reviewBtn.visibility = View.VISIBLE
                        b.reviewBtn.text = "Search"
                    }
                    ImportItemStatus.UNAVAILABLE -> {
                        b.statusBadge.text = "UNAVAILABLE"
                        b.statusBadge.setTextColor(Color.GRAY)
                        b.reviewBtn.visibility = View.GONE
                    }
                    ImportItemStatus.DUPLICATE -> {
                        b.statusBadge.text = "DUPLICATE"
                        b.statusBadge.setTextColor(Color.GRAY)
                        b.reviewBtn.visibility = View.GONE
                    }
                    ImportItemStatus.PENDING -> {
                        b.statusBadge.text = "PENDING"
                        b.statusBadge.setTextColor(Color.GRAY)
                        b.reviewBtn.visibility = View.GONE
                    }
                }

                b.reviewBtn.setOnClickListener { onSelectClick(item) }
            }
        }
    }

    private class CandidateListAdapter(
        private val onPick: (Song) -> Unit
    ) : RecyclerView.Adapter<CandidateListAdapter.ViewHolder>() {

        private var items: List<Pair<Song, String>> = emptyList()

        fun submitList(newItems: List<Pair<Song, String>>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(android.R.layout.simple_list_item_2, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val (song, info) = items[position]
            val artists = song.artists?.primary?.mapNotNull { it?.name() }?.joinToString(", ") ?: ""
            holder.text1.text = "${song.name()} — $artists"
            holder.text2.text = "${song.album?.name() ?: ""} • $info"
            holder.itemView.setOnClickListener { onPick(song) }
        }

        override fun getItemCount(): Int = items.size

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val text1: TextView = view.findViewById(android.R.id.text1)
            val text2: TextView = view.findViewById(android.R.id.text2)
        }
    }
}
