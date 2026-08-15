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
| 6 | Analysis quality, trends, and query | done |
| 7 | Accuracy harness and release hygiene | done |
| 8 | Make the insights presentable: periods, cash position, time | done |
| 9a | Capture bills that are *owed*, instead of dropping them | done |
| 9b | The Bills tab: obligations, settlement, monthly movement | done |
| 9c | Reminders that leave the app — blocked on a design decision | open |

What remains is 9c, plus "Open questions and known gaps" below, which is where the
rest of the outstanding work lives.

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

**Phase 6** — the statistics now need evidence before they will claim anything.

*Recurring detection (F3.1).* The old detector compared the two most recent charges
and then let anything through with `|| merchantTxns.size >= 3`, so **any** merchant
billed three times became a subscription — three Swiggy orders made Swiggy a
recurring charge, and its "committed cost" was then added to the month-end forecast.
Recurrence now requires ≥3 charges, gaps whose median absolute deviation is under 25%
of the median gap, and amounts that cluster.

Those last two pull against each other, and resolving that was the interesting part
of this phase: **a price hike is itself a break in amount clustering**. Test the
amounts naively and F3.2 becomes unreachable, because every item it should flag is
disqualified from being recurring first. So charges are split into a current level —
the newest run of similar amounts — and whatever came before, and each side must be
internally tight. Random spending fails that (its leading run is one charge and the
tail is scattered); a subscription that stepped up once passes, with both levels
visible.

*Price hikes (F3.2).* Compared against the median of the prior level rather than the
single previous charge, and a hike must be **sustained** — at least two charges at the
new price. F3.2 is about silent increases, which are permanent by nature; one
expensive month is an anomaly, and F3.3 already reports those.

*Category trends (F3.6).* `compareCategories` returns per-category totals, absolute
delta and percentage change, largest movement first. Categories present in only one
window are still reported: a category you stopped spending in is a trend. A category
with no earlier spending gets a **null** percentage rather than infinity — "new
spending" is a different statement from "up 100%", and rendering infinity as a number
is how plausible-looking nonsense reaches the screen.

*Query (F4.1/F4.2).* `data/query/` holds a deterministic grammar over
metric / direction / category / merchant / period. No model, and there will not be
one — F4.2 is explicit that numbers are computed and injected. The parser **refuses
rather than guesses**: a question it cannot read returns null, because for a question
about money "I did not understand" beats a confident number answering something else.
It also echoes back what it understood, so a misread question is visible. Category
aliases only resolve to categories that exist in that database — reporting
"Health & Medical: ₹0" for a category the user never created is a wrong answer wearing
the costume of a right one.

*Tap-through (F4.4).* `RecurringItem`, `CategorySlice`, `CategoryTrend` and every
`QueryResult` carry their source transaction ids, and the analytics screen opens them.
An inference you cannot check is a number you have to take on faith.

Also fixed while in here: `computeAnalytics` read `transactionDao` directly rather
than the adjustment-folded ledger, so **every correction and every voided transaction
was invisible to analytics** — a transaction the user had removed still counted
towards the spend total, the donut and the forecast, while the ledger screen beside it
showed it gone.

**112 tests pass.** Release APK 11.32 MB.

**Migration safety** — the whole chain is now tested, not just the newest link.

`app/schemas/` starts at `2.json`: Room's schema export was switched on at v2, so no
v1 export has ever existed. v1 is therefore *reconstructed* — `2.json` with exactly
what `MIGRATION_1_2` adds taken back out — and lives in `src/test/resources/` rather
than `app/schemas/`, so that directory stays purely Room's own output. The file says
so in a `_provenance` field.

That reconstruction is partly circular and the limit is worth stating: derived from
the migration, it cannot detect a v1 column nobody remembers. What it does verify is
that the migration runs, that it produces a schema v3 can rebuild, and that data
survives — three of the four ways this breaks on a real install.

Coverage went from 4 tests to 12: v1→v2 (column defaults, duplicate collapse,
allowlist seeding), v2→v3 (in-place edits recovered as adjustments, all fifteen
columns across the table rebuild, `app_settings` created), v3→v4, v4→v5, and the
whole chain end to end — the only path a real v1 install actually takes.

The new tests were checked by breaking the migration on purpose. Removing the dedup
`DELETE`, and removing `DEFAULT 'POSTED'` from the `status` column, each failed
exactly the tests written for them. That second one is not cosmetic: without the
default, every pre-existing row reaches the v3 rebuild holding NULL in a NOT NULL
column, the INSERT fails, and the entire upgrade aborts on a real ledger.

**Own accounts (schema v5)** — the largest remaining source of wrong numbers, closed.

Moving ₹20,000 from savings to current is not spending, but every bank describes it
in the language of a payment, so the parser booked it as an outflow and the analytics
counted it. The bug is **symmetrical**, which the original note missed: the receiving
leg arrives as a credit and counted as *income*. A user who swept money between their
own accounts twice a month saw both figures overstated by the same amount, with net
cash flow — the one number that happened to be right — sitting between two wrong ones.

`own_accounts` records tails the user has confirmed. `isInternalTransfer` requires
`TxnType.TRANSFER` *and* a counterparty tail in that set; the counterparty is read
back out of the merchant label, which `SmsParserEngine.transferLabel` generates in a
fixed shape, so this parses a value this codebase wrote. Excluded at `postedDebits`,
the single chokepoint every debit-side figure passes through, and separately on the
income side of the forecast.

Three decisions worth recording:

- **Confirmed, not inferred.** Every `accountTail` in the ledger is already an account
  of the user's — banks do not text you about somebody else's. The set could have been
  derived with no UI at all. It is not, because `accountTail` is extracted by regex and
  takes the first match on a message naming two accounts; auto-marking would let one
  bad extraction silently delete real spending from the totals. The screen offers only
  tails actually seen, so nobody types a number from memory and mistypes it.
- **A blanket "ignore all transfers" rule was rejected.** It is one line, and it is
  wrong in the common Indian case: paying a person by UPI or account number is a real
  outflow that would have vanished from the totals. Only accounts the user marked are
  excluded, and a test pins that case.
- **The exclusion is shown, not silent.** `InternalTransferSummary` reports the count
  and both totals on the insights screen, with tap-through to the rows. Money
  disappearing from a total with no explanation is its own kind of wrong number — and
  if an account was marked by mistake, that line is how the user finds out.

`MIGRATION_4_5` is purely additive and arrives empty, so upgrading changes nobody's
figures until they mark something. A migration that silently restated a user's
spending history would be indistinguishable, from the outside, from a bug.

Two things the change would have quietly broken: `fullLocalWipe` now clears
`own_accounts` (F5.2 — they are account numbers the user told us about), and the
`.avault` backup carries them, because a restore that dropped them would count every
own-account transfer as spending again and disagree with the ledger it came from.

**129 tests pass.** Release APK 11.34 MB. Declared permissions unchanged:
`READ_SMS`, `RECEIVE_SMS`, `USE_BIOMETRIC`, `USE_FINGERPRINT`. No `INTERNET`.

**Phase 8** — the insights screen now answers the question people open it to ask.

The engine had been correct for two phases and the screen was throwing most of it
away. `MonthEndForecast` carried `totalIncomeSoFar` and `netCashFlowSoFar`;
`ForecastCard` rendered neither. Income appeared exactly once on the whole screen — as
the *unlabelled denominator* of a progress bar, which fell back to a hardcoded
half-full bar whenever income was zero, drawing a measurement of nothing. What led
the screen instead was a projected month-end **outflow** in the largest type
available: a derived estimate with a wide error bar, compared to nothing at all.
Earned, spent, left were absent, absent and absent.

*One correctness bug, not a presentation one.* `computeAnalytics` compared a
month-**to-date** total against a **whole** previous month. On the 3rd of the month
the category trends card reported that spending had collapsed in every single
category, and it was systematically wrong for roughly the first three weeks of every
month. `PeriodResolver` now truncates the comparison window to the same elapsed span
and clamps it so it cannot bleed past the end of its own month — 31 March has no
counterpart in February. Both windows are named on screen ("1–10 Aug against
1–10 Jul"), because a comparison you cannot see the terms of cannot be checked.

*Slicing.* Everything was hardwired to the current calendar month, and
`computeMonthEndForecast` re-derived "now" from `Calendar` internally, which also made
every figure in it untestable without freezing system time. `PeriodScope` offers
this week / this month / last month / 90 days; `computeAnalytics(scope, now)` and
`computeMonthEndForecast(transactions, now)` both take their clock as a parameter.
`currentMonthRange` and `previousMonthRange` were deleted rather than left beside
`PeriodResolver` — two implementations of month arithmetic is exactly how the two
sides of a comparison drift apart.

*A cash position, at last.* Income, spent and net across the top, each opening its own
rows. A forecast that includes **recurring income not yet received**: it projected
outflows only while calling itself a cash-position forecast, so anyone paid on the
28th spent three weeks looking at a card implying they were about to end the month
deeply in the red. `detectRecurringIncome` requires the same evidence the spending
side does — a one-off bonus is not a salary and must not be projected as one. And
**safe to spend per day**, which is the number that turns a projection into a
decision; every input already existed and none of it reached the screen.

*Time.* There was no time axis anywhere — a donut, three lists and a projected total,
all snapshots. Cumulative spend against the same elapsed span of the previous period
answers "am I ahead of last month", which no arrangement of totals can. Daily bars
with the typical day marked show the shape of the month; zero days are drawn as gaps
rather than skipped, or a quiet fortnight compresses out of the picture.

*Honesty about confidence.* Two days of spending stretched across thirty is
arithmetic, not a forecast, and the card presented day-2 output exactly like day-25
output. It now says which it is.

*Alert lists that resolve.* `detectAnomalies` and `detectDuplicates` ran over the
whole ledger with no dates on the rows, so a fourteen-month-old outlier sat at the top
forever, indistinguishable from yesterday. Detection still runs over all history —
a category needs it to know what normal costs — but `reportRange` scopes what is
*surfaced*, and every row is dated and tappable.

*Subscriptions say when they next hit*, the most useful fact about one, derived from
the established cadence. The section header states the monthly-equivalent run rate:
a quarterly ₹1,499 and a monthly ₹499 are not comparable until both are per month.

*One formatter.* Aggregates were written four ways inside a single card — `₹%.2f` for
the headline, `₹%.0f` with no grouping beside it, `%,.0f` in the card below — so
`₹43210` and `₹43,210` appeared inches apart looking like different figures. Indian
digit grouping throughout, paise only on individual transactions.

**148 tests pass**, up from 129, including seven pinning the window arithmetic that
the trends bug lived in.

**Phase 9a** — the app stops throwing away the only messages that say what is due.

`SmsParserEngine.DUE_REMINDER` existed to stop a bill that is *owed* being booked as a
bill that was *paid*, and it was right: "Total Amount Due of Rs 2,783.00 … payable by
06-Aug-26" had been entering the ledger as ₹2,783 of spend, and the variant that adds
"Payments are credited within 2 working days" as ₹2,783 of **income**. Its remedy was to
`return ParseResult()` — no transaction, no review-queue entry, no trace. So the twelve
due notices in the corpus, the only messages in the whole inbox that carry a deadline,
were the ones guaranteed to be discarded.

Notices now land in `bill_notices` (schema v6). The guard itself is unchanged: a due
reminder still produces `parsedTransaction = null`, and a test asserts exactly that
alongside the capture, because the value of this feature is nowhere near the cost of
reopening that defect.

*Why a table and not a `txnType`.* Every aggregate in `FinanceAnalyticsEngine` runs
through `postedDebits`/`postedCredits`, both of which mean "money moved". A statement
balance entering there re-creates the original bug, and worse: a card statement's
purchases are already in the ledger — card senders are allowlisted by default — so
counting the statement as well counts the same rupees twice. This is the reasoning that
produced `TxnType.CARD_PAYMENT`, applied one level up.

*Identity, mirroring the transactions table.* `noticeHash` (sender + body + day, unique)
collapses the identical re-sends a biller makes three or four times a cycle. `cycleKey`
(biller + tail + due day, indexed) groups differently-worded notices into one obligation.
That is the same split `hash`/`txnHash` already makes. Keying the cycle on the due day
rather than the amount is deliberate: a reminder re-sent after a part payment quotes a
smaller total, and that is the same bill in a new state, not a second bill.

*Dates were the new risk.* Nothing in this codebase had ever read a date — a
transaction's timestamp comes from the SMS envelope, which is unambiguous and always
present. `BillDateParser` fixes three rules and says why: `dd-mm` never `mm-dd` (fixed by
region, overridden only when a component above 12 proves otherwise); a day component is
required, so "statement for Aug 2026" cannot become a deadline of 1 August for a bill
payable on the 20th; and a missing year is resolved against the **message** timestamp,
not the current clock, so "due on 05-Jan" sent on 20 December means next January and a
re-parse of old messages stays stable. `Calendar.isLenient = false`, so 31 February is
rejected rather than silently becoming 3 March.

*Two amounts, one bill.* Reading "Minimum Amount Due of Rs 140.00" as the bill when the
total is ₹2,783 understates the obligation twentyfold, which is the worst number this
feature could put on screen. Labelled totals win; the unlabelled fallback explicitly
skips whatever the minimum-due pattern already claimed.

*One ordering change with a real consequence.* `NON_TRANSACTIONAL` was split into
`OTP_MARKER` and `PROMO_MARKER`, and the due-reminder check now sits between them. OTP
wording is exclusive to credentials and its verdict stays final. Marketing wording is
not: utility and telecom billers routinely append a payment link to a genuine notice, and
tested first, "click to" threw the whole bill away. Tested after, an advert that quotes
no amount due and no deadline never satisfies the reminder pattern anyway — the PhonePe
cashback promo in the corpus is still dropped, and a test pins it.

A notice naming neither a sum nor a date is still discarded. "Your statement is ready.
Login to view" is a notification about a notification.

**Phase 9b** — the tab, and the three states it is willing to report.

*Settlement is three-valued, and the third value is the point.* A bill settled by
autopay, by netbanking, or from a bank whose alerts are not allowlisted produces no SMS
at all, so the ledger genuinely cannot tell "not paid" from "paid invisibly". The chip
reads **"No payment seen"**, never "Unpaid" or "Overdue" — the first is an observation
about the ledger, the second an accusation about the user, and being wrong about the
second costs more trust than any wrong total, because a total can be checked.

*Every candidate payment needs an identity link.* Matching on amount alone was tried and
rejected: an unrelated ₹2,783 purchase in the same fortnight would mark a card bill
settled, and a false "paid" is discovered in the form of a late fee. A candidate must
match by transaction type (`CARD_PAYMENT` against a card notice), account tail, or payee
name. The type arm is load-bearing — a card bill names only one account whichever leg
arrives, so the tail can never match on both sides. Amount tolerance is 2%, tighter than
the spending side's 15%, because a bill names an exact figure and a looser band starts
calling a minimum-due payment a settled bill.

*Only a full match retires a bill.* "Part paid" stays in the due list rather than moving
to the settled one. The commonest way to reach that state is paying the minimum due on a
card, which leaves the balance owed and accruing interest — filing it as settled would
hide the bill that most needs looking at.

*A trend needs three cycles.* Two bills make one comparison, and one comparison cannot
tell a real rise from the ordinary variation a metered bill has every month — an
electricity bill is higher in May than April because of the weather. Below three cycles
the card shows the figures and declines to call them a direction. Same evidence bar as
`MIN_CHARGES_FOR_RECURRING`, for the same reason.

*Trends run over obligations, not notices.* Four reminders about one ₹12,340 statement
are one bill; trending on notices would report a fourfold rise every time a biller sent a
chaser. A test pins that.

*Monthly totals are bucketed by due date, not issue date.* A statement generated on
28 July and payable on 15 August is an August outgoing.

*What came free.* "Expected auto-debits" is `detectRecurringAndPriceHikes` +
`nextExpectedTimestamp`, both built in Phase 6 and previously rendering as one line of
text. Subscriptions, SIPs and autopay announce nothing, so inference is the only source
there is — the section says so on screen rather than presenting a projected cadence and a
biller's stated deadline as the same kind of fact.

`fullLocalWipe` clears `bill_notices` (they name accounts, billers and sums owed) and the
`.avault` backup carries them — dropping them would lose the only record of what a
settled bill *was*, which is the history the trends are computed from. Bills is a fifth
bottom-nav destination, which is Material 3's maximum; anything further has to displace
something.

**261 tests pass**, up from 241. Parser accuracy unchanged at **0.9993**. Release APK
**11.94 MB** against the 30 MB budget. Declared permissions unchanged: `READ_SMS`,
`RECEIVE_SMS`, `USE_BIOMETRIC`, `USE_FINGERPRINT`. No `INTERNET`, no notification
permission, no background work — see 9c under "Open questions".

---

---

## Open questions and known gaps

These are decisions or debts not owned by any phase above.

- **Phase 9c is blocked on one decision, and it is architectural rather than cosmetic.**
  The database key is auth-bound with a 30-second validity window, so a background
  worker *physically cannot* read the ledger — the Keystore hardware refuses. This is
  the same constraint `SmsReceiver` is built around. A conventional "₹12,340 due in 3
  days" push therefore requires the amount, biller and date to live outside SQLCipher,
  in SharedPreferences or WorkManager's own unencrypted database, and then to be
  rendered on a lock screen. That inverts the app's premise for a convenience.
  The ladder, of which only the first rung is built:
  1. **At-unlock digest** — the Bills tab and a nav badge. Zero new permissions, zero
     leakage, and for an app opened regularly it covers most of the value. *Done.*
  2. **Content-free scheduled nudge** — `POST_NOTIFICATIONS` only. At unlock, when the
     database is readable, compute the reminder instants and schedule inexact
     WorkManager jobs with an **empty payload**; the notification reads "A payment is
     due soon — open Arth Vault". What reaches disk and lock screen is a bare
     timestamp. *Not built — needs the decision.*
  3. **Rich notifications.** Rejected: plaintext financial data at rest outside the
     vault. `SCHEDULE_EXACT_ALARM` is separately out — a restricted Play permission
     whose allowed-use policy bill reminders do not clearly satisfy, and inexact
     scheduling is fine three days ahead.
- **Only credit cards are evidenced.** All twelve due notices in the corpus are card
  statements. `BillKind.UTILITY`, `TELECOM`, `INSURANCE` and `LOAN` are implemented
  because the patterns are cheap and the alternative is filing a BESCOM bill under
  "OTHER", but no real electricity, postpaid or broadband SMS has ever been tested
  against them — the one utility case in `BillNoticeCaptureTest` is written from the
  shape such messages are expected to take, which is not the same as evidence. This is
  the binding constraint on the bill analytics being worth anything, and the fix is
  collection, not code: add real messages to `sms_corpus.jsonl` as they arrive.
- **There is no accuracy gate on bill extraction.** `ParserAccuracyTest` scores the
  transaction fields per field and fails the build below 0.95. Bills have unit tests but
  no equivalent gate, so a regression in due-date extraction would pass CI. It should
  get one once the corpus has enough non-card notices to make the score mean something.
- **A bill paid in part, then in full, reads as "Part paid".** The reconciler reports the
  strongest single match rather than summing candidates. Summing is the obvious fix and
  was not taken: two payments that happen to total the bill are not proof it was cleared,
  and the failure mode of guessing wrong here is a bill the user thinks is settled.

- **Merchant extraction is the parser's weakest field, and the corpus is the only
  thing that says so.** At 0.9993 overall, merchant sits at 0.994 while every other
  field is 1.000 — and before Phase 7 it was 0.483 while the aggregate still looked
  respectable. Field-level scoring is what makes that visible; keep it that way, and
  extend the corpus with any real message the parser gets wrong rather than patching
  the regex and moving on.
- **Own-account transfers are only excluded once the user marks the account.**
  Resolved in principle (see "Own accounts" below), but the exclusion depends on the
  parser having written the counterparty into the merchant label as
  `Transfer to A/c NNN`. A bank that phrases a transfer without naming the
  destination account produces no counterparty tail, and that transfer still counts
  as spending with nothing on screen to say so. The corpus is where to catch it.
- **The recurring thresholds are asserted, not calibrated.** `MAX_GAP_DISPERSION`,
  `AMOUNT_TOLERANCE` and `MIN_CLUSTERED_FRACTION` come from the spec and from
  judgement, and the tests fix the behaviour at those values against invented data.
  Whether 0.25 is the right dispersion for real Indian billing — where a monthly
  charge routinely lands 28 to 33 days apart, and annual renewals drift — is
  unanswered until there is a year of real ledger to check it against. §6's success
  criterion ("surfaces at least one forgotten or increased subscription") is the test
  that matters, and it takes months of use, not a unit test.
- **The query grammar's vocabulary is narrow by choice.** It handles metric,
  direction, category, merchant and period, and refuses everything else rather than
  guessing. Widening it is safe; making it guess is not.
- **The v1 schema in the migration tests is a reconstruction, not an export.**
  Room's schema export was switched on at v2, so `1.json` never existed.
  `src/test/resources/schema-v1-reconstructed.json` is derived from the real `2.json`
  by removing exactly what `MIGRATION_1_2` adds, which is partly circular: it cannot
  detect a v1 column nobody remembers. It does verify the migration runs, produces a
  schema v3 can rebuild, and preserves data. Kept out of `app/schemas/` so that
  directory stays purely Room's own output.
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
- **The donut total and the "SPENT" figure can still disagree.**
  `computePeriodSummary` nets refunds off spending; `computeCategoryBreakdown` sums
  gross debits. Phase 8 put both on the same screen, inches apart, so the gap is now
  visible where before it was merely present. Netting refunds per category is the
  obvious fix and was deliberately not taken: a category can hold more refunds than
  spending in a short window, and a negative slice is undrawable, so flooring at zero
  would break the identity again in exactly the case the fix was for. It needs a
  decision about what a refunded category *means* on a chart, not a patch.
- **The lock screen pending count includes non-bank SMS**, because the receiver
  deliberately does not parse while locked.
- **Non-transactional messages are dropped, not queued.** Phase 7 added a guard that
  discards OTPs and adverts outright rather than filing them under T2.3's review
  queue, on the grounds that a queue full of marketing SMS trains the user to ignore
  it. The risk is the mirror image: a genuine alert whose wording happens to contain
  "click to" or "offer valid" disappears with no trace. Nothing currently detects
  that, and the corpus is the place to catch it if it ever happens.
