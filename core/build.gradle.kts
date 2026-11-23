// Everything that doesn't need Android: the event model, redaction, the disk queue, batching,
// the uploader. No runtime dependency beyond the Kotlin standard library.
plugins {
    kotlin("jvm")
    `maven-publish`
    `java-test-fixtures`
}

kotlin {
    jvmToolchain(21)
    coreLibrariesVersion = "2.2.0"
    compilerOptions {
        // Android apps consume this jar: Java 11 bytecode, and the JDK 11 API as a first guard
        // (Android 24 has less still: no java.time, java.nio.file; see CLAUDE.md).
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        // Readable by apps on an older Kotlin (React Native 0.88 and Flutter use Kotlin 2.2):
        // the metadata, the language and the stdlib this needs are 2.2's.
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
        freeCompilerArgs.add("-Xjdk-release=11")
        // Java passes null where Kotlin says it can't (`logger.info(e.getMessage())`): the entry
        // points check for it themselves instead of Kotlin's checks throwing into the caller.
        freeCompilerArgs.add("-Xno-param-assertions")
        // The engine is marked @InternalLyrelogApi (not API for apps); this module uses it freely.
        freeCompilerArgs.add("-opt-in=com.lyrelog.core.InternalLyrelogApi")
        allWarningsAsErrors.set(true)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
    withSourcesJar()
    // Maven Central wants one; the sources are Kotlin, so it holds no pages (the sources jar has the docs).
    withJavadocJar()
}

// The Java module path: a stable module name (no module-info, the jar also runs on Android 24).
tasks.jar {
    manifest { attributes("Automatic-Module-Name" to "com.lyrelog.core") }
}

// com.lyrelog:lyrelog-core. Not published to a public repository yet; `publishToMavenLocal`
// puts it in ~/.m2 for the SDKs built on it (React Native, Flutter) to try locally.
publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "lyrelog-core"
            from(components["java"])
            pom {
                name.set("Lyrelog core")
                description.set("The engine shared by Lyrelog's Android and JVM SDKs.")
            }
        }
    }
}

dependencies {
    // The test helpers (a fake ingest, the server's line rules), shared with `jvm`'s tests.
    testFixturesApi(kotlin("test"))
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(kotlin("test-junit5"))
}

// The test fixtures are never published.
val javaComponent = components["java"] as AdhocComponentWithVariants
javaComponent.withVariantsFromConfiguration(configurations["testFixturesApiElements"]) { skip() }
javaComponent.withVariantsFromConfiguration(configurations["testFixturesRuntimeElements"]) { skip() }

// The tests run on the build's JDK (JUnit 6 needs 17+); only the library is held to Java 11.
for (name in listOf("testCompileClasspath", "testRuntimeClasspath", "testFixturesCompileClasspath", "testFixturesRuntimeClasspath")) {
    configurations.named(name) {
        attributes.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21)
    }
}
for (name in listOf("compileTestKotlin", "compileTestFixturesKotlin")) {
    tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>(name) {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
            freeCompilerArgs.set(listOf("-opt-in=com.lyrelog.core.InternalLyrelogApi"))
        }
    }
}
for (name in listOf("compileTestJava", "compileTestFixturesJava")) {
    tasks.named<JavaCompile>(name) {
        options.release.set(21)
    }
}

tasks.test {
    useJUnitPlatform()
    // PublicFilesTest reads the repository's files.
    systemProperty("lyrelog.repoRoot", rootDir.absolutePath)
    // The end-to-end test reads these; without them it is skipped.
    environment("LYRELOG_E2E_URL", System.getenv("LYRELOG_E2E_URL") ?: "")
    environment("LYRELOG_E2E_KEY", System.getenv("LYRELOG_E2E_KEY") ?: "")
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
