package com.example.tilefall

/** Tilefall's native board (src/main/jni), when the build has it (lyrelog.native=true). */
object NativeBoard {
    val loaded: Boolean = try {
        System.loadLibrary("tilefall")
        true
    } catch (e: UnsatisfiedLinkError) {
        false
    }

    /** Crashes in C++: reads a tile through a null pointer. */
    @JvmStatic external fun drop(): Int
}
