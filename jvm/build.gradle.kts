// The JVM server SDK (Java and Kotlin on servers: Spring, Ktor, plain JVM): Lyrelog.start(...),
// a server key, environment from LYRELOG_ENVIRONMENT, the shutdown hook. The engine is `core`.
// Maven coordinates: com.lyrelog:lyrelog-jvm (not on a public repository yet;
// `publishToMavenLocal` puts it in ~/.m2). No runtime dependency beyond core and Kotlin's stdlib.
plugins {
    kotlin("jvm")
    `maven-publish`
}

kotlin {
    jvmToolchain(21)
    coreLibrariesVersion = "2.2.0"
    compilerOptions {
        // Servers on Java 11 and later.
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        // Readable by apps on an older Kotlin (React Native 0.88 and Flutter use Kotlin 2.2):
        // the metadata, the language and the stdlib this needs are 2.2's.
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
        freeCompilerArgs.add("-Xjdk-release=11")
        // Java passes null where Kotlin says it can't (`Lyrelog.info(e.getMessage())`): the entry
        // points check for it themselves instead of Kotlin's checks throwing into the caller.
        freeCompilerArgs.add("-Xno-param-assertions")
        freeCompilerArgs.add("-opt-in=com.lyrelog.core.InternalLyrelogApi")
        allWarningsAsErrors.set(true)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
    withSourcesJar()
    withJavadocJar()
}

tasks.jar {
    manifest { attributes("Automatic-Module-Name" to "com.lyrelog.jvm") }
}

dependencies {
    api(project(":core"))
    // Only for LyrelogUser, the coroutine context element: an application that uses coroutines
    // brings its own kotlinx-coroutines; nothing else here touches it.
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    testImplementation(testFixtures(project(":core")))
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(kotlin("test-junit5"))
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "lyrelog-jvm"
            from(components["java"])
            pom {
                name.set("Lyrelog for the JVM")
                description.set("Structured logging for Java and Kotlin servers, sent to Lyrelog.")
            }
        }
    }
}

// The tests run on the build's JDK; only the library is held to Java 11.
for (name in listOf("testCompileClasspath", "testRuntimeClasspath")) {
    configurations.named(name) {
        attributes.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21)
    }
}
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileTestKotlin") {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        freeCompilerArgs.set(listOf("-opt-in=com.lyrelog.core.InternalLyrelogApi"))
    }
}
tasks.named<JavaCompile>("compileTestJava") {
    options.release.set(21)
}

tasks.test {
    useJUnitPlatform()
    // VersionTest: Lyrelog.VERSION, the artifacts' version and the README agree.
    systemProperty("lyrelog.version", project.version.toString())
    systemProperty("lyrelog.repoRoot", rootDir.absolutePath)
    environment("LYRELOG_E2E_URL", System.getenv("LYRELOG_E2E_URL") ?: "")
    environment("LYRELOG_E2E_KEY", System.getenv("LYRELOG_E2E_KEY") ?: "")
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
