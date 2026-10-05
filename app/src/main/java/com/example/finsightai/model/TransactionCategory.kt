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
        defaultMonthlyBudget = 350.0,
        colorHex = "#FF7043"
    ),
    TECH_GADGETS(
        displayName = "Tech & Gadgets",
        iconName = "devices",
        defaultMonthlyBudget = 250.0,
        colorHex = "#42A5F5"
    ),
    HOUSING_RENT(
        displayName = "Rent & Housing",
        iconName = "home",
        defaultMonthlyBudget = 1000.0,
        colorHex = "#7E57C2"
    ),
    TRANSPORT(
        displayName = "Transport & Commute",
        iconName = "commute",
        defaultMonthlyBudget = 150.0,
        colorHex = "#26A69A"
    ),
    SUBSCRIPTIONS(
        displayName = "Subscriptions",
        iconName = "subscriptions",
        defaultMonthlyBudget = 100.0,
        colorHex = "#AB47BC"
    ),
    GROCERIES(
        displayName = "Groceries & Essentials",
        iconName = "shopping_cart",
        defaultMonthlyBudget = 300.0,
        colorHex = "#66BB6A"
    ),
    LIFESTYLE(
        displayName = "Lifestyle & Leisure",
        iconName = "sports_esports",
        defaultMonthlyBudget = 200.0,
        colorHex = "#FFA726"
    ),
    SALARY(
        displayName = "Salary & Income",
        iconName = "account_balance_wallet",
        defaultMonthlyBudget = 0.0,
        colorHex = "#2E7D32"
    )
}
