package com.lyrelog.core

/** For the Java tests: Kotlin's default arguments aren't callable from Java. */
object JavaSupport {
    @JvmStatic
    fun engine(ingest: FakeIngest): Engine = Engine(options(ingest.url), log = SdkLog.silent())
}
