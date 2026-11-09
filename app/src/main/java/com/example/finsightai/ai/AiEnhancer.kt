package com.example.finsightai.ai

import com.example.finsightai.ai.backend.AiRequest
import com.example.finsightai.model.AiInsight
import com.example.finsightai.model.GoalProjection
import com.example.finsightai.model.SimulationResult
import com.example.finsightai.model.TransactionCategory
import com.example.finsightai.util.Money
import java.util.Locale

/** Text produced by a generative backend, plus which backend produced it. */
data class GeneratedText(val text: String, val by: String)

/**
 * Something that can turn an [AiRequest] into text, or decline (null) so the deterministic
 * engines' wording stays. [AiBackendRouter] is the production implementation.
 */
fun interface GenerativeSource {
    suspend fun generate(request: AiRequest): GeneratedText?
}

/** Never generates anything: deterministic wording is always used. */
object NoGenerativeSource : GenerativeSource {
    override suspend fun generate(request: AiRequest): GeneratedText? = null
}

data class InsightRewrite(val description: String, val actionSuggestion: String?, val by: String)

/**
 * Binds the generative backends into the three AI features (nudges, what-if simulator, goal
 * milestone context) *without* touching the deterministic engines: the engines still compute every
 * number and produce the default wording; this class only asks a model to re-phrase that
 * wording from the engine's own facts. Anything that fails, is empty, or mentions a money figure
 * that was not in the facts is discarded (returns null) and the deterministic text stays.
 */
class AiEnhancer(private val source: GenerativeSource) {

    suspend fun rewriteInsight(insight: AiInsight, monthlyIncome: Double): InsightRewrite? {
        val facts = buildString {
            append("Monthly take-home income: ${money(monthlyIncome)}\n")
            append("Insight type: ${insight.type.name.lowercase(Locale.US).replace('_', ' ')}\n")
            append("Headline: ${insight.title}\n")
            append("Findings: ${insight.description}\n")
            insight.actionSuggestion?.let { append("Suggested action: $it\n") }
            insight.impactAmount?.let { append("Amount at stake: ${money(it)}\n") }
        }
        val request = AiRequest(
            system = SYSTEM_PROMPT,
            user = "$facts\nRewrite the findings and the suggested action in a warm, specific voice for the user. " +
                "Reply in exactly two lines:\nINSIGHT: <at most two sentences>\nACTION: <one concrete sentence>",
            maxTokens = 160,
        )
        val generated = source.generate(request) ?: return null
        val cleaned = OutputCleaner.clean(generated.text)
        val (insightText, actionText) = parseTwoLines(cleaned)
        val description = insightText?.takeIf { it.isNotBlank() } ?: return null
        if (!NumberGuard.isGrounded(description + " " + (actionText ?: ""), facts)) return null
        return InsightRewrite(
            description = description,
            actionSuggestion = actionText?.takeIf { it.isNotBlank() } ?: insight.actionSuggestion,
            by = generated.by,
        )
    }

    suspend fun explainSimulation(result: SimulationResult): GeneratedText? {
        if (result.monthlySavings <= 0.5) return null // engine already explains the empty state
        val cuts = result.targetReductions
            .filter { it.value > 0f }
            .entries.joinToString("; ") { (c, f) -> "${categoryName(c)} cut ${(f * 100).toInt()}%" }
        val facts = buildString {
            append("Planned spending cuts: $cuts\n")
            append("Freed cashflow per month: ${money(result.monthlySavings)}\n")
            append("Savings after 3 months: ${money(result.projectedSavings3M)}\n")
            append("Savings after 6 months: ${money(result.projectedSavings6M)}\n")
            append("Savings after 12 months: ${money(result.projectedSavings12M)} (4.5% yearly yield assumed)\n")
        }
        val request = AiRequest(
            system = SYSTEM_PROMPT,
            user = "$facts\nExplain in two or three sentences what these cuts mean for the user, in plain language.",
            maxTokens = 140,
        )
        val generated = source.generate(request) ?: return null
        val text = OutputCleaner.clean(generated.text)
        if (text.isBlank() || !NumberGuard.isGrounded(text, facts)) return null
        return GeneratedText(text, generated.by)
    }

    suspend fun goalMilestoneContext(projection: GoalProjection, freedMonthly: Double): GeneratedText? {
        val goal = projection.goal
        val facts = buildString {
            append("Savings goal: ${goal.title}\n")
            append("Target: ${money(goal.targetAmount)}; saved so far: ${money(goal.currentSaved)}\n")
            append("Remaining: ${money(projection.remainingAmount)}\n")
            append("Progress: ${(projection.progressPercentage * 100).toInt()}%\n")
            append("Extra freed cashflow per month from the simulator: ${money(freedMonthly)}\n")
            append("Projected completion: ${projection.formattedTargetDate} (${projection.monthsToReach} months)\n")
        }
        val request = AiRequest(
            system = SYSTEM_PROMPT,
            user = "$facts\nWrite one encouraging sentence (at most 30 words) about the next milestone for this goal.",
            maxTokens = 70,
        )
        val generated = source.generate(request) ?: return null
        val text = OutputCleaner.clean(generated.text)
        if (text.isBlank() || !NumberGuard.isGrounded(text, facts)) return null
        return GeneratedText(text, generated.by)
    }

    private fun parseTwoLines(text: String): Pair<String?, String?> {
        var insight: String? = null
        var action: String? = null
        text.lines().forEach { line ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith("INSIGHT:", ignoreCase = true) -> insight = trimmed.substringAfter(':').trim()
                trimmed.startsWith("ACTION:", ignoreCase = true) -> action = trimmed.substringAfter(':').trim()
            }
        }
        // Small models sometimes ignore the format: accept the whole text as the insight.
        if (insight == null && action == null && text.isNotBlank()) insight = text.lines().joinToString(" ") { it.trim() }.trim()
        return insight to action
    }

    private fun categoryName(category: TransactionCategory): String = category.displayName

    private fun money(value: Double): String = Money.format(value)

    companion object {
        const val SYSTEM_PROMPT =
            "You are FinSight, a concise personal-finance coach for early-career earners. " +
                "Use only the numbers provided; never invent amounts, rates, products or guarantees. " +
                "All amounts are in Philippine pesos (${Money.SYMBOL}); write them with the ${Money.SYMBOL} sign, never with a dollar sign. " +
                "Plain text only, no markdown, no emojis."
    }
}

/** Removes reasoning blocks / markdown / quoting that small models like to emit, and bounds the length. */
object OutputCleaner {
    private val thinkBlock = Regex("<think>.*?</think>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val danglingThink = Regex("^.*?</think>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val markdown = Regex("[*_`#>]+")
    private const val MAX_CHARS = 600

    fun clean(raw: String): String {
        var text = raw.replace(thinkBlock, "")
        if (text.contains("</think>", ignoreCase = true)) text = text.replace(danglingThink, "")
        text = text.replace(markdown, "").trim().trim('"').trim()
        if (text.length > MAX_CHARS) {
            val cut = text.take(MAX_CHARS)
            text = cut.substringBeforeLast('.', cut).let { if (it.length < 40) cut else "$it." }
        }
        return text
    }
}

/**
 * Hallucination guard for money: every money figure in a generated text must appear (at the same
 * value, or its rounded value) in the facts that were sent. Figures are the only machine-checkable
 * claim a finance nudge makes; wording is left to the model.
 */
object NumberGuard {
    // Peso is the app currency, written "₱" or "PHP"; a model that still writes "$" is checked the same way.
    private val money = Regex("""(?:₱|\$|\bPHP)\s?([0-9][0-9,]*(?:\.[0-9]+)?)""", RegexOption.IGNORE_CASE)

    fun isGrounded(generated: String, facts: String): Boolean {
        val allowed = money.findAll(facts).mapNotNull { it.groupValues[1].replace(",", "").toDoubleOrNull() }.toList()
        return money.findAll(generated).all { match ->
            val value = match.groupValues[1].replace(",", "").trimEnd('.').toDoubleOrNull() ?: return@all true
            allowed.any { kotlin.math.abs(it - value) < 0.51 }
        }
    }
}
