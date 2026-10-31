// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.terminal

import android.content.Context
import android.text.InputType
import android.content.SharedPreferences
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs

/**
 * Per-package opt-in for terminal mode.
 *
 * Termux hands the IME `InputType.TYPE_NULL` (or, with `enforce-char-based-input=true`,
 * `TYPE_TEXT_VARIATION_VISIBLE_PASSWORD | TYPE_TEXT_FLAG_NO_SUGGESTIONS`). Both have input class 0,
 * which is what makes every AOSP-derived keyboard refuse to suggest anything. Terminal mode is
 * therefore an IME-side override keyed on the package name, not a user-visible input type.
 */
object TerminalMode : SharedPreferences.OnSharedPreferenceChangeListener {
    private const val DEFAULT_PACKAGE = "com.termux"

    @Volatile
    private var enabled = false

    @Volatile
    private var packages: Set<String> = setOf(DEFAULT_PACKAGE)

    @Volatile
    private var activePackage: String? = null

    fun init(context: Context) {
        val prefs = context.prefs()
        prefs.registerOnSharedPreferenceChangeListener(this)
        HistoryStore.init(context)
        reload(prefs)
    }

    private fun reload(prefs: SharedPreferences) {
        enabled = prefs.getBoolean(Settings.PREF_TERMINAL_MODE, Defaults.PREF_TERMINAL_MODE)
        packages = prefs.getString(Settings.PREF_TERMINAL_PACKAGES, Defaults.PREF_TERMINAL_PACKAGES)
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: emptySet()
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences, key: String?) {
        if (key == Settings.PREF_TERMINAL_MODE || key == Settings.PREF_TERMINAL_PACKAGES) reload(prefs)
    }

    fun appliesTo(packageName: String?): Boolean =
        enabled && packageName != null && packages.contains(packageName)

    /** True while the focused field belongs to a terminal app. */
    val isActive: Boolean get() = activePackage != null

    val currentPackage: String? get() = activePackage

    /**
     * Termux has two input targets: the terminal view (input class 0) and its optional "text input
     * view", a real EditText (input class TEXT). Only the former is a shell line.
     */
    fun onInputStart(packageName: String?, inputType: Int) {
        val isTextField = (inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT
        activePackage = if (appliesTo(packageName) && !isTextField) packageName else null
        ShellLine.reset()
    }

    fun onInputFinish() {
        activePackage = null
        ShellLine.reset()
    }
}
