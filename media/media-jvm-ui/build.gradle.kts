plugins {
    id("java-library")
    alias(libs.plugins.jetbrains.kotlin.jvm)
    id("com.google.osdetector") version "1.7.3"
    alias(libs.plugins.compose.compiler)
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }

    dependencies {
        implementation(projects.common)
        implementation(projects.domain)
        implementation(projects.mediaJvm)
        // UI
        implementation(libs.compose.ui)
        implementation(libs.compose.material3)

        implementation(libs.coil.compose)
        implementation(libs.coil.network.okhttp)

        implementation(platform(libs.koin.bom))
        implementation(libs.koin.jvm)
        implementation(libs.koin.compose)

        // Gstreamer
        implementation(libs.gst1.java.core)
        implementation(libs.gst1.java.fx)
        implementation(libs.gst1.java.swing)

        implementation(libs.jna)
        implementation(libs.jna.platform)

        val fxSuffix =
            when (osdetector.classifier) {
                "linux-x86_64" -> "linux"
                "linux-aarch_64" -> "linux-aarch64"
                "windows-x86_64" -> "win"
                "osx-x86_64" -> "mac"
                "osx-aarch_64" -> "mac-aarch64"
                else -> throw IllegalStateException("Unknown OS: ${osdetector.classifier}")
            }
        implementation(libs.kotlinx.coroutinesSwing)
        implementation("org.openjfx:javafx-base:19:$fxSuffix")
        implementation("org.openjfx:javafx-graphics:19:$fxSuffix")
        implementation("org.openjfx:javafx-controls:19:$fxSuffix")
        implementation("org.openjfx:javafx-media:19:$fxSuffix")
        implementation("org.openjfx:javafx-web:19:$fxSuffix")
        implementation("org.openjfx:javafx-swing:19:$fxSuffix")
    }
}