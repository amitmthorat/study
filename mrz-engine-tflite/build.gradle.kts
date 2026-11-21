plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.bcv)
}

android {
    namespace = "com.airline.mrz.engine.tflite"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Models must not be compressed inside the AAR/APK so they can be memory-mapped.
    androidResources { noCompress += "tflite" }
}

kotlin {
    explicitApi()
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    api(project(":mrz-engine-api"))
    implementation(libs.tflite)
    // The GPU delegate is optional for integrators; it is resolved by reflection in Delegates.kt.
    compileOnly(libs.tflite.gpu)
}
