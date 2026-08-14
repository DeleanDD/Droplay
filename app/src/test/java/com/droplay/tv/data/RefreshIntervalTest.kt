package com.droplay.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RefreshIntervalTest {
    @Test fun defaultIntervalsAreOrderedAndEveryLaunchHasNoCacheWindow() {
        assertEquals(0L, RefreshInterval.EVERY_LAUNCH.durationMs)
        assertEquals(24L * 60 * 60 * 1000, RefreshInterval.DAILY.durationMs)
        assertTrue(RefreshInterval.WEEKLY.durationMs > RefreshInterval.DAILY.durationMs)
        assertTrue(RefreshInterval.MONTHLY.durationMs > RefreshInterval.WEEKLY.durationMs)
    }
}
