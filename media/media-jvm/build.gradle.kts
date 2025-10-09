plugins {
    id("java-library")
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
        implementation(libs.vlcj)
        implementation(libs.kotlinx.serialization.json)
    }
}
