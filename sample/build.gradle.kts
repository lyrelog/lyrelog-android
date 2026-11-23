// Tilefall: a made-up puzzle game that shows the SDK in an app. Its ingest key and endpoint
// come from local.properties (never committed):
//
//     lyrelog.key=lyr_ing_...
//     lyrelog.endpoint=http://10.0.2.2:8090     # the local stack, from the emulator
//
// Without them it uses https://ingest.lyrelog.com and an empty key (events stay on the device).
import java.util.Properties

plugins {
    id("com.android.application")
}

val local = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}

fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.example.tilefall"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.tilefall"
        minSdk = 24
        targetSdk = 37
        versionCode = 3
        versionName = "1.2.0"
        buildConfigField("String", "LYRELOG_KEY", quoted(local.getProperty("lyrelog.key", "")))
        buildConfigField("String", "LYRELOG_ENDPOINT", quoted(local.getProperty("lyrelog.endpoint", "https://ingest.lyrelog.com")))
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":android"))
}
