package com.smokelog.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
abstract class CookDao {
    @Query("SELECT * FROM cooks ORDER BY COALESCE(startTime, createdAt) DESC")
    abstract fun observeCooks(): Flow<List<Cook>>

    @Query("SELECT tags FROM cooks WHERE tags != ''")
    abstract suspend fun allTagStrings(): List<String>

    @Query("SELECT * FROM events ORDER BY time")
    abstract fun observeAllEvents(): Flow<List<CookEvent>>

    @Query("SELECT * FROM photos ORDER BY time")
    abstract fun observeAllPhotos(): Flow<List<CookPhoto>>

    @Query("SELECT * FROM cooks WHERE id = :id")
    abstract fun observeCook(id: Long): Flow<Cook?>

    @Query("SELECT * FROM cooks WHERE id = :id")
    abstract suspend fun getCook(id: Long): Cook?

    @Query("SELECT * FROM cooks WHERE id != :excludeId AND meat LIKE :meat ORDER BY COALESCE(startTime, createdAt) DESC LIMIT :limit")
    abstract suspend fun similarCooks(meat: String, excludeId: Long, limit: Int = 5): List<Cook>

    /** Most recent "notes for next cook" left on other cooks of the same meat. */
    @Query("SELECT * FROM cooks WHERE meat = :meat COLLATE NOCASE AND id != :excludeId AND nextCookNotes != '' ORDER BY COALESCE(startTime, createdAt) DESC LIMIT 3")
    abstract suspend fun nextCookNotesFor(meat: String, excludeId: Long): List<Cook>

    @Insert abstract suspend fun insertCook(cook: Cook): Long
    @Update abstract suspend fun updateCook(cook: Cook)
    @Delete abstract suspend fun deleteCook(cook: Cook)

    @Query("SELECT * FROM readings WHERE cookId = :cookId ORDER BY time")
    abstract fun observeReadings(cookId: Long): Flow<List<TempReading>>

    @Query("SELECT * FROM readings WHERE cookId = :cookId ORDER BY time")
    abstract suspend fun getReadings(cookId: Long): List<TempReading>

    @Query("SELECT * FROM readings WHERE cookId = :cookId ORDER BY time DESC LIMIT 1")
    abstract suspend fun latestReading(cookId: Long): TempReading?

    @Insert abstract suspend fun insertReading(r: TempReading): Long
    @Insert abstract suspend fun insertReadings(r: List<TempReading>)
    @Delete abstract suspend fun deleteReading(r: TempReading)

    @Query("SELECT * FROM events WHERE cookId = :cookId ORDER BY time")
    abstract fun observeEvents(cookId: Long): Flow<List<CookEvent>>

    @Query("SELECT * FROM events WHERE cookId = :cookId ORDER BY time")
    abstract suspend fun getEvents(cookId: Long): List<CookEvent>

    @Insert abstract suspend fun insertEvent(e: CookEvent): Long
    @Insert abstract suspend fun insertEvents(e: List<CookEvent>)
    @Delete abstract suspend fun deleteEvent(e: CookEvent)

    @Query("SELECT * FROM photos WHERE cookId = :cookId ORDER BY time")
    abstract fun observePhotos(cookId: Long): Flow<List<CookPhoto>>

    @Query("SELECT * FROM photos WHERE cookId = :cookId ORDER BY time")
    abstract suspend fun getPhotos(cookId: Long): List<CookPhoto>

    @Insert abstract suspend fun insertPhoto(p: CookPhoto): Long
    @Insert abstract suspend fun insertPhotos(p: List<CookPhoto>)
    @Update abstract suspend fun updatePhoto(p: CookPhoto)
    @Delete abstract suspend fun deletePhoto(p: CookPhoto)

    @Transaction
    open suspend fun importCook(
        cook: Cook, readings: List<TempReading>, events: List<CookEvent>, photos: List<CookPhoto> = emptyList(),
    ): Long {
        val id = insertCook(cook.copy(id = 0, traegerThing = null))
        insertReadings(readings.map { it.copy(id = 0, cookId = id) })
        insertEvents(events.map { it.copy(id = 0, cookId = id) })
        insertPhotos(photos.map { it.copy(id = 0, cookId = id) })
        return id
    }
}

@Database(entities = [Cook::class, TempReading::class, CookEvent::class, CookPhoto::class], version = 5, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun cookDao(): CookDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "smokelog.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cooks ADD COLUMN nextCookNotes TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(PHOTOS_TABLE_SQL)
                db.execSQL(PHOTOS_INDEX_SQL)
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cooks ADD COLUMN tags TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE cooks ADD COLUMN category TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE readings ADD COLUMN probe3F REAL")
                db.execSQL("ALTER TABLE readings ADD COLUMN probe4F REAL")
            }
        }
        private const val PHOTOS_TABLE_SQL = "CREATE TABLE IF NOT EXISTS `photos` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `cookId` INTEGER NOT NULL, `time` INTEGER NOT NULL, `fileName` TEXT NOT NULL, `caption` TEXT NOT NULL, FOREIGN KEY(`cookId`) REFERENCES `cooks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
        private const val PHOTOS_INDEX_SQL = "CREATE INDEX IF NOT EXISTS `index_photos_cookId` ON `photos` (`cookId`)"
    }
}
