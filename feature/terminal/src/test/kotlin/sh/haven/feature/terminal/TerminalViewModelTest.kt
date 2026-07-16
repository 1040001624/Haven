package sh.haven.feature.terminal

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import sh.haven.core.et.EtSessionManager
import sh.haven.core.local.LocalSessionManager
import sh.haven.core.mosh.MoshSessionManager
import sh.haven.core.reticulum.ReticulumSessionManager
import sh.haven.core.ssh.SshClient
import sh.haven.core.ssh.SshSessionManager

class TerminalViewModelTest {

    private lateinit var sessionManager: SshSessionManager
    private lateinit var reticulumSessionManager: ReticulumSessionManager
    private lateinit var moshSessionManager: MoshSessionManager
    private lateinit var etSessionManager: EtSessionManager
    private lateinit var localSessionManager: LocalSessionManager
    private lateinit var btSerialSessionManager: sh.haven.core.btserial.BtSerialSessionManager
    private lateinit var bleSerialSessionManager: sh.haven.core.bleserial.BleSerialSessionManager
    private lateinit var usbSerialSessionManager: sh.haven.core.usbserial.UsbSerialSessionManager
    private lateinit var sshEmulatorOwner: SshTerminalEmulatorOwner
    private lateinit var viewModel: TerminalViewModel

    @Before
    fun setUp() {
        sessionManager = SshSessionManager(mockk(relaxed = true), mockk(relaxed = true))
        reticulumSessionManager = mockk<ReticulumSessionManager>(relaxed = true) {
            every { sessions } returns MutableStateFlow(emptyMap())
        }
        moshSessionManager = mockk<MoshSessionManager>(relaxed = true) {
            every { sessions } returns MutableStateFlow(emptyMap())
        }
        etSessionManager = mockk<EtSessionManager>(relaxed = true) {
            every { sessions } returns MutableStateFlow(emptyMap())
        }
        localSessionManager = mockk<LocalSessionManager>(relaxed = true) {
            every { sessions } returns MutableStateFlow(emptyMap())
        }
        btSerialSessionManager = mockk(relaxed = true) {
            every { sessions } returns MutableStateFlow(emptyMap())
        }
        bleSerialSessionManager = mockk(relaxed = true) {
            every { sessions } returns MutableStateFlow(emptyMap())
        }
        usbSerialSessionManager = mockk(relaxed = true) {
            every { sessions } returns MutableStateFlow(emptyMap())
        }
        // No connect-time bundle in unit tests (the real emulator needs JNI),
        // so the SSH adopt branch skips — matching the old isReadyForTerminal skip.
        sshEmulatorOwner = mockk(relaxed = true) {
            every { bundleFor(any()) } returns null
        }
        viewModel = TerminalViewModel(
            mockk(relaxed = true),
            sessionManager,
            mockk(relaxed = true), // SshSessionAttacher
            reticulumSessionManager,
            moshSessionManager,
            etSessionManager,
            btSerialSessionManager,
            bleSerialSessionManager,
            usbSerialSessionManager,
            mockk<sh.haven.core.usb.UsbBroker>(relaxed = true),
            localSessionManager,
            mockk(relaxed = true), // HostKeyVerifier
            mockk(relaxed = true), // FidoAuthenticator
            mockk(relaxed = true), // UserPreferencesRepository
            mockk(relaxed = true), // ConnectionRepository
            mockk(relaxed = true), // TunnelResolver
            sh.haven.core.data.agent.AgentUiCommandBus(),
            sh.haven.core.data.message.UserMessageBus(),
            mockk(relaxed = true),
            sh.haven.feature.terminal.agent.TerminalSessionRegistry(),
            sshEmulatorOwner,
            mockk(relaxed = true), // BarcodeDecoder
            mockk(relaxed = true), // TextRecognizer
        )
    }

    @Test
    fun `initially has no tabs`() {
        assertEquals(0, viewModel.tabs.value.size)
        assertEquals(0, viewModel.activeTabIndex.value)
    }

    @Test
    fun `syncSessions with no sessions produces no tabs`() {
        runBlocking { viewModel.syncSessions() }
        assertEquals(0, viewModel.tabs.value.size)
    }

    @Test
    fun `syncSessions skips CONNECTING sessions`() {
        val client = mockk<SshClient>(relaxed = true)
        sessionManager.registerSession("profile1", "Server", client)
        // Status is CONNECTING, no shell channel

        runBlocking { viewModel.syncSessions() }
        assertEquals(0, viewModel.tabs.value.size)
    }

    @Test
    fun `syncSessions skips CONNECTED sessions without shell channel`() {
        val client = mockk<SshClient>(relaxed = true)
        val sessionId = sessionManager.registerSession("profile1", "Server", client)
        sessionManager.updateStatus(sessionId, SshSessionManager.SessionState.Status.CONNECTED)
        // No shell channel attached

        runBlocking { viewModel.syncSessions() }
        assertEquals(0, viewModel.tabs.value.size)
    }

    @Test
    fun `selectTab with no tabs is no-op`() {
        viewModel.selectTab(2)
        assertEquals(0, viewModel.activeTabIndex.value)
    }

    @Test
    fun `closeTab removes from session manager`() {
        val client = mockk<SshClient>(relaxed = true)
        val sessionId = sessionManager.registerSession("profile1", "Server", client)
        viewModel.closeTab(sessionId)

        assertEquals(null, sessionManager.getSession(sessionId))
    }

    @Test
    fun `closeTab tears down a BLE-serial session`() {
        // Regression: removeTabAndSync only knew SSH/mosh/et/local/reticulum, so a
        // serial session fell through to reticulum (no-op), stayed alive, and
        // syncSessions rebuilt its tab — the tab wouldn't close.
        val sessionId = "ble-1"
        every { bleSerialSessionManager.sessions } returns
            MutableStateFlow(mapOf(sessionId to mockk(relaxed = true)))

        viewModel.closeTab(sessionId)

        verify { bleSerialSessionManager.removeSession(sessionId) }
    }

    @Test
    fun `closeSession removes all sessions for profile`() {
        val c1 = mockk<SshClient>(relaxed = true)
        val c2 = mockk<SshClient>(relaxed = true)
        sessionManager.registerSession("profile1", "Server", c1)
        sessionManager.registerSession("profile1", "Server", c2)

        viewModel.closeSession("profile1")

        assertEquals(0, sessionManager.getSessionsForProfile("profile1").size)
    }

    @Test
    fun `selectTabByProfileId with no matching tab is no-op`() {
        viewModel.selectTabByProfileId("nonexistent")
        assertEquals(0, viewModel.activeTabIndex.value)
    }

    @Test
    fun `selectTabBySessionId with no matching tab is no-op`() {
        viewModel.selectTabBySessionId("nonexistent")
        assertEquals(0, viewModel.activeTabIndex.value)
    }
}
