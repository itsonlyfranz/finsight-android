package com.example.finsightai.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.TipsAndUpdates
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.finsightai.model.AiInsight
import com.example.finsightai.model.InsightType
import com.example.finsightai.model.Severity
import com.example.finsightai.theme.AmberTip
import com.example.finsightai.theme.AmberTipContainer
import com.example.finsightai.theme.BrightEmerald
import com.example.finsightai.theme.CoralRed
import com.example.finsightai.theme.CoralRedBorder
import com.example.finsightai.theme.CoralRedContainer
import com.example.finsightai.theme.EmeraldBorder
import com.example.finsightai.theme.EmeraldContainer
import com.example.finsightai.theme.EmeraldPrimary
import com.example.finsightai.theme.SlateBorder
import com.example.finsightai.theme.SlateSurface
import com.example.finsightai.theme.SlateSurfaceVariant
import com.example.finsightai.theme.TextMuted
import com.example.finsightai.theme.TextPrimary
import com.example.finsightai.theme.TextSecondary
import com.example.finsightai.ui.viewmodel.FinSightViewModel
import java.util.Locale

private enum class InsightFilterTab(val title: String) {
    ALL("All"),
    RISK_NUDGES("Risk Nudges"),
    GROWTH_FOCUS("Growth Focus"),
    PRO_TIPS("Pro Tips")
}

@Composable
fun AiInsightsScreen(
    viewModel: FinSightViewModel,
    modifier: Modifier = Modifier
) {
    val insights by viewModel.insights.collectAsStateWithLifecycle()
    var selectedTab by remember { mutableStateOf(InsightFilterTab.ALL) }

    val filteredInsights = remember(insights, selectedTab) {
        when (selectedTab) {
            InsightFilterTab.ALL -> insights
            InsightFilterTab.RISK_NUDGES -> insights.filter { it.type == InsightType.RISK_NUDGE }
            InsightFilterTab.GROWTH_FOCUS -> insights.filter { it.type == InsightType.GROWTH_FOCUS }
            InsightFilterTab.PRO_TIPS -> insights.filter { it.type == InsightType.TIP }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    tint = BrightEmerald,
                    modifier = Modifier.size(24.dp)
                )
                Column {
                    Text(
                        text = "AI Financial Intelligence",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    )
                    Text(
                        text = "Real-time behavioral nudges & wealth accelerators",
                        style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                    )
                }
            }
        }

        // Filter Tabs Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            InsightFilterTab.values().forEach { tab ->
                val isSelected = tab == selectedTab
                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .border(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) BrightEmerald else SlateBorder,
                            shape = RoundedCornerShape(20.dp)
                        )
                        .clickable { selectedTab = tab },
                    color = if (isSelected) EmeraldContainer else SlateSurface,
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Text(
                        text = tab.title,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelMedium.copy(
                            color = if (isSelected) BrightEmerald else TextSecondary,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Insights List
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(filteredInsights, key = { it.id }) { insight ->
                InsightCard(insight = insight)
            }
        }
    }
}

@Composable
private fun InsightCard(insight: AiInsight) {
    val (borderColor, containerColor, iconVector, iconTint, badgeText, badgeColor) = when (insight.type) {
        InsightType.RISK_NUDGE -> {
            val severityLabel = when (insight.severity) {
                Severity.HIGH -> "High Risk"
                Severity.MEDIUM -> "Risk Nudge"
                Severity.LOW -> "Caution"
            }
            InsightVisuals(
                borderColor = CoralRedBorder,
                containerColor = CoralRedContainer.copy(alpha = 0.45f),
                icon = Icons.Filled.Warning,
                iconTint = CoralRed,
                badgeText = severityLabel,
                badgeColor = CoralRed
            )
        }
        InsightType.GROWTH_FOCUS -> {
            InsightVisuals(
                borderColor = EmeraldBorder,
                containerColor = EmeraldContainer.copy(alpha = 0.35f),
                icon = Icons.AutoMirrored.Filled.TrendingUp,
                iconTint = BrightEmerald,
                badgeText = "Growth Focus",
                badgeColor = BrightEmerald
            )
        }
        InsightType.TIP -> {
            InsightVisuals(
                borderColor = SlateBorder,
                containerColor = AmberTipContainer.copy(alpha = 0.3f),
                icon = Icons.Filled.Lightbulb,
                iconTint = AmberTip,
                badgeText = "Pro Tip",
                badgeColor = AmberTip
            )
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, borderColor, RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = SlateSurface)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header with badge and impact
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
                            .background(containerColor),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = iconVector,
                            contentDescription = null,
                            tint = iconTint,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(containerColor)
                            .border(1.dp, borderColor.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = badgeText,
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = badgeColor,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp
                            )
                        )
                    }
                }

                if (insight.impactAmount != null && insight.impactAmount > 0) {
                    Text(
                        text = "$${String.format(Locale.US, "%,.2f", insight.impactAmount)}",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    )
                }
            }

            // Title
            Text(
                text = insight.title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            )

            // Description
            Text(
                text = insight.description,
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = TextSecondary,
                    lineHeight = 22.sp
                )
            )

            // Action Suggestion box
            if (!insight.actionSuggestion.isNullOrEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(SlateSurfaceVariant)
                        .border(1.dp, SlateBorder, RoundedCornerShape(12.dp))
                        .padding(14.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Filled.TipsAndUpdates,
                            contentDescription = null,
                            tint = BrightEmerald,
                            modifier = Modifier.size(18.dp)
                        )
                        Column {
                            Text(
                                text = "Actionable Suggestion",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = BrightEmerald,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.5.sp
                                )
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = insight.actionSuggestion,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = TextPrimary,
                                    lineHeight = 18.sp
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class InsightVisuals(
    val borderColor: Color,
    val containerColor: Color,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val iconTint: Color,
    val badgeText: String,
    val badgeColor: Color
)
