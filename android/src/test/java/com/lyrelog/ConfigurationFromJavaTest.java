package com.lyrelog;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** The builder is how Java configures Lyrelog; a null for an option with a default is that default. */
public class ConfigurationFromJavaTest {
    @Test
    public void javaBuildsAConfiguration() {
        Configuration c = Configuration.builder("lyr_ing_test")
            .consent(Consent.PENDING)
            .environment("beta")
            .flushIntervalMillis(30_000)
            .attest(false)
            .build();
        assertEquals(Consent.PENDING, c.getConsent());
        assertEquals("beta", c.getEnvironment());
        assertEquals(30_000L, c.getFlushIntervalMillis());
        assertEquals(false, c.getAttest());

        Configuration again = c.toBuilder().consent(null).endpoint(null).minimumLevel(null).build();
        assertEquals(Consent.GRANTED, again.getConsent());
        assertEquals(Configuration.DEFAULT_ENDPOINT, again.getEndpoint());
        assertEquals(Level.DEBUG, again.getMinimumLevel());
        assertEquals("beta", again.getEnvironment());

        assertEquals(Configuration.DEFAULT_ENDPOINT, new Configuration("lyr_ing_test").getEndpoint());
    }

    @Test
    public void startSaysWhetherItStarted() {
        boolean started = Lyrelog.start(null, new Configuration("lyr_ing_test"));
        assertEquals(false, started);
        assertEquals(false, Lyrelog.isStarted());
    }
}
