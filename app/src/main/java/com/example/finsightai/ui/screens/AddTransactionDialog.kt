package com.example.finsightai.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.finsightai.model.TransactionCategory
import com.example.finsightai.theme.BrightEmerald
import com.example.finsightai.theme.CoralRed
import com.example.finsightai.theme.EmeraldContainer
import com.example.finsightai.theme.EmeraldPrimary
import com.example.finsightai.theme.SlateBorder
import com.example.finsightai.theme.SlateSurface
import com.example.finsightai.theme.SlateSurfaceVariant
import com.example.finsightai.theme.TextMuted
import com.example.finsightai.theme.TextPrimary
import com.example.finsightai.theme.TextSecondary
import com.example.finsightai.ui.components.categoryColor
import com.example.finsightai.ui.components.categoryIcon
import com.example.finsightai.ui.viewmodel.FinSightViewModel
import com.example.finsightai.util.Money
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import com.example.finsightai.model.Transaction

/** Adds a new transaction (dated today). */
@Composable
fun AddTransactionDialog(
    viewModel: FinSightViewModel,
    onDismiss: () -> Unit
) {
    TransactionFormDialog(
        existing = null,
        onDismiss = onDismiss,
        onSave = { title, amount, category, _, notes ->
            viewModel.addTransaction(
                title = title,
                amount = amount,
                category = category,
                date = LocalDate.now(),
                notes = notes
            )
            true
        }
    )
}

/** Edits [transaction]: amount, category, date and note (and the title), saved through the view model. */
@Composable
fun EditTransactionDialog(
    viewModel: FinSightViewModel,
    transaction: Transaction,
    onDismiss: () -> Unit
) {
    TransactionFormDialog(
        existing = transaction,
        onDismiss = onDismiss,
        onSave = { title, amount, category, date, notes ->
            viewModel.updateTransaction(transaction, title, amount, category, date, notes)
        }
    )
}

/**
 * The one form behind both dialogs. [existing] == null is "add" (no date field, dated today); otherwise
 * it pre-fills the fields and shows a date picker. [onSave] gets the validated, trimmed values and
 * returns false if it refused them, which keeps the dialog open.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun TransactionFormDialog(
    existing: Transaction?,
    onDismiss: () -> Unit,
    onSave: (title: String, amount: Double, category: TransactionCategory, date: LocalDate, notes: String) -> Boolean
) {
    val isEdit = existing != null
    var title by remember { mutableStateOf(existing?.title ?: "") }
    var amountText by remember { mutableStateOf(existing?.let { plainAmount(it.amount) } ?: "") }
    var selectedCategory by remember { mutableStateOf(existing?.category ?: TransactionCategory.CAFES_DINING) }
    var date by remember { mutableStateOf(existing?.date ?: LocalDate.now()) }
    var showDatePicker by remember { mutableStateOf(false) }
    var notes by remember { mutableStateOf(existing?.notes ?: "") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SlateSurface,
        shape = RoundedCornerShape(24.dp),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isEdit) "Edit Transaction" else "Add Transaction",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Close",
                        tint = TextSecondary
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Title field
                OutlinedTextField(
                    value = title,
                    onValueChange = {
                        title = it
                        errorMessage = null
                    },
                    label = { Text("Title / Merchant", color = TextSecondary) },
                    placeholder = { Text("e.g. Philz Coffee, Keychron Keyboard", color = TextMuted) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("tx_title"),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = EmeraldPrimary,
                        unfocusedBorderColor = SlateBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )

                // Amount field
                OutlinedTextField(
                    value = amountText,
                    onValueChange = {
                        amountText = it
                        errorMessage = null
                    },
                    label = { Text("Amount (${Money.SYMBOL})", color = TextSecondary) },
                    placeholder = { Text("0.00", color = TextMuted) },
                    leadingIcon = {
                        Text(Money.SYMBOL, color = BrightEmerald, fontWeight = FontWeight.Bold)
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().testTag("tx_amount"),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = EmeraldPrimary,
                        unfocusedBorderColor = SlateBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )

                // Category selector
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Category",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = TextSecondary,
                            fontWeight = FontWeight.Medium
                        )
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TransactionCategory.values().forEach { category ->
                            val isSelected = category == selectedCategory
                            val catColor = category.categoryColor()

                            Surface(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .border(
                                        width = if (isSelected) 2.dp else 1.dp,
                                        color = if (isSelected) catColor else SlateBorder,
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                    .testTag("tx_category_${category.name}")
                                    .clickable { selectedCategory = category },
                                color = if (isSelected) catColor.copy(alpha = 0.2f) else SlateSurfaceVariant,
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = category.categoryIcon(),
                                        contentDescription = null,
                                        tint = if (isSelected) catColor else TextSecondary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = category.displayName,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            color = if (isSelected) TextPrimary else TextSecondary,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                        )
                                    )
                                }
                            }
                        }
                    }
                }

                // Date (edit only; new transactions are dated today)
                if (isEdit) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Date",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = TextSecondary,
                                fontWeight = FontWeight.Medium
                            )
                        )
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .border(1.dp, SlateBorder, RoundedCornerShape(10.dp))
                                .testTag("tx_date")
                                .clickable { showDatePicker = true },
                            color = SlateSurfaceVariant,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.CalendarMonth,
                                    contentDescription = null,
                                    tint = BrightEmerald,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = date.format(DateTimeFormatter.ofPattern("EEEE, MMM d, yyyy", Locale.US)),
                                    color = TextPrimary,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }

                // Notes field (optional)
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes (optional)", color = TextSecondary) },
                    placeholder = { Text("Split with roommate, impulse buy, etc.", color = TextMuted) },
                    singleLine = false,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth().testTag("tx_notes"),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = EmeraldPrimary,
                        unfocusedBorderColor = SlateBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )

                // Error validation message
                if (errorMessage != null) {
                    Text(
                        text = errorMessage ?: "",
                        modifier = Modifier.testTag("tx_error"),
                        color = CoralRed,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                modifier = Modifier.testTag("tx_save"),
                onClick = {
                    val trimmedTitle = title.trim()
                    if (trimmedTitle.isEmpty()) {
                        errorMessage = "Please enter a transaction title"
                        return@Button
                    }
                    val parsedAmount = amountText.trim().toDoubleOrNull()
                    if (parsedAmount == null || !parsedAmount.isFinite() || parsedAmount <= 0.0) {
                        errorMessage = "Please enter a valid amount greater than ${Money.format(0.0)}"
                        return@Button
                    }

                    if (onSave(trimmedTitle, parsedAmount, selectedCategory, date, notes.trim())) {
                        onDismiss()
                    } else {
                        errorMessage = "Could not save this transaction"
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = EmeraldPrimary,
                    contentColor = Color.Black
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    imageVector = if (isEdit) Icons.Filled.Check else Icons.Filled.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(if (isEdit) "Save Changes" else "Save Transaction", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("tx_cancel")) {
                Text("Cancel", color = TextSecondary)
            }
        }
    )

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = toPickerMillis(date))
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    modifier = Modifier.testTag("tx_date_confirm"),
                    onClick = {
                        pickerState.selectedDateMillis?.let { date = fromPickerMillis(it) }
                        showDatePicker = false
                    }
                ) { Text("OK", color = BrightEmerald, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel", color = TextSecondary) }
            }
        ) {
            DatePicker(state = pickerState)
        }
    }
}

/** `250.0` -> `250`, `250.75` -> `250.75`: the amount as someone would type it back. */
internal fun plainAmount(amount: Double): String =
    java.math.BigDecimal.valueOf(amount).stripTrailingZeros().toPlainString()

// The Material date picker works in UTC midnight milliseconds.
internal fun toPickerMillis(date: LocalDate): Long = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

internal fun fromPickerMillis(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
