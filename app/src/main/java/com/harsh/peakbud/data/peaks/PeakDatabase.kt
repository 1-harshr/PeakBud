package com.harsh.peakbud.data.peaks

import android.content.Context
import androidx.room.*
import com.harsh.peakbud.core.model.GeoPoint
import com.harsh.peakbud.core.model.Peak

@Entity(tableName = "peaks", primaryKeys = ["packKey", "sourceId"], indices = [Index(value = ["packKey", "latitude", "longitude"])])
data class PeakRecord(
    val packKey: String, val sourceId: String, val name: String,
    val latitude: Double, val longitude: Double, val publishedHeightM: Double?, val source: String,
) {
    fun toPeak() = Peak(sourceId, name, GeoPoint(latitude, longitude), publishedHeightM, source)
}

@Entity(tableName = "regions")
data class RegionRecord(@PrimaryKey val packKey: String, val regionId: String, val name: String, val version: String)

@Dao
interface PeakDao {
    @Query("SELECT * FROM peaks WHERE packKey = :key AND latitude BETWEEN :south AND :north AND longitude BETWEEN :west AND :east")
    suspend fun candidates(key: String, south: Double, west: Double, north: Double, east: Double): List<PeakRecord>
    @Query("SELECT * FROM regions WHERE packKey = :key") suspend fun region(key: String): RegionRecord?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(peaks: List<PeakRecord>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertRegion(region: RegionRecord)
    @Query("DELETE FROM peaks WHERE packKey = :key") suspend fun deletePeaks(key: String)
    @Query("DELETE FROM regions WHERE packKey = :key") suspend fun deleteRegion(key: String)
}

@Database(entities = [PeakRecord::class, RegionRecord::class], version = 1, exportSchema = true)
abstract class PeakDatabase : RoomDatabase() {
    abstract fun peaks(): PeakDao
    companion object {
        fun open(context: Context) = Room.databaseBuilder(context, PeakDatabase::class.java, "peakbud.db").build()
    }
}
