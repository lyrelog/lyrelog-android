package com.lyrelog

import org.junit.Assert.assertEquals
import org.junit.Test

class VersionTest {
    /** `resource.sdk` and the User-Agent name the version the artifact is published as. */
    @Test
    fun theSdkVersionIsTheArtifactsVersion() {
        val built = System.getProperty("lyrelog.version")
        assertEquals("1.0.0", Lyrelog.VERSION)
        assertEquals(built, Lyrelog.VERSION)
        assertEquals("lyrelog-android/${Lyrelog.VERSION}", DeviceContext.SDK)
    }
}
