package com.example.finsightai.model

enum class InsightType {
    RISK_NUDGE,
    GROWTH_FOCUS,
    TIP
}

enum class Severity {
    LOW,
    MEDIUM,
    HIGH
}

data class AiInsight(
    val id: String,
    val title: String,
    val description: String,
    val type: InsightType,
    val impactAmount: Double? = null,
    val actionSuggestion: String? = null,
    val severity: Severity = Severity.LOW
)
