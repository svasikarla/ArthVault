package com.arthvault

import com.arthvault.data.analytics.PeriodResolver
import com.arthvault.data.analytics.PeriodScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class AnalyticsPeriodTest {

    /** A fixed clock: 10 August 2026, 14:00 local. */
    private fun at(
        year: Int = 2026,
        month: Int = Calendar.AUGUST,
        day: Int = 10,
        hour: Int = 14
    ): Long = Calendar.getInstance().apply {
        set(year, month, day, hour, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun dayOfMonth(at: Long) =
        Calendar.getInstance().apply { timeInMillis = at }.get(Calendar.DAY_OF_MONTH)

    private fun month(at: Long) =
        Calendar.getInstance().apply { timeInMillis = at }.get(Calendar.MONTH)

    @Test
    fun `this month runs from the first to now, not to the end of the month`() {
        val now = at()
        val period = PeriodResolver.resolve(PeriodScope.THIS_MONTH, now)

        assertEquals(1, dayOfMonth(period.range.first))
        assertEquals(Calendar.AUGUST, month(period.range.first))
        assertEquals(now, period.range.last)
        assertTrue(period.isOpen)
        assertEquals(10, period.elapsedDays)
        assertEquals(31, period.totalDays)
        assertEquals(21, period.daysRemaining)
    }

    @Test
    fun `the comparison window covers the same elapsed span, not the whole month`() {
        // The bug this exists to prevent: a month-to-date total was being set against
        // a *complete* previous month, so on the 3rd of the month the trends card
        // reported that spending had collapsed in every single category.
        val period = PeriodResolver.resolve(PeriodScope.THIS_MONTH, at())

        assertEquals(Calendar.JULY, month(period.comparison.first))
        assertEquals(1, dayOfMonth(period.comparison.first))
        assertEquals(Calendar.JULY, month(period.comparison.last))
        assertEquals(10, dayOfMonth(period.comparison.last))
    }

    @Test
    fun `the comparison window cannot bleed past the end of its own month`() {
        // 31 March has no counterpart in February. Left unclamped, the elapsed span
        // would run into March and pull March spending into "February".
        val period = PeriodResolver.resolve(
            PeriodScope.THIS_MONTH,
            at(month = Calendar.MARCH, day = 31)
        )

        assertEquals(Calendar.FEBRUARY, month(period.comparison.first))
        assertEquals(
            "the comparison must stay inside February",
            Calendar.FEBRUARY,
            month(period.comparison.last)
        )
    }

    @Test
    fun `last month is a closed window compared against the month before it`() {
        val period = PeriodResolver.resolve(PeriodScope.LAST_MONTH, at())

        assertEquals(Calendar.JULY, month(period.range.first))
        assertEquals(1, dayOfMonth(period.range.first))
        assertEquals(Calendar.JULY, month(period.range.last))
        assertEquals(31, dayOfMonth(period.range.last))
        assertEquals(Calendar.JUNE, month(period.comparison.first))

        assertFalse(period.isOpen)
        assertEquals("a finished month has no days remaining", 0, period.daysRemaining)
        assertEquals(period.totalDays, period.elapsedDays)
    }

    @Test
    fun `this week starts on the locale's first day and ends now`() {
        val now = at()
        val period = PeriodResolver.resolve(PeriodScope.THIS_WEEK, now)

        assertTrue("the week cannot start after now", period.range.first <= now)
        assertEquals(now, period.range.last)
        assertEquals(7, period.totalDays)
        assertTrue(period.elapsedDays in 1..7)
        // The comparison is the equivalent stretch of the previous week.
        assertEquals(
            7 * 86_400_000L,
            period.range.first - period.comparison.first
        )
    }

    @Test
    fun `ninety days ends today and is compared against the ninety before it`() {
        val period = PeriodResolver.resolve(PeriodScope.LAST_90_DAYS, at())

        assertEquals(90, period.elapsedDays)
        assertTrue(period.comparison.last < period.range.first)
        assertEquals(90, PeriodResolver.daysBetween(period.comparison.first, period.comparison.last) + 1)
    }

    @Test
    fun `a window inside one month is labelled as a day range`() {
        val period = PeriodResolver.resolve(PeriodScope.THIS_MONTH, at())
        assertTrue("expected a 1–10 style label, got ${period.label}", period.label.startsWith("1–10"))
    }
}
