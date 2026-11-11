package de.ayutv.data.parse

import java.io.BufferedReader

data class M3uHeader(val epgUrls: List<String>)

data class M3uEntry(
    val name: String,
    val url: String,
    val attributes: Map<String, String>,
    val group: String?,
    val userAgent: String?,
) {
    val tvgId: String? get() = attributes["tvg-id"]?.takeIf { it.isNotBlank() }
    val logo: String? get() = attributes["tvg-logo"]?.takeIf { it.isNotBlank() }
    val groupTitle: String get() = attributes["group-title"]?.takeIf { it.isNotBlank() } ?: group ?: ""
    val number: Int? get() = (attributes["tvg-chno"] ?: attributes["channel-number"])?.trim()?.toIntOrNull()
    val catchupDays: Int get() = (attributes["catchup-days"] ?: attributes["timeshift"] ?: attributes["tvg-rec"])?.trim()?.toIntOrNull() ?: 0
    val catchupType: String? get() = (attributes["catchup"] ?: attributes["catchup-type"])?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    val catchupSource: String? get() = attributes["catchup-source"]?.trim()?.takeIf { it.isNotEmpty() }

    /** Filme und Serien-Episoden erkennt man bei den meisten Anbietern am Pfad oder an der Dateiendung. */
    val isVod: Boolean
        get() {
            val path = url.substringBefore('?').lowercase()
            return "/movie/" in path || "/series/" in path || VOD_EXTENSIONS.any { path.endsWith(it) }
        }

    private companion object {
        val VOD_EXTENSIONS = listOf(".mp4", ".mkv", ".avi", ".mov", ".m4v", ".wmv", ".flv")
    }
}

/**
 * Liest M3U zeilenweise und gibt jeden Eintrag sofort weiter, statt die ganze Liste
 * im Speicher zu halten. Große Playlists mit 100k Einträgen bleiben so auf dem Fire TV Stick handhabbar.
 */
object M3uParser {
    // Werte in doppelten, einfachen oder ganz ohne Anführungszeichen (tvg-id=ard.de) – alles kommt in Playlists vor.
    private val attributeRegex = Regex("""([A-Za-z0-9_-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s",']+))""")

    private fun MatchResult.attributeValue(): String = groups[2]?.value ?: groups[3]?.value ?: groups[4]?.value.orEmpty()

    fun parse(reader: BufferedReader, onEntry: (M3uEntry) -> Unit): M3uHeader {
        val epgUrls = mutableListOf<String>()
        var pendingInfo: String? = null
        var pendingGroup: String? = null
        var pendingUserAgent: String? = null

        while (true) {
            val raw = reader.readLine() ?: break
            val line = raw.trim().removePrefix("﻿")
            if (line.isEmpty()) continue

            when {
                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    for (match in attributeRegex.findAll(line)) {
                        val key = match.groupValues[1].lowercase()
                        if (key == "url-tvg" || key == "x-tvg-url" || key == "tvg-url") {
                            match.attributeValue().split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach(epgUrls::add)
                        }
                    }
                }
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    pendingInfo = line
                    pendingGroup = null
                    pendingUserAgent = null
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> pendingGroup = line.substring(8).trim()
                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val option = line.substring(11).trim()
                    if (option.startsWith("http-user-agent=", ignoreCase = true)) {
                        pendingUserAgent = option.substringAfter('=').trim()
                    }
                }
                line.startsWith("#") -> Unit
                else -> {
                    val info = pendingInfo ?: continue
                    onEntry(parseEntry(info, line, pendingGroup, pendingUserAgent))
                    pendingInfo = null
                    pendingGroup = null
                    pendingUserAgent = null
                }
            }
        }
        return M3uHeader(epgUrls.distinct())
    }

    private fun parseEntry(info: String, url: String, group: String?, userAgent: String?): M3uEntry {
        val body = info.substringAfter(':', "")
        val commaIndex = commaOutsideQuotes(body)
        val attributePart = if (commaIndex >= 0) body.substring(0, commaIndex) else body
        val name = if (commaIndex >= 0) body.substring(commaIndex + 1).trim() else ""

        val attributes = HashMap<String, String>()
        for (match in attributeRegex.findAll(attributePart)) {
            attributes[match.groupValues[1].lowercase()] = match.attributeValue().trim()
        }
        val displayName = name.ifEmpty { attributes["tvg-name"].orEmpty() }.ifEmpty { url.substringAfterLast('/') }
        return M3uEntry(displayName, url, attributes, group, userAgent)
    }

    private fun commaOutsideQuotes(text: String): Int {
        var inQuotes = false
        for (i in text.indices) {
            when (text[i]) {
                '"' -> inQuotes = !inQuotes
                ',' -> if (!inQuotes) return i
            }
        }
        return -1
    }
}
