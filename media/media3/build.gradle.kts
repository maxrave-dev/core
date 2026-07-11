val isFullBuild: Boolean =
    try {
        extra["isFullBuild"] == "true"
    } catch (e: Exception) {
        false
    }

plugins {
    alias(libs.plugins.android.library)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.add("-Xmulti-dollar-interpolation")
        freeCompilerArgs.add("-Xwhen-guards")
    }
}

android {
    namespace = "com.maxrave.media3"
    compileSdk = 37

    defaultConfig {
        minSdk = 26

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {

    implementation(libs.core.ktx)
    implementation(libs.appcompat)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.espresso.core)

    implementation(projects.common)
    implementation(projects.domain)

    // Koin
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.core)
    implementation(libs.koin.android)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // Media3
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    api(libs.media3.session)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.rtsp)
    implementation(libs.media3.exoplayer.smoothstreaming)
    implementation(libs.media3.exoplayer.workmanager)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.okhttp3.logging.interceptor)
    implementation(libs.coroutines.guava)

    // Android Auto (Car App Library media templates)
    implementation(libs.car.app.core)
    implementation(libs.car.app.projected)
    // registerMediaPlaybackToken only accepts MediaSessionCompat.Token; both car-app
    // and media3-session ship androidx.media at runtime scope only
    implementation(libs.androidx.media)

    // Google Cast (gated: real SDK for full builds, no-op stub for FOSS builds)
    if (isFullBuild) {
        implementation(projects.cast)
    } else {
        implementation(projects.castEmpty)
    }
}