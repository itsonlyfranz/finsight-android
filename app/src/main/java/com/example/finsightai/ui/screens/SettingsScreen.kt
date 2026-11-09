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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.example.finsightai.util.Money

/** Profile settings: the name the dashboard greets and the monthly income everything is measured against. */
@Composable
fun SettingsScreen(
    viewModel: FinSightViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val savedName by viewModel.userName.collectAsStateWithLifecycle()
    val savedIncome by viewModel.monthlyIncome.collectAsStateWithLifecycle()

    BackHandler(onBack = onClose)

    var nameText by remember { mutableStateOf(savedName) }
    var nameStatus by remember { mutableStateOf<String?>(null) }
    var incomeText by remember { mutableStateOf(plainAmount(savedIncome)) }
    var incomeError by remember { mutableStateOf<String?>(null) }
    var incomeStatus by remember { mutableStateOf<String?>(null) }

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
        }
    }
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
