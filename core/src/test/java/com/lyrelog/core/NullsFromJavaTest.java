package com.lyrelog.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lyrelog.Level;
import com.lyrelog.Logger;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Java passes null where Kotlin says it can't (`logger.info(e.getMessage())`). Logging never
 * throws: a null message is "(empty)", a null level is info, a null error records nothing.
 */
class NullsFromJavaTest {
    @Test
    void nullsFromJavaNeverThrowIntoTheCaller() {
        try (FakeIngest ingest = new FakeIngest()) {
            Engine engine = JavaSupport.engine(ingest);
            try {
                Logger logger = new Logger("http", () -> engine);
                String missing = null;
                logger.info(missing);
                logger.log(null, "no_level");
                logger.captureError(null);
                logger.captureError(new IllegalStateException("x"), null, null);
                engine.log(null, null, null, null, null, null, null);
                engine.captureError(null, null, null, null, null);
                engine.screen(null, null);
                engine.setConsent(null);
                engine.setUser(null);
                assertTrue(engine.flush(5_000));
                List<String> messages = ingest.getAllEvents().stream()
                    .map(e -> ((JsonValue.Str) ((JsonValue.Obj) e).getFields().get("message")).getValue())
                    .collect(Collectors.toList());
                assertEquals(List.of("(empty)", "no_level", "exception", "(empty)"), messages);
                ingest.getAllEvents().forEach(e -> ServerRules.INSTANCE.check(e.encode()));
            } finally {
                engine.stopNow();
            }
        }
    }
}
