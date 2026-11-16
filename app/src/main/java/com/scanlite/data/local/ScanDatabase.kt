package com.scanlite.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "scans")
data class ScanRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val displayName: String,
    val createdAt: Long,
    val format: String,
    val sizeBytes: Long,
    val pageCount: Int,
    val uri: String
)
@Dao
interface ScanDao {
    @Query("SELECT * FROM scans ORDER BY createdAt DESC, id DESC") fun observe(): Flow<List<ScanRecord>>
    @Insert suspend fun insert(records: List<ScanRecord>)
    @Delete suspend fun delete(record: ScanRecord)
    @Query("SELECT * FROM scans WHERE id = :id") suspend fun get(id: Long): ScanRecord?
}
@Database(entities = [ScanRecord::class], version = 1, exportSchema = true)
abstract class ScanDatabase : RoomDatabase() { abstract fun scans(): ScanDao }
