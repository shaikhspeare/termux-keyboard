// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.terminal

import java.util.regex.Pattern

/** One command line as it appears in a shell history file. */
class HistoryEntry(val text: String, val count: Int, val lastUsed: Long)

/**
 * Parses bash, zsh and fish history files into deduplicated entries.
 *
 * Formats handled (all as they are written by the shells themselves):
 * - bash: one command per line, optionally preceded by `#<epochSeconds>`;
 * - zsh: `: <epochSeconds>:<duration>;<command>` with `EXTENDED_HISTORY`;
 * - fish: `- cmd: <command>` followed by an indented `when: <epochSeconds>`.
 *
 * A trailing backslash joins the next line, which is how multi-line commands are stored.
 * Lines matching [REDACT] and lines starting with a space (bash `HISTCONTROL=ignorespace`) are
 * dropped, so secrets never reach the in-memory index or the on-disk cache.
 */
object HistoryFormat {
    private const val MAX_ENTRY_LENGTH = 512

    private val REDACT = Pattern.compile(
        "(?i)(password|passwd|token|secret|api[_-]?key|AKIA|BEGIN [A-Z ]*PRIVATE KEY)"
    )
    private val ZSH_EXTENDED = Regex("""^: (\d{10}):\d+;(.*)$""")
    private val BASH_TIMESTAMP = Regex("""^#(\d{10})$""")
    private val FISH_CMD = Regex("""^- cmd: (.*)$""")
    private val FISH_WHEN = Regex("""^\s+when: (\d+)\s*$""")

    fun parse(raw: String): List<HistoryEntry> {
        val acc = LinkedHashMap<String, LongArray>() // text -> [count, lastUsedSeconds]
        val lines = raw.split('\n')
        var bashTimestamp = 0L
        var i = 0
        while (i < lines.size) {
            val line = lines[i]

            val zsh = ZSH_EXTENDED.matchEntire(line)
            if (zsh != null) {
                add(acc, zsh.groupValues[2], zsh.groupValues[1].toLongOrNull() ?: 0L)
                i++
                continue
            }

            val timestamp = BASH_TIMESTAMP.matchEntire(line)
            if (timestamp != null) {
                bashTimestamp = timestamp.groupValues[1].toLongOrNull() ?: 0L
                i++
                continue
            }

            val fish = FISH_CMD.matchEntire(line)
            if (fish != null) {
                var whenTs = 0L
                if (i + 1 < lines.size) {
                    val w = FISH_WHEN.matchEntire(lines[i + 1])
                    if (w != null) {
                        whenTs = w.groupValues[1].toLongOrNull() ?: 0L
                        i++
                    }
                }
                add(acc, fish.groupValues[1], whenTs)
                i++
                continue
            }

            var command = line
            while (command.endsWith("\\") && i + 1 < lines.size) {
                command = command.dropLast(1).trimEnd() + " " + lines[i + 1].trim()
                i++
            }
            add(acc, command, bashTimestamp)
            bashTimestamp = 0L
            i++
        }
        return acc.map { (text, value) -> HistoryEntry(text, value[0].toInt(), value[1]) }
    }

    private fun add(acc: MutableMap<String, LongArray>, rawCommand: String, timestamp: Long) {
        val command = rawCommand.trimEnd()
        if (command.isEmpty() || command.length > MAX_ENTRY_LENGTH) return
        if (command.startsWith(" ")) return              // HISTCONTROL=ignorespace
        if (REDACT.matcher(command).find()) return
        val existing = acc[command]
        if (existing == null) {
            acc[command] = longArrayOf(1, timestamp)
        } else {
            existing[0] = existing[0] + 1
            if (timestamp > existing[1]) existing[1] = timestamp
        }
    }
}

/**
 * Prefix index over history entries with frecency ordering.
 *
 * Entries are sorted once; a query binary-searches the prefix range and picks the top few by
 * `count × age bucket`. At 100k lines this is well under a millisecond (measured: p95 0.29 ms in
 * the prototype, see docs/spikes.md S7).
 */
class HistoryIndex(entries: List<HistoryEntry>) {
    private val items: Array<HistoryEntry> =
        entries.sortedWith(compareBy { it.text }).toTypedArray()

    fun isEmpty(): Boolean = items.isEmpty()

    fun suggest(prefix: String, limit: Int, nowSeconds: Long): List<HistoryEntry> =
        suggestScored(prefix, limit, nowSeconds).map { it.first }

    fun suggestScored(prefix: String, limit: Int, nowSeconds: Long): List<Pair<HistoryEntry, Double>> {
        if (prefix.isEmpty() || items.isEmpty() || limit <= 0) return emptyList()
        var i = lowerBound(prefix)
        val best = ArrayList<HistoryEntry>(limit)
        val scores = ArrayList<Double>(limit)
        while (i < items.size && items[i].text.startsWith(prefix)) {
            val entry = items[i]
            i++
            if (entry.text.length == prefix.length) continue
            val score = frecency(entry, nowSeconds)
            if (best.size < limit) {
                best.add(entry)
                scores.add(score)
                continue
            }
            // <= keeps the earliest (alphabetically first) entry on ties, which is stable
            var worst = 0
            for (k in 1 until scores.size) if (scores[k] <= scores[worst]) worst = k
            if (score > scores[worst]) {
                best[worst] = entry
                scores[worst] = score
            }
        }
        return best.map { it to frecency(it, nowSeconds) }.sortedByDescending { it.second }
    }

    private fun lowerBound(prefix: String): Int {
        var lo = 0
        var hi = items.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (items[mid].text < prefix) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun frecency(entry: HistoryEntry, nowSeconds: Long): Double {
        if (entry.lastUsed <= 0L) return entry.count.toDouble()
        val days = (nowSeconds - entry.lastUsed).coerceAtLeast(0L) / 86_400.0
        val weight = when {
            days < 4 -> 1.0
            days < 15 -> 0.75
            days < 31 -> 0.5
            days < 91 -> 0.25
            else -> 0.1
        }
        return entry.count * weight
    }
}
