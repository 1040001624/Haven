package sh.haven.core.ssh

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards [ConnectionConfig.AuthMethod.containsFidoKey]. A profile that lists
 * several security keys is a `Multi([FidoKey, …])`, so the old `is FidoKey`
 * check missed it — and the SSH auth-failure UX then wrongly offered a
 * password fallback even on a publickey-only server (#237, vintozver).
 */
class AuthMethodContainsFidoKeyTest {

    private fun fido() =
        ConnectionConfig.AuthMethod.FidoKey(skKeyData = byteArrayOf(1, 2, 3), keyLabel = "YK")

    private fun pw() = ConnectionConfig.AuthMethod.Password("pw")

    @Test
    fun `a bare FidoKey contains a FIDO key`() {
        assertTrue(fido().containsFidoKey())
    }

    @Test
    fun `a password does not`() {
        assertFalse(pw().containsFidoKey())
    }

    @Test
    fun `a Multi of two FidoKeys contains a FIDO key — the #237 multi-key case`() {
        assertTrue(ConnectionConfig.AuthMethod.Multi(listOf(fido(), fido())).containsFidoKey())
    }

    @Test
    fun `a Multi of password plus FidoKey contains a FIDO key`() {
        assertTrue(ConnectionConfig.AuthMethod.Multi(listOf(pw(), fido())).containsFidoKey())
    }

    @Test
    fun `a Multi of only passwords does not`() {
        assertFalse(ConnectionConfig.AuthMethod.Multi(listOf(pw(), pw())).containsFidoKey())
    }

    @Test
    fun `a nested Multi containing a FidoKey contains a FIDO key`() {
        val nested = ConnectionConfig.AuthMethod.Multi(
            listOf(pw(), ConnectionConfig.AuthMethod.Multi(listOf(fido()))),
        )
        assertTrue(nested.containsFidoKey())
    }
}
