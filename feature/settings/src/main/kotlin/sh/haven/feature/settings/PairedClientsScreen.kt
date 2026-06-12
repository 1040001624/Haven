package sh.haven.feature.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Lists every MCP client the user has paired (via the first-connect
 * pairing prompt) and lets them, per client:
 *
 * - toggle **Skip approval prompts** — the persistent counterpart to the
 *   consent sheet's session-only "Allow all from X until restart"
 *   checkbox. OFF by default; turning it on means that client's tool
 *   calls (including destructive ones) run without a per-call prompt
 *   until toggled back off or the client is un-paired;
 * - **un-pair** the client with the bin icon — one tap, the row removes
 *   immediately, and the client must be re-approved on next connect (which
 *   also revokes any standing auto-approval).
 *
 * Mounted as an overlay over the pager from [SettingsScreen], same as
 * [AgentActivityScreen]; hence the opaque background.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairedClientsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val allClients by viewModel.mcpAllowedClients.collectAsState()
    val bypassed by viewModel.mcpBypassConsentClients.collectAsState()
    // Optimistically hide a row the instant its bin is tapped, so the list
    // reflects the un-pair immediately rather than waiting on the DataStore
    // round-trip. The un-pair itself is persisted by unpairMcpClient (the
    // allowlist is the source of truth); this only fixes the display lag.
    // Resets on screen re-entry, where the persisted flow is authoritative.
    val removed = remember { mutableStateListOf<String>() }
    val clients = allClients.filterNot { it in removed }
    val unpairedToast = stringResource(R.string.settings_paired_clients_unpaired_toast)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        TopAppBar(
            title = { Text(stringResource(R.string.settings_paired_clients_screen_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.settings_cd_back),
                    )
                }
            },
        )

        if (clients.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.settings_paired_clients_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(clients, key = { it }) { client ->
                    val isBypassed = client in bypassed
                    ListItem(
                        headlineContent = { Text(client) },
                        supportingContent = {
                            Text(
                                if (isBypassed) {
                                    stringResource(R.string.settings_paired_clients_bypass_on)
                                } else {
                                    stringResource(R.string.settings_paired_clients_bypass_off)
                                },
                            )
                        },
                        trailingContent = {
                            IconButton(onClick = {
                                // Immediate: hide the row now, persist the
                                // un-pair, confirm with a toast. No dialog —
                                // re-pairing is just re-approving on next connect.
                                removed.add(client)
                                viewModel.unpairMcpClient(client)
                                Toast.makeText(
                                    context,
                                    unpairedToast.format(client),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = stringResource(
                                        R.string.settings_paired_clients_cd_unpair,
                                    ),
                                )
                            }
                        },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                    // The auto-approve switch sits on its own labelled row
                    // so the destructive un-pair affordance can't be
                    // mistaken for the toggle.
                    ListItem(
                        headlineContent = {
                            Text(stringResource(R.string.settings_paired_clients_bypass_label))
                        },
                        trailingContent = {
                            Switch(
                                checked = isBypassed,
                                onCheckedChange = { viewModel.setMcpClientConsentBypass(client, it) },
                            )
                        },
                        modifier = Modifier.padding(start = 24.dp, end = 8.dp),
                    )
                }
            }
        }
    }
}
