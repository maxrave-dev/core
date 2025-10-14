plugins {
    id("java-library")
    id("com.google.osdetector") version "1.7.3"
    alias(libs.plugins.jetbrains.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        freeCompilerArgs.add("-Xwhen-guards")
    }
    dependencies {
        implementation(projects.common)
        implementation(projects.domain)
        implementation(platform(libs.koin.bom))
        implementation(libs.koin.jvm)
        implementation(libs.kotlinx.serialization.json)
        val fxSuffix = when (osdetector.classifier) {
            "linux-x86_64" -> "linux"
            "linux-aarch_64" -> "linux-aarch64"
            "windows-x86_64" -> "win"
            "osx-x86_64" -> "mac"
            "osx-aarch_64" -> "mac-aarch64"
            else -> throw IllegalStateException("Unknown OS: ${osdetector.classifier}")
        }
        implementation("org.openjfx:javafx-base:21:${fxSuffix}")
        implementation("org.openjfx:javafx-graphics:21:${fxSuffix}")
        implementation("org.openjfx:javafx-controls:21:${fxSuffix}")
        implementation("org.openjfx:javafx-swing:21:${fxSuffix}")
        implementation("org.openjfx:javafx-web:21:${fxSuffix}")
        implementation("org.openjfx:javafx-media:21:${fxSuffix}")
    }
}
