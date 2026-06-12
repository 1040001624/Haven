package sh.haven.core.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * A Tier-3 standing policy (VISION.md "Consent tiers"): a scoped, rate-capped,
 * expiring grant that lets a named MCP client call the listed tools without a
 * per-call consent prompt. The agent *proposes* the policy (create_standing_policy,
 * itself EVERY_CALL); the user's tap on that consent sheet is the installation.
 * Every covered call is still written to the agent-audit table, and the policy
 * is revocable from the Agent activity screen (the kill-switch) or by the agent
 * itself (revoke_standing_policy — privilege reduction, no prompt).
 *
 * Follows the [MailRule] precedent: enabling a rule IS the standing authorization
 * for its actions, witnessed by an audit trail and an in-app off switch. Tool
 * names and arg constraints are JSON strings (codebase convention — no Room
 * TypeConverters).
 */
@Entity(tableName = "standing_policies")
data class StandingPolicy(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    /** The MCP clientInfo.name this grant binds to. Never applies to other clients. */
    val clientHint: String,
    /** Human label shown on the install consent sheet and the kill-switch list. */
    val description: String,
    /** JSON array of exact tool names the policy covers. */
    val toolNamesJson: String,
    /**
     * Optional JSON object of argument constraints: every key present here must
     * string-equal the call's argument of the same name for the policy to apply
     * (e.g. {"profileId":"abc"} scopes the grant to one connection). Null = the
     * listed tools are covered regardless of arguments.
     */
    val argConstraintsJson: String? = null,
    /** Rate ceiling: covered calls per rolling 60s window; beyond it, calls fall back to the consent prompt. */
    val maxCallsPerMinute: Int,
    /** Hard expiry (epoch ms); an expired policy never applies and is purged lazily. */
    val expiresAt: Long,
    val createdAt: Long = System.currentTimeMillis(),
    /** The kill-switch: a disabled policy never applies (rows kept for the audit trail until deleted). */
    val enabled: Boolean = true,
)
