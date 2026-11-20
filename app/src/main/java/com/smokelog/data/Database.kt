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

    @Transaction
    open suspend fun importCook(cook: Cook, readings: List<TempReading>, events: List<CookEvent>): Long {
        val id = insertCook(cook.copy(id = 0, traegerThing = null))
        insertReadings(readings.map { it.copy(id = 0, cookId = id) })
        insertEvents(events.map { it.copy(id = 0, cookId = id) })
        return id
    }
}

@Database(entities = [Cook::class, TempReading::class, CookEvent::class], version = 2, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun cookDao(): CookDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "smokelog.db")
                .addMigrations(MIGRATION_1_2)
                .build()

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cooks ADD COLUMN nextCookNotes TEXT NOT NULL DEFAULT ''")
            }
        }
    }
}
