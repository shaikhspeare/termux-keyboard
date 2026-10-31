# Upstream touchpoints

Every file of the HeliBoard tree that this fork modifies, why, and how to keep the diff cheap to
merge. New files are not listed here — they live in `helium314.keyboard.terminal` and
`settings/screens/TerminalModeScreen.kt` and never conflict with upstream.

Upstream base: `HeliBorg/HeliBoard` @ `44ddb1a` (2026-10-25), tracked as remote `upstream`.

## Modified upstream files

| File | Change | Merge risk |
|---|---|---|
| `app/build.gradle.kts` | `applicationId` → `org.termuxkeyboard.latin` so the fork installs next to HeliBoard | none (one line) |
| `app/src/main/AndroidManifest.xml` | provider authorities → `${applicationId}.clipprovider` / `${applicationId}.provider`; without this the fork cannot coexist with HeliBoard | low (two lines) |
| `app/src/main/res/values/clip_provider.xml` | deleted (authority constant no longer used) | none |
| `app/src/main/res/values/gesture_data.xml` | dropped the unused authority string | none |
| `app/src/main/res/values/donottranslate.xml` | app label → "Termux Keyboard" | none |
| `app/src/main/res/values/strings.xml` | five terminal-mode strings, added in their own block | low (append-only) |
| `app/src/main/res/drawable/ic_settings_terminal.xml` | new icon | none |
| `latin/InputAttributes.java` | one early branch: if `TerminalMode.appliesTo(package)` force suggestions on, everything word-level off | low — a self-contained `if` + `return` at the top of the constructor |
| `latin/LatinIME.java` | `TerminalMode.init()` + handler creation in `onCreate`; `onInputStart`/`onInputFinish`; early-return in `onEvent`, `onTextInput`, `pickSuggestionManually`; one `refresh()` at the end of `onStartInputViewInternal` | medium — five small blocks, all guarded by `TerminalMode` |
| `latin/inputlogic/InputLogic.java` | `getSuggestedWords()` returns terminal suggestions when active | low — four lines at the top of the method |
| `latin/settings/Settings.java` | two preference keys | none |
| `latin/settings/Defaults.kt` | two defaults | none |
| `settings/SettingsNavHost.kt` | destination + route + callback | low |
| `settings/screens/MainSettingsScreen.kt` | one row + one parameter | low |
| `settings/SettingsContainer.kt` | register the terminal settings for search | none |

## Why these and not others

- **`InputAttributes`** is the only place that decides whether the strip is allowed to exist. Termux
  reports input class 0, so the stock code path sets `mShouldShowSuggestions = false` and returns.
- **`LatinIME.onEvent`** is the single funnel for every software key press; handling terminal input
  there means the word composer, composing text and auto-correct are never involved, which is
  exactly what a byte-oriented terminal needs.
- **`LatinIME.pickSuggestionManually`** is the only callback from the strip. Accepting a suggestion
  must not go through `InputLogic.onPickSuggestionManually`, because that deletes and retypes the
  typed word and may append a space.
- **`InputLogic.getSuggestedWords`** keeps any suggestion request — including ones triggered by the
  framework or after rotation — consistent with the tracked line instead of returning dictionary
  results.

## Deliberately untouched

- No changes to the dictionary/JNI layer, the gesture code, the theme system or the toolbar.
- No change to `TerminalView` or Termux: the IME adapts to what Termux sends, not the other way
  round.

## Upstream merge recipe

```sh
git fetch upstream
git merge upstream/main        # conflicts, if any, are confined to the table above
./gradlew :app:assembleDebug
```

The terminal feature is additive: if a merge breaks a hook, deleting the `TerminalMode` guard
restores stock HeliBoard behaviour.
