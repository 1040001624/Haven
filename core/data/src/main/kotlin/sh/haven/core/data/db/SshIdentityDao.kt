package sh.haven.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import sh.haven.core.data.db.entities.SshIdentity

@Dao
interface SshIdentityDao {

    @Query("SELECT * FROM ssh_identities ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<SshIdentity>>

    @Query("SELECT * FROM ssh_identities ORDER BY name COLLATE NOCASE")
    suspend fun getAll(): List<SshIdentity>

    @Query("SELECT * FROM ssh_identities WHERE id = :id")
    suspend fun getById(id: String): SshIdentity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(identity: SshIdentity)

    @Query("DELETE FROM ssh_identities WHERE id = :id")
    suspend fun deleteById(id: String)
}
