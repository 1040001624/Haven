package sh.haven.feature.settings

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import sh.haven.core.data.db.entities.AgentAuditEvent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentActivityScreen(
    onBack: () -> Unit,
    viewModel: AgentActivityViewModel = hiltViewModel(),
) {
    val events by viewModel.events.collectAsState()
    val standingPolicies by viewModel.standingPolicies.collectAsState()
    var showClearDialog by remember { mutableStateOf(false) }
    var expandedId by remember { mutableStateOf<Long?>(null) }

    // Opaque background so the screen renders correctly when mounted as
    // an overlay over the pager (HavenNavHost) — without it the
    // Connections list bled through (#138 follow-up).
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        TopAppBar(
            title = { Text(stringResource(R.string.settings_agent_activity_screen_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_cd_back))
                }
            },
            actions = {
                if (events.isNotEmpty()) {
                    IconButton(onClick = { showClearDialog = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.settings_agent_activity_cd_clear))
                    }
                }
            },
        )

        // Live Tier-3 standing policies — the kill-switch. Pinned above the
        // audit list so a no-prompt grant is never out of sight while active.
        val nowMs = System.currentTimeMillis()
        val livePolicies = standingPolicies.filter { it.enabled && it.expiresAt > nowMs }
        if (livePolicies.isNotEmpty()) {
            StandingPoliciesSection(livePolicies, onRevoke = viewModel::revokePolicy)
        }

        if (events.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.settings_agent_activity_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            // Group events into "sessions" — runs of activity separated
            // by a quiet gap. The view sorts newest-first, so we walk
            // pairs in that order: a row starts a new section if its
            // (older) neighbour is more than SESSION_GAP_MS away.
            val sessions = remember(events) { groupIntoSessions(events) }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                sessions.forEachIndexed { sectionIndex, section ->
                    item(key = "header-$sectionIndex-${section.first().id}") {
                        SessionHeader(section)
                    }
                    items(section, key = { it.id }) { event ->
                        EventRow(
                            event = event,
                            expanded = expandedId == event.id,
                            onClick = {
                                expandedId = if (expandedId == event.id) null else event.id
                            },
                        )
                    }
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(R.string.settings_agent_activity_clear_dialog_title)) },
            text = { Text(stringResource(R.string.settings_agent_activity_clear_dialog_text)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearAll()
                    showClearDialog = false
                }) { Text(stringResource(R.string.settings_action_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

/**
 * The Tier-3 kill-switch: every live standing policy (a no-prompt grant)
 * with its scope and a one-tap Revoke. Tinted with the error container so
 * "an agent can act without asking right now" is visually unmissable.
 */
@Composable
private fun StandingPoliciesSection(
    policies: List<sh.haven.core.data.db.entities.StandingPolicy>,
    onRevoke: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)),
    ) {
        Text(
            text = stringResource(R.string.settings_standing_policies_header),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 2.dp),
        )
        policies.forEach { policy ->
            val toolNames = remember(policy.toolNamesJson) {
                runCatching {
                    val arr = org.json.JSONArray(policy.toolNamesJson)
                    (0 until arr.length()).joinToString(", ") { arr.optString(it) }
                }.getOrDefault(policy.toolNamesJson)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = policy.description,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = toolNames,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(
                            R.string.settings_standing_policy_meta,
                            policy.clientHint,
                            policy.maxCallsPerMinute,
                            DateUtils.getRelativeTimeSpanString(policy.expiresAt).toString(),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { onRevoke(policy.id) }) {
                    Text(stringResource(R.string.settings_standing_policy_revoke))
                }
            }
        }
    }
}

@Composable
private fun SessionHeader(events: List<AgentAuditEvent>) {
    val first = events.first() // newest in section
    val last = events.last()   // oldest in section
    val clientHint = first.clientHint ?: stringResource(R.string.settings_agent_activity_unknown_client)
    val span = if (events.size == 1) {
        DateUtils.getRelativeTimeSpanString(first.timestamp).toString()
    } else {
        "${events.size} calls · " +
            DateUtils.getRelativeTimeSpanString(first.timestamp).toString()
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = clientHint,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = span,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EventRow(
    event: AgentAuditEvent,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutcomeIcon(event.outcome)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = event.toolName ?: event.method,
                    style = MaterialTheme.typography.bodyMedium,
                )
                val subtitle = event.resultSummary ?: event.errorMessage ?: ""
                if (subtitle.isNotEmpty()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = "${event.durationMs} ms",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            Spacer(modifier = Modifier.height(8.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                    )
                    .padding(12.dp),
            ) {
                MetaLine("method", event.method)
                event.toolName?.let { MetaLine("tool", it) }
                MetaLine("outcome", event.outcome.name.lowercase())
                event.argsJson?.let { args ->
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.settings_agent_activity_args_redacted),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SelectionContainer {
                        Text(
                            text = args,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OutcomeIcon(outcome: AgentAuditEvent.Outcome) {
    val (icon, tint) = when (outcome) {
        AgentAuditEvent.Outcome.OK ->
            Icons.Filled.CheckCircle to Color(0xFF4CAF50)
        AgentAuditEvent.Outcome.DENIED ->
            Icons.Filled.Block to MaterialTheme.colorScheme.tertiary
        AgentAuditEvent.Outcome.ERROR ->
            Icons.Filled.ErrorOutline to MaterialTheme.colorScheme.error
    }
    Icon(imageVector = icon, contentDescription = null, tint = tint)
}

@Composable
private fun MetaLine(label: String, value: String) {
    Row {
        Text(
            text = "$label: ",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private const val SESSION_GAP_MS = 60_000L

private fun groupIntoSessions(events: List<AgentAuditEvent>): List<List<AgentAuditEvent>> {
    if (events.isEmpty()) return emptyList()
    val sessions = mutableListOf<MutableList<AgentAuditEvent>>()
    var current = mutableListOf<AgentAuditEvent>().also { sessions.add(it) }
    // events arrive newest-first; a gap means an older event is more
    // than SESSION_GAP_MS before the previous (newer) one we kept.
    var prevTimestamp = events.first().timestamp
    for (e in events) {
        if (prevTimestamp - e.timestamp > SESSION_GAP_MS) {
            current = mutableListOf<AgentAuditEvent>().also { sessions.add(it) }
        }
        current.add(e)
        prevTimestamp = e.timestamp
    }
    return sessions
}
