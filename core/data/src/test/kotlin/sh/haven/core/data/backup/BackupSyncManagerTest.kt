package sh.haven.core.data.backup

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * #323: BackupSyncManager is deliberately thin — it composes BackupService
 * (encrypt/decrypt) with RemoteBackupIo (move bytes). These tests pin that
 * composition: push encrypts then writes the SAME bytes; pull reads then
 * decrypts what it read; and a transport failure propagates rather than being
 * swallowed (the caller maps it to "connect the destination first").
 */
class BackupSyncManagerTest {

    private val encrypted = byteArrayOf(1, 2, 3, 4, 5)

    @Test
    fun `push exports then writes the encrypted bytes to the given destination`() = runTest {
        val backup = mockk<BackupService>()
        val io = mockk<RemoteBackupIo>(relaxed = true)
        coEvery { backup.export("pw") } returns encrypted
        val mgr = BackupSyncManager(backup, io)

        val written = slot<ByteArray>()
        coEvery { io.writeBackup("p1", "haven-backup.enc", capture(written)) } returns Unit

        mgr.push("p1", "haven-backup.enc", "pw")

        coVerify(exactly = 1) { backup.export("pw") }
        assertArrayEquals("must write exactly the encrypted blob", encrypted, written.captured)
    }

    @Test
    fun `pull reads the remote then imports what it read`() = runTest {
        val backup = mockk<BackupService>()
        val io = mockk<RemoteBackupIo>()
        val result = BackupService.BackupResult(count = 7)
        coEvery { io.readBackup("p1", "haven-backup.enc") } returns encrypted
        val imported = slot<ByteArray>()
        coEvery { backup.import(capture(imported), "pw") } returns result
        val mgr = BackupSyncManager(backup, io)

        val out = mgr.pull("p1", "haven-backup.enc", "pw")

        assertArrayEquals("must import exactly the bytes read from the remote", encrypted, imported.captured)
        assertSame("returns the BackupService result verbatim", result, out)
        assertEquals(7, out.count)
    }

    @Test(expected = IllegalStateException::class)
    fun `push propagates a transport failure (e_g_ destination not connected)`() = runTest {
        val backup = mockk<BackupService>()
        val io = mockk<RemoteBackupIo>()
        coEvery { backup.export(any()) } returns encrypted
        coEvery { io.writeBackup(any(), any(), any()) } throws IllegalStateException("not connected")
        BackupSyncManager(backup, io).push("p1", "x.enc", "pw")
    }
}
