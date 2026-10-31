// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.terminal

import android.content.Context
import android.content.SharedPreferences
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Pulls history from the optional Termux helper (`helper/termux-keyboard-helper`).
 *
 * The helper is a small daemon the user starts inside Termux; it binds 127.0.0.1, hands out one
 * host's raw history at a time and requires the token configured here. Everything the keyboard
 * needs from the network happens on a background thread, at most once a minute, and only while a
 * terminal field is focused — the keystroke path never touches the socket.
 */
object HelperClient : SharedPreferences.OnSharedPreferenceChangeListener {
    private const val TAG = "HelperClient"
    private const val REFRESH_INTERVAL_MS = 60_000L

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "termux-keyboard-helper").apply { isDaemon = true }
    }

    @Volatile
    private var index = HistoryIndex(emptyList())

    @Volatile
    private var state = "off"

    @Volatile
    private var lastAttempt = 0L

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        val preferences = context.prefs()
        prefs = preferences
        preferences.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences, key: String?) {
        if (key == Settings.PREF_TERMINAL_HELPER_ENABLED ||
            key == Settings.PREF_TERMINAL_HELPER_PORT ||
            key == Settings.PREF_TERMINAL_HELPER_TOKEN
        ) {
            index = HistoryIndex(emptyList())
            lastAttempt = 0L
            state = "off"
        }
    }

    /** Human readable state for the settings screen. */
    fun state(): String = state

    fun suggest(line: String, limit: Int): List<Pair<HistoryEntry, Double>> =
        index.suggestScored(line, limit, System.currentTimeMillis() / 1000)

    fun onInputStart() {
        val preferences = prefs ?: return
        if (!preferences.getBoolean(Settings.PREF_TERMINAL_HELPER_ENABLED, Defaults.PREF_TERMINAL_HELPER_ENABLED)) return
        val token = preferences.getString(Settings.PREF_TERMINAL_HELPER_TOKEN, Defaults.PREF_TERMINAL_HELPER_TOKEN).orEmpty()
        if (token.isEmpty()) return
        val now = System.currentTimeMillis()
        if (now - lastAttempt < REFRESH_INTERVAL_MS) return
        lastAttempt = now
        val port = preferences.getString(Settings.PREF_TERMINAL_HELPER_PORT, Defaults.PREF_TERMINAL_HELPER_PORT)
            ?.toIntOrNull() ?: return
        executor.execute { fetch(port, token) }
    }

    private fun fetch(port: Int, token: String) {
        try {
            val status = JSONObject(get("/v1/status", port, token))
            val host = if (status.isNull("activeHost")) "local"
                       else status.optString("activeHost").ifEmpty { "local" }
            val text = get("/v1/history?host=$host", port, token)
            val entries = HistoryFormat.parse(text)
            index = HistoryIndex(entries)
            state = "$host: ${entries.size} commands"
            Log.i(TAG, "loaded ${entries.size} commands for $host")
        } catch (e: Exception) {
            state = "offline: ${e.message?.take(80) ?: e.javaClass.simpleName}"
            Log.w(TAG, "helper fetch failed: ${e.message}")
        }
    }

    private fun get(path: String, port: Int, token: String): String {
        val connection = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        connection.connectTimeout = 2_000
        connection.readTimeout = 4_000
        connection.setRequestProperty("Authorization", "Bearer $token")
        try {
            val code = connection.responseCode
            if (code != 200) throw IOException("HTTP $code")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
