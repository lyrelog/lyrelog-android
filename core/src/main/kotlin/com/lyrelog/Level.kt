package com.lyrelog

/** How serious an event is. The same seven levels in every Lyrelog SDK, in this order. */
enum class Level(val wireName: String) {
    TRACE("trace"),
    DEBUG("debug"),
    INFO("info"),
    NOTICE("notice"),
    WARNING("warning"),
    ERROR("error"),
    CRITICAL("critical"),
}

/**
 * Whether the person agreed to send diagnostics. [PENDING] keeps events on the device without
 * sending them, [GRANTED] sends them (and those kept while pending), [DENIED] deletes
 * everything queued and the install id, and records nothing until consent changes.
 */
enum class Consent {
    PENDING,
    GRANTED,
    DENIED,
}
