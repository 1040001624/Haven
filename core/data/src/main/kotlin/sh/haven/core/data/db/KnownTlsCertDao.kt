package sh.haven.core.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import sh.haven.core.data.db.entities.KnownTlsCert

@Dao
interface KnownTlsCertDao {

    @Query("SELECT * FROM known_tls_certs ORDER BY hostname ASC")
    fun observeAll(): Flow<List<KnownTlsCert>>

    @Query("SELECT * FROM known_tls_certs ORDER BY hostname ASC")
    suspend fun getAll(): List<KnownTlsCert>

    @Query("SELECT * FROM known_tls_certs WHERE hostname = :hostname AND port = :port LIMIT 1")
    suspend fun findByHostPort(hostname: String, port: Int): KnownTlsCert?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(cert: KnownTlsCert)

    @Delete
    suspend fun delete(cert: KnownTlsCert)

    @Query("DELETE FROM known_tls_certs WHERE hostname = :hostname AND port = :port")
    suspend fun deleteByHostPort(hostname: String, port: Int)
}
