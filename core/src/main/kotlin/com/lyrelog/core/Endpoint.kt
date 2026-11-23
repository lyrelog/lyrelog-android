package com.lyrelog.core

import java.net.URI
import java.net.URL

internal object Endpoint {
    const val DEFAULT = "https://ingest.lyrelog.com"

    private val loopback = setOf("localhost", "127.0.0.1", "::1")

    /**
     * The origin requests go to (`<origin>/v1/events:batch`), or null for an endpoint that must
     * not be used. A pasted `.../v1/events:batch` or `.../v1` is cut back; the query and fragment
     * are dropped. `https` always; plain `http` only to this machine (`localhost`, `127.0.0.1`,
     * `::1`) and to [extraPlainHosts] (the Android emulator's `10.0.2.2`, only on an emulator).
     */
    fun origin(raw: String, extraPlainHosts: Set<String> = emptySet()): URL? {
        val uri = try {
            URI(raw.trim())
        } catch (e: Exception) {
            return null
        }
        val scheme = uri.scheme?.lowercase(java.util.Locale.ROOT) ?: return null
        val host = (uri.host ?: return null).lowercase(java.util.Locale.ROOT).trim('[', ']')
        when (scheme) {
            "https" -> {}
            "http" -> if (host !in loopback && host !in extraPlainHosts) return null
            else -> return null
        }
        var path = (uri.rawPath ?: "").trimEnd('/')
        for (suffix in listOf("/v1/events:batch", "/v1")) {
            if (path.endsWith(suffix)) {
                path = path.dropLast(suffix.length)
                break
            }
        }
        path = path.trimEnd('/')
        return try {
            URI(scheme, uri.rawUserInfo, uri.host, uri.port, null, null, null).toURL().let { base ->
                URL(base.toString().trimEnd('/') + path)
            }
        } catch (e: Exception) {
            null
        }
    }
}
