package com.arthvault.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.arthvault.data.analytics.AnalyticsPeriod
import com.arthvault.data.analytics.CategorySlice
import com.arthvault.data.analytics.CategoryTrend
import com.arthvault.data.analytics.cumulativeSpend
import com.arthvault.data.analytics.InternalTransferSummary
import com.arthvault.data.analytics.RecurringItem
import com.arthvault.data.query.QueryMetric
import com.arthvault.ui.components.AnalyticsSkeleton
import com.arthvault.ui.components.CardHeading
import com.arthvault.ui.components.VaultCard
import com.arthvault.ui.components.VaultRowCard
import com.arthvault.ui.components.VaultScaffold
import com.arthvault.ui.format.formatCount
import com.arthvault.ui.format.formatDate
import com.arthvault.ui.format.formatDirectedMoney
import com.arthvault.ui.format.formatMoney
import com.arthvault.ui.format.formatMoneyPrecise
import com.arthvault.ui.format.formatPercentChange
import com.arthvault.ui.format.formatSignedMoney
import com.arthvault.ui.theme.Spacing
import com.arthvault.ui.theme.VaultTheme
import com.arthvault.ui.theme.moneyLarge
import com.arthvault.ui.theme.moneyMedium
import com.arthvault.ui.theme.moneySmall
import com.arthvault.ui.theme.numeric
import com.arthvault.ui.viewmodel.AnalyticsViewModel

@Composable
fun AnalyticsScreen(
    viewModel: AnalyticsViewModel
) {
    val analytics by viewModel.analytics.collectAsState()
    val scope by viewModel.scope.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val recurringList = analytics.recurring
    val forecast = analytics.forecast
    val anomalies = analytics.anomalies
    val duplicates = analytics.duplicates
    val categoryBreakdown = analytics.categoryBreakdown
    val categoryTrends = analytics.categoryTrends
    val period = analytics.period

    val queryAnswer by viewModel.queryAnswer.collectAsState()
    val tappedThrough by viewModel.tappedThrough.collectAsState()
    var question by rememberSaveable { mutableStateOf("") }
    val semantics = VaultTheme.semantics

    LaunchedEffect(Unit) {
        viewModel.refreshAnalytics()
    }

    // F4.4 — the rows behind whichever figure was tapped. Every insight on this
    // screen carries its source transaction ids, so a number that looks wrong can be
    // opened and checked rather than argued with.
    if (tappedThrough.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { viewModel.clearSourceTransactions() },
            shape = MaterialTheme.shapes.extraLarge,
            title = { Text("${tappedThrough.size} transactions") },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(Spacing.snug)) {
                    items(tappedThrough, key = { it.id }) { txn ->
                        val isCredit = txn.direction != "DEBIT"
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(txn.merchant, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = formatDirectedMoney(txn.amount, isCredit),
                                    style = MaterialTheme.typography.moneySmall,
                                    color = semantics.forDirection(isCredit)
                                )
                            }
                            Text(
                                "${txn.category} · ${formatDate(txn.timestamp)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.clearSourceTransactions() }) { Text("Close") }
            }
        )
    }

    VaultScaffold(
        title = "Analytics",
        actions = {
            IconButton(onClick = { viewModel.refreshAnalytics() }) {
                Icon(Icons.Default.Refresh, contentDescription = "Recalculate insights")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = Spacing.standard),
            verticalArrangement = Arrangement.spacedBy(Spacing.standard)
        ) {
            item {
                Text(
                    text = "Deterministic statistical insights, computed on this device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Every figure below is scoped to whatever is selected here.
            item {
                PeriodSelector(selected = scope, onSelect = { viewModel.setScope(it) })
            }

            // Recomputing runs six passes over the whole ledger. A skeleton of the
            // cards that are coming says what is being rebuilt; the 2dp progress line
            // this replaces said only that something was happening somewhere.
            item {
                Crossfade(targetState = isLoading, label = "analytics-loading") { loading ->
                    if (loading) {
                        AnalyticsSkeleton()
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.standard)) {
                            // Earned, spent, left — the three figures the screen now
                            // leads with. Income was previously computed and discarded.
                            CashPositionCard(
                                period = period,
                                summary = analytics.summary,
                                comparison = analytics.comparisonSummary,
                                forecast = forecast,
                                onShowIncome = {
                                    viewModel.showSourceTransactions(analytics.summary.incomeTransactionIds)
                                },
                                onShowSpend = {
                                    viewModel.showSourceTransactions(analytics.summary.spendTransactionIds)
                                }
                            )

                            // The screen had no time axis at all before these two.
                            SpendPaceChart(
                                period = period,
                                current = cumulativeSpend(analytics.dailyTotals),
                                previous = cumulativeSpend(analytics.comparisonDailyTotals)
                            )

                            DailySpendChart(buckets = analytics.dailyTotals)
                        }
                    }
                }
            }

            // F4.1 — ask the ledger a question. Nothing here is generated: the
            // grammar is deterministic and the arithmetic runs over rows you can
            // open (F4.2 forbids a model anywhere near the numbers).
            item {
                VaultCard {
                    CardHeading(
                        title = "Ask your ledger",
                        icon = Icons.Default.Search,
                        iconTint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(Spacing.snug))
                    OutlinedTextField(
                        value = question,
                        onValueChange = { question = it },
                        label = { Text("Question") },
                        placeholder = { Text("e.g. spend on fuel last quarter") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { viewModel.ask(question) })
                    )
                    Spacer(modifier = Modifier.height(Spacing.snug))
                    Button(
                        onClick = { viewModel.ask(question) },
                        enabled = question.isNotBlank(),
                        shape = MaterialTheme.shapes.small
                    ) { Text("Ask") }

                    when (val answer = queryAnswer) {
                        null -> Unit

                        // Saying "I could not read that" is a real answer. Showing
                        // a confident 0 for a question the app misread is how a
                        // wrong number gets believed.
                        is AnalyticsViewModel.QueryAnswer.NotUnderstood -> {
                            Spacer(modifier = Modifier.height(Spacing.snug))
                            Text(
                                "That question could not be read. Try naming a metric and " +
                                    "a subject, e.g. \"total spend on groceries last month\".",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                        }

                        is AnalyticsViewModel.QueryAnswer.Answered -> {
                            val result = answer.result
                            Spacer(modifier = Modifier.height(Spacing.snug))
                            // The restatement matters as much as the figure: a
                            // misread question is only detectable if the app says
                            // which one it answered.
                            Text(
                                result.intent.interpretation,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = if (result.intent.metric == QueryMetric.COUNT) {
                                    formatCount(result.value)
                                } else {
                                    formatMoneyPrecise(result.value)
                                },
                                style = MaterialTheme.typography.moneyLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                            if (result.transactionIds.isNotEmpty()) {
                                TextButton(
                                    onClick = { viewModel.showSourceTransactions(result.transactionIds) }
                                ) {
                                    Text("From ${result.matchCount} transactions — show them")
                                }
                            }
                        }
                    }
                }
            }

            // What the figures above deliberately leave out. Money disappearing from
            // a total with no explanation is its own kind of wrong number, and if an
            // account was marked by mistake this line is how the user finds out.
            item {
                val internal = analytics.internalTransfers
                if (!internal.isEmpty) {
                    InternalTransferNote(
                        summary = internal,
                        onClick = { viewModel.showSourceTransactions(internal.transactionIds) }
                    )
                }
            }

            // F3.6 — this window against the like-for-like earlier one, biggest
            // movement first.
            item {
                if (categoryTrends.isNotEmpty()) {
                    CategoryTrendCard(
                        period = period,
                        trends = categoryTrends.take(6),
                        onTrendClick = { viewModel.showSourceTransactions(it.transactionIds) }
                    )
                }
            }

            item {
                if (categoryBreakdown.isNotEmpty()) {
                    CategoryBreakdownCard(
                        period = period,
                        slices = categoryBreakdown,
                        onSliceClick = { viewModel.showSourceTransactions(it.transactionIds) }
                    )
                }
            }

            // Price hike banner
            item {
                val priceHikeItems = recurringList.filter { it.isPriceHike }
                if (priceHikeItems.isNotEmpty()) {
                    VaultCard(accent = semantics.caution) {
                        CardHeading(
                            title = "Silent price hike detected",
                            icon = Icons.Default.Warning,
                            iconTint = semantics.caution
                        )
                        Spacer(modifier = Modifier.height(Spacing.tight))
                        priceHikeItems.forEach { hike ->
                            Text(
                                text = "• ${hike.merchant}: up %.1f%% — %s → %s".format(
                                    hike.priceHikePercentage,
                                    formatMoney(hike.previousAmount ?: 0.0),
                                    formatMoney(hike.currentAmount)
                                ),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }

            // Section: Detected Recurring Subscriptions
            item {
                Column {
                    Text(
                        text = "Recurring subscriptions & outflows (${recurringList.size})",
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (recurringList.isNotEmpty()) {
                        // The monthly-equivalent run rate. A quarterly ₹1,499 and a
                        // monthly ₹499 are not comparable until both are stated per
                        // month, and the sum of raw charge amounts is not a rate at all.
                        val perMonth = recurringList.sumOf {
                            it.currentAmount * 30.0 / it.frequencyDays.coerceAtLeast(1)
                        }
                        Text(
                            text = "About ${formatMoney(perMonth)} a month committed",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (recurringList.isEmpty()) {
                item {
                    Text(
                        text = "No recurring monthly charges or subscriptions detected yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(recurringList, key = { it.merchant }) { item ->
                    // F4.4 — a recurring charge is an inference, so the charges it
                    // was inferred from have to be reachable. "Netflix, ₹649
                    // monthly" is only checkable if you can see the three charges
                    // that produced it.
                    RecurringItemCard(
                        item = item,
                        modifier = Modifier.animateItem(),
                        onClick = { viewModel.showSourceTransactions(item.transactionIds) }
                    )
                }
            }

            // Section: Anomalies (F3.3), scoped to the selected window. These lists
            // used to run over the whole ledger with no dates on the rows, so a
            // fourteen-month-old outlier sat at the top forever, indistinguishable
            // from something that happened yesterday.
            if (anomalies.isNotEmpty()) {
                item { AlertSectionHeading("Unusual spending", anomalies.size, period) }
                items(anomalies, key = { it.transaction.id }) { anomaly ->
                    AlertRow(
                        tint = semantics.caution,
                        icon = Icons.Default.NotificationsActive,
                        title = "${anomaly.transaction.merchant} • ${formatMoney(anomaly.transaction.amount)}",
                        detail = "%.1f× the usual %s for %s".format(
                            anomaly.ratioToMedian,
                            formatMoney(anomaly.categoryMedian),
                            anomaly.transaction.category
                        ),
                        timestamp = anomaly.transaction.timestamp,
                        modifier = Modifier.animateItem(),
                        onClick = { viewModel.showSourceTransactions(listOf(anomaly.transaction.id)) }
                    )
                }
            }

            // Section: Possible duplicate charges (F3.4)
            if (duplicates.isNotEmpty()) {
                item { AlertSectionHeading("Possible duplicate charges", duplicates.size, period) }
                items(duplicates, key = { it.id }) { txn ->
                    AlertRow(
                        tint = semantics.negative,
                        icon = Icons.Default.Warning,
                        title = "${txn.merchant} • ${formatMoney(txn.amount)}",
                        detail = "Charged twice for the same amount within 24 hours",
                        timestamp = txn.timestamp,
                        modifier = Modifier.animateItem(),
                        onClick = { viewModel.showSourceTransactions(listOf(txn.id)) }
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(Spacing.section)) }
        }
    }
}

/** Heading for an alert list, naming the window it was drawn from. */
@Composable
private fun AlertSectionHeading(title: String, count: Int, period: AnalyticsPeriod) {
    Column {
        Text(text = "$title ($count)", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "In ${period.label}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * One alert row. Dated, because "when" is half of whether an alert matters, and
 * tappable, because an inference you cannot check is a number taken on faith (F4.4).
 */
@Composable
private fun AlertRow(
    tint: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    detail: String,
    timestamp: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    VaultRowCard(
        modifier = modifier.clickable(onClickLabel = "Show source transactions") { onClick() },
        accent = tint
    ) {
        Row(
            modifier = Modifier.heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(Spacing.snug))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(Spacing.tight))
            Text(
                text = formatDate(timestamp),
                style = MaterialTheme.typography.numeric,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun CategoryBreakdownCard(
    period: AnalyticsPeriod,
    slices: List<CategorySlice>,
    onSliceClick: (CategorySlice) -> Unit
) {
    // The category ramp, not the semantic set. These slices used to be drawn in
    // ArthCrimson / ArthEmerald / ArthGold, so "Groceries" rendered in the red that
    // means declined and the slice beside it in the green that means income.
    val sliceColors = VaultTheme.semantics.categorical
    val totalSpent = slices.sumOf { it.total }

    VaultCard {
        CardHeading(
            title = "Where it went",
            icon = Icons.Default.PieChart,
            iconTint = VaultTheme.semantics.info,
            // A percentage is meaningless without the window it covers: 34% of a
            // five-day-old month and 34% of a finished one are different claims.
            subtitle = period.label
        )

        Spacer(modifier = Modifier.height(Spacing.standard))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.standard)
        ) {
            val breakdown = slices.joinToString(", ") {
                "${it.category} ${"%.0f".format(it.fraction * 100)} percent"
            }
            Box(
                modifier = Modifier
                    .size(130.dp)
                    .semantics {
                        contentDescription =
                            "Category breakdown, ${formatMoney(totalSpent)} total. $breakdown"
                    },
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    var startAngle = -90f
                    slices.forEachIndexed { idx, slice ->
                        val sweepAngle = slice.fraction * 360f
                        drawArc(
                            color = sliceColors[idx % sliceColors.size],
                            startAngle = startAngle,
                            // Only inset a gap when the slice is wide enough to
                            // survive it; thin slices would otherwise vanish.
                            sweepAngle = if (sweepAngle > 8f) sweepAngle - 4f else sweepAngle,
                            useCenter = false,
                            style = Stroke(width = 24.dp.toPx(), cap = StrokeCap.Round)
                        )
                        startAngle += sweepAngle
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "TOTAL",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(formatMoney(totalSpent), style = MaterialTheme.typography.moneySmall)
                }
            }

            // Legend. Carries the amount as well as the share — a percentage alone
            // cannot be checked against anything — and each row opens the
            // transactions behind it (F4.4).
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.tight)
            ) {
                slices.forEachIndexed { idx, slice ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.extraSmall)
                            .clickable(onClickLabel = "Show ${slice.category} transactions") {
                                onSliceClick(slice)
                            }
                            .heightIn(min = 40.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(sliceColors[idx % sliceColors.size])
                        )
                        Spacer(modifier = Modifier.width(Spacing.tight))
                        Column {
                            Text(
                                text = slice.category,
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = "${formatMoney(slice.total)} · " +
                                    "${"%.0f".format(slice.fraction * 100)}%",
                                style = MaterialTheme.typography.numeric,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RecurringItemCard(
    item: RecurringItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val semantics = VaultTheme.semantics
    VaultRowCard(
        modifier = modifier.clickable(onClickLabel = "Show the charges behind this") { onClick() }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(semantics.info.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Repeat,
                    contentDescription = null,
                    tint = semantics.info,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(Spacing.snug))

            Column(modifier = Modifier.weight(1f)) {
                Text(item.merchant, style = MaterialTheme.typography.titleMedium)
                Text(
                    "Every ~${item.frequencyDays} days • ${item.category}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // The most useful fact about a subscription, and the one the card
                // was not showing: when it next hits.
                val daysAway = item.daysUntilNextCharge()
                val imminent = daysAway in 0..7
                Text(
                    text = when {
                        daysAway < 0 -> "Expected ${-daysAway}d ago — may have stopped"
                        daysAway == 0 -> "Due today"
                        else -> "Next ~${formatDate(item.nextExpectedTimestamp)} (in ${daysAway}d)"
                    },
                    style = if (imminent) {
                        MaterialTheme.typography.labelMedium
                    } else {
                        MaterialTheme.typography.numeric
                    },
                    color = if (imminent) {
                        semantics.caution
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    formatMoney(item.currentAmount),
                    style = MaterialTheme.typography.moneyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (item.isPriceHike) {
                    Spacer(modifier = Modifier.height(Spacing.hairline))
                    Surface(
                        shape = MaterialTheme.shapes.extraSmall,
                        color = semantics.negative.copy(alpha = 0.12f)
                    ) {
                        Text(
                            "+%.1f%% hike".format(item.priceHikePercentage),
                            modifier = Modifier.padding(horizontal = Spacing.tight, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = semantics.negative
                        )
                    }
                }
            }
        }
    }
}

/**
 * States plainly what was left out of the spending and income figures.
 *
 * Not a warning and not an insight — a reconciliation line. The user asked for these
 * transfers to be excluded; this says how much that came to, and opens the rows so
 * the exclusion can be checked against what actually happened.
 */
@Composable
private fun InternalTransferNote(
    summary: InternalTransferSummary,
    onClick: () -> Unit
) {
    VaultCard(
        modifier = Modifier.clickable(onClickLabel = "Show these transfers") { onClick() }
    ) {
        CardHeading(
            title = "Between your own accounts",
            icon = Icons.Default.SwapHoriz
        )
        Spacer(modifier = Modifier.height(Spacing.tight))
        Text(
            buildString {
                append("${summary.count} transfer")
                if (summary.count != 1) append("s")
                append(" excluded from the figures above — ")
                append("${formatMoney(summary.outflowTotal)} out")
                append(", ${formatMoney(summary.inflowTotal)} in. ")
                append("Not counted as spending or income.")
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(Spacing.hairline))
        Text(
            "Tap to see them",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/**
 * F3.6 — per-category movement between this window and the like-for-like earlier one.
 *
 * Sorted by the size of the change rather than by total, because a category that
 * doubled matters more than the one that is merely largest — and the largest is
 * already the first slice of the donut above.
 */
@Composable
private fun CategoryTrendCard(
    period: AnalyticsPeriod,
    trends: List<CategoryTrend>,
    onTrendClick: (CategoryTrend) -> Unit
) {
    val semantics = VaultTheme.semantics
    VaultCard {
        CardHeading(
            title = "Category trends",
            icon = Icons.Default.TrendingUp,
            iconTint = semantics.info,
            // Naming both windows is what makes this checkable. It used to say "this
            // month against last" while comparing a partial month to a complete one.
            subtitle = "${period.label} against ${period.comparisonLabel}"
        )
        Spacer(modifier = Modifier.height(Spacing.snug))

        trends.forEach { trend ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.extraSmall)
                    .clickable(onClickLabel = "Show ${trend.category} transactions") {
                        onTrendClick(trend)
                    }
                    .heightIn(min = 48.dp)
                    .padding(vertical = Spacing.tight),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(trend.category, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${formatMoney(trend.previousTotal)} → ${formatMoney(trend.currentTotal)}",
                        style = MaterialTheme.typography.numeric,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        formatSignedMoney(trend.delta),
                        style = MaterialTheme.typography.moneySmall,
                        // Spending more is the negative direction, whichever sign
                        // the delta carries.
                        color = semantics.forAmount(-trend.delta)
                    )
                    Text(
                        // A null percentage is "new spending", not "up 100%".
                        // Dividing by a zero baseline yields infinity, and
                        // rendering that as a number puts plausible-looking
                        // nonsense on screen.
                        text = formatPercentChange(trend.percentageChange) ?: "new",
                        style = MaterialTheme.typography.numeric,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
