plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.bcv)
}

android {
    namespace = "com.airline.mrz"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { buildConfig = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

kotlin {
    explicitApi()
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    api(project(":mrz-core"))
    api(project(":mrz-engine-api"))
    api(project(":mrz-camera"))
    implementation(project(":mrz-engine-tflite"))
    // Development engine; stripped from release builds of the SDK by the build type below.
    debugImplementation(project(":mrz-engine-mlkit"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.activity.ktx)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test> { useJUnitPlatform() }

// Offline guarantee: the merged manifest of this library must never request network access.
tasks.register("checkNoInternetPermission") {
    group = "verification"
    val manifests = fileTree(projectDir) { include("src/**/AndroidManifest.xml") }
    inputs.files(manifests)
    doLast {
        manifests.forEach { f ->
            if (f.readText().contains("android.permission.INTERNET")) {
                throw GradleException("${f.path} requests INTERNET; the SDK must stay offline")
            }
        }
    }
}
tasks.named("check") { dependsOn("checkNoInternetPermission") }
