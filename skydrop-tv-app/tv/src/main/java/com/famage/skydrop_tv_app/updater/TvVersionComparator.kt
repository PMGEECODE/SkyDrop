package com.famage.skydrop_tv_app.updater

/**
 * Semantic version comparison utility mirroring the desktop app's compareVersions logic.
 * Handles prefixes like 'v', 'tv-v', 'desktop-v', release notes tags, and builds.
 */
object TvVersionComparator {

    /**
     * Cleans raw version tags (e.g. "v1.0.39", "tv-v1.1.0", "1.0.0-beta")
     * into pure numeric semantic version components.
     */
    fun cleanVersion(tag: String?): String {
        if (tag.isNullOrBlank()) return "0.0.0"
        return tag.trim()
            .replace(Regex("^(tv-|desktop-|mobile-)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^v", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    /**
     * Compares two semantic version strings (e.g. "1.0.1" vs "1.0.0").
     * Returns 1 if v1 > v2, -1 if v1 < v2, 0 if equal.
     */
    fun compare(v1: String?, v2: String?): Int {
        val s1 = cleanVersion(v1)
        val s2 = cleanVersion(v2)

        // Split into core version and prerelease tag if any
        val core1 = s1.split('-')[0]
        val core2 = s2.split('-')[0]

        val parts1 = core1.split('.').map { it.toIntOrNull() ?: 0 }
        val parts2 = core2.split('.').map { it.toIntOrNull() ?: 0 }

        val maxLen = maxOf(parts1.size, parts2.size)
        for (i in 0 until maxLen) {
            val p1 = parts1.getOrElse(i) { 0 }
            val p2 = parts2.getOrElse(i) { 0 }
            if (p1 > p2) return 1
            if (p1 < p2) return -1
        }
        return 0
    }

    /**
     * Returns true if remoteVersion is strictly newer than currentVersion.
     */
    fun isNewer(remoteVersion: String?, currentVersion: String?): Boolean {
        return compare(remoteVersion, currentVersion) > 0
    }
}
