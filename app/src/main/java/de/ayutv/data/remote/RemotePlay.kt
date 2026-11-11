package de.ayutv.data.remote

import de.ayutv.data.db.HistoryKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** „Auf dem Fernseher abspielen“: was das Handy dem Fernseher schickt. */
data class RemotePlay(
    val live: Boolean,
    val url: String,
    val title: String,
    val key: String = "",
    val kind: HistoryKind = HistoryKind.VOD,
    val subtitle: String? = null,
    val image: String? = null,
    val positionMs: Long = 0,
    /** User-Agent, mit dem der Anbieter den Stream ausliefert (manche verlangen einen bestimmten). */
    val userAgent: String? = null,
) {
    fun encode(): String = JsonObject(
        buildMap {
            put("live", JsonPrimitive(live))
            put("url", JsonPrimitive(url))
            put("title", JsonPrimitive(title))
            put("key", JsonPrimitive(key))
            put("kind", JsonPrimitive(kind.name))
            subtitle?.let { put("subtitle", JsonPrimitive(it)) }
            image?.let { put("image", JsonPrimitive(it)) }
            put("position", JsonPrimitive(positionMs))
            userAgent?.let { put("agent", JsonPrimitive(it)) }
        }
    ).toString()

    companion object {
        fun parse(text: String): RemotePlay {
            val o = Json.parseToJsonElement(text).jsonObject
            fun str(key: String) = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            return RemotePlay(
                live = o["live"]?.jsonPrimitive?.booleanOrNull ?: false,
                url = str("url")?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("Keine Adresse"),
                title = str("title").orEmpty(),
                key = str("key").orEmpty(),
                kind = runCatching { HistoryKind.valueOf(str("kind").orEmpty()) }.getOrDefault(HistoryKind.VOD),
                subtitle = str("subtitle"),
                image = str("image"),
                positionMs = o["position"]?.jsonPrimitive?.longOrNull ?: 0L,
                userAgent = str("agent"),
            )
        }
    }
}
