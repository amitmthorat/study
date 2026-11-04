pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "passport-mrz-sdk"

// Pure JVM modules: always built. They carry the parser, integrator, stopping rule,
// image math and the evaluation harness, and need no Android SDK.
include(":mrz-core")
include(":mrz-engine-api")
include(":tools:eval")

// Android modules are included only when an Android SDK is available, so the JVM core
// can be built and tested on any machine (and in CI jobs without the SDK).
val androidSdkPresent: Boolean = run {
    val fromEnv = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    if (fromEnv != null && file(fromEnv).isDirectory) return@run true
    val localProps = file("local.properties")
    if (localProps.isFile) {
        val props = java.util.Properties().apply { localProps.inputStream().use { load(it) } }
        val sdkDir = props.getProperty("sdk.dir")
        if (sdkDir != null && file(sdkDir).isDirectory) return@run true
    }
    false
}

if (androidSdkPresent) {
    include(":mrz-engine-tflite")
    include(":mrz-engine-mlkit")
    include(":mrz-camera")
    include(":mrz-sdk")
    include(":mrz-ui")
    include(":sample-app")
} else {
    logger.warn(
        "Android SDK not found (ANDROID_HOME, ANDROID_SDK_ROOT or local.properties sdk.dir). " +
            "Only the JVM modules are included: :mrz-core, :mrz-engine-api, :tools:eval."
    )
}
