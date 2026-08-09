package com.arthvault.data.parser.rules

/**
 * The exact bytes that get signed and verified (T2.2).
 *
 * Signing the file bytes would be simpler and wrong: JSON has no canonical
 * encoding, so re-indenting the asset, reordering its keys, or a text editor
 * rewriting CRLF to LF would all invalidate a rule set that had not actually
 * changed. The predictable response to a signature that fails for cosmetic reasons
 * is to stop checking the signature.
 *
 * So the payload is derived from *parsed values*, in a fixed field order, joined
 * with two ASCII control characters that cannot occur in any legitimate field. The
 * signer and the verifier both call this object — it is the one piece of Phase 5
 * that must never fork, which is why it is pure Kotlin with no Android imports and
 * no JSON handling of its own. The signer parses JSON one way and the app another;
 * that difference is safe precisely because neither one signs the text.
 */
object RuleCanonicalForm {

    /** Field separator. ASCII US. */
    private const val US = '\u001F'

    /** Record separator. ASCII RS. */
    private const val RS = '\u001E'

    /**
     * Domain separator. Without it a signature over some other structure with the
     * same field layout could be replayed as a rule file.
     */
    private const val PREAMBLE = "arth-vault/parser-rules"

    /**
     * True when [value] can appear in a canonicalised field.
     *
     * A field containing a separator could be split differently by the verifier than
     * by the signer, which is the classic way a canonicalisation scheme becomes
     * forgeable. Rejecting such a rule is safe: no regex, sender pattern or
     * identifier has any reason to contain an ASCII control character.
     */
    fun isCanonicalisable(value: String): Boolean = !value.contains(US) && !value.contains(RS)

    /**
     * The signed payload for [document]'s rules and version metadata.
     *
     * [ParserRuleDocument.signature] is deliberately excluded — it is the output of
     * this function, not an input to it.
     *
     * @throws IllegalArgumentException if any field contains a separator character.
     */
    fun of(document: ParserRuleDocument): ByteArray = buildString {
        append(PREAMBLE).append(RS)
        append(document.schemaVersion).append(US)
        append(document.rulesVersion).append(US)
        append(field(document.issuedAt)).append(RS)

        // Array order, not sorted order. The order rules appear in the file is
        // itself signed content: it is the tie-break for equal priorities, so
        // reordering the array changes which rule wins on an ambiguous message.
        for (rule in document.rules) {
            append(field(rule.ruleId)).append(US)
            append(field(rule.senderPattern)).append(US)
            append(field(rule.regexPattern)).append(US)
            append(rule.amountGroup).append(US)
            append(nullable(rule.directionGroup)).append(US)
            append(nullable(rule.merchantGroup)).append(US)
            append(nullable(rule.accountGroup)).append(US)
            append(nullable(rule.channelGroup)).append(US)
            append(rule.priority).append(US)
            append(rule.isActive).append(RS)
        }
    }.toByteArray(Charsets.UTF_8)

    /**
     * [description] is absent from the payload on purpose: it is documentation for
     * whoever reads the file, it does not affect parsing, and signing it would mean
     * fixing a typo in a comment requires re-signing the rule set.
     */
    private fun field(value: String): String {
        require(isCanonicalisable(value)) {
            "Rule field contains an ASCII separator and cannot be signed: ${value.take(60)}"
        }
        return value
    }

    private fun nullable(value: Int?): String = value?.toString() ?: "null"
}
