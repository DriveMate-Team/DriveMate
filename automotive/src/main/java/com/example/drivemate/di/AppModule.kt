package com.example.drivemate.di

import android.content.Context
import androidx.room.Room
import com.example.drivemate.coaching.AnswerEngine
import com.example.drivemate.coaching.KeywordAnswerEngine
import com.example.drivemate.data.DriveDao
import com.example.drivemate.data.DriveDatabase
import com.example.drivemate.vehicle.AndroidLocationSource
import com.example.drivemate.vehicle.LocationSource
import com.example.drivemate.vehicle.NaviSource
import com.example.drivemate.vehicle.PleosVehicleSource
import com.example.drivemate.vehicle.SwitchingNaviSource
import com.example.drivemate.vehicle.VehicleSource
import com.example.drivemate.voice.PleosSpeechInput
import com.example.drivemate.voice.PleosSpeechOutput
import com.example.drivemate.voice.SpeechInput
import com.example.drivemate.voice.SpeechOutput
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/**
 * Hilt에게 "이 통로가 필요하면 이 구현을 넣어 줘"라고 알려주는 곳.
 * 나중에 SDK를 바꿀 때(예: 위치를 FusedLocation SDK로, 답변을 Gleo AI로)는 여기 한 줄만 바꾸면 된다.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class BindModule {
    @Binds abstract fun vehicle(impl: PleosVehicleSource): VehicleSource
    @Binds abstract fun navi(impl: SwitchingNaviSource): NaviSource
    @Binds abstract fun location(impl: AndroidLocationSource): LocationSource
    @Binds abstract fun speechOutput(impl: PleosSpeechOutput): SpeechOutput
    @Binds abstract fun speechInput(impl: PleosSpeechInput): SpeechInput
}

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /** 앱이 켜져 있는 동안 계속 살아 있는 작업 공간 (메인 스레드에서 돈다) */
    @Provides
    @Singleton
    fun appScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 기기 안 데이터베이스 파일 "drivemate.db" */
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): DriveDatabase =
        Room.databaseBuilder(context, DriveDatabase::class.java, "drivemate.db").build()

    @Provides
    fun dao(db: DriveDatabase): DriveDao = db.dao()

    /** 질문 답변: 1차는 키워드 방식. 2차에 AI로 바꿀 때 여기만 바꾼다 */
    @Provides
    @Singleton
    fun answerEngine(): AnswerEngine = KeywordAnswerEngine()
}
