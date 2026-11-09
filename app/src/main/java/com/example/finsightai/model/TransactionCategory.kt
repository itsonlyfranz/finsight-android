package com.example.finsightai.model

enum class TransactionCategory(
    val displayName: String,
    val iconName: String,
    val defaultMonthlyBudget: Double,
    val colorHex: String
) {
    CAFES_DINING(
        displayName = "Cafes & Dining",
        iconName = "local_cafe",
        defaultMonthlyBudget = 5000.0,
        colorHex = "#FF7043"
    ),
    TECH_GADGETS(
        displayName = "Tech & Gadgets",
        iconName = "devices",
        defaultMonthlyBudget = 3500.0,
        colorHex = "#42A5F5"
    ),
    HOUSING_RENT(
        displayName = "Rent & Housing",
        iconName = "home",
        defaultMonthlyBudget = 14000.0,
        colorHex = "#7E57C2"
    ),
    TRANSPORT(
        displayName = "Transport & Commute",
        iconName = "commute",
        defaultMonthlyBudget = 2100.0,
        colorHex = "#26A69A"
    ),
    SUBSCRIPTIONS(
        displayName = "Subscriptions",
        iconName = "subscriptions",
        defaultMonthlyBudget = 2500.0,
        colorHex = "#AB47BC"
    ),
    GROCERIES(
        displayName = "Groceries & Essentials",
        iconName = "shopping_cart",
        defaultMonthlyBudget = 4200.0,
        colorHex = "#66BB6A"
    ),
    LIFESTYLE(
        displayName = "Lifestyle & Leisure",
        iconName = "sports_esports",
        defaultMonthlyBudget = 2800.0,
        colorHex = "#FFA726"
    ),
    SALARY(
        displayName = "Salary & Income",
        iconName = "account_balance_wallet",
        defaultMonthlyBudget = 0.0,
        colorHex = "#2E7D32"
    )
}
