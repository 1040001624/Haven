package sh.haven.feature.mail

import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.ReplyAll
import androidx.compose.material.icons.automirrored.filled.LabelImportant
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Drafts
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.haven.core.mail.MailEngine
import sh.haven.core.mail.MailFolder
import sh.haven.core.mail.MailFolderRole
import sh.haven.core.mail.MailMessage
import java.text.SimpleDateFormat
import java.util.Calendar
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
    val baseFontSize by viewModel.terminalFontSize.collectAsState()
    val mailFontScale by viewModel.mailFontScale.collectAsState()

    LaunchedEffect(pendingProfileId) {
        viewModel.setPendingEmailProfile(pendingProfileId)
    }

    // Compose/reply/forward overlays everything as a full-screen pane (CP-7).
    ui.compose?.let { draft ->
        ComposeView(
            draft = draft,
            accounts = ui.accounts,
            onTo = viewModel::updateTo,
            onCc = viewModel::updateCc,
            onBcc = viewModel::updateBcc,
            onSubject = viewModel::updateSubject,
            onBody = viewModel::updateBody,
            onToggleCcBcc = viewModel::toggleCcBcc,
            onFromSelected = viewModel::setComposeFrom,
            onSend = viewModel::send,
            onDiscard = viewModel::discardCompose,
            onAddAttachment = viewModel::addDeviceAttachment,
            onRemoveAttachment = viewModel::removeAttachment,
            modifier = mailModifier,
        )
        return
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val sentMessage = stringResource(R.string.mail_sent_ok)
    LaunchedEffect(ui.sentSignal) {
        if (ui.sentSignal > 0) snackbarHostState.showSnackbar(sentMessage)
    }
    // One-shot snackbar for delete results.
    LaunchedEffect(ui.snackbarMessage) {
        ui.snackbarMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeSnackbar()
        }
    }
    // Back exits selection mode instead of leaving the message list.
    BackHandler(enabled = ui.selectionMode) { viewModel.clearSelection() }

    // Save-an-attachment-to-device: tapping Save in the reader stashes the chosen
    // attachment, launches the SAF "create document" picker, then writes the
    // decoded bytes straight to the user-picked Uri (scoped-storage-safe).
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Hoist the snackbar strings out of the callback: reading resource values off
    // LocalContext.current inside a lambda trips the LocalContextGetResourceValueCall
    // lint (it won't recompose on a locale change). The saved message keeps its
    // %1$s filename placeholder, filled in at callback time.
    val attachmentSavedTemplate = stringResource(R.string.mail_attachment_saved)
    val attachmentSaveFailedMsg = stringResource(R.string.mail_attachment_save_failed)
    var pendingSave by remember { mutableStateOf<MailAttachmentInfo?>(null) }
    val saveAttachmentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        val att = pendingSave
        pendingSave = null
        if (uri != null && att != null) {
            scope.launch {
                val extracted = viewModel.loadAttachmentBytes(att.index)
                val ok = extracted != null && withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { it.write(extracted.bytes) }
                            ?: error("no output stream")
                    }.isSuccess
                }
                snackbarHostState.showSnackbar(
                    if (ok) attachmentSavedTemplate.format(att.filename)
                    else attachmentSaveFailedMsg,
                )
            }
        }
    }

    val title = when (ui.view) {
        MailViewModel.View.FOLDERS -> "Mail"
        MailViewModel.View.MESSAGES -> ui.selectedFolder?.name ?: "Mail"
        MailViewModel.View.READER -> ui.openMessage?.subject ?: "Message"
        MailViewModel.View.COMPOSE -> ""
    }

    Scaffold(
        modifier = mailModifier.fillMaxSize(),
        topBar = {
            if (ui.view == MailViewModel.View.MESSAGES && ui.selectionMode) {
                MailSelectionTopBar(
                    count = ui.selectedMessageIds.size,
                    total = ui.messages.size,
                    onClose = viewModel::clearSelection,
                    onSelectAll = viewModel::selectAllMessages,
                    onDelete = viewModel::deleteSelected,
                )
            } else {
            TopAppBar(
                title = {
                    if (ui.view == MailViewModel.View.FOLDERS) {
                        AccountSwitcher(ui.accounts, ui.activeAccount, viewModel::selectAccount)
                    } else {
                        // Folder name / subject, with the active account as a subtitle so it's
                        // always clear which account's mail you're reading.
                        Column {
                            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            ui.activeAccount?.let { acc ->
                                Text(
                                    acc.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                },
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
                actions = {
                    when (ui.view) {
                        MailViewModel.View.FOLDERS, MailViewModel.View.MESSAGES -> {
                            if (ui.activeAccount != null) {
                                IconButton(onClick = viewModel::refresh) {
                                    Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.mail_refresh))
                                }
                            }
                            if (ui.canCompose) {
                                IconButton(onClick = viewModel::startCompose) {
                                    Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.mail_compose))
                                }
                            }
                            if (ui.activeAccount != null) {
                                var menuOpen by remember { mutableStateOf(false) }
                                Box {
                                    IconButton(onClick = { menuOpen = true }) {
                                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.mail_more))
                                    }
                                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.mail_delete_to_bin)) },
                                            trailingIcon = {
                                                if (ui.deleteToBin) Icon(Icons.Filled.Check, contentDescription = null)
                                            },
                                            onClick = {
                                                viewModel.setDeleteToBin(!ui.deleteToBin)
                                                menuOpen = false
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        MailViewModel.View.READER -> {
                            val open = ui.openMessage
                            val attribution = if (open != null) {
                                stringResource(
                                    R.string.mail_reply_quote_header,
                                    open.dateMillis?.let { formatDate(it) } ?: "",
                                    open.from,
                                )
                            } else {
                                ""
                            }
                            val forwardHeader = stringResource(R.string.mail_forward_header)
                            IconButton(onClick = { viewModel.startReply(replyAll = false, attributionLine = attribution) }) {
                                Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = stringResource(R.string.mail_reply))
                            }
                            IconButton(onClick = { viewModel.startReply(replyAll = true, attributionLine = attribution) }) {
                                Icon(Icons.AutoMirrored.Filled.ReplyAll, contentDescription = stringResource(R.string.mail_reply_all))
                            }
                            IconButton(onClick = { viewModel.startForward(forwardHeader = forwardHeader) }) {
                                Icon(Icons.AutoMirrored.Filled.Forward, contentDescription = stringResource(R.string.mail_forward))
                            }
                            // Delete is immediate (one tap; Gmail keeps it in Trash).
                            IconButton(onClick = { viewModel.deleteOpenMessage() }) {
                                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.mail_delete))
                            }
                        }
                        else -> {}
                    }
                },
            )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                ui.error != null && ui.folders.isEmpty() -> CenterText(ui.error!!)
                ui.loading && ui.view == MailViewModel.View.FOLDERS && ui.folders.isEmpty() ->
                    Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                else -> when (ui.view) {
                    MailViewModel.View.FOLDERS -> FolderList(ui.folders, viewModel::openFolder)
                    MailViewModel.View.MESSAGES -> MessageList(
                        messages = ui.messages,
                        loading = ui.loading,
                        fontSize = baseFontSize * mailFontScale,
                        hasMore = ui.hasMore,
                        loadingOlder = ui.loadingOlder,
                        selectionMode = ui.selectionMode,
                        selectedIds = ui.selectedMessageIds,
                        onZoom = viewModel::zoomMailFont,
                        onZoomEnd = viewModel::commitMailFontScale,
                        onLoadOlder = viewModel::loadOlder,
                        onOpen = viewModel::openMessage,
                        onToggleSelect = viewModel::toggleSelection,
                        onEnterSelection = viewModel::enterSelection,
                    )
                    MailViewModel.View.READER -> ui.openMessage?.let { msg ->
                        MessageReader(msg) { att ->
                            pendingSave = att
                            saveAttachmentLauncher.launch(att.filename)
                        }
                    }
                    MailViewModel.View.COMPOSE -> {} // handled by the early full-screen branch above
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
                    // \Noselect containers are filtered out upstream; the guard keeps a
                    // stray non-selectable folder from a non-clickable, crash-free row.
                    .then(if (folder.selectable) Modifier.clickable { onOpen(folder) } else Modifier)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(folderIcon(folder.role), contentDescription = null, modifier = Modifier.padding(end = 16.dp))
                Text(folder.name, style = MaterialTheme.typography.bodyLarge)
            }
            Divider()
        }
    }
}

/** Icon for a folder's special-use [role] (Inbox/Sent/Drafts/…); plain folder for user labels. */
private fun folderIcon(role: MailFolderRole) = when (role) {
    MailFolderRole.INBOX -> Icons.Filled.Inbox
    MailFolderRole.STARRED -> Icons.Filled.Star
    MailFolderRole.IMPORTANT -> Icons.AutoMirrored.Filled.LabelImportant
    MailFolderRole.SENT -> Icons.AutoMirrored.Filled.Send
    MailFolderRole.DRAFTS -> Icons.Filled.Drafts
    MailFolderRole.ARCHIVE -> Icons.Filled.Archive
    MailFolderRole.SPAM -> Icons.Filled.Report
    MailFolderRole.TRASH -> Icons.Filled.Delete
    MailFolderRole.NONE -> Icons.Filled.Folder
}

/** Contextual top bar shown while messages are selected: count, select-all, and bulk delete (confirmed). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MailSelectionTopBar(
    count: Int,
    total: Int,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onDelete: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    TopAppBar(
        title = { Text(stringResource(R.string.mail_selection_count, count)) },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.mail_selection_clear))
            }
        },
        actions = {
            if (count < total) {
                IconButton(onClick = onSelectAll) {
                    Icon(Icons.Filled.DoneAll, contentDescription = stringResource(R.string.mail_select_all))
                }
            }
            IconButton(onClick = { confirmDelete = true }) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.mail_delete))
            }
        },
    )
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.mail_delete_selection_title)) },
            text = { Text(stringResource(R.string.mail_delete_selection_message, count)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text(stringResource(R.string.mail_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.mail_cancel)) }
            },
        )
    }
}

/**
 * One compact row per message — sender · subject · time on a single line, sized to
 * the terminal font ([fontSize] sp, after the pinch-zoom factor) so the list reads at
 * roughly one termlib row per email instead of a tall multi-line card. Unread = bold
 * sender + emphasised subject. Two-finger pinch zooms via [onZoom]/[onZoomEnd]; a
 * single-finger drag still scrolls (only multi-touch events are consumed).
 */
@Composable
private fun MessageList(
    messages: List<MailMessage>,
    loading: Boolean,
    fontSize: Float,
    hasMore: Boolean,
    loadingOlder: Boolean,
    selectionMode: Boolean,
    selectedIds: Set<String>,
    onZoom: (Float) -> Unit,
    onZoomEnd: () -> Unit,
    onLoadOlder: () -> Unit,
    onOpen: (MailMessage) -> Unit,
    onToggleSelect: (String) -> Unit,
    onEnterSelection: (String) -> Unit,
) {
    if (messages.isEmpty() && loading) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        return
    }
    if (messages.isEmpty()) {
        CenterText(stringResource(R.string.mail_no_messages))
        return
    }
    val fs = fontSize.sp
    val lh = (fontSize + 4).sp
    val timeFs = (fontSize - 2).coerceAtLeast(9f).sp
    LazyColumn(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // Pinch-to-zoom: only consume when ≥2 pointers are down, so a
                // one-finger drag is left for the LazyColumn to scroll.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var pinched = false
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.count { it.pressed } >= 2) {
                            val zoom = event.calculateZoom()
                            if (zoom != 1f) {
                                onZoom(zoom)
                                pinched = true
                                event.changes.forEach { it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                    if (pinched) onZoomEnd()
                }
            },
    ) {
        items(messages, key = { it.id }) { msg ->
            // Memoised per message (keyed on the timestamp), so the pinch-zoom's
            // continuous recomposition never re-runs the date formatting.
            val timeLabel = remember(msg.timeSeconds) {
                if (msg.timeSeconds > 0) formatTimeCompact(msg.timeSeconds * 1000) else ""
            }
            val selected = msg.id in selectedIds
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(
                        if (selected) Modifier.background(MaterialTheme.colorScheme.primaryContainer)
                        else Modifier,
                    )
                    .combinedClickable(
                        onClick = { if (selectionMode) onToggleSelect(msg.id) else onOpen(msg) },
                        onLongClick = { onEnterSelection(msg.id) },
                    )
                    .padding(horizontal = 12.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selectionMode) {
                    Icon(
                        if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 8.dp).size((fontSize + 2).dp),
                    )
                }
                Text(
                    msg.from?.let { if (it.name.isNotBlank()) it.name else it.address } ?: "(unknown)",
                    fontSize = fs,
                    lineHeight = lh,
                    fontWeight = if (msg.unread) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(0.38f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    msg.subject.ifBlank { "(no subject)" },
                    fontSize = fs,
                    lineHeight = lh,
                    fontWeight = if (msg.unread) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (msg.unread) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (msg.numAttachments > 0) {
                    Icon(
                        Icons.Filled.AttachFile,
                        contentDescription = "Has attachments",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp).size(fontSize.dp),
                    )
                }
                if (timeLabel.isNotEmpty()) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        timeLabel,
                        fontSize = timeFs,
                        lineHeight = lh,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
        if (hasMore) {
            item(key = "__load_older__") {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !loadingOlder) { onLoadOlder() }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (loadingOlder) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Text(
                            stringResource(R.string.mail_load_older),
                            fontSize = fs,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageReader(msg: ParsedMessage, onSaveAttachment: (MailAttachmentInfo) -> Unit) {
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                ) {
                    Icon(Icons.Filled.AttachFile, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(att.filename, style = MaterialTheme.typography.bodySmall)
                        if (att.sizeBytes > 0) {
                            Text(
                                formatBytes(att.sizeBytes),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    IconButton(onClick = { onSaveAttachment(att) }) {
                        Icon(Icons.Filled.Save, contentDescription = stringResource(R.string.mail_save_attachment))
                    }
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

/**
 * Full-screen compose pane (portrait: no FAB, one pane at a time). Send lives in
 * the app bar with an in-flight spinner; Close/system-back confirm a discard when
 * the draft is dirty. Send failures stay in-form ([ComposeDraft.sendError]); send
 * success is signalled by the parent's snackbar after this pane closes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComposeView(
    draft: ComposeDraft,
    accounts: List<MailViewModel.MailAccount>,
    onTo: (String) -> Unit,
    onCc: (String) -> Unit,
    onBcc: (String) -> Unit,
    onSubject: (String) -> Unit,
    onBody: (String) -> Unit,
    onToggleCcBcc: () -> Unit,
    onFromSelected: (String) -> Unit,
    onSend: (String) -> Unit,
    onDiscard: () -> Unit,
    onAddAttachment: (uri: String, name: String, mimeType: String, size: Long) -> Unit,
    onRemoveAttachment: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDiscardConfirm by remember { mutableStateOf(false) }
    val recipientsRequired = stringResource(R.string.mail_recipients_required)
    val context = LocalContext.current
    val attachLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val (name, size) = queryNameSize(context, uri)
            val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
            onAddAttachment(uri.toString(), name, mime, size)
        }
    }

    fun attemptDiscard() {
        if (draft.isDirty) showDiscardConfirm = true else onDiscard()
    }

    BackHandler { attemptDiscard() }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.mail_compose), maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = { attemptDiscard() }) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.mail_close))
                    }
                },
                actions = {
                    IconButton(
                        onClick = { attachLauncher.launch(arrayOf("*/*")) },
                        enabled = !draft.sending,
                    ) {
                        Icon(Icons.Filled.AttachFile, contentDescription = stringResource(R.string.mail_attach_file))
                    }
                    if (draft.sending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp).padding(end = 12.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        IconButton(
                            onClick = { onSend(recipientsRequired) },
                            enabled = draft.to.isNotBlank(),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.mail_send))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            ComposeFromRow(accounts, draft.fromProfileId, onFromSelected)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = draft.to,
                onValueChange = onTo,
                label = { Text(stringResource(R.string.mail_to)) },
                singleLine = true,
                isError = draft.sendError != null,
                supportingText = {
                    Text(draft.sendError ?: stringResource(R.string.mail_recipients_hint))
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                trailingIcon = {
                    TextButton(onClick = onToggleCcBcc) { Text(stringResource(R.string.mail_cc_bcc)) }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (draft.showCcBcc) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = draft.cc,
                    onValueChange = onCc,
                    label = { Text(stringResource(R.string.mail_cc)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = draft.bcc,
                    onValueChange = onBcc,
                    label = { Text(stringResource(R.string.mail_bcc)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = draft.subject,
                onValueChange = onSubject,
                label = { Text(stringResource(R.string.mail_subject)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = draft.body,
                onValueChange = onBody,
                label = { Text(stringResource(R.string.mail_message)) },
                minLines = 8,
                modifier = Modifier.fillMaxWidth(),
            )
            if (draft.attachments.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                draft.attachments.forEachIndexed { i, a ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    ) {
                        Icon(Icons.Filled.AttachFile, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(a.displayName, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (a.sizeBytes > 0) {
                                Text(
                                    formatBytes(a.sizeBytes),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        IconButton(onClick = { onRemoveAttachment(i) }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.mail_remove_attachment))
                        }
                    }
                }
            }
        }
    }

    if (showDiscardConfirm) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirm = false },
            title = { Text(stringResource(R.string.mail_discard_title)) },
            text = { Text(stringResource(R.string.mail_discard_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardConfirm = false
                    onDiscard()
                }) { Text(stringResource(R.string.mail_discard_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirm = false }) {
                    Text(stringResource(R.string.mail_discard_cancel))
                }
            },
        )
    }
}

/** Folder-view title: the active account, with a dropdown to switch when several are connected. */
@Composable
private fun AccountSwitcher(
    accounts: List<MailViewModel.MailAccount>,
    active: MailViewModel.MailAccount?,
    onSelect: (String) -> Unit,
) {
    val label = active?.label ?: "Mail"
    if (accounts.size <= 1) {
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        return
    }
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier.clickable { expanded = true },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = stringResource(R.string.mail_switch_account))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            accounts.forEach { acc ->
                DropdownMenuItem(
                    text = { Text("${acc.label} · ${engineLabel(acc.engine)}") },
                    onClick = {
                        expanded = false
                        onSelect(acc.profileId)
                    },
                    leadingIcon = if (acc.profileId == active?.profileId) {
                        { Icon(Icons.Filled.Check, contentDescription = null) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

/** Compose "From" row: names the sending account; a dropdown when more than one is connected. */
@Composable
private fun ComposeFromRow(
    accounts: List<MailViewModel.MailAccount>,
    fromProfileId: String,
    onSelect: (String) -> Unit,
) {
    val selected = accounts.firstOrNull { it.profileId == fromProfileId }
    val selectedText = selected?.let { "${it.label} · ${engineLabel(it.engine)}" } ?: ""
    val multi = accounts.size > 1
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.mail_from),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (multi) Modifier.clickable { expanded = true } else Modifier)
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    selectedText,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (multi) Icon(Icons.Filled.ArrowDropDown, contentDescription = stringResource(R.string.mail_switch_account))
            }
            if (multi) {
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    accounts.forEach { acc ->
                        DropdownMenuItem(
                            text = { Text("${acc.label} · ${engineLabel(acc.engine)}") },
                            onClick = {
                                expanded = false
                                onSelect(acc.profileId)
                            },
                            leadingIcon = if (acc.profileId == fromProfileId) {
                                { Icon(Icons.Filled.Check, contentDescription = null) }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }
        Divider()
    }
}

/** Short engine tag shown next to an account label (Proton vs generic IMAP). */
@Composable
private fun engineLabel(engine: MailEngine): String = when (engine) {
    MailEngine.PROTON -> stringResource(R.string.mail_engine_proton)
    MailEngine.IMAP -> stringResource(R.string.mail_engine_imap)
}

@Composable
private fun CenterText(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Shared, pre-built date formatters. Constructing a `SimpleDateFormat` (and two
 * `Calendar`s) per row per recomposition was the message list's jank source —
 * during a pinch-zoom every visible row reformatted on every frame. These are
 * created once and reused; all access is on the Compose main thread, so the
 * (non-thread-safe) formatters and the single `Calendar` are safe to share.
 */
private object MailDateFormats {
    val time = SimpleDateFormat("HH:mm", Locale.getDefault())
    val dayMonth = SimpleDateFormat("d MMM", Locale.getDefault())
    val dayMonthYear = SimpleDateFormat("d/MM/yy", Locale.getDefault())
    val full = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())
    private val cal: Calendar = Calendar.getInstance()

    /** (sameDayAsNow, sameYearAsNow) for [millis], using one reused Calendar. */
    fun dayYear(millis: Long): Pair<Boolean, Boolean> {
        cal.timeInMillis = System.currentTimeMillis()
        val nowYear = cal.get(Calendar.YEAR)
        val nowDay = cal.get(Calendar.DAY_OF_YEAR)
        cal.timeInMillis = millis
        val sameYear = cal.get(Calendar.YEAR) == nowYear
        return (sameYear && cal.get(Calendar.DAY_OF_YEAR) == nowDay) to sameYear
    }
}

private fun formatDate(millis: Long): String = MailDateFormats.full.format(Date(millis))

/** A short list-row timestamp: time of day for today, "d MMM" this year, else "d/MM/yy". */
private fun formatTimeCompact(millis: Long): String {
    val (sameDay, sameYear) = MailDateFormats.dayYear(millis)
    val fmt = when {
        sameDay -> MailDateFormats.time
        sameYear -> MailDateFormats.dayMonth
        else -> MailDateFormats.dayMonthYear
    }
    return fmt.format(Date(millis))
}

/** Human-readable byte size for an attachment row. */
private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> String.format(Locale.getDefault(), "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

/** Read a SAF document's display name + size (size = -1 when the provider omits it). */
private fun queryNameSize(context: android.content.Context, uri: android.net.Uri): Pair<String, Long> {
    var name: String? = null
    var size = -1L
    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
        if (c.moveToFirst()) {
            if (nameIdx >= 0) name = c.getString(nameIdx)
            if (sizeIdx >= 0 && !c.isNull(sizeIdx)) size = c.getLong(sizeIdx)
        }
    }
    return (name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "attachment") to size
}
