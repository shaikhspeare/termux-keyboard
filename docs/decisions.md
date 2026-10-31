# Decisions (ADRs)

Format: context → options → choice → consequences. Superseded decisions stay, marked as such.
Evidence lives in `docs/prior-art.md` and `docs/spikes.md`.

---

## ADR-001 — Base keyboard

**Context.** We need an IME we can extend with a shell suggestion strip, on GPL-compatible terms,
that survives upstream updates. Termux hands the IME `InputType.TYPE_NULL`, so every candidate needs
the same IME-side override.

**Options.** Fork HeliBoard · fork Unexpected Keyboard · fork FlorisBoard · AnySoftKeyboard add-on ·
write from scratch.

**Choice. Fork HeliBoard.**

**Consequences.**
- Only active AOSP-LatinIME fork (release 4.3-beta1 2026-10-18, commits 2026-10-25); merging upstream
  stays cheap.
- The suggestion strip and `SuggestedWords` model already exist; terminal mode is an override in
  `InputAttributes` plus one provider, not a re-architecture.
- GPL-3.0, matching the spec; AOSP Apache-2.0 notices are already carried.
- Cost: HeliBoard's suggestion pipeline is word-composer based, so terminal mode must bypass it
  (`InputLogic.getSuggestedWords`, `LatinIME.pickSuggestionManually`), and the fork must keep those
  two hook points small.
- Rejected: AnySoftKeyboard add-ons are data-only (`AddOnsFactory` parses XML, no code loading);
  FlorisBoard ships no suggestions; FUTO's licence forbids the fork; OpenBoard and Hacker's Keyboard
  are unmaintained; Unexpected Keyboard's engine is word-scoped with a custom canvas UI.

---

## ADR-002 — Suggestion surface

**Context.** Whole-line shell suggestions must appear above the keyboard with minimal UI work.

**Options.** (a) New custom strip view; (b) reuse HeliBoard's `SuggestionStripView` with a custom
`SuggestedWords` producer; (c) feed HeliBoard's dictionary engine.

**Choice. (b) reuse `SuggestionStripView`, produce suggestions with a separate
`ShellSuggestionProvider`.**

**Consequences.**
- The strip already renders arbitrary `CharSequence`s, scales them down and ellipsizes at the middle
  (`SuggestionStripLayoutHelper.java:481,528-557`), so multi-word completions need no new widget.
- Theme, RTL, tap handling and the "more suggestions" popup come for free.
- Cost: long commands are ellipsized in the middle; v1 accepts that for the top-3 row.
- Rejected (c): the dictionary engine is word/ngram based; shell completion is prefix-over-lines and
  context-sensitive, and ranking would have to be redone anyway (measured provider beats the target
  by ~100×, see S7).

---

## ADR-003 — Where the command line comes from

**Context.** `getTextBeforeCursor()` returns `""` at all times in Termux (measured, S2). Composing
text never reaches the terminal until committed.

**Options.** (a) Read the InputConnection; (b) track keystrokes in the IME; (c) ask Termux to expose
the screen; (d) a shell hook that reports the current line.

**Choice. (b) track keystrokes in the IME, with an explicit trust model.**

**Consequences.**
- Works with stock Termux, no Termux fork, no `allow-external-apps`, no shell cooperation.
- Any key the IME cannot model (arrows, Tab, ↑ recall, paste, external IME) marks the buffer
  untrusted: the strip hides and the buffer resets at the next Enter. Missing a suggestion is
  cheap; a wrong suffix is not.
- (d) is deferred to Slice 3 as an optional accuracy upgrade via the helper's `preexec`/
  `PROMPT_COMMAND` hook, not a dependency.
- Rejected (c): Termux does not expose the emulator screen to IMEs, and emulating that requires
  patching Termux itself — out of scope and fragile.

---

## ADR-004 — Accepting a suggestion

**Context.** Terminals interpret backspace differently across shells and line editors; a
delete-and-retype accept can corrupt the line.

**Options.** (a) Replace the whole line (Ctrl-U + type); (b) suffix-only insertion; (c) composing
text + commit.

**Choice. (b) suffix-only insertion via `commitText(suffix, 1)`, no trailing space, no composing
text.**

**Consequences.**
- Verified on device in bash: `gi` + `commitText("t status")` → `git status` with the cursor after
  `status`; one shell-visible operation, no duplicated chars.
- Works identically in zsh/fish/tmux/SSH because it is plain bytes into the PTY.
- Requires the line buffer to be exactly correct, which is why ADR-003's trust model gates the strip.
- No auto-appended space: the user decides when the command is complete.

---

## ADR-005 — Transport between the IME and Termux-side history

**Context.** The IME cannot read Termux's private files. Measured: creating a loopback TCP socket
without `android.permission.INTERNET` fails with `EPERM`. The spec prefers loopback and asks for a
justification if a permission is added.

**Options.** Loopback TCP + token · abstract `LocalSocket` · `RUN_COMMAND` intent · shared-storage
file · companion app `ContentProvider` · manual import only.

**Choice. Loopback TCP on `127.0.0.1` with a shared 256-bit token, plus manual import as the
always-available baseline.**

**Consequences.**
- The IME declares `android.permission.INTERNET`. Justification: Android gates *all* sockets,
  including loopback, on that permission (measured). The app contains no outbound network code, no
  telemetry, no third-party SDK, and lists no other network capability.
- Daemon binds `127.0.0.1` only, requires the token on every request, is opt-in, and is documented
  as a separate trust domain (it can read history and run `ssh`).
- Abstract `LocalSocket` remains the permission-free fallback to investigate later; the client call
  is unproven (S5) and the Java server API has no abstract-namespace constructor.
- Rejected: `RUN_COMMAND` (per-call process spawn, no return channel, needs `allow-external-apps`);
  shared storage (FUSE cannot host sockets; scoped-storage friction); companion app (second APK).

---

## ADR-006 — History sources, parsing and redaction

**Context.** Local and remote histories come in different formats and contain secrets.

**Options.** Parse in the IME · parse in the helper and send normalised JSON · send raw files.

**Choice. The helper parses and normalises; the IME receives `{line, count, lastUsed}` records.**

**Consequences.**
- IME stays small and permission-free beyond the transport; one place to fix format bugs.
- Supported: bash `HISTTIMEFORMAT` stamps (`#1700000000`), zsh extended (`: 1700000000:0;cmd`),
  fish (`- cmd:` YAML-ish), multi-line continuations (`\`).
- Redaction (default on): drop lines matching `password|passwd|token|secret|AKIA|-----BEGIN`, drop
  lines starting with a space (`HISTCONTROL=ignorespace`), never log contents.
- Cache is app-private, capped (e.g. 50k lines per host), and clearable from settings.

---

## ADR-007 — "Which host am I on?"

**Context.** The IME cannot see the SSH session.

**Options.** Parse the prompt via OSC escapes · ask the shell via a hook · wrapper `ssh` function ·
manual chip.

**Choice. Wrapper `ssh` first, remote hook second, manual chip always.**

**Consequences.**
- The helper installs a shell function that records the target before exec'ing the real `ssh`, and
  restores the local profile on exit; a remote `PROMPT_COMMAND`/`precmd` hook can correct it and
  reports the hostname over the same connection.
- Manual chip in the toolbar overrides inference for the current session.
- If detection fails, suggestions fall back to global history rather than guessing a host.

---

## ADR-008 — Suppressing suggestions outside a shell prompt

**Context.** vim/less/htop share the same `EditorInfo`; the IME gets no callback when a full-screen
app starts (measured: one `onStartInputView` for `com.termux`, none afterwards).

**Options.** Detect alternate screen via shell hook · per-app toggle only · trust-model heuristics ·
always on.

**Choice. Toolbar toggle + trust model: suggestions only while the buffer is trusted, and the
buffer is reset by Enter/Ctrl-C/arrows/Tab.**

**Consequences.**
- In practice the strip disappears as soon as the user presses Enter (running `vim`) and stays off
  until the next trusted line.
- Alternate-screen tracking becomes an optional Slice-3 upgrade when the helper is installed.
- No global "always suggest" mode that would insert commands into vim's insert buffer.

---

## ADR-009 — Distribution

**Context.** Sideloaded APK over Tailscale, installable next to HeliBoard, updates must install
over previous versions.

**Options.** Play Store · F-Droid · GitHub Actions artifact/release · local build.

**Choice. GitHub Actions builds on push/tag and attaches the APK to a release; delivery over
Tailscale (`tailscale serve` or Taildrop).**

**Consequences.**
- Stable signing key in CI secrets; `applicationId` `org.termuxkeyboard.latin` so HeliBoard can stay
  installed.
- The keystore never enters git; the debug build is only for local device tests.
- README documents the Android 14+ "restricted settings" step for sideloaded IMEs.


---

## ADR-010 — History ingestion (slice 2)

**Context.** Live shell history needs a Termux-side helper, which needs `INTERNET` for loopback
(ADR-005). Shipping nothing until the helper exists would leave the keyboard with only the static
list.

**Options.** Wait for the helper · ship manual import only · ship both at once.

**Choice. Ship manual import first (SAF file pick, bash/zsh/fish parsers, frecency index); feed the
same ingestion path from the helper in slice 3.**

**Consequences.**
- Useful today with zero permissions and no daemon; the spec also asks for manual import as the
  permanent baseline.
- Only parsed, redacted entries are persisted (TSV), never the raw history file.
- The parser is one Kotlin implementation, unit-tested; the helper will hand it raw text.
- Cost: no freshness — the user re-imports when they want newer history.
