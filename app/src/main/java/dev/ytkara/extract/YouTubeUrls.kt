package dev.ytkara.extract

import java.net.URI

object YouTubeUrls {
    private val ID = Regex("^[A-Za-z0-9_-]{11}$")
    private val HOSTS = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com")

    /**
     * The canonical watch URL for a pasted YouTube link (watch, youtu.be, shorts, live, embed,
     * music), or null if it isn't one.
     */
    fun normalize(input: String): String? {
        val text = input.trim().let { if ("://" in it) it else "https://$it" }
        val uri = try {
            URI(text)
        } catch (_: Exception) {
            return null
        }
        val host = uri.host?.lowercase() ?: return null
        val segments = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
        val id = when {
            host == "youtu.be" || host == "www.youtu.be" -> segments.firstOrNull()
            host in HOSTS -> when (segments.firstOrNull()) {
                "watch" -> query(uri, "v")
                "shorts", "live", "embed", "v" -> segments.getOrNull(1)
                else -> null
            }
            else -> null
        }
        return id?.takeIf { ID.matches(it) }?.let { "https://www.youtube.com/watch?v=$it" }
    }

    private fun query(uri: URI, name: String): String? =
        uri.rawQuery?.split('&')?.firstNotNullOfOrNull { part ->
            val eq = part.indexOf('=')
            if (eq > 0 && part.substring(0, eq) == name) part.substring(eq + 1) else null
        }
}
