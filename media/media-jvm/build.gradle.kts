plugins {
    id("java-library")
    alias(libs.plugins.jetbrains.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
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
        // Native desktop notifications: Windows Toast, macOS UserNotifications, Linux D-Bus.
        implementation(libs.nucleus.notification)

        // mpv — libmpv is bound through JNA (see MpvLibrary).
        implementation(libs.jna)
    }
}