package com.famage.skydrop_tv_app

import com.famage.skydrop_tv_app.model.TvRemovableMedia
import com.famage.skydrop_tv_app.model.TvStorageOverview
import com.famage.skydrop_tv_app.model.TvStorageSpace
import org.junit.Assert.*
import org.junit.Test

class TvStorageStatsTest {

    @Test
    fun testFormatBytesZeroAndNegative() {
        assertEquals("0 B", TvStorageSpace.formatBytes(0L))
        assertEquals("0 B", TvStorageSpace.formatBytes(-100L))
    }

    @Test
    fun testFormatBytesUnits() {
        assertEquals("500 B", TvStorageSpace.formatBytes(500L))
        assertEquals("1.0 KB", TvStorageSpace.formatBytes(1024L))
        assertEquals("2.5 MB", TvStorageSpace.formatBytes((2.5 * 1024 * 1024).toLong()))
        assertEquals("7.9 GB", TvStorageSpace.formatBytes((7.9 * 1024 * 1024 * 1024).toLong()))
        assertEquals("1.5 TB", TvStorageSpace.formatBytes((1.5 * 1024 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun testTvStorageSpaceCalculations() {
        val total = 10_000_000_000L // 10 GB
        val free = 3_000_000_000L   // 3 GB
        val space = TvStorageSpace(totalBytes = total, freeBytes = free)

        assertEquals(7_000_000_000L, space.usedBytes)
        assertEquals(70, space.usedPercent)
        assertTrue(space.totalFormatted.contains("GB"))
        assertTrue(space.usedFormatted.contains("GB"))
        assertTrue(space.freeFormatted.contains("GB"))
    }

    @Test
    fun testTvStorageSpaceBoundaryConditions() {
        // Zero total bytes should not divide by zero
        val zeroSpace = TvStorageSpace(totalBytes = 0L, freeBytes = 0L)
        assertEquals(0, zeroSpace.usedPercent)
        assertEquals(0L, zeroSpace.usedBytes)

        // Free bytes exceeding total should coerce used to 0
        val overflownSpace = TvStorageSpace(totalBytes = 1000L, freeBytes = 2000L)
        assertEquals(0L, overflownSpace.usedBytes)
        assertEquals(0, overflownSpace.usedPercent)
    }

    @Test
    fun testTvStorageOverviewWithNoRemovableMedia() {
        val overview = TvStorageOverview(
            internalStorage = TvStorageSpace(totalBytes = 8_000_000_000L, freeBytes = 4_000_000_000L),
            removableMedia = emptyList()
        )

        assertTrue(overview.internalStorage.totalFormatted.isNotBlank())
        assertEquals("3.7 GB", overview.internalStorage.freeFormatted)
        assertTrue(overview.removableMedia.isEmpty())
    }

    @Test
    fun testTvStorageOverviewWithRemovableMedia() {
        val usb = TvRemovableMedia(
            id = "16D4-0E82",
            name = "ISOIMAGE",
            path = "/storage/16D4-0E82",
            space = TvStorageSpace(totalBytes = 15_000_000_000L, freeBytes = 1_000_000_000L)
        )

        val overview = TvStorageOverview(
            internalStorage = TvStorageSpace(totalBytes = 8_000_000_000L, freeBytes = 4_000_000_000L),
            removableMedia = listOf(usb)
        )

        assertFalse(overview.removableMedia.isEmpty())
        assertEquals("ISOIMAGE", overview.removableMedia[0].name)
        assertEquals(14_000_000_000L, overview.removableMedia[0].space.usedBytes)
        assertEquals(93, overview.removableMedia[0].space.usedPercent)
    }
}
