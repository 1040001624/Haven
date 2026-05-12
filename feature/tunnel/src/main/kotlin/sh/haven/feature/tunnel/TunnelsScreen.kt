package sh.haven.feature.tunnel

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import sh.haven.core.data.db.entities.TunnelConfig
import sh.haven.core.data.db.entities.typeEnum
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lists tunnel configs the user has saved, with add + delete.
 * Referenced from [ConnectionsViewModel]'s profile edit flow via the
 * tunnel dropdown's "Manage tunnels..." link, and from Settings.
 *
 * WireGuard is the only backend wired at launch. Tailscale shares the
 * screen but its "Add" path is disabled until the tsnet bridge lands —
 * surfacing as a greyed option communicates the roadmap.
 */
@Composable
fun TunnelsScreen(
    viewModel: TunnelViewModel = hiltViewModel(),
    onBack: (() -> Unit)? = null,
) {
    val tunnels by viewModel.tunnels.collectAsState()
    val error by viewModel.error.collectAsState()
    val message by viewModel.message.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(error) {
        error?.let {
            snackbar.showSnackbar(it)
            viewModel.dismissError()
        }
    }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.dismissMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add tunnel")
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (tunnels.isEmpty()) {
                EmptyState(modifier = Modifier.align(Alignment.Center))
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        vertical = 8.dp,
                    ),
                ) {
                    items(tunnels, key = { it.id }) { tunnel ->
                        TunnelRow(
                            tunnel = tunnel,
                            onDelete = { pendingDeleteId = tunnel.id },
                        )
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddTunnelDialog(
            onDismiss = { showAddDialog = false },
            onSubmitWireguard = { label, configText ->
                viewModel.addWireguardConfig(label, configText)
                showAddDialog = false
            },
            onSubmitTailscale = { label, authKey, controlUrl ->
                viewModel.addTailscaleConfig(label, authKey, controlUrl)
                showAddDialog = false
            },
            onSubmitCloudflareAccess = { label, hostname, teamDomain, jwt, expiresAt ->
                viewModel.addCloudflareAccessConfig(label, hostname, teamDomain, jwt, expiresAt)
                showAddDialog = false
            },
        )
    }

    pendingDeleteId?.let { id ->
        val tunnel = tunnels.firstOrNull { it.id == id }
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text("Delete tunnel?") },
            text = {
                Text("Remove \"${tunnel?.label ?: id}\". Profiles referencing this tunnel will fail to connect until you pick another one.")
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(id)
                    pendingDeleteId = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            Icons.Filled.VpnLock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "No tunnels configured yet",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Add a WireGuard config to route individual connection profiles through it — no system-wide VPN required.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TunnelRow(
    tunnel: TunnelConfig,
    onDelete: () -> Unit,
) {
    val formatter = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
    // For Cloudflare Access rows, decode the blob to surface a stale-JWT
    // hint inline; for other types this is skipped entirely.
    val cfExpired = remember(tunnel.id, tunnel.configText) {
        runCatching {
            if (tunnel.typeEnum == sh.haven.core.data.db.entities.TunnelConfigType.CLOUDFLARE_ACCESS) {
                sh.haven.core.tunnel.CloudflareAccessConfigBlob.parse(tunnel.configText)
                    .isJwtExpired()
            } else false
        }.getOrDefault(false)
    }
    ListItem(
        headlineContent = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    tunnel.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (cfExpired) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            "Sign in again",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
        },
        supportingContent = {
            val kind = runCatching { tunnelTypeLabel(tunnel.typeEnum) }.getOrDefault("unknown")
            Text(
                "$kind · added ${formatter.format(Date(tunnel.createdAt))}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        leadingContent = {
            Icon(Icons.Filled.VpnLock, contentDescription = null)
        },
        trailingContent = {
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Delete tunnel",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        },
    )
}

@Composable
private fun AddTunnelDialog(
    onDismiss: () -> Unit,
    onSubmitWireguard: (label: String, configText: String) -> Unit,
    onSubmitTailscale: (label: String, authKey: String, controlUrl: String) -> Unit,
    onSubmitCloudflareAccess: (
        label: String,
        hostname: String,
        teamDomain: String,
        jwt: String,
        expiresAtSeconds: Long,
    ) -> Unit,
) {
    var type by remember { mutableStateOf(sh.haven.core.data.db.entities.TunnelConfigType.WIREGUARD) }
    var label by remember { mutableStateOf("") }
    var configText by remember { mutableStateOf("") }
    var authKey by remember { mutableStateOf("") }
    var controlUrl by remember { mutableStateOf("") }
    // Cloudflare Access form state — survives type-switch in case user
    // flips back and forth before saving.
    var cfHostname by remember { mutableStateOf("") }
    var cfTeamDomain by remember { mutableStateOf("") }
    var cfJwt by remember { mutableStateOf("") }
    var cfExpiresAt by remember { mutableStateOf(0L) }
    var cfAdvancedOpen by remember { mutableStateOf(false) }
    val cfLoginLauncher = rememberLauncherForActivityResult(
        contract = CloudflareAccessLoginContract(),
    ) { result ->
        when (result) {
            is CloudflareAccessLoginContract.Result.Success -> {
                cfJwt = result.jwt
                cfExpiresAt = result.expiresAtSeconds
            }
            is CloudflareAccessLoginContract.Result.Failed,
            CloudflareAccessLoginContract.Result.Cancelled -> Unit
        }
    }
    val context = LocalContext.current

    // Use OpenDocument (SAF) rather than GetContent so the user can pick
    // the file from any provider — Drive, NextCloud, Files app, etc.
    // Filtering to text/* and */* because .conf files often show up as
    // application/octet-stream depending on the provider.
    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val text = context.contentResolver.openInputStream(uri)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            } ?: return@rememberLauncherForActivityResult
            configText = text
            if (label.isBlank()) {
                // Best-effort label from filename. DocumentsContract gives us
                // a _display_name via query; for simplicity, extract from the
                // URI's last path segment and strip .conf suffix.
                val last = uri.lastPathSegment?.substringAfterLast('/').orEmpty()
                val guessed = last.substringAfterLast(':').removeSuffix(".conf")
                if (guessed.isNotBlank()) label = guessed
            }
        } catch (_: Throwable) {
            // Surface via snackbar? For MVP, keep the dialog open and let
            // the user notice nothing populated.
        }
    }

    // Use a full-size Dialog rather than AlertDialog so the config editor
    // gets real screen width instead of the AlertDialog's narrow column.
    // Wrap in Surface so the dialog has an opaque background — without it
    // the full-size Dialog draws against whatever's behind it, making the
    // whole sheet look semi-transparent (#105).
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
            Text(
                "Add tunnel",
                style = MaterialTheme.typography.headlineSmall,
            )

            // Type picker — FilterChip row over the backends. Each
            // toggles the fields below; label persists across flips.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                sh.haven.core.data.db.entities.TunnelConfigType.entries.forEach { t ->
                    androidx.compose.material3.FilterChip(
                        selected = type == t,
                        onClick = { type = t },
                        label = { Text(tunnelTypeLabel(t)) },
                    )
                }
            }

            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Label") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            when (type) {
                sh.haven.core.data.db.entities.TunnelConfigType.WIREGUARD -> {
                    Text(
                        "Paste a wg-quick style config or load a .conf file. Only [Interface] and [Peer] fields are read; unknown keys (PostUp, Table, MTU) are ignored. Hostname endpoints and DNS names are resolved at tunnel start.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = { fileLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.FileOpen, contentDescription = null)
                        Text("  Load from file…")
                    }
                    OutlinedTextField(
                        value = configText,
                        onValueChange = { configText = it },
                        label = { Text("WireGuard config") },
                        placeholder = {
                            Text(
                                "[Interface]\nPrivateKey = …\nAddress = 10.0.0.2/32\n\n[Peer]\nPublicKey = …\nEndpoint = vpn.example.com:51820\nAllowedIPs = 0.0.0.0/0",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                            )
                        },
                        singleLine = false,
                        minLines = 10,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                        ),
                    )
                }
                sh.haven.core.data.db.entities.TunnelConfigType.TAILSCALE -> {
                    Text(
                        "Generate an authkey in the Tailscale admin console (Settings → Keys), or in your Headscale CLI (`headscale preauthkeys create`). Haven joins your tailnet on first use and reuses the node state after that, so a one-time key is fine. Reusable keys let you reconnect after reinstall.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = authKey,
                        onValueChange = { authKey = it },
                        label = { Text("Auth key (tskey-auth-…)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                    )
                    OutlinedTextField(
                        value = controlUrl,
                        onValueChange = { controlUrl = it },
                        label = { Text("Control plane URL (optional)") },
                        placeholder = {
                            Text(
                                "https://headscale.example.com",
                                fontFamily = FontFamily.Monospace,
                            )
                        },
                        supportingText = {
                            Text(
                                "Leave blank for Tailscale's hosted controlplane. Set to a Headscale URL for a self-hosted coordination server.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                    )
                    androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                }
                sh.haven.core.data.db.entities.TunnelConfigType.CLOUDFLARE_ACCESS -> {
                    CloudflareAccessForm(
                        hostname = cfHostname,
                        onHostnameChange = { cfHostname = it; cfJwt = ""; cfExpiresAt = 0L },
                        teamDomain = cfTeamDomain,
                        onTeamDomainChange = { cfTeamDomain = it },
                        jwt = cfJwt,
                        jwtExpiresAt = cfExpiresAt,
                        onSignInClick = {
                            val host = cfHostname.trim()
                            if (host.isNotEmpty()) {
                                cfLoginLauncher.launch(
                                    CloudflareAccessLoginContract.Input(hostname = host),
                                )
                            }
                        },
                        advancedOpen = cfAdvancedOpen,
                        onAdvancedToggle = { cfAdvancedOpen = !cfAdvancedOpen },
                        onJwtPaste = { pasted, expiresAt ->
                            cfJwt = pasted
                            cfExpiresAt = expiresAt
                        },
                    )
                    androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                val canSubmit = label.isNotBlank() && when (type) {
                    sh.haven.core.data.db.entities.TunnelConfigType.WIREGUARD -> configText.isNotBlank()
                    sh.haven.core.data.db.entities.TunnelConfigType.TAILSCALE -> authKey.isNotBlank()
                    sh.haven.core.data.db.entities.TunnelConfigType.CLOUDFLARE_ACCESS ->
                        cfHostname.isNotBlank() && cfJwt.isNotBlank()
                }
                Button(
                    onClick = {
                        when (type) {
                            sh.haven.core.data.db.entities.TunnelConfigType.WIREGUARD ->
                                onSubmitWireguard(label, configText)
                            sh.haven.core.data.db.entities.TunnelConfigType.TAILSCALE ->
                                onSubmitTailscale(label, authKey, controlUrl)
                            sh.haven.core.data.db.entities.TunnelConfigType.CLOUDFLARE_ACCESS ->
                                onSubmitCloudflareAccess(label, cfHostname, cfTeamDomain, cfJwt, cfExpiresAt)
                        }
                    },
                    enabled = canSubmit,
                ) { Text("Save") }
            }
            }
        }
    }
}

private fun tunnelTypeLabel(t: sh.haven.core.data.db.entities.TunnelConfigType): String =
    when (t) {
        sh.haven.core.data.db.entities.TunnelConfigType.WIREGUARD -> "WireGuard"
        sh.haven.core.data.db.entities.TunnelConfigType.TAILSCALE -> "Tailscale"
        sh.haven.core.data.db.entities.TunnelConfigType.CLOUDFLARE_ACCESS -> "Cloudflare Access"
    }

@Composable
private fun CloudflareAccessForm(
    hostname: String,
    onHostnameChange: (String) -> Unit,
    teamDomain: String,
    onTeamDomainChange: (String) -> Unit,
    jwt: String,
    jwtExpiresAt: Long,
    onSignInClick: () -> Unit,
    advancedOpen: Boolean,
    onAdvancedToggle: () -> Unit,
    onJwtPaste: (jwt: String, expiresAtSeconds: Long) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(
            color = MaterialTheme.colorScheme.tertiaryContainer,
            shape = MaterialTheme.shapes.small,
        ) {
            Text(
                "Experimental — wire protocol is reverse-engineered from cloudflared; may break with Cloudflare edge updates.",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }

        Text(
            "Route SSH for a single Access-protected hostname through Cloudflare's edge — the in-app equivalent of `cloudflared access ssh --hostname <host>`. Tap Sign in to authenticate with your team's identity provider; the resulting JWT is stored encrypted alongside the config.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = hostname,
            onValueChange = onHostnameChange,
            label = { Text("Access hostname (e.g. ssh.example.com)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace,
            ),
        )

        OutlinedTextField(
            value = teamDomain,
            onValueChange = onTeamDomainChange,
            label = { Text("Team domain (optional)") },
            placeholder = {
                Text(
                    "myteam.cloudflareaccess.com",
                    fontFamily = FontFamily.Monospace,
                )
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace,
            ),
        )

        val now = remember { System.currentTimeMillis() / 1000 }
        val jwtStatus = when {
            jwt.isBlank() -> "Not signed in"
            jwtExpiresAt in 1 until now -> "JWT expired — sign in again"
            jwtExpiresAt > 0 -> {
                val secs = jwtExpiresAt - now
                val hours = secs / 3600
                if (hours > 0) "Signed in · expires in ~${hours}h" else "Signed in · expires in <1h"
            }
            else -> "Signed in"
        }
        Text(
            jwtStatus,
            style = MaterialTheme.typography.bodySmall,
            color = if (jwt.isBlank() || (jwtExpiresAt in 1 until now)) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            },
        )

        OutlinedButton(
            onClick = onSignInClick,
            modifier = Modifier.fillMaxWidth(),
            enabled = hostname.isNotBlank(),
        ) {
            Text(if (jwt.isBlank()) "Sign in via Cloudflare Access" else "Re-authenticate")
        }

        TextButton(onClick = onAdvancedToggle) {
            Text(if (advancedOpen) "Hide advanced" else "Advanced (paste JWT)")
        }
        if (advancedOpen) {
            Text(
                "For headless setups: run `cloudflared access token --app https://<hostname>` on another machine and paste the JWT here. Expiry is parsed from the token automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = jwt,
                onValueChange = { pasted ->
                    val expiry = sh.haven.core.security.JwtPayload.parse(pasted)
                        ?.expiresAtSeconds ?: 0L
                    onJwtPaste(pasted, expiry)
                },
                label = { Text("Cloudflare Access JWT") },
                singleLine = false,
                minLines = 3,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 80.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                ),
            )
        }
    }
}
