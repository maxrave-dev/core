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
        implementation(libs.kotlinx.coroutinesSwing)

        // Gstreamer
        implementation(libs.gst1.java.core)
        implementation(libs.gst1.java.fx)
        implementation(libs.gst1.java.swing)

        implementation(libs.jna)
        implementation(libs.jna.platform)
    }
}