import com.android.build.gradle.internal.tasks.CompileArtProfileTask

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.android.lint)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    android {
        namespace = "org.simpmusic.loginsync"
        compileSdk = 37
        minSdk = 26
    }

    // Android sends, Desktop receives; iOS takes no part. With JVM-based targets only, commonMain can
    // use javax.crypto directly — no expect/actual for the cipher.
    jvm()

    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.kotlin.stdlib)
                implementation(libs.kotlinx.serialization.json)
                // Transport: ktorExt carries `api(ktor-client-core)` and the per-platform engine, the
                // same route listenTogether and kizzy take, so the engine choice stays in one place.
                implementation(projects.ktorExt)
                // Logger.
                implementation(projects.common)
            }
        }

        jvmMain {
            dependencies {
                // Only the Desktop hosts a server; the APK never ships these.
                implementation(libs.ktor.server.core)
                implementation(libs.ktor.server.cio)
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
