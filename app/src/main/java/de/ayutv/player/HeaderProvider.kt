package de.ayutv.player

/** User-Agent des laufenden Streams (je Sender verschieden, #EXTVLCOPT), ohne den Player neu zu bauen. */
class HeaderProvider(@Volatile var userAgent: String)
