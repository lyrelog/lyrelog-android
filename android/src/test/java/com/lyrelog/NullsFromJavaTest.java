package com.lyrelog;

import org.junit.Test;

/** Java passes null where Kotlin says it can't (`Lyrelog.info(e.getMessage())`): never a throw into the app. */
public class NullsFromJavaTest {
    @Test
    public void nullsFromJavaNeverThrow() {
        String missing = null;
        Lyrelog.info(missing);
        Lyrelog.log(null, null);
        Lyrelog.captureError(null);
        Lyrelog.screen(null);
        Lyrelog.setConsent(null);
        Lyrelog.logger(null).info(null);
        Lyrelog.logger("http").captureError(null);
    }
}
