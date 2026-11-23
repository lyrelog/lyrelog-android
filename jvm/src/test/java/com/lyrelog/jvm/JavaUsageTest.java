package com.lyrelog.jvm;

import com.lyrelog.Level;
import com.lyrelog.Logger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lyrelog.core.FakeIngest;
import com.lyrelog.core.Received;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The API as a Java service calls it: statics, overloads, the builder, try-with-resources. */
class JavaUsageTest {
    private final FakeIngest ingest = new FakeIngest();

    @AfterEach
    void tearDown() {
        Lyrelog.close();
        ingest.close();
    }

    @Test
    void javaCallsEveryPartOfTheApi() {
        List<String> diagnostics = new ArrayList<>();
        Configuration configuration = Configuration.builder("lyr_srv_java")
            .endpoint(ingest.getUrl())
            .environment("staging")
            .platform("api")
            .appId("orders")
            .release("1.4.0")
            .minimumLevel(Level.DEBUG)
            .flushIntervalMillis(3_600_000)
            .shutdownHook(false)
            .redactedKeys(new ArrayList<>(Configuration.DEFAULT_REDACTED_KEYS) {{ add("pin"); }})
            .diagnostics(diagnostics::add)
            .build();
        assertTrue(Lyrelog.start(configuration));
        assertFalse(Lyrelog.start(new Configuration("lyr_srv_other")));

        Lyrelog.info("checkout_done");
        Lyrelog.info("checkout_priced", "billing", Map.of("plan", "pro", "pin", "1234"));
        Lyrelog.error("payment_failed", "billing", Map.of("code", "card_declined"), Map.of("response", "declined"));
        Lyrelog.log(Level.NOTICE, "plan_changed");
        Lyrelog.setUser("service");
        try (Lyrelog.UserScope scope = Lyrelog.userScope("u_123")) {
            Logger http = Lyrelog.logger("http");
            http.info("http_request_served", Map.of("method", "GET", "route", "/orders/{id}", "status", 200));
            http.captureError(new IllegalStateException("db down"));
        }
        Lyrelog.captureError(new RuntimeException("x"), "jobs", Map.of("job", "sync"), Level.CRITICAL, "u_9");
        assertTrue(Lyrelog.flush());
        Lyrelog.flushInBackground();

        Received request = ingest.getReceived().get(0);
        assertEquals("Bearer lyr_srv_java", request.getHeaders().get("authorization"));
        List<String> lines = request.getLines();
        assertEquals(7, lines.size());
        String body = request.getBody();
        assertTrue(body.contains("\"environment\":\"staging\""));
        assertTrue(body.contains("\"platform\":\"api\""));
        assertTrue(body.contains("\"app_id\":\"orders\""));
        assertTrue(body.contains("\"app_version\":\"1.4.0\""));
        assertTrue(body.contains("\"pin\":\"[redacted]\""));
        assertTrue(lines.get(4).contains("\"user_id\":\"u_123\"") && lines.get(4).contains("\"category\":\"http\""));
        assertTrue(lines.get(5).contains("\"message\":\"exception\"") && lines.get(5).contains("\"user_id\":\"u_123\""));
        assertTrue(lines.get(6).contains("\"level\":\"critical\"") && lines.get(6).contains("\"user_id\":\"u_9\""));
        assertTrue(lines.get(3).contains("\"level\":\"notice\""));
        assertTrue(diagnostics.isEmpty(), diagnostics.toString());
    }

    /** Attributes without a category: before, Java had to pass `null` for it. */
    @Test
    void javaLogsAttributesWithoutACategory() {
        assertFalse(Lyrelog.isStarted());
        assertTrue(Lyrelog.start(Configuration.builder("lyr_srv_java").endpoint(ingest.getUrl()).flushIntervalMillis(3_600_000).shutdownHook(false).build()));
        assertTrue(Lyrelog.isStarted());
        Map<String, Object> plan = Map.of("plan", "pro");
        Lyrelog.trace("t", plan);
        Lyrelog.debug("d", plan);
        Lyrelog.info("i", plan);
        Lyrelog.notice("n", plan);
        Lyrelog.warning("w", plan);
        Lyrelog.error("e", plan);
        Lyrelog.critical("c", plan);
        Lyrelog.log(Level.INFO, "l", plan);
        Lyrelog.captureError(new IllegalStateException("x"), Map.of("order_id", "o_1"));
        assertTrue(Lyrelog.flush());
        List<String> lines = ingest.getReceived().get(0).getLines();
        assertEquals(8, lines.size(), "trace is below the default minimum level");
        for (int i = 0; i < 7; i++) {
            assertTrue(lines.get(i).contains("\"plan\":\"pro\""), lines.get(i));
            assertFalse(lines.get(i).contains("\"category\""), lines.get(i));
        }
        assertTrue(lines.get(7).contains("\"message\":\"exception\"") && lines.get(7).contains("\"order_id\":\"o_1\""));
        Lyrelog.close();
        assertFalse(Lyrelog.isStarted());
    }

    /** Java can pass null where Kotlin says it can't: a log call or a start must still never throw. */
    @Test
    void nullsFromJavaNeverThrow() {
        assertFalse(Lyrelog.start(null));
        Configuration configuration = Configuration.builder("lyr_srv_java")
            .endpoint(null) // an unset environment variable: the default endpoint
            .minimumLevel(null)
            .shutdownHook(false)
            .flushIntervalMillis(3_600_000)
            .build();
        assertEquals(Configuration.DEFAULT_ENDPOINT, configuration.getEndpoint());
        assertEquals(Level.DEBUG, configuration.getMinimumLevel());
        assertTrue(Lyrelog.start(Configuration.builder("lyr_srv_java").endpoint(ingest.getUrl()).shutdownHook(false).build()));
        String missing = null;
        Lyrelog.info(missing);                      // e.getMessage() is often null
        Lyrelog.warning(missing, missing, null, null, missing);
        Lyrelog.log(null, "no_level");
        Lyrelog.captureError(null);
        Lyrelog.captureError(new RuntimeException("x"), null, null, null, null);
        Lyrelog.logger(null).info("no_category");
        assertTrue(Lyrelog.flush());
        List<String> lines = ingest.getReceived().get(0).getLines();
        assertEquals(5, lines.size(), lines.toString());
        assertTrue(lines.get(0).contains("\"message\":\"(empty)\""), lines.get(0));
        assertTrue(lines.get(2).contains("\"level\":\"info\""), lines.get(2));
        assertTrue(lines.get(3).contains("\"level\":\"error\"") && lines.get(3).contains("\"message\":\"exception\""), lines.get(3));
        assertFalse(lines.get(4).contains("\"category\""), lines.get(4));
    }
}
