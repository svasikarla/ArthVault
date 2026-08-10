package com.arthvault.ui.format

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * One set of formatters for the whole app.
 *
 * These lived in `AnalyticsComponents.kt` and were correct there — Indian digit
 * grouping, an aggregate variant and a precise variant, with a comment explaining
 * why paise on a five-figure total is noise. The Ledger screen never used them: it
 * formatted with a raw `"₹%.2f".format(...)` in five places, so the Analytics tab
 * showed `₹1,23,456` and the Ledger tab showed `₹123456.00` for the same money.
 *
 * The fix already existed. It just wasn't reachable across the package, so it now
 * lives somewhere both screens can see.
 */

/**
 * Indian digit grouping — `₹12,00,000`, not `₹1,200,000`.
 *
 * Done by hand, and it has to be. The obvious implementation is
 * `NumberFormat.getInstance(Locale("en", "IN"))`, which is what this code used to
 * be, and it does not work: on the JVM that locale resolves to plain three-digit
 * grouping, so ₹1,28,450 came out as ₹128,450. `DecimalFormat` cannot express it
 * either — it only honours the rightmost grouping interval in a pattern, so
 * `"#,##,##0"` silently behaves as `"#,##0"`.
 *
 * Doing it explicitly also makes the result independent of the device locale,
 * which is what you want for a figure the user thinks of in lakhs regardless of
 * what language their phone is set to.
 */
private fun groupIndian(digits: String): String {
    if (digits.length <= 3) return digits
    val lastThree = digits.takeLast(3)
    val rest = digits.dropLast(3)

    // Everything above the final three digits is grouped in pairs, right to left.
    val pairs = ArrayDeque<String>()
    var end = rest.length
    while (end > 0) {
        val start = maxOf(0, end - 2)
        pairs.addFirst(rest.substring(start, end))
        end = start
    }
    return pairs.joinToString(",") + "," + lastThree
}

private fun formatIndian(amount: Double, decimals: Int): String {
    // valueOf, not the BigDecimal(Double) constructor: the latter takes the exact
    // binary value, so 1234.56 rounds off the back of ...5599999999999.
    val fixed = BigDecimal.valueOf(abs(amount)).setScale(decimals, RoundingMode.HALF_UP)
    val plain = fixed.toPlainString()
    val grouped = groupIndian(plain.substringBefore('.'))
    val fraction = if (decimals > 0) "." + plain.substringAfter('.') else ""
    return grouped + fraction
}

/** Aggregates. Paise on a five-figure total is noise, not precision. */
fun formatMoney(amount: Double): String =
    (if (amount < 0) "−₹" else "₹") + formatIndian(amount, 0)

/** Individual transactions, where the paise are the user's own and worth showing. */
fun formatMoneyPrecise(amount: Double): String =
    (if (amount < 0) "−₹" else "₹") + formatIndian(amount, 2)

/** Signed, for deltas and net figures where the direction is the message. */
fun formatSignedMoney(amount: Double): String =
    (if (amount < 0) "−₹" else "+₹") + formatIndian(amount, 0)

/**
 * A ledger amount with its direction on the front.
 *
 * A true minus sign (U+2212), not a hyphen: it sits on the same optical axis as the
 * plus and matches the digit weight, which a hyphen does not.
 */
fun formatDirectedMoney(amount: Double, isCredit: Boolean): String =
    // abs, because the direction is carried by the flag: ledger rows store a
    // positive amount alongside a DEBIT/CREDIT column, and a stray negative here
    // would otherwise render two minus signs.
    (if (isCredit) "+" else "−") + formatMoneyPrecise(abs(amount))

/** Whole numbers that are not money — transaction counts, message counts. */
fun formatCount(value: Double): String = formatIndian(value, 0)

/** `+12%` / `−4%`. Null means there was no baseline to compare against. */
fun formatPercentChange(percent: Double?): String? =
    percent?.let { "%s%.0f%%".format(if (it >= 0) "+" else "−", abs(it)) }

// --- dates -----------------------------------------------------------------
//
// java.time rather than SimpleDateFormat: these are shared top-level instances and
// DateTimeFormatter is thread-safe, which SimpleDateFormat is not.

private val zone: ZoneId get() = ZoneId.systemDefault()

private val dayMonthYear = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.getDefault())
private val dayMonth = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
private val dayMonthTime = DateTimeFormatter.ofPattern("dd MMM, hh:mm a", Locale.getDefault())
private val fullTimestamp = DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a", Locale.getDefault())

private fun Long.local() = Instant.ofEpochMilli(this).atZone(zone)

/** `09 Aug 2026` — alert rows, transaction lists. */
fun formatDate(timestamp: Long): String = dayMonthYear.format(timestamp.local())

/** `9 Aug` — chart axis ends, where space is tight. */
fun formatShortDate(timestamp: Long): String = dayMonth.format(timestamp.local())

/** `09 Aug, 02:15 PM` — the ledger feed, where time of day disambiguates. */
fun formatDateTime(timestamp: Long): String = dayMonthTime.format(timestamp.local())

/** `09 Aug 2026, 02:15 PM` — the transaction detail sheet. */
fun formatFullTimestamp(timestamp: Long): String = fullTimestamp.format(timestamp.local())
