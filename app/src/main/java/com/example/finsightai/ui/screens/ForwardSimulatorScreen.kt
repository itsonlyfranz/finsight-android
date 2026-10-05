package com.example.finsightai.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.finsightai.model.SimulationResult
import com.example.finsightai.model.TransactionCategory
import com.example.finsightai.theme.BrightEmerald
import com.example.finsightai.theme.CyanAccent
import com.example.finsightai.theme.EmeraldBorder
import com.example.finsightai.theme.EmeraldContainer
import com.example.finsightai.theme.EmeraldPrimary
import com.example.finsightai.theme.SlateBorder
import com.example.finsightai.theme.SlateSurface
import com.example.finsightai.theme.SlateSurfaceVariant
import com.example.finsightai.theme.TextMuted
import com.example.finsightai.theme.TextPrimary
import com.example.finsightai.theme.TextSecondary
import com.example.finsightai.ui.components.categoryColor
import com.example.finsightai.ui.components.categoryIcon
import com.example.finsightai.ui.viewmodel.FinSightViewModel
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun ForwardSimulatorScreen(
    viewModel: FinSightViewModel,
    modifier: Modifier = Modifier
) {
    val simulationResult by viewModel.simulationResult.collectAsStateWithLifecycle()
    val sliderReductions by viewModel.sliderReductions.collectAsStateWithLifecycle()
    val categoryBreakdown by viewModel.categoryBreakdown.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        // Header
        item {
            HeaderSection()
        }

        // Real-Time Projection Output Card
        item {
            ProjectionsSummaryCard(simulationResult = simulationResult)
        }

        // AI Context Explanation Card
        item {
            AiContextCard(aiExplanation = simulationResult.aiExplanation)
        }

        // Interactive Sliders Section
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, SlateBorder, RoundedCornerShape(20.dp)),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = SlateSurface)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Lifestyle Inflation Levers",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                            )
                            Text(
                                text = "Adjust cutbacks (0% to 50%) to observe compound gains",
                                style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                            )
                        }
                    }

                    // 1. Dining & Cafes Slider
                    ReductionSliderItem(
                        category = TransactionCategory.CAFES_DINING,
                        baselineSpend = categoryBreakdown[TransactionCategory.CAFES_DINING] ?: 0.0,
                        currentReduction = sliderReductions[TransactionCategory.CAFES_DINING] ?: 0f,
                        onValueChange = { viewModel.updateSliderReduction(TransactionCategory.CAFES_DINING, it) }
                    )

                    // 2. Tech & Gadgets Slider
                    ReductionSliderItem(
                        category = TransactionCategory.TECH_GADGETS,
                        baselineSpend = categoryBreakdown[TransactionCategory.TECH_GADGETS] ?: 0.0,
                        currentReduction = sliderReductions[TransactionCategory.TECH_GADGETS] ?: 0f,
                        onValueChange = { viewModel.updateSliderReduction(TransactionCategory.TECH_GADGETS, it) }
                    )

                    // 3. Subscriptions Slider
                    ReductionSliderItem(
                        category = TransactionCategory.SUBSCRIPTIONS,
                        baselineSpend = categoryBreakdown[TransactionCategory.SUBSCRIPTIONS] ?: 0.0,
                        currentReduction = sliderReductions[TransactionCategory.SUBSCRIPTIONS] ?: 0f,
                        onValueChange = { viewModel.updateSliderReduction(TransactionCategory.SUBSCRIPTIONS, it) }
                    )
                }
            }
        }
    }
}

@Composable
private fun HeaderSection() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Timeline,
                    contentDescription = null,
                    tint = BrightEmerald,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = "Forward Simulator",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Model compound savings from small habit changes",
                style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
            )
        }
    }
}

@Composable
private fun ReductionSliderItem(
    category: TransactionCategory,
    baselineSpend: Double,
    currentReduction: Float,
    onValueChange: (Float) -> Unit
) {
    val color = category.categoryColor()
    val reductionPct = (currentReduction * 100).roundToInt()
    val monthlySaved = baselineSpend * currentReduction

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(color.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = category.categoryIcon(),
                        contentDescription = null,
                        tint = color,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Column {
                    Text(
                        text = category.displayName,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                    )
                    Text(
                        text = "Current: $${formatCurrency(baselineSpend)}/mo",
                        style = MaterialTheme.typography.bodySmall.copy(color = TextMuted)
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = EmeraldContainer,
                modifier = Modifier.border(1.dp, EmeraldBorder, RoundedCornerShape(8.dp))
            ) {
                Text(
                    text = "$reductionPct% cut (-$${formatCurrency(monthlySaved)}/mo)",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = BrightEmerald,
                        fontWeight = FontWeight.Bold
                    )
                )
            }
        }

        Slider(
            value = currentReduction,
            onValueChange = onValueChange,
            valueRange = 0f..0.50f, // 0% to 50% cutback
            steps = 9, // 0%, 5%, 10%, 15%, 20%, 25%, 30%, 35%, 40%, 45%, 50%
            colors = SliderDefaults.colors(
                thumbColor = BrightEmerald,
                activeTrackColor = EmeraldPrimary,
                inactiveTrackColor = SlateSurfaceVariant
            )
        )
    }
}

@Composable
private fun ProjectionsSummaryCard(simulationResult: SimulationResult) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, EmeraldBorder, RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = SlateSurface)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column {
                    Text(
                        text = "MONTHLY FREED CASHFLOW",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = TextMuted,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 1.sp
                        )
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "+$${formatCurrency(simulationResult.monthlySavings)}/mo",
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = BrightEmerald
                        )
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(EmeraldContainer)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Savings,
                            contentDescription = null,
                            tint = BrightEmerald,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "4.5% APY compounding",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = BrightEmerald,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                }
            }

            // 3 Projections in columns
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ProjectionColumnItem(
                    label = "3-MONTH",
                    value = simulationResult.projectedSavings3M,
                    modifier = Modifier.weight(1f)
                )
                ProjectionColumnItem(
                    label = "6-MONTH",
                    value = simulationResult.projectedSavings6M,
                    modifier = Modifier.weight(1f)
                )
                ProjectionColumnItem(
                    label = "1-YEAR",
                    value = simulationResult.projectedSavings12M,
                    highlight = true,
                    modifier = Modifier.weight(1.1f)
                )
            }
        }
    }
}

@Composable
private fun ProjectionColumnItem(
    label: String,
    value: Double,
    highlight: Boolean = false,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .border(
                width = 1.dp,
                color = if (highlight) EmeraldPrimary else SlateBorder,
                shape = RoundedCornerShape(14.dp)
            ),
        color = if (highlight) EmeraldContainer.copy(alpha = 0.5f) else SlateSurfaceVariant,
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    color = if (highlight) BrightEmerald else TextMuted,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    letterSpacing = 0.5.sp
                )
            )
            Text(
                text = "$${formatCurrency(value)}",
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = if (highlight) BrightEmerald else TextPrimary
                )
            )
        }
    }
}

@Composable
private fun AiContextCard(aiExplanation: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, EmeraldBorder, RoundedCornerShape(18.dp)),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = EmeraldContainer.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(EmeraldContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.AutoAwesome,
                    contentDescription = "AI Context",
                    tint = BrightEmerald,
                    modifier = Modifier.size(20.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "AI Milestone Context",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = BrightEmerald,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = aiExplanation,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = TextPrimary,
                        lineHeight = 20.sp
                    )
                )
            }
        }
    }
}

private fun formatCurrency(amount: Double): String {
    return String.format(Locale.US, "%,.2f", amount)
}
