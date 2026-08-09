# Local-First Personal Finance Ledger — Spec v0.1

## 1. Problem Statement

Bank transaction data arrives on the phone via SMS but is unusable — unstructured, unsearchable, un-aggregated. Every existing app that solves this requires uploading financial data to a third-party server, where it is used for lending, marketing, or resale. There is no tool that turns SMS into a usable financial record without the data leaving the device.

**Solve:** reconstruct a complete personal financial ledger from on-device signals, with zero network egress.

---

## 2. Personas

| | Primary | Secondary |
|---|---|---|
| **Who** | Technical professional, privacy-conscious | Salaried user, non-technical |
| **Device** | Flagship Android, 8GB+ | Mid-range, 4–6GB |
| **Behavior** | Refuses PhonePe/CRED-class apps | Uses them, uneasy about it |
| **Needs** | Full record, exportable, verifiable privacy | Recurring charges, month-end forecast |
| **Phase** | v1 target (self) | v2 target (if pursued) |

---

## 3. Non-Goals

- Investment advice or product recommendations
- Bank account linking, Account Aggregator, or any server integration
- Bill payment, transfers, or any transactional capability
- Multi-user, sync, or cloud backup
- Budgeting/goal-setting gamification

---

## 4. Functional Requirements

### F1 — Ingestion
- F1.1 Read SMS from bank/financial senders only; filter by sender ID allowlist
- F1.2 Parse debit, credit, UPI, card, ATM, EMI, refund, failed-txn events
- F1.3 Extract: amount, direction, date/time, merchant/counterparty, account tail, channel, balance (when present)
- F1.4 Notification listener as secondary source (bank push alerts)
- F1.5 Manual entry + CSV import for cash and gaps
- F1.6 Deduplicate across SMS + notification for the same transaction

### F2 — Categorization
- F2.1 Auto-assign category from merchant string
- F2.2 User override; override persists as a rule for that merchant
- F2.3 Custom category creation
- F2.4 Bulk re-categorize by merchant

### F3 — Analysis
- F3.1 Recurring charge detection (periodicity + amount clustering)
- F3.2 Silent price-hike flagging on recurring items
- F3.3 Anomaly detection — per-category robust z-score / IQR
- F3.4 Duplicate charge detection (same merchant + amount within window)
- F3.5 Month-end cash-position forecast from committed outflows + trend
- F3.6 Category trend comparison across periods

### F4 — Query & Narration
- F4.1 Natural-language query over ledger ("spend on fuel last quarter")
- F4.2 All numeric values computed deterministically and injected; LLM formats prose only
- F4.3 LLM layer optional — full app functions without it
- F4.4 Every insight traceable to source transactions (tap-through)

### F5 — Data Ownership
- F5.1 CSV/JSON export of all data
- F5.2 Full local wipe
- F5.3 Encrypted local backup to user-chosen file location
- F5.4 No telemetry, no analytics, no crash reporting

---

## 5. Technical Requirements

### T1 — Architecture Constraints
- T1.1 `android.permission.INTERNET` **not declared** in manifest — egress structurally impossible
- T1.2 Ingestion behind a source-agnostic interface (SMS / notification / import swappable)
- T1.3 App fully functional with LLM module absent or unsupported

### T2 — Parsing
- T2.1 Deterministic rules/regex engine — **no LLM in the parse path**
- T2.2 Parser rules as versioned, signed, updatable data files (not hardcoded)
- T2.3 Unparsed messages queued for review, never silently dropped
- T2.4 Target ≥95% extraction accuracy on covered senders

### T3 — Storage
- T3.1 Room / SQLite with SQLCipher encryption
- T3.2 Key in Android Keystore, biometric-gated unlock
- T3.3 Immutable transaction records; corrections as append-only adjustments
- T3.4 Schema versioned with forward migration

### T4 — ML / Inference
- T4.1 Merchant categorization: small on-device embedding model + kNN over user corrections
- T4.2 Recurring/anomaly/forecast: statistical methods, not learned models (n is too small)
- T4.3 Narration: quantized SLM (~1B, Q4) via MediaPipe or llama.cpp
- T4.4 Model assets versioned independently of app; graceful degradation if unavailable
- T4.5 Inference deterministic and reproducible across devices for a given model version

### T5 — Platform
- T5.1 Min SDK 26; target latest
- T5.2 Kotlin + Jetpack Compose
- T5.3 All processing on background workers; no ANR on bulk import
- T5.4 Baseline APK (no LLM) target <30MB; LLM model as optional download-free asset or sideload

### T6 — Permissions
- T6.1 `READ_SMS`, `RECEIVE_SMS` — Play declaration required if published
- T6.2 `BIND_NOTIFICATION_LISTENER_SERVICE` — user-granted
- T6.3 No location, no contacts, no storage-wide access

---

## 6. Success Criteria (v1, personal use)

- Ledger reconstructs ≥95% of transactions across the user's own banks, unaided
- Recurring detection surfaces at least one forgotten or increased subscription
- Month-end forecast within ±10% for three consecutive months
- Zero network sockets opened — verifiable via manifest and runtime inspection

---

## 7. Open Decisions

| Decision | Options | Note |
|---|---|---|
| LLM narration in v1 | Include / defer | Defer recommended — prove ledger first |
| Parser rule distribution | Bundled / sideloaded file | Sideloaded keeps app updatable without rebuild |
| Notification listener in v1 | Yes / v2 | SMS alone may suffice initially |
| Publish path | Personal only / paid app later | Decide after 3 months of self-use |
