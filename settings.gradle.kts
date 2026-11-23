// Lyrelog for Android and the JVM. `core` (the engine), `jvm` (the server SDK) and
// `jvm-logback` (its Logback appender) are plain Kotlin/JVM and always build; `android` (the
// library) and `sample` (a demo app) need an Android SDK, so they are included only when one is
// found: `sdk.dir` in local.properties, or ANDROID_HOME / ANDROID_SDK_ROOT.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "lyrelog-android"

include(":core")
include(":jvm")
include(":jvm-logback")

val localProperties = java.util.Properties().apply {
    val file = rootDir.resolve("local.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}
val androidSdk = listOfNotNull(
    localProperties.getProperty("sdk.dir"),
    System.getenv("ANDROID_HOME"),
    System.getenv("ANDROID_SDK_ROOT"),
).map(::File).firstOrNull { it.isDirectory }

if (androidSdk != null) {
    include(":android")
    include(":sample")
} else {
    logger.lifecycle("lyrelog: no Android SDK found (local.properties sdk.dir, ANDROID_HOME); building :core, :jvm and :jvm-logback only")
}
