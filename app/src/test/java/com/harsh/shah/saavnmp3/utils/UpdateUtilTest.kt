package com.harsh.shah.saavnmp3.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateUtilTest {

    @Test
    fun `isVersionGreater compares semver and suffixed tags accurately`() {
        assertTrue(UpdateUtil.isVersionGreater("v1.0.23", "1.0.22"))
        assertTrue(UpdateUtil.isVersionGreater("1.1.0", "1.0.99"))
        assertTrue(UpdateUtil.isVersionGreater("2.0.0-beta1", "1.9.9"))
        assertFalse(UpdateUtil.isVersionGreater("v1.0.22-auto", "1.0.22"))
        assertFalse(UpdateUtil.isVersionGreater("v1.0.21", "1.0.22"))
        assertFalse(UpdateUtil.isVersionGreater("invalid", "1.0.22"))
    }

    @Test
    fun `parseReleaseJson extracts apk asset and release metadata`() {
        val json = """
            {
              "tag_name": "v1.0.23-auto",
              "html_url": "https://github.com/LazyNinja435/MyMusicApp/releases/tag/v1.0.23-auto",
              "body": "Added built-in self-updater",
              "assets": [
                {
                  "name": "Melotune-1.0.23-debug.apk",
                  "browser_download_url": "https://github.com/LazyNinja435/MyMusicApp/releases/download/v1.0.23-auto/Melotune-1.0.23-debug.apk"
                }
              ]
            }
        """.trimIndent()

        val release = UpdateUtil.parseReleaseJson(json)
        assertNotNull(release)
        assertEquals("v1.0.23-auto", release!!.tagName)
        assertEquals("https://github.com/LazyNinja435/MyMusicApp/releases/tag/v1.0.23-auto", release.htmlUrl)
        assertEquals("Added built-in self-updater", release.releaseNotes)
        assertEquals("Melotune-1.0.23-debug.apk", release.apkName)
        assertEquals(
            "https://github.com/LazyNinja435/MyMusicApp/releases/download/v1.0.23-auto/Melotune-1.0.23-debug.apk",
            release.apkDownloadUrl
        )
    }

    @Test
    fun `parseReleaseJson handles release without apk asset`() {
        val json = """
            {
              "tag_name": "v1.0.23",
              "html_url": "https://github.com/LazyNinja435/MyMusicApp/releases/tag/v1.0.23",
              "body": "",
              "assets": [
                {
                  "name": "source.zip",
                  "browser_download_url": "https://example.com/source.zip"
                }
              ]
            }
        """.trimIndent()

        val release = UpdateUtil.parseReleaseJson(json)
        assertNotNull(release)
        assertNull(release!!.apkDownloadUrl)
        assertNull(release.apkName)
    }
}
