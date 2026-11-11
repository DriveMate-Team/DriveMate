package com.example.drivemate.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * 데이터베이스에 묻고 쓰는 명령 모음. Room이 이 설명을 보고 실제 코드를 자동으로 만들어 준다.
 * Flow를 돌려주는 함수는 "값이 바뀔 때마다 새 값을 계속 알려주는 통로"다 (화면이 자동으로 갱신됨).
 */
@Dao
interface DriveDao {
    // ---------- 주행 ----------
    @Insert
    suspend fun insertTrip(trip: TripEntity): Long

    @Update
    suspend fun updateTrip(trip: TripEntity)

    @Query("SELECT * FROM trip WHERE id = :id")
    suspend fun getTrip(id: Long): TripEntity?

    @Query("SELECT * FROM trip WHERE id = :id")
    fun observeTrip(id: Long): Flow<TripEntity?>

    /** 끝난 주행들을 최신순으로 */
    @Query("SELECT * FROM trip WHERE endTime IS NOT NULL ORDER BY startTime DESC")
    fun observeFinishedTrips(): Flow<List<TripEntity>>

    /** 앱이 꺼져서 끝맺지 못한 주행을 찾는다 */
    @Query("SELECT * FROM trip WHERE endTime IS NULL")
    suspend fun getUnfinishedTrips(): List<TripEntity>

    @Query("DELETE FROM trip WHERE id = :id")
    suspend fun deleteTrip(id: Long)

    @Query("DELETE FROM trip")
    suspend fun deleteAllTrips()

    // ---------- 이벤트 ----------
    @Insert
    suspend fun insertEvent(event: EventEntity): Long

    @Query("SELECT * FROM event WHERE tripId = :tripId ORDER BY time")
    suspend fun getEvents(tripId: Long): List<EventEntity>

    @Query("SELECT * FROM event WHERE tripId = :tripId ORDER BY time")
    fun observeEvents(tripId: Long): Flow<List<EventEntity>>

    @Query("SELECT * FROM event")
    fun observeAllEvents(): Flow<List<EventEntity>>

    @Query("UPDATE event SET difficult = :difficult WHERE id = :id")
    suspend fun setDifficult(id: Long, difficult: Boolean)

    // ---------- 질문 ----------
    @Insert
    suspend fun insertQuestion(question: QuestionEntity): Long

    @Query("SELECT * FROM question WHERE tripId = :tripId ORDER BY time")
    suspend fun getQuestions(tripId: Long): List<QuestionEntity>

    @Query("SELECT * FROM question WHERE tripId = :tripId ORDER BY time")
    fun observeQuestions(tripId: Long): Flow<List<QuestionEntity>>

    // ---------- 사용자 학습 정보 ----------
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveProfile(profile: ProfileEntity)

    @Query("SELECT * FROM profile WHERE id = 1")
    suspend fun getProfile(): ProfileEntity?

    @Query("SELECT * FROM profile WHERE id = 1")
    fun observeProfile(): Flow<ProfileEntity?>
}

/** 데이터베이스 본체. 표가 바뀌면 version을 올려야 한다 */
@Database(
    entities = [TripEntity::class, EventEntity::class, QuestionEntity::class, ProfileEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class DriveDatabase : RoomDatabase() {
    abstract fun dao(): DriveDao
}
