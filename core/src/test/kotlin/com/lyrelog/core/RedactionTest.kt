package com.lyrelog.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** docs/sdk-spec.md rule 9: the same names, the same cases, as the Go and Swift tests. */
class RedactionTest {
    private val rule = Redaction.DEFAULT

    @Test
    fun secretNames() {
        val secret = listOf(
            "password", "PASSWORD", "X-Api-Key", "api_key", "apiKey", "csrf_token", "Set-Cookie", "cookie",
            "user_token", "Authorization", "proxy-authorization", "client_secret", "passwd", "Private Key",
            "aws.access_key", "Credentials", "credential", "x-signature", "refresh token",
        )
        val plain = listOf(
            "token_count", "max_tokens", "tokens", "author", "keyboard", "cookie_consent", "signatures",
            "secret_santa", "passwords_reset_at", "plan", "",
        )
        for (name in secret) assertTrue(rule.isSecret(name), name)
        for (name in plain) assertFalse(rule.isSecret(name), name)
    }

    @Test
    fun defaultKeysAreTheSharedList() {
        assertEquals(
            listOf("authorization", "cookie", "password", "passwd", "secret", "token", "apikey", "privatekey", "credential", "credentials", "accesskey", "signature"),
            Redaction.DEFAULT_KEYS,
        )
    }

    @Test
    fun attrsAndPayloadAtAnyDepthInsideArrays() {
        val attrs = Shaping.attributes(
            (JsonValue.from(mapOf("password" to "hunter2", "plan" to "pro", "token_count" to 12, "inner" to mapOf("client_secret" to "cs", "region" to "eu"))) as JsonValue.Obj).fields,
        )
        assertEquals(Redaction.MARKER, attrs["password"])
        assertEquals(JsonValue.Str("pro"), attrs["plan"])
        assertEquals(JsonValue.Integer(12), attrs["token_count"])
        assertEquals(Redaction.MARKER, attrs["inner"]["client_secret"])
        assertEquals(JsonValue.Str("eu"), attrs["inner"]["region"])

        val payload = Shaping.payload(JsonValue.from(mapOf("headers" to listOf(mapOf("Authorization" to "Bearer x")), "auth" to mapOf("token" to mapOf("a" to 1)))))!!
        val text = payload.encode()
        assertEquals("""{"headers":[{"Authorization":"[redacted]"}],"auth":{"token":"[redacted]"}}""", text)
    }

    @Test
    fun redactionRunsBeforeTruncationSoNoSecretSurvivesInAPreview() {
        val payload = Shaping.payload(JsonValue.from(mapOf("api_key" to "SECRET-VALUE", "filler" to "f".repeat(70_000))))!!
        assertEquals(JsonValue.Bool(true), payload["lyrelog_truncated"])
        assertFalse(payload.encode().contains("SECRET-VALUE"))
    }

    @Test
    fun onlyNamesAreChecked() {
        val attrs = Shaping.attributes((JsonValue.from(mapOf("note" to "password=hunter2")) as JsonValue.Obj).fields)
        assertEquals(JsonValue.Str("password=hunter2"), attrs["note"])
    }

    @Test
    fun aCustomListReplacesTheDefaultsAndEmptyTurnsItOff() {
        val custom = Redaction(listOf("pin"))
        assertTrue(custom.isSecret("card_pin"))
        assertFalse(custom.isSecret("password"))
        val off = Redaction(emptyList())
        assertFalse(off.isSecret("password"))
        val kept = Shaping.attributes(mapOf("password" to JsonValue.Str("x")), off)
        assertEquals(JsonValue.Str("x"), kept["password"])
        val added = Redaction(Redaction.DEFAULT_KEYS + "pin")
        assertTrue(added.isSecret("pin") && added.isSecret("password"))
    }
}
