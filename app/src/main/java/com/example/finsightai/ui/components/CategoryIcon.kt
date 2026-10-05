package com.example.finsightai.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Commute
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.finsightai.model.TransactionCategory
import com.example.finsightai.theme.ChartCafe
import com.example.finsightai.theme.ChartGroceries
import com.example.finsightai.theme.ChartLifestyle
import com.example.finsightai.theme.ChartRent
import com.example.finsightai.theme.ChartSalary
import com.example.finsightai.theme.ChartSubscriptions
import com.example.finsightai.theme.ChartTech
import com.example.finsightai.theme.ChartTransport

fun TransactionCategory.categoryColor(): Color = when (this) {
    TransactionCategory.CAFES_DINING -> ChartCafe
    TransactionCategory.TECH_GADGETS -> ChartTech
    TransactionCategory.HOUSING_RENT -> ChartRent
    TransactionCategory.TRANSPORT -> ChartTransport
    TransactionCategory.SUBSCRIPTIONS -> ChartSubscriptions
    TransactionCategory.GROCERIES -> ChartGroceries
    TransactionCategory.LIFESTYLE -> ChartLifestyle
    TransactionCategory.SALARY -> ChartSalary
}

fun TransactionCategory.categoryIcon(): ImageVector = when (this) {
    TransactionCategory.CAFES_DINING -> Icons.Filled.LocalCafe
    TransactionCategory.TECH_GADGETS -> Icons.Filled.Devices
    TransactionCategory.HOUSING_RENT -> Icons.Filled.Home
    TransactionCategory.TRANSPORT -> Icons.Filled.Commute
    TransactionCategory.SUBSCRIPTIONS -> Icons.Filled.Subscriptions
    TransactionCategory.GROCERIES -> Icons.Filled.ShoppingCart
    TransactionCategory.LIFESTYLE -> Icons.Filled.SportsEsports
    TransactionCategory.SALARY -> Icons.Filled.AccountBalanceWallet
}
