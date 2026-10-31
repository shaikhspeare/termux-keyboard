// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.SettingsWithoutKey
import helium314.keyboard.settings.filePicker
import helium314.keyboard.settings.layoutIntent
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.settings.preferences.SwitchPreference
import helium314.keyboard.settings.preferences.TextInputPreference
import helium314.keyboard.terminal.HistoryStore

@Composable
fun TerminalModeScreen(
    onClickBack: () -> Unit,
) {
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.settings_screen_terminal),
        settings = listOf(
            Settings.PREF_TERMINAL_MODE,
            Settings.PREF_TERMINAL_PACKAGES,
            SettingsWithoutKey.IMPORT_HISTORY,
            SettingsWithoutKey.CLEAR_HISTORY,
        )
    )
}

fun createTerminalSettings(context: Context) = listOf(
    Setting(
        context,
        Settings.PREF_TERMINAL_MODE,
        R.string.terminal_mode,
        R.string.terminal_mode_summary
    ) {
        SwitchPreference(it, Defaults.PREF_TERMINAL_MODE)
    },
    Setting(
        context,
        Settings.PREF_TERMINAL_PACKAGES,
        R.string.terminal_packages,
        R.string.terminal_packages_summary
    ) {
        TextInputPreference(it, Defaults.PREF_TERMINAL_PACKAGES)
    },
    Setting(
        context,
        SettingsWithoutKey.IMPORT_HISTORY,
        R.string.terminal_import_history,
        R.string.terminal_import_history_summary
    ) {
        val ctx = LocalContext.current
        val picker = filePicker { uri ->
            HistoryStore.importFrom(ctx, uri)
            Toast.makeText(
                ctx,
                ctx.getString(R.string.terminal_history_imported, HistoryStore.count()),
                Toast.LENGTH_SHORT
            ).show()
        }
        Preference(
            name = stringResource(R.string.terminal_import_history),
            description = stringResource(R.string.terminal_import_history_summary),
            onClick = { picker.launch(layoutIntent) }
        )
    },
    Setting(
        context,
        SettingsWithoutKey.CLEAR_HISTORY,
        R.string.terminal_clear_history,
        R.string.terminal_clear_history_summary
    ) {
        val ctx = LocalContext.current
        Preference(
            name = stringResource(R.string.terminal_clear_history),
            description = stringResource(R.string.terminal_clear_history_summary),
            onClick = {
                HistoryStore.clear()
                Toast.makeText(ctx, R.string.terminal_history_cleared, Toast.LENGTH_SHORT).show()
            }
        )
    },
)
