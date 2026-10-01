package ar.com.notifmp

import androidx.room.*

@Entity(tableName = "movements")
data class Movement(
  @PrimaryKey val mpId: String,
  val amount: Double,
  val payer: String?,
  val email: String?,
  val dateApproved: String?,
  val type: String?,
  val rawId: String? = null,
  val notifiedAt: Long = System.currentTimeMillis(),
)

@Dao
interface MovementDao {
  @Query("SELECT * FROM movements ORDER BY notifiedAt DESC LIMIT :limit")
  suspend fun last(limit: Int = 5): List<Movement>
  @Query("SELECT * FROM movements WHERE date(notifiedAt/1000,'unixepoch','localtime') = date('now','localtime')")
  suspend fun today(): List<Movement>
  @Query("SELECT * FROM movements WHERE date(notifiedAt/1000,'unixepoch','localtime') = date('now','-1 day','localtime')")
  suspend fun yesterday(): List<Movement>
  @Query("SELECT * FROM movements WHERE notifiedAt BETWEEN :from AND :to ORDER BY notifiedAt DESC")
  suspend fun range(from: Long, to: Long): List<Movement>
  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insert(m: Movement): Long
}

@Database(entities = [Movement::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
  abstract fun movements(): MovementDao
}
