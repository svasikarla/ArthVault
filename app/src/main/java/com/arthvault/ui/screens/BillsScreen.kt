package com.arthvault.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.EventRepeat
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.TrendingUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arthvault.data.analytics.BillMonth
import com.arthvault.data.analytics.BillObligation
import com.arthvault.data.analytics.BillSettlement
import com.arthvault.data.analytics.BillTrend
import com.arthvault.data.analytics.RecurringItem
import com.arthvault.ui.components.BarAction
import com.arthvault.ui.components.CardHeading
import com.arthvault.ui.components.EmptyState
import com.arthvault.ui.components.HairlineDivider
import com.arthvault.ui.components.VaultCard
import com.arthvault.ui.components.VaultRowCard
import com.arthvault.ui.components.VaultScaffold
import com.arthvault.ui.format.formatDate
import com.arthvault.ui.format.formatMoney
import com.arthvault.ui.format.formatMoneyPrecise
import com.arthvault.ui.format.formatPercentChange
import com.arthvault.ui.theme.Spacing
import com.arthvault.ui.theme.VaultTheme
import com.arthvault.ui.theme.moneyMedium
import com.arthvault.ui.theme.moneySmall
import com.arthvault.ui.viewmodel.BillsViewModel
import java.util.Calendar
import java.util.Locale

/**
 * Phase 9 — what is owed, kept visibly apart from what was spent.
 *
 * The separation from the Analytics screen is the design, not an accident of layout. A
 * card statement's purchases are already in the ledger, so a screen that put an
 * obligation total next to a spending total would invite the user to add two numbers
 * that describe the same rupees. Nothing here is ever summed with anything there, and
 * the header says which kind of number it is showing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillsScreen(
    viewModel: BillsViewModel,
    isMasked: Boolean = false,
    onToggleMask: (() -> Unit)? = null,
    now: Long = System.currentTimeMillis()
) {
    val insights by viewModel.insights.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val tappedNotices by viewModel.tappedNotices.collectAsStateWithLifecycle()
    val tappedTransactions by viewModel.tappedTransactions.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.refresh() }

    if (tappedNotices.isNotEmpty() || tappedTransactions.isNotEmpty()) {
        BillSourcesDialog(
            notices = tappedNotices,
            transactions = tappedTransactions,
            isMasked = isMasked,
            onDismiss = { viewModel.clearSources() }
        )
    }

    VaultScaffold(
        title = "Bills",
        actions = {
            onToggleMask?.let { toggle ->
                BarAction(
                    label = if (isMasked) "Show" else "Hide",
                    icon = if (isMasked) Icons.Outlined.Lock else Icons.Outlined.Security,
                    onClick = toggle
                )
            }
            BarAction(
                label = "Refresh",
                icon = Icons.Outlined.Refresh,
                onClick = { viewModel.refresh() }
            )
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isLoading,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = Spacing.standard)
                    .testTag("bills_list"),
                verticalArrangement = Arrangement.spacedBy(Spacing.standard)
            ) {
                item { Spacer(Modifier.height(Spacing.hairline)) }

                // Nothing captured and nothing inferred. Distinguished from "nothing
                // due" below, because they call for different responses: this one means
                // the app has not seen a bill SMS yet, which a rescan may fix.
                if (!insights.hasAnyNotices && insights.expectedAutoDebits.isEmpty()) {
                    item {
                        EmptyState(
                            icon = Icons.Outlined.ReceiptLong,
                            title = "No bills seen yet",
                            message = "Reminders from your card issuers and billers appear " +
                                "here once they arrive. Run a full inbox scan from the Rules " +
                                "tab to read the ones already on this phone."
                        )
                    }
                    return@LazyColumn
                }

                item {
                    OutstandingCard(
                        count = insights.dueSoon.size,
                        total = insights.outstandingTotal,
                        isMasked = isMasked
                    )
                }

                if (insights.dueSoon.isNotEmpty()) {
                    item { SectionHeading("Due & upcoming", insights.dueSoon.size) }
                    items(insights.dueSoon, key = { it.cycleKey }) { obligation ->
                        BillObligationRow(
                            obligation = obligation,
                            now = now,
                            isMasked = isMasked,
                            onClick = { viewModel.showSources(obligation) }
                        )
                    }
                }

                if (insights.expectedAutoDebits.isNotEmpty()) {
                    item {
                        SectionHeading("Expected auto-debits", insights.expectedAutoDebits.size)
                    }
                    item { AutoDebitNote() }
                    items(insights.expectedAutoDebits, key = { it.merchant }) { item ->
                        AutoDebitRow(
                            item = item,
                            now = now,
                            isMasked = isMasked,
                            onClick = { viewModel.showSourceTransactions(item.transactionIds) }
                        )
                    }
                }

                if (insights.monthlyTotals.isNotEmpty()) {
                    item { MonthlyBillTotalsCard(insights.monthlyTotals, isMasked) }
                }

                if (insights.trends.isNotEmpty()) {
                    item { SectionHeading("Bill movement", insights.trends.size) }
                    items(insights.trends, key = { it.billerKey }) { trend ->
                        BillTrendRow(trend, isMasked)
                    }
                }

                if (insights.settledOrPast.isNotEmpty()) {
                    item { SectionHeading("Settled & past", insights.settledOrPast.size) }
                    items(insights.settledOrPast, key = { it.cycleKey }) { obligation ->
                        BillObligationRow(
                            obligation = obligation,
                            now = now,
                            isMasked = isMasked,
                            onClick = { viewModel.showSources(obligation) }
                        )
                    }
                }

                item { Spacer(Modifier.height(Spacing.section)) }
            }
        }
    }
}

@Composable
private fun SectionHeading(title: String, count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            count.toString(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * The headline figure, labelled as an obligation rather than an expense.
 *
 * "Outstanding" and not "spent": none of this money has moved, and a card bill's
 * purchases are already counted on the Analytics tab. The wording is the only thing
 * stopping someone reading the two screens as parts of one total.
 */
@Composable
private fun OutstandingCard(count: Int, total: Double, isMasked: Boolean) {
    val semantics = VaultTheme.semantics
    VaultCard(accent = if (count > 0) semantics.caution else null) {
        Text(
            text = if (count == 0) "Nothing due" else "Outstanding",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(Spacing.hairline))
        Text(
            text = formatMoney(total, isMasked),
            style = MaterialTheme.typography.moneyMedium,
            color = if (count > 0) semantics.caution else MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(Spacing.tight))
        Text(
            text = if (count == 0) {
                "No bill you have been notified about is waiting to be paid."
            } else {
                "Across $count ${if (count == 1) "bill" else "bills"} you have been " +
                    "told about. Money owed, not money spent — these do not appear in " +
                    "your spending totals."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun BillObligationRow(
    obligation: BillObligation,
    now: Long,
    isMasked: Boolean,
    onClick: () -> Unit
) {
    val semantics = VaultTheme.semantics
    val days = obligation.daysUntilDue(now)
    val isPastDue = obligation.isPastDue(now)

    val accent = when {
        isPastDue -> semantics.negative
        obligation.settlement == BillSettlement.PAID -> semantics.positive
        days != null && days <= URGENT_DAYS -> semantics.caution
        else -> null
    }

    VaultRowCard(accent = accent) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = obligation.billerLabel,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1
                )
                Spacer(Modifier.height(Spacing.hairline))
                Text(
                    text = buildDueLine(obligation, days, isPastDue),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isPastDue) semantics.negative
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = obligation.amountDue
                        ?.let { formatMoneyPrecise(it, isMasked) }
                        ?: "No amount stated",
                    style = MaterialTheme.typography.moneySmall,
                    color = accent ?: MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(Spacing.hairline))
                SettlementChip(obligation.settlement)
            }
        }

        // Shown only when it exists and differs, because the minimum is the number a
        // user is most likely to misread as the bill.
        obligation.minAmountDue?.let { minimum ->
            Spacer(Modifier.height(Spacing.tight))
            Text(
                text = "Minimum ${formatMoneyPrecise(minimum, isMasked)} — paying this " +
                    "avoids a late fee, not interest.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun buildDueLine(obligation: BillObligation, days: Int?, isPastDue: Boolean): String {
    val account = obligation.accountTail?.let { " ••$it" } ?: ""
    val period = obligation.billingPeriodLabel?.let { " · $it" } ?: ""
    val timing = when {
        obligation.dueDate == null -> "No due date given"
        isPastDue && days != null -> "Was due ${formatDate(obligation.dueDate)} (${-days}d ago)"
        days == null -> formatDate(obligation.dueDate)
        days == 0 -> "Due today"
        days < 0 -> "Due ${formatDate(obligation.dueDate)}"
        days == 1 -> "Due tomorrow"
        else -> "Due in ${days}d · ${formatDate(obligation.dueDate)}"
    }
    return "$timing$account$period"
}

/**
 * The three-state settlement, worded as an observation.
 *
 * "No payment seen" rather than "Unpaid" is the whole point. A bill settled by autopay,
 * by netbanking, or from a bank whose alerts are not allowlisted leaves no trace in the
 * ledger, so the app genuinely does not know. Saying "Unpaid" would be an accusation
 * about something the user may well have done a week ago.
 */
@Composable
private fun SettlementChip(settlement: BillSettlement) {
    val semantics = VaultTheme.semantics
    val (label, color) = when (settlement) {
        BillSettlement.PAID -> "Paid" to semantics.positive
        BillSettlement.LIKELY_PAID -> "Part paid" to semantics.caution
        BillSettlement.NO_PAYMENT_SEEN -> "No payment seen" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .background(color.copy(alpha = 0.10f))
            .padding(horizontal = Spacing.tight, vertical = 2.dp)
    )
}

/**
 * Says where these came from, because they are inferred rather than announced.
 *
 * A subscription sends no reminder — the charge simply lands. The date beside each one
 * is the established cadence projected forward, not something a biller committed to,
 * and the screen should not present the two kinds of certainty identically.
 */
@Composable
private fun AutoDebitNote() {
    Text(
        text = "Worked out from your own history — no biller announces these. Dates are " +
            "the established cadence projected forward.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun AutoDebitRow(
    item: RecurringItem,
    now: Long,
    isMasked: Boolean,
    onClick: () -> Unit
) {
    val semantics = VaultTheme.semantics
    val days = item.daysUntilNextCharge(now)

    VaultRowCard(accent = if (item.isPriceHike) semantics.caution else null) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.EventRepeat,
                contentDescription = null,
                tint = semantics.info,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(Spacing.snug))
            Column(modifier = Modifier.weight(1f)) {
                Text(item.merchant, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Spacer(Modifier.height(Spacing.hairline))
                Text(
                    text = buildString {
                        append(
                            when {
                                days < 0 -> "Expected ${-days}d ago"
                                days == 0 -> "Expected today"
                                days == 1 -> "Expected tomorrow"
                                else -> "Expected in ${days}d"
                            }
                        )
                        append(" · every ${item.frequencyDays}d")
                        append(" · ${item.chargeCount} charges")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = formatMoneyPrecise(item.currentAmount, isMasked),
                    style = MaterialTheme.typography.moneySmall
                )
                if (item.isPriceHike) {
                    Spacer(Modifier.height(Spacing.hairline))
                    Text(
                        text = formatPercentChange(item.priceHikePercentage) ?: "",
                        style = MaterialTheme.typography.labelSmall,
                        color = semantics.caution
                    )
                }
            }
        }
    }
}

/**
 * What the bills came to, month by month.
 *
 * Bucketed by the month the money has to leave rather than the month the statement was
 * issued: a bill generated on 28 July and payable on 15 August is an August outgoing,
 * and putting it in July would make every month look like it belonged to the one before.
 */
@Composable
private fun MonthlyBillTotalsCard(months: List<BillMonth>, isMasked: Boolean) {
    val semantics = VaultTheme.semantics
    val peak = months.maxOf { it.total }.coerceAtLeast(1.0)

    VaultCard {
        CardHeading(
            title = "Monthly bill totals",
            icon = Icons.Outlined.ReceiptLong,
            iconTint = semantics.info,
            subtitle = "Billed in each month, by the date payment was due."
        )
        Spacer(Modifier.height(Spacing.snug))

        months.takeLast(MONTHS_SHOWN).forEach { month ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Spacing.hairline),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = monthLabel(month),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(64.dp)
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(8.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth((month.total / peak).toFloat())
                            .height(8.dp)
                            .clip(MaterialTheme.shapes.small)
                            .background(semantics.info)
                    )
                }
                Spacer(Modifier.width(Spacing.snug))
                Text(
                    text = formatMoney(month.total, isMasked),
                    style = MaterialTheme.typography.moneySmall
                )
            }
        }
    }
}

private fun monthLabel(month: BillMonth): String {
    val calendar = Calendar.getInstance().apply { timeInMillis = month.monthStart }
    val name = calendar.getDisplayName(
        Calendar.MONTH,
        Calendar.SHORT,
        Locale.getDefault()
    ) ?: month.month.toString()
    return "$name ${month.year % 100}"
}

/**
 * How one biller's bill has moved, with an explicit floor on what counts as a trend.
 *
 * Two bills make one comparison, and one comparison cannot tell a real rise from the
 * ordinary variation a metered bill has every month — an electricity bill in May is
 * higher than April's because of the weather, not because anything changed. Below three
 * cycles the card shows the figures and declines to call them a direction.
 */
@Composable
private fun BillTrendRow(trend: BillTrend, isMasked: Boolean) {
    val semantics = VaultTheme.semantics
    val delta = trend.delta
    val rising = delta != null && delta > 0

    VaultRowCard(accent = if (trend.isEstablished && rising) semantics.caution else null) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(trend.billerLabel, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Spacer(Modifier.height(Spacing.hairline))
                Text(
                    text = "${trend.cycles.size} ${if (trend.cycles.size == 1) "bill" else "bills"} seen" +
                        (trend.previousAmount?.let {
                            " · last ${formatMoneyPrecise(it, isMasked)}"
                        } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = formatMoneyPrecise(trend.latestAmount, isMasked),
                    style = MaterialTheme.typography.moneySmall
                )
                if (trend.isEstablished && delta != null && delta != 0.0) {
                    Spacer(Modifier.height(Spacing.hairline))
                    Text(
                        text = formatPercentChange(trend.percentageChange) ?: "",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (rising) semantics.caution else semantics.positive
                    )
                }
            }
        }

        if (!trend.isEstablished) {
            Spacer(Modifier.height(Spacing.tight))
            Text(
                text = "Too few cycles to call this a trend. " +
                    "${BillTrend.MIN_CYCLES_FOR_TREND} bills are needed before a rise " +
                    "means more than ordinary variation.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * F4.4 — the reminders behind a row, and whatever payment was matched to them.
 *
 * Kept in two labelled groups rather than one merged list. One half is what a biller
 * sent; the other is what this app decided that meant. A user checking a "Paid" they
 * disagree with needs to see which is which.
 */
@Composable
private fun BillSourcesDialog(
    notices: List<com.arthvault.data.local.entity.BillNoticeEntity>,
    transactions: List<com.arthvault.data.local.entity.TransactionEntity>,
    isMasked: Boolean,
    onDismiss: () -> Unit
) {
    val semantics = VaultTheme.semantics
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        title = { Text("Behind this bill") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(Spacing.snug)) {
                if (notices.isNotEmpty()) {
                    item {
                        Text(
                            "${notices.size} ${if (notices.size == 1) "reminder" else "reminders"} received",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    items(notices, key = { "n${it.id}" }) { notice ->
                        Column {
                            Text(
                                text = formatDate(notice.issuedAt) + " · " + notice.sender,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = notice.rawMessage,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                if (transactions.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(Spacing.tight))
                        HairlineDivider()
                        Spacer(Modifier.height(Spacing.tight))
                        Text(
                            "Matched payment${if (transactions.size == 1) "" else "s"}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    items(transactions, key = { "t${it.id}" }) { txn ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(txn.merchant, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    formatDate(txn.timestamp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = formatMoneyPrecise(txn.amount, isMasked),
                                style = MaterialTheme.typography.moneySmall,
                                color = semantics.negative
                            )
                        }
                    }
                } else if (notices.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(Spacing.tight))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(Spacing.tight))
                            Text(
                                text = "No payment matched. A bill paid by autopay or from " +
                                    "an account this app does not read leaves no trace here.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

/** Inside this many days a bill is worth colouring. */
private const val URGENT_DAYS = 3

/** A year of history is plenty on a phone-width chart. */
private const val MONTHS_SHOWN = 12
