package com.example.finsightai

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.finsightai.theme.BrightEmerald
import com.example.finsightai.theme.EmeraldContainer
import com.example.finsightai.theme.SlateBorder
import com.example.finsightai.theme.SlateSurface
import com.example.finsightai.theme.TextMuted
import com.example.finsightai.theme.TextSecondary
import com.example.finsightai.ui.screens.AiInsightsScreen
import com.example.finsightai.ui.screens.DashboardScreen
import com.example.finsightai.ui.screens.ForwardSimulatorScreen
import com.example.finsightai.ui.screens.TransactionHistoryScreen
import com.example.finsightai.ui.viewmodel.FinSightViewModel
import androidx.compose.ui.platform.LocalContext

enum class FinSightTab(
    val title: String,
    val icon: ImageVector
) {
    DASHBOARD("Dashboard", Icons.Filled.Dashboard),
    TRANSACTIONS("Transactions", Icons.AutoMirrored.Filled.ReceiptLong),
    INSIGHTS("AI Insights", Icons.Filled.AutoAwesome),
    SIMULATOR("Simulator", Icons.Filled.Timeline)
}

@Composable
fun MainNavigation(
    viewModel: FinSightViewModel = viewModel(
        factory = FinSightViewModel.factory(LocalContext.current.applicationContext)
    )
) {
    var selectedTab by rememberSaveable { mutableStateOf(FinSightTab.DASHBOARD) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(width = 1.dp, color = SlateBorder, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                    .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)),
                containerColor = SlateSurface,
                tonalElevation = 8.dp
            ) {
                FinSightTab.values().forEach { tab ->
                    val isSelected = tab == selectedTab

                    NavigationBarItem(
                        selected = isSelected,
                        onClick = { selectedTab = tab },
                        icon = {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = tab.title
                            )
                        },
                        label = {
                            Text(
                                text = tab.title,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 11.sp
                                )
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = BrightEmerald,
                            selectedTextColor = BrightEmerald,
                            indicatorColor = EmeraldContainer,
                            unselectedIconColor = TextMuted,
                            unselectedTextColor = TextSecondary
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                FinSightTab.DASHBOARD -> DashboardScreen(
                    viewModel = viewModel,
                    onNavigateToInsights = { selectedTab = FinSightTab.INSIGHTS },
                    onNavigateToSimulator = { selectedTab = FinSightTab.SIMULATOR }
                )
                FinSightTab.TRANSACTIONS -> TransactionHistoryScreen(
                    viewModel = viewModel
                )
                FinSightTab.INSIGHTS -> AiInsightsScreen(
                    viewModel = viewModel
                )
                FinSightTab.SIMULATOR -> ForwardSimulatorScreen(
                    viewModel = viewModel
                )
            }
        }
    }
}
