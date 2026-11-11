package com.example.drivemate.vehicle

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 현재 위치를 주는 통로. 이벤트가 생긴 곳을 기록하는 데만 쓴다.
 * 1차는 안드로이드 기본 LocationManager로 만들고, 나중에 Pleos FusedLocation SDK로 바꿔 끼울 수 있다.
 * (설계문서 1단계 결정 사항)
 */
interface LocationSource {
    /** 주행을 시작할 때: 위치 받기 시작 */
    fun start()

    /** 주행이 끝날 때: 위치 받기 중단 (불필요한 센서 수집을 하지 않는다) */
    fun stop()

    /** 가장 최근 위치 (위도, 경도). 권한이 없거나 아직 못 받았으면 null */
    fun current(): Pair<Double, Double>?
}

@Singleton
class AndroidLocationSource @Inject constructor(
    @ApplicationContext private val context: Context,
) : LocationSource {

    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    @Volatile private var last: Location? = null
    private var listening = false

    private val listener = LocationListener { location -> last = location }

    private fun hasPermission() =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")   // 바로 위에서 hasPermission()으로 확인한다
    override fun start() {
        if (listening || !hasPermission()) return
        try {
            if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                last = manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 0f, listener, Looper.getMainLooper())
                listening = true
            }
        } catch (e: Exception) {
            Log.w("DriveMate", "위치 받기 실패: ${e.message}")
        }
    }

    override fun stop() {
        if (!listening) return
        listening = false
        try { manager.removeUpdates(listener) } catch (_: Exception) {}
    }

    override fun current(): Pair<Double, Double>? = last?.let { it.latitude to it.longitude }
}
