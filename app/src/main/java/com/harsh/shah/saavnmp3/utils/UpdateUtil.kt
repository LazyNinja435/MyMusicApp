package com.harsh.shah.saavnmp3.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.harsh.shah.saavnmp3.network.utility.RequestNetwork
import com.harsh.shah.saavnmp3.network.utility.RequestNetworkController
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object UpdateUtil {

    private const val TAG = "UpdateUtil"
    const val RELEASES_LATEST_API_URL =
        "https://api.github.com/repos/LazyNinja435/MyMusicApp/releases/latest"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val downloadClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    data class ReleaseInfo(
        val tagName: String,
        val htmlUrl: String,
        val releaseNotes: String,
        val apkDownloadUrl: String?,
        val apkName: String?
    )

    fun checkForUpdates(context: Context, showToasts: Boolean = false) {
        val requestNetwork = RequestNetwork(context)
        requestNetwork.startRequestNetwork(
            RequestNetworkController.GET,
            RELEASES_LATEST_API_URL,
            "UPDATE_CHECK",
            object : RequestNetwork.RequestListener {
                override fun onResponse(
                    tag: String?,
                    response: String?,
                    responseHeaders: HashMap<String?, Any?>?
                ) {
                    try {
                        val release = parseReleaseJson(response.orEmpty())
                        if (release == null) {
                            if (showToasts) {
                                Toast.makeText(context, "No release information found.", Toast.LENGTH_SHORT).show()
                            }
                            return
                        }

                        val currentVersion = context.packageManager
                            .getPackageInfo(context.packageName, 0).versionName ?: ""

                        if (isVersionGreater(release.tagName, currentVersion)) {
                            showUpdateDialog(context, release)
                        } else if (showToasts) {
                            Toast.makeText(context, "You are on the latest version ($currentVersion)!", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse update info", e)
                        if (showToasts) {
                            Toast.makeText(context, "Failed to check for updates.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                override fun onErrorResponse(tag: String?, message: String?) {
                    Log.e(TAG, "Update check failed: $message")
                    if (showToasts) {
                        Toast.makeText(context, "Failed to check for updates.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    internal fun parseReleaseJson(rawJson: String): ReleaseInfo? {
        if (rawJson.isBlank()) return null
        val jsonObject = JSONObject(rawJson)
        val tagName = jsonObject.optString("tag_name", "").trim()
        if (tagName.isEmpty()) return null

        val htmlUrl = jsonObject.optString("html_url", "")
        val body = jsonObject.optString("body", "").trim()
        val assets = jsonObject.optJSONArray("assets")

        var apkUrl: String? = null
        var apkName: String? = null
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name", "")
                val url = asset.optString("browser_download_url", "")
                if (name.endsWith(".apk", ignoreCase = true) && url.isNotBlank()) {
                    apkUrl = url
                    apkName = name
                    break
                }
            }
        }

        return ReleaseInfo(
            tagName = tagName,
            htmlUrl = htmlUrl,
            releaseNotes = body,
            apkDownloadUrl = apkUrl,
            apkName = apkName
        )
    }

    internal fun isVersionGreater(latestRaw: String, currentRaw: String): Boolean {
        val latestClean = latestRaw.trim().removePrefix("v").removePrefix("V")
        val currentClean = currentRaw.trim().removePrefix("v").removePrefix("V")

        val latestNums = extractVersionNumbers(latestClean)
        val currentNums = extractVersionNumbers(currentClean)

        val length = kotlin.math.max(latestNums.size, currentNums.size)
        for (i in 0 until length) {
            val l = latestNums.getOrElse(i) { 0 }
            val c = currentNums.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }

        // If numeric versions match, a release tag without pre-release suffix is newer than a pre-release,
        // or a differently suffixed tag isn't treated as older.
        return false
    }

    private fun extractVersionNumbers(version: String): List<Int> {
        val base = version.substringBefore("-").substringBefore("+")
        return base.split(".").map { segment ->
            Regex("\\d+").find(segment)?.value?.toIntOrNull() ?: 0
        }
    }

    private fun showUpdateDialog(context: Context, release: ReleaseInfo) {
        if (context is Activity && (context.isFinishing || context.isDestroyed)) return

        val notesPreview = if (release.releaseNotes.isNotBlank()) {
            "\n\n${release.releaseNotes.take(400)}"
        } else {
            ""
        }

        val builder = MaterialAlertDialogBuilder(context)
            .setTitle("Update Available (${release.tagName})")
            .setMessage("A new version of Melotune is available on GitHub.$notesPreview")
            .setNegativeButton("Later", null)

        if (!release.apkDownloadUrl.isNullOrBlank()) {
            builder.setPositiveButton("Update Now") { _, _ ->
                startInAppUpdate(context, release)
            }
            if (release.htmlUrl.isNotBlank()) {
                builder.setNeutralButton("View Release") { _, _ ->
                    openReleasePage(context, release.htmlUrl)
                }
            }
        } else if (release.htmlUrl.isNotBlank()) {
            builder.setPositiveButton("Download on GitHub") { _, _ ->
                openReleasePage(context, release.htmlUrl)
            }
        }

        builder.show()
    }

    private fun openReleasePage(context: Context, htmlUrl: String) {
        val intent = Intent(Intent.ACTION_VIEW, htmlUrl.withUtmParameters("update", "release_page").toUri())
        context.startActivity(intent)
    }

    private fun startInAppUpdate(context: Context, release: ReleaseInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            MaterialAlertDialogBuilder(context)
                .setTitle("Allow App Installs")
                .setMessage("To install updates directly inside Melotune, allow installing unknown apps from this source.")
                .setPositiveButton("Open Settings") { _, _ ->
                    val settingsIntent = Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        "package:${context.packageName}".toUri()
                    )
                    context.startActivity(settingsIntent)
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        val apkUrl = release.apkDownloadUrl ?: return
        val progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            progress = 0
        }
        val statusLabel = TextView(context).apply {
            text = "Downloading ${release.apkName ?: "update"}..."
            setPadding(0, 0, 0, 16)
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(48, 32, 48, 32)
            addView(statusLabel)
            addView(progressBar)
        }

        val cancelled = AtomicBoolean(false)
        val progressDialog: AlertDialog = MaterialAlertDialogBuilder(context)
            .setTitle("Updating Melotune")
            .setView(container)
            .setCancelable(false)
            .setNegativeButton("Cancel") { _, _ -> cancelled.set(true) }
            .show()

        Thread {
            try {
                val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
                val apkFile = File(updatesDir, "melotune-update.apk")
                if (apkFile.exists()) apkFile.delete()

                val request = Request.Builder().url(apkUrl).get().build()
                downloadClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("Download failed (HTTP ${response.code})")
                    }
                    val body = response.body ?: throw IllegalStateException("Empty response body")
                    val totalBytes = body.contentLength()
                    body.byteStream().use { input ->
                        FileOutputStream(apkFile).use { output ->
                            val buffer = ByteArray(16 * 1024)
                            var downloaded = 0L
                            while (true) {
                                if (cancelled.get()) throw InterruptedException("Cancelled")
                                val read = input.read(buffer)
                                if (read == -1) break
                                output.write(buffer, 0, read)
                                downloaded += read
                                if (totalBytes > 0) {
                                    val pct = ((downloaded * 100) / totalBytes).toInt().coerceIn(0, 100)
                                    mainHandler.post {
                                        progressBar.progress = pct
                                        statusLabel.text = "Downloading ${release.apkName ?: "update"}... $pct%"
                                    }
                                } else {
                                    mainHandler.post {
                                        progressBar.isIndeterminate = true
                                    }
                                }
                            }
                        }
                    }
                }

                mainHandler.post {
                    if (progressDialog.isShowing) progressDialog.dismiss()
                    if (!cancelled.get()) {
                        installApk(context, apkFile)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "In-app update download failed", e)
                mainHandler.post {
                    if (progressDialog.isShowing) progressDialog.dismiss()
                    if (!cancelled.get()) {
                        Toast.makeText(
                            context,
                            "Update download failed: ${e.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }.start()
    }

    private fun installApk(context: Context, apkFile: File) {
        val apkUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(installIntent)
    }
}
