package com.famage.skydrop_tv_app.receiver

import org.junit.Assert.assertEquals
import org.junit.Test

class TvSystemOverlayManagerTest {

    @Test
    fun testFormatBytes_zeroAndNegative() {
        assertEquals("0 B", TvSystemOverlayManager.formatBytes(0))
        assertEquals("0 B", TvSystemOverlayManager.formatBytes(-500))
    }

    @Test
    fun testFormatBytes_bytes() {
        assertEquals("500.0 B", TvSystemOverlayManager.formatBytes(500))
    }

    @Test
    fun testFormatBytes_kilobytes() {
        assertEquals("1.5 KB", TvSystemOverlayManager.formatBytes(1536))
    }

    @Test
    fun testFormatBytes_megabytes() {
        assertEquals("10.0 MB", TvSystemOverlayManager.formatBytes(10 * 1024 * 1024))
    }

    @Test
    fun testFormatBytes_gigabytes() {
        assertEquals("2.5 GB", TvSystemOverlayManager.formatBytes((2.5 * 1024 * 1024 * 1024).toLong()))
    }
}
