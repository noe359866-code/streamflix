package com.streamflixreborn.streamflix.utils

/** Offline, network-free parser shared by the IPTV providers. */
object M3uParser {
    data class Channel(
        val name: String,
        val url: String,
        val logo: String = "",
        val group: String = "",
        val userAgent: String? = null,
        val referrer: String? = null,
    )

    fun parse(input: String): List<Channel> {
        val result = mutableListOf<Channel>()
        var pending: Channel? = null
        for (raw in input.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF:") -> {
                    fun attr(name: String) = Regex("""$name="([^"]*)"""").find(line)?.groupValues?.get(1)
                    pending = Channel(
                        // Only the first comma separates duration/attributes from the title.
                        name = line.substringAfter(',', "").trim(),
                        url = "",
                        logo = attr("tvg-logo").orEmpty(),
                        group = attr("group-title").orEmpty(),
                        userAgent = attr("http-user-agent"),
                        referrer = attr("http-referrer"),
                    )
                }
                line.startsWith("#EXTVLCOPT:") && pending != null -> {
                    val option = line.removePrefix("#EXTVLCOPT:")
                    when {
                        option.startsWith("http-user-agent=") ->
                            pending = pending.copy(userAgent = option.substringAfter('=').trim())
                        option.startsWith("http-referrer=") ->
                            pending = pending.copy(referrer = option.substringAfter('=').trim())
                    }
                }
                line.startsWith("https://") || line.startsWith("http://") -> {
                    pending?.takeIf { it.name.isNotEmpty() }?.let { result += it.copy(url = line) }
                    pending = null
                }
                line.startsWith("#EXTM3U") -> pending = null
            }
        }
        return result
    }
}
