package com.arthvault.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material.icons.outlined.TrendingUp
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.arthvault.data.analytics.AnalyticsPeriod
import com.arthvault.data.analytics.DayBucket
import com.arthvault.data.analytics.MonthEndForecast
import com.arthvault.data.analytics.PeriodScope
import com.arthvault.data.analytics.PeriodSummary
import com.arthvault.ui.components.CardHeading
import com.arthvault.ui.components.HairlineDivider
import com.arthvault.ui.components.VaultCard
import com.arthvault.ui.components.animateMetric
import com.arthvault.ui.format.formatMoney
import com.arthvault.ui.format.formatPercentChange
import com.arthvault.ui.format.formatShortDate
import com.arthvault.ui.format.formatSignedMoney
import com.arthvault.ui.theme.Spacing
import com.arthvault.ui.theme.VaultTheme
import com.arthvault.ui.theme.moneyLarge
import com.arthvault.ui.theme.moneyMedium
import com.arthvault.ui.theme.moneySmall

// --- period selector ------------------------------------------------------

/**
 * The scope control. Nothing on this screen could previously be looked at over any
 * window but the current calendar month.
 */
@Composable
fun PeriodSelector(
    selected: PeriodScope,
    onSelect: (PeriodScope) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Spacing.tight)
    ) {
        PeriodScope.entries.forEach { scope ->
            FilterChip(
                selected = scope == selected,
                onClick = { onSelect(scope) },
                label = { Text(scope.chipLabel) },
                shape = MaterialTheme.shapes.small,
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            )
        }
    }
}

// --- cash position --------------------------------------------------------

/**
 * Earned, spent, left — in that order, at the top of the screen.
 *
 * The card this replaces led with a projected month-end *outflow* in the largest type
 * available: a derived estimate, compared to nothing. Income was computed and then
 * discarded, surviving only as the unlabelled denominator of a progress bar, which
 * fell back to a meaningless half-full bar whenever income was zero. The three
 * figures a person actually opens a finance app for were all absent.
 */
@Composable
fun CashPositionCard(
    period: AnalyticsPeriod,
    summary: PeriodSummary,
    comparison: PeriodSummary,
    forecast: MonthEndForecast?,
    onShowIncome: () -> Unit,
    onShowSpend: () -> Unit,
    /**
     * Carries the brand accent, marking this as the screen's one focal card.
     *
     * Off by default so the render tests and any future reuse get the neutral card.
     * The accent is a hierarchy signal here, not a status one — everything semantic on
     * this card (the figures, the bar, the forecast) is already coloured by meaning.
     */
    accented: Boolean = false
) {
    VaultCard(accent = if (accented) MaterialTheme.colorScheme.primary else null) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = period.label, style = MaterialTheme.typography.titleMedium)
            if (period.isOpen && period.daysRemaining > 0) {
                Text(
                    text = "${period.daysRemaining} days left",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(Spacing.snug))

        CashFigures(summary = summary, onShowIncome = onShowIncome, onShowSpend = onShowSpend)

        Spacer(modifier = Modifier.height(Spacing.snug))

        SpendAgainstIncomeBar(
            spent = summary.spent,
            income = summary.income,
            committed = forecast?.committedRecurringTotal ?: 0.0
        )

        Spacer(modifier = Modifier.height(Spacing.tight))

        Text(
            text = summary.spentFractionOfIncome
                ?.let { "Spent ${(it * 100).toInt()}% of income so far" }
                // "No income recorded" and "you have spent all your income" are
                // very different statements and must not share a rendering.
                ?: "No income recorded in this period",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        ComparisonLine(period = period, summary = summary, comparison = comparison)

        if (forecast != null) {
            Spacer(modifier = Modifier.height(Spacing.snug))
            HairlineDivider()
            Spacer(modifier = Modifier.height(Spacing.snug))
            ForecastSection(forecast)
        }
    }
}

/**
 * Income / spent / net.
 *
 * Three equal columns is the right layout right up until the user turns their font
 * size up, at which point `₹1,23,456` at 20sp bold has a third of the screen to live
 * in and clips. Money strings contain no spaces, so they cannot wrap their way out of
 * it. Above ~130% scale the row becomes a stack, which costs a little vertical space
 * and keeps every figure readable.
 */
@Composable
private fun CashFigures(
    summary: PeriodSummary,
    onShowIncome: () -> Unit,
    onShowSpend: () -> Unit
) {
    val semantics = VaultTheme.semantics
    val stacked = LocalDensity.current.fontScale > 1.3f

    val income = @Composable { modifier: Modifier ->
        MoneyColumn("INCOME", formatMoney(summary.income), semantics.positive,
            modifier.clickable(onClickLabel = "Show income transactions") { onShowIncome() })
    }
    val spent = @Composable { modifier: Modifier ->
        MoneyColumn("SPENT", formatMoney(summary.spent), semantics.negative,
            modifier.clickable(onClickLabel = "Show spending transactions") { onShowSpend() })
    }
    val net = @Composable { modifier: Modifier ->
        MoneyColumn("NET", formatSignedMoney(summary.net), semantics.forAmount(summary.net), modifier)
    }

    if (stacked) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.snug)) {
            income(Modifier.fillMaxWidth())
            spent(Modifier.fillMaxWidth())
            net(Modifier.fillMaxWidth())
        }
    } else {
        Row(modifier = Modifier.fillMaxWidth()) {
            income(Modifier.weight(1f))
            spent(Modifier.weight(1f))
            net(Modifier.weight(1f))
        }
    }
}

@Composable
private fun MoneyColumn(
    label: String,
    amount: String,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            // 48dp so the tappable figures meet the touch-target minimum even when
            // the two lines of text come to less than that.
            .heightIn(min = 48.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$label $amount" }
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(Spacing.hairline))
        Text(text = amount, style = MaterialTheme.typography.moneyMedium, color = tint)
    }
}

/**
 * Spending against income, with the still-to-come commitments ghosted on ahead of it.
 *
 * Drawn rather than assembled from nested boxes because the two segments have to be
 * fractions of the same width, and a `Row` measures its second child against whatever
 * the first one left behind.
 */
@Composable
private fun SpendAgainstIncomeBar(spent: Double, income: Double, committed: Double) {
    val semantics = VaultTheme.semantics
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    // With no income there is no denominator, and inventing one (the old card used a
    // hardcoded half-full bar) draws a figure that means nothing. Fall back to the
    // total outflow so the bar still shows the split between spent and committed.
    val basis = if (income > 0) income else (spent + committed)
    val spentTarget = if (basis > 0) (spent / basis).coerceIn(0.0, 1.0).toFloat() else 0f
    val committedTarget =
        if (basis > 0) (committed / basis).coerceIn(0.0, 1.0 - spentTarget).toFloat() else 0f
    val overspent = income > 0 && spent > income

    val spentFraction by animateMetric(spentTarget, label = "spend-bar")
    val committedFraction by animateMetric(committedTarget, label = "committed-bar")

    val description = buildString {
        append("Spent ${formatMoney(spent)}")
        if (income > 0) append(" of ${formatMoney(income)} income")
        if (committed > 0) append(", with ${formatMoney(committed)} still committed")
        if (overspent) append(". Over budget.")
    }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(12.dp)
            .clip(MaterialTheme.shapes.extraSmall)
            .semantics { contentDescription = description }
    ) {
        drawRect(color = track)
        drawRect(
            color = if (overspent) semantics.negative else semantics.positive,
            size = size.copy(width = size.width * spentFraction)
        )
        if (committedFraction > 0f) {
            drawRect(
                color = semantics.caution.copy(alpha = 0.45f),
                topLeft = Offset(size.width * spentFraction, 0f),
                size = size.copy(width = size.width * committedFraction)
            )
        }
    }
}

/**
 * How this window compares to the same span of the previous one.
 *
 * "Same span" is load-bearing. The comparison used to be a month-to-date total set
 * against a *whole* previous month, which reported a collapse in spending every time
 * the user opened the app before about the 25th.
 */
@Composable
private fun ComparisonLine(
    period: AnalyticsPeriod,
    summary: PeriodSummary,
    comparison: PeriodSummary
) {
    if (comparison.spent <= 0.0 && comparison.income <= 0.0) return

    val delta = summary.spent - comparison.spent
    val pct = if (comparison.spent > 0) (delta / comparison.spent) * 100.0 else null

    Spacer(modifier = Modifier.height(Spacing.tight))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "vs ${period.comparisonLabel}:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(Spacing.hairline))
        Text(
            text = buildString {
                append(formatSignedMoney(delta))
                formatPercentChange(pct)?.let { append(" ($it)") }
                append(" spend")
            },
            style = MaterialTheme.typography.moneySmall,
            color = VaultTheme.semantics.forAmount(-delta)
        )
    }
}

/**
 * The forecast, demoted to a supporting role and given the one number that makes it
 * actionable.
 *
 * A projection tells the user where they are heading; "safe to spend" tells them what
 * to do about it today. Every input already existed — none of it reached the screen.
 */
@Composable
private fun ForecastSection(forecast: MonthEndForecast) {
    val semantics = VaultTheme.semantics
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.Savings,
                contentDescription = null,
                tint = semantics.info,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(Spacing.tight))
            Text(
                text = "SAFE TO SPEND",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(Spacing.hairline))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = formatMoney(forecast.safeToSpendPerDay),
                style = MaterialTheme.typography.moneyLarge,
                color = semantics.info
            )
            Spacer(modifier = Modifier.width(Spacing.hairline))
            Text(
                text = "/day for ${forecast.daysRemainingInMonth} days",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = Spacing.hairline)
            )
        }

        Spacer(modifier = Modifier.height(Spacing.snug))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.small
                )
                .padding(Spacing.snug),
            horizontalArrangement = Arrangement.spacedBy(Spacing.tight)
        ) {
            ForecastStat("ON COURSE TO SPEND", formatMoney(forecast.projectedSpentMonthEnd), Modifier.weight(1f))
            ForecastStat(
                label = "MONTH-END NET",
                value = formatSignedMoney(forecast.projectedNetMonthEnd),
                modifier = Modifier.weight(1f),
                tint = semantics.forAmount(forecast.projectedNetMonthEnd)
            )
            ForecastStat("PER DAY SO FAR", formatMoney(forecast.dailySpendVelocity), Modifier.weight(1f))
        }

        if (forecast.expectedIncomeRemaining > 0) {
            Spacer(modifier = Modifier.height(Spacing.tight))
            Text(
                text = "Includes ${formatMoney(forecast.expectedIncomeRemaining)} of " +
                    "recurring income not yet received this month.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Two days of spending stretched across thirty is arithmetic, not a forecast.
        // Saying so is cheaper than being quietly wrong for the first week of every
        // month, and the card used to present day-2 output exactly like day-25 output.
        if (!forecast.isProjectionReliable) {
            Spacer(modifier = Modifier.height(Spacing.tight))
            Text(
                text = "Only ${forecast.daysElapsedInMonth} day" +
                    (if (forecast.daysElapsedInMonth == 1) "" else "s") +
                    " into the month — the projection will settle as more spending lands.",
                style = MaterialTheme.typography.bodySmall,
                color = semantics.caution
            )
        }
    }
}

@Composable
private fun ForecastStat(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    tint: Color? = null
) {
    Column(modifier = modifier.semantics(mergeDescendants = true) {}) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.moneySmall,
            color = tint ?: MaterialTheme.colorScheme.onSurface
        )
    }
}

// --- charts ---------------------------------------------------------------

/**
 * Cumulative spending this period against the same elapsed span of the last one.
 *
 * The screen had no time axis at all: a donut, three lists and a projected total, all
 * snapshots. This is the chart that answers "am I ahead of where I was last month",
 * which no arrangement of totals can.
 */
@Composable
fun SpendPaceChart(
    period: AnalyticsPeriod,
    current: List<Double>,
    previous: List<Double>
) {
    if (current.size < 2) return

    val peak = maxOf(current.maxOrNull() ?: 0.0, previous.maxOrNull() ?: 0.0)
    if (peak <= 0.0) return

    val semantics = VaultTheme.semantics
    val currentEnd = current.last()
    val previousAtSamePoint = previous.getOrNull(current.lastIndex) ?: previous.lastOrNull() ?: 0.0
    val gap = currentEnd - previousAtSamePoint
    val outline = MaterialTheme.colorScheme.outlineVariant

    val summary = if (previousAtSamePoint <= 0.0) {
        "${formatMoney(currentEnd)} so far. No spending in the comparison period."
    } else {
        "${formatMoney(currentEnd)} so far — ${formatSignedMoney(gap)} " +
            "against ${formatMoney(previousAtSamePoint)} by the same day."
    }

    ChartCard(
        title = "Spending pace",
        subtitle = "${period.label} against ${period.comparisonLabel}",
        icon = Icons.Outlined.TrendingUp
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                // A bare Canvas is invisible to TalkBack. This is the primary
                // visualisation on the screen; the figure it draws has to be readable
                // without seeing it.
                .semantics { contentDescription = "Spending pace chart. $summary" }
        ) {
            fun pathOf(series: List<Double>): Path {
                val path = Path()
                // Both series are laid out on this window's day count so the same x
                // position means the same day-of-period in each.
                val steps = (current.size - 1).coerceAtLeast(1)
                series.take(current.size).forEachIndexed { index, value ->
                    val x = size.width * index / steps
                    val y = size.height - (size.height * (value / peak)).toFloat()
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                return path
            }

            drawLine(
                color = outline,
                start = Offset(0f, size.height),
                end = Offset(size.width, size.height),
                strokeWidth = 1.dp.toPx()
            )
            if (previous.isNotEmpty()) {
                drawPath(
                    path = pathOf(previous),
                    color = outline,
                    style = Stroke(
                        width = 2.dp.toPx(),
                        cap = StrokeCap.Round,
                        pathEffect = PathEffect.dashPathEffect(
                            floatArrayOf(8.dp.toPx(), 6.dp.toPx())
                        )
                    )
                )
            }
            drawPath(
                path = pathOf(current),
                color = if (gap > 0) semantics.negative else semantics.positive,
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
            )
        }

        Spacer(modifier = Modifier.height(Spacing.tight))
        Text(
            text = summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Daily spending across the window, with the typical day marked.
 *
 * Zero days are drawn as gaps rather than skipped, so a quiet fortnight reads as a
 * quiet fortnight instead of compressing out of the picture.
 */
@Composable
fun DailySpendChart(buckets: List<DayBucket>) {
    if (buckets.isEmpty()) return
    val peak = buckets.maxOf { it.spent }
    if (peak <= 0.0) return

    val spendingDays = buckets.map { it.spent }.filter { it > 0 }.sorted()
    val typicalDay = if (spendingDays.isEmpty()) 0.0 else spendingDays[spendingDays.size / 2]
    val outline = MaterialTheme.colorScheme.outlineVariant
    val barColor = VaultTheme.semantics.info

    val caption = "typical day ${formatMoney(typicalDay)} · busiest ${formatMoney(peak)}"

    ChartCard(
        title = "Daily spending",
        subtitle = "${spendingDays.size} of ${buckets.size} days had spending",
        icon = Icons.Outlined.ShowChart
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp)
                .semantics {
                    contentDescription = "Daily spending chart from " +
                        "${formatShortDate(buckets.first().dayStart)} to " +
                        "${formatShortDate(buckets.last().dayStart)}. $caption."
                }
        ) {
            val slot = size.width / buckets.size
            val barWidth = (slot * 0.62f).coerceAtLeast(1.5f)

            buckets.forEach { bucket ->
                if (bucket.spent <= 0.0) return@forEach
                val barHeight = (size.height * (bucket.spent / peak)).toFloat()
                drawRect(
                    color = barColor,
                    topLeft = Offset(
                        x = slot * bucket.dayIndex + (slot - barWidth) / 2f,
                        y = size.height - barHeight
                    ),
                    size = androidx.compose.ui.geometry.Size(barWidth, barHeight)
                )
            }

            if (typicalDay > 0) {
                val y = size.height - (size.height * (typicalDay / peak)).toFloat()
                drawLine(
                    color = outline,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(
                        floatArrayOf(6.dp.toPx(), 5.dp.toPx())
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(Spacing.tight))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatShortDate(buckets.first().dayStart),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = caption,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = formatShortDate(buckets.last().dayStart),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ChartCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable () -> Unit
) {
    VaultCard {
        CardHeading(
            title = title,
            icon = icon,
            iconTint = VaultTheme.semantics.info,
            subtitle = subtitle
        )
        Spacer(modifier = Modifier.height(Spacing.snug))
        content()
    }
}
