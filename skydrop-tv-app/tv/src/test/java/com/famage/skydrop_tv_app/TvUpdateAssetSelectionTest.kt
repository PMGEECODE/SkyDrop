package com.famage.skydrop_tv_app.updater

import org.junit.Assert.*
import org.junit.Test

class TvUpdateAssetSelectionTest {

    @Test
    fun testSelectsTvApkOverDesktopAssets() {
        val assets = listOf(
            TvReleaseAsset("skydrop-desktop_1.0.39_amd64.deb", "https://example.com/deb", 50000),
            TvReleaseAsset("SkyDrop-1.0.39.AppImage", "https://example.com/appimage", 80000),
            TvReleaseAsset("SkyDrop Setup 1.0.39.exe", "https://example.com/exe", 70000),
            TvReleaseAsset("SkyDrop-tv-1.0.39.apk", "https://example.com/tv-apk", 35000)
        )

        val target = TvUpdateConfig.selectTargetApkAsset(assets)
        assertNotNull(target)
        assertEquals("SkyDrop-tv-1.0.39.apk", target?.name)
    }

    @Test
    fun testRejectsDesktopAssetsWhenNoApkExists() {
        val assets = listOf(
            TvReleaseAsset("skydrop-desktop_1.0.39_amd64.deb", "https://example.com/deb", 50000),
            TvReleaseAsset("SkyDrop-1.0.39.AppImage", "https://example.com/appimage", 80000),
            TvReleaseAsset("SkyDrop Setup 1.0.39.exe", "https://example.com/exe", 70000)
        )

        val target = TvUpdateConfig.selectTargetApkAsset(assets)
        assertNull("TV app must NEVER select desktop installer", target)
    }

    @Test
    fun testPrefersTvSpecificApkOverGenericApk() {
        val assets = listOf(
            TvReleaseAsset("SkyDrop-mobile-1.0.39.apk", "https://example.com/mobile-apk", 30000),
            TvReleaseAsset("SkyDrop-tv-1.0.39.apk", "https://example.com/tv-apk", 35000)
        )

        val target = TvUpdateConfig.selectTargetApkAsset(assets)
        assertNotNull(target)
        assertEquals("SkyDrop-tv-1.0.39.apk", target?.name)
    }

    @Test
    fun testMultiReleaseCandidateSelectionFiltersOutDesktopOnlyReleases() {
        // Desktop release v1.0.42 (only has desktop assets)
        val desktopAssets = listOf(
            TvReleaseAsset("SkyDrop_1.0.42_amd64.deb", "https://example.com/deb", 50000),
            TvReleaseAsset("SkyDrop-1.0.42.AppImage", "https://example.com/appimage", 80000),
            TvReleaseAsset("SkyDrop-Setup-1.0.42.exe", "https://example.com/exe", 70000)
        )
        // TV release tv-v1.0.42 (has TV APK)
        val tvAssets = listOf(
            TvReleaseAsset("SkyDrop-tv-1.0.42.apk", "https://example.com/tv-apk", 35000),
            TvReleaseAsset("sha256sums.txt", "https://example.com/sha256", 100)
        )

        val desktopTarget = TvUpdateConfig.selectTargetApkAsset(desktopAssets)
        val tvTarget = TvUpdateConfig.selectTargetApkAsset(tvAssets)

        assertNull("Desktop assets must be skipped by updater candidate selector", desktopTarget)
        assertNotNull("TV release asset must be selected", tvTarget)
        assertEquals("SkyDrop-tv-1.0.42.apk", tvTarget?.name)
    }

    @Test
    fun testVersionComparisonWithTvAndDesktopPrefixes() {
        assertEquals("1.0.42", TvVersionComparator.cleanVersion("tv-v1.0.42"))
        assertEquals("1.0.42", TvVersionComparator.cleanVersion("v1.0.42"))
        assertEquals("1.0.42", TvVersionComparator.cleanVersion("desktop-v1.0.42"))
        assertTrue(TvVersionComparator.isNewer("tv-v1.0.42", "1.0.41"))
        assertFalse(TvVersionComparator.isNewer("tv-v1.0.42", "1.0.42"))
    }
}
