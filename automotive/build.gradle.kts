import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)   // Compose(화면을 코틀린 코드로 그리는 방식)
    alias(libs.plugins.ksp)              // Room·Hilt용 코드 자동 생성
    alias(libs.plugins.hilt)             // 의존성 주입
}

// Android SDK 폴더 위치를 local.properties에서 읽는다 (아래 android.car.jar 경로에 사용)
val localProps = Properties().apply {
    rootProject.file("local.properties").inputStream().use { load(it) }
}

android {
    namespace = "com.example.drivemate"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.drivemate"
        minSdk = 29
        targetSdk = 36
        versionCode = 2
        versionName = "2.0"
    }

    // release 서명 정보: 비밀번호는 저장소에 올리지 않는 local.properties에서 읽는다 (없으면 서명 없이 빌드)
    val releaseStore = localProps.getProperty("release.storeFile")
    signingConfigs {
        if (releaseStore != null) create("release") {
            storeFile = file(releaseStore)
            storePassword = localProps.getProperty("release.storePassword")
            keyAlias = localProps.getProperty("release.keyAlias")
            keyPassword = localProps.getProperty("release.keyPassword")
        }
    }

    buildTypes {
        release {
            if (releaseStore != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true   // BuildConfig.DEBUG로 "데모 경로" 버튼을 디버그 빌드에서만 보이게 한다
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // 화면 (Jetpack Compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    // 의존성 주입 (Hilt)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // 저장 (Room)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Pleos SDK
    implementation(libs.pleos.vehicle)   // 방향지시등, 브레이크
    implementation(libs.pleos.navi)      // 목적지, 다음 회전
    implementation(libs.pleos.tts)       // 음성 안내
    implementation(libs.pleos.stt)       // 음성 질문 인식

    // 차량 속도/기어를 읽는 AAOS 'android.car' 클래스. 차량(에뮬레이터)에 이미 들어 있어서 컴파일할 때만 참고한다.
    compileOnly(files("${localProps.getProperty("sdk.dir")}/platforms/android-36/optional/android.car.jar"))

    // 단위 테스트 (PC에서 바로 돌리는 테스트: 코칭 규칙, 이벤트 판단, 리포트 처방, 색 대비)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
