package com.harsh.shah.saavnmp3.utils

import androidx.test.core.app.ApplicationProvider
import com.harsh.shah.saavnmp3.records.sharedpref.SavedLibraries
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SharedPreferenceManagerBackupTest {

    @Before
    fun setUp() {
        SharedPreferenceManager.resetInstanceForTest()
    }

    @After
    fun tearDown() {
        SharedPreferenceManager.resetInstanceForTest()
    }

    @Test
    fun `export and restore preserves saved playlists and YouTube import metadata`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = SharedPreferenceManager.getInstance(context)

        val song = SavedLibraries.Library.Songs("s1", "Track One", "Artist A", "https://img/1.jpg")
        val lib = SavedLibraries.Library(
            id = "yt_import_PL123",
            isCreatedByUser = true,
            isAlbum = false,
            name = "Roadtrip Mix",
            image = "https://img/1.jpg",
            description = "Imported",
            songs = arrayListOf(song)
        )
        prefs.savedLibrariesData = SavedLibraries(arrayListOf(lib))
        prefs.setSavedLibraryDataById("yt_import_PL123", lib)
        prefs.markYouTubeImportedPlaylist("yt_import_PL123", "https://youtube.com/playlist?list=PL123", "PL123")

        val backupFile = File(context.cacheDir, "test_library_backup.json")
        if (backupFile.exists()) backupFile.delete()

        assertTrue(prefs.exportLibraryBackupToFile(backupFile))
        assertTrue(backupFile.exists())

        // Simulate empty DB after reinstall/update
        prefs.putJson("saved_libraries", null)
        assertFalse(prefs.restoreLibraryBackupFromFileIfEmpty(File(context.cacheDir, "missing.json")))
        assertTrue(prefs.restoreLibraryBackupFromFileIfEmpty(backupFile))

        val restored = prefs.savedLibrariesData
        assertNotNull(restored)
        assertEquals(1, restored!!.lists?.size)
        assertEquals("Roadtrip Mix", restored.lists?.first()?.name)
        assertEquals("s1", restored.lists?.first()?.songs?.first()?.id)
        assertEquals("Roadtrip Mix", prefs.getSavedLibraryDataById("yt_import_PL123")?.name)
        assertTrue(prefs.isYouTubeImportedPlaylist("yt_import_PL123"))

        // Should not overwrite non-empty libraries
        assertFalse(prefs.restoreLibraryBackupFromFileIfEmpty(backupFile))
    }
}
