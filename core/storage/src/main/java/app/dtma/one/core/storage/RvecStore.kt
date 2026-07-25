package app.dtma.one.core.storage

import android.content.Context
import android.util.Base64
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import app.dtma.one.core.model.EndpointCandidate
import app.dtma.one.core.model.FailureStage
import app.dtma.one.core.model.IpFamily
import app.dtma.one.core.model.PaerLimits
import app.dtma.one.core.model.RvecPolicy
import app.dtma.one.core.model.Transport
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Entity(tableName = "rvec_entries")
data class RvecEntity(
    @PrimaryKey val id: String,
    val blob: String,
    val hostnameHash: String,
    val updatedAt: Long,
)

@Dao
interface RvecDao {
    @Query("SELECT * FROM rvec_entries ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun all(limit: Int): List<RvecEntity>

    @Query("SELECT * FROM rvec_entries WHERE hostnameHash = :hash ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun forHost(hash: String, limit: Int): List<RvecEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: RvecEntity)

    @Query("DELETE FROM rvec_entries")
    suspend fun clear()

    @Query(
        "DELETE FROM rvec_entries WHERE id NOT IN " +
            "(SELECT id FROM rvec_entries ORDER BY updatedAt DESC LIMIT :keep)",
    )
    suspend fun trim(keep: Int)
}

@Database(entities = [RvecEntity::class], version = 1, exportSchema = false)
abstract class RvecDatabase : RoomDatabase() {
    abstract fun rvecDao(): RvecDao
}

/**
 * Field-level AES-GCM with key in EncryptedSharedPreferences (Android Keystore-backed).
 */
class RvecStore(context: Context) {
    private val appContext = context.applicationContext
    private val crypto = RvecCrypto(appContext)
    private val db = Room.databaseBuilder(appContext, RvecDatabase::class.java, "dtma_rvec.db")
        .fallbackToDestructiveMigration()
        .build()

    suspend fun listForHost(
        hostname: String,
        limit: Int = PaerLimits.MAX_CACHE_CANDIDATES_PER_HOST,
    ): List<EndpointCandidate> = withContext(Dispatchers.IO) {
        try {
            val hash = hostname.lowercase().hashCode().toString()
            db.rvecDao().forHost(hash, limit).mapNotNull { decryptEntity(it) }
        } catch (_: Exception) {
            runCatching { appContext.deleteDatabase("dtma_rvec.db") }
            emptyList()
        }
    }

    suspend fun listAll(limit: Int = PaerLimits.MAX_GLOBAL_RVEC_ENTRIES): List<EndpointCandidate> =
        withContext(Dispatchers.IO) {
            try {
                db.rvecDao().all(limit).mapNotNull { decryptEntity(it) }
            } catch (_: Exception) {
                runCatching { appContext.deleteDatabase("dtma_rvec.db") }
                emptyList()
            }
        }

    suspend fun saveSuccess(candidate: EndpointCandidate, nowMs: Long, contextId: String) =
        withContext(Dispatchers.IO) {
            val record = RvecPolicy.onSuccess(RvecPolicy.fromCandidate(candidate), nowMs, contextId)
            upsert(record)
            db.rvecDao().trim(PaerLimits.MAX_GLOBAL_RVEC_ENTRIES)
        }

    suspend fun saveFailure(
        candidate: EndpointCandidate,
        nowMs: Long,
        contextId: String,
        stage: FailureStage,
    ) = withContext(Dispatchers.IO) {
        val existing = listForHost(candidate.hostname).firstOrNull {
            it.ipAddress == candidate.ipAddress && it.port == candidate.port
        }
        val base = existing?.let { RvecPolicy.fromCandidate(it) } ?: RvecPolicy.fromCandidate(candidate)
        val record = RvecPolicy.onFailure(base, nowMs, stage, contextId)
        upsert(record)
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        db.rvecDao().clear()
    }

    private suspend fun upsert(record: RvecPolicy.RvecRecord) {
        val id = listOf(
            record.hostname.lowercase(),
            record.ipAddress,
            record.port.toString(),
            record.transport.name,
        ).joinToString("|")
        val plain = serialize(record)
        val blob = crypto.encrypt(plain)
        db.rvecDao().upsert(
            RvecEntity(
                id = id,
                blob = blob,
                hostnameHash = record.hostname.lowercase().hashCode().toString(),
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    private fun decryptEntity(entity: RvecEntity): EndpointCandidate? {
        return try {
            deserialize(crypto.decrypt(entity.blob)).toCandidate()
        } catch (_: Exception) {
            null
        }
    }

    private fun serialize(r: RvecPolicy.RvecRecord): ByteArray {
        val s = listOf(
            r.hostname,
            r.ipAddress,
            r.ipFamily.name,
            r.port.toString(),
            r.transport.name,
            r.alpn.joinToString(","),
            r.networkContextId,
            r.lastSuccessAt?.toString().orEmpty(),
            r.lastFailureAt?.toString().orEmpty(),
            r.consecutiveFailures.toString(),
            r.cooldownUntil?.toString().orEmpty(),
            r.discoveredAt.toString(),
        ).joinToString("\u0001")
        return s.toByteArray(Charsets.UTF_8)
    }

    private fun deserialize(bytes: ByteArray): RvecPolicy.RvecRecord {
        val p = String(bytes, Charsets.UTF_8).split('\u0001')
        require(p.size >= 12)
        return RvecPolicy.RvecRecord(
            hostname = p[0],
            ipAddress = p[1],
            ipFamily = IpFamily.valueOf(p[2]),
            port = p[3].toInt(),
            transport = Transport.valueOf(p[4]),
            alpn = if (p[5].isEmpty()) emptyList() else p[5].split(','),
            networkContextId = p[6],
            lastSuccessAt = p[7].toLongOrNull(),
            lastFailureAt = p[8].toLongOrNull(),
            consecutiveFailures = p[9].toInt(),
            cooldownUntil = p[10].toLongOrNull(),
            discoveredAt = p[11].toLong(),
        )
    }
}

internal class RvecCrypto(context: Context) {
    private val key: SecretKey

    init {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        val prefs = EncryptedSharedPreferences.create(
            "dtma_rvec_keys",
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        val existing = prefs.getString("aes_key", null)
        if (existing != null) {
            key = SecretKeySpec(Base64.decode(existing, Base64.NO_WRAP), "AES")
        } else {
            val kg = KeyGenerator.getInstance("AES")
            kg.init(256)
            val generated = kg.generateKey()
            prefs.edit()
                .putString("aes_key", Base64.encodeToString(generated.encoded, Base64.NO_WRAP))
                .apply()
            key = generated
        }
    }

    fun encrypt(plain: ByteArray): String {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(plain)
        val out = ByteBuffer.allocate(iv.size + encrypted.size)
        out.put(iv)
        out.put(encrypted)
        return Base64.encodeToString(out.array(), Base64.NO_WRAP)
    }

    fun decrypt(blob: String): ByteArray {
        val all = Base64.decode(blob, Base64.NO_WRAP)
        val iv = all.copyOfRange(0, 12)
        val data = all.copyOfRange(12, all.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return cipher.doFinal(data)
    }
}
