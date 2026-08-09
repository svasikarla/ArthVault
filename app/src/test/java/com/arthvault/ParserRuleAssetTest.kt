package com.arthvault

import com.arthvault.data.parser.rules.ParserRuleDocument
import com.arthvault.data.parser.rules.ParserRuleJson
import com.arthvault.data.parser.rules.RuleAssetVerifier
import com.arthvault.data.parser.rules.RuleCanonicalForm
import com.arthvault.data.parser.rules.RuleLoadResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * T2.2 — the shipped rule asset, and the tool that signs it.
 *
 * Runs under Robolectric because [ParserRuleJson] uses `org.json`, whose plain-JVM
 * stubs throw.
 *
 * ### Re-signing after editing the rules
 *
 * Editing `parser_rules_v1.json` invalidates its signature, and
 * `the bundled rule asset verifies` then fails — which is the intended behaviour,
 * not an obstacle. Re-sign with:
 *
 * ```
 * ARTH_SIGN_RULES=1 ./gradlew :app:testDebugUnitTest --tests "*ParserRuleAssetTest*"
 * ```
 *
 * which needs `parser-rules-private-key.pem` in the project root. Without the
 * environment variable the signing test is skipped, so an ordinary build can never
 * rewrite the signature by accident.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ParserRuleAssetTest {

    private val projectRoot: File = File(System.getProperty("user.dir")!!)
    private val assetFile = File(projectRoot, "src/main/assets/parser_rules_v1.json")
    private val publicKeyFile = File(projectRoot, "src/main/res/raw/parser_rules_public_key.der")
    private val privateKeyFile = File(projectRoot.parentFile, "parser-rules-private-key.pem")

    private fun verifier() = RuleAssetVerifier(publicKeyFile.readBytes())

    @Test
    fun `the bundled rule asset verifies against the bundled public key`() {
        when (val result = verifier().load(assetFile.readText())) {
            is RuleLoadResult.Loaded -> {
                assertEquals(
                    ParserRuleDocument.SUPPORTED_SCHEMA_VERSION,
                    result.document.schemaVersion
                )
                assertTrue(
                    "a rule file with no rules would silently disable ingestion",
                    result.document.rules.isNotEmpty()
                )
            }

            // Deliberately fatal. The asset ships inside the APK, so a signature that
            // does not verify here is a signature that will not verify on a phone —
            // and the app would then start with no parser rules at all.
            else -> throw AssertionError(
                "parser_rules_v1.json did not verify: $result. " +
                    "If you edited the rules, re-sign them (see this class's docs)."
            )
        }
    }

    @Test
    fun `every rule field can be canonicalised`() {
        val document = ParserRuleJson.parse(assetFile.readText())
        val offenders = document.rules.filterNot { rule ->
            listOf(rule.ruleId, rule.senderPattern, rule.regexPattern)
                .all(RuleCanonicalForm::isCanonicalisable)
        }
        assertTrue(
            "these rules contain an ASCII separator and cannot be signed: " +
                offenders.map { it.ruleId },
            offenders.isEmpty()
        )
    }

    @Test
    fun `every rule regex compiles and its capture groups exist`() {
        val document = ParserRuleJson.parse(assetFile.readText())

        for (rule in document.rules) {
            val pattern = java.util.regex.Pattern.compile(rule.regexPattern)
            val groups = pattern.matcher("").groupCount()

            // A rule pointing at a group the pattern does not have would silently
            // extract nothing rather than fail, which is how a rule set degrades
            // without anyone noticing.
            val referenced = listOfNotNull(
                rule.amountGroup, rule.directionGroup, rule.merchantGroup,
                rule.accountGroup, rule.channelGroup
            )
            val outOfRange = referenced.filter { it < 1 || it > groups }
            assertTrue(
                "${rule.ruleId} references capture group(s) $outOfRange but the " +
                    "pattern has $groups",
                outOfRange.isEmpty()
            )
        }
    }

    @Test
    fun `tampering with a rule invalidates the signature`() {
        val document = ParserRuleJson.parse(assetFile.readText())

        // The exact attack the signature exists to stop: a rule that still parses,
        // still verifies structurally, and quietly redirects what the ledger records.
        val tampered = document.copy(
            rules = document.rules.mapIndexed { index, rule ->
                if (index == 0) rule.copy(amountGroup = rule.amountGroup + 1) else rule
            }
        )

        assertTrue(
            "an unmodified document must verify",
            verifier().isSignatureValid(document)
        )
        assertTrue(
            "a modified capture group must not verify",
            !verifier().isSignatureValid(tampered)
        )
    }

    @Test
    fun `signature covers rule order`() {
        val document = ParserRuleJson.parse(assetFile.readText())

        // Order is the tie-break between equal priorities, so reordering changes
        // which rule wins on an ambiguous message. It has to be signed content.
        val reordered = document.copy(rules = document.rules.reversed())
        assertTrue(
            "reordering the rules must not verify",
            !verifier().isSignatureValid(reordered)
        )
    }

    /**
     * The signing tool. Skipped unless `ARTH_SIGN_RULES` is set, so a normal build
     * cannot rewrite the signature as a side effect of running tests.
     */
    @Test
    fun `re-sign the rule asset`() {
        assumeTrue("set ARTH_SIGN_RULES=1 to re-sign", System.getenv("ARTH_SIGN_RULES") == "1")
        assumeTrue(
            "no private key at ${privateKeyFile.absolutePath} — " +
                "generate one with tools/GenParserRuleKey.java",
            privateKeyFile.exists()
        )

        val original = assetFile.readText()
        val document = ParserRuleJson.parse(original)

        val pem = privateKeyFile.readText()
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace(Regex("\\s"), "")
        val privateKey = KeyFactory.getInstance("EC")
            .generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)))

        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(RuleCanonicalForm.of(document))
            Base64.getEncoder().encodeToString(sign())
        }

        assetFile.writeText(ParserRuleJson.withSignature(original, signature))

        val result = verifier().load(assetFile.readText())
        assertTrue(
            "the freshly written signature must verify, but got $result",
            result is RuleLoadResult.Loaded
        )
        println("parser_rules_v1.json re-signed (rulesVersion ${document.rulesVersion})")
    }
}
