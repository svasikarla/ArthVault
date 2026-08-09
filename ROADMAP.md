# Arth Vault — remediation roadmap

Tracks conformance with [`local-finance-app-spec.md`](local-finance-app-spec.md) (v0.1).
Requirement IDs below (F1–F5, T1–T6) refer to that document.

Status legend: **done** · **open** · **blocked**

| Phase | Scope | Status |
|---|---|---|
| 0 | Unblock the build and the test task | done |
| 1 | Restore the zero-egress guarantee | done |
| 2 | Stop showing the user wrong numbers | done |
| 3 | Close the ingestion gaps | done |
| 4 | Storage security and record integrity | done |
| 5 | Parser rules as versioned, signed data files | done |
| 6 | Analysis quality, trends, and query | open |
| 7 | Accuracy harness and release hygiene | done |

Phase 7 was taken before 5 and 6, for the reason recorded under "Open questions"
below: both of those change how messages are read and how the numbers are computed,
and neither is safe to attempt against a parser whose accuracy nobody has measured.

---

## Completed

**Phase 0** — deleted four AI Studio template tests (one referenced a `Greeting`
composable that does not exist, so `./gradlew test` could not compile).

**Phase 1** — removed Firebase, OkHttp, Retrofit and the `google-services` plugin.
`android.permission.INTERNET` is stripped at manifest-merge time with
`tools:node="remove"`. `NetworkEgressGuardTest` asserts this against the *merged*
manifest and the runtime classpath, so the build fails if it regresses.

**Phase 2** — corrected the analytics that were producing wrong on-screen numbers.

**Phase 3** — sender allowlist honoured, parser rules scoped by sender, account
tail and balance extraction, refund/EMI typing, `txnHash` for cross-source dedup.

**Phase 4** — SQLCipher (AES-256) with the passphrase wrapped by an auth-bound
AndroidKeystore key; biometric/device-credential unlock; transactions immutable
with corrections as append-only `adjustments` folded at read time; encrypted
`.avault` backup to a user-chosen location. SMS arriving while locked is *not*
buffered by the app — the OS inbox is the queue, and ingestion runs once at unlock
from a stored watermark, so there is no background battery cost.

**Phase 7** — accuracy is now measured rather than asserted.

`app/src/test/resources/sms_corpus.jsonl` holds **205 messages** across all 22
allowlisted senders, including declined transactions, refunds, EMI notices,
balance-only alerts, promotional SMS and OTPs that must not parse.
`ParserAccuracyTest` scores **per field, not per message** and fails the build below
0.95, so T2.4 is a gate rather than an aspiration.

The first run scored **0.9262**, and what it found was worse than the number:

- **Merchant extraction was 0.483.** The cause was three seeded `senderPattern = ".*"`
  rules in `DefaultSeedData`. Database rules are evaluated *before* the built-in
  patterns, so those three decided almost every message — and they were markedly
  worse than the code they shadowed. On the commonest Indian layout the merchant
  capture stopped at the value date, so a payment to `swiggy@ybl` was recorded as a
  payment to **HDFC Bank**. Every bank in the corpus showed its own name as a
  merchant. The seeded rules are now removed; Phase 5 repopulates that table from a
  signed asset.
- Two **false positives**: an OTP quoting a transaction amount, and a pre-approved
  loan advert, both booked as real spend (₹5,000 and ₹15,00,000).
- `"towards"` was being read as the preposition `to`, leaving merchants named
  `"wards EMI for LOAN 88213"`.
- `@` was stripped from VPAs, so `instamart@ybl` became `instamart ybl` and failed to
  group with itself across months.
- `cash` matched inside `cashback`, filing every cashback credit as an ATM withdrawal.
- A rail keyword alone made a credit a `TRANSFER`, so **NEFT salary and pension
  credits dropped out of income entirely**.
- `SenderMatcher.normalize` cut `BANK-SMS` at the first hyphen to `BANK`, which
  matched no allowlist entry — every manually imported or pasted message was silently
  filtered out of its own ingestion path.

After the fixes: **0.9993** (1436/1437 fields), with every field except merchant at
1.000. 69 unit tests pass.

Release hygiene: `isMinifyEnabled` and `isShrinkResources` on, with the Room and
SQLCipher keeps documented in `proguard-rules.pro`. Release APK **11.30 MB**, down
from 21.2 MB against a 30 MB budget. `applicationId` is now `com.arthvault.ledger`
and the package root `com.arthvault`, both renamed off the AI Studio template before
first real use. A release built without an upload key is now **unsigned** rather than
silently signed with the repository's own debug key.

Declared permissions: `READ_SMS`, `RECEIVE_SMS`, `USE_BIOMETRIC`, `USE_FINGERPRINT`.
No `INTERNET`.

**Phase 5** — parser rules are data, and the app has exactly one way to apply them.

All six patterns moved out of `SmsParserEngine` into
`app/src/main/assets/parser_rules_v1.json`, signed with ECDSA-P256 and verified
against a public key in `res/raw` using `java.security.Signature`. No network, no key
server, no revocation lookup, so T2.2 costs nothing against T1.1. `ParserAccuracyTest`
scores **0.9993 both before and after the move** — the corpus is what made a refactor
of this size safe to attempt.

Three decisions worth recording:

- **The signature covers a canonical form, not the file bytes.** JSON has no canonical
  encoding, so signing bytes means re-indenting the asset or a text editor rewriting
  CRLF invalidates a rule set that did not change — and the predictable response to a
  signature that fails for cosmetic reasons is to stop checking it. `RuleCanonicalForm`
  derives the signed payload from parsed values in a fixed field order, and the signer
  and verifier share that one object.
- **There is no built-in fallback set.** An empty rule list parses nothing, so the
  temptation was to keep the Kotlin patterns as a safety net — but that is the
  two-implementations problem Phase 7 removed, and the weaker one wins by
  construction. Instead the bundled asset is a build artefact: `ParserRuleAssetTest`
  fails the build if it does not verify, and the accuracy gate runs against it.
- **A rejected file changes nothing.** Bad signature, unknown schema, unreadable —
  every case keeps the installed rules. Falling back to no rules would turn a
  corrupted file into a total ingestion outage.

Rules are applied on **every unlock**, not at database creation. Seeding from Room's
`onCreate` meant a rule fix only ever reached fresh installs; everyone who already had
the app kept the broken rules forever, which is exactly what a versioned rule file
exists to prevent. An unchanged `rulesVersion` costs one integer comparison.

Schema v4 gives each rule a stable `ruleId` (upsert identity) and a `priority`
(evaluation order, user rules first per F2.2). `directionGroup` became nullable:
the old `0` meant "assume DEBIT", which silently inverted the sign on every credit
matched by a rule without a direction capture. `MIGRATION_3_4` also **deletes existing
system rules** — Phase 7 removed the three harmful seeded rules from new installs, but
an install that had already run still had those rows.

5.3 accepts a sideloaded file via `OpenDocument()`. Picking a file only *inspects* it;
version, rule count and signature verdict are shown, and installing is a separate
confirmation. Rejections show their specific reason, because "the signature does not
match" and "this is not a rule file" call for different responses.

`MigrationTest` closes a gap that had been open since Phase 4: `room-testing` was a
dependency with no test using it. It builds v3 from the *exported* `3.json` rather
than from SQL retyped by hand, then opens through Room so the identity-hash check
validates the migrated schema against `4.json` — a near-miss schema passes every data
assertion and then crashes on the next launch.

**87 tests pass.** Release APK 11.32 MB.

---

## Phase 6 — Analysis quality, trends, and query
*Spec: F3.1 · F3.2 · F3.6 · F4.1 · F4.4. Est. ~1 week.*

The statistical work is sound in method but thin in evidence — most of it looks at
only the two most recent transactions.

- **6.1** Real periodicity and amount clustering
  (`FinanceAnalyticsEngine.detectRecurringAndPriceHikes`). Today the detector compares
  `merchantTxns[0]` against `merchantTxns[1]`, with an escape hatch
  `if (isPeriodic || merchantTxns.size >= 3)` that makes any merchant with three
  charges "recurring" — order three Swiggy meals and Swiggy becomes a
  subscription. Require ≥3 charges, take the median inter-arrival gap, require
  `MAD / median < 0.25`, and require ≥70% of amounts within 15% of the median.
  Delete the `|| size >= 3` hatch.
- **6.2** Compare price hikes against the median of prior charges, not against the
  single previous charge, and require the new amount to persist across two
  consecutive periods. F3.2 is about *silent* hikes, which are sustained by nature.
- **6.3** Category trend comparison across periods (F3.6, entirely absent). Add
  `compareCategories(periodA, periodB)` returning per-category totals, absolute
  delta and percentage change.
- **6.4** Deterministic natural-language query (`data/query/QueryParser.kt`, new).
  A small intent grammar over metric / direction / category / merchant / period.
  No LLM — F4.2 forbids one from touching the numbers. Return the computed number
  *with* the contributing transaction IDs.
- **6.5** Tap-through from every insight (F4.4). Carry `transactionIds` on
  `RecurringItem`, `CategorySlice`, `AnomalyItem` and every query result.

---

## Open questions and known gaps

These are decisions or debts not owned by any phase above.

- **Merchant extraction is the parser's weakest field, and the corpus is the only
  thing that says so.** At 0.9993 overall, merchant sits at 0.994 while every other
  field is 1.000 — and before Phase 7 it was 0.483 while the aggregate still looked
  respectable. Field-level scoring is what makes that visible; keep it that way, and
  extend the corpus with any real message the parser gets wrong rather than patching
  the regex and moving on.
- **Own-account transfers count as spending.** `FinanceAnalyticsEngine` does not
  exclude `TxnType.TRANSFER` from outflows, so moving money between your own
  accounts inflates the spend total, the donut and the forecast. Needs a product
  decision: net out transfers when both legs are visible, or let the user mark
  accounts as their own.
- **Only v3→v4 is covered by a migration test.** Phase 5 added `MigrationTest`, but
  `app/schemas/` has no `1.json`, so v1→v2 cannot be reconstructed and v2→v3 is still
  unverified. Anyone still on v1 or v2 is migrating on hope.
- **Rotating the rule-signing key is a one-way door.** `tools/GenParserRuleKey.java`
  refuses to overwrite an existing private key for good reason: rotation invalidates
  every signature made with the old one, including a rule file a user has already
  sideloaded. A rotation has to ship with a re-signed asset in the same release.
- **The signing key is a development key.** It lives on one machine, unprotected by a
  passphrase. Anything wider than personal use needs it moved somewhere it cannot
  leak, because possession of it is possession of the parser.
- **`setInvalidatedByBiometricEnrollment(false)`** — deliberate. `true` is the
  textbook answer but destroys the ledger when a user enrols a new fingerprint,
  an action taken routinely with no warning that it is destructive.
- **Backup restore drops adjustment history.** Row IDs are reassigned on insert,
  so adjustments cannot be remapped. Corrected *values* survive; the audit trail
  does not.
- **Ledger header inconsistencies** — "TOTAL INFLOWS" counts refunds while the
  forecast does not; the donut total is gross debits while "SPENT SO FAR" is net
  of refunds.
- **The lock screen pending count includes non-bank SMS**, because the receiver
  deliberately does not parse while locked.
- **Non-transactional messages are dropped, not queued.** Phase 7 added a guard that
  discards OTPs and adverts outright rather than filing them under T2.3's review
  queue, on the grounds that a queue full of marketing SMS trains the user to ignore
  it. The risk is the mirror image: a genuine alert whose wording happens to contain
  "click to" or "offer valid" disappears with no trace. Nothing currently detects
  that, and the corpus is the place to catch it if it ever happens.
