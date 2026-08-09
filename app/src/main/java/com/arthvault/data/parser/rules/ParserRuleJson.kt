package com.arthvault.data.parser.rules

import org.json.JSONObject

/**
 * Reads the rule-file JSON into [ParserRuleDocument].
 *
 * Separate from [RuleAssetVerifier] because the signing tool needs to parse a file
 * whose signature is by definition not yet valid. Keeping "what the file says" apart
 * from "should we trust it" also means the sideload screen (5.3) can show the user
 * the version and rule count of a file it is about to reject.
 *
 * `org.json` is the Android platform implementation, so this adds no dependency.
 * Its stubs throw in plain JVM unit tests, which is why the tests around this run
 * under Robolectric.
 */
object ParserRuleJson {

    /**
     * @throws org.json.JSONException if the text is not JSON or a required key is absent.
     * @throws IllegalArgumentException if it is JSON but not a usable rule file.
     */
    fun parse(json: String): ParserRuleDocument {
        val root = JSONObject(json)
        val rulesArray = root.getJSONArray("rules")

        val rules = (0 until rulesArray.length()).map { index ->
            val entry = rulesArray.getJSONObject(index)
            ParserRuleDocumentEntry(
                ruleId = entry.getString("ruleId"),
                description = entry.optString("description", ""),
                senderPattern = entry.getString("senderPattern"),
                regexPattern = entry.getString("regexPattern"),
                amountGroup = entry.getInt("amountGroup"),
                directionGroup = entry.optIntOrNull("directionGroup"),
                merchantGroup = entry.optIntOrNull("merchantGroup"),
                accountGroup = entry.optIntOrNull("accountGroup"),
                channelGroup = entry.optIntOrNull("channelGroup"),
                priority = entry.getInt("priority"),
                isActive = entry.optBoolean("isActive", true)
            )
        }

        require(rules.isNotEmpty()) { "a rule file with no rules would disable ingestion" }
        require(rules.map { it.ruleId }.toSet().size == rules.size) {
            "duplicate ruleId — rule identity has to be unique to be upsertable"
        }

        return ParserRuleDocument(
            schemaVersion = root.getInt("schemaVersion"),
            rulesVersion = root.getInt("rulesVersion"),
            issuedAt = root.getString("issuedAt"),
            signature = root.optString("signature", ""),
            rules = rules
        )
    }

    /**
     * Rewrites the `signature` value in [json], leaving the rest of the text
     * untouched.
     *
     * Used only by the signing tool. Editing the text rather than re-serialising the
     * document keeps the file's formatting and comments-as-descriptions exactly as
     * the author wrote them — and it does not matter to verification either way,
     * because what gets signed is the canonical form, not the bytes.
     */
    fun withSignature(json: String, signature: String): String {
        val field = Regex("\"signature\"\\s*:\\s*\"[^\"]*\"")
        require(field.containsMatchIn(json)) { "no signature field to replace" }
        return field.replace(json, "\"signature\": \"$signature\"")
    }

    /**
     * JSON `null` and an absent key both mean "no capture group here", which is a
     * meaningful value rather than a default. `optInt` cannot express that: it
     * returns 0, and group 0 is the whole match.
     */
    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (!has(key) || isNull(key)) null else getInt(key)
}
