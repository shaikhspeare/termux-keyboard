// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.terminal

import android.content.Context
import android.net.Uri
import helium314.keyboard.latin.utils.Log
import java.io.File

/**
 * Owns the imported history: parses it once, keeps an immutable index in memory and persists only
 * the already-parsed, already-redacted entries (never the raw file, which may contain secrets the
 * redaction pass dropped).
 */
object HistoryStore {
    private const val TAG = "HistoryStore"
    private const val FILE_NAME = "terminal-history.tsv"
    private const val MAX_IMPORT_BYTES = 8 * 1024 * 1024

    @Volatile
    private var index = HistoryIndex(emptyList())

    private var file: File? = null

    fun init(context: Context) {
        file = File(context.filesDir, FILE_NAME)
        reload()
    }

    fun suggest(line: String, limit: Int): List<HistoryEntry> =
        index.suggest(line, limit, System.currentTimeMillis() / 1000)

    /** Imports a history file, merging with whatever was already imported. */
    fun importFrom(context: Context, uri: Uri) {
        val raw = context.contentResolver.openInputStream(uri)?.use { stream ->
            stream.readNBytes(MAX_IMPORT_BYTES).toString(Charsets.UTF_8)
        }
        if (raw == null) {
            Log.w(TAG, "could not open history file")
            return
        }
        importText(raw)
    }

    fun importText(raw: String) {
        val parsed = HistoryFormat.parse(raw)
        val merged = LinkedHashMap<String, LongArray>()
        for (entry in load(file)) {
            merged[entry.text] = longArrayOf(entry.count.toLong(), entry.lastUsed)
        }
        for (entry in parsed) {
            val existing = merged[entry.text]
            if (existing == null) {
                merged[entry.text] = longArrayOf(entry.count.toLong(), entry.lastUsed)
            } else {
                existing[0] += entry.count
                existing[1] = maxOf(existing[1], entry.lastUsed)
            }
        }
        save(file, merged.map { (text, v) -> HistoryEntry(text, v[0].toInt(), v[1]) })
        reload()
    }

    fun clear() {
        file?.delete()
        index = HistoryIndex(emptyList())
    }

    private var loadedCount = 0

    fun count(): Int = loadedCount

    private fun reload() {
        val entries = load(file)
        loadedCount = entries.size
        index = HistoryIndex(entries)
        Log.i(TAG, "loaded " + loadedCount + " entries from " + file)
    }

    private fun load(file: File?): List<HistoryEntry> {
        if (file == null || !file.exists()) return emptyList()
        return try {
            file.readLines().mapNotNull { line ->
                val first = line.indexOf('\t')
                val second = if (first < 0) -1 else line.indexOf('\t', first + 1)
                if (second < 0) return@mapNotNull null
                val count = line.substring(0, first).toIntOrNull() ?: return@mapNotNull null
                val lastUsed = line.substring(first + 1, second).toLongOrNull() ?: 0L
                HistoryEntry(line.substring(second + 1), count, lastUsed)
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not read history cache")
            emptyList()
        }
    }

    private fun save(file: File?, entries: List<HistoryEntry>) {
        if (file == null) return
        try {
            file.bufferedWriter().use { out ->
                for (entry in entries) {
                    out.write(entry.count.toString())
                    out.write('\t'.code)
                    out.write(entry.lastUsed.toString())
                    out.write('\t'.code)
                    out.write(entry.text)
                    out.write('\n'.code)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not write history cache")
        }
    }
}
