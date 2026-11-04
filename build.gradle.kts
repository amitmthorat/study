// Only plugins needed by the always-included JVM modules are declared here. Dokka is
// applied per module when API docs are generated (see docs/api). The Android
// Gradle Plugin and the Compose compiler plugin are applied by the Android modules
// themselves (see settings.gradle.kts), so that a machine without the Android SDK, or
// without access to Google's Maven repository, can still build and test :mrz-core,
// :mrz-engine-api and :tools:eval.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.bcv) apply false
}

allprojects {
    group = "com.airline.mrz"
    version = "0.1.0-SNAPSHOT"
}
