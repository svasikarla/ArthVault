package com.arthvault.data.query

import java.util.Calendar
import java.util.Locale

/**
 * F4.1 / F4.2 — turns a typed question into a [QueryIntent], deterministically.
 *
 * There is no model here and there will not be one. F4.2 is explicit that numeric
 * values are computed and injected, and an LLM formats prose at most; T4.3 makes the
 * narration layer optional entirely. A grammar cannot answer everything, but it can
 * be *wrong in ways you can see* — it reports what it understood, and says so plainly
 * when it understood nothing.
 *
 * The design bias throughout is to refuse rather than guess. "Spend on fuel last
 * quarter" has one reading; "how about fuel" has several, and picking one silently
 * would produce a confident number answering a question nobody asked.
 */
class QueryParser(
    /** Category names as they exist in the database, so matches are real categories. */
    private val knownCategories: List<String> = emptyList()
) {

    fun parse(question: String, now: Long = System.currentTimeMillis()): QueryIntent? {
        val text = question.lowercase(Locale.ROOT).trim()
        if (text.isBlank()) return null

        val metric = detectMetric(text)
        val direction = detectDirection(text)
        val period = detectPeriod(text, now)
        val category = detectCategory(text)
        val merchant = if (category == null) detectMerchant(text) else null

        // A bare period with no metric, subject or direction cue is not a question
        // about anything in particular. Answering "total spend" would be inventing
        // the interesting half.
        val hasSubject = category != null || merchant != null
        val hasCue = METRIC_CUES.any { it in text } ||
            DIRECTION_CUES.any { it in text } ||
            SPEND_CUES.any { it in text }
        if (!hasSubject && !hasCue) return null

        return QueryIntent(
            metric = metric,
            direction = direction,
            category = category,
            merchant = merchant,
            period = period,
            interpretation = describe(metric, direction, category, merchant, period)
        )
    }

    /**
     * Generates relevant auto-complete query suggestions based on partial user input.
     */
    fun getSuggestions(input: String): List<String> {
        val clean = input.lowercase(Locale.ROOT).trim()
        if (clean.isBlank()) return emptyList()

        val suggestions = mutableListOf<String>()

        if (!clean.startsWith("spend") && !clean.startsWith("how")) {
            suggestions.add("spend on $clean")
            suggestions.add("biggest spend on $clean")
        }

        for (cat in knownCategories) {
            val lowerCat = cat.lowercase(Locale.ROOT)
            if (lowerCat.contains(clean) || clean.contains(lowerCat)) {
                suggestions.add("spend on $lowerCat this month")
                suggestions.add("biggest spend on $lowerCat")
            }
        }

        return suggestions.distinct().take(4)
    }

    private fun detectMetric(text: String): QueryMetric = when {
        "how many" in text || "how often" in text || "number of" in text ||
            "count" in text -> QueryMetric.COUNT
        "average" in text || "avg" in text || "typical" in text ||
            "on average" in text -> QueryMetric.AVERAGE
        "biggest" in text || "largest" in text || "most expensive" in text ||
            "highest" in text || "max" in text -> QueryMetric.LARGEST
        else -> QueryMetric.TOTAL
    }

    private fun detectDirection(text: String): QueryDirection =
        if (DIRECTION_CUES.any { it in text }) QueryDirection.IN else QueryDirection.OUT

    private fun detectCategory(text: String): String? {
        // Explicit category names first — an exact match beats any alias.
        knownCategories.firstOrNull { it.lowercase(Locale.ROOT) in text }?.let { return it }

        // Then the words people actually type. Each alias maps to a category name,
        // and is only accepted if that category exists in this user's database:
        // reporting "fuel: 0" for a category they never created is a wrong answer
        // dressed up as a right one.
        for ((alias, category) in CATEGORY_ALIASES) {
            if (Regex("\\b$alias\\b").containsMatchIn(text)) {
                knownCategories.firstOrNull { it.equals(category, ignoreCase = true) }
                    ?.let { return it }
            }
        }
        return null
    }

    /**
     * A merchant named after "at", "to", "from" or "with".
     *
     * Anchored on the preposition rather than scanning for capitalised words: SMS
     * merchant strings have no reliable casing, and a bare noun is as likely to be
     * part of the question as the name of a shop.
     */
    private fun detectMerchant(text: String): String? {
        val match = Regex("\\b(?:at|to|from|with|for)\\s+([a-z0-9@._&'-]+(?:\\s+[a-z0-9@._&'-]+)?)")
            .find(text) ?: return null

        val candidate = match.groupValues[1].trim()
            // Strip a trailing period phrase the preposition swept up, e.g.
            // "at swiggy last month" -> "swiggy".
            .replace(Regex("\\b(last|this|past|previous)\\b.*$"), "")
            .trim()

        if (candidate.isBlank()) return null
        if (candidate in STOP_WORDS) return null
        return candidate
    }

    private fun detectPeriod(text: String, now: Long): QueryPeriod {
        fun cal() = Calendar.getInstance().apply { timeInMillis = now }

        Regex("\\b(?:last|past)\\s+(\\d{1,3})\\s+(day|days|week|weeks|month|months)\\b")
            .find(text)?.let { match ->
                val n = match.groupValues[1].toInt()
                val unit = match.groupValues[2]
                val start = cal().apply {
                    when {
                        unit.startsWith("day") -> add(Calendar.DAY_OF_YEAR, -n)
                        unit.startsWith("week") -> add(Calendar.WEEK_OF_YEAR, -n)
                        else -> add(Calendar.MONTH, -n)
                    }
                }.timeInMillis
                return QueryPeriod(start..now, "the last $n $unit")
            }

        return when {
            "today" in text -> {
                val start = cal().atStartOfDay().timeInMillis
                QueryPeriod(start..now, "today")
            }

            "yesterday" in text -> {
                val startCal = cal().atStartOfDay().apply { add(Calendar.DAY_OF_YEAR, -1) }
                val start = startCal.timeInMillis
                val end = startCal.apply { add(Calendar.DAY_OF_YEAR, 1) }.timeInMillis - 1
                QueryPeriod(start..end, "yesterday")
            }

            "last month" in text || "previous month" in text -> {
                val startCal = cal().atStartOfMonth().apply { add(Calendar.MONTH, -1) }
                val start = startCal.timeInMillis
                val end = cal().atStartOfMonth().timeInMillis - 1
                QueryPeriod(start..end, "last month")
            }

            "last quarter" in text -> {
                val startCal = cal().atStartOfQuarter().apply { add(Calendar.MONTH, -3) }
                val start = startCal.timeInMillis
                val end = cal().atStartOfQuarter().timeInMillis - 1
                QueryPeriod(start..end, "last quarter")
            }

            "this quarter" in text -> {
                QueryPeriod(cal().atStartOfQuarter().timeInMillis..now, "this quarter")
            }

            "last year" in text -> {
                val startCal = cal().atStartOfYear().apply { add(Calendar.YEAR, -1) }
                val start = startCal.timeInMillis
                val end = cal().atStartOfYear().timeInMillis - 1
                QueryPeriod(start..end, "last year")
            }

            "this year" in text -> QueryPeriod(cal().atStartOfYear().timeInMillis..now, "this year")

            "last week" in text -> {
                val startCal = cal().atStartOfWeek().apply { add(Calendar.WEEK_OF_YEAR, -1) }
                val start = startCal.timeInMillis
                val end = cal().atStartOfWeek().timeInMillis - 1
                QueryPeriod(start..end, "last week")
            }

            "this week" in text -> QueryPeriod(cal().atStartOfWeek().timeInMillis..now, "this week")

            // The default is stated in [QueryIntent.interpretation] rather than
            // assumed silently, so a question with no period reads as "this month"
            // on screen and can be corrected.
            else -> QueryPeriod(cal().atStartOfMonth().timeInMillis..now, "this month")
        }
    }

    private fun describe(
        metric: QueryMetric,
        direction: QueryDirection,
        category: String?,
        merchant: String?,
        period: QueryPeriod
    ): String {
        val flow = if (direction == QueryDirection.IN) "money in" else "spending"
        val subject = when {
            category != null -> " on $category"
            merchant != null -> " at $merchant"
            else -> ""
        }
        val lead = when (metric) {
            QueryMetric.TOTAL -> "Total $flow"
            QueryMetric.AVERAGE -> "Average $flow"
            QueryMetric.COUNT -> "Number of transactions"
            QueryMetric.LARGEST -> "Largest single ${if (direction == QueryDirection.IN) "credit" else "charge"}"
        }
        return "$lead$subject in ${period.label}"
    }

    private fun Calendar.atStartOfDay() = apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    private fun Calendar.atStartOfWeek() = atStartOfDay().apply {
        set(Calendar.DAY_OF_WEEK, firstDayOfWeek)
    }

    private fun Calendar.atStartOfMonth() = atStartOfDay().apply { set(Calendar.DAY_OF_MONTH, 1) }

    private fun Calendar.atStartOfQuarter() = atStartOfMonth().apply {
        set(Calendar.MONTH, (get(Calendar.MONTH) / 3) * 3)
    }

    private fun Calendar.atStartOfYear() = atStartOfMonth().apply { set(Calendar.MONTH, 0) }

    private companion object {
        val METRIC_CUES = listOf(
            "how many", "how often", "number of", "count", "average", "avg", "typical",
            "biggest", "largest", "most expensive", "highest", "max"
        )

        val DIRECTION_CUES = listOf(
            "income", "earn", "earned", "received", "credited", "salary", "came in",
            "paid me", "money in"
        )

        val SPEND_CUES = listOf(
            "spend", "spent", "spending", "cost", "paid", "outgoing", "charged", "total"
        )

        /**
         * The words people type mapped to the seeded category names. A match is only
         * used if the category exists in this database — see [detectCategory].
         */
        val CATEGORY_ALIASES = listOf(
            "fuel" to "Transport & Fuel",
            "petrol" to "Transport & Fuel",
            "diesel" to "Transport & Fuel",
            "transport" to "Transport & Fuel",
            "travel" to "Transport & Fuel",
            "cab" to "Transport & Fuel",
            "food" to "Food & Dining",
            "dining" to "Food & Dining",
            "eating out" to "Food & Dining",
            "restaurants" to "Food & Dining",
            "grocery" to "Grocery",
            "groceries" to "Grocery",
            "shopping" to "Shopping",
            "bills" to "Utilities & Bills",
            "utilities" to "Utilities & Bills",
            "electricity" to "Utilities & Bills",
            "entertainment" to "Entertainment & Subs",
            "subscriptions" to "Entertainment & Subs",
            "health" to "Health & Medical",
            "medical" to "Health & Medical",
            "transfers" to "Transfers",
            "atm" to "ATM Cash",
            "cash" to "ATM Cash"
        )

        /** Prepositions routinely precede these, and none of them names a shop. */
        val STOP_WORDS = setOf(
            "me", "my", "it", "that", "this", "the", "a", "an", "all", "everything",
            "date", "now", "then", "which", "what", "how", "much", "many"
        )
    }
}
