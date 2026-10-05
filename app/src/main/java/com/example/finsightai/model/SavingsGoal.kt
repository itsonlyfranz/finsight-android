package com.example.finsightai.model

data class SavingsGoal(
    val id: String,
    val title: String,
    val targetAmount: Double,
    val currentSaved: Double,
    val icon: String,
    val description: String
) {
    companion object {
        val PREPOPULATED_GOALS = listOf(
            SavingsGoal(
                id = "goal_emergency",
                title = "Emergency Reserve (3 Months)",
                targetAmount = 5400.0,
                currentSaved = 2100.0,
                icon = "🛡️",
                description = "Living expenses cushion for peace of mind"
            ),
            SavingsGoal(
                id = "goal_dev_rig",
                title = "M4 Pro Dev Rig & Setup",
                targetAmount = 3200.0,
                currentSaved = 800.0,
                icon = "💻",
                description = "Next-gen workspace & hardware upgrade"
            ),
            SavingsGoal(
                id = "goal_tokyo_tour",
                title = "Tokyo Cafe & Tech Tour",
                targetAmount = 2500.0,
                currentSaved = 450.0,
                icon = "✈️",
                description = "Akihabara tech hunt & Tokyo pour-overs"
            )
        )
    }
}

data class GoalProjection(
    val goal: SavingsGoal,
    val remainingAmount: Double,
    val progressPercentage: Float,
    val monthsToReach: Int,
    val formattedTargetDate: String,
    val badgeText: String
)
