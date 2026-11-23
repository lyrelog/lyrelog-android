package com.lyrelog.core

/**
 * Marks what `lyrelog-core` shares with Lyrelog's own modules (the Android library, the JVM SDK,
 * the Logback appender) and their React Native and Flutter bridges: the engine, the queue, the
 * uploader, the JSON model. **Not API**: it changes in any release without notice. Apps use
 * `com.lyrelog.Lyrelog` (Android) or `com.lyrelog.jvm.Lyrelog` (servers), `Level`, `Consent` and
 * `Logger`. Kotlin refuses a use without `@OptIn(InternalLyrelogApi::class)`; Java doesn't check.
 */
@RequiresOptIn(
    message = "Lyrelog's internal engine, not API: use com.lyrelog.Lyrelog instead.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.TYPEALIAS,
)
annotation class InternalLyrelogApi
