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

    fun forLine(line: String): List<String> {
        if (line.isEmpty() || !ShellLine.isTrusted) return emptyList()
        val matches = ArrayList<StaticCommands.Entry>(8)
        for (entry in StaticCommands.entries) {
            if (entry.text.length > line.length && entry.text.startsWith(line)) matches.add(entry)
        }
        matches.sortWith(
            compareByDescending<StaticCommands.Entry> { it.weight }
                .thenBy { it.text.length }
                .thenBy { it.text }
        )
        return matches.take(MAX_SUGGESTIONS).map { it.text }
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
