package com.example.finsightai.testdata

import com.example.finsightai.data.DefaultFinSightRepository
import com.example.finsightai.model.SavingsGoal
import com.example.finsightai.model.Transaction
import com.example.finsightai.model.TransactionCategory
import java.time.LocalDate

/**
 * Test-only sample data. The app itself ships no demo data (a fresh install starts empty); tests that
 * need a realistic month of spending build it from here. This file is compiled into both the unit
 * test and instrumented test source sets (see `app/build.gradle.kts`).
 */
object SampleData {
    fun markSantosTransactions(): List<Transaction> {
        val now = LocalDate.now()
        val year = now.year
        val month = now.monthValue

        return listOf(
            // 1. Income (₱45,000)
            Transaction(
                id = "tx_salary_1",
                title = "Apex Systems SWE Paycheck",
                amount = 45000.0,
                category = TransactionCategory.SALARY,
                date = LocalDate.of(year, month, 1),
                isExpense = false,
                notes = "Monthly net direct deposit - Junior SWE"
            ),

            // 2. Rent (₱13,500)
            Transaction(
                id = "tx_rent_1",
                title = "Apex Apartments Monthly Rent",
                amount = 13500.0,
                category = TransactionCategory.HOUSING_RENT,
                date = LocalDate.of(year, month, 1),
                isExpense = true,
                notes = "2B2B split with roommate"
            ),

            // 3. Cafe Visits (₱4,000 across 14 transactions)
            Transaction(id = "tx_cafe_1", title = "Blue Bottle Pour Over & Pastry", amount = 320.00, category = TransactionCategory.CAFES_DINING, date = LocalDate.of(year, month, 2)),
            Transaction(id = "tx_cafe_2", title = "Philz Mint Mojito Iced Coffee", amount = 180.00, category = TransactionCategory.CAFES_DINING, date = LocalDate.of(year, month, 3)),
            Transaction(id = "tx_cafe_3", title = "Sightglass Espresso & Croissant", amount = 260.00, category = TransactionCategory.CAFES_DINING, date = LocalDate.of(year, month, 5)),
            Transaction(id = "tx_cafe_4", title = "Local Artisan Roasters Oat Latte", amount = 170.00, category = TransactionCategory.CAFES_DINING, date = LocalDate.of(year, month, 7)),
            Transaction(id = "tx_cafe_5", title = "Matcha Cafe Maiko Soft Serve & Drink", amount = 280.00, category = TransactionCategory.CAFES_DINING, date = LocalDate.of(year, month, 8)),
            Transaction(id = "tx_cafe_6", title = "Equator Coffees Cold Brew", amount = 160.00, category = TransactionCategory.CAFES_DINING, date = LocalDate.of(year, month, 10)),
            Transaction(id = "tx_cafe_7", title = "Ritual Coffee Roasters Drip", amount = 150.00, category = TransactionCategory.CAFES_DINING, date = LocalDate.of(year, month, 12)),
            Transaction(id = "tx_cafe_8", title = "Verve Coffee Roasters Flat White", amount = 175.00, category = TransactionCategory.CAFES_DINING, date = LocalDate.of(year, month, 14)),
            Transaction(id = "tx_cafe_9", title = "Weekend Coffee Tasting & Beans", amount = 650.00, category = TransactionCategory.CAFES_DINING, date = LocalDate.of(year, month, 16)),
            Transaction(id = "tx_cafe_10", title = "Andytown Snowy Plover & Muffin", amount = 245.00, category = TransactionCategory.CAFES_DINING, date = LocalDate.of(year, month, 18)),
            Transaction(id = "tx_cafe_11", title = "Cow Hollow Cafe Brunch Coffee", amount = 290.00, category = TransactionCategory.CAFES_DINING, date = LocalDate.of(year, month, 20)),
            Transaction(id = "tx_cafe_12", title = "Craft Coffee Lab Pour Over", amount = 195.00, category = TransactionCategory.CAFES_DINING, date = LocalDate.of(year, month, 22)),
            Transaction(id = "tx_cafe_13", title = "Four Barrel Espresso Bar", amount = 185.00, category = TransactionCategory.CAFES_DINING, date = LocalDate.of(year, month, 24)),
            Transaction(id = "tx_cafe_14", title = "Sunday Roastery Batch Brew & Whole Beans", amount = 740.00, category = TransactionCategory.CAFES_DINING, date = LocalDate.of(year, month, 26)),

            // 4. Dining Out (₱4,800 across 6 outings)
            Transaction(id = "tx_dining_1", title = "Ramen Nagi Rich Tonkotsu Dinner", amount = 650.00, category = TransactionCategory.CAFES_DINING, date = LocalDate.of(year, month, 4)),
            Transaction(id = "tx_dining_2", title = "Sushi Omakase Lunch Special", amount = 1250.00, category = TransactionCategory.CAFES_DINING, date = LocalDate.of(year, month, 11)),
            Transaction(id = "tx_dining_3", title = "Engineering Team Hotpot Night", amount = 850.00, category = TransactionCategory.CAFES_DINING, date = LocalDate.of(year, month, 15)),
            Transaction(id = "tx_dining_4", title = "Friday Craft Burger & Fries", amount = 480.00, category = TransactionCategory.CAFES_DINING, date = LocalDate.of(year, month, 19)),
            Transaction(id = "tx_dining_5", title = "Thai Basil Bistro Weekend Dinner", amount = 570.00, category = TransactionCategory.CAFES_DINING, date = LocalDate.of(year, month, 23)),
            Transaction(id = "tx_dining_6", title = "Artisan Sourdough Pizza with Colleagues", amount = 1000.00, category = TransactionCategory.CAFES_DINING, date = LocalDate.of(year, month, 27)),

            // 5. Tech Gadgets (₱5,900 across 3 items)
            Transaction(id = "tx_tech_1", title = "Keychron Q1 Pro Mechanical Keyboard", amount = 2950.00, category = TransactionCategory.TECH_GADGETS, date = LocalDate.of(year, month, 6), notes = "Banana switches upgrade"),
            Transaction(id = "tx_tech_2", title = "CalDigit TS4 Thunderbolt Dock", amount = 2250.00, category = TransactionCategory.TECH_GADGETS, date = LocalDate.of(year, month, 13), notes = "Dual monitor productivity"),
            Transaction(id = "tx_tech_3", title = "Anker 737 GaN 24K Power Bank", amount = 700.00, category = TransactionCategory.TECH_GADGETS, date = LocalDate.of(year, month, 21), notes = "Remote cafe charging backup"),

            // 6. Subscriptions (₱3,496 across 7 recurring services)
            Transaction(id = "tx_sub_1", title = "GitHub Copilot Pro", amount = 560.00, category = TransactionCategory.SUBSCRIPTIONS, date = LocalDate.of(year, month, 2)),
            Transaction(id = "tx_sub_2", title = "ChatGPT Plus", amount = 1100.00, category = TransactionCategory.SUBSCRIPTIONS, date = LocalDate.of(year, month, 5)),
            Transaction(id = "tx_sub_3", title = "Spotify Family Plan", amount = 349.00, category = TransactionCategory.SUBSCRIPTIONS, date = LocalDate.of(year, month, 9)),
            Transaction(id = "tx_sub_4", title = "Netflix Premium 4K", amount = 549.00, category = TransactionCategory.SUBSCRIPTIONS, date = LocalDate.of(year, month, 12)),
            Transaction(id = "tx_sub_5", title = "iCloud+ 2TB Storage", amount = 499.00, category = TransactionCategory.SUBSCRIPTIONS, date = LocalDate.of(year, month, 15)),
            Transaction(id = "tx_sub_6", title = "YouTube Premium", amount = 159.00, category = TransactionCategory.SUBSCRIPTIONS, date = LocalDate.of(year, month, 20)),
            Transaction(id = "tx_sub_7", title = "NordVPN Monthly Equivalent", amount = 280.00, category = TransactionCategory.SUBSCRIPTIONS, date = LocalDate.of(year, month, 25)),

            // 7. Groceries (₱4,200 across 4 trips)
            Transaction(id = "tx_groc_1", title = "Trader Joe's Weekly Haul", amount = 1600.00, category = TransactionCategory.GROCERIES, date = LocalDate.of(year, month, 3)),
            Transaction(id = "tx_groc_2", title = "Whole Foods Organic Market", amount = 1350.00, category = TransactionCategory.GROCERIES, date = LocalDate.of(year, month, 10)),
            Transaction(id = "tx_groc_3", title = "Safeway Essentials & Snacks", amount = 850.00, category = TransactionCategory.GROCERIES, date = LocalDate.of(year, month, 17)),
            Transaction(id = "tx_groc_4", title = "Farmer's Market Fresh Produce", amount = 400.00, category = TransactionCategory.GROCERIES, date = LocalDate.of(year, month, 24)),

            // 8. Transport (₱2,100)
            Transaction(id = "tx_trans_1", title = "Clipper Card Transit Auto-Reload", amount = 1350.00, category = TransactionCategory.TRANSPORT, date = LocalDate.of(year, month, 4)),
            Transaction(id = "tx_trans_2", title = "Uber Weekend Transit", amount = 750.00, category = TransactionCategory.TRANSPORT, date = LocalDate.of(year, month, 18))
        ).sortedByDescending { it.date }
    }

    /** Test-only savings goals (the app ships none; a fresh Simulator shows an empty goals state). */
    fun goals(): List<SavingsGoal> = listOf(
        SavingsGoal(
            id = "goal_emergency",
            title = "Emergency Reserve (3 Months)",
            targetAmount = 75600.0,
            currentSaved = 29400.0,
            icon = "🛡️",
            description = "Living expenses cushion for peace of mind"
        ),
        SavingsGoal(
            id = "goal_dev_rig",
            title = "M4 Pro Dev Rig & Setup",
            targetAmount = 44800.0,
            currentSaved = 11200.0,
            icon = "💻",
            description = "Next-gen workspace & hardware upgrade"
        ),
        SavingsGoal(
            id = "goal_tokyo_tour",
            title = "Tokyo Cafe & Tech Tour",
            targetAmount = 35000.0,
            currentSaved = 6300.0,
            icon = "✈️",
            description = "Akihabara tech hunt & Tokyo pour-overs"
        )
    )

    /** An in-memory repository pre-filled with [markSantosTransactions]. */
    fun repository(): DefaultFinSightRepository = DefaultFinSightRepository().also { repo ->
        markSantosTransactions().forEach(repo::addTransaction)
    }
}
