package com.example.finsightai.model

data class SavingsGoal(
    val id: String,
    val title: String,
    val targetAmount: Double,
    val currentSaved: Double,
    val icon: String,
    val description: String
)

data class GoalProjection(
    val goal: SavingsGoal,
    val remainingAmount: Double,
    val progressPercentage: Float,
    val monthsToReach: Int,
    val formattedTargetDate: String,
    val badgeText: String,
    /** Short milestone commentary written by a generative backend; null = none. */
    val aiContext: String? = null
)
