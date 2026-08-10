package com.arthvault.data.analytics

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * The windows the analytics screen can be scoped to.
 *
 * Everything on that screen used to be hardwired to the current calendar month,
 * which meant a user could not ask "what did last month actually come to" without
 * waiting for the month to end.
 */
enum class PeriodScope(val chipLabel: String) {
    THIS_WEEK("This week"),
    THIS_MONTH("This month"),
    LAST_MONTH("Last month"),
    LAST_90_DAYS("90 days")
}

/**
 * A resolved window, together with the window it should be compared against.
 *
 * [comparison] is the crux. Comparing a month-to-date total against a *whole*
 * previous month is the single most misleading thing this app did: on the 3rd of
 * the month it reported that spending had collapsed in every category, because two
 * days were being measured against thirty-one. For an open window the comparison is
 * therefore truncated to the same elapsed span, so "1–10 Aug" is only ever set
 * beside "1–10 Jul".
 *
 * @param isOpen true when the window contains now and is therefore still filling up.
 *   Forecasting and pace only mean anything for an open window.
 * @param elapsedDays days of the window that have actually happened.
 * @param totalDays days the window will hold once complete. Equal to [elapsedDays]
 *   for a closed window.
 */
data class AnalyticsPeriod(
    val scope: PeriodScope,
    val range: LongRange,
    val label: String,
    val comparison: LongRange,
    val comparisonLabel: String,
    val isOpen: Boolean,
    val elapsedDays: Int,
    val totalDays: Int
) {
    val daysRemaining: Int get() = (totalDays - elapsedDays).coerceAtLeast(0)
}

/** Resolves a [PeriodScope] into concrete timestamps against a supplied clock. */
object PeriodResolver {

    private const val DAY_MS = 86_400_000L

    fun resolve(scope: PeriodScope, now: Long = System.currentTimeMillis()): AnalyticsPeriod =
        when (scope) {
            PeriodScope.THIS_WEEK -> openWindow(
                scope = scope,
                start = startOfWeek(now),
                now = now,
                fullStart = startOfWeek(now),
                fullEndExclusive = shift(startOfWeek(now), Calendar.DAY_OF_MONTH, 7),
                previousStart = shift(startOfWeek(now), Calendar.DAY_OF_MONTH, -7),
                previousEndExclusive = startOfWeek(now)
            )

            PeriodScope.THIS_MONTH -> openWindow(
                scope = scope,
                start = startOfMonth(now),
                now = now,
                fullStart = startOfMonth(now),
                fullEndExclusive = shift(startOfMonth(now), Calendar.MONTH, 1),
                previousStart = shift(startOfMonth(now), Calendar.MONTH, -1),
                previousEndExclusive = startOfMonth(now)
            )

            PeriodScope.LAST_MONTH -> {
                val start = shift(startOfMonth(now), Calendar.MONTH, -1)
                val endExclusive = startOfMonth(now)
                val priorStart = shift(startOfMonth(now), Calendar.MONTH, -2)
                closedWindow(scope, start, endExclusive, priorStart, start)
            }

            PeriodScope.LAST_90_DAYS -> {
                val start = shift(startOfDay(now), Calendar.DAY_OF_MONTH, -89)
                openWindow(
                    scope = scope,
                    start = start,
                    now = now,
                    fullStart = start,
                    fullEndExclusive = shift(startOfDay(now), Calendar.DAY_OF_MONTH, 1),
                    previousStart = shift(start, Calendar.DAY_OF_MONTH, -90),
                    previousEndExclusive = start
                )
            }
        }

    /**
     * A window that is still filling. The comparison is truncated to the same elapsed
     * span, and clamped so it cannot bleed past the end of its own period — 31 March
     * has no counterpart in February, and letting the span run over would quietly pull
     * March spending into "February".
     */
    private fun openWindow(
        scope: PeriodScope,
        start: Long,
        now: Long,
        fullStart: Long,
        fullEndExclusive: Long,
        previousStart: Long,
        previousEndExclusive: Long
    ): AnalyticsPeriod {
        val elapsed = now - start
        val comparisonEnd = (previousStart + elapsed).coerceAtMost(previousEndExclusive - 1)
        val range = start..now

        return AnalyticsPeriod(
            scope = scope,
            range = range,
            label = formatRange(range),
            comparison = previousStart..comparisonEnd,
            comparisonLabel = formatRange(previousStart..comparisonEnd),
            isOpen = true,
            elapsedDays = daysBetween(start, now) + 1,
            totalDays = daysBetween(fullStart, fullEndExclusive - 1) + 1
        )
    }

    private fun closedWindow(
        scope: PeriodScope,
        start: Long,
        endExclusive: Long,
        priorStart: Long,
        priorEndExclusive: Long
    ): AnalyticsPeriod {
        val range = start..(endExclusive - 1)
        val days = daysBetween(start, endExclusive - 1) + 1

        return AnalyticsPeriod(
            scope = scope,
            range = range,
            label = formatRange(range),
            comparison = priorStart..(priorEndExclusive - 1),
            comparisonLabel = formatRange(priorStart..(priorEndExclusive - 1)),
            isOpen = false,
            elapsedDays = days,
            totalDays = days
        )
    }

    // --- calendar helpers -------------------------------------------------

    private fun cal(at: Long) = Calendar.getInstance().apply { timeInMillis = at }

    private fun startOfDay(at: Long): Long = cal(at).apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun startOfWeek(at: Long): Long = cal(startOfDay(at)).apply {
        set(Calendar.DAY_OF_WEEK, firstDayOfWeek)
        // Setting DAY_OF_WEEK can roll forward when the first day of the week is
        // later in the week than today; step back if it did.
        if (timeInMillis > at) add(Calendar.DAY_OF_MONTH, -7)
    }.timeInMillis

    private fun startOfMonth(at: Long): Long = cal(startOfDay(at)).apply {
        set(Calendar.DAY_OF_MONTH, 1)
    }.timeInMillis

    private fun shift(at: Long, field: Int, amount: Int): Long =
        cal(at).apply { add(field, amount) }.timeInMillis

    /** Whole days from [from] to [to], counted on calendar days rather than millis. */
    fun daysBetween(from: Long, to: Long): Int {
        val a = startOfDay(from)
        val b = startOfDay(to)
        // Half a day of slack absorbs any DST transition inside the span.
        return ((b - a + DAY_MS / 2) / DAY_MS).toInt()
    }

    /** "1–10 Aug", or "12 Jul – 10 Aug" when the window crosses a month boundary. */
    fun formatRange(range: LongRange): String {
        val startCal = cal(range.first)
        val endCal = cal(range.last)
        val sameMonth = startCal.get(Calendar.MONTH) == endCal.get(Calendar.MONTH) &&
            startCal.get(Calendar.YEAR) == endCal.get(Calendar.YEAR)

        val day = SimpleDateFormat("d", Locale.getDefault())
        val dayMonth = SimpleDateFormat("d MMM", Locale.getDefault())

        return if (sameMonth) {
            "${day.format(Date(range.first))}–${dayMonth.format(Date(range.last))}"
        } else {
            "${dayMonth.format(Date(range.first))} – ${dayMonth.format(Date(range.last))}"
        }
    }
}
