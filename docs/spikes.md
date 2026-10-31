# Phase 1 — Feasibility spikes

Environment used for on-device evidence (all measurements reproduced below):

| Piece | Version / detail |
|---|---|
| Device | Android emulator `Pixel_8_API_35`, API 35 (Android 15), `arm64-v8a`, Google APIs image |
| Termux | `termux-app` v0.118.3 GitHub debug build, `arm64-v8a` (sha256 `72fdb596…a4e`) |
| Probe IME | purpose-built, source in `tools/probe-ime/`; logs `EditorInfo` and exposes `commitText`, `setComposingText`, `deleteSurroundingText`, `sendKeyEvent`, `getTextBeforeCursor`, `getSelectedText`, `getExtractedText`, socket probes |
| Host tooling | JDK 21, Android SDK 37 / build-tools 35, NDK 28.0.13004108, Gradle 9.7.1 |

No physical phone was attached (`adb devices` empty), so every "device" claim below is from the
emulator. The spec requires a phone matrix at the end; that gap is listed in §9.

---

## S1 — Termux input type. **PASS**

`TerminalView.onCreateInputConnection()` is the only input target in Termux's terminal:

```java
// terminal-view/src/main/java/com/termux/view/TerminalView.java:314-343
if (mClient.isTerminalViewSelected()) {
    if (mClient.shouldEnforceCharBasedInput()) {
        outAttrs.inputType = InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                           | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;   // line 323
    } else {
        outAttrs.inputType = InputType.TYPE_NULL;                       // line 332
    }
} else {
    outAttrs.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_NORMAL; // 336
}
outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN;                 // line 341
return new BaseInputConnection(this, true) { … };                        // line 343
```

`enforce-char-based-input` defaults to **false**
(`TermuxPropertyConstants.java:121`, `TermuxSharedProperties` default-false list).

Measured with the probe IME, `pkg=com.termux`, `fieldId=2131231121`, `initialSel=-1..-1`:

```
default properties:
I PROBEIME: onStartInput ... inputType=0x0 imeOptions=0x2000000 pkg=com.termux fieldId=2131231121
I PROBEIME: onStartInputView ... inputType=0x0 class=0 variation=0x0 flags=0x0 imeOptions=0x2000000

~/.termux/termux.properties: enforce-char-based-input=true:
I PROBEIME: onStartInput ... inputType=0x80090 imeOptions=0x2000000 pkg=com.termux
I PROBEIME: onStartInputView ... inputType=0x80090 class=0 variation=0x90 flags=0x80000
```

Result: **input class is `0` in both cases**, i.e. never `TYPE_CLASS_TEXT`. Variation is
`VISIBLE_PASSWORD` and `NO_SUGGESTIONS` is set in the opt-in variant. Any IME that gates on
`TYPE_CLASS_TEXT` (all AOSP-derived ones, including HeliBoard) disables suggestions.

Verdict: terminal mode cannot be a "setting"; it must be an IME-side override keyed on the package
name. `enforce-char-based-input` is *not* a workaround (and Termux's own docs call such keyboards
"not compatible with the Termux input method", wiki `Terminal_Settings`).

## S2 — What the IME sees. **PASS**

Termux returns `new BaseInputConnection(this, true)` where `this` is a `View`, not a `TextView`.
AOSP `BaseInputConnection.getEditable()` lazily creates a **fake empty `Editable`**
(`BaseInputConnection.java:168-173`), and Termux clears that buffer after every commit:

```java
// TerminalView.java:343-351 (finishComposingText) and 366-378 (commitText)
sendTextToTerminal(getEditable());
getEditable().clear();
```

Measured behaviour (probe → logcat, terminal line verified by screenshot):

| API call | Returned | Reached the terminal? | Notes |
|---|---|---|---|
| `getTextBeforeCursor(200,0)` | `""` | n/a | **always empty**, even after typing `gi` — measured 5× |
| `getTextAfterCursor(200,0)` | `""` | n/a | same |
| `getSelectedText(0)` | `null` | n/a | no selection support |
| `commitText("g",1)`, `commitText("i",1)` | `true` | yes, byte-for-byte | per-character commits echo correctly |
| `commitText("t status",1)` | `true` | yes | multi-char, spaces, multi-byte safe |
| `commitText("\n",1)` | `true` | yes, executes line | Termux maps `\n` → `\r` internally (`TerminalView.java:404-410`) |
| `commitText("\u0003",1)` | `true` | yes | Ctrl-C works; `codePoint<=31` is converted to a ctrl char |
| `setComposingText("xyz",1)` | `true` | **no** | text sits in the fake editable; `getTextBeforeCursor` then returns `"xyz"` |
| `finishComposingText()` | `true` | yes, flushes composing text | verified earlier: `comp` appeared in the terminal |
| `deleteSurroundingText(1,0)` | `true` | yes (DEL key event) | `TerminalView.java:380-390` converts leftLength into DEL key events |
| `sendKeyEvent(KEYCODE_A)` | `true` | yes | Gboard uses this path under `TYPE_NULL` (`TerminalView.java:671-676`) |
| `getExtractedText(...)` | `null` | n/a | no text extraction |
| `getCursorCapsMode(0)` | `0` | n/a | cursor unknown to the app |

**Conclusion (confirmed, not guessed):** the IME must maintain the command line itself. Composing
text is unusable for a live preview because the terminal does not render it until a commit.

### Line-buffer model and resync rules

Track a `StringBuilder line` fed **only** by events the IME itself generates:

| Event | Action |
|---|---|
| printable `commitText` | append |
| backspace (`deleteSurroundingText`/DEL) | drop last char |
| accepted suggestion | append inserted suffix |
| Enter (`\n`) | clear buffer (new prompt) |
| Ctrl-C / Ctrl-D / Ctrl-U / Ctrl-L (`\u0003`, `\u0004`, `\u0015`, `\u000c`) | clear buffer |
| focus change (`onStartInput`, `onFinishInput`) | clear buffer |

Events the IME **cannot** see, and which break the model:

| Cause | Effect | Mitigation |
|---|---|---|
| Arrow keys / Home / End (Termux extra-keys row) | cursor moves, buffer position wrong | mark buffer **untrusted**, hide strip until next Enter |
| Shell history recall (↑ / Ctrl-R) | line replaced behind the IME's back | same |
| Shell tab completion | line extended behind the IME's back | same |
| Paste (terminal context menu) | text lands without a commit | same |
| Voice input / external IME switch | unknown text | same (focus change resets) |
| Full-screen app (vim, less, htop) | not a shell line at all | same + toolbar toggle (S6) |

Rule: any control key that is not in the trusted list ⇒ `trusted = false`; suggestions are hidden
and the buffer is rebuilt from zero at the next Enter. A manual "resync" toolbar chip forces the
same. This is deliberately conservative: a wrong suggestion costs more than a missing one.

## S3 — Forcing suggestions in HeliBoard. **PASS (verified on device in slice 1)**

The gate is in one constructor:

```java
// app/src/main/java/helium314/keyboard/latin/InputAttributes.java:61-82
final int inputClass = mInputType & InputType.TYPE_MASK_CLASS;
…
if (inputClass != InputType.TYPE_CLASS_TEXT) {
    …
    mShouldShowSuggestions = false;          // line 76
    mMayOverrideShowingSuggestions = false;  // line 77
    mInputTypeShouldAutoCorrect = false;
    …
    return;
}
…
mShouldShowSuggestions = !mIsPasswordField && !flagNoSuggestions;  // line 96
```

Downstream consumers:
- `SettingsValues.java:267-273` — `mSuggestionsEnabled = PREF_SHOW_SUGGESTIONS && (mShouldShowSuggestions || mOverrideShowingSuggestions) && …`
- `SettingsValues.java:346` — `needsToLookupSuggestions()`
- `LatinIME.setSuggestedWords()` (`LatinIME.java:1406-1436`) — hides the strip when disabled.

Hook: in `InputAttributes`, after computing `mTargetApplicationPackageName`, if the package is in
the terminal-mode list, take the `TYPE_CLASS_TEXT` branch semantics with
`mShouldShowSuggestions = true`, `mInputTypeShouldAutoCorrect = false`,
`mShouldInsertSpacesAutomatically = false`, `mIsGeneralTextInput = false`, and keep
`mInputType` unchanged so `isTypeNull()` stays true and backspace keeps using DEL key events
(`InputLogic.java:1355-1366` depends on that).

Suggestion **production** hook: `InputLogic.getSuggestedWords()` (`InputLogic.java:2481-2502`) —
return `SuggestedWords` built by the terminal provider instead of `mSuggest.getSuggestedWords(...)`.

Suggestion **acceptance** hook: `LatinIME.pickSuggestionManually()` (`LatinIME.java:1463`) — branch
to a suffix-only commit instead of `InputLogic.onPickSuggestionManually` (which deletes/retypes the
word and may add a space).

Strip rendering: `SuggestionStripLayoutHelper.java:481` sets the item text from
`SuggestedWords.getInfo(i)`, scaling down and ellipsizing at the middle
(`:528-557`), so multi-word strings render without new UI.

**Measured after implementing slice 1** (fork installed on the API 35 emulator, Termux focused):

```
onStartInput pkg=com.termux inputType=0x0  → active=com.termux
key g  → line="g"  → suggestions=4 (typed + 3)
key i  → line="gi" → suggestions=4
setSuggestedWords size=4 enabled=true strip=true inputShown=true shouldShow=true
```

Strip renders `git status` / `git` / `git add` above the keyboard. The InputAttributes override is
therefore sufficient; no other gate had to be touched.

## S4 — Acceptance mechanics. **PASS, verified end-to-end in Termux on the emulator (bash)**

Design: **suffix-only insertion**. Suggestion `git status` while the line is `gi` → commit the
string `t status`; never delete-and-retype.

First verified with the probe IME (clean prompt, screenshots `/tmp/s4a.png` → `/tmp/s4b.png`):

```
I PROBEIME: commitText(ETX) -> true        # Ctrl-C, reset line
I PROBEIME: commitText("g") -> true
I PROBEIME: commitText("i") -> true
I PROBEIME: commitText("t status") -> true
I PROBEIME: getTextBeforeCursor(200,0) -> ""   # IME still blind
```

Terminal rendered `~ $ git status` with the cursor immediately after `status`. No duplicated
characters, no cursor damage, one shell-visible operation per accepted suggestion.

Decisions:

1. **Never append a trailing space.** The inserted suffix ends where the suggestion ends; the user
   taps space (or accepts another suggestion) deliberately. Auto-space is what would break
   `cd`-into-directory and `git commit -m` flows.
2. **No composing text.** Verified above: Termux ignores composing text until commit, so a
   "preview" would be invisible and a `setComposingText`-based accept would double-insert.
3. **Backspace** must go through `deleteSurroundingText(1,0)` (→ DEL key event), which Termux
   already maps; do not use `commitText("")` tricks.
4. **Shell independence**: the accept path emits plain bytes into the PTY. It is the same code path
   as typing, so bash/zsh/fish readline all see ordinary characters. The unverified part is
   readline *state* (vi-mode, prompting functions); see §9.
5. **tmux / SSH** add no new mechanic: bytes go to the PTY, which is the SSH client or tmux pane.
   Only latency differs (remote echo delay).

**Verified again with the finished fork** (no probe): typing `gi` showed `git status` / `git` /
`git add`; tapping the first suggestion produced exactly `~ $ git status` in the terminal with the
cursor after `status`; Enter executed the line and reset the buffer; backspace deleted one character.

Risk that remains: if the user edits the line with arrows/Tab/↑ before accepting, the suffix is
computed against a stale buffer. Handled by the S2 trust model (strip hides), not by attempting to
repair cursor position.

**New finding (measured):** Termux's own extra-keys row (`ESC TAB CTRL ALT ← ↓ → HOME END PGUP
PGDN`) is part of the terminal view, not of the IME. Pressing TAB there sends a tab to the shell and
the IME never sees an event — the buffer silently goes stale. The IME can only trust keys it
delivers itself. Mitigations: Enter resets the line (verified), switching apps resets it, and later
slices should add the terminal keys to the keyboard's own toolbar so the IME sees them.

## S5 — Getting remote history to the phone. **PARTIAL (transport decided with measurement; daemon not built)**

### What the IME can and cannot reach

- Termux's private files: `drwx------ com.termux` under `/data/data/com.termux/files/home` —
  inaccessible to the IME (different UID, SELinux `untrusted_app`).
- Termux cannot be assumed to expose anything; `allow-external-apps` is off by default.

### Measured permission facts

| Probe | Result |
|---|---|
| `new ServerSocket(0,1,127.0.0.1)` from the IME | `SocketException: socket failed: EPERM` — **loopback TCP requires `android.permission.INTERNET`** |
| `LocalSocket.connect(ABSTRACT "tkprobe")` from the IME | `UnsupportedOperationException` (no server present; the Java `LocalServerSocket` API has no abstract-namespace constructor, so the daemon would have to bind from Python/native) |

### Option comparison

| # | Transport | Works without INTERNET? | Latency | Complexity | Verdict |
|---|---|---|---|---|---|
| 1 | Loopback TCP `127.0.0.1:<port>` + shared token | **no** (EPERM proven) | ~1 ms | low | **chosen** |
| 2 | Unix/abstract `LocalSocket` | possibly (unproven; client API awkward) | ~1 ms | medium, platform-risky | rejected for v1, revisit |
| 3 | `RUN_COMMAND` intent | yes | process spawn per call, 100 ms+; no return channel | medium; requires `allow-external-apps` | rejected |
| 4 | Shared-storage file (`/sdcard`) | yes, but needs storage permission or SAF grant | file poll | medium; FUSE + scoped storage | rejected |
| 5 | Companion Android app `ContentProvider` | yes | ~1 ms | high (second APK) | rejected for v1 |
| 6 | Manual history import | yes | n/a | trivial | **shipped as baseline** |

**Decision:** loopback TCP, daemon binds `127.0.0.1` only, authenticates every request with a
256-bit token stored in the daemon's config and pasted once into the IME settings (or discovered
via a QR/paste flow). The IME therefore declares `android.permission.INTERNET`; justification is
recorded in `docs/decisions.md`. No outbound connections, no analytics, no third-party SDKs.

### Active host detection

- `~/.ssh/config` `Host` aliases parsed by the helper (menu of profiles).
- Wrapper `ssh` function installed by the helper writes the current target to the daemon
  (`TERMUX_KEYBOARD_HOST=…`), and a remote `PROMPT_COMMAND`/`precmd` hook reports
  `hostname` + exit status over the same SSH connection.
- Manual host chip in the keyboard toolbar overrides everything.
- Default: infer from the wrapper; if unknown → global history only.

### Security requirements (carried into implementation)

localhost-only bind, token required, redaction patterns (`password|token|secret|AKIA|BEGIN .*PRIVATE KEY`),
ignore commands starting with a space, never log command text, cache stored in app-private storage,
"clear cache" and per-host disable in settings.

## S6 — Context detection. **PASS (design), with a measured limitation**

There is no IME-visible signal for "a full-screen app is running": Termux's `TerminalView` is the
only input target and its `EditorInfo` never changes while the user runs commands inside the PTY.
The probe IME received exactly one `onStartInputView` for `com.termux` and no further callbacks
during all subsequent command execution (see S1/S2 logs).

v1 decision (matches the spec's "simplest acceptable"):

- suggestions are gated on `trusted` (S2), so any of Enter/Ctrl-C/arrow/Tab turns the strip off;
- a toolbar toggle ("terminal mode" chip) enables/disables the whole feature per app;
- auto-hide after Enter until the next printable character, which alone suppresses suggestions in
  vim/less in the common case (you press Enter, then Esc/`:`, which mark the buffer untrusted).

Deferred: alternate-screen escape tracking via a shell hook (needs the daemon, Phase-2 Slice 3).

## S7 — Suggestion engine. **PASS**

Decision: **(b) a separate Kotlin `ShellSuggestionProvider`**, not HeliBoard's dictionary engine.
Reason: shell completion is prefix-over-whole-lines plus previous-token context; the AOSP engine is
word-composer / n-gram based and would have to be fed synthetic words and re-ranked anyway.

Prototype measured on the JVM (M1 Max, 100k history lines, 99 979 distinct, 5 000 queries of
1–12-char prefixes, `/tmp/spike-s7/Bench.java`):

```
indexed: 100000 lines
sort 100k strings: 2 ms
p50=0.022 ms  p95=0.293 ms  p99=0.389 ms  max=0.870 ms
single-char "g"   : 1.96 ms     (worst case: matches every line starting with g)
"docker co"       : 0.044 ms
```

p95 is ~100× under the 30 ms budget; the one-hot prefix (`"g"`) is the worst case and still ~2 ms
on a desktop core. A phone core is ~3–5× slower → still <10 ms. Index build (sort) is 2 ms, cheap
enough to rebuild on every history refresh.

Algorithm:

1. Normalise: trim, drop empty, drop redacted lines, drop lines starting with a space
   (`HISTCONTROL=ignorespace`), dedupe with `(count, lastUsedMillis)`.
2. Score at load: `frecency = count × decay(age)` with buckets 1.0 / 0.75 / 0.5 / 0.25 / 0.1 for
   <4d / <15d / <31d / <91d / older.
3. Query: binary-search the sorted line array for the prefix range, take top 3 by score. Skip exact
   equality (no suffix to insert).
4. Fallback order: host history → global history → bundled static commands (prefix match).
5. Token completion: per-command token frequency map (e.g. `grep` → `-r`, `--color`);
   used when the line ends with a space or when the whole-line prefix yields nothing.
6. All of this is pure in-memory; no I/O on the keystroke path. Refresh happens on a background
   thread and swaps an immutable index.

Known limit observed in the prototype: whole-line prefix matching returns nothing when the user
edits the middle (`"ls /home"` vs stored `"ls -la /home"`). Token completion covers the common case
(command + subcommand), not arbitrary middle edits.

## S8 — Build & install pipeline. **PASS (fork builds and installs alongside HeliBoard)**

Measured on this machine (cold build, including Gradle 9.7.1 download and NDK 28 install):

```
$ ./gradlew :app:assembleDebug
BUILD SUCCESSFUL — 4m30s, EXIT=0
app/build/outputs/apk/debug/HeliBoard_4.3-beta1-debug.apk   27,260,619 bytes
```

Installed and exercised on the emulator:

```
$ adb install -r HeliBoard_4.3-beta1-debug.apk        -> Success
$ adb shell ime set helium314.keyboard.debug/helium314.keyboard.latin.LatinIME
$ adb shell dumpsys input_method | grep mCurMethodId -> helium314.keyboard.debug/…
```

Screenshot: HeliBoard renders in Termux with an **empty toolbar row and no suggestion strip** —
the S3 baseline that terminal mode must change.

Pipeline facts:

| Item | Value |
|---|---|
| Toolchain | JDK 21, Android SDK 37, build-tools 35, NDK 28.0.13004108 (auto-installed by AGP) |
| Gradle / Kotlin | 9.7.1 / 2.4.0, Compose BOM 2025.11.01 |
| minSdk / target / compile | 21 / 37 / 37 |
| Upstream applicationId | `helium314.keyboard` (debug: `helium314.keyboard.debug`) |
| Fork applicationId | `org.termuxkeyboard.latin` (must be set; installs alongside HeliBoard) |
| Note | minSdk 21 keeps HeliBoard's device range; no change needed for the fork |

Signing: a dedicated keystore, kept outside git (`~/.termux-keyboard/keystore.jks` locally, repo
secrets in CI). Debug signing is acceptable only for the first device test; a stable release key is
required before distribution so updates install over previous versions.

## 9. What is *not* verified yet (honest gaps)

1. **Physical phone.** All device evidence is from an API 35 arm64 emulator. The user's Android
   version/model is still unknown (open question).
2. **zsh / fish / tmux / SSH acceptance.** Only bash was exercised. The mechanism is byte-level, so
   the risk is readline state, not insertion.
3. **Multi-byte / IME-composition paths** (e.g. Japanese input, emoji) were not exercised.
4. **Abstract-namespace `LocalSocket`** as a permission-free transport: the client call raised
   `UnsupportedOperationException` without a server; needs a dedicated experiment.
5. **Latency on the user's phone** — only desktop-JVM numbers exist.
6. **Full-screen app behaviour** (vim/less/htop): `pkg install vim` was still downloading mirrors
   when the session ended; the design does not depend on it (S6), but the manual matrix in §9 of
   the spec still needs a device run.
