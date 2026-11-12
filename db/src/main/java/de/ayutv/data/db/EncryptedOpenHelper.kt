package de.ayutv.data.db

import android.content.Context
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File

/**
 * Die ganze Datenbank verschlüsselt (SQLCipher, AES-256 je Seite, mit Prüfsumme): Senderlisten samt Adressen (die bei
 * vielen Anbietern Benutzer und Passwort enthalten), Verlauf, Favoriten, TV-Guide. Ohne den Schlüssel – der durch den
 * Android-Keystore geschützt ist – ist die Datei auch mit Root-Zugriff oder einer Kopie nicht lesbar.
 *
 * Eine vorhandene unverschlüsselte Datenbank (bis 0.61.16) wird beim ersten Öffnen einmal verschlüsselt umkopiert –
 * auf dem Thread, der sie zuerst öffnet (Room: im Hintergrund), nicht beim App-Start. Gelingt das nicht, bleibt sie
 * vorerst unverschlüsselt in Gebrauch (nichts geht verloren) und wird beim nächsten Start erneut versucht.
 *
 * [rawKey]: 32 Byte. [keyLost]: Der bisherige Schlüssel ließ sich nicht mehr entschlüsseln (Keystore zurückgesetzt);
 * eine damit verschlüsselte Datei ist unlesbar und wird beiseitegelegt (nicht gelöscht), statt bei jedem Start abzustürzen.
 */
class EncryptedOpenHelperFactory(
    private val context: Context,
    private val rawKey: ByteArray,
    private val keyLost: Boolean = false,
) : SupportSQLiteOpenHelper.Factory {
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        val name = configuration.name
        val key = keyLiteral(rawKey)
        val encrypted by lazy { SupportOpenHelperFactory(key).create(configuration) }
        val plain by lazy { SupportOpenHelperFactory(ByteArray(0)).create(configuration) }
        var chosen: SupportSQLiteOpenHelper? = null
        // Room stellt WAL schon beim Anlegen ein (auf dem Haupt-Thread): erst merken, beim ersten Öffnen anwenden – das
        // Umkopieren einer alten Datenbank darf den Start nicht aufhalten.
        var wal: Boolean? = null
        fun helper(): SupportSQLiteOpenHelper = synchronized(this) {
            chosen ?: run {
                val file = name?.let { context.getDatabasePath(it) }
                if (keyLost && file != null && file.exists() && !isPlain(file)) {
                    Log.w(TAG, "Schlüssel der Datenbank verloren – sie wird neu angelegt (alte Datei beiseitegelegt)")
                    listOf("", "-wal", "-shm").forEach { File(file.path + it).renameTo(File(file.path + ".unlesbar" + it)) }
                }
                val useEncrypted = when {
                    file == null -> true
                    !file.exists() -> true
                    !isPlain(file) -> true
                    else -> encryptExisting(file, key)
                }
                (if (useEncrypted) encrypted else plain).also { h ->
                    wal?.let { h.setWriteAheadLoggingEnabled(it) }
                    chosen = h
                }
            }
        }
        return object : SupportSQLiteOpenHelper {
            override val databaseName: String? get() = name
            override val writableDatabase: SupportSQLiteDatabase get() = helper().writableDatabase
            override val readableDatabase: SupportSQLiteDatabase get() = helper().readableDatabase
            override fun setWriteAheadLoggingEnabled(enabled: Boolean) {
                synchronized(this@EncryptedOpenHelperFactory) {
                    chosen?.setWriteAheadLoggingEnabled(enabled) ?: run { wal = enabled }
                }
            }
            override fun close() {
                synchronized(this@EncryptedOpenHelperFactory) { chosen?.close() }
            }
        }
    }

    /** Unverschlüsselte Datenbank verschlüsselt umkopieren; true, wenn das geklappt hat. */
    private fun encryptExisting(file: File, key: ByteArray): Boolean {
        val target = File(file.path + "-encrypting")
        return try {
            deleteFiles(target)
            val started = android.os.SystemClock.elapsedRealtime()
            val db = SQLiteDatabase.openDatabase(file.path, ByteArray(0), null, SQLiteDatabase.OPEN_READWRITE, null, null)
            try {
                db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
                val version = db.version
                db.rawExecSQL("ATTACH DATABASE '${target.path.replace("'", "''")}' AS enc KEY \"${String(key)}\"")
                db.rawQuery("SELECT sqlcipher_export('enc')").use { it.moveToFirst() }
                db.rawExecSQL("PRAGMA enc.user_version = $version")
                db.rawExecSQL("DETACH DATABASE enc")
            } finally {
                db.close()
            }
            // Erst wenn die verschlüsselte Kopie vollständig ist, die alte ersetzen.
            deleteFiles(file)
            if (!target.renameTo(file)) throw IllegalStateException("Umbenennen fehlgeschlagen")
            Log.i(TAG, "Datenbank verschlüsselt (${file.length() / 1024} KB, ${android.os.SystemClock.elapsedRealtime() - started} ms)")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Datenbank konnte nicht verschlüsselt werden – vorerst unverschlüsselt", e)
            deleteFiles(target)
            false
        }
    }

    companion object {
        private const val TAG = "AyuDatabase"
        private val HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

        init {
            System.loadLibrary("sqlcipher")
        }

        /** Rohschlüssel in der Form x'…' (64 Hex-Zeichen): SQLCipher nutzt ihn direkt, ohne langsame Ableitung je Verbindung. */
        fun keyLiteral(raw: ByteArray): ByteArray = ("x'" + raw.joinToString("") { "%02x".format(it) } + "'").toByteArray(Charsets.US_ASCII)

        /** Ist die Datei eine unverschlüsselte SQLite-Datenbank (Kopfzeile lesbar)? */
        fun isPlain(file: File): Boolean = runCatching {
            if (file.length() < HEADER.size) return@runCatching false
            val head = ByteArray(HEADER.size)
            file.inputStream().use { it.read(head) }
            head.contentEquals(HEADER)
        }.getOrDefault(false)

        private fun deleteFiles(file: File) {
            listOf("", "-wal", "-shm", "-journal").forEach { File(file.path + it).delete() }
        }
    }
}
