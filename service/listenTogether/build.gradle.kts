import com.android.build.gradle.internal.tasks.CompileArtProfileTask

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.android.lint)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    android {
        namespace = "org.simpmusic.listentogether"
        compileSdk = 37
        minSdk = 26
    }

    val xcfName = "listenTogetherKit"

    iosArm64 {
        binaries.framework {
            baseName = xcfName
        }
    }

    iosSimulatorArm64 {
        binaries.framework {
            baseName = xcfName
        }
    }

    jvm {
    }

    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.kotlin.stdlib)
                // The wire format is protobuf. kotlinx-serialization-protobuf produces the same
                // bytes from @Serializable classes that protoc produces from listentogether.proto,
                // which is what lets the whole codec live in commonMain — see docs/listen-together.md.
                implementation(libs.kotlinx.serialization.protobuf)
                // Gzip for the envelope's `compressed` flag. java.util.zip is JVM-only; okio ships
                // GzipSource/GzipSink on every target this module builds for.
                implementation(libs.okio)
                // One multiplatform lock, for ServerClock. Metrolist guards it with @Synchronized,
                // which does not exist in commonMain; atomicfu's SynchronizedObject compiles to the
                // same JVM monitor and gives Native a real lock. Used as a plain library — the
                // atomicfu compiler plugin is NOT needed for kotlinx.atomicfu.locks.
                implementation(libs.atomicfu)
                // Transport. ktorExt already carries `api(ktor-client-core)` and the per-platform
                // engine actual (OkHttp on Android/JVM, Darwin on iOS), so depending on it is what
                // keeps the engine choice in one place — the same route kizzy's Discord gateway
                // takes. Coroutines arrive transitively with ktor-client-core, which is why no
                // module here declares them: the version catalog has no coroutines-core entry.
                implementation(projects.ktorExt)
                implementation(libs.ktor.client.websockets)
                // Logger.
                implementation(projects.common)
            }
        }

        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }
}

tasks.withType<CompileArtProfileTask> {
    enabled = false
}
