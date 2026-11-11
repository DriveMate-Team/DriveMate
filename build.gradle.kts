// 맨 위 빌드 파일: 모듈들이 함께 쓰는 플러그인 목록 (apply false = 여기서는 불러오기만 하고 적용은 각 모듈에서)
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
