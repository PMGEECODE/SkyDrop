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
}
