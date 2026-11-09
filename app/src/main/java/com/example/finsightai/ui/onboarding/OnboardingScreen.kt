package com.example.finsightai.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.finsightai.theme.BrightEmerald
import com.example.finsightai.theme.CoralRed
import com.example.finsightai.theme.EmeraldContainer
import com.example.finsightai.theme.EmeraldPrimary
import com.example.finsightai.theme.SlateBorder
import com.example.finsightai.theme.SlateSurface
import com.example.finsightai.theme.TextMuted
import com.example.finsightai.theme.TextPrimary
import com.example.finsightai.theme.TextSecondary
import com.example.finsightai.ui.viewmodel.FinSightViewModel
import com.example.finsightai.data.FinSightDatabaseHelper
import com.example.finsightai.util.Money

/** Number of intro pages; the step label reads "n/4". */
const val ONBOARDING_PAGE_COUNT = 4

/**
 * The first-launch intro: four short pages, skippable from every page. Finishing saves the monthly income
 * (required), an optional name and an optional first savings goal; skipping saves nothing and keeps the
 * defaults. Either way the intro is marked done in the view model and never comes back.
 */
@Composable
fun OnboardingScreen(
    viewModel: FinSightViewModel,
    modifier: Modifier = Modifier
) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    var name by rememberSaveable { mutableStateOf("") }
    var incomeText by rememberSaveable { mutableStateOf("") }
    var goalTitle by rememberSaveable { mutableStateOf("") }
    var goalTargetText by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    val isLast = page == ONBOARDING_PAGE_COUNT - 1

    BackHandler(enabled = page > 0) {
        error = null
        page -= 1
    }

    fun finish() {
        val income = Money.parseAmount(incomeText)
        if (income == null || income <= 0.0) {
            error = "Enter your monthly income to finish, or skip the intro to keep the default."
            return
        }
        val hasGoalInput = goalTitle.isNotBlank() || goalTargetText.isNotBlank()
        val goalTarget = Money.parseAmount(goalTargetText)
        if (hasGoalInput && (goalTitle.isBlank() || goalTarget == null || goalTarget <= 0.0)) {
            error = "A first goal needs a name and a target amount above zero. Leave both empty to skip it."
            return
        }
        val saved = viewModel.finishOnboarding(
            monthlyIncome = income,
            name = name,
            goalTitle = goalTitle,
            goalTarget = if (hasGoalInput) goalTarget else null
        )
        if (!saved) error = "Could not save these details. Check the income and goal amounts."
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("onboarding_screen")
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${page + 1}/$ONBOARDING_PAGE_COUNT",
                modifier = Modifier.testTag("onboarding_step"),
                style = MaterialTheme.typography.labelLarge.copy(
                    color = BrightEmerald,
                    fontWeight = FontWeight.Bold
                )
            )
            TextButton(
                onClick = { viewModel.skipOnboarding() },
                modifier = Modifier.testTag("onboarding_skip")
            ) {
                Text("Skip", color = TextSecondary, fontWeight = FontWeight.SemiBold)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            repeat(ONBOARDING_PAGE_COUNT) { index ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(if (index <= page) EmeraldPrimary else SlateBorder)
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .testTag("onboarding_content"),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when (page) {
                0 -> WelcomePage(name = name, onNameChange = { name = it })
                1 -> LogExpensesPage()
                2 -> AiPage()
                else -> SetupPage(
                    incomeText = incomeText,
                    onIncomeChange = { incomeText = it; error = null },
                    goalTitle = goalTitle,
                    onGoalTitleChange = { goalTitle = it; error = null },
                    goalTargetText = goalTargetText,
                    onGoalTargetChange = { goalTargetText = it; error = null },
                    error = error
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (page > 0) {
                TextButton(
                    onClick = { error = null; page -= 1 },
                    modifier = Modifier.testTag("onboarding_back")
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Back", color = TextSecondary)
                }
            } else {
                Spacer(Modifier.width(1.dp))
            }

            if (isLast) {
                Button(
                    onClick = { finish() },
                    modifier = Modifier.testTag("onboarding_finish"),
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary, contentColor = Color.Black),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Finish", fontWeight = FontWeight.Bold)
                }
            } else {
                Button(
                    onClick = { page += 1 },
                    modifier = Modifier.testTag("onboarding_next"),
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary, contentColor = Color.Black),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("Next", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun PageHeader(icon: ImageVector, title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(EmeraldContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = BrightEmerald, modifier = Modifier.size(30.dp))
        }
        Text(
            text = title,
            modifier = Modifier.testTag("onboarding_title"),
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold, color = TextPrimary)
        )
        Text(text = subtitle, style = MaterialTheme.typography.bodyMedium.copy(color = TextSecondary, lineHeight = 22.sp))
    }
}

@Composable
private fun Bullet(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Filled.Check, contentDescription = null, tint = BrightEmerald, modifier = Modifier.size(18.dp).padding(top = 2.dp))
        Text(text = text, style = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary, lineHeight = 22.sp))
    }
}

@Composable
private fun NumberedStep(number: Int, title: String, body: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SlateSurface)
            .border(1.dp, SlateBorder, RoundedCornerShape(14.dp))
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier.size(28.dp).clip(CircleShape).background(EmeraldContainer),
            contentAlignment = Alignment.Center
        ) {
            Text("$number", color = BrightEmerald, fontWeight = FontWeight.Bold)
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge.copy(color = TextPrimary, fontWeight = FontWeight.Bold))
            Text(body, style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary, lineHeight = 18.sp))
        }
    }
}

@Composable
private fun WelcomePage(name: String, onNameChange: (String) -> Unit) {
    Column(modifier = Modifier.testTag("onboarding_page_1"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageHeader(
            icon = Icons.Filled.Savings,
            title = "Welcome to FinSight",
            subtitle = "A calm place to see where your money goes and what to do about it."
        )
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Bullet("Track your spending in pesos, category by category")
            Bullet("Get nudges when a habit starts to add up")
            Bullet("Plan your savings and see how fast goals get closer")
            Bullet("Works offline: your data stays on this phone")
        }
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            label = { Text("What should we call you? (optional)", color = TextSecondary) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("onboarding_name"),
            colors = fieldColors()
        )
    }
}

@Composable
private fun LogExpensesPage() {
    Column(modifier = Modifier.testTag("onboarding_page_2"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageHeader(
            icon = Icons.Filled.Insights,
            title = "Log expenses, get insights",
            subtitle = "The more you log, the sharper FinSight gets."
        )
        NumberedStep(1, "Log an expense", "Tap Add Expense, enter an amount and pick a category. It takes a few seconds.")
        NumberedStep(2, "Get insights and nudges", "FinSight spots patterns in your spending and tells you what stands out.")
        NumberedStep(3, "Try cutbacks in the Simulator", "See how small changes add up over a year and speed up your goals.")
    }
}

@Composable
private fun AiPage() {
    Column(modifier = Modifier.testTag("onboarding_page_3"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageHeader(
            icon = Icons.Filled.AutoAwesome,
            title = "Optional: friendlier wording with AI",
            subtitle = "FinSight works fully without AI. If you want, a model can rewrite insights in your own terms."
        )
        NumberedStep(1, "On-device models", "Download a small model and it runs on this phone. Nothing leaves your device.")
        NumberedStep(2, "Codex sign-in", "Sign in with Codex instead. This sends your income, insight amounts and savings goals to OpenAI.")
        Text(
            text = "You can set this up any time from the AI Setup tab.",
            modifier = Modifier.testTag("onboarding_ai_pointer"),
            style = MaterialTheme.typography.bodyMedium.copy(color = BrightEmerald, fontWeight = FontWeight.SemiBold)
        )
    }
}

@Composable
private fun SetupPage(
    incomeText: String,
    onIncomeChange: (String) -> Unit,
    goalTitle: String,
    onGoalTitleChange: (String) -> Unit,
    goalTargetText: String,
    onGoalTargetChange: (String) -> Unit,
    error: String?
) {
    Column(modifier = Modifier.testTag("onboarding_page_4"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PageHeader(
            icon = Icons.Filled.Timeline,
            title = "Set your starting point",
            subtitle = "Your monthly income is what spending and savings are measured against. You can change it later in Settings."
        )
        OutlinedTextField(
            value = incomeText,
            onValueChange = onIncomeChange,
            label = { Text("Monthly income (${Money.SYMBOL}), required", color = TextSecondary) },
            placeholder = { Text("e.g. 45,000", color = TextMuted) },
            leadingIcon = { Text(Money.SYMBOL, color = BrightEmerald, fontWeight = FontWeight.Bold) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth().testTag("onboarding_income"),
            colors = fieldColors()
        )
        Text(
            text = "First savings goal (optional)",
            style = MaterialTheme.typography.titleSmall.copy(color = TextPrimary, fontWeight = FontWeight.Bold)
        )
        OutlinedTextField(
            value = goalTitle,
            onValueChange = onGoalTitleChange,
            label = { Text("Goal name", color = TextSecondary) },
            placeholder = { Text("e.g. Emergency fund", color = TextMuted) },
            leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null, tint = TextSecondary) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("onboarding_goal_title"),
            colors = fieldColors()
        )
        OutlinedTextField(
            value = goalTargetText,
            onValueChange = onGoalTargetChange,
            label = { Text("Target amount (${Money.SYMBOL})", color = TextSecondary) },
            leadingIcon = { Text(Money.SYMBOL, color = BrightEmerald, fontWeight = FontWeight.Bold) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth().testTag("onboarding_goal_target"),
            colors = fieldColors()
        )
        Text(
            text = "Skipping the intro keeps the default income of ${Money.format(FinSightDatabaseHelper.DEFAULT_MONTHLY_INCOME)}.",
            style = MaterialTheme.typography.bodySmall.copy(color = TextMuted)
        )
        if (error != null) {
            Text(
                text = error,
                modifier = Modifier.testTag("onboarding_error"),
                textAlign = TextAlign.Start,
                style = MaterialTheme.typography.bodySmall.copy(color = CoralRed)
            )
        }
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = EmeraldPrimary,
    unfocusedBorderColor = SlateBorder,
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary
)
