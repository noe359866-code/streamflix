package com.streamflixreborn.streamflix.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Immutable
import androidx.core.content.FileProvider
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.utils.DnsResolver
import com.streamflixreborn.streamflix.utils.NetworkClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.max

@Serializable
data class GitHubLatestReleaseDto(
    @SerialName("tag_name")
    val tagName: String = "",
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("published_at")
    val publishedAt: String? = null,
    @SerialName("html_url")
    val htmlUrl: String = "",
    val assets: List<GitHubReleaseAssetDto> = emptyList(),
)

@Serializable
data class GitHubReleaseAssetDto(
    val name: String = "",
    @SerialName("browser_download_url")
    val browserDownloadUrl: String = "",
    val size: Long = 0L,
    @SerialName("content_type")
    val contentType: String = "",
)

@Immutable
data class TvAvailableUpdate(
    val versionTag: String,
    val cleanVersion: String,
    val releaseTitle: String,
    val releaseNotes: String,
    val apkName: String,
    val apkDownloadUrl: String,
    val apkSizeBytes: Long,
    val repositorySlug: String,
)

sealed interface TvUpdateUiState {
    data object Idle : TvUpdateUiState
    data object Checking : TvUpdateUiState
    data class UpToDate(val currentVersion: String) : TvUpdateUiState
    data class UpdateAvailable(val update: TvAvailableUpdate) : TvUpdateUiState
    data class Downloading(
        val update: TvAvailableUpdate,
        val progressFraction: Float,
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : TvUpdateUiState
    data class ReadyToInstall(
        val update: TvAvailableUpdate,
        val apkFilePath: String,
    ) : TvUpdateUiState
    data class Error(val message: String) : TvUpdateUiState
}

enum class ApkInstallLaunchResult {
    INSTALLER_OPENED,
    NEEDS_UNKNOWN_SOURCES_PERMISSION,
    FAILED,
}

/**
 * Commercial In-App Auto-Updater for Android TV powered by GitHub Releases (`releases/latest`)
 * and OkHttp streaming downloads into `cacheDir/apk_updates`, launching Android's native package
 * installer via `FileProvider` while preserving all local Room databases and user profiles.
 */
object TvAutoUpdater {

    private const val FALLBACK_UPSTREAM_REPO = "streamflix-reborn/streamflix"
    private const val APK_MIME_TYPE = "application/vnd.android.package-archive"

    private val updaterScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeDownloadJob: Job? = null
    private var checkedAutomaticallyThisSession = false

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(DnsResolver.doh)
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private val _state = MutableStateFlow<TvUpdateUiState>(TvUpdateUiState.Idle)
    val state: StateFlow<TvUpdateUiState> = _state.asStateFlow()

    /**
     * Performs a single silent background check once per app session so a non-intrusive
     * update badge can be displayed if a newer release exists on GitHub.
     */
    fun checkSilentlyOncePerSession(
        onUpdateDetected: ((TvAvailableUpdate) -> Unit)? = null,
    ) {
        if (checkedAutomaticallyThisSession) return
        checkedAutomaticallyThisSession = true
        updaterScope.launch {
            val found = fetchNewerReleaseInternal()
            if (found != null) {
                _state.value = TvUpdateUiState.UpdateAvailable(found)
                onUpdateDetected?.invoke(found)
            } else if (_state.value is TvUpdateUiState.Idle) {
                _state.value = TvUpdateUiState.UpToDate(BuildConfig.VERSION_NAME)
            }
        }
    }

    /**
     * Explicit user-initiated check from Settings Hub -> Acerca de.
     */
    fun checkForUpdatesNow() {
        if (_state.value is TvUpdateUiState.Downloading) return
        updaterScope.launch {
            _state.value = TvUpdateUiState.Checking
            runCatching {
                fetchNewerReleaseInternalWithErrors()
            }.fold(
                onSuccess = { update ->
                    if (update != null) {
                        _state.value = TvUpdateUiState.UpdateAvailable(update)
                    } else {
                        _state.value = TvUpdateUiState.UpToDate(BuildConfig.VERSION_NAME)
                    }
                },
                onFailure = { err ->
                    _state.value = TvUpdateUiState.Error(
                        err.message ?: "No se pudo consultar GitHub Releases en este momento."
                    )
                },
            )
        }
    }

    /**
     * Streams the `.apk` file into `context.cacheDir/apk_updates/` with real-time visual progress.
     */
    fun startDownload(
        update: TvAvailableUpdate,
        context: Context = StreamFlixApp.instance,
    ) {
        if (activeDownloadJob?.isActive == true) return
        activeDownloadJob = updaterScope.launch {
            try {
                val updatesDir = File(context.applicationContext.cacheDir, "apk_updates").apply {
                    if (!exists()) mkdirs()
                }
                // Clean old cached APKs before downloading
                updatesDir.listFiles()?.forEach { file ->
                    if (file.extension.equals("apk", ignoreCase = true)) {
                        runCatching { file.delete() }
                    }
                }

                val safeFileName = update.apkName
                    .replace(Regex("[^A-Za-z0-9._-]"), "_")
                    .let { if (it.endsWith(".apk", ignoreCase = true)) it else "$it.apk" }
                val targetFile = File(updatesDir, safeFileName)

                _state.value = TvUpdateUiState.Downloading(
                    update = update,
                    progressFraction = 0f,
                    downloadedBytes = 0L,
                    totalBytes = update.apkSizeBytes,
                )

                val request = Request.Builder()
                    .url(update.apkDownloadUrl)
                    .header("User-Agent", NetworkClient.USER_AGENT)
                    .header("Accept", "application/octet-stream,*/*")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        error("Error al descargar el paquete APK (HTTP ${response.code}).")
                    }
                    val body = response.body ?: error("El archivo APK descargado está vacío.")
                    val contentLen = body.contentLength().takeIf { it > 0L } ?: update.apkSizeBytes

                    body.byteStream().use { input ->
                        FileOutputStream(targetFile).use { output ->
                            val buffer = ByteArray(16 * 1024)
                            var downloaded = 0L
                            var lastEmitMs = 0L

                            while (isActive) {
                                val read = input.read(buffer)
                                if (read == -1) break
                                output.write(buffer, 0, read)
                                downloaded += read

                                val now = System.currentTimeMillis()
                                if (now - lastEmitMs >= 90L) {
                                    lastEmitMs = now
                                    val pct = if (contentLen > 0L) {
                                        (downloaded.toFloat() / contentLen.toFloat()).coerceIn(0f, 0.99f)
                                    } else {
                                        0.5f
                                    }
                                    _state.value = TvUpdateUiState.Downloading(
                                        update = update,
                                        progressFraction = pct,
                                        downloadedBytes = downloaded,
                                        totalBytes = contentLen,
                                    )
                                }
                            }
                            output.flush()
                        }
                    }

                    if (!isActive) {
                        runCatching { targetFile.delete() }
                        _state.value = TvUpdateUiState.UpdateAvailable(update)
                        return@use
                    }

                    if (!targetFile.exists() || targetFile.length() < 32_768L) {
                        runCatching { targetFile.delete() }
                        error("El archivo APK descargado está incompleto o dañado.")
                    }

                    _state.value = TvUpdateUiState.ReadyToInstall(
                        update = update,
                        apkFilePath = targetFile.absolutePath,
                    )
                }
            } catch (ce: CancellationException) {
                _state.value = TvUpdateUiState.UpdateAvailable(update)
                throw ce
            } catch (t: Throwable) {
                _state.value = TvUpdateUiState.Error(
                    t.message ?: "Error de red durante la descarga de la actualización."
                )
            }
        }
    }

    fun cancelDownload() {
        val current = _state.value
        activeDownloadJob?.cancel()
        activeDownloadJob = null
        if (current is TvUpdateUiState.Downloading) {
            _state.value = TvUpdateUiState.UpdateAvailable(current.update)
        }
    }

    /**
     * Launches Android's native Package Installer transparently using `FileProvider` so all Room
     * profile databases, watch history, Trakt tokens, and custom add-ons remain 100% intact.
     */
    fun launchNativeApkInstaller(
        context: Context,
        apkFilePath: String,
    ): ApkInstallLaunchResult {
        val apkFile = File(apkFilePath)
        if (!apkFile.exists()) return ApkInstallLaunchResult.FAILED

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            val permIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val openedSettings = runCatching {
                context.startActivity(permIntent)
                true
            }.getOrDefault(false)
            if (openedSettings) {
                return ApkInstallLaunchResult.NEEDS_UNKNOWN_SOURCES_PERMISSION
            }
        }

        return runCatching {
            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                apkFile,
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, APK_MIME_TYPE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            }
            context.startActivity(installIntent)
            ApkInstallLaunchResult.INSTALLER_OPENED
        }.getOrDefault(ApkInstallLaunchResult.FAILED)
    }

    private suspend fun fetchNewerReleaseInternal(): TvAvailableUpdate? = withContext(Dispatchers.IO) {
        runCatching { fetchNewerReleaseInternalWithErrors() }.getOrNull()
    }

    private suspend fun fetchNewerReleaseInternalWithErrors(): TvAvailableUpdate? = withContext(Dispatchers.IO) {
        val candidateRepos = listOfNotNull(
            BuildConfig.GITHUB_UPDATE_REPO.trim().takeIf { it.contains("/") },
            FALLBACK_UPSTREAM_REPO,
        ).distinct()

        var anySuccessfulCheck = false
        var lastError: String? = null

        for (repoSlug in candidateRepos) {
            val apiUrl = "https://api.github.com/repos/$repoSlug/releases/latest"
            val req = Request.Builder()
                .url(apiUrl)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", NetworkClient.USER_AGENT)
                .build()

            val checkResult = runCatching {
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.code == 404) {
                        // Repo has no published releases yet; try fallback repo
                        return@use null
                    }
                    if (!resp.isSuccessful) {
                        error("GitHub API respondió HTTP ${resp.code}.")
                    }
                    anySuccessfulCheck = true
                    val rawBody = resp.body?.string().orEmpty()
                    val dto = json.decodeFromString<GitHubLatestReleaseDto>(rawBody)
                    if (dto.draft || dto.prerelease) return@use null

                    val cleanRemoteVer = normalizeVersionString(dto.tagName)
                    val cleanLocalVer = normalizeVersionString(BuildConfig.VERSION_NAME)
                    if (compareSemanticVersions(cleanRemoteVer, cleanLocalVer) <= 0) {
                        return@use null
                    }

                    val apkAsset = selectBestApkAsset(dto.assets) ?: return@use null
                    TvAvailableUpdate(
                        versionTag = dto.tagName,
                        cleanVersion = cleanRemoteVer,
                        releaseTitle = dto.name?.takeIf { it.isNotBlank() } ?: "Versión ${dto.tagName}",
                        releaseNotes = dto.body?.trim()?.takeIf { it.isNotBlank() }
                            ?: "Mejoras de rendimiento, estabilidad y reproducción en Android TV.",
                        apkName = apkAsset.name,
                        apkDownloadUrl = apkAsset.browserDownloadUrl,
                        apkSizeBytes = apkAsset.size,
                        repositorySlug = repoSlug,
                    )
                }
            }

            checkResult.fold(
                onSuccess = { maybeUpdate ->
                    if (maybeUpdate != null) return@withContext maybeUpdate
                },
                onFailure = { err ->
                    lastError = err.message
                },
            )
        }

        if (!anySuccessfulCheck && lastError != null) {
            error(lastError!!)
        }
        null
    }

    private fun selectBestApkAsset(assets: List<GitHubReleaseAssetDto>): GitHubReleaseAssetDto? {
        val apkAssets = assets.filter {
            it.browserDownloadUrl.isNotBlank() && it.name.endsWith(".apk", ignoreCase = true)
        }
        if (apkAssets.isEmpty()) return null
        if (apkAssets.size == 1) return apkAssets.first()

        val supportedAbis = Build.SUPPORTED_ABIS.map { it.lowercase(Locale.ROOT) }
        // 1. Prefer explicit TV + matching ABI APK if published
        for (abi in supportedAbis) {
            apkAssets.firstOrNull { a ->
                val lower = a.name.lowercase(Locale.ROOT)
                lower.contains("tv") && lower.contains(abi)
            }?.let { return it }
        }
        // 2. Prefer explicit TV APK
        apkAssets.firstOrNull { it.name.contains("tv", ignoreCase = true) }?.let { return it }
        // 3. Prefer ABI-matching APK
        for (abi in supportedAbis) {
            apkAssets.firstOrNull { it.name.lowercase(Locale.ROOT).contains(abi) }?.let { return it }
        }
        // 4. Universal / first APK
        return apkAssets.firstOrNull { it.name.contains("universal", ignoreCase = true) }
            ?: apkAssets.first()
    }

    internal fun normalizeVersionString(raw: String): String {
        return raw.trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore("-")
            .substringBefore("+")
            .trim()
    }

    internal fun compareSemanticVersions(a: String, b: String): Int {
        val partsA = a.split(".").map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val partsB = b.split(".").map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val maxLen = max(partsA.size, partsB.size)
        for (i in 0 until maxLen) {
            val va = partsA.getOrNull(i) ?: 0
            val vb = partsB.getOrNull(i) ?: 0
            if (va != vb) return va.compareTo(vb)
        }
        return 0
    }

    fun formatMegabytes(bytes: Long): String {
        if (bytes <= 0L) return "0.0 MB"
        val mb = bytes.toDouble() / (1024.0 * 1024.0)
        return String.format(Locale.US, "%.1f MB", mb)
    }
}
