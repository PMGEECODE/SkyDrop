package com.famage.skydrop_tv_app.updater

import org.junit.Assert.*
import org.junit.Test

class TvVersionComparatorTest {

    @Test
    fun testVersionComparisonEqual() {
        assertEquals(0, TvVersionComparator.compare("1.0.0", "1.0.0"))
        assertEquals(0, TvVersionComparator.compare("v1.0.0", "1.0.0"))
        assertEquals(0, TvVersionComparator.compare("tv-v1.0.0", "1.0.0"))
        assertEquals(0, TvVersionComparator.compare("desktop-v1.2.3", "1.2.3"))
    }

    @Test
    fun testVersionComparisonNewer() {
        assertTrue(TvVersionComparator.compare("1.0.1", "1.0.0") > 0)
        assertTrue(TvVersionComparator.compare("1.1.0", "1.0.9") > 0)
        assertTrue(TvVersionComparator.compare("2.0.0", "1.99.99") > 0)
        assertTrue(TvVersionComparator.compare("v1.0.40", "1.0.39") > 0)
    }

    @Test
    fun testVersionComparisonOlder() {
        assertTrue(TvVersionComparator.compare("1.0.0", "1.0.1") < 0)
        assertTrue(TvVersionComparator.compare("1.0.39", "1.0.40") < 0)
        assertTrue(TvVersionComparator.compare("0.9.9", "1.0.0") < 0)
    }

    @Test
    fun testPrereleaseTagStripping() {
        assertEquals(0, TvVersionComparator.compare("1.0.1-beta.1", "1.0.1"))
        assertTrue(TvVersionComparator.compare("1.0.2-alpha", "1.0.1") > 0)
    }

    @Test
    fun testIsNewer() {
        assertTrue(TvVersionComparator.isNewer("1.0.40", "1.0.39"))
        assertFalse(TvVersionComparator.isNewer("1.0.39", "1.0.39"))
        assertFalse(TvVersionComparator.isNewer("1.0.38", "1.0.39"))
    }
}
