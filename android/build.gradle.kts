// The Android library: Lyrelog.start(context, Configuration(...)), the app's defaults, the
// lifecycle (flush on background), crashes and screens. The logic lives in `core`.
// Maven coordinates: com.lyrelog:lyrelog-android (not on a public repository yet;
// `publishToMavenLocal` puts it and lyrelog-core in ~/.m2).
plugins {
    id("com.android.library")
    `maven-publish`
}

android {
    namespace = "com.lyrelog"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
        // Built against 37, but nothing in it needs more than 34 to compile the app: apps on
        // Flutter's or React Native's default (36) don't have to raise theirs.
        aarMetadata {
            minCompileSdk = 34
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // VersionTest: Lyrelog.VERSION and the artifact's version agree.
        val sdkVersion = project.version.toString()
        unitTests.all { it.systemProperty("lyrelog.version", sdkVersion) }
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

publishing {
    publications {
        create<MavenPublication>("release") {
            artifactId = "lyrelog-android"
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("Lyrelog for Android")
                description.set("Structured logging for Android apps, sent to Lyrelog.")
            }
        }
    }
}

kotlin {
    coreLibrariesVersion = "2.2.0"
    compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
        // Java passes null where Kotlin says it can't (`Lyrelog.info(e.getMessage())`): the
        // engine checks for it instead of Kotlin's checks throwing into the app (as `core`, `jvm`).
        freeCompilerArgs.add("-Xno-param-assertions")
        freeCompilerArgs.add("-opt-in=com.lyrelog.core.InternalLyrelogApi")
    }
}

dependencies {
    api(project(":core"))
    testImplementation("junit:junit:4.13.2")
}
