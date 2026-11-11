package com.example.drivemate.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 저장소(Repository): 화면과 코칭 로직이 데이터베이스를 직접 만지지 않고 이 클래스를 통해서만 읽고 쓴다.
 * (MVVM 구조의 "화면 - ViewModel - Repository" 중 맨 아래)
 */
@Singleton
class DriveRepository @Inject constructor(private val dao: DriveDao) {

    // ---------- 주행 ----------
    suspend fun startTrip(trip: TripEntity): Long = dao.insertTrip(trip)
    suspend fun updateTrip(trip: TripEntity) = dao.updateTrip(trip)
    suspend fun getTrip(id: Long): TripEntity? = dao.getTrip(id)
    fun observeTrip(id: Long): Flow<TripEntity?> = dao.observeTrip(id)
    fun observeFinishedTrips(): Flow<List<TripEntity>> = dao.observeFinishedTrips()

    /** 주행 하나 삭제 (그 주행의 이벤트·질문도 함께 지워진다) */
    suspend fun deleteTrip(id: Long) = dao.deleteTrip(id)

    /** 전체 삭제: 모든 주행 기록과 학습 정보(다음 목표)를 지운다 */
    suspend fun deleteEverything() {
        dao.deleteAllTrips()
        dao.saveProfile(ProfileEntity())
    }

    /** 앱이 주행 도중 꺼져서 끝나지 않은 채 남은 주행을 "그때 끝난 것"으로 정리한다 */
    suspend fun closeUnfinishedTrips() {
        dao.getUnfinishedTrips().forEach { trip ->
            val lastEventTime = dao.getEvents(trip.id).maxOfOrNull { it.time }
            dao.updateTrip(trip.copy(endTime = lastEventTime ?: trip.startTime))
        }
    }

    // ---------- 이벤트 ----------
    suspend fun addEvent(event: EventEntity): Long = dao.insertEvent(event)
    suspend fun getEvents(tripId: Long): List<EventEntity> = dao.getEvents(tripId)
    fun observeEvents(tripId: Long): Flow<List<EventEntity>> = dao.observeEvents(tripId)
    fun observeAllEvents(): Flow<List<EventEntity>> = dao.observeAllEvents()
    suspend fun setDifficult(eventId: Long, difficult: Boolean) = dao.setDifficult(eventId, difficult)

    // ---------- 질문 ----------
    suspend fun addQuestion(question: QuestionEntity): Long = dao.insertQuestion(question)
    suspend fun getQuestions(tripId: Long): List<QuestionEntity> = dao.getQuestions(tripId)
    fun observeQuestions(tripId: Long): Flow<List<QuestionEntity>> = dao.observeQuestions(tripId)

    // ---------- 사용자 학습 정보 ----------
    /** 저장된 것이 없으면 기본값을 돌려준다 */
    suspend fun getProfile(): ProfileEntity = dao.getProfile() ?: ProfileEntity()
    fun observeProfile(): Flow<ProfileEntity> = dao.observeProfile().map { it ?: ProfileEntity() }
    suspend fun saveProfile(profile: ProfileEntity) = dao.saveProfile(profile)
}
