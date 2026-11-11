package com.example.drivemate

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * 앱이 켜질 때 가장 먼저 만들어지는 클래스.
 * @HiltAndroidApp 을 붙이면 Hilt가 "필요한 객체를 자동으로 넣어 주는" 준비를 여기서 시작한다.
 */
@HiltAndroidApp
class DriveMateApp : Application()
