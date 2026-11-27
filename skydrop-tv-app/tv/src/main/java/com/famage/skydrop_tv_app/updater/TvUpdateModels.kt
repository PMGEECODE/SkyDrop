package com.famage.skydrop_tv_app.updater

/**
 * Metadata representation of a release asset hosted on GitHub.
 */
data class TvReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long
)

/**
 * Result data payload when checking for updates.
 */
data class TvUpdateInfo(
    val isUpdateAvailable: Boolean,
    val currentVersion: String,
    val latestVersion: String,
    val releaseTitle: String,
    val releaseNotes: String,
    val releaseUrl: String,
    val targetAsset: TvReleaseAsset?,
    val checksumAsset: TvReleaseAsset? = null,
    val errorMessage: String? = null
)

/**
 * Live download progress updates.
 */
data class TvUpdateDownloadProgress(
    val percent: Int,
    val bytesRead: Long,
    val totalBytes: Long
)
