package com.example.drivemate

import com.example.drivemate.data.EventEntity
import com.example.drivemate.data.TripEntity
import com.example.drivemate.model.EventType
import com.example.drivemate.report.RoadmapBuilder
import com.example.drivemate.report.Stage
import org.junit.Assert.assertEquals
import org.junit.Test

/** 연수 로드맵 단계 완료 판단 (팀 기준값: 회전·교차로 2번 연속, 합류 1번) */
class RoadmapTest {
    private fun trip(id: Long, m: Int = 1000) = TripEntity(id = id, startTime = id * 1000, endTime = id * 1000 + 500, mode = "BEGINNER", topics = "", distanceM = m)
    private fun turn(trip: Long, dir: String, signal: Boolean) =
        EventEntity(tripId = trip, time = 0, type = EventType.TURN.name, speedKmh = 20f, turnSignal = "OFF", brake = false, direction = dir, signalUsed = signal)
    private fun pass(trip: Long, kind: String) =
        EventEntity(tripId = trip, time = 0, type = EventType.POINT_PASSED.name, speedKmh = 30f, turnSignal = "OFF", brake = false, direction = kind)
    private fun nearBrake(trip: Long) =
        EventEntity(tripId = trip, time = 0, type = EventType.HARD_BRAKE.name, speedKmh = 40f, turnSignal = "OFF", brake = true, nearTurn = true)

    @Test fun `기록이 없으면 회차 0, 첫 단계가 지금 단계`() {
        val r = RoadmapBuilder.build(emptyList(), emptyList())
        assertEquals(0, r.session); assertEquals(emptySet<Stage>(), r.done); assertEquals(Stage.LEFT_TURN, r.current)
    }

    @Test fun `500m 미만 주행은 회차로 세지 않는다`() {
        assertEquals(1, RoadmapBuilder.build(listOf(trip(1), trip(2, m = 300)), emptyList()).session)
    }

    @Test fun `좌회전 깜빡이 기록이 2번 연속이면 완료, 회전 없는 주행은 건너뛴다`() {
        val trips = listOf(trip(1), trip(2), trip(3))
        val r = RoadmapBuilder.build(trips, listOf(turn(1, "LEFT", true), turn(3, "LEFT", true)))
        assertEquals(setOf(Stage.LEFT_TURN), r.done); assertEquals(Stage.RIGHT_TURN, r.current)
    }

    @Test fun `중간에 깜빡이 기록 없는 회전이 있으면 연속이 끊긴다`() {
        val r = RoadmapBuilder.build(listOf(trip(1), trip(2), trip(3)),
            listOf(turn(1, "RIGHT", true), turn(2, "RIGHT", false), turn(3, "RIGHT", true)))
        assertEquals(emptySet<Stage>(), r.done)
    }

    @Test fun `교차로는 앞 급감속 없이 2번 연속, 합류는 1번`() {
        val ok = RoadmapBuilder.build(listOf(trip(1), trip(2)), listOf(pass(1, "INTERSECTION"), pass(2, "INTERSECTION"), pass(2, "MERGE")))
        assertEquals(setOf(Stage.INTERSECTION, Stage.MERGE), ok.done)
        assertEquals(Stage.LEFT_TURN, ok.current)   // 순서상 아직 안 끝낸 첫 단계
        val braked = RoadmapBuilder.build(listOf(trip(1), trip(2)), listOf(pass(1, "INTERSECTION"), pass(2, "INTERSECTION"), nearBrake(2)))
        assertEquals(emptySet<Stage>(), braked.done)
    }
}
