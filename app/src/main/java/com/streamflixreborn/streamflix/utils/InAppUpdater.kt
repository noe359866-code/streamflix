package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.streamflixreborn.streamflix.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL

object InAppUpdater {

    private const val GITHUB_OWNER = "streamflix-reborn"
    private const val GITHUB_REPO = "streamflix"

    suspend fun getReleaseUpdate(): GitHub.Release? {
        val latestRelease = GitHub.Releases.getLatestRelease(GITHUB_OWNER, GITHUB_REPO)
        val currentVersion = BuildConfig.VERSION_NAME

        if (TvUpdatePolicy.compare(latestRelease.tagName.substringAfter("v"), currentVersion) > 0) {
            return latestRelease
        }
        return null
    }

    suspend fun getNewReleases(): List<GitHub.Release> {
        val releases = GitHub.Releases.getReleases(GITHUB_OWNER, GITHUB_REPO)
        val currentVersion = BuildConfig.VERSION_NAME

        val newReleases = releases
            .filter { TvUpdatePolicy.compare(it.tagName.substringAfter("v"), currentVersion) > 0 }

        return newReleases
    }

    suspend fun downloadApk(context: Context, asset: GitHub.Release.Asset): File {
        context.cacheDir.listFiles()
            ?.filter { it.extension == "apk" }
            ?.forEach { it.deleteOnExit() }

        val apk = withContext(Dispatchers.IO) {
            File.createTempFile(
                "${File(asset.name).nameWithoutExtension}-",
                ".${File(asset.name).extension}"
            )
        }

        withContext(Dispatchers.IO) {
            URL(asset.browserDownloadUrl).openStream()
        }.use { input ->
            FileOutputStream(apk).use { output -> input.copyTo(output) }
        }

        return apk
    }

    fun installApk(context: Context, uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).also { intent ->
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            intent.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            intent.data = FileProvider.getUriForFile(
                context,
                BuildConfig.APPLICATION_ID + ".provider",
                File(uri.path!!)
            )
        }
        context.startActivity(intent)
    }
}