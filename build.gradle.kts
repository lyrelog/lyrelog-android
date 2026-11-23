// Versions are pinned here and in gradle/wrapper/gradle-wrapper.properties (Gradle 9.9.2).
// Both plugins sit on the root classpath (applied by the modules), so every module shares one
// copy of each, and the Android modules' built-in Kotlin (AGP 9) is the same Kotlin as `core`.
// Resolving the Android plugin needs no Android SDK; applying it does (settings.gradle.kts).
plugins {
    kotlin("jvm") version "2.4.21" apply false
    id("com.android.library") version "9.5.1" apply false
    id("com.android.application") version "9.5.1" apply false
}

// What Maven Central asks of every published artifact's POM; each module sets its own name and
// description. A release (.github/workflows/publish.yml) publishes every module to the local
// repository `centralBundle` (build/central-bundle), signed with the key in the Gradle
// properties `signingKey` / `signingPassword` (ORG_GRADLE_PROJECT_*), zips it and uploads the
// zip to the Central Portal. Without a key nothing is signed (publishToMavenLocal, tests).
subprojects {
    plugins.withId("maven-publish") {
        apply(plugin = "signing")
        extensions.configure<PublishingExtension> {
            repositories {
                maven {
                    name = "centralBundle"
                    url = uri(rootProject.layout.buildDirectory.dir("central-bundle"))
                }
            }
        }
        val signingKey = providers.gradleProperty("signingKey")
        if (signingKey.isPresent) {
            extensions.configure<SigningExtension> {
                useInMemoryPgpKeys(signingKey.get(), providers.gradleProperty("signingPassword").orNull)
                sign(extensions.getByType<PublishingExtension>().publications)
            }
        }
        extensions.configure<PublishingExtension> {
            publications.withType<MavenPublication>().configureEach {
                pom {
                    url.set("https://lyrelog.com")
                    licenses {
                        license {
                            name.set("MIT")
                            url.set("https://opensource.org/licenses/MIT")
                            distribution.set("repo")
                        }
                    }
                    developers {
                        developer {
                            id.set("lyrelog")
                            name.set("Lyrelog")
                            email.set("hello@lyrelog.com")
                            organization.set("Lyrelog")
                            organizationUrl.set("https://lyrelog.com")
                        }
                    }
                    scm {
                        url.set("https://github.com/lyrelog/lyrelog-android")
                        connection.set("scm:git:https://github.com/lyrelog/lyrelog-android.git")
                        developerConnection.set("scm:git:ssh://git@github.com/lyrelog/lyrelog-android.git")
                    }
                    issueManagement {
                        system.set("GitHub")
                        url.set("https://github.com/lyrelog/lyrelog-android/issues")
                    }
                }
            }
        }
    }
}
