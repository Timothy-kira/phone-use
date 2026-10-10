import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The phone-side half of Phone Use. Built as an APK that is never installed: the bridge pushes it to
// /data/local/tmp/phone-use/ and runs it with `app_process` under the shell uid (as scrcpy does), so
// the phone needs no app and no Shizuku. Only classes.dex is used; there are no resources.
plugins {
    id("com.android.application") version "8.13.2"
    id("org.jetbrains.kotlin.android") version "2.4.0"
}

android {
    namespace = "kira.phoneuse.server"
    compileSdk = 36

    defaultConfig {
        applicationId = "kira.phoneuse.server"
        minSdk = 26
        targetSdk = 28
        versionCode = 825
        versionName = "0.8.25"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        buildConfig = false
        resValues = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("META-INF/**", "kotlin/**", "DebugProbesKt.bin")
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.annotation:annotation:1.9.1")
}
