import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Signs app/src/main/assets/parser_rules_v*.json in place (T2.2).
 *
 *   "C:\Program Files\Android\Android Studio\jbr\bin\java.exe" tools/SignParserRules.java \
 *       app/src/main/assets/parser_rules_v1.json
 *
 * Reads parser-rules-private-key.pem — the gitignored half written by
 * {@link GenParserRuleKey} — computes the canonical payload, and rewrites only the
 * file's "signature" value. Everything else in the text, including the descriptions
 * and the indentation, is left exactly as the author wrote it: what gets signed is
 * the canonical form, not the bytes.
 *
 * <h2>Why this duplicates RuleCanonicalForm</h2>
 *
 * That object is Kotlin in the app module, and reaching it from here would mean
 * either compiling the app to sign its own asset or pulling the signer into the
 * Gradle build. So the canonical form is restated below, and the two are held
 * together by {@code ParserRuleAssetTest}, which verifies the bundled asset using the
 * app's own RuleCanonicalForm and the public key from res/raw. If this file ever
 * drifts from that one, that test goes red on the next build — the drift cannot ship.
 *
 * Keep the two in step: any change to RuleCanonicalForm.of needs the same change to
 * {@link #canonicalForm}.
 *
 * No network use anywhere in this file; the private key never leaves the machine.
 */
public final class SignParserRules {

    /** Field separator. ASCII US. */
    private static final char US = '\u001F';

    /** Record separator. ASCII RS. */
    private static final char RS = '\u001E';

    /** Domain separator; see RuleCanonicalForm.PREAMBLE. */
    private static final String PREAMBLE = "arth-vault/parser-rules";

    private static final Path PRIVATE_KEY_PATH = Path.of("parser-rules-private-key.pem");

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("usage: java tools/SignParserRules.java <path-to-rule-file.json>");
            System.exit(2);
        }
        Path assetPath = Path.of(args[0]);
        if (!Files.exists(PRIVATE_KEY_PATH)) {
            System.err.println(
                "Missing " + PRIVATE_KEY_PATH + ". It is gitignored by design; generate a "
                    + "keypair with tools/GenParserRuleKey.java, or restore the existing "
                    + "private key. Generating a new one invalidates every signature made "
                    + "with the old one."
            );
            System.exit(1);
        }

        String json = Files.readString(assetPath, StandardCharsets.UTF_8);

        @SuppressWarnings("unchecked")
        Map<String, Object> document = (Map<String, Object>) new MiniJson(json).parse();

        byte[] payload = canonicalForm(document);

        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(readPrivateKey());
        signer.update(payload);
        String signature = Base64.getEncoder().encodeToString(signer.sign());

        Files.writeString(assetPath, withSignature(json, signature), StandardCharsets.UTF_8);

        @SuppressWarnings("unchecked")
        List<Object> rules = (List<Object>) document.get("rules");
        System.out.println("signed    " + assetPath);
        System.out.println("  schemaVersion " + intOf(document.get("schemaVersion")));
        System.out.println("  rulesVersion  " + intOf(document.get("rulesVersion")));
        System.out.println("  issuedAt      " + document.get("issuedAt"));
        System.out.println("  rules         " + rules.size());
        System.out.println("  signature     " + signature);
        System.out.println();
        System.out.println("Run ParserRuleAssetTest to confirm the app accepts it.");
    }

    /**
     * The exact bytes RuleCanonicalForm.of produces for this document.
     *
     * The rules are appended in array order, not sorted order: the order they appear
     * in the file is the tie-break for equal priorities, so it is signed content.
     * "signature" is excluded — it is the output of this function, not an input.
     */
    private static byte[] canonicalForm(Map<String, Object> document) {
        StringBuilder out = new StringBuilder();
        out.append(PREAMBLE).append(RS);
        out.append(intOf(document.get("schemaVersion"))).append(US);
        out.append(intOf(document.get("rulesVersion"))).append(US);
        out.append(field(str(document.get("issuedAt")))).append(RS);

        Object rulesValue = document.get("rules");
        if (!(rulesValue instanceof List)) {
            throw new IllegalArgumentException("\"rules\" is missing or is not an array");
        }
        @SuppressWarnings("unchecked")
        List<Object> rules = (List<Object>) rulesValue;
        if (rules.isEmpty()) {
            throw new IllegalArgumentException("a rule file with no rules would disable ingestion");
        }

        for (Object entry : rules) {
            @SuppressWarnings("unchecked")
            Map<String, Object> rule = (Map<String, Object>) entry;
            out.append(field(str(rule.get("ruleId")))).append(US);
            out.append(field(str(rule.get("senderPattern")))).append(US);
            out.append(field(str(rule.get("regexPattern")))).append(US);
            out.append(intOf(rule.get("amountGroup"))).append(US);
            out.append(nullableInt(rule.get("directionGroup"))).append(US);
            out.append(nullableInt(rule.get("merchantGroup"))).append(US);
            out.append(nullableInt(rule.get("accountGroup"))).append(US);
            out.append(nullableInt(rule.get("channelGroup"))).append(US);
            out.append(intOf(rule.get("priority"))).append(US);
            // ParserRuleJson defaults an absent isActive to true.
            out.append(rule.get("isActive") == null ? true : (Boolean) rule.get("isActive"))
                .append(RS);
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Mirrors ParserRuleJson.withSignature: rewrite the value, touch nothing else. */
    private static String withSignature(String json, String signature) {
        String field = "\"signature\"\\s*:\\s*\"[^\"]*\"";
        if (!json.matches("(?s).*" + field + ".*")) {
            throw new IllegalArgumentException("no signature field to replace");
        }
        return json.replaceAll(field, "\"signature\": \"" + signature + "\"");
    }

    private static PrivateKey readPrivateKey() throws Exception {
        String pem = Files.readString(PRIVATE_KEY_PATH, StandardCharsets.UTF_8)
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(pem);
        return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    /**
     * A field carrying a separator could be split differently by the verifier than by
     * the signer, which is how a canonicalisation scheme becomes forgeable. The app
     * rejects such a file; refusing to sign it says so while it can still be fixed.
     */
    private static String field(String value) {
        if (value.indexOf(US) >= 0 || value.indexOf(RS) >= 0) {
            throw new IllegalArgumentException(
                "Rule field contains an ASCII separator and cannot be signed: "
                    + value.substring(0, Math.min(60, value.length()))
            );
        }
        return value;
    }

    private static String str(Object value) {
        if (!(value instanceof String)) {
            throw new IllegalArgumentException("expected a string but found: " + value);
        }
        return (String) value;
    }

    /** JSON has one number type; the canonical form renders these as integers. */
    private static int intOf(Object value) {
        if (!(value instanceof Double)) {
            throw new IllegalArgumentException("expected a number but found: " + value);
        }
        return (int) Math.round((Double) value);
    }

    private static String nullableInt(Object value) {
        return value == null ? "null" : Integer.toString(intOf(value));
    }

    // ---- JSON reading ----------------------------------------------------

    /**
     * A JSON reader, so signing depends on no library.
     *
     * The app reads this same file with org.json. That the two parsers differ is
     * harmless, and is the reason ParserRuleJson exists apart from RuleAssetVerifier:
     * neither side signs the text, so only the canonical values have to agree.
     */
    private static final class MiniJson {

        private final String s;
        private int i = 0;

        MiniJson(String s) {
            this.s = s;
        }

        Object parse() {
            Object value = value();
            skipWhitespace();
            if (i < s.length()) {
                throw new IllegalArgumentException("trailing content at offset " + i);
            }
            return value;
        }

        private void skipWhitespace() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        private Object value() {
            skipWhitespace();
            if (i >= s.length()) throw new IllegalArgumentException("unexpected end of input");
            char c = s.charAt(i);
            if (c == '{') return obj();
            if (c == '[') return arr();
            if (c == '"') return str();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            return num();
        }

        private Map<String, Object> obj() {
            Map<String, Object> map = new LinkedHashMap<>();
            i++; // '{'
            skipWhitespace();
            if (i < s.length() && s.charAt(i) == '}') { i++; return map; }
            while (true) {
                skipWhitespace();
                String key = str();
                skipWhitespace();
                expect(':');
                map.put(key, value());
                skipWhitespace();
                if (i >= s.length()) throw new IllegalArgumentException("unterminated object");
                char c = s.charAt(i);
                if (c == ',') { i++; continue; }
                if (c == '}') { i++; return map; }
                throw new IllegalArgumentException("expected ',' or '}' at offset " + i);
            }
        }

        private List<Object> arr() {
            List<Object> list = new ArrayList<>();
            i++; // '['
            skipWhitespace();
            if (i < s.length() && s.charAt(i) == ']') { i++; return list; }
            while (true) {
                list.add(value());
                skipWhitespace();
                if (i >= s.length()) throw new IllegalArgumentException("unterminated array");
                char c = s.charAt(i);
                if (c == ',') { i++; continue; }
                if (c == ']') { i++; return list; }
                throw new IllegalArgumentException("expected ',' or ']' at offset " + i);
            }
        }

        private String str() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (i < s.length() && s.charAt(i) != '"') {
                char c = s.charAt(i);
                if (c != '\\') { sb.append(c); i++; continue; }
                i++;
                if (i >= s.length()) throw new IllegalArgumentException("dangling escape");
                char esc = s.charAt(i);
                switch (esc) {
                    case '"': case '\\': case '/': sb.append(esc); i++; break;
                    case 'b': sb.append('\b'); i++; break;
                    case 'f': sb.append('\f'); i++; break;
                    case 'n': sb.append('\n'); i++; break;
                    case 'r': sb.append('\r'); i++; break;
                    case 't': sb.append('\t'); i++; break;
                    case 'u':
                        if (i + 4 >= s.length()) {
                            throw new IllegalArgumentException("truncated unicode escape");
                        }
                        sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                        i += 5;
                        break;
                    default:
                        throw new IllegalArgumentException("unknown escape \\" + esc);
                }
            }
            if (i >= s.length()) throw new IllegalArgumentException("unterminated string");
            i++; // closing quote
            return sb.toString();
        }

        private Double num() {
            int start = i;
            while (i < s.length() && (Character.isDigit(s.charAt(i)) || "-+.eE".indexOf(s.charAt(i)) >= 0)) {
                i++;
            }
            return Double.valueOf(s.substring(start, i));
        }

        private void expect(char c) {
            if (i >= s.length() || s.charAt(i) != c) {
                throw new IllegalArgumentException("expected '" + c + "' at offset " + i);
            }
            i++;
        }
    }

    private SignParserRules() {
    }
}
