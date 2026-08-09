package com.arthvault.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arthvault.data.analytics.CategorySlice
import com.arthvault.data.analytics.MonthEndForecast
import com.arthvault.data.analytics.RecurringItem
import com.arthvault.ui.theme.ArthCrimson
import com.arthvault.ui.theme.ArthEmerald
import com.arthvault.ui.theme.ArthEmeraldLight
import com.arthvault.ui.theme.ArthGold
import com.arthvault.ui.theme.ArthGoldLight
import com.arthvault.ui.theme.ArthIndigo
import com.arthvault.ui.viewmodel.AnalyticsViewModel

@Composable
fun AnalyticsScreen(
    viewModel: AnalyticsViewModel
) {
    val analytics by viewModel.analytics.collectAsState()
    val recurringList = analytics.recurring
    val forecast = analytics.forecast
    val anomalies = analytics.anomalies
    val duplicates = analytics.duplicates
    val categoryBreakdown = analytics.categoryBreakdown

    LaunchedEffect(Unit) {
        viewModel.refreshAnalytics()
    }

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header Title
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Financial Intelligence",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = "Deterministic statistical insights & subscriptions",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { viewModel.refreshAnalytics() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh Insights", tint = ArthGold)
                    }
                }
            }

            // Month-End Cash Position Forecast Card
            item {
                forecast?.let { f ->
                    ForecastCard(forecast = f)
                }
            }

            // Category Distribution Donut Visualization
            item {
                if (categoryBreakdown.isNotEmpty()) {
                    CategoryBreakdownCard(slices = categoryBreakdown)
                }
            }

            // Price Hike & Recurring Subscriptions Alert Banner
            item {
                val priceHikeItems = recurringList.filter { it.isPriceHike }
                if (priceHikeItems.isNotEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, ArthCrimson.copy(alpha = 0.3f)),
                        colors = CardDefaults.cardColors(containerColor = ArthCrimson.copy(alpha = 0.08f))
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = ArthCrimson)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Silent Price Hike Detected!",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = ArthCrimson,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            priceHikeItems.forEach { item ->
                                Text(
                                    text = "• ${item.merchant}: Increased by %.1f%% (from ₹%.0f to ₹%.0f)".format(
                                        item.priceHikePercentage,
                                        item.previousAmount ?: 0.0,
                                        item.currentAmount
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }

            // Section: Detected Recurring Subscriptions
            item {
                Text(
                    text = "Recurring Subscriptions & Outflows (${recurringList.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
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
                items(recurringList) { item ->
                    RecurringItemCard(item = item)
                }
            }

            // Section: Anomalies (F3.3)
            if (anomalies.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Unusual Spending (${anomalies.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                items(anomalies) { anomaly ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, ArthGold.copy(alpha = 0.3f)),
                        colors = CardDefaults.cardColors(containerColor = ArthGold.copy(alpha = 0.1f))
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.NotificationsActive, contentDescription = null, tint = ArthGold)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "${anomaly.transaction.merchant} • ₹%.0f".format(anomaly.transaction.amount),
                                    fontWeight = FontWeight.Bold
                                )
                                Text(anomaly.reason, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            // Section: Possible duplicate charges (F3.4)
            if (duplicates.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Possible Duplicate Charges (${duplicates.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                items(duplicates) { txn ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, ArthCrimson.copy(alpha = 0.3f)),
                        colors = CardDefaults.cardColors(containerColor = ArthCrimson.copy(alpha = 0.08f))
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = ArthCrimson)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "${txn.merchant} • ₹%.0f".format(txn.amount),
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Charged twice for the same amount within 24 hours",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(32.dp)) }
        }
    }
}

@Composable
fun ForecastCard(forecast: MonthEndForecast) {
    val progress = if (forecast.totalIncomeSoFar > 0) {
        (forecast.totalSpentSoFar / forecast.totalIncomeSoFar).toFloat().coerceIn(0f, 1f)
    } else {
        0.5f
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(ArthEmerald.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.TrendingUp, contentDescription = null, tint = ArthEmerald, modifier = Modifier.size(20.dp))
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Month-End Cash Forecast", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = "${forecast.daysRemainingInMonth} days left",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text("PROJECTED MONTH-END OUTFLOW", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "₹%.2f".format(forecast.projectedSpentMonthEnd),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.ExtraBold,
                color = ArthGold
            )

            Spacer(modifier = Modifier.height(14.dp))

            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp)),
                color = if (progress > 0.8f) ArthCrimson else ArthEmerald,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), shape = RoundedCornerShape(12.dp))
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("DAILY VELOCITY", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("₹%.0f/day".format(forecast.dailySpendVelocity), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                }
                Column {
                    Text("COMMITTED", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("₹%.0f".format(forecast.committedRecurringTotal), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                }
                Column {
                    Text("SPENT SO FAR", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("₹%.0f".format(forecast.totalSpentSoFar), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun CategoryBreakdownCard(slices: List<CategorySlice>) {
    val sliceColors = listOf(ArthGold, ArthEmerald, ArthIndigo, ArthCrimson, ArthGoldLight, ArthEmeraldLight)
    val totalSpent = slices.sumOf { it.total }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.PieChart, contentDescription = null, tint = ArthIndigo)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Category Spending Distribution", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                // Donut Canvas
                Box(
                    modifier = Modifier.size(130.dp),
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
                        Text("TOTAL", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("₹%.0f".format(totalSpent), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    }
                }

                // Legend
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    slices.forEachIndexed { idx, slice ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(sliceColors[idx % sliceColors.size])
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "${slice.category} (${"%.0f".format(slice.fraction * 100)}%)",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RecurringItemCard(item: RecurringItem) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(ArthIndigo.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Repeat, contentDescription = null, tint = ArthIndigo, modifier = Modifier.size(20.dp))
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(item.merchant, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    "Every ~${item.frequencyDays} days • ${item.category}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "₹%.2f".format(item.currentAmount),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = ArthIndigo
                )
                if (item.isPriceHike) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = ArthCrimson.copy(alpha = 0.18f)
                    ) {
                        Text(
                            "+%.1f%% Hike".format(item.priceHikePercentage),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = ArthCrimson,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

