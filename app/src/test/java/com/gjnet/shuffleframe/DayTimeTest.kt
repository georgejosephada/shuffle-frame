package com.gjnet.shuffleframe

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class DayTimeTest {
    private fun t(h: Int, m: Int = 0) = LocalTime.of(h, m)

    @Test
    fun sevenToTen() {
        val (s, e) = 7 * 60 to 22 * 60
        assertFalse(FrameConditions.dayTime(s, e, t(6, 59)))
        assertTrue(FrameConditions.dayTime(s, e, t(7)))
        assertTrue(FrameConditions.dayTime(s, e, t(21, 59)))
        assertFalse(FrameConditions.dayTime(s, e, t(22)))
        assertFalse(FrameConditions.dayTime(s, e, t(0)))
    }

    @Test
    fun hoursPastMidnight() {
        val (s, e) = 20 * 60 to 2 * 60
        assertTrue(FrameConditions.dayTime(s, e, t(23)))
        assertTrue(FrameConditions.dayTime(s, e, t(1, 59)))
        assertFalse(FrameConditions.dayTime(s, e, t(2)))
        assertFalse(FrameConditions.dayTime(s, e, t(12)))
    }

    @Test
    fun sameStartAndEndIsAllDay() {
        assertTrue(FrameConditions.dayTime(8 * 60, 8 * 60, t(3)))
    }
}
