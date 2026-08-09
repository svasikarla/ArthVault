package com.arthvault

import com.arthvault.data.local.DefaultSeedData
import com.arthvault.data.parser.SenderMatcher
import com.arthvault.data.parser.SmsParserEngine
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * T2.4 — extraction accuracy measured against a corpus, not asserted by hand.
 *
 * The other parser tests are regression tests: each one pins a specific defect that
 * was found the hard way. They cannot tell you how often the parser is *right*, and
 * the failure that motivated this file was invisible to them — a live ICICI transfer
 * alert booked as 635 credited instead of 45,425 debited, with no exception, no
 * unparsed entry, and a perfectly plausible-looking row in the ledger. The only
 * defence against that class of bug is a scored corpus.
 *
 * Scoring is **per field, not per message**. A message whose amount is right and
 * whose merchant is wrong is 7/8, not 0/1 — otherwise one cosmetic miss looks the
 * same as reading the wrong number off a bank alert, and the score stops meaning
 * anything. Messages that must *not* produce a transaction (OTPs, promos,
 * balance-only alerts) score a single field.
 *
 * The corpus lives in `app/src/test/resources/sms_corpus.jsonl`. Account numbers,
 * VPAs and reference numbers in it are fabricated.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ParserAccuracyTest {

    private val engine = SmsParserEngine()

    private val scoredFields =
        listOf("amount", "direction", "merchant", "tail", "channel", "balance", "status", "type")

    private companion object {
        /** Fixed so a run is reproducible; the parser only uses it for hashing. */
        const val TIMESTAMP = 1_754_700_000_000L

        /** T2.4 target. Below this the build fails. */
        const val THRESHOLD = 0.95

        /** Roadmap 7.1 — a corpus smaller than this stops being evidence. */
        const val MIN_MESSAGES = 200

        const val MAX_REPORTED_FAILURES = 40
    }

    @Test
    fun `parser meets the T2 4 extraction accuracy target`() {
        val cases = loadCorpus()
        val scorer = Scorer()

        for (case in cases) {
            val result = engine.parseMessage(
                case.sender,
                case.body,
                TIMESTAMP,
                DefaultSeedData.merchantRules,
                // The rules from the signed asset, mapped and ordered exactly as
                // ParserRuleSeeder installs them. This is what makes the score a
                // measurement of the shipped app rather than of a test fixture: edit
                // parser_rules_v1.json badly and this number moves.
                BundledParserRules.entities
            )
            val txn = result.parsedTransaction

            if (case.expect["parsed"] == false) {
                // The point of these rows is that a plausible-looking number must not
                // enter the ledger, so the produced row is what gets reported —
                // "expected null" alone would not tell you what leaked through.
                scorer.record(
                    case, "parsed", txn == null,
                    expected = "no transaction",
                    actual = txn?.let { "${it.direction} ${it.amount} @ ${it.merchant}" }
                        ?: "no transaction"
                )
                continue
            }

            if (txn == null) {
                // Every field of an unparsed message counts as a miss. Scoring it as a
                // single miss would let a parser that silently drops messages outscore
                // one that reads them slightly wrong.
                for (field in scoredFields) {
                    scorer.record(case, field, false, case.expect[field], "message did not parse")
                }
                continue
            }

            scorer.record(
                case, "amount",
                near(case.expect["amount"] as Double?, txn.amount),
                case.expect["amount"], txn.amount
            )
            scorer.record(
                case, "direction",
                case.expect["direction"] == txn.direction,
                case.expect["direction"], txn.direction
            )
            scorer.record(
                case, "merchant",
                merchantEq(case.expect["merchant"] as String?, txn.merchant),
                case.expect["merchant"], txn.merchant
            )
            scorer.record(
                case, "tail",
                case.expect["tail"] == txn.accountTail,
                case.expect["tail"], txn.accountTail
            )
            scorer.record(
                case, "channel",
                case.expect["channel"] == txn.channel,
                case.expect["channel"], txn.channel
            )
            scorer.record(
                case, "balance",
                near(case.expect["balance"] as Double?, txn.balanceAfter),
                case.expect["balance"], txn.balanceAfter
            )
            scorer.record(
                case, "status",
                case.expect["status"] == txn.status,
                case.expect["status"], txn.status
            )
            scorer.record(
                case, "type",
                case.expect["type"] == txn.txnType,
                case.expect["type"], txn.txnType
            )
        }

        val report = scorer.report()
        println(report)
        if (scorer.accuracy < THRESHOLD) {
            fail(
                "Parser accuracy %.4f is below the T2.4 target of %.2f.%n%n%s"
                    .format(scorer.accuracy, THRESHOLD, report)
            )
        }
    }

    @Test
    fun `the corpus is large enough to be evidence`() {
        val size = loadCorpus().size
        assertTrue(
            "The corpus holds $size messages; T2.4 needs at least $MIN_MESSAGES to mean anything.",
            size >= MIN_MESSAGES
        )
    }

    @Test
    fun `every allowlisted sender is covered by the corpus`() {
        val cases = loadCorpus()
        val uncovered = DefaultSeedData.senderAllowlist
            .map { it.senderId }
            .filter { code -> cases.none { SenderMatcher.isAllowed(it.sender, setOf(code)) } }

        assertTrue(
            "Allowlisted senders with no corpus coverage: $uncovered. " +
                "An allowlisted sender the corpus never exercises is an unmeasured sender.",
            uncovered.isEmpty()
        )
    }

    // ---- scoring ---------------------------------------------------------

    private fun near(expected: Double?, actual: Double?): Boolean = when {
        expected == null && actual == null -> true
        expected == null || actual == null -> false
        else -> kotlin.math.abs(expected - actual) < 0.01
    }

    /**
     * Merchant names are compared case-insensitively with runs of whitespace
     * collapsed. Every downstream consumer uppercases the name anyway —
     * categorisation, recurring detection and the transaction hash all do — so a
     * case difference is not an extraction error.
     */
    private fun merchantEq(expected: String?, actual: String?): Boolean =
        normalizeMerchant(expected) == normalizeMerchant(actual)

    private fun normalizeMerchant(raw: String?): String? =
        raw?.trim()?.replace(Regex("\\s+"), " ")?.uppercase(Locale.ROOT)

    private inner class Scorer {
        private val fieldHits = linkedMapOf<String, Int>()
        private val fieldTotals = linkedMapOf<String, Int>()
        private val senderHits = linkedMapOf<String, Int>()
        private val senderTotals = linkedMapOf<String, Int>()
        private val failures = mutableListOf<String>()

        fun record(case: Case, field: String, ok: Boolean, expected: Any?, actual: Any?) {
            val sender = SenderMatcher.normalize(case.sender)
            fieldTotals[field] = (fieldTotals[field] ?: 0) + 1
            senderTotals[sender] = (senderTotals[sender] ?: 0) + 1
            if (ok) {
                fieldHits[field] = (fieldHits[field] ?: 0) + 1
                senderHits[sender] = (senderHits[sender] ?: 0) + 1
            } else {
                failures += "  %-13s %-9s expected <%s> but was <%s>"
                    .format(case.id, field, expected, actual)
            }
        }

        private val hits get() = fieldHits.values.sum()
        private val total get() = fieldTotals.values.sum()

        val accuracy: Double get() = if (total == 0) 0.0 else hits.toDouble() / total

        fun report(): String = buildString {
            appendLine("Parser accuracy: %.4f  (%d/%d fields)".format(accuracy, hits, total))
            appendLine()
            appendLine("By field:")
            fieldTotals.keys.sorted().forEach { f ->
                val h = fieldHits[f] ?: 0
                val t = fieldTotals.getValue(f)
                appendLine("  %-10s %.3f  (%d/%d)".format(f, h.toDouble() / t, h, t))
            }
            appendLine()
            appendLine("By sender:")
            senderTotals.entries.sortedBy { it.key }.forEach { (s, t) ->
                val h = senderHits[s] ?: 0
                appendLine("  %-10s %.3f  (%d/%d)".format(s, h.toDouble() / t, h, t))
            }
            if (failures.isNotEmpty()) {
                appendLine()
                appendLine(
                    "Mismatches (${failures.size} total, showing up to $MAX_REPORTED_FAILURES):"
                )
                failures.take(MAX_REPORTED_FAILURES).forEach { appendLine(it) }
            }
        }
    }

    // ---- corpus loading --------------------------------------------------

    private data class Case(
        val id: String,
        val sender: String,
        val body: String,
        val expect: Map<String, Any?>
    )

    private fun loadCorpus(): List<Case> {
        val stream = javaClass.getResourceAsStream("/sms_corpus.jsonl")
            ?: error("sms_corpus.jsonl is missing from the test classpath")

        return stream.bufferedReader().useLines { lines ->
            // A Windows editor will happily re-add a BOM to the corpus, and a leading
            // U+FEFF is not whitespace, so trim() alone leaves it on line 1.
            lines.map { it.removePrefix("﻿").trim() }
                .filter { it.isNotEmpty() && !it.startsWith("{\"_comment") }
                .mapIndexed { index, line ->
                    val obj = try {
                        @Suppress("UNCHECKED_CAST")
                        MiniJson(line).parse() as Map<String, Any?>
                    } catch (malformed: Exception) {
                        error("Corpus line ${index + 1} is not valid JSON: ${malformed.message}")
                    }

                    @Suppress("UNCHECKED_CAST")
                    Case(
                        id = obj["id"] as String,
                        sender = obj["sender"] as String,
                        body = obj["body"] as String,
                        expect = obj["expect"] as Map<String, Any?>
                    )
                }
                .toList()
        }
    }
}

/**
 * A JSON reader for the corpus, so the accuracy gate depends on no parsing library.
 *
 * `org.json` is not a real implementation in JVM unit tests — the android.jar stubs
 * throw — and adding a serialization dependency to read one flat test fixture is a
 * poor trade in a project whose dependency list is a privacy surface.
 */
private class MiniJson(private val s: String) {

    private var i = 0

    fun parse(): Any? {
        val value = value()
        skipWhitespace()
        require(i >= s.length) { "trailing content at offset $i" }
        return value
    }

    private fun skipWhitespace() {
        while (i < s.length && s[i].isWhitespace()) i++
    }

    private fun value(): Any? {
        skipWhitespace()
        require(i < s.length) { "unexpected end of input" }
        return when {
            s[i] == '{' -> obj()
            s[i] == '[' -> arr()
            s[i] == '"' -> str()
            s.startsWith("true", i) -> { i += 4; true }
            s.startsWith("false", i) -> { i += 5; false }
            s.startsWith("null", i) -> { i += 4; null }
            else -> num()
        }
    }

    private fun obj(): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        i++ // '{'
        skipWhitespace()
        if (i < s.length && s[i] == '}') {
            i++
            return map
        }
        while (true) {
            skipWhitespace()
            val key = str()
            skipWhitespace()
            require(i < s.length && s[i] == ':') { "expected ':' at offset $i" }
            i++
            map[key] = value()
            skipWhitespace()
            require(i < s.length) { "unterminated object" }
            when (s[i]) {
                ',' -> i++
                '}' -> { i++; return map }
                else -> error("expected ',' or '}' at offset $i")
            }
        }
    }

    private fun arr(): List<Any?> {
        val list = mutableListOf<Any?>()
        i++ // '['
        skipWhitespace()
        if (i < s.length && s[i] == ']') {
            i++
            return list
        }
        while (true) {
            list += value()
            skipWhitespace()
            require(i < s.length) { "unterminated array" }
            when (s[i]) {
                ',' -> i++
                ']' -> { i++; return list }
                else -> error("expected ',' or ']' at offset $i")
            }
        }
    }

    private fun str(): String {
        require(i < s.length && s[i] == '"') { "expected a string at offset $i" }
        i++
        val sb = StringBuilder()
        while (i < s.length && s[i] != '"') {
            val c = s[i]
            if (c != '\\') {
                sb.append(c)
                i++
                continue
            }
            i++
            require(i < s.length) { "dangling escape" }
            when (val esc = s[i]) {
                '"', '\\', '/' -> { sb.append(esc); i++ }
                'b' -> { sb.append('\b'); i++ }
                'f' -> { sb.append('\u000C'); i++ }
                'n' -> { sb.append('\n'); i++ }
                'r' -> { sb.append('\r'); i++ }
                't' -> { sb.append('\t'); i++ }
                'u' -> {
                    require(i + 4 < s.length) { "truncated unicode escape" }
                    sb.append(s.substring(i + 1, i + 5).toInt(16).toChar())
                    i += 5
                }
                else -> error("unknown escape \\$esc at offset $i")
            }
        }
        require(i < s.length) { "unterminated string" }
        i++ // closing quote
        return sb.toString()
    }

    private fun num(): Double {
        val start = i
        while (i < s.length && (s[i].isDigit() || s[i] in "-+.eE")) i++
        return s.substring(start, i).toDoubleOrNull()
            ?: error("invalid number '${s.substring(start, i)}' at offset $start")
    }
}
