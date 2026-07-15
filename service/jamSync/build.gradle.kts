plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    android {
        namespace = "com.maxrave.jamsync"
        compileSdk = 37
        minSdk = 26
    }
    
    jvm {
    }
    
    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    )
    
    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.websockets)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kermit.logging)
                implementation(libs.coroutines.core)
            }
        }
        androidMain {
            dependencies {
                implementation(libs.coroutines.android)
            }
        }
    }
}
