// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.terminal

import helium314.keyboard.latin.SuggestedWords
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo

/**
 * Turns the tracked command line into suggestions.
 *
 * Matching is a prefix match on the whole line, because that is what a shell completion is: the
 * user has typed `gi` and the candidate is `git status`, of which only `t status` still has to be
 * sent. Ranking is static weight for now; history frecency replaces the weight in slice 2.
 */
object ShellSuggestions {
    const val MAX_SUGGESTIONS = 3

    /**
     * The bundled list is a cold-start prior; history is personal evidence. Both are put on one
     * scale so a command used a handful of times outranks a generic entry, while a single recent
     * run does not bury the obvious completion (`gi` should still offer `git status`).
     */
    private const val STATIC_WEIGHT_SCALE = 0.5

    fun forLine(line: String): List<String> {
        if (line.isEmpty() || !ShellLine.isTrusted) return emptyList()
        val scored = LinkedHashMap<String, Double>()
        for ((entry, score) in HistoryStore.suggestScored(line, MAX_SUGGESTIONS * 2)) {
            scored[entry.text] = score
        }
        for (entry in StaticCommands.entries) {
            if (entry.text.length <= line.length || !entry.text.startsWith(line)) continue
            val score = entry.weight * STATIC_WEIGHT_SCALE
            val existing = scored[entry.text]
            if (existing == null || score > existing) scored[entry.text] = score
        }
        return scored.entries
            .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
            .take(MAX_SUGGESTIONS)
            .map { it.key }
    }

    /**
     * HeliBoard's strip expects the typed word at index 0 and omits it when
     * `INPUT_STYLE_TYPING`; the remaining entries are the visible candidates.
     */
    fun asSuggestedWords(line: String): SuggestedWords {
        val suggestions = forLine(line)
        if (suggestions.isEmpty()) return SuggestedWords.getEmptyInstance()
        val typed = SuggestedWordInfo(
            line, "", SuggestedWordInfo.MAX_SCORE, SuggestedWordInfo.KIND_TYPED, null, 0, 0
        )
        val list = ArrayList<SuggestedWordInfo>(suggestions.size + 1)
        list.add(typed)
        suggestions.forEachIndexed { index, text ->
            list.add(
                SuggestedWordInfo(
                    text, "", SuggestedWordInfo.MAX_SCORE - index - 1,
                    SuggestedWordInfo.KIND_COMPLETION, null, 0, 0
                )
            )
        }
        return SuggestedWords(
            list, null, typed, line.isNotEmpty(), false, false,
            SuggestedWords.INPUT_STYLE_TYPING, SuggestedWords.NOT_A_SEQUENCE_NUMBER
        )
    }
}
