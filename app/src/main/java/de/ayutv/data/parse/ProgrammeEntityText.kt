package de.ayutv.data.parse

import de.ayutv.data.db.ProgrammeEntity

/** Titel für die Anzeige; bei Sport mit der Partie vorn (auch aus Beschreibung oder Spielplan). */
fun ProgrammeEntity.headline(): String = ProgrammeText.display(title, subtitle, description, category, start, stop)
fun ProgrammeEntity.isLive(): Boolean = ProgrammeText.isLive(title, subtitle, category, flags)
fun ProgrammeEntity.isSportEvent(sportChannel: Boolean = false): Boolean =
    (sportChannel || ProgrammeText.isSport(title, subtitle, category)) && ProgrammeText.isLive(title, subtitle, category, flags, sportChannel, description)
fun ProgrammeEntity.sportHeadline(): String = ProgrammeText.sportHeadline(title, subtitle, description)
fun ProgrammeEntity.pairing(): String? = ProgrammeText.pairing(title, subtitle, description)

/** Titel mit „LIVE“-Kennzeichen davor, wenn die Sendung live läuft. */
fun ProgrammeEntity.headlineWithBadge(): String = (if (isLive()) "🔴 LIVE  " else "") + headline()
