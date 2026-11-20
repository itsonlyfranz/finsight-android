package com.example.finsightai.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.finsightai.theme.BrightEmerald
import com.example.finsightai.theme.CoralRed
import com.example.finsightai.theme.EmeraldPrimary
import com.example.finsightai.theme.SlateBorder
import com.example.finsightai.theme.SlateSurface
import com.example.finsightai.theme.TextMuted
import com.example.finsightai.theme.TextPrimary
import com.example.finsightai.theme.TextSecondary
import com.example.finsightai.ui.viewmodel.FinSightViewModel
import com.example.finsightai.util.CsvExporter
import com.example.finsightai.util.Money

/** What the confirmation dialog on the Settings screen is about to do. */
private enum class DataAction {
    DELETE_TRANSACTIONS,
    DELETE_GOALS,
    DELETE_ALL,
    RESET_EVERYTHING
}

/**
 * Profile settings (the name the dashboard greets and the monthly income everything is measured against)
 * and the controls for the user's data: export the transactions as CSV, delete them or the goals, delete
 * everything, or factory-reset the app.
 */
@Composable
fun SettingsScreen(
    viewModel: FinSightViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val savedName by viewModel.userName.collectAsStateWithLifecycle()
    val savedIncome by viewModel.monthlyIncome.collectAsStateWithLifecycle()
    val transactions by viewModel.transactions.collectAsStateWithLifecycle()
    val goals by viewModel.goals.collectAsStateWithLifecycle()
    val context = LocalContext.current

    BackHandler(onBack = onClose)

    var nameText by remember { mutableStateOf(savedName) }
    var nameStatus by remember { mutableStateOf<String?>(null) }
    var incomeText by remember { mutableStateOf(plainAmount(savedIncome)) }
    var incomeError by remember { mutableStateOf<String?>(null) }
    var incomeStatus by remember { mutableStateOf<String?>(null) }
    var pendingAction by rememberSaveable { mutableStateOf<DataAction?>(null) }
    var dataStatus by remember { mutableStateOf<String?>(null) }

    pendingAction?.let { action ->
        DataConfirmDialog(
            action = action,
            transactionCount = transactions.size,
            goalCount = goals.size,
            onExportFirst = { CsvExporter.exportAndShareCsv(context, transactions) },
            onCancel = { pendingAction = null },
            onConfirm = {
                pendingAction = null
                when (action) {
                    DataAction.DELETE_TRANSACTIONS -> {
                        viewModel.deleteAllTransactions()
                        dataStatus = "All transactions deleted"
                    }
                    DataAction.DELETE_GOALS -> {
                        viewModel.deleteAllGoals()
                        dataStatus = "All savings goals deleted"
                    }
                    // These two return the app to the first-launch intro, which replaces this screen.
                    DataAction.DELETE_ALL -> viewModel.deleteAllData()
                    DataAction.RESET_EVERYTHING -> viewModel.resetEverything()
                }
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("settings_screen")
            .background(MaterialTheme.colorScheme.background)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose, modifier = Modifier.testTag("settings_back")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextSecondary)
            }
            Text(
                text = "Settings",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, color = TextPrimary)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .testTag("settings_list")
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            SettingsCard(title = "Your name", subtitle = "The dashboard greets you by this name. Leave it empty to be welcomed without one.") {
                OutlinedTextField(
                    value = nameText,
                    onValueChange = { nameText = it; nameStatus = null },
                    label = { Text("Name", color = TextSecondary) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("settings_name"),
                    colors = fieldColors()
                )
                SaveRow(
                    saveTag = "settings_name_save",
                    statusTag = "settings_name_status",
                    status = nameStatus,
                    isError = false,
                    onSave = {
                        viewModel.setUserName(nameText)
                        nameText = nameText.trim()
                        nameStatus = "Saved"
                    }
                )
            }

            SettingsCard(
                title = "Monthly income",
                subtitle = "Spending, buffer and insights are measured against this amount."
            ) {
                OutlinedTextField(
                    value = incomeText,
                    onValueChange = { incomeText = it; incomeError = null; incomeStatus = null },
                    label = { Text("Monthly income (${Money.SYMBOL})", color = TextSecondary) },
                    leadingIcon = { Text(Money.SYMBOL, color = BrightEmerald, fontWeight = FontWeight.Bold) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().testTag("settings_income"),
                    colors = fieldColors()
                )
                SaveRow(
                    saveTag = "settings_income_save",
                    statusTag = if (incomeError != null) "settings_income_error" else "settings_income_status",
                    status = incomeError ?: incomeStatus,
                    isError = incomeError != null,
                    onSave = {
                        val parsed = Money.parseAmount(incomeText)
                        if (parsed != null && viewModel.setMonthlyIncome(parsed)) {
                            incomeText = plainAmount(parsed)
                            incomeError = null
                            incomeStatus = "Saved"
                        } else {
                            incomeStatus = null
                            incomeError = "Enter an amount above ${Money.format(0.0)}"
                        }
                    }
                )
            }

            SettingsCard(
                title = "Your data",
                subtitle = "Everything is stored only on this phone. Save a copy of your transactions as a CSV file before deleting."
            ) {
                OutlinedButton(
                    onClick = { CsvExporter.exportAndShareCsv(context, transactions) },
                    enabled = transactions.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().testTag("settings_export_csv"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Export transactions (CSV)", fontWeight = FontWeight.Bold)
                }
                if (transactions.isEmpty()) {
                    Text(
                        "No transactions to export yet.",
                        modifier = Modifier.testTag("settings_export_empty"),
                        style = MaterialTheme.typography.bodySmall.copy(color = TextMuted)
                    )
                }
                OutlinedButton(
                    onClick = { pendingAction = DataAction.DELETE_TRANSACTIONS; dataStatus = null },
                    modifier = Modifier.fillMaxWidth().testTag("settings_delete_transactions"),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = CoralRed),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Delete all transactions", fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = { pendingAction = DataAction.DELETE_GOALS; dataStatus = null },
                    modifier = Modifier.fillMaxWidth().testTag("settings_delete_goals"),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = CoralRed),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Delete all savings goals", fontWeight = FontWeight.Bold)
                }
                if (dataStatus != null) {
                    Text(
                        text = dataStatus!!,
                        modifier = Modifier.testTag("settings_data_status"),
                        style = MaterialTheme.typography.bodySmall.copy(color = BrightEmerald)
                    )
                }
            }

            SettingsCard(
                title = "Start over",
                subtitle = "Delete all data clears your transactions, budgets, savings goals, name and income, then shows the " +
                    "welcome intro again. Your theme and AI settings are kept. Reset everything also clears those."
            ) {
                Button(
                    onClick = { pendingAction = DataAction.DELETE_ALL; dataStatus = null },
                    modifier = Modifier.fillMaxWidth().testTag("settings_delete_all"),
                    colors = ButtonDefaults.buttonColors(containerColor = CoralRed, contentColor = Color.White),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Delete all data", fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = { pendingAction = DataAction.RESET_EVERYTHING; dataStatus = null },
                    modifier = Modifier.fillMaxWidth().testTag("settings_reset_everything"),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = CoralRed),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Reset everything", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun DataConfirmDialog(
    action: DataAction,
    transactionCount: Int,
    goalCount: Int,
    onExportFirst: () -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit
) {
    val title: String
    val messages: List<String>
    val confirmLabel: String
    var offersExport = false
    when (action) {
        DataAction.DELETE_TRANSACTIONS -> {
            title = "Delete all transactions?"
            messages = listOf(
                "This permanently deletes all $transactionCount ${if (transactionCount == 1) "transaction" else "transactions"} on this phone. " +
                    "Your name, income, budgets and savings goals are kept. This can't be undone."
            )
            confirmLabel = "Delete"
            offersExport = transactionCount > 0
        }
        DataAction.DELETE_GOALS -> {
            title = "Delete all savings goals?"
            messages = listOf(
                "This permanently deletes all $goalCount savings ${if (goalCount == 1) "goal" else "goals"} on this phone. " +
                    "Your transactions, name and income are kept. This can't be undone."
            )
            confirmLabel = "Delete"
        }
        DataAction.DELETE_ALL -> {
            title = "Delete all data?"
            messages = listOf(
                "This permanently deletes all your FinSight data on this phone. This can't be undone.",
                "You will see the welcome intro again. Your theme and AI settings are kept."
            )
            confirmLabel = "Delete"
            offersExport = transactionCount > 0
        }
        DataAction.RESET_EVERYTHING -> {
            title = "Reset everything?"
            messages = listOf(
                "This permanently deletes all your FinSight data on this phone. AI sign-in and theme are also removed, " +
                    "and your AI model choice is cleared. This can't be undone.",
                "Downloaded model files stay on this phone; delete them in AI Setup."
            )
            confirmLabel = "Reset everything"
            offersExport = transactionCount > 0
        }
    }

    AlertDialog(
        onDismissRequest = onCancel,
        modifier = Modifier.testTag("data_dialog"),
        containerColor = SlateSurface,
        title = {
            Text(title, modifier = Modifier.testTag("data_dialog_title"), fontWeight = FontWeight.Bold, color = TextPrimary)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                messages.forEachIndexed { index, message ->
                    Text(
                        message,
                        modifier = Modifier.testTag("data_dialog_message_$index"),
                        style = MaterialTheme.typography.bodyMedium.copy(color = TextSecondary)
                    )
                }
                if (offersExport) {
                    TextButton(onClick = onExportFirst, modifier = Modifier.testTag("data_dialog_export")) {
                        Text("Export first", fontWeight = FontWeight.Bold, color = BrightEmerald)
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, modifier = Modifier.testTag("data_dialog_cancel")) {
                Text("Cancel", color = TextSecondary)
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                modifier = Modifier.testTag("data_dialog_confirm"),
                colors = ButtonDefaults.buttonColors(containerColor = CoralRed, contentColor = Color.White),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(confirmLabel, fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
private fun SettingsCard(title: String, subtitle: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().border(1.dp, SlateBorder, RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = SlateSurface)
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = TextPrimary))
                Text(subtitle, style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary))
            }
            content()
        }
    }
}

@Composable
private fun SaveRow(
    saveTag: String,
    statusTag: String,
    status: String?,
    isError: Boolean,
    onSave: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (status != null) {
            Text(
                text = status,
                modifier = Modifier.weight(1f).testTag(statusTag),
                style = MaterialTheme.typography.bodySmall.copy(color = if (isError) CoralRed else BrightEmerald)
            )
        } else {
            Text("", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall.copy(color = TextMuted))
        }
        Button(
            onClick = onSave,
            modifier = Modifier.testTag(saveTag),
            colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary, contentColor = Color.Black),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Save", fontWeight = FontWeight.Bold)
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
