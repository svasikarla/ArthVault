package com.arthvault

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import com.arthvault.data.analytics.AnalyticsPeriod
import com.arthvault.data.analytics.CategorySlice
import com.arthvault.data.analytics.DayBucket
import com.arthvault.data.analytics.MonthEndForecast
import com.arthvault.data.analytics.PeriodScope
import com.arthvault.data.analytics.PeriodSummary
import com.arthvault.data.analytics.RecurringItem
import com.arthvault.data.local.entity.TransactionEntity
import androidx.compose.material3.Text
import com.arthvault.data.local.DefaultSeedData
import com.arthvault.data.query.QueryParser
import com.arthvault.ui.components.AnalyticsSkeleton
import com.arthvault.ui.components.BarAction
import com.arthvault.ui.components.EmptyState
import com.arthvault.ui.components.TooltipIconButton
import com.arthvault.ui.components.LocalSnackbar
import com.arthvault.ui.components.RowSkeleton
import com.arthvault.ui.components.SnackbarNotifier
import com.arthvault.ui.components.colorIndexFor
import com.arthvault.ui.components.initialsOf
import com.arthvault.ui.screens.CashPositionCard
import com.arthvault.ui.screens.CategoryBreakdownCard
import com.arthvault.ui.screens.DailySpendChart
import com.arthvault.ui.screens.PeriodSelector
import com.arthvault.ui.screens.QUERY_EXAMPLES
import com.arthvault.ui.screens.RecurringItemCard
import com.arthvault.ui.screens.SpendPaceChart
import com.arthvault.ui.screens.TransactionCard
import com.arthvault.ui.format.formatDayHeader
import com.arthvault.ui.format.formatDirectedMoney
import com.arthvault.ui.format.formatMoney
import com.arthvault.ui.format.formatMoneyPrecise
import com.arthvault.ui.format.formatSignedMoney
import com.arthvault.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Renders the design system and every stateless surface built on it.
 *
 * The rest of the suite covers parsing, crypto and analytics arithmetic; nothing
 * rendered a composable, so a UI change was verified by the compiler alone. A type
 * scale that fails to resolve a bundled font, a `CompositionLocal` read with no
 * provider above it, or a `Modifier` chain that measures to nothing all compile
 * perfectly and crash on the first frame.
 *
 * Each case runs in light and dark, and again at 2× font scale — the accessibility
 * setting most likely to break a row of three money columns.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DesignSystemRenderTest {

    @get:Rule
    val compose = createComposeRule()

    // --- fixtures ----------------------------------------------------------

    private val period = AnalyticsPeriod(
        scope = PeriodScope.THIS_MONTH,
        range = 0L..30L,
        label = "This month",
        comparison = 0L..30L,
        comparisonLabel = "Last month",
        isOpen = true,
        elapsedDays = 12,
        totalDays = 31,
    )

    private val summary = PeriodSummary(
        income = 128_450.0,
        spent = 74_310.0,
        refunds = 0.0,
        net = 54_140.0,
        spendTransactionIds = listOf(1L, 2L),
        incomeTransactionIds = listOf(3L),
    )

    private val forecast = MonthEndForecast(
        totalSpentSoFar = 74_310.0,
        totalIncomeSoFar = 128_450.0,
        netCashFlowSoFar = 54_140.0,
        projectedSpentMonthEnd = 191_800.0,
        projectedRemainingOutflows = 117_490.0,
        committedRecurringTotal = 12_400.0,
        dailySpendVelocity = 6_192.0,
        daysRemainingInMonth = 19,
        expectedIncomeRemaining = 4_000.0,
        projectedIncomeMonthEnd = 132_450.0,
        projectedNetMonthEnd = -59_350.0,
        safeToSpendPerDay = 2_849.0,
        daysElapsedInMonth = 12,
    )

    private val transaction = TransactionEntity(
        id = 1L,
        amount = 1_234.56,
        direction = "DEBIT",
        timestamp = 1_754_000_000_000L,
        sender = "AD-HDFCBK-S",
        merchant = "Swiggy",
        accountTail = "4821",
        channel = "UPI",
        category = "Food & Dining",
        rawMessage = "Rs 1234.56 debited from a/c XX4821 to SWIGGY via UPI",
        balanceAfter = 42_100.0,
        hash = "test-hash",
    )

    private val recurring = RecurringItem(
        merchant = "Netflix",
        category = "Entertainment",
        currentAmount = 649.0,
        previousAmount = 499.0,
        isPriceHike = true,
        priceHikePercentage = 30.1,
        frequencyDays = 30,
        lastChargedTimestamp = 1_754_000_000_000L,
        chargeCount = 4,
        transactionIds = listOf(1L, 2L, 3L),
    )

    private val slices = listOf(
        CategorySlice("Food & Dining", 24_300.0, 0.42f, listOf(1L)),
        CategorySlice("Transport", 12_100.0, 0.21f, listOf(2L)),
        CategorySlice("Bills", 9_800.0, 0.17f, listOf(3L)),
        CategorySlice("Shopping", 6_400.0, 0.11f, listOf(4L)),
        CategorySlice("Health", 3_100.0, 0.05f, listOf(5L)),
        CategorySlice("Other", 2_300.0, 0.04f, listOf(6L)),
        // A seventh slice proves the ramp wraps rather than indexing out of bounds.
        CategorySlice("Fuel", 900.0, 0.01f, listOf(7L)),
    )

    private val buckets = (0 until 30).map { day ->
        DayBucket(
            dayStart = 1_752_000_000_000L + day * 86_400_000L,
            dayIndex = day,
            spent = if (day % 4 == 0) 0.0 else (500 + day * 120).toDouble(),
            income = 0.0,
        )
    }

    // --- harness -----------------------------------------------------------

    private fun render(
        dark: Boolean = false,
        fontScale: Float = 1f,
        content: @Composable () -> Unit,
    ) {
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(base.density, fontScale)
            ) {
                MyApplicationTheme(darkTheme = dark) {
                    Column { content() }
                }
            }
        }
        compose.waitForIdle()
    }

    /** Every surface, in one composition. */
    @Composable
    private fun AllSurfaces() {
        PeriodSelector(selected = PeriodScope.THIS_MONTH, onSelect = {})
        CashPositionCard(
            period = period,
            summary = summary,
            comparison = summary,
            forecast = forecast,
            onShowIncome = {},
            onShowSpend = {},
        )
        SpendPaceChart(
            period = period,
            current = listOf(0.0, 1200.0, 4300.0, 9100.0, 14_000.0),
            previous = listOf(0.0, 900.0, 3800.0, 8200.0, 16_500.0),
        )
        DailySpendChart(buckets = buckets)
        CategoryBreakdownCard(period = period, slices = slices, onSliceClick = {})
        RecurringItemCard(item = recurring, onClick = {})
        TransactionCard(transaction = transaction, onClick = {})
        EmptyState(
            icon = Icons.Default.ReceiptLong,
            title = "No transactions yet",
            message = "Scan your SMS inbox from the top bar.",
            actionLabel = "Add a transaction",
            onAction = {},
        )
        AnalyticsSkeleton()
        RowSkeleton()
    }

    // --- cases -------------------------------------------------------------

    @Test
    fun `every surface composes in light theme`() {
        render(dark = false) { AllSurfaces() }
        compose.onNodeWithText("INCOME").assertIsDisplayed()
    }

    @Test
    fun `every surface composes in dark theme`() {
        render(dark = true) { AllSurfaces() }
        compose.onNodeWithText("SPENT").assertIsDisplayed()
    }

    /**
     * The case the review called out: three `weight(1f)` money columns rendering
     * `₹1,28,450` in bold. Above 1.3× the row is supposed to become a stack.
     */
    @Test
    fun `every surface composes at 2x font scale`() {
        render(dark = false, fontScale = 2f) { AllSurfaces() }
        compose.onNodeWithText("NET").assertIsDisplayed()
    }

    /**
     * The grouping itself, asserted on the formatter rather than through a rendered
     * string. ₹1,28,450 — not ₹128,450. The Ledger screen used to produce the second
     * form with a raw `"₹%.2f".format(...)` while Analytics produced the first.
     */
    @Test
    fun `money uses Indian digit grouping`() {
        assertEquals("₹0", formatMoney(0.0))
        assertEquals("₹450", formatMoney(450.0))
        assertEquals("₹9,999", formatMoney(9_999.0))
        assertEquals("₹1,28,450", formatMoney(128_450.0))
        assertEquals("₹12,00,000", formatMoney(1_200_000.0))
        assertEquals("₹1,23,45,678", formatMoney(12_345_678.0))
        assertEquals("₹1,234.56", formatMoneyPrecise(1_234.56))
        assertEquals("₹1,28,450.00", formatMoneyPrecise(128_450.0))
        assertEquals("−₹1,28,450", formatSignedMoney(-128_450.0))
        assertEquals("+₹1,28,450", formatSignedMoney(128_450.0))
        assertEquals("−₹1,234.56", formatDirectedMoney(1_234.56, isCredit = false))
        assertEquals("+₹1,234.56", formatDirectedMoney(1_234.56, isCredit = true))
    }

    /**
     * And that the cards render exactly what that formatter returns, rather than
     * formatting money themselves.
     *
     * assertExists rather than assertIsDisplayed: every surface is stacked in one
     * Column taller than the test viewport, so anything past the first screenful
     * lays out off-screen. What matters here is that the string was composed.
     */
    @Test
    fun `cards render money through the shared formatter`() {
        render { AllSurfaces() }
        compose.onNodeWithText(formatMoney(summary.income), substring = true).assertExists()
        compose.onNodeWithText(formatMoney(recurring.currentAmount), substring = true).assertExists()
    }

    /**
     * Regression: reading [LocalSnackbar] with no [VaultScaffold] above it must not
     * crash.
     *
     * The Vault screen's "Delete all data" dialog is a sibling of the scaffold rather
     * than a child, so it read the local outside its provider. With a `staticCompositionLocalOf`
     * that defaulted to `error(...)`, tapping the button threw immediately. Both the
     * call site and the default are fixed; this pins the second one, because it is the
     * part that keeps any future stray read from taking the app down.
     */
    @Test
    fun `a snackbar read outside its provider degrades instead of crashing`() {
        var notifier: SnackbarNotifier? = null
        render {
            notifier = LocalSnackbar.current
            Text("no scaffold above this")
        }
        // Reading it is safe...
        assertNotNull(notifier)
        // ...and so is using it. The message is dropped and logged, not thrown.
        notifier!!.show("this goes nowhere")
        compose.onNodeWithText("no scaffold above this").assertExists()
    }

    /**
     * Top-bar actions carry a visible label, not just a glyph.
     *
     * Moving "Scan SMS inbox" out of the header card and into the app bar was right —
     * it had been one of three controls competing to be the screen's primary action —
     * but it went in icon-only, which left a scanner glyph and a sparkle glyph with
     * their meaning available only to TalkBack. An icon alone cannot carry an action
     * that fills the whole app with data.
     */
    @Test
    fun `bar actions show their label as text`() {
        render {
            BarAction(label = "Scan SMS", icon = Icons.Default.QrCodeScanner, onClick = {})
            BarAction(label = "Recalculate", icon = Icons.Default.Refresh, onClick = {})
        }
        compose.onNodeWithText("Scan SMS").assertIsDisplayed()
        compose.onNodeWithText("Recalculate").assertIsDisplayed()
    }

    /**
     * The repeated row actions stay icon-only — a label on every row of the feed would
     * drown the merchant names — but the label reaches a screen reader, and long-press
     * reveals it for everyone else.
     */
    @Test
    fun `repeated row actions name themselves to a screen reader`() {
        render {
            TooltipIconButton(label = "Change category", icon = Icons.Default.Edit, onClick = {})
            TooltipIconButton(label = "Void transaction", icon = Icons.Default.Delete, onClick = {})
        }
        compose.onNode(hasContentDescription("Change category")).assertExists()
        compose.onNode(hasContentDescription("Void transaction")).assertExists()
    }

    // --- the feed's new furniture -------------------------------------------

    /**
     * The avatar has to be a stable function of the category, not of insertion order or
     * of anything the JVM is free to vary. Two SWIGGY rows a month apart in the same
     * category must be the same colour, or the tint is noise rather than information.
     */
    @Test
    fun `a category always maps to the same slot in the ramp`() {
        repeat(3) {
            assertEquals(colorIndexFor("Food & Dining", 6), colorIndexFor("Food & Dining", 6))
        }
        // Negative hash codes are the case a plain `%` gets wrong: it returns a
        // negative index and the ramp lookup throws on the first row that hits one.
        listOf("Food & Dining", "Transport & Fuel", "Grocery", "Shopping", "Other / Misc", "")
            .forEach { category ->
                val index = colorIndexFor(category, 6)
                assertTrue("index out of ramp for '$category': $index", index in 0..5)
            }
    }

    @Test
    fun `initials come from the merchant, however it is punctuated`() {
        assertEquals("SW", initialsOf("SWIGGY"))
        assertEquals("AI", initialsOf("AMAZON INDIA"))
        assertEquals("IB", initialsOf("ICICI Bank Credit"))
        // Parsed merchants arrive delimited by whatever the bank used.
        assertEquals("BG", initialsOf("BLINKIT-GROCERY"))
        assertEquals("PU", initialsOf("PAYTM*UBER"))
        // Nothing to take initials from must still render something.
        assertEquals("?", initialsOf(""))
        assertEquals("?", initialsOf("—"))
    }

    /**
     * `Today` and `Yesterday` are the whole point of the header; a formatter that
     * silently fell back to the date for both would look fine and say nothing.
     */
    @Test
    fun `day headers name the recent days and date the rest`() {
        val now = 1_754_000_000_000L // 2025-08-01, mid-afternoon IST
        val day = 86_400_000L
        assertEquals("Today", formatDayHeader(now, now))
        assertEquals("Yesterday", formatDayHeader(now - day, now))
        assertNotEquals("Today", formatDayHeader(now - 5 * day, now))
        assertNotEquals("Yesterday", formatDayHeader(now - 5 * day, now))
        // A year old: the year has to appear, or "8 August" is ambiguous.
        assertTrue(formatDayHeader(now - 400 * day, now).any { it.isDigit() })
    }

    @Test
    fun `the feed row composes with an avatar instead of two icon buttons`() {
        render { TransactionCard(transaction = transaction, onClick = {}) }
        compose.onNodeWithText("Swiggy").assertIsDisplayed()
        compose.onNodeWithText(initialsOf("Swiggy")).assertIsDisplayed()
        // The destructive action is no longer one thumb-slip from a scrolling list.
        compose.onNode(hasContentDescription("Void transaction")).assertDoesNotExist()
    }

    /**
     * The example chips promise a question the parser will accept. A chip that offered
     * a phrasing the grammar refuses would teach the user the wrong shape and answer
     * with "that question could not be read" — the exact failure they exist to prevent.
     */
    @Test
    fun `every suggested question is one the parser can read`() {
        val parser = QueryParser(DefaultSeedData.categories.map { it.name })
        val now = System.currentTimeMillis()
        QUERY_EXAMPLES.forEach { example ->
            assertNotNull("the parser refuses its own suggestion: '$example'", parser.parse(example, now))
        }
    }

    @Test
    fun `charts carry a description for screen readers`() {
        render { AllSurfaces() }
        compose.onNodeWithText("Where it went").assertExists()
        // The donut, the pace lines and the daily bars are bare Canvases; without
        // explicit semantics TalkBack gets nothing at all from the screen's primary
        // visualisations.
        compose.onNode(hasContentDescription("Category breakdown", substring = true))
            .assertExists()
        compose.onNode(hasContentDescription("Spending pace chart", substring = true))
            .assertExists()
        compose.onNode(hasContentDescription("Daily spending chart", substring = true))
            .assertExists()
    }
}
