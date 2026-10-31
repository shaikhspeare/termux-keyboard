// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryTest {
    private val now = 1_800_000_000L

    @Test
    fun parsesBashWithTimestamps() {
        val raw = """
            #1700000000
            git status
            #1700000100
            ls -la
        """.trimIndent()
        val entries = HistoryFormat.parse(raw)
        assertEquals(listOf("git status", "ls -la"), entries.map { it.text })
        assertEquals(1700000000L, entries[0].lastUsed)
        assertEquals(1700000100L, entries[1].lastUsed)
    }

    @Test
    fun parsesZshExtended() {
        val raw = listOf(
            ": 1700000000:0;docker compose up -d",
            ": 1700000300:5;pkg update",
        ).joinToString("\n")
        val entries = HistoryFormat.parse(raw)
        assertEquals(listOf("docker compose up -d", "pkg update"), entries.map { it.text })
        assertEquals(1700000300L, entries[1].lastUsed)
    }

    @Test
    fun parsesFish() {
        val raw = """
            - cmd: systemctl status nginx
              when: 1700000000
            - cmd: journalctl -u nginx -f
              when: 1700000500
        """.trimIndent()
        val entries = HistoryFormat.parse(raw)
        assertEquals(listOf("systemctl status nginx", "journalctl -u nginx -f"), entries.map { it.text })
        assertEquals(1700000500L, entries[1].lastUsed)
    }

    @Test
    fun joinsContinuationLines() {
        val raw = "docker run \\\n  -p 8080:80 nginx\npwd"
        val entries = HistoryFormat.parse(raw)
        assertEquals(listOf("docker run -p 8080:80 nginx", "pwd"), entries.map { it.text })
    }

    @Test
    fun dropsSecretsAndSpacePrefixedLines() {
        val raw = """
            mysql -u root --password=hunter2
            curl -H "Authorization: token abc"
            ls
             git commit --amend
            grep AKIAIOSFODNN7EXAMPLE credentials.txt
        """.trimIndent()
        val entries = HistoryFormat.parse(raw)
        assertEquals(listOf("ls"), entries.map { it.text })
    }

    @Test
    fun deduplicatesAndCounts() {
        val raw = "ls\nls\nls\ngit status"
        val entries = HistoryFormat.parse(raw)
        assertEquals(2, entries.size)
        assertEquals(3, entries[0].count)
    }

    @Test
    fun keepsTheAlphabeticallyFirstOnTies() {
        val index = HistoryIndex(HistoryFormat.parse("git pull\ngit stash"))
        assertEquals(listOf("git pull", "git stash"), index.suggest("git", 2, now).map { it.text })
    }

    @Test
    fun ranksPrefixMatchesByFrecency() {
        val entries = HistoryFormat.parse(
            """
            git status
            git status
            git status
            git pull
            git stash
            """.trimIndent()
        )
        val index = HistoryIndex(entries)
        val result = index.suggest("git", 2, now)
        assertEquals(listOf("git status", "git pull"), result.map { it.text })
    }

    @Test
    fun olderEntriesLoseToRecentOnes() {
        val day = 86_400L
        val index = HistoryIndex(
            listOf(
                HistoryEntry("deploy.sh", count = 5, lastUsed = now - 200 * day),
                HistoryEntry("deploy-prod.sh", count = 5, lastUsed = now - day),
            )
        )
        val result = index.suggest("deploy", 1, now)
        assertEquals("deploy-prod.sh", result.single().text)
    }

    @Test
    fun skipsExactLineAndNonPrefixMatches() {
        val index = HistoryIndex(HistoryFormat.parse("git status\ngit\nls -la"))
        assertTrue(index.suggest("git", 3, now).none { it.text == "git" })
        assertTrue(index.suggest("zzz", 3, now).isEmpty())
    }
}
