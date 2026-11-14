package com.harsh.shah.saavnmp3.importer

import android.content.Context
import com.google.gson.Gson
import com.harsh.shah.saavnmp3.BuildConfig
import com.harsh.shah.saavnmp3.records.SongResponse.Song
import com.harsh.shah.saavnmp3.records.sharedpref.SavedLibraries
import com.harsh.shah.saavnmp3.utils.SharedPreferenceManager

data class PlaylistImportCheckpoint(
    val sourceUrl: String,
    val youtubePlaylistId: String,
    val playlistTitle: String,
    val playlistThumbnailUrl: String?,
    val processedCount: Int,
    val totalCount: Int,
    val matchedSongs: List<SavedLibraries.Library.Songs>,
    val updatedAtMs: Long
)

interface PlaylistPersistence {
    fun saveImportedPlaylist(
        youtubePlaylistId: String,
        sourceUrl: String,
        playlistName: String,
        coverImage: String?,
        songs: List<SavedLibraries.Library.Songs>
    ): SavedLibraries.Library

    fun saveCheckpoint(checkpoint: PlaylistImportCheckpoint?)
    fun loadCheckpoint(): PlaylistImportCheckpoint?
    fun getYouTubeApiKey(): String
    fun setYouTubeApiKey(apiKey: String)
}

class PlaylistImportRepository(
    private val context: Context,
    private val prefs: SharedPreferenceManager = SharedPreferenceManager.getInstance(context),
    private val gson: Gson = Gson()
) : PlaylistPersistence {

    companion object {
        private const val PREFS_YT_CONFIG = "melotune_yt_import_config"
        private const val KEY_YT_API_KEY = "youtube_api_key"
    }

    override fun saveImportedPlaylist(
        youtubePlaylistId: String,
        sourceUrl: String,
        playlistName: String,
        coverImage: String?,
        songs: List<SavedLibraries.Library.Songs>
    ): SavedLibraries.Library {
        val libraryId = "yt_import_${youtubePlaylistId}_${System.currentTimeMillis()}"
        val resolvedCover = coverImage?.takeIf { it.isNotBlank() }
            ?: songs.firstOrNull { !it.image.isNullOrBlank() }?.image
            ?: ""

        val library = SavedLibraries.Library(
            id = libraryId,
            isCreatedByUser = true,
            isAlbum = false,
            name = playlistName.ifBlank { "YouTube Imported Playlist" },
            image = resolvedCover,
            description = "${songs.size} songs • Imported from YouTube",
            songs = songs.toMutableList()
        )

        prefs.addLibraryToSavedLibraries(library)
        prefs.setSavedLibraryDataById(libraryId, library)
        prefs.markYouTubeImportedPlaylist(libraryId, sourceUrl, youtubePlaylistId)
        saveCheckpoint(null)
        return library
    }

    override fun saveCheckpoint(checkpoint: PlaylistImportCheckpoint?) {
        if (checkpoint == null) {
            prefs.saveYouTubeImportCheckpoint(null)
        } else {
            prefs.saveYouTubeImportCheckpoint(gson.toJson(checkpoint))
        }
    }

    override fun loadCheckpoint(): PlaylistImportCheckpoint? {
        val json = prefs.getYouTubeImportCheckpoint()
        if (json.isNullOrEmpty()) return null
        return try {
            gson.fromJson(json, PlaylistImportCheckpoint::class.java)
        } catch (e: Exception) {
            null
        }
    }

    override fun getYouTubeApiKey(): String {
        val userKey = context.getSharedPreferences(PREFS_YT_CONFIG, Context.MODE_PRIVATE)
            .getString(KEY_YT_API_KEY, "")
            ?.trim()
            .orEmpty()
        if (userKey.isNotEmpty()) return userKey
        return BuildConfig.YOUTUBE_API_KEY.trim()
    }

    override fun setYouTubeApiKey(apiKey: String) {
        context.getSharedPreferences(PREFS_YT_CONFIG, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_YT_API_KEY, apiKey.trim())
            .apply()
    }
}

fun Song.toSavedLibrarySong(): SavedLibraries.Library.Songs {
    val artistText = artists?.primary?.mapNotNull { it?.name() }?.joinToString(", ")
        ?.takeIf { it.isNotBlank() }
        ?: artists?.all?.mapNotNull { it?.name() }?.joinToString(", ")
        ?: ""
    return SavedLibraries.Library.Songs(
        id = id,
        title = name(),
        description = artistText,
        image = image?.lastOrNull()?.url
    )
}
