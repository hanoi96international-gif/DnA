package de.ayutv.ui.live

/**
 * Ausgewählte Sender ([keys]) als Block an die Stelle des ersten von ihnen ziehen (Reihenfolge untereinander bleibt),
 * damit sie danach gemeinsam verschoben werden können.
 */
internal fun <T> gatherBlock(list: List<T>, keys: Set<String>, keyOf: (T) -> String): List<T> {
    val first = list.indexOfFirst { keyOf(it) in keys }
    if (first < 0) return list
    val block = list.filter { keyOf(it) in keys }
    val rest = list.filterNot { keyOf(it) in keys }
    val at = list.subList(0, first).count { keyOf(it) !in keys }
    return rest.subList(0, at) + block + rest.subList(at, rest.size)
}

/** Block [keys] (zusammenhängend) um eine Stelle nach oben ([up]) oder unten verschieben; unverändert am Rand. */
internal fun <T> moveBlock(list: List<T>, keys: Set<String>, up: Boolean, keyOf: (T) -> String): List<T> {
    val start = list.indexOfFirst { keyOf(it) in keys }
    val end = list.indexOfLast { keyOf(it) in keys }
    if (start < 0) return list
    val out = list.toMutableList()
    if (up) {
        if (start == 0) return list
        out.add(end, out.removeAt(start - 1))
    } else {
        if (end == list.lastIndex) return list
        out.add(start, out.removeAt(end + 1))
    }
    return out
}
