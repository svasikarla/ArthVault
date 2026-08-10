# Arth Vault

A local-first personal finance ledger for Android. It reconstructs your financial
record from bank SMS already on the device, and never sends any of it anywhere.

Built against [`local-finance-app-spec.md`](local-finance-app-spec.md) (v0.1).

## The privacy guarantee

The app cannot reach the network. This is structural, not a promise:

- `android.permission.INTERNET` is not declared, and is explicitly stripped at
  manifest-merge time via `tools:node="remove"` so no transitive dependency can
  reintroduce it.
- No networking library (OkHttp, Retrofit, Firebase, any HTTP client) is on the
  runtime classpath.
- `android:allowBackup="false"` — the ledger is not eligible for Google cloud backup.
- No telemetry, analytics, or crash reporting.

`NetworkEgressGuardTest` asserts all of this against the **merged** manifest and
the runtime classpath, so the build fails if it ever regresses.

Verify it yourself on any build:

```bash
./gradlew :app:assembleDebug
$ANDROID_HOME/build-tools/36.0.0/aapt2 dump permissions \
    app/build/outputs/apk/debug/app-debug.apk
```

You should see exactly `READ_SMS`, `RECEIVE_SMS`, `USE_BIOMETRIC` and
`USE_FINGERPRINT` — the last two gate the unlock prompt and reach no network.
Notably absent: `INTERNET`.

## Requirements

- JDK 17+ (Android Studio's bundled JBR works)
- Android SDK with platform 36
- Gradle 9.3.1+ — supplied by the wrapper; AGP 9.1.1 requires it

## Build and run

```bash
./gradlew :app:assembleDebug
./gradlew :app:installDebug     # with a device or emulator attached
```

Two files are needed locally and are deliberately not committed:

- `local.properties` — set `sdk.dir` to your Android SDK path.
- `debug.keystore` — the debug signing config expects one in the project root.
  Copy your standard one (`~/.android/debug.keystore`), or generate it:

  ```bash
  keytool -genkeypair -v -keystore debug.keystore -storepass android \
      -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 \
      -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
  ```

A release build needs an upload key, supplied via `KEYSTORE_PATH` (plus
`STORE_PASSWORD` / `KEY_PASSWORD`) or as `my-upload-key.jks` in the project root.
Without one `assembleRelease` still runs — useful for measuring size — but emits
`app-release-unsigned.apk`, which will not install. That is deliberate: signing a
release with the `debug.keystore` in this repository would let anyone who cloned the
project push an update over it.

## Tests

```bash
./gradlew :app:test
```

## Analysis

Everything on the insights screen is arithmetic over rows you can open. Recurring
charges need at least three of them at a consistent interval and a stable price;
a price rise has to repeat before it is called a hike. Every figure carries the
transaction ids behind it, so tapping an insight shows the charges that produced it.

Moving money between your own accounts is not spending, but your bank describes it
exactly like a payment. Mark an account as yours under **Ingestion → My Accounts** and
transfers to it stop counting as spend — and transfers *from* it stop counting as
income, which is the same bug pointing the other way. Only accounts already seen in
your ledger are offered, nothing is excluded until you say so, and the insights screen
reports how much was left out so a mistake is visible rather than silent.

You can also ask the ledger a question — "spend on fuel last quarter", "biggest
charge this month", "how much did I earn last month". The grammar is deterministic
and small: there is no model involved, and a question it cannot read is refused
rather than answered approximately.

## Parser rules

The parser has no patterns compiled into it. Every rule lives in
`app/src/main/assets/parser_rules_v1.json`, signed with ECDSA-P256 and verified at
runtime against a public key in `res/raw` — offline, so the zero-egress guarantee is
untouched. The app installs them on each unlock if the file's `rulesVersion` is newer
than what is already applied, and a file that fails verification changes nothing.

After editing the rules, re-sign them or the build will fail:

```bash
ARTH_SIGN_RULES=1 ./gradlew :app:testDebugUnitTest --tests "*ParserRuleAssetTest*"
```

This needs `parser-rules-private-key.pem` in the project root; generate one with
`java tools/GenParserRuleKey.java`. The private key is gitignored and never ships.

`ParserAccuracyTest` scores the parser against
`app/src/test/resources/sms_corpus.jsonl` — 205 bank messages across every
allowlisted sender — field by field, and fails the build below 95% (T2.4). It
currently measures **0.9993**. When you find a message the parser reads wrong, add
it to the corpus before fixing the regex.

## Permissions

`READ_SMS` and `RECEIVE_SMS` to read bank transaction messages, plus
`USE_BIOMETRIC` / `USE_FINGERPRINT` to unlock the encrypted ledger.
No location, no contacts, no storage-wide access.

## Status

See [`ROADMAP.md`](ROADMAP.md) for what is implemented against the spec and what
is still outstanding.
