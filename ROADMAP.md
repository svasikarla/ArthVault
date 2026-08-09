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
| 5 | Parser rules as versioned, signed data files | open |
| 6 | Analysis quality, trends, and query | open |
| 7 | Accuracy harness and release hygiene | open |

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

Release APK measured at **21.2 MB**. Declared permissions: `READ_SMS`,
`RECEIVE_SMS`, `USE_BIOMETRIC`, `USE_FINGERPRINT`. No `INTERNET`.

---

## Phase 5 — Parser rules as versioned, signed data files
*Spec: F1.1 · T2.2 · §7. Est. ~3 days.*

- **5.1** Extract rules to `app/src/main/assets/parser_rules_v1.json` with
  `schemaVersion` / `rulesVersion` / `issuedAt` / `rules[]` / `signature`
  (base64 ECDSA-P256 over the canonical rules array). Verify with
  `java.security.Signature` against a public key in `res/raw` — entirely offline,
  no T1.1 conflict. On signature mismatch keep the previously loaded rules rather
  than falling back to none.
- **5.2** Load on every launch, not only on database creation. Compare the asset's
  `rulesVersion` against a value stored in the DB; if newer, upsert system rules
  keyed on a stable `ruleId`. Never touch user-authored rules.
- **5.3** Accept a sideloaded rule file via `OpenDocument()`, showing version and
  signature status before the user confirms. Same signature check, so a sideloaded
  file is exactly as trustworthy as a bundled one.

## Phase 6 — Analysis quality, trends, and query
*Spec: F3.1 · F3.2 · F3.6 · F4.1 · F4.4. Est. ~1 week.*

The statistical work is sound in method but thin in evidence — most of it looks at
only the two most recent transactions.

- **6.1** Real periodicity and amount clustering
  (`FinanceAnalyticsEngine.kt:39-78`). Today the detector compares
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

## Phase 7 — Accuracy harness and release hygiene
*Spec: T2.4 · T5.4 · §6. Est. ~3 days.*

- **7.1** Parser accuracy corpus — `app/src/test/resources/sms_corpus.jsonl` plus
  `ParserAccuracyTest.kt`. One JSON object per line: sender and body, plus expected
  amount, direction, merchant, account tail, channel, balance, status and type.
  Redact real account numbers. Cover every allowlisted sender, and deliberately
  include declined transactions, refunds, EMI notices, balance-only alerts and OTP
  messages that must *not* parse. Score per-field, not per-message, and fail the
  build below 0.95 so T2.4 becomes a gate rather than an aspiration.
  **Target: ≥200 messages.**
- **7.2** Enable `isMinifyEnabled` and resource shrinking for release, keep Room
  and SQLCipher rules in `proguard-rules.pro`, re-measure. (Currently 21.2 MB
  unminified, against a 30 MB budget.)
- **7.3** Remaining template debris:
  - `applicationId = "com.aistudio.vaultledger.pfxq"` — generated identity.
    Renaming forces a reinstall, so decide *before* real use, not after.
  - `namespace = "com.example"` and the `com.example` package root.
  - The release `signingConfig` silently falls back to `debug.keystore` when
    `KEYSTORE_PATH` is unset. Make it fail loudly instead.

---

## Open questions and known gaps

These are decisions or debts not owned by any phase above.

- **Phase 7.1 should be pulled forward.** It is filed under "release hygiene", but
  a live ICICI transfer alert on 2026-08-10 was booked as *₹635 credited* instead
  of *₹45,425 debited* — the amount had been read from the account-tail capture
  group, and the direction from a whole-body substring search that a two-leg
  transfer defeats. Both failures were silent: a plausible row, no error, no
  unparsed entry. Fixed, with 11 regression tests in `TransferSmsParsingTest.kt`,
  but the class of bug is only caught by a corpus. **A parser without a measured
  corpus should not be trusted with anyone's money.**
- **Own-account transfers count as spending.** `FinanceAnalyticsEngine` does not
  exclude `TxnType.TRANSFER` from outflows, so moving money between your own
  accounts inflates the spend total, the donut and the forecast. Needs a product
  decision: net out transfers when both legs are visible, or let the user mark
  accounts as their own.
- **No instrumented migration test.** `room-testing` is a dependency but no
  `MigrationTestHelper` test exists; v1→v2→v3 has only been verified by hand.
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
- **The project is not under version control.** `d:\myfinmythri` is not a git
  repository, so none of the above work is versioned or revertible.
- **`README.md` is out of date**: it states the APK declares "exactly `READ_SMS`
  and `RECEIVE_SMS`". Phase 4 added `USE_BIOMETRIC` and `USE_FINGERPRINT`.
