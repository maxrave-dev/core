import com.android.build.gradle.internal.tasks.CompileArtProfileTask

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.android.lint)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    // Target declarations - add or remove as needed below. These define
    // which platforms this KMP module supports.
    // See: https://kotlinlang.org/docs/multiplatform-discover-project.html#targets
    android {
        namespace = "org.simpmusic.lyrics"
        compileSdk = 37
        minSdk = 26
    }

    // For iOS targets, this is also where you should
    // configure native binary output. For more information, see:
    // https://kotlinlang.org/docs/multiplatform-build-native-binaries.html#build-xcframeworks

    // A step-by-step guide on how to include this library in an XCode
    // project can be found here:
    // https://developer.android.com/kotlin/multiplatform/migrate
    val xcfName = "lyricsServiceKit"

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

    // Source set declarations.
    // Declaring a target automatically creates a source set with the same name. By default, the
    // Kotlin Gradle Plugin creates additional source sets that depend on each other, since it is
    // common to share sources between related targets.
    // See: https://kotlinlang.org/docs/multiplatform-hierarchy.html
    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.kotlin.stdlib)
                // Add KMP dependencies here
                implementation(projects.domain)
                implementation(projects.common)
                implementation(projects.ktorExt)
                implementation(libs.kotlinx.datetime)
                implementation(libs.ktor.client.encoding)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlin.reflect)
            }
        }

        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }

        androidMain {
            dependencies {
                // Add Android-specific dependencies here. Note that this source set depends on
                // commonMain by default and will correctly pull the Android artifacts of any KMP
                // dependencies declared in commonMain.
                //
                // Romanization for Japanese and Chinese. Declared per JVM-ish target rather than in
                // commonMain because both are ordinary Java libraries with no Kotlin/Native build:
                // putting them in commonMain would break the iOS compilation, which gets a no-op
                // actual instead. Kanji readings are context-dependent, so a lookup table cannot do
                // it — kuromoji is a real morphological analyzer, which is why it costs ~12.7 MB.
                //
                // pinyin4j rather than TinyPinyin, which is what Metrolist uses: TinyPinyin is only
                // published on JitPack, its POM there names its own groupId with the wrong case
                // (promeG vs promeg) so the transitive resolve dead-ends, and it drags in
                // `tinypinyin-android-asset-lexicons` — an Android-asset artifact that has no
                // business in jvmMain. pinyin4j sits on Maven Central, is 316 KB, and its only
                // declared dependency is junit at test scope.
                implementation(libs.kuromoji.ipadic)
                implementation(libs.pinyin4j)
            }
        }

        iosMain {
            dependencies {
                // Add iOS-specific dependencies here. This a source set created by Kotlin Gradle
                // Plugin (KGP) that each specific iOS target (e.g., iosX64) depends on as
                // part of KMP’s default source set hierarchy. Note that this source set depends
                // on common by default and will correctly pull the iOS artifacts of any
                // KMP dependencies declared in commonMain.
            }
        }

        jvmMain {
            dependencies {
                // Same two as androidMain — see the note there. The desktop build gets Japanese and
                // Chinese romanization for free because neither library is Android-specific.
                implementation(libs.kuromoji.ipadic)
                implementation(libs.pinyin4j)
            }
        }
    }
}

tasks.withType<CompileArtProfileTask> {
    enabled = false
}