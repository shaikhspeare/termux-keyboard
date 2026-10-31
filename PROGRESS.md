# Progress

Agent: update this file at the end of every work session. Keep it short.

## Status
- [x] Phase 0 — prior art (`docs/prior-art.md`) + recommendation
- [x] Phase 1 — spikes S1–S8 (`docs/spikes.md`)
- [x] User sign-off on approach (2026-10-31: "ok build it")
- [x] Slice 1 — terminal mode + static commands
- [x] Slice 2 — local history (manual import path; Termux helper not yet)
- [ ] Slice 3 — remote history over SSH
- [ ] Slice 4 — quality
- [x] Slice 5 — CI + Tailscale distribution (helper still missing, see slice 3)

## Recommendation (Phase 0)
**Fork HeliBoard (A) and add an IME-side shell suggestion strip; treat shell-side autosuggestion as
a complement, not the solution (E hybrid).**

- No existing solution satisfies the goal. No IME reads remote host history; all AOSP-derived
  keyboards suppress suggestions for Termux's `TYPE_NULL` field. Termux's own request for this is
  open since 2019 (termux/termux-app#1089).
- HeliBoard is the only actively maintained AOSP-LatinIME fork, one gate disables suggestions for
  Termux (`InputAttributes.java:61-82`), and the strip already renders arbitrary strings.
- Termux gives the IME no text (`getTextBeforeCursor()` is always `""`), so the IME must track the
  command line itself and accept suggestions as suffix-only commits — both verified on device.
- Remote history needs a Termux-side helper; loopback TCP requires `android.permission.INTERNET`
  (measured `EPERM`), justified in `docs/decisions.md` ADR-005.

Full reasoning: `docs/prior-art.md` §4, `docs/spikes.md`, `docs/decisions.md`.

## Slice 1 — what works (verified on the API 35 emulator, Termux 0.118.3)

- `terminal/` package: `TerminalMode` (per-package opt-in, default `com.termux`), `ShellLine`
  (tracked line + trust model), `ShellSuggestions` (static seed list, prefix match, weight order),
  `TerminalInputHandler` (bytes out: chars, DEL, Enter, Tab, Esc, arrows, Ctrl combos).
- Hook points in upstream: `InputAttributes` (one branch), `LatinIME.onEvent` /
  `onTextInput` / `pickSuggestionManually` / lifecycle, `InputLogic.getSuggestedWords`.
- Settings → Terminal mode: on/off + comma-separated package list.
- Measured: `gi` → `git status` / `git` / `git add`; tap inserts only `t status`; Enter executes and
  resets; backspace deletes one char; whole line stays exactly in sync with the terminal.
- Known gap: Termux's own extra-keys row (Tab/arrows/ESC) bypasses the IME, so those keys can stale
  the buffer. Enter and app switches reset it. Fix belongs to slice 4 (own toolbar keys).

## Slice 2 — history (verified on the API 35 emulator)

- `HistoryFormat` parses bash (`#ts`), zsh extended (`: ts:dur;cmd`), fish (`- cmd:` / `when:`) and
  `\` continuations; drops redacted lines (password/token/secret/AKIA/private key), lines starting
  with a space, empties and >512-char lines; dedupes with `(count, lastUsed)`.
- `HistoryIndex`: sorted array + binary search on the line prefix, frecency ranking
  (`count × age bucket`), ties resolved alphabetically.
- `HistoryStore`: persists only the parsed, redacted entries (TSV in app-private storage), never the
  raw file; import merges with what is already there; clear wipes it.
- Settings → Terminal mode → *Import history file* (SAF picker) and *Clear imported history*.
- Unit tests: `app/src/test/java/helium314/keyboard/terminal/HistoryTest.kt` (10 tests, green).
- Device check: seeded 3 `kubectl …` entries, typed `ku` → `kubectl get pods -n prod` offered and
  accepted; only the suffix left the keyboard.

Known limitations (all documented in `docs/spikes.md`):
- long commands collapse the strip to one chip plus the "…" more-suggestions popup (HeliBoard's
  layout gives up at < 70 % text scale);
- Termux's own extra-keys row bypasses the IME, so Tab/arrows can stale the line;
- there is no Termux-side helper yet, so history is whatever the user imports.

## Slice 5 — distribution

- `.github/workflows/build.yml`: unit tests + APK build on push/PR, artifact upload, release
  attachment on `v*` tags, optional release signing through repository secrets, debug fallback.
- `docs/install.md`: Tailscale download, `tailscale serve`, Taildrop, adb, enabling the IME,
  Android 14 restricted settings, history import, keystore/update rules.
- The built APK is downloadable over the tailnet today:
  `https://shayks-macbook-pro.tail0ea196.ts.net:8787/app/build/outputs/apk/debug/HeliBoard_4.3-beta1-debug.apk`

## Open questions for the user
1. Phone model + Android version (for the on-device matrix and the Android 14+ sideload
   "restricted settings" step).
2. Preferred shell(s) on the phone and on the remote hosts (bash / zsh / fish) — affects the
   history parser fixtures and the acceptance matrix.
3. SSH hosts to test against (`~/.ssh/config` aliases or Tailscale names), or use fictional
   fixtures only.
4. Acceptable to add `android.permission.INTERNET` to the IME for loopback-only helper traffic?
   (Alternative: no helper, static commands + manual import only.)
5. Install Termux:API? Not required by the chosen design; only relevant if we later want
   `RUN_COMMAND` transport.

## Log
_newest first_

### 2026-10-31 — Slice 1 shipped
- Fork `shaikhspeare/termux-keyboard` created from HeliBoard `44ddb1a`; project files merged into
  the fork so docs and code live in one repo; `upstream` remote added.
- `applicationId` → `org.termuxkeyboard.latin` (debug `.debug`), provider authorities made
  package-scoped so the fork installs next to HeliBoard.
- Terminal mode implemented and verified on the emulator (see "Slice 1 — what works").
- `docs/upstream-touchpoints.md` written; `docs/spikes.md` S3/S4 updated with measured results.

### 2026-10-31 — Interactive behaviour mock
- `mocks/keyboard/` — dependency-free phone-frame mock: line buffer, frecency ranking, suffix-only
  accept, trust model, host switching, helper-offline and terminal-mode-off states, plus a Controls
  sheet with live IME state and the InputConnection log.
- Served on the tailnet: `https://shayks-macbook-pro.tail0ea196.ts.net:8787/mocks/keyboard/`
  (`python3 -m http.server 8787 --bind 127.0.0.1` behind `tailscale serve --https=8787`).
- Verified in a headless browser: `gi` → `git status` / `git diff` / `git log --oneline -10`;
  accept flashes `commitText("t status")`; TAB hides the strip until Enter; `ssh web1` switches the
  prompt and host corpus; helper-offline falls back to the static list.

### 2026-10-31 — Phase 0 + Phase 1 complete
- Phase 0 research: 5 parallel scouts + direct source verification; `docs/prior-art.md` written,
  20+ candidates with licence/date/Termux/remote-history verdicts. Verdict: no existing solution.
- Built a probe IME (`tools/probe-ime/`) and ran it against Termux 0.118.3 on an API 35 arm64
  emulator: `inputType=0x0` (or `0x80090` with `enforce-char-based-input=true`), both class 0;
  `getTextBeforeCursor()` always `""`; `commitText` suffix insertion verified in bash.
- Cloned and read `termux-app` @ `8629e63` and `HeliBoard` @ `44ddb1a`; every hook point cited by
  file:line in `docs/spikes.md`.
- Built HeliBoard 4.3-beta1 from source (BUILD SUCCESSFUL, 4m30s, 27.3 MB APK) and installed it on
  the emulator; confirmed the empty suggestion strip in Termux (S3 baseline).
- S7 benchmark: 100k-line history, p95 0.29 ms, max 0.87 ms per keystroke (desktop JVM).
- `docs/spikes.md` (S1–S8) and `docs/decisions.md` (ADR-001…009) written.
- Not verified: physical phone, zsh/fish/tmux/SSH acceptance, abstract `LocalSocket` transport.
  Listed in `docs/spikes.md` §9.

### 2026-10-31 — Repo seeded
- SPEC.md, README.md, PROGRESS.md only. No code.
