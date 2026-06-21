package sh.haven.feature.connections

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import sh.haven.core.rclone.ParsedRemote

/**
 * Imports remotes from a Linux `rclone.conf` so the user doesn't reconfigure
 * them in Haven (#251). Paste the file or pick it; each chosen remote becomes
 * an rclone remote + an RCLONE connection profile.
 */
@Composable
fun ImportRcloneConfigDialog(
    onDismiss: () -> Unit,
    viewModel: RcloneConfigViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.importState.collectAsStateWithLifecycle()
    var pasted by remember { mutableStateOf("") }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            if (!text.isNullOrBlank()) { pasted = text; viewModel.loadConfig(text) }
        }
    }

    fun close() {
        viewModel.resetImport()
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = ::close,
        title = { Text(stringRes(context, R.string.rclone_import_title)) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                when (val s = state) {
                    is RcloneConfigViewModel.ImportState.Loaded -> SelectRemotes(s, viewModel)
                    is RcloneConfigViewModel.ImportState.Importing ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.height(20.dp))
                            Spacer(Modifier.fillMaxWidth(0.05f))
                            Text(stringRes(context, R.string.rclone_import_title))
                        }
                    is RcloneConfigViewModel.ImportState.Encrypted ->
                        Text(stringRes(context, R.string.rclone_import_encrypted))
                    is RcloneConfigViewModel.ImportState.Failed ->
                        Text(s.message, color = MaterialTheme.colorScheme.error)
                    is RcloneConfigViewModel.ImportState.Done ->
                        Text(
                            context.getString(
                                R.string.rclone_import_done,
                                s.created.size, s.skipped.size, s.failed.size,
                            ) + (s.failed.entries.joinToString("") { "\n• ${it.key}: ${it.value}" }),
                        )
                    RcloneConfigViewModel.ImportState.Idle -> {
                        Text(stringRes(context, R.string.rclone_import_intro), style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { picker.launch("*/*") }) {
                            Text(stringRes(context, R.string.rclone_import_pick_file))
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = pasted,
                            onValueChange = { pasted = it },
                            label = { Text(stringRes(context, R.string.rclone_import_paste_label)) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            when (val s = state) {
                RcloneConfigViewModel.ImportState.Idle ->
                    TextButton(
                        onClick = { viewModel.loadConfig(pasted) },
                        enabled = pasted.isNotBlank(),
                    ) { Text(stringRes(context, R.string.rclone_import_parse)) }
                is RcloneConfigViewModel.ImportState.Done -> TextButton(onClick = ::close) { Text("Done") }
                else -> {}
            }
        },
        dismissButton = { TextButton(onClick = ::close) { Text("Close") } },
    )
}

@Composable
private fun SelectRemotes(
    loaded: RcloneConfigViewModel.ImportState.Loaded,
    viewModel: RcloneConfigViewModel,
) {
    val context = LocalContext.current
    // Default-select everything importable (has a type, not already added).
    val checked = remember(loaded) {
        mutableStateMapOf<String, Boolean>().apply {
            loaded.remotes.forEach { put(it.name, it.type.isNotBlank() && it.name !in loaded.existing) }
        }
    }
    Column {
        loaded.remotes.forEach { remote ->
            val already = remote.name in loaded.existing
            val noType = remote.type.isBlank()
            val disabled = already || noType
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Checkbox(
                    checked = checked[remote.name] == true,
                    onCheckedChange = if (disabled) null else { v -> checked[remote.name] = v },
                    enabled = !disabled,
                )
                Column(Modifier.padding(start = 4.dp)) {
                    Text(remote.name, style = MaterialTheme.typography.bodyMedium)
                    val sub = when {
                        already -> "${remote.type} · ${stringRes(context, R.string.rclone_import_already_added)}"
                        noType -> stringRes(context, R.string.rclone_import_no_type)
                        else -> remote.type
                    }
                    Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        val selected: List<ParsedRemote> = loaded.remotes.filter { checked[it.name] == true }
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(
                onClick = { viewModel.importRemotes(selected) },
                enabled = selected.isNotEmpty(),
            ) { Text(context.getString(R.string.rclone_import_select_count, selected.size)) }
        }
    }
}

private fun stringRes(context: android.content.Context, id: Int): String = context.getString(id)
