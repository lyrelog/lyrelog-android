// Lyrelog's Logback appender: SLF4J and Logback records (Spring Boot's default) to Lyrelog.
// Logback is compileOnly: the application brings its own (1.3 or later).
// Maven coordinates: com.lyrelog:lyrelog-jvm-logback (not on a public repository yet;
// `publishToMavenLocal` puts it in ~/.m2).
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
        // As core and jvm: readable by a build on Kotlin 2.2, and asking for no newer stdlib.
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
        freeCompilerArgs.add("-Xjdk-release=11")
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
    manifest { attributes("Automatic-Module-Name" to "com.lyrelog.logback") }
}

dependencies {
    api(project(":jvm"))
    compileOnly("ch.qos.logback:logback-classic:1.6.8")
    testImplementation("ch.qos.logback:logback-classic:1.6.8")
    testImplementation(testFixtures(project(":core")))
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(kotlin("test-junit5"))
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "lyrelog-jvm-logback"
            from(components["java"])
            pom {
                name.set("Lyrelog Logback appender")
                description.set("Sends SLF4J and Logback records to Lyrelog.")
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

// ModulePathTest resolves the published jars (core, jvm, this one) as Java modules.
abstract class ModulePath : CommandLineArgumentProvider {
    @get:Classpath
    abstract val jars: ConfigurableFileCollection

    override fun asArguments() = listOf("-Dlyrelog.modulePath=" + jars.asPath)
}

tasks.test {
    useJUnitPlatform()
    jvmArgumentProviders.add(objects.newInstance<ModulePath>().apply { jars.from(tasks.jar, configurations.runtimeClasspath) })
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
