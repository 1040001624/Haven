package sh.haven.feature.mail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import sh.haven.core.mail.MailFolder
import sh.haven.core.mail.MailMessage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Read-only Proton mail client (v1): folder list → message list → reader. The
 * reader shows plain text only (see [ParsedMessage.bodyText]) — no WebView, so
 * remote images/scripts never load. No compose/send in v1.
 *
 * Mirrors the rclone→SFTP pattern: the connect happens in ConnectionsViewModel;
 * this screen consumes an already-connected session via [pendingProfileId].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MailScreen(
    pendingProfileId: String? = null,
    mailModifier: Modifier = Modifier,
    viewModel: MailViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsState()

    LaunchedEffect(pendingProfileId) {
        viewModel.setPendingEmailProfile(pendingProfileId)
    }

    val title = when (ui.view) {
        MailViewModel.View.FOLDERS -> "Mail"
        MailViewModel.View.MESSAGES -> ui.selectedFolder?.name ?: "Mail"
        MailViewModel.View.READER -> ui.openMessage?.subject ?: "Message"
    }

    Scaffold(
        modifier = mailModifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (ui.view != MailViewModel.View.FOLDERS) {
                        IconButton(onClick = {
                            when (ui.view) {
                                MailViewModel.View.READER -> viewModel.closeMessage()
                                MailViewModel.View.MESSAGES -> viewModel.backToFolders()
                                else -> {}
                            }
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                ui.error != null && ui.folders.isEmpty() -> CenterText(ui.error!!)
                ui.loading && ui.view == MailViewModel.View.FOLDERS && ui.folders.isEmpty() ->
                    Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                else -> when (ui.view) {
                    MailViewModel.View.FOLDERS -> FolderList(ui.folders, viewModel::openFolder)
                    MailViewModel.View.MESSAGES -> MessageList(ui.messages, ui.loading, viewModel::openMessage)
                    MailViewModel.View.READER -> ui.openMessage?.let { MessageReader(it) }
                }
            }
            if (ui.error != null && ui.folders.isNotEmpty()) {
                // Non-fatal error banner over content.
                Text(
                    ui.error!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp),
                )
            }
        }
    }
}

@Composable
private fun FolderList(folders: List<MailFolder>, onOpen: (MailFolder) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(folders, key = { it.id }) { folder ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(folder) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.padding(end = 16.dp))
                Text(folder.name, style = MaterialTheme.typography.bodyLarge)
            }
            Divider()
        }
    }
}

@Composable
private fun MessageList(messages: List<MailMessage>, loading: Boolean, onOpen: (MailMessage) -> Unit) {
    if (messages.isEmpty() && loading) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        return
    }
    if (messages.isEmpty()) {
        CenterText("No messages")
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(messages, key = { it.id }) { msg ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(msg) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        msg.from?.let { if (it.name.isNotBlank()) it.name else it.address } ?: "(unknown)",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (msg.unread) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (msg.numAttachments > 0) {
                        Icon(
                            Icons.Filled.AttachFile,
                            contentDescription = "Has attachments",
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
                Text(
                    msg.subject.ifBlank { "(no subject)" },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (msg.unread) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (msg.timeSeconds > 0) {
                    Text(
                        formatDate(msg.timeSeconds * 1000),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Divider()
        }
    }
}

@Composable
private fun MessageReader(msg: ParsedMessage) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(msg.subject, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(msg.from, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (msg.to.isNotEmpty()) {
            Text(
                "To: ${msg.to.joinToString(", ")}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        msg.dateMillis?.let {
            Text(formatDate(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (msg.attachments.isNotEmpty()) {
            Divider(Modifier.padding(vertical = 8.dp))
            msg.attachments.forEach { att ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                    Icon(Icons.Filled.AttachFile, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text(att.filename, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Divider(Modifier.padding(vertical = 8.dp))
        if (msg.bodyWasHtml) {
            Text(
                "(HTML message shown as text — remote content blocked)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        Text(msg.bodyText, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun CenterText(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun formatDate(millis: Long): String =
    SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(millis))
