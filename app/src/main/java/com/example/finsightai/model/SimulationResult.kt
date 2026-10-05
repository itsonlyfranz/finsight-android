package com.example.finsightai.model

data class SimulationResult(
    val targetReductions: Map<TransactionCategory, Float>,
    val monthlySavings: Double,
    val projectedSavings3M: Double,
    val projectedSavings6M: Double,
    val projectedSavings12M: Double,
    val aiExplanation: String,
    val goalProjections: List<GoalProjection> = emptyList()
)
