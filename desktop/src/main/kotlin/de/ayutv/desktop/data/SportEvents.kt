package de.ayutv.desktop.data

import de.ayutv.data.parse.ChannelQuality
import de.ayutv.data.parse.ChannelVariants
import de.ayutv.data.parse.ProgrammeText

/** Sportsendung mit dem besten Sender und den anderen, auf denen sie auch läuft. */
data class SportEvent(val programme: Programme, val channel: Channel, val alsoOn: List<Channel> = emptyList()) {
    /** Überall gleich aufgebaut: die Paarung, sonst der Wettbewerb ausgeschrieben ohne „Live:“ (wie auf dem Fire TV). */
    val headline: String get() = ProgrammeText.eventTitle(programme.title, programme.subtitle, programme.description)
    val pairing: String? get() = ProgrammeText.pairing(programme.title, programme.subtitle, programme.description)

    /** Dasselbe Spiel auf weiteren Sendern: zusammenlegen, die Sendung mit Paarung im Titel vorn. */
    fun mergedWith(other: SportEvent): SportEvent {
        val (main, extra) = if (pairing == null && other.pairing != null) other to this else this to other
        return main.copy(alsoOn = (main.alsoOn + extra.channel + extra.alsoOn).distinctBy { it.key }.filter { it.key != main.channel.key })
    }
}

/** Sport aus dem TV-Guide, wie in der Fire-TV-App: gleiche Erkennung, gleiche Zusammenfassung über Sender. */
object SportEvents {
    private const val HOUR = 3_600_000L

    fun of(channels: List<Channel>, guide: Map<String, List<Programme>>, favorites: Set<String>, hours: Int = 36, now: Long = System.currentTimeMillis()): List<SportEvent> {
        val until = now + hours * HOUR
        fun variant(channel: Channel) = ChannelVariants.key(channel.name) ?: channel.key
        // Favorit entscheidet zwischen Sendern, die Qualität (4K vor Full HD) zwischen den Fassungen eines Senders.
        val favoriteVariants = channels.filter { it.key in favorites }.mapTo(HashSet()) { variant(it) }
        val best = compareBy<Pair<Programme, Channel>>({ if (variant(it.second) in favoriteVariants) 0 else 1 }, { ChannelQuality.sportScore(it.second.key, it.second.name) })
        return channels.asSequence()
            .flatMap { c ->
                val sportChannel = ProgrammeText.isSportChannel(c.name)
                guide[c.key].orEmpty().asSequence()
                    .filter { it.stop > now && it.start < until }
                    .filter { p ->
                        (sportChannel || ProgrammeText.isSport(p.title, p.subtitle, p.category)) &&
                            ProgrammeText.isLive(p.title, p.subtitle, p.category, p.flags, sportChannel, p.description)
                    }
                    .map { it to c }
            }
            .groupBy { (p, c) ->
                (p.start / (5 * 60_000L)) to
                    ProgrammeText.sportGroupKey(p.title, p.subtitle, p.description, c.epgId ?: c.key, ChannelVariants.key(c.name) ?: c.key)
            }
            .values.map { same ->
                val (p, c) = same.minWith(best)
                val others = same.filter { variant(it.second) != variant(c) }
                    .groupBy { variant(it.second) }.values
                    .map { it.minWith(best).second }
                    .sortedWith(compareBy({ if (it.key in favorites) 0 else 1 }, { it.name }))
                SportEvent(p, c, others)
            }
            .sortedWith(compareBy({ it.programme.start }, { it.channel.name }))
            // Dasselbe Spiel aus verschiedenen TV-Guides nur einmal; spätere Termine sind Wiederholungen.
            .let { list -> ProgrammeText.mergeSameMatch(list, { it.programme.start }, { it.pairing }) { a, b -> a.mergedWith(b) } }
    }

    /** Beste Fassung dieses Senders für Sport (4K vor Full HD) unter [all], auch wenn die Liste sonst Full HD zeigt. */
    fun bestFor(channel: Channel, all: List<Channel>): Channel {
        val key = ChannelVariants.key(channel.name) ?: return channel
        return (all.filter { it.playlistId == channel.playlistId && ChannelVariants.key(it.name) == key } + channel)
            .filter { ChannelQuality.passes(it.key, it.name) }.ifEmpty { listOf(channel) }
            .minBy { ChannelQuality.sportScore(it.key, it.name) }
    }
}
