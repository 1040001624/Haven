package sh.haven.app.agent

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.core.data.db.entities.StandingPolicy
import sh.haven.core.data.repository.StandingPolicyRepository

/**
 * Pins the Tier-3 policy evaluation contract: a policy only ever applies to
 * its own client, its listed tools, matching arguments, inside its rate
 * ceiling — and never to the denylisted (Tier-4 / policy-management) tools.
 * Expiry filtering lives in the DAO's SQL (`expiresAt > :now`) and is not
 * exercised here.
 */
class StandingPolicyEnforcerTest {

    private fun policy(
        client: String = "test-host",
        tools: String = """["tap_haven_ui","swipe_haven_ui"]""",
        constraints: String? = null,
        rate: Int = 10,
    ) = StandingPolicy(
        clientHint = client,
        description = "test policy",
        toolNamesJson = tools,
        argConstraintsJson = constraints,
        maxCallsPerMinute = rate,
        expiresAt = Long.MAX_VALUE,
    )

    private fun enforcer(vararg policies: StandingPolicy): StandingPolicyEnforcer {
        val repo = mockk<StandingPolicyRepository>()
        coEvery { repo.activePolicies(any()) } returns policies.toList()
        return StandingPolicyEnforcer(repo)
    }

    @Test
    fun `covers a listed tool for the policy's client`() = runTest {
        assertTrue(enforcer(policy()).permits("test-host", "tap_haven_ui", JSONObject()))
    }

    @Test
    fun `never applies to another client`() = runTest {
        assertFalse(enforcer(policy()).permits("other-client", "tap_haven_ui", JSONObject()))
        assertFalse(enforcer(policy()).permits(null, "tap_haven_ui", JSONObject()))
    }

    @Test
    fun `never applies to an unlisted tool`() = runTest {
        assertFalse(enforcer(policy()).permits("test-host", "delete_file", JSONObject()))
    }

    @Test
    fun `denylisted tools are never covered even when listed`() = runTest {
        val sneaky = policy(tools = """["install_apk_from_backend","create_standing_policy"]""")
        assertFalse(enforcer(sneaky).permits("test-host", "install_apk_from_backend", JSONObject()))
        assertFalse(enforcer(sneaky).permits("test-host", "create_standing_policy", JSONObject()))
    }

    @Test
    fun `argument constraints must match exactly`() = runTest {
        val pinned = policy(
            tools = """["disconnect_profile"]""",
            constraints = """{"profileId":"abc"}""",
        )
        val e = enforcer(pinned)
        assertTrue(e.permits("test-host", "disconnect_profile", JSONObject().put("profileId", "abc")))
        assertFalse(e.permits("test-host", "disconnect_profile", JSONObject().put("profileId", "xyz")))
        assertFalse(e.permits("test-host", "disconnect_profile", JSONObject()))
    }

    @Test
    fun `rate ceiling makes further calls fall back to the prompt`() = runTest {
        val e = enforcer(policy(rate = 2))
        val now = 1_000_000L
        assertTrue(e.permits("test-host", "tap_haven_ui", JSONObject(), now))
        assertTrue(e.permits("test-host", "tap_haven_ui", JSONObject(), now + 1))
        assertFalse("third call inside the window must fall back", e.permits("test-host", "tap_haven_ui", JSONObject(), now + 2))
        // The window rolls: a minute later there's room again.
        assertTrue(e.permits("test-host", "tap_haven_ui", JSONObject(), now + 61_000))
    }

    @Test
    fun `malformed policy JSON fails closed`() = runTest {
        assertFalse(enforcer(policy(tools = "not json")).permits("test-host", "tap_haven_ui", JSONObject()))
        val badConstraints = policy(constraints = "{broken")
        assertFalse(enforcer(badConstraints).permits("test-host", "tap_haven_ui", JSONObject()))
    }
}
