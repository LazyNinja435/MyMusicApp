package com.harsh.shah.saavnmp3.automotive

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

class MediaLibraryBrowser(private val dataSource: MediaLibraryDataSource) {

    companion object {
        const val ROOT_ID = "melotune_root"
        const val TAB_HOME = "tab_home"
        const val TAB_PLAYLISTS = "tab_playlists"
        const val TAB_ALBUMS = "tab_albums"
        const val TAB_ARTISTS = "tab_artists"
        const val TAB_DOWNLOADS = "tab_downloads"

        const val NODE_RECENT = "node_recent"
        const val NODE_FAVORITES = "node_favorites"
        const val NODE_RECOMMENDED = "node_recommended"
        const val NODE_USER_PLAYLISTS = "node_user_playlists"
        const val NODE_YT_PLAYLISTS = "node_yt_playlists"

        const val PREFIX_PLAYLIST = "playlist:"
        const val PREFIX_ALBUM = "album:"
        const val PREFIX_ARTIST = "artist:"
        const val PREFIX_SONG = "song:"
        const val PREFIX_DOWNLOAD = "download:"

        const val EXTRA_CONTEXT_PARENT_ID = "melotune.context_parent_id"
        const val EXTRA_SONG_ID = "melotune.song_id"
    }

    fun getRootItem(): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle("Melotune")
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
            .build()
        return MediaItem.Builder()
            .setMediaId(ROOT_ID)
            .setMediaMetadata(metadata)
            .build()
    }

    suspend fun getChildren(parentId: String, page: Int = 0, pageSize: Int = 50): List<MediaItem> {
        val allItems: List<MediaItem> = when (parentId) {
            ROOT_ID -> listOf(
                browsableFolder(TAB_HOME, "Home", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
                browsableFolder(TAB_PLAYLISTS, "Playlists", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
                browsableFolder(TAB_ALBUMS, "Albums", MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS),
                browsableFolder(TAB_ARTISTS, "Artists", MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS),
                browsableFolder(TAB_DOWNLOADS, "Downloads", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
            )
            TAB_HOME -> listOf(
                browsableFolder(NODE_RECENT, "Recently Played", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
                browsableFolder(NODE_FAVORITES, "Favorites", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
                browsableFolder(NODE_RECOMMENDED, "Recommended Songs", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
            )
            TAB_PLAYLISTS -> listOf(
                browsableFolder(NODE_USER_PLAYLISTS, "User-Created Playlists", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
                browsableFolder(NODE_YT_PLAYLISTS, "Imported YouTube Playlists", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS)
            )
            NODE_RECENT -> dataSource.getRecentlyPlayed().map { it.toPlayableMediaItem(parentId) }
            NODE_FAVORITES -> dataSource.getFavorites().map { it.toPlayableMediaItem(parentId) }
            NODE_RECOMMENDED -> dataSource.getRecommendedSongs().map { it.toPlayableMediaItem(parentId) }
            NODE_USER_PLAYLISTS -> dataSource.getUserPlaylists().map { it.toBrowsablePlaylistItem() }
            NODE_YT_PLAYLISTS -> dataSource.getImportedYouTubePlaylists().map { it.toBrowsablePlaylistItem() }
            TAB_ALBUMS -> dataSource.getAlbums().map { it.toBrowsableAlbumItem() }
            TAB_ARTISTS -> dataSource.getArtists().map { it.toBrowsableArtistItem() }
            TAB_DOWNLOADS -> dataSource.getDownloadedTracks().map { it.toPlayableDownloadItem(parentId) }
            else -> when {
                parentId.startsWith(PREFIX_PLAYLIST) -> {
                    val playlistId = parentId.removePrefix(PREFIX_PLAYLIST)
                    dataSource.getPlaylistTracks(playlistId).map { it.toPlayableMediaItem(parentId) }
                }
                parentId.startsWith(PREFIX_ALBUM) -> {
                    val albumId = parentId.removePrefix(PREFIX_ALBUM)
                    dataSource.getAlbumTracks(albumId).map { it.toPlayableMediaItem(parentId) }
                }
                parentId.startsWith(PREFIX_ARTIST) -> {
                    val artistId = parentId.removePrefix(PREFIX_ARTIST)
                    dataSource.getArtistTracks(artistId).map { it.toPlayableMediaItem(parentId) }
                }
                else -> emptyList()
            }
        }

        return paginate(allItems, page, pageSize)
    }

    suspend fun getItem(mediaId: String): MediaItem? {
        return when (mediaId) {
            ROOT_ID -> getRootItem()
            TAB_HOME -> browsableFolder(TAB_HOME, "Home", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
            TAB_PLAYLISTS -> browsableFolder(TAB_PLAYLISTS, "Playlists", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS)
            TAB_ALBUMS -> browsableFolder(TAB_ALBUMS, "Albums", MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS)
            TAB_ARTISTS -> browsableFolder(TAB_ARTISTS, "Artists", MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS)
            TAB_DOWNLOADS -> browsableFolder(TAB_DOWNLOADS, "Downloads", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
            NODE_RECENT -> browsableFolder(NODE_RECENT, "Recently Played", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
            NODE_FAVORITES -> browsableFolder(NODE_FAVORITES, "Favorites", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
            NODE_RECOMMENDED -> browsableFolder(NODE_RECOMMENDED, "Recommended Songs", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
            NODE_USER_PLAYLISTS -> browsableFolder(NODE_USER_PLAYLISTS, "User-Created Playlists", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS)
            NODE_YT_PLAYLISTS -> browsableFolder(NODE_YT_PLAYLISTS, "Imported YouTube Playlists", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS)
            else -> null
        }
    }

    suspend fun search(query: String, page: Int = 0, pageSize: Int = 50): List<MediaItem> {
        val results = dataSource.searchTracks(query).map { it.toPlayableMediaItem("search:$query") }
        return paginate(results, page, pageSize)
    }

    suspend fun resolveQueueForMediaItem(mediaItem: MediaItem): Pair<List<AutoTrackItem>, Int> {
        val mediaId = mediaItem.mediaId
        val extras = mediaItem.mediaMetadata.extras
        val parentId = mediaId.substringAfter("?parent=", "").takeIf { it.isNotEmpty() }
            ?.let(Uri::decode) ?: extras?.getString(EXTRA_CONTEXT_PARENT_ID)

        if (mediaId.startsWith(PREFIX_DOWNLOAD)) {
            val downloads = dataSource.getDownloadedTracks()
            val targetKey = mediaId.removePrefix(PREFIX_DOWNLOAD)
            val idx = downloads.indexOfFirst { it.id == targetKey || it.localFilePath == targetKey }
            return if (idx >= 0) downloads to idx else emptyList<AutoTrackItem>() to 0
        }

        val songId = when {
            mediaId.startsWith(PREFIX_SONG) -> Uri.decode(mediaId.removePrefix(PREFIX_SONG).substringBefore("?parent="))
            else -> extras?.getString(EXTRA_SONG_ID) ?: mediaId
        }

        if (!parentId.isNullOrBlank()) {
            val contextTracks = when (parentId) {
                NODE_RECENT -> dataSource.getRecentlyPlayed()
                NODE_FAVORITES -> dataSource.getFavorites()
                NODE_RECOMMENDED -> dataSource.getRecommendedSongs()
                TAB_DOWNLOADS -> dataSource.getDownloadedTracks()
                else -> when {
                    parentId.startsWith("search:") -> dataSource.searchTracks(parentId.removePrefix("search:"))
                    parentId.startsWith(PREFIX_PLAYLIST) -> dataSource.getPlaylistTracks(parentId.removePrefix(PREFIX_PLAYLIST))
                    parentId.startsWith(PREFIX_ALBUM) -> dataSource.getAlbumTracks(parentId.removePrefix(PREFIX_ALBUM))
                    parentId.startsWith(PREFIX_ARTIST) -> dataSource.getArtistTracks(parentId.removePrefix(PREFIX_ARTIST))
                    else -> emptyList()
                }
            }
            if (contextTracks.isNotEmpty()) {
                val idx = contextTracks.indexOfFirst { it.id == songId }
                if (idx >= 0) return contextTracks to idx
            }
        }

        val fallbackTrack = AutoTrackItem(
            id = songId,
            title = mediaItem.mediaMetadata.title?.toString() ?: "Melotune",
            artist = mediaItem.mediaMetadata.artist?.toString() ?: "Melotune",
            album = mediaItem.mediaMetadata.albumTitle?.toString(),
            artworkUrl = mediaItem.mediaMetadata.artworkUri?.toString()
        )
        return listOf(fallbackTrack) to 0
    }

    private fun paginate(items: List<MediaItem>, page: Int, pageSize: Int): List<MediaItem> {
        if (page < 0 || pageSize <= 0) return items
        val fromIndex = page.toLong() * pageSize
        if (fromIndex >= items.size) return emptyList()
        val toIndex = minOf(fromIndex + pageSize, items.size.toLong())
        return items.subList(fromIndex.toInt(), toIndex.toInt())
    }

    private fun browsableFolder(id: String, title: String, mediaType: Int): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(mediaType)
            .build()
        return MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(metadata)
            .build()
    }

    private fun AutoCollectionItem.toBrowsablePlaylistItem(): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setArtworkUri(artworkUrl?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) })
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
            .build()
        return MediaItem.Builder()
            .setMediaId("$PREFIX_PLAYLIST$id")
            .setMediaMetadata(metadata)
            .build()
    }

    private fun AutoCollectionItem.toBrowsableAlbumItem(): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setArtworkUri(artworkUrl?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) })
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_ALBUM)
            .build()
        return MediaItem.Builder()
            .setMediaId("$PREFIX_ALBUM$id")
            .setMediaMetadata(metadata)
            .build()
    }

    private fun AutoCollectionItem.toBrowsableArtistItem(): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setArtworkUri(artworkUrl?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) })
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_ARTIST)
            .build()
        return MediaItem.Builder()
            .setMediaId("$PREFIX_ARTIST$id")
            .setMediaMetadata(metadata)
            .build()
    }

    private fun AutoTrackItem.toPlayableMediaItem(parentId: String): MediaItem {
        val extras = Bundle().apply {
            putString(EXTRA_CONTEXT_PARENT_ID, parentId)
            putString(EXTRA_SONG_ID, id)
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setArtworkUri(artworkUrl?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) })
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setExtras(extras)
            .build()
        return MediaItem.Builder()
            .setMediaId("$PREFIX_SONG${Uri.encode(id)}?parent=${Uri.encode(parentId)}")
            .setMediaMetadata(metadata)
            .build()
    }

    private fun AutoTrackItem.toPlayableDownloadItem(parentId: String): MediaItem {
        val extras = Bundle().apply {
            putString(EXTRA_CONTEXT_PARENT_ID, parentId)
            putString(EXTRA_SONG_ID, id)
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setExtras(extras)
            .build()
        return MediaItem.Builder()
            .setMediaId("$PREFIX_DOWNLOAD$id")
            .setMediaMetadata(metadata)
            .build()
    }
}
