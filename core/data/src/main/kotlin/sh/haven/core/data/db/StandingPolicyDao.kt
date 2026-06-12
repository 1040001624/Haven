package sh.haven.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import sh.haven.core.data.db.entities.StandingPolicy

@Dao
interface StandingPolicyDao {
    @Query("SELECT * FROM standing_policies ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<StandingPolicy>>

    @Query("SELECT * FROM standing_policies ORDER BY createdAt DESC")
    suspend fun all(): List<StandingPolicy>

    /** Policies that can currently apply: enabled and not yet expired. */
    @Query("SELECT * FROM standing_policies WHERE enabled = 1 AND expiresAt > :now")
    suspend fun active(now: Long): List<StandingPolicy>

    @Query("SELECT * FROM standing_policies WHERE id = :id")
    suspend fun getById(id: String): StandingPolicy?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(policy: StandingPolicy)

    @Query("DELETE FROM standing_policies WHERE id = :id")
    suspend fun deleteById(id: String)

    /** Lazy housekeeping: drop policies whose expiry has passed. */
    @Query("DELETE FROM standing_policies WHERE expiresAt <= :now")
    suspend fun deleteExpired(now: Long)
}
