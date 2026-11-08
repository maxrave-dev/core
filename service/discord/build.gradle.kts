import com.android.build.gradle.internal.tasks.CompileArtProfileTask

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.android.lint)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    android {
        namespace = "org.simpmusic.discord"
        compileSdk = 37
        minSdk = 26
    }

    val xcfName = "discordKit"

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
                // SongEntity, which the presence is built from.
                implementation(projects.domain)
                // Logger.
                implementation(projects.common)
                // Transport: ktorExt carries ktor-client-core and the per-platform engine, the same
                // route listenTogether takes.
                implementation(projects.ktorExt)
                implementation(libs.ktor.client.websockets)
                implementation(libs.ktor.serialization.kotlinx.json)
                // SHA-256 and base64url for the PKCE code challenge.
                implementation(libs.okio)
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
