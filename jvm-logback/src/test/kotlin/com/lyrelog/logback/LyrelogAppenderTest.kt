package com.lyrelog.logback

import ch.qos.logback.classic.Level as LogbackLevel
import ch.qos.logback.classic.LoggerContext
import com.lyrelog.jvm.Configuration
import com.lyrelog.Level
import com.lyrelog.jvm.Lyrelog
import com.lyrelog.core.FakeIngest
import com.lyrelog.core.ServerRules
import com.lyrelog.core.get
import com.lyrelog.core.string
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.slf4j.LoggerFactory
import org.slf4j.MDC

class LyrelogAppenderTest {
    private val ingest = FakeIngest()
    private val context = LoggerFactory.getILoggerFactory() as LoggerContext
    private val appenders = ArrayList<LyrelogAppender>()

    @AfterTest
    fun tearDown() {
        for (appender in appenders) {
            context.loggerList.forEach { it.detachAppender(appender) }
            appender.stop()
        }
        MDC.clear()
        Lyrelog.close(1_000)
        ingest.close()
    }

    private fun appender(configure: LyrelogAppender.() -> Unit = {}): LyrelogAppender =
        LyrelogAppender().apply {
            this.context = this@LyrelogAppenderTest.context
            configure()
            start()
            appenders.add(this)
        }

    private fun logger(name: String, appender: LyrelogAppender) = context.getLogger(name).apply {
        level = LogbackLevel.TRACE
        isAdditive = false
        addAppender(appender)
    }

    private fun startLyrelog() =
        assertTrue(Lyrelog.start(Configuration("lyr_srv_test") { endpoint = ingest.url; flushIntervalMillis = 3_600_000; shutdownHook = false }))

    @Test
    fun recordsBecomeEventsWithTheirContext() {
        startLyrelog()
        val appender = appender()
        val log = org.slf4j.LoggerFactory.getLogger("com.example.billing.Invoices")
        logger("com.example.billing.Invoices", appender)
        val before = System.currentTimeMillis()
        MDC.put("request_id", "r_1")
        MDC.put("user_id", "u_42")
        log.info("Order {} shipped", 1042)
        MDC.clear()
        log.atWarn().addKeyValue("attempt", 2).addKeyValue("category", "billing").addKeyValue("password", "x").log("invoice_retry")
        log.error("charge_failed", IllegalStateException("card declined"))
        log.debug("debugging")
        log.trace("tracing") // below Lyrelog's default minimum level, debug
        assertTrue(Lyrelog.flush())
        val events = ingest.allEvents
        events.forEach { ServerRules.check(it.encode()) }
        assertEquals(listOf("Order 1042 shipped", "invoice_retry", "charge_failed", "debugging"), events.map { it["message"].string })
        assertEquals(listOf("info", "warning", "error", "debug"), events.map { it["level"].string })

        val shipped = events[0]
        assertEquals("com.example.billing.Invoices", shipped["category"].string)
        assertEquals("Order {} shipped", shipped["attrs"]["msg_template"].string)
        assertEquals("r_1", shipped["attrs"]["request_id"].string)
        assertEquals("u_42", shipped["user_id"].string)
        assertNull(shipped["attrs"]["user_id"])
        assertTrue(shipped["ts"].string!! >= java.time.Instant.ofEpochMilli(before).toString().take(19))

        val retry = events[1]
        assertEquals("billing", retry["category"].string)
        assertEquals(com.lyrelog.core.JsonValue.Integer(2), retry["attrs"]["attempt"])
        assertEquals("[redacted]", retry["attrs"]["password"].string)
        assertNull(retry["attrs"]["msg_template"])
        assertNull(retry["user_id"])

        val failed = events[2]
        assertEquals("java.lang.IllegalStateException", failed["attrs"]["error_type"].string)
        assertEquals("card declined", failed["attrs"]["error_message"].string)
        assertTrue(failed["payload"]["exception"]["frames"] != null)
    }

    @Test
    fun aFixedCategoryAndNoMdc() {
        startLyrelog()
        val appender = appender {
            category = "app"
            isIncludeMdc = false
        }
        val log = logger("com.example.Thing", appender)
        MDC.put("request_id", "r_1")
        log.info("hello")
        assertTrue(Lyrelog.flush())
        val event = ingest.allEvents.single()
        assertEquals("app", event["category"].string)
        assertNull(event["attrs"])
    }

    @Test
    fun theSdksOwnLoggersAndThreadsAreIgnoredAndNothingIsSentBeforeStart() {
        val appender = appender()
        logger("com.example.Early", appender).info("before_start")
        startLyrelog()
        logger("com.lyrelog.Something", appender).info("own")
        val thread = Thread({ logger("com.example.Worker", appender).info("from_sdk_thread") }, "lyrelog-test")
        thread.start()
        thread.join()
        logger("com.example.Worker", appender).info("kept")
        assertTrue(Lyrelog.flush())
        assertEquals(listOf("kept"), ingest.allEvents.map { it["message"].string })
    }

    @Test
    fun withAnApiKeyTheAppenderStartsAndClosesLyrelog() {
        assertFalse(Lyrelog.isStarted)
        val appender = appender {
            apiKey = "lyr_srv_appender"
            endpoint = ingest.url
            environment = "staging"
            platform = "api"
        }
        assertTrue(Lyrelog.isStarted)
        logger("com.example.Api", appender).info("served")
        appender.stop()
        assertFalse(Lyrelog.isStarted, "stopping Logback closes what the appender started")
        val event = ingest.allEvents.single()
        assertEquals("Bearer lyr_srv_appender", ingest.received.single().headers["authorization"])
        assertEquals("staging", event["resource"]["environment"].string)
        assertEquals("api", event["resource"]["platform"].string)
    }

    @Test
    fun aLogbackXmlConfiguresIt() {
        val xml = """
            <configuration>
              <appender name="LYRELOG" class="com.lyrelog.logback.LyrelogAppender">
                <apiKey>lyr_srv_xml</apiKey>
                <endpoint>${ingest.url}</endpoint>
                <platform>worker</platform>
                <category>jobs</category>
                <includeMdc>false</includeMdc>
              </appender>
              <logger name="com.example.xml" level="INFO" additivity="false">
                <appender-ref ref="LYRELOG" />
              </logger>
            </configuration>
        """.trimIndent()
        ch.qos.logback.classic.joran.JoranConfigurator().apply {
            context = this@LyrelogAppenderTest.context
            doConfigure(xml.byteInputStream())
        }
        try {
            assertTrue(Lyrelog.isStarted)
            MDC.put("request_id", "r_1")
            val log = LoggerFactory.getLogger("com.example.xml.Digest")
            log.info("digest_sent")
            log.debug("below_the_logger_level")
            assertTrue(Lyrelog.flush())
            val event = ingest.allEvents.single()
            assertEquals("digest_sent", event["message"].string)
            assertEquals("jobs", event["category"].string)
            assertEquals("worker", event["resource"]["platform"].string)
            assertNull(event["attrs"])
            assertEquals("Bearer lyr_srv_xml", ingest.received.single().headers["authorization"])
        } finally {
            context.reset() // stops the appender, which closes what it started
        }
        assertFalse(Lyrelog.isStarted)
    }

    @Test
    fun levelsMapToTheClosestLyrelogLevel() {
        assertEquals(Level.TRACE, LyrelogAppender.level(LogbackLevel.TRACE))
        assertEquals(Level.DEBUG, LyrelogAppender.level(LogbackLevel.DEBUG))
        assertEquals(Level.INFO, LyrelogAppender.level(LogbackLevel.INFO))
        assertEquals(Level.WARNING, LyrelogAppender.level(LogbackLevel.WARN))
        assertEquals(Level.ERROR, LyrelogAppender.level(LogbackLevel.ERROR))
    }

    @Test
    fun behindAnAsyncAppenderTheSdksThreadsAreStillIgnored() {
        // An AsyncAppender appends on its own worker thread: the record's thread is what counts,
        // or the upload thread's own records (the JDK's HTTP client through jul-to-slf4j at
        // debug) would be sent, logged again by the next upload, and so on forever.
        startLyrelog()
        val async = ch.qos.logback.classic.AsyncAppender().apply {
            context = this@LyrelogAppenderTest.context
            addAppender(appender())
            start()
        }
        val log = context.getLogger("sun.net.www.protocol.http.HttpURLConnection").apply {
            level = LogbackLevel.TRACE
            isAdditive = false
            addAppender(async)
        }
        try {
            val thread = Thread({ log.debug("sun.net.www.MessageHeader@1 pairs") }, "lyrelog-upload")
            thread.start()
            thread.join()
            log.info("from_the_app")
        } finally {
            async.stop() // drains the queue first
            log.detachAppender(async)
        }
        assertTrue(Lyrelog.flush())
        assertEquals(listOf("from_the_app"), ingest.allEvents.map { it["message"].string })
    }

    @Test
    fun anUndefinedKeyVariableStartsNothing() {
        // Logback writes an unset ${LYRELOG_KEY} as "LYRELOG_KEY_IS_UNDEFINED": on a laptop or
        // in CI that must start nothing, as a missing key does in code.
        val xml = """
            <configuration>
              <appender name="LYRELOG" class="com.lyrelog.logback.LyrelogAppender">
                <apiKey>${'$'}{LYRELOG_KEY_NOT_SET_IN_ANY_TEST}</apiKey>
                <endpoint>${ingest.url}</endpoint>
              </appender>
              <root level="INFO"><appender-ref ref="LYRELOG" /></root>
            </configuration>
        """.trimIndent()
        ch.qos.logback.classic.joran.JoranConfigurator().apply {
            context = this@LyrelogAppenderTest.context
            doConfigure(xml.byteInputStream())
        }
        try {
            assertFalse(Lyrelog.isStarted)
        } finally {
            context.reset()
        }
    }

    @Test
    fun readableByAServerOnKotlin22() {
        // As lyrelog-core and lyrelog-jvm: a Kotlin 2.2 build must read this jar's metadata,
        // and the stdlib it asks for (the POM's) must not be newer than 2.2.
        val version = LyrelogAppender::class.java.getAnnotation(Metadata::class.java).metadataVersion
        assertEquals(listOf(2, 2), version.take(2).toList(), "metadata ${version.joinToString(".")}")
        assertEquals(listOf(2, 2), Lyrelog::class.java.getAnnotation(Metadata::class.java).metadataVersion.take(2).toList())
    }
}
