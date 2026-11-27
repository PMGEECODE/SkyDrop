package com.famage.skydrop_tv_app.updater

/**
 * Central configuration for GitHub Releases updater.
 * Holds repository coordinates and target asset selection rules.
 */
object TvUpdateConfig {
    const val DEFAULT_REPO_OWNER = "PMGEECODE"
    const val DEFAULT_REPO_NAME = "skydrop-release"

    // Primary releases repository URL
    const val RELEASES_REPO_URL = "https://github.com/$DEFAULT_REPO_OWNER/$DEFAULT_REPO_NAME"
    const val API_RELEASES_URL = "https://api.github.com/repos/$DEFAULT_REPO_OWNER/$DEFAULT_REPO_NAME/releases"

    // User-Agent identifying the client
    const val USER_AGENT = "SkyDrop-TV-App"

    // TV specific APK identification keywords
    val TV_ASSET_KEYWORDS = listOf("tv", "leanback", "android-tv")
    
    // Non-TV / Desktop extensions that must NEVER be downloaded by the TV app
    val EXCLUDED_EXTENSIONS = listOf(".exe", ".deb", ".appimage", ".tar.gz", ".zip", ".msi", ".dmg")

    /**
     * Selects the correct APK asset for the TV app:
     * - Strictly rejects desktop assets (.exe, .deb, .AppImage)
     * - Prefers APKs containing "tv", "leanback", or "android-tv" in the filename
     * - Falls back to a standard .apk if it is the only Android asset available
     */
    fun selectTargetApkAsset(assets: List<TvReleaseAsset>): TvReleaseAsset? {
        val apkAssets = assets.filter { asset ->
            val name = asset.name.lowercase()
            name.endsWith(".apk") && EXCLUDED_EXTENSIONS.none { name.endsWith(it) }
        }

        if (apkAssets.isEmpty()) return null

        // Look for explicit TV build first
        val tvSpecific = apkAssets.firstOrNull { asset ->
            val name = asset.name.lowercase()
            TV_ASSET_KEYWORDS.any { name.contains(it) }
        }

        return tvSpecific ?: apkAssets.firstOrNull()
    }
}
