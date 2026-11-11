package de.ayutv.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        PlaylistEntity::class,
        ChannelEntity::class,
        ProgrammeEntity::class,
        VodEntity::class,
        SeriesEntity::class,
        FavoriteEntity::class,
        HistoryEntity::class,
        HiddenGroupEntity::class,
        LockedGroupEntity::class,
        CustomGroupEntity::class,
        CustomGroupItemEntity::class,
        GroupOrderEntity::class,
    ],
    views = [VisibleChannelView::class, VisibleVodView::class, VisibleSeriesView::class],
    version = AyuDatabase.VERSION,
    exportSchema = true,
)
abstract class AyuDatabase : RoomDatabase() {
    abstract fun playlists(): PlaylistDao
    abstract fun channels(): ChannelDao
    abstract fun programmes(): ProgrammeDao
    abstract fun vod(): VodDao
    abstract fun series(): SeriesDao
    abstract fun favorites(): FavoriteDao
    abstract fun history(): HistoryDao
    abstract fun hiddenGroups(): HiddenGroupDao
    abstract fun lockedGroups(): LockedGroupDao
    abstract fun customGroups(): CustomGroupDao
    abstract fun groupOrder(): GroupOrderDao

    companion object {
        const val VERSION = 5

        /** 1 → 2: Playlists ein-/ausschaltbar. Views neu anlegen, damit sie ausgeschaltete Playlists ausblenden. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `playlists` ADD COLUMN `enabled` INTEGER NOT NULL DEFAULT 1")
                // Die Views verweisen inzwischen auf locked_groups (kommt mit Version 3): Tabelle schon hier anlegen.
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `locked_groups` (`playlistId` INTEGER NOT NULL, `kind` TEXT NOT NULL, " +
                        "`name` TEXT NOT NULL, PRIMARY KEY(`playlistId`, `kind`, `name`))"
                )
                recreateViews(db)
            }
        }

        /** 2 → 3: Archiv (Catch-up): Art und Vorlage je Sender, Server-Zeitzone je Playlist. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `channels` ADD COLUMN `catchupType` TEXT")
                db.execSQL("ALTER TABLE `channels` ADD COLUMN `catchupSource` TEXT")
                db.execSQL("ALTER TABLE `playlists` ADD COLUMN `timezone` TEXT")
                // Kindersicherung
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `locked_groups` (`playlistId` INTEGER NOT NULL, `kind` TEXT NOT NULL, " +
                        "`name` TEXT NOT NULL, PRIMARY KEY(`playlistId`, `kind`, `name`))"
                )
                // Die Views lesen "SELECT *": neu anlegen, damit sie die neuen Spalten sicher enthalten.
                recreateViews(db)
            }
        }

        /** 3 → 4: Untertitel der Sendungen (Paarung beim Fußball, Folgentitel bei Serien), Bild, Live/Wiederholung. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `programmes` ADD COLUMN `subtitle` TEXT")
                db.execSQL("ALTER TABLE `programmes` ADD COLUMN `icon` TEXT")
                db.execSQL("ALTER TABLE `programmes` ADD COLUMN `flags` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** 4 → 5: Eigene Gruppen, Reihenfolge in Favoriten, eigenen Gruppen und Gruppen des Anbieters. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `favorites` ADD COLUMN `position` INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `custom_groups` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, `position` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `custom_group_items` (`groupId` INTEGER NOT NULL, `key` TEXT NOT NULL, " +
                        "`position` INTEGER NOT NULL, PRIMARY KEY(`groupId`, `key`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_custom_group_items_key` ON `custom_group_items` (`key`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `group_order` (`scope` TEXT NOT NULL, `key` TEXT NOT NULL, " +
                        "`position` INTEGER NOT NULL, PRIMARY KEY(`scope`, `key`))"
                )
            }
        }

        private fun recreateViews(db: SupportSQLiteDatabase) {
            listOf(
                "visible_channels" to VISIBLE_CHANNELS_SQL,
                "visible_vod" to VISIBLE_VOD_SQL,
                "visible_series" to VISIBLE_SERIES_SQL,
            ).forEach { (name, sql) ->
                db.execSQL("DROP VIEW IF EXISTS `$name`")
                db.execSQL("CREATE VIEW `$name` AS $sql")
            }
        }

        fun build(context: Context): AyuDatabase =
            Room.databaseBuilder(context, AyuDatabase::class.java, "ayu.db")
                // WAL erzwingen: Die Oberfläche kann lesen, während im Hintergrund EPG geschrieben wird.
                // Fire TV Sticks melden sich oft als "low RAM", dann würde Room sonst TRUNCATE wählen.
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                // Nur für unbekannte Versionssprünge: Playlists sollen Updates sonst immer überstehen.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
