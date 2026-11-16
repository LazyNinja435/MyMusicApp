package com.harsh.shah.saavnmp3.automotive

import android.content.Context
import com.google.gson.Gson
import com.harsh.shah.saavnmp3.network.ApiManager
import com.harsh.shah.saavnmp3.network.utility.RequestNetwork
import com.harsh.shah.saavnmp3.records.AlbumSearch
import com.harsh.shah.saavnmp3.records.ArtistAllSongs
import com.harsh.shah.saavnmp3.records.ArtistsSearch
import com.harsh.shah.saavnmp3.records.PlaylistSearch
import com.harsh.shah.saavnmp3.records.SongResponse
import com.harsh.shah.saavnmp3.records.SongSearch
import com.harsh.shah.saavnmp3.records.sharedpref.SavedLibraries
import com.harsh.shah.saavnmp3.utils.SharedPreferenceManager
import com.harsh.shah.saavnmp3.utils.TrackDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

data class AutoTrackItem(
    val id: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val artworkUrl: String? = null,
    val localFilePath: String? = null
)

data class AutoCollectionItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val artworkUrl: String? = null,
    val isAlbum: Boolean = false,
    val isYouTubeImport: Boolean = false,
    val songs: List<AutoTrackItem> = emptyList()
)

interface MediaLibraryDataSource {
    suspend fun getRecentlyPlayed(): List<AutoTrackItem>
    suspend fun getFavorites(): List<AutoTrackItem>
    suspend fun getRecommendedSongs(): List<AutoTrackItem>
    suspend fun getUserPlaylists(): List<AutoCollectionItem>
    suspend fun getImportedYouTubePlaylists(): List<AutoCollectionItem>
    suspend fun getAlbums(): List<AutoCollectionItem>
    suspend fun getArtists(): List<AutoCollectionItem>
    suspend fun getDownloadedTracks(): List<AutoTrackItem>
    suspend fun getPlaylistTracks(playlistId: String): List<AutoTrackItem>
    suspend fun getAlbumTracks(albumId: String): List<AutoTrackItem>
    suspend fun getArtistTracks(artistId: String): List<AutoTrackItem>
    suspend fun searchTracks(query: String): List<AutoTrackItem>
}

class MediaLibraryRepository(
    private val context: Context,
    private val prefs: SharedPreferenceManager = SharedPreferenceManager.getInstance(context),
    private val gson: Gson = Gson()
) : MediaLibraryDataSource {

    override suspend fun getRecentlyPlayed(): List<AutoTrackItem> {
        return prefs.getRecentlyPlayed().mapNotNull { it.toAutoTrack() }
    }

    override suspend fun getFavorites(): List<AutoTrackItem> {
        return prefs.getLikedSongs().mapNotNull { it.toAutoTrack() }
    }

    override suspend fun getRecommendedSongs(): List<AutoTrackItem> {
        val cached = prefs.homeSongsRecommended?.data?.results?.mapNotNull { it?.toAutoTrack() }
        if (!cached.isNullOrEmpty()) return cached

        return searchOnlineSongs("Trending")
    }

    override suspend fun getUserPlaylists(): List<AutoCollectionItem> {
        val libs = prefs.savedLibrariesData?.lists?.filterNotNull().orEmpty()
        return libs.filter {
            it.isCreatedByUser && !it.isAlbum && !prefs.isYouTubeImportedPlaylist(it.id)
        }.mapNotNull { it.toCollection(isYouTubeImport = false) }
    }

    override suspend fun getImportedYouTubePlaylists(): List<AutoCollectionItem> {
        val libs = prefs.savedLibrariesData?.lists?.filterNotNull().orEmpty()
        return libs.filter {
            prefs.isYouTubeImportedPlaylist(it.id)
        }.mapNotNull { it.toCollection(isYouTubeImport = true) }
    }

    override suspend fun getAlbums(): List<AutoCollectionItem> {
        val savedAlbums = prefs.savedLibrariesData?.lists?.filterNotNull().orEmpty()
            .filter { it.isAlbum }
            .mapNotNull { it.toCollection(isYouTubeImport = false) }

        val recommendedAlbums = prefs.homeAlbumsRecommended?.data?.results?.filterNotNull().orEmpty()
            .mapNotNull { album ->
                val id = album.id ?: return@mapNotNull null
                AutoCollectionItem(
                    id = id,
                    title = album.name().ifBlank { "Album" },
                    subtitle = album.artist?.primary?.mapNotNull { it?.name() }?.joinToString(", ") ?: "",
                    artworkUrl = album.image?.lastOrNull()?.url,
                    isAlbum = true
                )
            }

        return (savedAlbums + recommendedAlbums).distinctBy { it.id }
    }

    override suspend fun getArtists(): List<AutoCollectionItem> {
        val artists = prefs.homeArtistsRecommended?.data?.results?.filterNotNull().orEmpty()
        return artists.mapNotNull { artist ->
            val id = artist.id ?: return@mapNotNull null
            AutoCollectionItem(
                id = id,
                title = artist.name().ifBlank { "Artist" },
                subtitle = artist.role?.takeIf { it.isNotBlank() } ?: "Artist",
                artworkUrl = artist.image?.lastOrNull()?.url,
                isAlbum = false
            )
        }
    }

    override suspend fun getDownloadedTracks(): List<AutoTrackItem> = withContext(Dispatchers.IO) {
        try {
            TrackDownloader.getDownloadedTracks(context).mapNotNull { dt ->
                val file = dt.file ?: return@mapNotNull null
                if (!file.exists()) return@mapNotNull null
                val path = file.absolutePath
                val id = dt.trackUID?.takeIf { it.isNotBlank() } ?: path
                AutoTrackItem(
                    id = id,
                    title = dt.title?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension,
                    artist = dt.artist?.takeIf { it.isNotBlank() } ?: "Downloaded Track",
                    album = dt.album,
                    artworkUrl = null,
                    localFilePath = path
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getPlaylistTracks(playlistId: String): List<AutoTrackItem> {
        val fromSaved = prefs.savedLibrariesData?.lists?.firstOrNull { it?.id == playlistId }
            ?: prefs.getSavedLibraryDataById(playlistId)
        if (fromSaved != null) {
            return fromSaved.songs?.mapNotNull { it?.toAutoTrack() }.orEmpty()
        }

        val cachedPlaylist = prefs.getPlaylistResponseById(playlistId)
        val cachedSongs = cachedPlaylist?.data?.songs?.mapNotNull { it?.toAutoTrack() }
        if (!cachedSongs.isNullOrEmpty()) return cachedSongs

        return suspendCancellableCoroutine { cont ->
            ApiManager(context).retrievePlaylistById(playlistId, null, null, object : RequestNetwork.RequestListener {
                override fun onResponse(tag: String?, response: String?, responseHeaders: HashMap<String?, Any?>?) {
                    val parsed = try {
                        gson.fromJson(response, PlaylistSearch::class.java)
                    } catch (e: Exception) {
                        null
                    }
                    if (parsed?.success == true) {
                        prefs.setPlaylistResponseById(playlistId, parsed)
                    }
                    val tracks = parsed?.data?.songs?.mapNotNull { it?.toAutoTrack() }.orEmpty()
                    if (cont.isActive) cont.resume(tracks)
                }

                override fun onErrorResponse(tag: String?, message: String?) {
                    if (cont.isActive) cont.resume(emptyList())
                }
            })
        }
    }

    override suspend fun getAlbumTracks(albumId: String): List<AutoTrackItem> {
        val fromSaved = prefs.savedLibrariesData?.lists?.firstOrNull { it?.id == albumId }
            ?: prefs.getSavedLibraryDataById(albumId)
        if (fromSaved != null && !fromSaved.songs.isNullOrEmpty()) {
            return fromSaved.songs.mapNotNull { it?.toAutoTrack() }
        }

        val cachedAlbum = prefs.getAlbumResponseById(albumId)
        val cachedSongs = cachedAlbum?.data?.songs?.mapNotNull { it?.toAutoTrack() }
        if (!cachedSongs.isNullOrEmpty()) return cachedSongs

        return suspendCancellableCoroutine { cont ->
            ApiManager(context).retrieveAlbumById(albumId, object : RequestNetwork.RequestListener {
                override fun onResponse(tag: String?, response: String?, responseHeaders: HashMap<String?, Any?>?) {
                    val parsed = try {
                        gson.fromJson(response, AlbumSearch::class.java)
                    } catch (e: Exception) {
                        null
                    }
                    if (parsed?.success == true) {
                        prefs.setAlbumResponseById(albumId, parsed)
                    }
                    val tracks = parsed?.data?.songs?.mapNotNull { it?.toAutoTrack() }.orEmpty()
                    if (cont.isActive) cont.resume(tracks)
                }

                override fun onErrorResponse(tag: String?, message: String?) {
                    if (cont.isActive) cont.resume(emptyList())
                }
            })
        }
    }

    override suspend fun getArtistTracks(artistId: String): List<AutoTrackItem> {
        return suspendCancellableCoroutine { cont ->
            ApiManager(context).retrieveArtistSongs(
                artistId,
                0,
                ApiManager.SortBy.popularity,
                ApiManager.SortOrder.desc,
                object : RequestNetwork.RequestListener {
                    override fun onResponse(tag: String?, response: String?, responseHeaders: HashMap<String?, Any?>?) {
                        val parsed = try {
                            gson.fromJson(response, ArtistAllSongs::class.java)
                        } catch (e: Exception) {
                            null
                        }
                        val tracks = parsed?.data?.songs?.mapNotNull { it?.toAutoTrack() }.orEmpty()
                        if (cont.isActive) cont.resume(tracks)
                    }

                    override fun onErrorResponse(tag: String?, message: String?) {
                        if (cont.isActive) cont.resume(emptyList())
                    }
                }
            )
        }
    }

    override suspend fun searchTracks(query: String): List<AutoTrackItem> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return getRecentlyPlayed()

        val online = searchOnlineSongs(trimmed)
        if (online.isNotEmpty()) return online

        // Offline fallback: search downloaded tracks & saved libraries
        val lower = trimmed.lowercase()
        val offlineDownloads = getDownloadedTracks().filter {
            it.title.lowercase().contains(lower) || it.artist.lowercase().contains(lower)
        }
        val offlineLiked = getFavorites().filter {
            it.title.lowercase().contains(lower) || it.artist.lowercase().contains(lower)
        }
        return (offlineDownloads + offlineLiked).distinctBy { it.id }
    }

    private suspend fun searchOnlineSongs(query: String): List<AutoTrackItem> {
        return suspendCancellableCoroutine { cont ->
            ApiManager(context).searchSongs(query, 0, 20, object : RequestNetwork.RequestListener {
                override fun onResponse(tag: String?, response: String?, responseHeaders: HashMap<String?, Any?>?) {
                    val parsed = try {
                        gson.fromJson(response, SongSearch::class.java)
                    } catch (e: Exception) {
                        null
                    }
                    val tracks = parsed?.data?.results?.mapNotNull { it?.toAutoTrack() }.orEmpty()
                    if (cont.isActive) cont.resume(tracks)
                }

                override fun onErrorResponse(tag: String?, message: String?) {
                    if (cont.isActive) cont.resume(emptyList())
                }
            })
        }
    }

    private fun SavedLibraries.Library.Songs.toAutoTrack(): AutoTrackItem? {
        val songId = id?.takeIf { it.isNotBlank() } ?: return null
        return AutoTrackItem(
            id = songId,
            title = title?.takeIf { it.isNotBlank() } ?: "Unknown Track",
            artist = description?.takeIf { it.isNotBlank() } ?: "Melotune",
            artworkUrl = image
        )
    }

    private fun SongResponse.Song.toAutoTrack(): AutoTrackItem? {
        val songId = id?.takeIf { it.isNotBlank() } ?: return null
        val artistName = artists?.primary?.mapNotNull { it?.name() }?.joinToString(", ")
            ?.takeIf { it.isNotBlank() }
            ?: artists?.all?.mapNotNull { it?.name() }?.joinToString(", ")
            ?: "Melotune"
        return AutoTrackItem(
            id = songId,
            title = name().ifBlank { "Unknown Track" },
            artist = artistName,
            album = album?.name()?.takeIf { it.isNotBlank() },
            artworkUrl = image?.lastOrNull()?.url
        )
    }

    private fun SavedLibraries.Library.toCollection(isYouTubeImport: Boolean): AutoCollectionItem? {
        val libId = id?.takeIf { it.isNotBlank() } ?: return null
        val songItems = songs?.mapNotNull { it?.toAutoTrack() }.orEmpty()
        val cover = image?.takeIf { it.isNotBlank() } ?: songItems.firstOrNull { !it.artworkUrl.isNullOrBlank() }?.artworkUrl
        val desc = description?.takeIf { it.isNotBlank() } ?: "${songItems.size} songs"
        return AutoCollectionItem(
            id = libId,
            title = name?.takeIf { it.isNotBlank() } ?: "Playlist",
            subtitle = desc,
            artworkUrl = cover,
            isAlbum = isAlbum,
            isYouTubeImport = isYouTubeImport,
            songs = songItems
        )
    }
}
