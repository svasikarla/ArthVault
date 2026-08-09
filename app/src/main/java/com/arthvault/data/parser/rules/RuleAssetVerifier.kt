package com.arthvault.data.parser.rules

import org.json.JSONException
import java.io.InputStream
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Verifies a signed parser-rule file (T2.2).
 *
 * Everything here is offline. `java.security.Signature` against a public key
 * compiled into the APK involves no network, no key server and no revocation
 * lookup, so a signed, updatable rule format costs nothing against T1.1's
 * zero-egress guarantee. What the signature buys is that a sideloaded file (5.3) is
 * exactly as trustworthy as the bundled one: both must have been signed by the key
 * whose public half ships in `res/raw`.
 *
 * What it does *not* buy is protection from whoever holds the private key. This is
 * an integrity check on distribution, not a sandbox around the rules themselves.
 */
class RuleAssetVerifier(private val publicKeyDer: ByteArray) {

    private val publicKey: PublicKey by lazy {
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicKeyDer))
    }

    /** Reads [stream] fully and verifies it. The stream is always closed. */
    fun load(stream: InputStream): RuleLoadResult =
        stream.use { load(it.readBytes().toString(Charsets.UTF_8)) }

    fun load(json: String): RuleLoadResult {
        val document = try {
            ParserRuleJson.parse(json)
        } catch (malformed: JSONException) {
            return RuleLoadResult.Malformed(malformed.message ?: "not valid JSON")
        } catch (malformed: IllegalArgumentException) {
            return RuleLoadResult.Malformed(malformed.message ?: "not a valid rule file")
        }

        if (document.schemaVersion != ParserRuleDocument.SUPPORTED_SCHEMA_VERSION) {
            return RuleLoadResult.UnsupportedSchema(
                found = document.schemaVersion,
                supported = ParserRuleDocument.SUPPORTED_SCHEMA_VERSION
            )
        }

        return if (isSignatureValid(document)) {
            RuleLoadResult.Loaded(document)
        } else {
            RuleLoadResult.BadSignature
        }
    }

    fun isSignatureValid(document: ParserRuleDocument): Boolean = try {
        val expected = Base64.getDecoder().decode(document.signature)
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey)
            update(RuleCanonicalForm.of(document))
            verify(expected)
        }
    } catch (rejected: Exception) {
        // Malformed base64, a signature over a different curve, a field carrying a
        // separator character — every one of these means "do not trust this file",
        // and none of them should be able to crash ingestion.
        false
    }
}
