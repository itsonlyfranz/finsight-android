package com.example.finsightai.ui.aisetup

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.ClipEntry
import android.content.ClipData
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.finsightai.ai.backend.BackendChoice
import com.example.finsightai.ai.backend.EffectiveBackend
import com.example.finsightai.codex.CodexAuthState
import com.example.finsightai.llm.CuratedModels
import com.example.finsightai.llm.InstalledModel
import com.example.finsightai.llm.ModelFamily
import com.example.finsightai.llm.ModelSpec
import com.example.finsightai.llm.hf.RepoAccess
import com.example.finsightai.theme.BrightEmerald
import com.example.finsightai.theme.CoralRed
import com.example.finsightai.theme.EmeraldContainer
import com.example.finsightai.theme.SlateBorder
import com.example.finsightai.theme.SlateSurface
import com.example.finsightai.theme.TextMuted
import com.example.finsightai.theme.TextPrimary
import com.example.finsightai.theme.TextSecondary
import java.util.Locale

/** "AI Setup" tab: choose the AI backend, manage on-device models, search Hugging Face, sign in to Codex. */
@Composable
fun AiSetupScreen(viewModel: AiSetupViewModel, modifier: Modifier = Modifier) {
    var showSearch by rememberSaveable { mutableStateOf(false) }
    if (showSearch) {
        HuggingFaceSearchScreen(viewModel = viewModel, onBack = { showSearch = false }, modifier = modifier)
        return
    }

    LaunchedEffect(Unit) { viewModel.retryCodexModelsIfUnavailable() }
    val choice by viewModel.backendChoice.collectAsStateWithLifecycle()
    val effective by viewModel.effective.collectAsStateWithLifecycle()
    val installed by viewModel.installed.collectAsStateWithLifecycle()
    val active by viewModel.activeModel.collectAsStateWithLifecycle()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    var pendingDelete by rememberSaveable { mutableStateOf<String?>(null) }

    val installedById = installed.associateBy { it.id }
    val curatedIds = CuratedModels.all.map { it.id }.toSet()
    val searchInstalled = installed.filter { it.id !in curatedIds }
    val searchDownloads = downloads.values.filter { it.spec.id !in curatedIds && it.spec.id !in installedById }

    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("ai_setup_list"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("AI Setup", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold, color = TextPrimary))
            Text(
                "Choose what writes your insights. Without a model, FinSight's built-in rules are used.",
                style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
            )
        }

        item { BackendCard(choice, effective, onSelect = viewModel::selectBackend) }

        item { SectionTitle("On-device models", "Download once from Hugging Face; runs offline.") }
        CuratedModels.byFamilyGrouped().forEach { (family, specs) ->
            item(key = "family_${family.name}") {
                Text(family.label, style = MaterialTheme.typography.titleSmall.copy(color = BrightEmerald, fontWeight = FontWeight.Bold))
            }
            items(specs, key = { it.id }) { spec ->
                ModelCard(
                    spec = spec,
                    installed = installedById[spec.id],
                    isActive = active?.id == spec.id,
                    download = downloads[spec.id]?.state,
                    onDownload = { viewModel.download(spec) },
                    onCancel = { viewModel.cancelDownload(spec) },
                    onDismiss = { viewModel.dismissFailure(spec) },
                    onUse = { viewModel.setActive(spec.id) },
                    onDelete = { pendingDelete = spec.id },
                )
            }
        }

        if (searchInstalled.isNotEmpty() || searchDownloads.isNotEmpty()) {
            item { Text("From Hugging Face search", style = MaterialTheme.typography.titleSmall.copy(color = BrightEmerald, fontWeight = FontWeight.Bold)) }
            items(searchInstalled, key = { it.id }) { model ->
                ModelCard(
                    spec = model.spec,
                    installed = model,
                    isActive = active?.id == model.id,
                    download = null,
                    onDownload = {}, onCancel = {}, onDismiss = {},
                    onUse = { viewModel.setActive(model.id) },
                    onDelete = { pendingDelete = model.id },
                )
            }
            items(searchDownloads, key = { it.spec.id }) { d ->
                ModelCard(
                    spec = d.spec,
                    installed = null,
                    isActive = false,
                    download = d.state,
                    onDownload = { viewModel.download(d.spec) },
                    onCancel = { viewModel.cancelDownload(d.spec) },
                    onDismiss = { viewModel.dismissFailure(d.spec) },
                    onUse = {}, onDelete = {},
                )
            }
        }

        item {
            OutlinedButton(
                onClick = { showSearch = true },
                modifier = Modifier.fillMaxWidth().testTag("open_hf_search")
            ) { Text("Search Hugging Face for more models") }
        }

        item { CodexCard(viewModel) }
    }

    pendingDelete?.let { id ->
        ConfirmDeleteDialog(id, installedById[id], isActive = active?.id == id, viewModel = viewModel, onClose = { pendingDelete = null })
    }
}

@Composable
private fun ConfirmDeleteDialog(id: String, model: InstalledModel?, isActive: Boolean, viewModel: AiSetupViewModel, onClose: () -> Unit) {
    AlertDialog(
        modifier = Modifier.testTag("confirm_delete_dialog"),
        onDismissRequest = onClose,
        title = { Text("Delete model?") },
        text = {
            Text(
                "Removes ${model?.spec?.displayName ?: id} (${formatBytes(model?.sizeOnDisk ?: 0L)}) from this device." +
                    if (isActive) " It is the active model, so FinSight will fall back to built-in rules (or Codex if selected)." else ""
            )
        },
        confirmButton = {
            TextButton(
                modifier = Modifier.testTag("confirm_delete"),
                onClick = { viewModel.delete(id); onClose() }
            ) { Text("Delete", color = CoralRed) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Keep") } }
    )
}

private fun CuratedModels.byFamilyGrouped(): List<Pair<ModelFamily, List<ModelSpec>>> =
    listOf(ModelFamily.GEMMA, ModelFamily.BONSAI, ModelFamily.LFM).map { it to byFamily(it) }.filter { it.second.isNotEmpty() }

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = TextPrimary))
        Text(subtitle, style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary))
    }
}

@Composable
private fun BackendCard(choice: BackendChoice, effective: EffectiveBackend?, onSelect: (BackendChoice) -> Unit) {
    SetupCard {
        Text("AI backend", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = TextPrimary))
        BackendChoice.entries.forEach { option ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = choice == option, role = Role.RadioButton, onClick = { onSelect(option) })
                    .testTag("backend_${option.name.lowercase(Locale.US)}")
            ) {
                RadioButton(selected = choice == option, onClick = null)
                Text(option.label, color = TextPrimary)
            }
        }
        val status = when (effective) {
            is EffectiveBackend.Generative -> "Writing insights with ${effective.label}"
            is EffectiveBackend.Deterministic -> "Using built-in rules: ${effective.reason}"
            null -> "Checking…"
        }
        Text(status, modifier = Modifier.testTag("effective_status"), style = MaterialTheme.typography.bodySmall.copy(color = if (effective is EffectiveBackend.Generative) BrightEmerald else TextSecondary))
    }
}

@Composable
internal fun ModelCard(
    spec: ModelSpec,
    installed: InstalledModel?,
    isActive: Boolean,
    download: DownloadUi?,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    onUse: () -> Unit,
    onDelete: () -> Unit,
) {
    SetupCard(modifier = Modifier.testTag("model_card_${spec.id}")) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(spec.displayName, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, color = TextPrimary), modifier = Modifier.weight(1f))
            if (isActive) Text("ACTIVE", modifier = Modifier.testTag("active_badge_${spec.id}"), style = MaterialTheme.typography.labelSmall.copy(color = BrightEmerald, fontWeight = FontWeight.Bold))
        }
        Text(spec.description, style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary))
        Text(
            "${spec.quantization} · download ${formatBytes(spec.sizeBytes)} · needs ~${formatRam(spec.minRamMb)} RAM (${formatRam(spec.recommendedRamMb)} recommended)" +
                if (spec.gated) " · gated: needs a Hugging Face token" else "",
            style = MaterialTheme.typography.labelSmall.copy(color = TextMuted)
        )
        when {
            installed != null -> {
                Text("On device: ${formatBytes(installed.sizeOnDisk)}", modifier = Modifier.testTag("installed_size_${spec.id}"), style = MaterialTheme.typography.labelMedium.copy(color = BrightEmerald))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!isActive) Button(onClick = onUse, modifier = Modifier.testTag("use_${spec.id}")) { Text("Use this model") }
                    OutlinedButton(onClick = onDelete, modifier = Modifier.testTag("delete_${spec.id}")) { Text("Delete") }
                }
            }
            download is DownloadUi.Progress -> {
                LinearProgressIndicator(progress = { download.fraction }, modifier = Modifier.fillMaxWidth().testTag("progress_${spec.id}"))
                Text("${formatBytes(download.bytesDone)} of ${formatBytes(download.totalBytes)}", style = MaterialTheme.typography.labelSmall.copy(color = TextSecondary))
                OutlinedButton(onClick = onCancel, modifier = Modifier.testTag("cancel_${spec.id}")) { Text("Cancel") }
            }
            download == DownloadUi.Queued || download == DownloadUi.Verifying -> {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().testTag("progress_${spec.id}"))
                Text(if (download == DownloadUi.Verifying) "Verifying…" else "Starting…", style = MaterialTheme.typography.labelSmall.copy(color = TextSecondary))
                OutlinedButton(onClick = onCancel, modifier = Modifier.testTag("cancel_${spec.id}")) { Text("Cancel") }
            }
            download is DownloadUi.Failed -> {
                Text(download.message, modifier = Modifier.testTag("error_${spec.id}"), style = MaterialTheme.typography.bodySmall.copy(color = CoralRed))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onDownload, modifier = Modifier.testTag("download_${spec.id}")) { Text("Retry") }
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.testTag("dismiss_${spec.id}")) { Text("Dismiss") }
                }
            }
            else -> Button(onClick = onDownload, modifier = Modifier.testTag("download_${spec.id}")) { Text("Download") }
        }
    }
}

@Composable
private fun CodexCard(viewModel: AiSetupViewModel) {
    val state by viewModel.codexState.collectAsStateWithLifecycle()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    SetupCard(modifier = Modifier.testTag("codex_card")) {
        Text("Codex (ChatGPT account)", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = TextPrimary))
        Text(
            "Sign in with your ChatGPT account to let Codex write insights instead of an on-device model. " +
                "Your sign-in is stored encrypted on this device. " +
                "When Codex is the selected backend, the app sends your income, insight amounts, and savings goals to OpenAI.",
            style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
        )
        when (val s = state) {
            CodexAuthState.SignedOut -> {
                Text("Signed out", modifier = Modifier.testTag("codex_status"), color = TextSecondary)
                Button(onClick = viewModel::startCodexSignIn, modifier = Modifier.testTag("codex_sign_in")) { Text("Sign in with ChatGPT") }
            }
            CodexAuthState.RequestingCode -> {
                Text("Requesting a sign-in code…", modifier = Modifier.testTag("codex_status"), color = TextSecondary)
                OutlinedButton(onClick = viewModel::cancelCodexSignIn, modifier = Modifier.testTag("codex_cancel")) { Text("Cancel") }
            }
            is CodexAuthState.AwaitingApproval -> {
                Text("Open the page below and enter this code:", modifier = Modifier.testTag("codex_status"), color = TextSecondary)
                Text(s.userCode, modifier = Modifier.testTag("codex_code"), style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold, color = BrightEmerald))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { uriHandler.openUri(s.verificationUrl) }, modifier = Modifier.testTag("codex_open_page")) { Text("Open verification page") }
                    OutlinedButton(onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Codex code", s.userCode))) } }, modifier = Modifier.testTag("codex_copy_code")) { Text("Copy code") }
                }
                Text(s.verificationUrl, style = MaterialTheme.typography.labelSmall.copy(color = TextMuted))
                Text("Waiting for approval… the code expires in 15 minutes.", style = MaterialTheme.typography.labelSmall.copy(color = TextMuted))
                OutlinedButton(onClick = viewModel::cancelCodexSignIn, modifier = Modifier.testTag("codex_cancel")) { Text("Cancel") }
            }
            is CodexAuthState.SignedIn -> {
                Text(
                    "Signed in" + (s.email?.let { " as $it" } ?: "") + (s.planType?.let { " · $it plan" } ?: ""),
                    modifier = Modifier.testTag("codex_status"), color = BrightEmerald, fontWeight = FontWeight.Bold
                )
                CodexModelPicker(viewModel)
                OutlinedButton(onClick = viewModel::signOutCodex, modifier = Modifier.testTag("codex_sign_out")) { Text("Sign out") }
            }
            is CodexAuthState.Error -> {
                Text(s.message, modifier = Modifier.testTag("codex_error"), color = CoralRed)
                Button(onClick = viewModel::startCodexSignIn, modifier = Modifier.testTag("codex_sign_in")) { Text(if (s.retryable) "Try again" else "Sign in again") }
            }
        }
    }
}

@Composable
private fun CodexModelPicker(viewModel: AiSetupViewModel) {
    val models by viewModel.codexModels.collectAsStateWithLifecycle()
    val selected by viewModel.codexSelectedModel.collectAsStateWithLifecycle()
    when (val m = models) {
        CodexModelsUi.Hidden -> Unit
        CodexModelsUi.Loading -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth().testTag("codex_models_loading"))
        CodexModelsUi.Unavailable -> Text(
            "Could not load your account's models. Codex will try its default models.",
            modifier = Modifier.testTag("codex_models_error"),
            style = MaterialTheme.typography.bodySmall.copy(color = TextMuted)
        )
        is CodexModelsUi.Loaded -> Column {
            Text("Model", style = MaterialTheme.typography.titleSmall.copy(color = TextPrimary, fontWeight = FontWeight.Bold))
            m.models.forEach { slug ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(selected = selected == slug, role = Role.RadioButton, onClick = { viewModel.selectCodexModel(slug) })
                        .testTag("codex_model_$slug")
                ) {
                    RadioButton(selected = selected == slug, onClick = null)
                    Text(slug, color = TextPrimary)
                }
            }
        }
    }
}

@Composable
internal fun SetupCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth().border(1.dp, SlateBorder, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SlateSurface)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

/** Search Hugging Face; only models this app's runtime can use are listed. */
@Composable
fun HuggingFaceSearchScreen(viewModel: AiSetupViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    var query by rememberSaveable { mutableStateOf("") }
    var token by rememberSaveable { mutableStateOf("") }
    val state by viewModel.search.collectAsStateWithLifecycle()
    val installed by viewModel.installed.collectAsStateWithLifecycle()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val active by viewModel.activeModel.collectAsStateWithLifecycle()
    val hasToken by viewModel.hasHfToken.collectAsStateWithLifecycle()
    val tokenError by viewModel.hfTokenError.collectAsStateWithLifecycle()
    val installedById = installed.associateBy { it.id }
    var pendingDelete by rememberSaveable { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("hf_search_list"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            TextButton(onClick = onBack, modifier = Modifier.testTag("search_back")) { Text("← Back to AI Setup") }
            Text("Search Hugging Face", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold, color = TextPrimary))
            Text(
                "Only GGUF models this app can run on a phone are shown: supported architectures and quantizations, a single file up to 4 GB.",
                style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    label = { Text("Model name, e.g. qwen, smollm") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.weight(1f).testTag("search_field")
                )
                Button(onClick = { viewModel.search(query) }, modifier = Modifier.testTag("search_button")) { Text("Search") }
            }
        }
        item {
            SetupCard(modifier = Modifier.testTag("hf_token_card")) {
                Text("Hugging Face token (optional)", style = MaterialTheme.typography.titleSmall.copy(color = TextPrimary, fontWeight = FontWeight.Bold))
                Text("Needed only for gated models. Stored encrypted on this device, never uploaded anywhere else.", style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary))
                if (hasToken) {
                    Text("Token saved", modifier = Modifier.testTag("hf_token_saved"), color = BrightEmerald)
                    OutlinedButton(onClick = viewModel::clearHfToken, modifier = Modifier.testTag("hf_token_clear")) { Text("Remove token") }
                } else {
                    OutlinedTextField(
                        value = token, onValueChange = { token = it },
                        label = { Text("hf_… token") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth().testTag("hf_token_field")
                    )
                    Button(onClick = { viewModel.saveHfToken(token); token = "" }, modifier = Modifier.testTag("hf_token_save")) { Text("Save token") }
                    tokenError?.let { Text(it, modifier = Modifier.testTag("hf_token_error"), color = CoralRed) }
                }
            }
        }
        when (val s = state) {
            SearchUiState.Idle -> item { Text("Type at least two characters and tap Search.", color = TextMuted) }
            SearchUiState.Loading -> item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth().testTag("search_loading")) }
            is SearchUiState.Error -> item { Text(s.message, modifier = Modifier.testTag("search_error"), color = CoralRed) }
            is SearchUiState.Results -> {
                if (s.items.isEmpty()) {
                    item { Text("No Android-compatible models found for “${s.query}”.", modifier = Modifier.testTag("search_empty"), color = TextSecondary) }
                } else {
                    items(s.items, key = { it.repoId }) { result ->
                        Column(modifier = Modifier.testTag("result_${result.repoId}"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "${result.repoId}  ·  ${formatCount(result.downloads)} downloads" +
                                    if (result.access == RepoAccess.GATED_NEEDS_TOKEN) "  ·  gated" else "",
                                style = MaterialTheme.typography.labelMedium.copy(color = TextSecondary)
                            )
                            result.models.forEach { spec ->
                                ModelCard(
                                    spec = spec,
                                    installed = installedById[spec.id],
                                    isActive = active?.id == spec.id,
                                    download = downloads[spec.id]?.state,
                                    onDownload = { viewModel.download(spec) },
                                    onCancel = { viewModel.cancelDownload(spec) },
                                    onDismiss = { viewModel.dismissFailure(spec) },
                                    onUse = { viewModel.setActive(spec.id) },
                                    onDelete = { pendingDelete = spec.id },
                                )
                            }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    pendingDelete?.let { id ->
        ConfirmDeleteDialog(id, installedById[id], isActive = active?.id == id, viewModel = viewModel, onClose = { pendingDelete = null })
    }
}

internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1L shl 20 -> String.format(Locale.US, "%.0f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1L shl 10 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

internal fun formatRam(mb: Int): String = if (mb >= 1024) String.format(Locale.US, "%.1f GB", mb / 1024.0) else "$mb MB"

private fun formatCount(n: Long): String = when {
    n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1_000_000.0)
    n >= 1_000 -> String.format(Locale.US, "%.1fk", n / 1_000.0)
    else -> n.toString()
}
