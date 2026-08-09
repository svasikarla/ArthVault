package com.arthvault.data.parser

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

        // Some carriers deliver without the operator prefix, e.g. "HDFCBK-S". Only a
        // single-letter DLT category suffix is stripped.
        //
        // Cutting at the first hyphen instead — as this used to — reduced the
        // synthetic "BANK-SMS" sender that manual and CSV imports arrive under to
        // "BANK", which matched nothing in the allowlist, so every imported row was
        // silently filtered out of its own ingestion path.
        Regex("^([A-Z0-9]{3,})[-.][A-Z]$").find(upper)?.let { return it.groupValues[1] }

        return upper
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
