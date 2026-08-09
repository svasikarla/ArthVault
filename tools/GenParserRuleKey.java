import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

/**
 * Generates the ECDSA P-256 keypair that signs parser_rules_v*.json (T2.2).
 *
 * Run once, and again only to rotate the key:
 *
 *   "C:\Program Files\Android\Android Studio\jbr\bin\java.exe" tools/GenParserRuleKey.java
 *
 * Writes:
 *   app/src/main/res/raw/parser_rules_public_key.der   X.509 SubjectPublicKeyInfo, shipped in the APK
 *   parser-rules-private-key.pem                        PKCS#8, gitignored, never ships
 *
 * Rotating the key invalidates every signature made with the old one, including any
 * rule file a user has already sideloaded. That is the point of a signature, but it
 * does mean a rotation has to be paired with a re-signed asset in the same release.
 *
 * There is no network use anywhere in this file; the keypair is generated locally and
 * the private half never leaves the machine that ran it (T1.1).
 */
public final class GenParserRuleKey {

    public static void main(String[] args) throws Exception {
        Path publicKeyPath = Path.of("app/src/main/res/raw/parser_rules_public_key.der");
        Path privateKeyPath = Path.of("parser-rules-private-key.pem");

        if (Files.exists(privateKeyPath)) {
            System.err.println(
                "Refusing to overwrite " + privateKeyPath + ".\n" +
                "Delete it deliberately if you actually mean to rotate the signing key — " +
                "every existing signature stops verifying when you do."
            );
            System.exit(1);
        }

        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair pair = generator.generateKeyPair();

        Files.createDirectories(publicKeyPath.getParent());
        try (FileOutputStream out = new FileOutputStream(publicKeyPath.toFile())) {
            out.write(pair.getPublic().getEncoded());
        }

        String pem = "-----BEGIN PRIVATE KEY-----\n"
            + Base64.getMimeEncoder(64, new byte[] { '\n' })
                .encodeToString(pair.getPrivate().getEncoded())
            + "\n-----END PRIVATE KEY-----\n";
        Files.writeString(privateKeyPath, pem);

        System.out.println("public key  -> " + publicKeyPath + " (" + pair.getPublic().getEncoded().length + " bytes DER)");
        System.out.println("private key -> " + privateKeyPath + "  [gitignored — do not commit]");
    }
}
