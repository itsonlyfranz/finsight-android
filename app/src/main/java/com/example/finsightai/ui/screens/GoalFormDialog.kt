package com.example.finsightai.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.finsightai.model.SavingsGoal
import com.example.finsightai.theme.BrightEmerald
import com.example.finsightai.theme.CoralRed
import com.example.finsightai.theme.EmeraldPrimary
import com.example.finsightai.theme.SlateBorder
import com.example.finsightai.theme.SlateSurface
import com.example.finsightai.theme.SlateSurfaceVariant
import com.example.finsightai.theme.TextMuted
import com.example.finsightai.theme.TextPrimary
import com.example.finsightai.theme.TextSecondary
import com.example.finsightai.ui.viewmodel.FinSightViewModel
import com.example.finsightai.util.Money

/** Emoji a goal can wear; the first is the default. */
private val GOAL_ICONS = listOf("🎯", "🛡️", "🏠", "🚗", "✈️", "💻", "🎓", "💍", "🎁", "🌴")

/** Adds a new savings goal. */
@Composable
fun AddGoalDialog(viewModel: FinSightViewModel, onDismiss: () -> Unit) {
    GoalFormDialog(
        existing = null,
        onDismiss = onDismiss,
        onDelete = null,
        onSave = { title, target, saved, icon, description ->
            viewModel.addGoal(title, target, saved, icon, description)
        }
    )
}

/** Edits (or deletes) [goal]. */
@Composable
fun EditGoalDialog(viewModel: FinSightViewModel, goal: SavingsGoal, onDismiss: () -> Unit) {
    GoalFormDialog(
        existing = goal,
        onDismiss = onDismiss,
        onDelete = { viewModel.deleteGoal(goal.id) },
        onSave = { title, target, saved, icon, description ->
            viewModel.updateGoal(goal, title, target, saved, icon, description)
        }
    )
}

/**
 * The one form behind both goal dialogs. [onSave] gets validated, parsed values and returns false if it
 * refused them (the dialog then stays open). [onDelete] != null adds a delete button with a confirmation.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GoalFormDialog(
    existing: SavingsGoal?,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)?,
    onSave: (title: String, target: Double, saved: Double, icon: String, description: String) -> Boolean
) {
    val isEdit = existing != null
    var title by remember { mutableStateOf(existing?.title ?: "") }
    var targetText by remember { mutableStateOf(existing?.let { plainAmount(it.targetAmount) } ?: "") }
    var savedText by remember { mutableStateOf(existing?.let { plainAmount(it.currentSaved) } ?: "") }
    var icon by remember { mutableStateOf(existing?.icon ?: GOAL_ICONS.first()) }
    var description by remember { mutableStateOf(existing?.description ?: "") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var confirmingDelete by remember { mutableStateOf(false) }

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
                    text = if (isEdit) "Edit Goal" else "Add a Goal",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, color = TextPrimary)
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = TextSecondary)
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it; errorMessage = null },
                    label = { Text("Goal name", color = TextSecondary) },
                    placeholder = { Text("e.g. Emergency fund, New laptop", color = TextMuted) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("goal_title"),
                    colors = goalFieldColors()
                )
                OutlinedTextField(
                    value = targetText,
                    onValueChange = { targetText = it; errorMessage = null },
                    label = { Text("Target amount (${Money.SYMBOL})", color = TextSecondary) },
                    leadingIcon = { Text(Money.SYMBOL, color = BrightEmerald, fontWeight = FontWeight.Bold) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().testTag("goal_target"),
                    colors = goalFieldColors()
                )
                OutlinedTextField(
                    value = savedText,
                    onValueChange = { savedText = it; errorMessage = null },
                    label = { Text("Saved so far (${Money.SYMBOL}, optional)", color = TextSecondary) },
                    leadingIcon = { Text(Money.SYMBOL, color = BrightEmerald, fontWeight = FontWeight.Bold) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().testTag("goal_saved"),
                    colors = goalFieldColors()
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Icon",
                        style = MaterialTheme.typography.bodyMedium.copy(color = TextSecondary, fontWeight = FontWeight.Medium)
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // An edited goal may carry an icon that is not in the list; keep it selectable.
                        val choices = if (icon in GOAL_ICONS) GOAL_ICONS else listOf(icon) + GOAL_ICONS
                        choices.forEach { candidate ->
                            val selected = candidate == icon
                            Surface(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .border(
                                        width = if (selected) 2.dp else 1.dp,
                                        color = if (selected) EmeraldPrimary else SlateBorder,
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                    .testTag("goal_icon_$candidate")
                                    .clickable { icon = candidate },
                                color = if (selected) EmeraldPrimary.copy(alpha = 0.2f) else SlateSurfaceVariant,
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text(text = candidate, fontSize = 20.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Note (optional)", color = TextSecondary) },
                    maxLines = 2,
                    modifier = Modifier.fillMaxWidth().testTag("goal_note"),
                    colors = goalFieldColors()
                )
                if (errorMessage != null) {
                    Text(
                        text = errorMessage ?: "",
                        modifier = Modifier.testTag("goal_error"),
                        color = CoralRed,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (onDelete != null) {
                    TextButton(onClick = { confirmingDelete = true }, modifier = Modifier.testTag("goal_delete")) {
                        Icon(Icons.Filled.Delete, contentDescription = null, tint = CoralRed, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Delete goal", color = CoralRed)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                modifier = Modifier.testTag("goal_save"),
                onClick = {
                    val target = Money.parseAmount(targetText)
                    val saved = if (savedText.isBlank()) 0.0 else Money.parseAmount(savedText)
                    when {
                        title.isBlank() -> errorMessage = "Please give the goal a name"
                        target == null || target <= 0.0 ->
                            errorMessage = "Please enter a target amount greater than ${Money.format(0.0)}"
                        saved == null -> errorMessage = "Saved so far must be an amount, or left empty"
                        onSave(title, target, saved, icon, description) -> onDismiss()
                        else -> errorMessage = "Could not save this goal"
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary, contentColor = Color.Black),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(if (isEdit) Icons.Filled.Check else Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (isEdit) "Save Changes" else "Save Goal", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("goal_cancel")) {
                Text("Cancel", color = TextSecondary)
            }
        }
    )

    if (confirmingDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            containerColor = SlateSurface,
            title = { Text("Delete this goal?", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = { Text("\"${existing?.title.orEmpty()}\" will be removed from your goals.", color = TextSecondary) },
            confirmButton = {
                TextButton(
                    modifier = Modifier.testTag("confirm_delete_goal"),
                    onClick = {
                        onDelete()
                        confirmingDelete = false
                        onDismiss()
                    }
                ) { Text("Delete", color = CoralRed, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text("Keep", color = TextSecondary) }
            }
        )
    }
}

@Composable
private fun goalFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = EmeraldPrimary,
    unfocusedBorderColor = SlateBorder,
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary
)
