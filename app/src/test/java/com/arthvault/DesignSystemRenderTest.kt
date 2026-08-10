package com.arthvault

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ReceiptLong
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
import com.arthvault.ui.components.AnalyticsSkeleton
import com.arthvault.ui.components.EmptyState
import com.arthvault.ui.components.LocalSnackbar
import com.arthvault.ui.components.RowSkeleton
import com.arthvault.ui.components.SnackbarNotifier
import com.arthvault.ui.screens.CashPositionCard
import com.arthvault.ui.screens.CategoryBreakdownCard
import com.arthvault.ui.screens.DailySpendChart
import com.arthvault.ui.screens.PeriodSelector
import com.arthvault.ui.screens.RecurringItemCard
import com.arthvault.ui.screens.SpendPaceChart
import com.arthvault.ui.screens.TransactionCard
import com.arthvault.ui.format.formatDirectedMoney
import com.arthvault.ui.format.formatMoney
import com.arthvault.ui.format.formatMoneyPrecise
import com.arthvault.ui.format.formatSignedMoney
import com.arthvault.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
        TransactionCard(
            transaction = transaction,
            onClick = {},
            onChangeCategory = {},
            onVoid = {},
        )
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
