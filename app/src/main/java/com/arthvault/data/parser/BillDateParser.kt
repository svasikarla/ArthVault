package com.arthvault.data.parser

import java.util.Calendar
import java.util.Locale

/**
 * Dates written the way Indian billers write them.
 *
 * Nothing else in this codebase has ever had to read a date: a transaction's timestamp
 * comes from the SMS envelope, which is unambiguous and always present. A due date is
 * the first value the app takes from the *body* of a message, and it is the field most
 * likely to be wrong, so the rules it applies are spelled out rather than implied.
 *
 *  - **`dd-mm`, never `mm-dd`.** Indian senders write `15-08-25` for 15 August. The
 *    American reading is a real 12-day-a-month hazard and there is no way to tell the
 *    two apart from the digits alone, so the convention is fixed by region and only
 *    overridden by evidence: a second component above 12 cannot be a month.
 *  - **A day component is required.** "statement for Aug 2026" names a billing period,
 *    not a deadline, and reading it as one would invent a due date of 1 August for a
 *    bill payable on the 20th. Month-and-year alone is never a date here.
 *  - **A missing year is inferred from the message, not from today.** "due on 05-Jan"
 *    sent on 20 December means next January. Anchoring to the SMS timestamp gets that
 *    right and also keeps a re-parse of old messages stable, which anchoring to the
 *    current clock would not.
 */
object BillDateParser {

    /**
     * A date found in the body, with where it was found.
     *
     * The position matters as much as the value: a card statement quotes a statement
     * period, a due date and sometimes a transaction date, and which one is the
     * deadline is decided by which phrase it sits next to.
     */
    data class DateMatch(val range: IntRange, val millis: Long)

    private val MONTHS = mapOf(
        "jan" to 0, "feb" to 1, "mar" to 2, "apr" to 3, "may" to 4, "jun" to 5,
        "jul" to 6, "aug" to 7, "sep" to 8, "oct" to 9, "nov" to 10, "dec" to 11
    )

    /** `06-Aug-26`, `06 Aug 2026`, `6-August`, `15 Sept 25`. */
    private val ALPHA_DATE = Regex(
        """(?i)\b(\d{1,2})\s*[-/ .]\s*([A-Za-z]{3,9})\b(?:\s*[-/ ,]\s*(\d{2,4})\b)?"""
    )

    /** `15-08-25`, `15/08/2025`. A year is optional here too. */
    private val NUMERIC_DATE = Regex(
        """\b(\d{1,2})[-/](\d{1,2})(?:[-/](\d{2,4}))?\b"""
    )

    /**
     * How far before the message a resolved date may fall before the year is pushed
     * forward. A reminder can legitimately quote a deadline that has just passed
     * ("your payment was due on the 3rd"), so the window is not zero.
     */
    private const val BACKWARD_TOLERANCE_DAYS = 75

    /**
     * Every date in [text], in the order they appear.
     *
     * Alphabetic months are matched first and their spans are then excluded from the
     * numeric pass, so `06-Aug-26` cannot also yield a bogus numeric reading.
     */
    fun findAll(text: String, reference: Long): List<DateMatch> {
        val matches = mutableListOf<DateMatch>()
        val consumed = mutableListOf<IntRange>()

        for (m in ALPHA_DATE.findAll(text)) {
            val month = MONTHS[m.groupValues[2].take(3).lowercase(Locale.ROOT)] ?: continue
            val day = m.groupValues[1].toIntOrNull() ?: continue
            val millis = build(day, month, m.groupValues[3], reference) ?: continue
            matches += DateMatch(m.range, millis)
            consumed += m.range
        }

        for (m in NUMERIC_DATE.findAll(text)) {
            if (consumed.any { m.range.first <= it.last && it.first <= m.range.last }) continue
            val first = m.groupValues[1].toIntOrNull() ?: continue
            val second = m.groupValues[2].toIntOrNull() ?: continue

            // dd-mm by default; a second component above 12 is the only proof available
            // that the writer meant mm-dd, and it is worth acting on when it appears.
            val (day, month1) = if (second > 12 && first <= 12) second to first else first to second
            if (month1 !in 1..12) continue

            val millis = build(day, month1 - 1, m.groupValues[3], reference) ?: continue
            matches += DateMatch(m.range, millis)
        }

        return matches.sortedBy { it.range.first }
    }

    /**
     * The date attached to a due phrase, or null when the message names none.
     *
     * [phraseEnd] is where the phrase that promised a deadline stopped. Only a date
     * sitting within [WINDOW_CHARS] after it counts, so "Total Amount Due Rs 2,783.00
     * ... includes reversed charges" cannot pick up a date from an unrelated clause at
     * the far end of the message.
     */
    fun findNear(text: String, phraseEnd: Int, reference: Long): Long? =
        findAll(text, reference)
            .firstOrNull { it.range.first >= phraseEnd && it.range.first - phraseEnd <= WINDOW_CHARS }
            ?.millis

    private const val WINDOW_CHARS = 40

    /**
     * @param rawYear the year exactly as written, possibly empty.
     * @return start of that day in local time, or null if the components cannot be a
     *   real date. Room stores an instant, and midnight local is the only defensible
     *   reading of a date with no time on it.
     */
    private fun build(day: Int, monthIndex: Int, rawYear: String, reference: Long): Long? {
        if (day !in 1..31) return null

        val calendar = Calendar.getInstance().apply {
            timeInMillis = reference
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val year = when {
            rawYear.isBlank() -> null
            rawYear.length <= 2 -> 2000 + (rawYear.toIntOrNull() ?: return null)
            else -> rawYear.toIntOrNull() ?: return null
        }

        calendar.isLenient = false // 31 February must fail rather than roll into March.
        calendar.set(Calendar.YEAR, year ?: calendar.get(Calendar.YEAR))
        calendar.set(Calendar.MONTH, monthIndex)
        calendar.set(Calendar.DAY_OF_MONTH, day)

        // Non-lenient means 31 February throws here rather than silently becoming
        // 3 March, which is the whole reason for setting it.
        return try {
            val resolved = calendar.timeInMillis

            // Only a year the message did not state may be corrected. A biller that
            // writes "26" has said which year it means, and second-guessing that would
            // trade one parsing bug for a different one.
            if (year == null && (reference - resolved) / 86_400_000L > BACKWARD_TOLERANCE_DAYS) {
                calendar.add(Calendar.YEAR, 1)
                calendar.timeInMillis
            } else {
                resolved
            }
        } catch (invalidDate: Exception) {
            null
        }
    }
}
