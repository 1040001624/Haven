package sh.haven.app.workspace

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import sh.haven.core.data.agent.AgentUiCommand
import sh.haven.core.data.agent.AgentUiCommandBus
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.db.entities.WorkspaceItem
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.data.repository.WorkspaceRepository
import sh.haven.core.ssh.SshSessionManager
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "WorkspaceLauncher"

/**
 * Walks a [sh.haven.core.data.db.entities.WorkspaceProfile]'s items and
 * dispatches the corresponding `AgentUiCommand` for each, in a kind
 * priority order that lets the existing `tunnelDependents` machinery in
 * `SshSessionManager` attach tunneled desktops to already-up SSH
 * sessions when both are in the same workspace.
 *
 * ### Kind priority
 *
 * 1. `TERMINAL` — SSH/Mosh/ET/Reticulum/Local. SSH terminals come up
 *    first so any tunneled desktops in the same workspace attach to
 *    already-connected sessions instead of opening duplicate ones.
 * 2. `WAYLAND` — local compositor tab. No network ordering concern.
 * 3. `FILE_BROWSER` — SFTP/SMB/rclone. SFTP routes through the same
 *    SSH session a TERMINAL item would have created.
 * 4. `DESKTOP` — VNC/RDP. Last so the tunnel SSH is up.
 *
 * Within a kind, items launch in their stored `sortOrder`. Ties broken
 * by item id for determinism.
 *
 * ### Dispatch is via [AgentUiCommandBus]
 *
 * The launcher is a singleton and emits commands; `HavenNavHost`
 * collects them to switch the pager, and the relevant feature
 * ViewModels (`TerminalViewModel`, `SftpViewModel`, `DesktopViewModel`)
 * collect to materialise the tabs. Same caveat as the existing
 * `NavigateToSftpPath` verb: emissions for a screen the user has never
 * visited may drop because the page-scoped ViewModel isn't alive yet.
 * `DesktopViewModel` is nav-scoped and always alive.
 *
 * ### Cancellation
 *
 * Cooperative — [cancel] flips a flag; the in-flight item is allowed to
 * dispatch (the bus emit is non-blocking anyway), and remaining items
 * mark `Skipped`.
 */
@Singleton
class WorkspaceLauncher @Inject constructor(
    private val workspaceRepository: WorkspaceRepository,
    private val connectionRepository: ConnectionRepository,
    private val agentUiCommandBus: AgentUiCommandBus,
    private val sshSessionManager: SshSessionManager,
) {
    private val _state = MutableStateFlow<WorkspaceLaunchState>(WorkspaceLaunchState.Idle)
    val state: StateFlow<WorkspaceLaunchState> = _state.asStateFlow()

    @Volatile
    private var cancelRequested = false

    /**
     * Launch every item of [workspaceId]. Suspends only briefly while
     * looking up the workspace + profiles; per-item dispatch is
     * non-blocking (`tryEmit`) so the whole thing returns quickly. The
     * UI observes [state] for progress.
     */
    suspend fun launch(workspaceId: String) {
        cancelRequested = false

        val workspace = workspaceRepository.getWorkspace(workspaceId)
        if (workspace == null) {
            _state.value = WorkspaceLaunchState.Failed(
                workspaceId = workspaceId,
                workspaceName = "(unknown)",
                reason = "workspace not found",
                items = emptyList(),
            )
            Log.w(TAG, "launch($workspaceId) — not found")
            return
        }

        val sortedItems = sortByKindPriority(workspace.items)

        val initialProgress = sortedItems.map { item ->
            ItemProgress(
                itemId = item.id,
                kind = item.kind,
                connectionProfileId = item.connectionProfileId,
                status = ItemProgress.Status.Pending,
            )
        }
        _state.value = WorkspaceLaunchState.Launching(
            workspaceId = workspace.profile.id,
            workspaceName = workspace.profile.name,
            items = initialProgress,
        )
        Log.d(TAG, "launch ${workspace.profile.name} — ${sortedItems.size} items")

        val progressMap = initialProgress.associateBy { it.itemId }.toMutableMap()

        // Profiles we have already issued a from-cold dial for this run, so a
        // failed connect isn't restacked once per remaining item on the same
        // profile (a workspace with N terminals on one host dials at most once).
        val dialed = mutableSetOf<String>()

        for (item in sortedItems) {
            if (cancelRequested) {
                progressMap[item.id] = progressMap.getValue(item.id).copy(
                    status = ItemProgress.Status.Skipped,
                    message = "cancelled",
                )
                publishLaunching(workspace.profile.id, workspace.profile.name, progressMap, sortedItems)
                continue
            }

            progressMap[item.id] = progressMap.getValue(item.id).copy(
                status = ItemProgress.Status.Running,
            )
            publishLaunching(workspace.profile.id, workspace.profile.name, progressMap, sortedItems)

            val outcome = dispatch(item, dialed)
            progressMap[item.id] = progressMap.getValue(item.id).copy(
                status = if (outcome.success) ItemProgress.Status.Succeeded
                else ItemProgress.Status.Failed,
                message = outcome.message,
            )
            publishLaunching(workspace.profile.id, workspace.profile.name, progressMap, sortedItems)
        }

        val finalItems = sortedItems.map { progressMap.getValue(it.id) }
        _state.value = when {
            cancelRequested -> WorkspaceLaunchState.Cancelled(
                workspace.profile.id, workspace.profile.name, finalItems,
            )
            finalItems.all { it.status == ItemProgress.Status.Succeeded } ->
                WorkspaceLaunchState.Completed(
                    workspace.profile.id, workspace.profile.name, finalItems,
                )
            else -> WorkspaceLaunchState.Completed(
                // Mixed success counts as completed-with-failures rather
                // than overall Failed — partial workspaces are useful.
                // The Snackbar reads finalItems and shows the count.
                workspace.profile.id, workspace.profile.name, finalItems,
            )
        }
        Log.d(TAG, "launch ${workspace.profile.name} done: ${finalItems.count { it.status == ItemProgress.Status.Succeeded }}/${finalItems.size}")
    }

    /** Cooperative cancel — remaining items mark Skipped. */
    fun cancel() {
        cancelRequested = true
    }

    /**
     * Reset state to [WorkspaceLaunchState.Idle]. Called by the UI when
     * the snackbar dismisses, so a subsequent launch starts clean.
     */
    fun acknowledge() {
        if (_state.value !is WorkspaceLaunchState.Launching) {
            _state.value = WorkspaceLaunchState.Idle
        }
    }

    private suspend fun dispatch(item: WorkspaceItem, dialed: MutableSet<String>): DispatchOutcome {
        val command = when (item.kind) {
            WorkspaceItem.Kind.WAYLAND -> AgentUiCommand.OpenWaylandDesktop
            WorkspaceItem.Kind.TERMINAL -> {
                val profile = item.connectionProfileId?.let { connectionRepository.getById(it) }
                    ?: return DispatchOutcome(false, "profile missing")
                if (!profile.isTerminal) {
                    return DispatchOutcome(false, "${profile.label} is not a terminal profile")
                }
                // A plain-SSH terminal tab can only attach to an already-live
                // session (OpenTerminalSession reads the connection config off
                // one). On a cold workspace restore none is up, so it would
                // no-op with "Connection config not found". Dial the profile
                // instead — connect() establishes the SSH session AND opens the
                // first tab — and wait until it is CONNECTED so the remaining
                // items on the same profile reuse the connection rather than
                // racing the in-flight dial. Mosh/ET/Reticulum/Local keep the
                // tab-only path (separate session managers; not this bug).
                val isPlainSsh = profile.isSsh && !profile.isMosh && !profile.isEternalTerminal
                if (isPlainSsh && !sshSessionManager.isProfileConnected(profile.id)) {
                    if (profile.id in dialed) {
                        // Already tried to bring this profile up and it isn't
                        // connected — don't restack another dial.
                        return DispatchOutcome(false, "connection unavailable")
                    }
                    dialed += profile.id
                    // Dial with the saved session name so the first tab attaches
                    // straight to its tmux/zellij session (ConnectProfile threads
                    // it through as preselectedSessionName, skipping the picker).
                    if (!agentUiCommandBus.emit(AgentUiCommand.ConnectProfile(profile.id, sessionName = item.sessionName))) {
                        return DispatchOutcome(false, "ui bus overflow")
                    }
                    return if (awaitProfileConnected(profile.id, CONNECT_TIMEOUT_MS)) {
                        DispatchOutcome(true, null)
                    } else {
                        DispatchOutcome(false, "connection did not come up")
                    }
                }
                // Reuse the live connection for a further tab, reattaching to the
                // saved session by name so it too skips the picker.
                AgentUiCommand.OpenTerminalSession(profile.id, sessionName = item.sessionName)
            }
            WorkspaceItem.Kind.FILE_BROWSER -> {
                val profile = item.connectionProfileId?.let { connectionRepository.getById(it) }
                    ?: return DispatchOutcome(false, "profile missing")
                if (!profile.isFileBrowser()) {
                    return DispatchOutcome(false, "${profile.label} is not a file-browser profile")
                }
                AgentUiCommand.NavigateToSftpPath(
                    profileId = profile.id,
                    path = item.path ?: "/",
                )
            }
            WorkspaceItem.Kind.DESKTOP -> {
                val profile = item.connectionProfileId?.let { connectionRepository.getById(it) }
                    ?: return DispatchOutcome(false, "profile missing")
                if (!profile.isDesktop) {
                    return DispatchOutcome(false, "${profile.label} is not a desktop profile")
                }
                AgentUiCommand.OpenRemoteDesktop(profile.id)
            }
        }
        val delivered = agentUiCommandBus.emit(command)
        return if (delivered) {
            DispatchOutcome(true, null)
        } else {
            // Bus overflow is rare (1-element extra buffer) but real if
            // the same caller emits faster than the collector drains.
            DispatchOutcome(false, "ui bus overflow")
        }
    }

    /**
     * Suspend until a CONNECTED session exists for [profileId], or [timeoutMs]
     * elapses. Watches the sessions flow directly (not
     * [SshSessionManager.awaitConnected], which returns false the instant it is
     * called before the just-emitted ConnectProfile has moved the session into
     * CONNECTING). Returns false on timeout so a failed dial fails the item
     * rather than hanging the whole launch.
     */
    private suspend fun awaitProfileConnected(profileId: String, timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) {
            sshSessionManager.sessions.first { sessions ->
                sessions.values.any {
                    it.profileId == profileId &&
                        it.status == SshSessionManager.SessionState.Status.CONNECTED
                }
            }
            true
        } ?: false

    private fun publishLaunching(
        workspaceId: String,
        workspaceName: String,
        progressMap: Map<String, ItemProgress>,
        order: List<WorkspaceItem>,
    ) {
        _state.value = WorkspaceLaunchState.Launching(
            workspaceId = workspaceId,
            workspaceName = workspaceName,
            items = order.map { progressMap.getValue(it.id) },
        )
    }

    private data class DispatchOutcome(val success: Boolean, val message: String?)

    companion object {
        /**
         * How long to wait for a from-cold SSH dial to reach CONNECTED before
         * giving up on that item. Generous enough to cover an interactive
         * host-key / password / FIDO step on the connect it triggers.
         */
        private const val CONNECT_TIMEOUT_MS = 45_000L

        /**
         * Rank items by kind so SSH terminals come up before tunneled
         * desktops attach. Stable within a kind: ties broken by the
         * user's stored [WorkspaceItem.sortOrder], then by id.
         */
        internal fun sortByKindPriority(items: List<WorkspaceItem>): List<WorkspaceItem> =
            items.sortedWith(
                compareBy(
                    { kindPriority(it.kind) },
                    { it.sortOrder },
                    { it.id },
                ),
            )

        private fun kindPriority(kind: WorkspaceItem.Kind): Int = when (kind) {
            WorkspaceItem.Kind.TERMINAL -> 0
            WorkspaceItem.Kind.WAYLAND -> 1
            WorkspaceItem.Kind.FILE_BROWSER -> 2
            WorkspaceItem.Kind.DESKTOP -> 3
        }
    }
}

/**
 * True when the profile resolves to a file-browser tab. Mirrors the
 * `isTerminal` / `isDesktop` helpers but covers the SFTP / SMB / Rclone
 * / Local file-browser kinds explicitly so workspace items don't have to
 * duplicate the predicate.
 */
private fun ConnectionProfile.isFileBrowser(): Boolean =
    isSsh || isSmb || isRclone || isLocal
