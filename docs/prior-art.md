# Phase 0 — Prior art

Research date: 2026-10-31. Every claim below is backed by a source URL or a file:line in a
cloned upstream tree (`termux-app` @ `8629e63`, 2026-09-26; `HeliBoard` @ `44ddb1a`, 2026-10-25).
On-device statements were measured on an Android 35 arm64 emulator with Termux 0.118.3 and a
purpose-built probe IME; the raw evidence is in [spikes.md](spikes.md).

## 0. The structural finding that reframes the whole search

Termux deliberately tells the IME "there is no editable text here":

```java
// terminal-view/src/main/java/com/termux/view/TerminalView.java:332
outAttrs.inputType = InputType.TYPE_NULL;          // default
// TerminalView.java:323 (only when termux.properties: enforce-char-based-input=true)
outAttrs.inputType = InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
// TerminalView.java:341
outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN;
```

Measured with the probe IME in Termux on the emulator:

| termux.properties | `EditorInfo.inputType` | class | variation | flags |
|---|---|---|---|---|
| default | `0x0` (`TYPE_NULL`) | `0` | `0x0` | `0x0` |
| `enforce-char-based-input=true` | `0x80090` | `0` | `0x90` (`VISIBLE_PASSWORD`) | `0x80000` (`NO_SUGGESTIONS`) |

Both variants have input class `0` — *not* `TYPE_CLASS_TEXT`. AOSP-derived keyboards gate the
entire suggestion pipeline on `TYPE_CLASS_TEXT` (`HeliBoard InputAttributes.java:61-82`), so
suggestions are off by construction, not by a user setting. On top of that, Termux returns a
`BaseInputConnection` whose text buffer is a fake empty `Editable` that Termux clears after every
`commitText` (`TerminalView.java:343-351, 366-378`; AOSP `BaseInputConnection.getEditable()`), so
`getTextBeforeCursor()` returns `""` at all times — measured, see S2.

Consequences for the search:

1. No stock or third-party IME can be *configured* into showing shell suggestions in Termux.
   The IME must (a) override the input-attribute gate and (b) track the command line itself.
2. "Just use Gboard with a special dictionary" is not a viable path.
3. The maintainers agree. Unexpected Keyboard's author, 2026-05-19:
   *"The keyboard can't access the text that is being typed in a terminal."*
   ([Julow/Unexpected-Keyboard#1294](https://github.com/Julow/Unexpected-Keyboard/issues/1294))
4. Termux has an open feature request for exactly this since 2019-04-08, still unimplemented:
   [termux/termux-app#1089](https://github.com/termux/termux-app/issues/1089).

## 1. Candidate matrix

| Candidate | What it does | License | Last activity | Works in Termux? | Remote SSH history? | Verdict |
|---|---|---|---|---|---|---|
| **Gboard** | Stock Google IME, word-level + clipboard suggestions | proprietary | shipped with OS | no suggestions in Termux at any input type | no | irrelevant |
| **SwiftKey** | Stock Microsoft IME, prediction from cloud/local n-grams | proprietary | — | no suggestions in Termux | no | irrelevant |
| **Unexpected Keyboard** | Termux-favourite layout keyboard, small word-suggestion engine | GPL-3.0 (`LICENSE`) | active (issues Oct 2026) | types fine; suggestion engine deliberately disabled in terminals (`CurrentlyTypedWord.java`: word = letter/digit/apostrophe, `getSurroundingText(20,20)`) | no | borrow ideas (key layout, Termux ergonomics) |
| **Hacker's Keyboard** | Full PC layout IME, abandoned | Apache-2.0 | last release 2018-11-27, no commits since ~2021 | types fine, no suggestions in terminals | no | irrelevant (abandoned) — but its `PluginManager` cross-app dictionary protocol is a useful reference |
| **AnySoftKeyboard** | Full IME + data-only add-ons (word lists, autotext, layout) | Apache-2.0 | commit 2026-10-27, release 1.13-r2 2026-09-08 | suggestions suppressed by `TYPE_NULL`; `AnySoftKeyboardSuggestions.java:424-428` honors `NO_SUGGESTIONS` | no — add-ons cannot execute code (`AddOnsFactory.java:403-421` parses XML only; no `DexClassLoader`) | irrelevant for (C) |
| **OpenBoard** | AOSP LatinIME fork; HeliBoard's ancestor | GPL-3.0 | last commit 2022-12-17, last release 1.4.5 (2022-08) | no suggestion machinery for terminals | no | irrelevant (dead) |
| **FlorisBoard** | Modern Kotlin IME, smartbar, extension store | Apache-2.0 | commit 2026-09-30, stable 0.5.2 (2025-11) | word suggestions/spell check **not shipped** (`README`); extension type is `theme` only (`docs.florisboard.org/extensions`) | no | irrelevant (no suggestion engine yet) |
| **FUTO Keyboard** | Voice-first IME | FUTO Source First License 1.1-kb | active | n/a | no | irrelevant — license forbids the fork we need |
| **Simple Keyboard** | Minimal AOSP-derived IME | Apache-2.0 | 6.8, 2026-10-27 | types fine; README states spell check/suggestions will never exist | no | irrelevant |
| **HeliBoard** | AOSP/OpenBoard fork, active, no INTERNET permission, pluggable-ish suggestion strip | GPL-3.0 (+ Apache-2.0 for AOSP parts) | commit 2026-10-25, release 4.3-beta1 2026-10-18 | suggestions suppressed by `InputAttributes` gate — one hook point fixes it | no (must be added) | **fork this** |
| **Termux extra-keys row** | On-screen ESC/CTRL/arrows/brackets row | part of Termux (GPL-3.0 / Apache-2.0 for terminal-view) | active | yes, it is Termux | n/a | complement (do not duplicate) |
| **Termux:API / Termux:Widget** | Intents into Termux (`RUN_COMMAND`) | GPL-3.0 | active | yes | only as a transport, not a suggestion source | borrow as optional transport |
| **zsh-autosuggestions** | Inline dim suggestion from history, accept with Right/End | MIT | v0.7.1 (2024-11-15) | yes (Termux package) | local only | complement |
| **fish autosuggestions** | Same, built in; accept Right / Ctrl-F | GPL-2.0 | fish 4.9.3, 2026-09-08 | yes | local only | complement |
| **atuin** | SQLite history + Ctrl-R TUI + optional E2E-encrypted sync | MIT | v18.24.4, 2026-10-30 | yes (Termux package 18.24.4); an Android key-file bug was fixed Oct 2026 | only via atuin's own server + account, not SSH | complement; not the transport for per-host history |
| **mcfly** | Neural-ish Ctrl-R replacement | MIT | v0.9.4, 2025-12-24 | packaged in Termux | no | irrelevant for v1 |
| **fzf** | Fuzzy finder; Ctrl-R history integration | MIT | 0.74.5 (2026-10) | yes | no | complement |
| **ble.sh** | Bash line editor with rich completion | BSD-3-Clause | active | heavy on Termux | no | irrelevant for v1 |
| **Termux-autocomplete** (afrhan-repo) | Script that installs fish + config for autosuggest | MIT-ish repo | small project | yes | no | complement only |
| **Termius** | Closed-source SSH client; autocomplete reads **host history over an extra SSH exec channel** | proprietary | active | it is not an IME; does not help inside Termux | **yes** — the only shipped proof of the data path | borrow the design (documented: OpenSSH 7.2+, bash, `MaxSessions>=2`) |
| **TermX** (andiahmadnurmadani) | MIT SSH client with autocomplete row from host shell history | MIT | v0.8.3, 2026-10-12 | not an IME | yes, same technique | borrow implementation ideas |
| **ConnectBot** | OSS SSH client | Apache-2.0 | 2026-10-29 | not an IME; termlib hardcodes `NO_SUGGESTIONS|VISIBLE_PASSWORD`; per-host suggestions PR #2016 closed unmerged 2026-04-17 | no | irrelevant |
| **JuiceSSH** | Closed-source SSH client | proprietary | last release 2021-02-06 | not an IME | snippets only | irrelevant |
| **swell.sh** | Web terminal with bash-completion-driven autocomplete | AGPL-3.0 | last push 2023-08-14 | runs in Termux as a web server | no (fails over SSH) | irrelevant |
| **KeyTab / Bucky / 1337 Board / KeyJawn / CustomKeyboardEngine** | Small terminal-oriented IMEs | MIT / Apache-2.0 / GPL-3.0 | 2026 | yes (IME) | **no** — none reads host history; KeyTab has no INTERNET permission at all | borrow UI ideas only |

## 2. Why not "just use the shell"

Shell-side suggestions (fish, `zsh-autosuggestions`, atuin, mcfly, fzf) are excellent, mature, and
run on Termux today. They fail the project goal on three counts:

1. **No remote context.** Inside `ssh host`, the *local* shell is suspended; the remote shell's
   history is only available if the same tools are installed and configured on the host. atuin can
   sync, but only through its own account/server, and it must be installed on every host.
2. **Acceptance is still keyboard-hostile.** Inline suggestions are accepted with Right / End /
   Ctrl-F / Tab. On a phone that means reaching for Termux's extra-keys row — the exact friction
   the project is trying to remove.
3. **No ranking across "current host + global".** Every tool is per-shell-instance.

They *are* the right thing to recommend as a complement (they need no APK, no permissions) and the
IME should not break them.

## 3. What exists that does satisfy the goal?

**Nothing.** No Android IME surfaces suggestions sourced from the *remote* host's shell history
while typing inside Termux. The field splits cleanly:

- terminal/SSH **apps** that read per-host history but are not IMEs (Termius, TermX, swell.sh);
- terminal-oriented **IMEs** that work in Termux but have no history source (KeyTab, Bucky,
  1337 Board, KeyJawn, Unexpected Keyboard).

The closest shipped behaviour is Termius's extra-SSH-exec-channel history fetch — a documented,
portable technique we can reuse in the Termux-side helper (`ssh <host> 'tail -n N ~/.bash_history'`).

## 4. Recommendation

**(A) fork HeliBoard, plus (E) hybrid:** the IME provides the strip; shell-side suggestions remain a
supported, documented complement (and the fallback if the helper is not installed).

Why HeliBoard and not another base:

1. **Only active AOSP-LatinIME fork.** OpenBoard is dead (2022); HeliBoard ships every ~2 weeks
   (4.2 on 2026-10-12, 4.3-beta1 on 2026-10-18, commits 2026-10-25). Upstream merges stay cheap.
2. **The suggestion strip already exists and renders arbitrary `CharSequence`s**
   (`SuggestionStripLayoutHelper.java:481`, ellipsized with `TextUtils.TruncateAt.MIDDLE`), so
   whole-line shell suggestions need no new UI widget.
3. **Exactly one gate disables suggestions for Termux** (`InputAttributes.java:61-82`); terminal
   mode is a package-conditional override there, not a re-architecture.
4. **Suggestion production and acceptance are two call sites**
   (`InputLogic.getSuggestedWords()` at `InputLogic.java:2481`,
   `LatinIME.pickSuggestionManually()` at `LatinIME.java:1463`), both behind
   `SettingsValues`/`SuggestedWords`, so a `ShellSuggestionProvider` can be injected without
   touching the dictionary engine.
5. **No INTERNET permission upstream** (per README) — the security posture in the spec is a
   one-line diff, not a removal of existing network code.
6. **GPL-3.0** matches the spec's license requirement; HeliBoard already carries the AOSP
   Apache-2.0 notices.

Rejected alternatives, with the blocking evidence: AnySoftKeyboard add-ons are data-only
(`AddOnsFactory.java:403-421`), FlorisBoard has no suggestion engine (`README`), FUTO's license
forbids the fork, OpenBoard/Hacker's Keyboard are unmaintained, Unexpected Keyboard's engine is
word-scoped and its UI is a custom canvas with no suggestion strip.

## 5. Sources

Termux
- `terminal-view/src/main/java/com/termux/view/TerminalView.java` (lines 313-343, 366-378, 671-676)
- `app/src/main/java/com/termux/app/terminal/TermuxActivityRootView.java` (lines 25-55)
- `termux-shared/.../TermuxPropertyConstants.java:121`
- https://wiki.termux.com/wiki/Terminal_Settings
- https://github.com/termux/termux-app/issues/1089 (open, 2019-04-08)
- https://github.com/termux/termux-app/releases/tag/v0.118.3 (2025-05-22)

Android
- AOSP `BaseInputConnection.java` `getEditable()` / `getTextBeforeCursor()` — fake empty editable

HeliBoard
- https://github.com/HeliBorg/HeliBoard (commit 2026-10-25, release 4.3-beta1 2026-10-18)
- `app/src/main/java/helium314/keyboard/latin/InputAttributes.java:60-82,96-97`
- `app/src/main/java/helium314/keyboard/latin/settings/SettingsValues.java:267-275,346`
- `app/src/main/java/helium314/keyboard/latin/inputlogic/InputLogic.java:2481-2502,1356`
- `app/src/main/java/helium314/keyboard/latin/LatinIME.java:1438-1478`
- `app/src/main/java/helium314/keyboard/latin/suggestions/SuggestionStripLayoutHelper.java:481,553-557`

Other IMEs / apps
- https://github.com/Julow/Unexpected-Keyboard/issues/1294 (maintainer statement, 2026-05-19)
- https://github.com/Julow/Unexpected-Keyboard/blob/master/srcs/juloo.keyboard2/CurrentlyTypedWord.java
- https://github.com/AnySoftKeyboard/AnySoftKeyboard/blob/main/ime/app/src/main/java/com/anysoftkeyboard/ime/AnySoftKeyboardSuggestions.java
- https://github.com/AnySoftKeyboard/AnySoftKeyboard/blob/main/ime/addons/src/main/java/com/anysoftkeyboard/addons/AddOnsFactory.java
- https://github.com/florisboard/florisboard (README: no suggestions yet) ; https://docs.florisboard.org/extensions
- https://github.com/openboard-team/openboard (last commit 2022-12-17)
- https://github.com/klausw/hackerskeyboard (last release 2018-11-27)
- https://keyboard.futo.org / FUTO Source First License 1.1-kb
- https://docs.termius.com/terminal/autocomplete-and-shell-integration
- https://github.com/andiahmadnurmadani/TermX
- https://github.com/connectbot/connectbot/issues/2015
- https://github.com/piot5/keytab

Shell-side
- https://github.com/zsh-users/zsh-autosuggestions (MIT, v0.7.1 2024-11-15)
- https://fishshell.com/docs/current/interactive.html (fish 4.9.3)
- https://github.com/atuinsh/atuin (MIT, v18.24.4 2026-10-30) ; https://docs.atuin.sh/latest/guide/sync/
- https://github.com/cantino/mcfly (MIT, v0.9.4 2025-12-24)
- https://github.com/junegunn/fzf (MIT, 0.74.5)
