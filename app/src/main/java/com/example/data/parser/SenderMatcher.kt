package com.example.data.parser

import java.util.Locale

/**
 * F1.1 — decides whether a message comes from a financial sender at all.
 *
 * Indian bank SMS arrives under DLT headers shaped like `AD-HDFCBK-S`: a two-letter
 * operator prefix, the registered entity code, and an optional category suffix. The
 * entity code is the stable part, so everything is normalised down to it before
 * comparison.
 */
object SenderMatcher {

    private val DLT_HEADER = Regex("^([A-Z]{2})[-.]([A-Z0-9]{3,})(?:[-.]([A-Z]))?$")

    /** Reduce a raw sender address to its entity code. */
    fun normalize(raw: String): String {
        val upper = raw.uppercase(Locale.ROOT).trim()
        DLT_HEADER.find(upper)?.let { return it.groupValues[2] }

        // Some carriers deliver without the operator prefix, e.g. "HDFCBK-S" or "HDFCBK".
        return upper.substringBefore('-').ifBlank { upper }
    }

    /**
     * True when [rawSender] is covered by [allowlist].
     *
     * An empty allowlist means "not configured yet" and allows everything — silently
     * dropping the entire inbox would look identical to a broken parser, which is a
     * worse failure than reading too much. The default list is seeded on first run.
     */
    fun isAllowed(rawSender: String, allowlist: Set<String>): Boolean {
        if (allowlist.isEmpty()) return true
        val normalized = normalize(rawSender)
        return allowlist.any { allowed ->
            normalized == allowed || normalized.contains(allowed)
        }
    }
}
