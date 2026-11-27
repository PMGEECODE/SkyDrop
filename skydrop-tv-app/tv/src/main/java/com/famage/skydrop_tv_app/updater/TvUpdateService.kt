package com.famage.skydrop_tv_app.updater

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Service managing GitHub release checking, APK asset selection, download with SHA-256 verification,
 * and prompting the Android package installer.
 */
class TvUpdateService(
    private val context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
) {
    companion object {
        private const val TAG = "TvUpdateService"

        @Volatile
        private var hasCheckedOnLaunch = false

        fun resetLaunchCheckFlag() {
            hasCheckedOnLaunch = false
        }
    }

    /**
     * Retrieves the installed version name (e.g. "1.0").
     */
    fun getCurrentVersionName(): String {
        return try {
            val pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            pInfo.versionName ?: "1.0"
        } catch (_: Exception) {
            "1.0"
        }
    }

    /**
     * Checks GitHub releases on app launch only.
     * Prevents hitting GitHub API rate limits on orientation changes or activity recreations.
     */
    suspend fun checkUpdateOnLaunch(force: Boolean = false): TvUpdateInfo? {
        if (!force && hasCheckedOnLaunch) {
            Log.d(TAG, "Already checked for updates on this app session. Skipping.")
            return null
        }
        hasCheckedOnLaunch = true
        return checkGitHubReleases()
    }

    /**
     * Queries the GitHub Releases API (with seamless fallback to Atom feed & expanded assets
     * when unauthenticated GitHub API rate limits occur) and determines if a newer release with a matching TV APK exists.
     */
    suspend fun checkGitHubReleases(): TvUpdateInfo = withContext(Dispatchers.IO) {
        val currentVersion = getCurrentVersionName()
        try {
            val request = Request.Builder()
                .url(TvUpdateConfig.API_RELEASES_URL)
                .header("User-Agent", TvUpdateConfig.USER_AGENT)
                .header("Accept", "application/vnd.github.v3+json")
                .build()

            val response = client.newCall(request).execute()
            val code = response.code
            val body = response.body?.string().orEmpty()

            if (response.isSuccessful && body.isNotBlank()) {
                val releasesArray = JSONArray(body)
                var bestCandidateRelease: JSONObject? = null
                var bestCandidateVersion = ""
                var bestTargetAsset: TvReleaseAsset? = null
                var bestChecksumAsset: TvReleaseAsset? = null

                for (i in 0 until releasesArray.length()) {
                    val rel = releasesArray.getJSONObject(i)
                    if (rel.optBoolean("draft", false)) continue

                    val tagName = rel.optString("tag_name", rel.optString("name", ""))
                    val cleanTag = TvVersionComparator.cleanVersion(tagName)

                    val assetsJson = rel.optJSONArray("assets") ?: JSONArray()
                    val allAssets = mutableListOf<TvReleaseAsset>()
                    for (j in 0 until assetsJson.length()) {
                        val a = assetsJson.getJSONObject(j)
                        allAssets.add(
                            TvReleaseAsset(
                                name = a.optString("name", ""),
                                downloadUrl = a.optString("browser_download_url", ""),
                                sizeBytes = a.optLong("size", 0L)
                            )
                        )
                    }

                    // Only consider releases that actually contain a valid TV APK asset
                    val targetAsset = TvUpdateConfig.selectTargetApkAsset(allAssets) ?: continue

                    if (bestCandidateRelease == null || TvVersionComparator.compare(cleanTag, bestCandidateVersion) > 0) {
                        bestCandidateRelease = rel
                        bestCandidateVersion = cleanTag
                        bestTargetAsset = targetAsset
                        bestChecksumAsset = allAssets.firstOrNull {
                            val lower = it.name.lowercase()
                            lower.contains("sha256") || lower.contains("checksum")
                        }
                    }
                }

                if (bestCandidateRelease != null && bestTargetAsset != null) {
                    val isNewer = TvVersionComparator.isNewer(bestCandidateVersion, currentVersion)
                    val releaseTitle = bestCandidateRelease.optString("name", "v$bestCandidateVersion")
                    val releaseNotes = bestCandidateRelease.optString("body", "No release notes provided.")
                    val releaseUrl = bestCandidateRelease.optString("html_url", TvUpdateConfig.RELEASES_REPO_URL)

                    return@withContext TvUpdateInfo(
                        isUpdateAvailable = isNewer,
                        currentVersion = currentVersion,
                        latestVersion = bestCandidateVersion,
                        releaseTitle = releaseTitle,
                        releaseNotes = releaseNotes,
                        releaseUrl = releaseUrl,
                        targetAsset = bestTargetAsset,
                        checksumAsset = bestChecksumAsset,
                        errorMessage = null
                    )
                }
            } else {
                Log.w(TAG, "GitHub API returned HTTP $code; attempting Atom feed fallback...")
            }
        } catch (e: Exception) {
            Log.w(TAG, "API check exception (${e.message}); attempting Atom feed fallback...")
        }

        // Fallback to Atom feed and expanded assets (avoids GitHub API 60 req/hr rate limit)
        checkReleasesViaAtomFeed(currentVersion)
    }

    /**
     * Checks releases via GitHub Atom feed and expanded_assets.
     * Guaranteed to work without hitting unauthenticated GitHub API rate limits.
     */
    suspend fun checkReleasesViaAtomFeed(currentVersion: String): TvUpdateInfo = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(TvUpdateConfig.ATOM_RELEASES_URL)
                .header("User-Agent", TvUpdateConfig.USER_AGENT)
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext TvUpdateInfo(
                    isUpdateAvailable = false,
                    currentVersion = currentVersion,
                    latestVersion = currentVersion,
                    releaseTitle = "",
                    releaseNotes = "",
                    releaseUrl = TvUpdateConfig.RELEASES_REPO_URL,
                    targetAsset = null,
                    errorMessage = "Update check failed (HTTP ${response.code})"
                )
            }

            val xml = response.body?.string().orEmpty()
            val entryBlocks = xml.split("<entry>").drop(1)
            if (entryBlocks.isEmpty()) {
                return@withContext TvUpdateInfo(
                    isUpdateAvailable = false,
                    currentVersion = currentVersion,
                    latestVersion = currentVersion,
                    releaseTitle = "",
                    releaseNotes = "",
                    releaseUrl = TvUpdateConfig.RELEASES_REPO_URL,
                    targetAsset = null,
                    errorMessage = null
                )
            }

            var bestTag = ""
            var bestVersion = ""
            var bestTitle = ""
            var bestTargetAsset: TvReleaseAsset? = null
            var bestChecksumAsset: TvReleaseAsset? = null

            for (block in entryBlocks) {
                val tagMatch = Regex("<id>[^<]*/([^/<]+)</id>").find(block)
                    ?: Regex("href=\"[^\"]*/releases/tag/([^\"]+)\"").find(block)
                val rawTag = tagMatch?.groupValues?.get(1)?.trim().orEmpty()
                if (rawTag.isBlank()) continue

                val titleMatch = Regex("<title>([^<]+)</title>").find(block)
                val rawTitle = titleMatch?.groupValues?.get(1)?.trim().orEmpty()

                val cleanTag = TvVersionComparator.cleanVersion(rawTag)

                // If we already found a valid target asset and this entry version is not higher, skip checking expanded assets
                if (bestTargetAsset != null && TvVersionComparator.compare(cleanTag, bestVersion) <= 0) {
                    continue
                }

                // Query expanded_assets for this release tag
                val assetsUrl = "${TvUpdateConfig.EXPANDED_ASSETS_URL}/$rawTag"
                val assetsReq = Request.Builder()
                    .url(assetsUrl)
                    .header("User-Agent", TvUpdateConfig.USER_AGENT)
                    .build()

                try {
                    val assetsRes = client.newCall(assetsReq).execute()
                    if (assetsRes.isSuccessful) {
                        val html = assetsRes.body?.string().orEmpty()
                        val assetMatches = Regex("href=[\"'](/${TvUpdateConfig.DEFAULT_REPO_OWNER}/${TvUpdateConfig.DEFAULT_REPO_NAME}/releases/download/[^\"']+)[\"']").findAll(html)
                        val assetsList = mutableListOf<TvReleaseAsset>()
                        for (m in assetMatches) {
                            val path = m.groupValues[1]
                            val assetName = path.substringAfterLast("/")
                            val downloadUrl = "https://github.com$path"
                            assetsList.add(TvReleaseAsset(name = assetName, downloadUrl = downloadUrl, sizeBytes = 0L))
                        }

                        val targetAsset = TvUpdateConfig.selectTargetApkAsset(assetsList)
                        if (targetAsset != null) {
                            bestTag = rawTag
                            bestVersion = cleanTag
                            bestTitle = if (rawTitle.isNotBlank()) rawTitle else "SkyDrop TV v$cleanTag"
                            bestTargetAsset = targetAsset
                            bestChecksumAsset = assetsList.firstOrNull {
                                val lower = it.name.lowercase()
                                lower.contains("sha256") || lower.contains("checksum")
                            }
                        }
                    }
                } catch (ae: Exception) {
                    Log.w(TAG, "Failed to inspect expanded assets for $rawTag: ${ae.message}")
                }
            }

            if (bestTargetAsset != null) {
                val isNewer = TvVersionComparator.isNewer(bestVersion, currentVersion)
                return@withContext TvUpdateInfo(
                    isUpdateAvailable = isNewer,
                    currentVersion = currentVersion,
                    latestVersion = bestVersion,
                    releaseTitle = bestTitle,
                    releaseNotes = "Release notes available at GitHub.",
                    releaseUrl = "${TvUpdateConfig.RELEASES_REPO_URL}/releases/tag/$bestTag",
                    targetAsset = bestTargetAsset,
                    checksumAsset = bestChecksumAsset,
                    errorMessage = null
                )
            }

            TvUpdateInfo(
                isUpdateAvailable = false,
                currentVersion = currentVersion,
                latestVersion = currentVersion,
                releaseTitle = "",
                releaseNotes = "",
                releaseUrl = TvUpdateConfig.RELEASES_REPO_URL,
                targetAsset = null,
                errorMessage = null
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check releases via Atom feed: ${e.message}")
            TvUpdateInfo(
                isUpdateAvailable = false,
                currentVersion = currentVersion,
                latestVersion = currentVersion,
                releaseTitle = "",
                releaseNotes = "",
                releaseUrl = TvUpdateConfig.RELEASES_REPO_URL,
                targetAsset = null,
                errorMessage = e.message
            )
        }
    }

    /**
     * Selects the correct APK asset for the TV app:
     * - Strictly rejects desktop assets (.exe, .deb, .AppImage)
     * - Prefers APKs containing "tv", "leanback", or "android-tv" in the filename
     * - Falls back to a standard .apk if it is the only Android asset available
     */
    fun selectTargetApkAsset(assets: List<TvReleaseAsset>): TvReleaseAsset? {
        return TvUpdateConfig.selectTargetApkAsset(assets)
    }

    /**
     * Downloads the APK file to the application cache directory with real-time progress,
     * verifies SHA-256 integrity if checksum asset is available, and returns the target File.
     */
    suspend fun downloadApk(
        asset: TvReleaseAsset,
        checksumAsset: TvReleaseAsset?,
        onProgress: (TvUpdateDownloadProgress) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val downloadDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val apkFile = File(downloadDir, asset.name)

            val request = Request.Builder()
                .url(asset.downloadUrl)
                .header("User-Agent", TvUpdateConfig.USER_AGENT)
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Download failed with HTTP ${response.code}"))
            }

            val body = response.body ?: return@withContext Result.failure(Exception("Empty response body"))
            val totalBytes = if (body.contentLength() > 0) body.contentLength() else asset.sizeBytes

            var bytesRead = 0L
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8192)

            body.byteStream().use { input ->
                FileOutputStream(apkFile).use { output ->
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        bytesRead += read

                        val pct = if (totalBytes > 0) ((bytesRead * 100) / totalBytes).toInt().coerceIn(0, 100) else 0
                        onProgress(TvUpdateDownloadProgress(pct, bytesRead, totalBytes))
                    }
                    output.flush()
                }
            }

            val calculatedHash = digest.digest().joinToString("") { "%02x".format(it) }

            // Verify checksum against checksum asset if present
            if (checksumAsset != null) {
                try {
                    val checkReq = Request.Builder().url(checksumAsset.downloadUrl).build()
                    val checkRes = client.newCall(checkReq).execute()
                    if (checkRes.isSuccessful) {
                        val checkBody = checkRes.body?.string().orEmpty()
                        if (!checkBody.lowercase().contains(calculatedHash.lowercase())) {
                            apkFile.delete()
                            return@withContext Result.failure(Exception("Checksum verification failed for ${asset.name}"))
                        }
                    }
                } catch (ce: Exception) {
                    Log.w(TAG, "Checksum verification network error (proceeding): ${ce.message}")
                }
            }

            Result.success(apkFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Checks if the app has permission to request installing packages (Android 8.0+).
     */
    fun canInstallApks(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    /**
     * Prompts the system package installer to install the downloaded APK.
     */
    fun promptInstallApk(apkFile: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
