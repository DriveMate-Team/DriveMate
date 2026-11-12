package com.example.drivemate.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * 기기 안 데이터베이스(Room)에 저장하는 표 4개. (설계문서 5단계)
 * 필요한 것만 저장하고, 사용자가 주행 단위 또는 전체로 지울 수 있다.
 * 서버로 보내지 않는다.
 */

/** 주행 1회 */
@Entity(tableName = "trip")
data class TripEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startTime: Long,              // 시작 시각 (ms)
    val endTime: Long? = null,        // 종료 시각. 주행 중이면 null
    val destination: String? = null,  // 목적지 이름 (내비에 경로가 없으면 null)
    val mode: String,                 // DriveMode 이름 ("BEGINNER" / "NORMAL")
    val topics: String,               // 연습 주제들을 쉼표로 이은 글자
    val distanceM: Int = 0,           // 달린 거리 (m)
)

/** 주행 이벤트 1건. 이벤트가 생긴 순간만 저장한다 (1초마다 전부 저장하지 않음) */
@Entity(
    tableName = "event",
    foreignKeys = [ForeignKey(
        entity = TripEntity::class, parentColumns = ["id"], childColumns = ["tripId"],
        onDelete = ForeignKey.CASCADE,   // 주행을 지우면 그 주행의 이벤트도 함께 지워진다
    )],
    indices = [Index("tripId")],
)
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val time: Long,                   // 생긴 시각 (ms)
    val latitude: Double? = null,     // 위치 (권한이 없거나 못 읽으면 null)
    val longitude: Double? = null,
    val type: String,                 // EventType 이름
    val speedKmh: Float,              // 그때 속도
    val turnSignal: String,           // 그때 방향지시등 상태 (TurnSignal 이름)
    val brake: Boolean,               // 그때 브레이크를 밟고 있었는지
    val direction: String? = null,    // 회전 이벤트일 때: "LEFT" / "RIGHT"
    val signalUsed: Boolean? = null,  // 회전 이벤트일 때: 100m 안에서 "회전 방향과 같은 쪽" 방향지시등 기록이 있었는지
    val signalSide: String? = null,   // 회전 이벤트일 때: 100m 안에서 켜진 쪽 "LEFT" / "RIGHT" / "LEFT,RIGHT" / "" (없음)
    val nearTurn: Boolean? = null,    // 급감속일 때: 회전·교차로 100m 안이었는지
    val fromStop: Boolean? = null,    // 급가속일 때: 정차 후 출발 중이었는지
    val difficult: Boolean = false,   // 사용자가 "어려웠던 구간"으로 표시했는지
)

/** 질문 기록 1건 */
@Entity(
    tableName = "question",
    foreignKeys = [ForeignKey(
        entity = TripEntity::class, parentColumns = ["id"], childColumns = ["tripId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("tripId")],
)
data class QuestionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val time: Long,
    val question: String,             // 인식된 질문 문장
    val answer: String,               // 앱이 한 답변
    val topic: String? = null,        // 질문 주제 (예: "비보호 좌회전"). 목록에 없는 질문이면 null
)

/** 사용자 학습 정보. 항상 한 줄(id = 1)만 있다 */
@Entity(tableName = "profile")
data class ProfileEntity(
    @PrimaryKey val id: Int = 1,
    val mode: String = "BEGINNER",                // 마지막으로 고른 모드
    val topics: String = "INTERSECTION",          // 마지막으로 고른 연습 주제
    val goalTitle: String? = null,                // 지난 리포트가 정한 "다음 연습 목표" (없으면 null)
    val goalDetail: String? = null,               // 목표 아래 한 줄 설명 (예: "지난 주행: 3번 중 2번 기록 없음")
)
