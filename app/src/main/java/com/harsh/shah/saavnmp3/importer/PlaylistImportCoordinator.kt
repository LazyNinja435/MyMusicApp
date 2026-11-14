package com.harsh.shah.saavnmp3.importer

import com.harsh.shah.saavnmp3.records.SongResponse.Song
import com.harsh.shah.saavnmp3.records.sharedpref.SavedLibraries
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ImportItemStatus {
    PENDING,
    MATCHED,
    NEEDS_REVIEW,
    FAILED,
    UNAVAILABLE,
    DUPLICATE
}

data class ImportTrackItemState(
    val index: Int,
    val youtubeTrack: YouTubePlaylistTrack,
    val status: ImportItemStatus,
    val selectedSong: Song? = null,
    val candidates: List<ScoredSongCandidate> = emptyList(),
    val confidenceScore: Double = 0.0,
    val note: String? = null
)

enum class ImportPhase {
    IDLE,
    FETCHING_PLAYLIST,
    MATCHING_SONGS,
    COMPLETED,
    CANCELLED,
    ERROR
}

data class PlaylistImportUiState(
    val phase: ImportPhase = ImportPhase.IDLE,
    val sourceUrl: String = "",
    val youtubePlaylistId: String = "",
    val playlistTitle: String = "",
    val playlistThumbnailUrl: String? = null,
    val totalTracks: Int = 0,
    val processedTracks: Int = 0,
    val matchedCount: Int = 0,
    val needsReviewCount: Int = 0,
    val failedCount: Int = 0,
    val unavailableCount: Int = 0,
    val duplicateCount: Int = 0,
    val items: List<ImportTrackItemState> = emptyList(),
    val errorMessage: String? = null,
    val savedLibrary: SavedLibraries.Library? = null
)

class PlaylistImportCoordinator(
    private val youTubeClient: YouTubePlaylistDataSource,
    private val jioSaavnSearch: JioSaavnSearchDataSource,
    private val matcher: JioSaavnSongMatcher,
    private val persistence: PlaylistPersistence,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var activeJob: Job? = null

    private val _uiState = MutableStateFlow(PlaylistImportUiState())
    val uiState: StateFlow<PlaylistImportUiState> = _uiState.asStateFlow()

    fun startImport(rawUrl: String, apiKeyOverride: String? = null) {
        val parsed = PlaylistUrlParser.parse(rawUrl)
        if (parsed is PlaylistUrlParseResult.Invalid) {
            _uiState.value = PlaylistImportUiState(
                phase = ImportPhase.ERROR,
                sourceUrl = rawUrl,
                errorMessage = parsed.reason
            )
            return
        }
        val success = parsed as PlaylistUrlParseResult.Success
        val apiKey = apiKeyOverride?.trim()?.takeIf { it.isNotEmpty() } ?: persistence.getYouTubeApiKey()
        if (apiKey.isBlank()) {
            _uiState.value = PlaylistImportUiState(
                phase = ImportPhase.ERROR,
                sourceUrl = success.normalizedUrl,
                youtubePlaylistId = success.playlistId,
                errorMessage = "YouTube Data API v3 key is missing. Add your API key to import public playlists."
            )
            return
        }

        activeJob?.cancel()
        activeJob = scope.launch {
            _uiState.value = PlaylistImportUiState(
                phase = ImportPhase.FETCHING_PLAYLIST,
                sourceUrl = success.normalizedUrl,
                youtubePlaylistId = success.playlistId
            )

            try {
                val fetchResult = withContext(ioDispatcher) {
                    youTubeClient.fetchPlaylist(success.playlistId, apiKey) { fetched, expected ->
                        _uiState.update {
                            it.copy(
                                totalTracks = expected ?: fetched,
                                processedTracks = 0
                            )
                        }
                    }
                }

                val tracks = fetchResult.tracks
                if (tracks.isEmpty()) {
                    _uiState.value = PlaylistImportUiState(
                        phase = ImportPhase.COMPLETED,
                        sourceUrl = success.normalizedUrl,
                        youtubePlaylistId = success.playlistId,
                        playlistTitle = fetchResult.metadata.title,
                        playlistThumbnailUrl = fetchResult.metadata.thumbnailUrl,
                        totalTracks = 0,
                        processedTracks = 0,
                        items = emptyList()
                    )
                    return@launch
                }

                val initialItems = tracks.mapIndexed { idx, track ->
                    ImportTrackItemState(
                        index = idx,
                        youtubeTrack = track,
                        status = if (track.isAvailable) ImportItemStatus.PENDING else ImportItemStatus.UNAVAILABLE,
                        note = track.unavailableReason
                    )
                }.toMutableList()

                _uiState.value = recalcCounts(
                    PlaylistImportUiState(
                        phase = ImportPhase.MATCHING_SONGS,
                        sourceUrl = success.normalizedUrl,
                        youtubePlaylistId = success.playlistId,
                        playlistTitle = fetchResult.metadata.title,
                        playlistThumbnailUrl = fetchResult.metadata.thumbnailUrl,
                        totalTracks = tracks.size,
                        processedTracks = 0,
                        items = initialItems.toList()
                    )
                )

                val seenVideoIds = HashSet<String>()
                val seenSongIds = HashSet<String>()

                for (idx in initialItems.indices) {
                    if (!isActive) throw CancellationException("Import cancelled")
                    val current = initialItems[idx]
                    val ytTrack = current.youtubeTrack

                    if (!ytTrack.isAvailable) {
                        _uiState.update { state ->
                            recalcCounts(state.copy(processedTracks = idx + 1, items = initialItems.toList()))
                        }
                        continue
                    }

                    if (ytTrack.videoId.isNotBlank() && !seenVideoIds.add(ytTrack.videoId)) {
                        initialItems[idx] = current.copy(
                            status = ImportItemStatus.DUPLICATE,
                            note = "Duplicate video in playlist"
                        )
                        _uiState.update { state ->
                            recalcCounts(state.copy(processedTracks = idx + 1, items = initialItems.toList()))
                        }
                        continue
                    }

                    val matchResult = withContext(ioDispatcher) {
                        matcher.match(ytTrack)
                    }

                    val matchedSong = matchResult.bestCandidate?.song
                    val newItem = when (matchResult.status) {
                        MatchConfidenceStatus.MATCHED -> {
                            val songId = matchedSong?.id
                            if (songId != null && !seenSongIds.add(songId)) {
                                current.copy(
                                    status = ImportItemStatus.DUPLICATE,
                                    selectedSong = matchedSong,
                                    candidates = matchResult.candidates,
                                    confidenceScore = matchResult.bestCandidate?.score ?: 0.0,
                                    note = "Duplicate matched song in playlist"
                                )
                            } else {
                                current.copy(
                                    status = ImportItemStatus.MATCHED,
                                    selectedSong = matchedSong,
                                    candidates = matchResult.candidates,
                                    confidenceScore = matchResult.bestCandidate?.score ?: 0.0,
                                    note = matchResult.bestCandidate?.explanation
                                )
                            }
                        }
                        MatchConfidenceStatus.NEEDS_REVIEW -> current.copy(
                            status = ImportItemStatus.NEEDS_REVIEW,
                            selectedSong = null,
                            candidates = matchResult.candidates,
                            confidenceScore = matchResult.candidates.firstOrNull()?.score ?: 0.0,
                            note = "Review ${matchResult.candidates.size} candidate(s)"
                        )
                        MatchConfidenceStatus.FAILED -> current.copy(
                            status = ImportItemStatus.FAILED,
                            selectedSong = null,
                            candidates = matchResult.candidates,
                            confidenceScore = matchResult.candidates.firstOrNull()?.score ?: 0.0,
                            note = matchResult.failureReason ?: "No match found"
                        )
                    }

                    initialItems[idx] = newItem
                    val updatedState = recalcCounts(
                        _uiState.value.copy(
                            processedTracks = idx + 1,
                            items = initialItems.toList()
                        )
                    )
                    _uiState.value = updatedState

                    if ((idx + 1) % 5 == 0 || idx == initialItems.lastIndex) {
                        saveProgressCheckpoint(updatedState)
                    }
                }

                _uiState.update { it.copy(phase = ImportPhase.COMPLETED) }
            } catch (ce: CancellationException) {
                _uiState.update { state ->
                    saveProgressCheckpoint(state)
                    state.copy(phase = ImportPhase.CANCELLED)
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        phase = ImportPhase.ERROR,
                        errorMessage = e.message ?: "Failed to import playlist"
                    )
                }
            }
        }
    }

    fun cancelImport() {
        activeJob?.cancel()
        _uiState.update { state ->
            if (state.phase == ImportPhase.FETCHING_PLAYLIST || state.phase == ImportPhase.MATCHING_SONGS) {
                saveProgressCheckpoint(state)
                state.copy(phase = ImportPhase.CANCELLED)
            } else {
                state
            }
        }
    }

    fun retryFailedMatches() {
        val currentState = _uiState.value
        val failedIndices = currentState.items
            .filter { it.status == ImportItemStatus.FAILED }
            .map { it.index }
        if (failedIndices.isEmpty()) return

        activeJob?.cancel()
        activeJob = scope.launch {
            _uiState.update { it.copy(phase = ImportPhase.MATCHING_SONGS, errorMessage = null) }
            val mutableItems = _uiState.value.items.toMutableList()
            val existingSongIds = mutableItems.mapNotNull { it.selectedSong?.id }.toMutableSet()

            for (idx in failedIndices) {
                if (!isActive) break
                val item = mutableItems.getOrNull(idx) ?: continue
                val matchResult = withContext(ioDispatcher) { matcher.match(item.youtubeTrack) }
                val bestSong = matchResult.bestCandidate?.song
                mutableItems[idx] = when (matchResult.status) {
                    MatchConfidenceStatus.MATCHED -> {
                        if (bestSong?.id != null && !existingSongIds.add(bestSong.id)) {
                            item.copy(
                                status = ImportItemStatus.DUPLICATE,
                                selectedSong = bestSong,
                                candidates = matchResult.candidates,
                                confidenceScore = matchResult.bestCandidate?.score ?: 0.0,
                                note = "Duplicate matched song in playlist"
                            )
                        } else {
                            item.copy(
                                status = ImportItemStatus.MATCHED,
                                selectedSong = bestSong,
                                candidates = matchResult.candidates,
                                confidenceScore = matchResult.bestCandidate?.score ?: 0.0,
                                note = matchResult.bestCandidate?.explanation
                            )
                        }
                    }
                    MatchConfidenceStatus.NEEDS_REVIEW -> item.copy(
                        status = ImportItemStatus.NEEDS_REVIEW,
                        candidates = matchResult.candidates,
                        confidenceScore = matchResult.candidates.firstOrNull()?.score ?: 0.0,
                        note = "Review ${matchResult.candidates.size} candidate(s)"
                    )
                    MatchConfidenceStatus.FAILED -> item.copy(
                        status = ImportItemStatus.FAILED,
                        candidates = matchResult.candidates,
                        note = matchResult.failureReason ?: "No match found"
                    )
                }
                _uiState.value = recalcCounts(_uiState.value.copy(items = mutableItems.toList()))
            }
            _uiState.update { it.copy(phase = ImportPhase.COMPLETED) }
        }
    }

    fun selectCandidate(itemIndex: Int, song: Song) {
        val currentItems = _uiState.value.items.toMutableList()
        val existing = currentItems.getOrNull(itemIndex) ?: return
        val matchedScore = existing.candidates.firstOrNull { it.song.id == song.id }?.score ?: 1.0
        currentItems[itemIndex] = existing.copy(
            status = ImportItemStatus.MATCHED,
            selectedSong = song,
            confidenceScore = matchedScore,
            note = "Manually selected"
        )
        _uiState.value = recalcCounts(_uiState.value.copy(items = currentItems))
    }

    suspend fun searchManualCandidates(query: String): List<Song> {
        return withContext(ioDispatcher) {
            jioSaavnSearch.searchSongs(query, limit = 15)
        }
    }

    fun saveImportedPlaylist(
        customPlaylistName: String? = null,
        includeDuplicates: Boolean = false
    ): SavedLibraries.Library? {
        val state = _uiState.value
        val selectedSongs = state.items
            .filter {
                it.status == ImportItemStatus.MATCHED ||
                    (includeDuplicates && it.status == ImportItemStatus.DUPLICATE && it.selectedSong != null)
            }
            .mapNotNull { it.selectedSong?.toSavedLibrarySong() }

        if (selectedSongs.isEmpty()) return null

        val resolvedName = customPlaylistName?.trim()?.takeIf { it.isNotEmpty() }
            ?: state.playlistTitle.ifBlank { "YouTube Playlist" }

        val saved = persistence.saveImportedPlaylist(
            youtubePlaylistId = state.youtubePlaylistId.ifBlank { "custom_${System.currentTimeMillis()}" },
            sourceUrl = state.sourceUrl,
            playlistName = resolvedName,
            coverImage = state.playlistThumbnailUrl,
            songs = selectedSongs
        )
        _uiState.update { it.copy(savedLibrary = saved) }
        return saved
    }

    fun loadSavedCheckpoint(): PlaylistImportCheckpoint? = persistence.loadCheckpoint()

    fun dispose() {
        activeJob?.cancel()
    }

    private fun saveProgressCheckpoint(state: PlaylistImportUiState) {
        val matched = state.items
            .filter { it.status == ImportItemStatus.MATCHED && it.selectedSong != null }
            .mapNotNull { it.selectedSong?.toSavedLibrarySong() }
        persistence.saveCheckpoint(
            PlaylistImportCheckpoint(
                sourceUrl = state.sourceUrl,
                youtubePlaylistId = state.youtubePlaylistId,
                playlistTitle = state.playlistTitle,
                playlistThumbnailUrl = state.playlistThumbnailUrl,
                processedCount = state.processedTracks,
                totalCount = state.totalTracks,
                matchedSongs = matched,
                updatedAtMs = System.currentTimeMillis()
            )
        )
    }

    private fun recalcCounts(state: PlaylistImportUiState): PlaylistImportUiState {
        var matched = 0
        var review = 0
        var failed = 0
        var unavailable = 0
        var duplicate = 0
        for (item in state.items) {
            when (item.status) {
                ImportItemStatus.MATCHED -> matched++
                ImportItemStatus.NEEDS_REVIEW -> review++
                ImportItemStatus.FAILED -> failed++
                ImportItemStatus.UNAVAILABLE -> unavailable++
                ImportItemStatus.DUPLICATE -> duplicate++
                ImportItemStatus.PENDING -> {}
            }
        }
        return state.copy(
            matchedCount = matched,
            needsReviewCount = review,
            failedCount = failed,
            unavailableCount = unavailable,
            duplicateCount = duplicate
        )
    }
}
