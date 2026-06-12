package sh.haven.core.data.repository

import kotlinx.coroutines.flow.Flow
import sh.haven.core.data.db.StandingPolicyDao
import sh.haven.core.data.db.entities.StandingPolicy
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Injectable surface over the Tier-3 standing-policy table, for the MCP
 * enforcement path, the policy MCP tools, and the kill-switch UI.
 */
@Singleton
class StandingPolicyRepository @Inject constructor(
    private val dao: StandingPolicyDao,
) {
    fun observePolicies(): Flow<List<StandingPolicy>> = dao.observeAll()
    suspend fun allPolicies(): List<StandingPolicy> = dao.all()
    suspend fun activePolicies(now: Long = System.currentTimeMillis()): List<StandingPolicy> = dao.active(now)
    suspend fun getPolicy(id: String): StandingPolicy? = dao.getById(id)
    suspend fun savePolicy(policy: StandingPolicy) = dao.upsert(policy)
    suspend fun deletePolicy(id: String) = dao.deleteById(id)
    suspend fun purgeExpired(now: Long = System.currentTimeMillis()) = dao.deleteExpired(now)
}
