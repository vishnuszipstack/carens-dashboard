package dev.carens.dashboard.trips

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * One recorded trip. A row with [endedAtWallMs] == null is in progress (or was
 * interrupted by the process dying) and is excluded from history.
 */
@Entity(tableName = "trips")
data class TripEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAtWallMs: Long,
    val endedAtWallMs: Long? = null,
    val lastUpdatedWallMs: Long,
    val distanceM: Double = 0.0,
    val durationMs: Long = 0,
    val movingMs: Long = 0,
    val maxSpeedKmh: Double = 0.0,
    val gpsGapMs: Long = 0,
    val autoStarted: Boolean = false,
)

fun TripEntity.avgMovingKmh(): Double? =
    if (movingMs < 10_000 || distanceM < 50) null else distanceM / (movingMs / 1000.0) * 3.6

@Dao
interface TripDao {
    @Insert
    suspend fun insert(trip: TripEntity): Long

    @Update
    suspend fun update(trip: TripEntity)

    @Query("SELECT * FROM trips WHERE endedAtWallMs IS NOT NULL ORDER BY startedAtWallMs DESC")
    fun observeFinished(): Flow<List<TripEntity>>

    @Query("SELECT * FROM trips WHERE endedAtWallMs IS NULL")
    suspend fun openTrips(): List<TripEntity>

    @Query("DELETE FROM trips WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM trips")
    suspend fun deleteAll()
}

@Database(entities = [TripEntity::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao
}

class TripRepository(private val dao: TripDao) {
    val finishedTrips: Flow<List<TripEntity>> = dao.observeFinished()

    suspend fun insert(trip: TripEntity): Long = dao.insert(trip)
    suspend fun update(trip: TripEntity) = dao.update(trip)
    suspend fun delete(id: Long) = dao.delete(id)
    suspend fun deleteAll() = dao.deleteAll()

    /** Close trips left open by a crash or the system killing the app. */
    suspend fun closeInterruptedTrips(minDistanceM: Double) {
        dao.openTrips().forEach { t ->
            if (t.distanceM < minDistanceM) dao.delete(t.id)
            else dao.update(t.copy(endedAtWallMs = t.lastUpdatedWallMs))
        }
    }
}
