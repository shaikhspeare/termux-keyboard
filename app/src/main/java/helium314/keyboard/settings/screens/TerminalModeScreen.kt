// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.preferences.SwitchPreference
import helium314.keyboard.settings.preferences.TextInputPreference

@Composable
fun TerminalModeScreen(
    onClickBack: () -> Unit,
) {
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.settings_screen_terminal),
        settings = listOf(Settings.PREF_TERMINAL_MODE, Settings.PREF_TERMINAL_PACKAGES)
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
)
