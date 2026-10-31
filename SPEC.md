# termux-keyboard — Spec

> Status: **SPEC ONLY. Do not build anything until Phase 0 and Phase 1 are complete and signed off in `PROGRESS.md`.**
> Audience: an autonomous coding agent that will investigate, decide, and implement.

## 1. Problem

On Android, Termux's terminal view tells the IME "no suggestions / no text field semantics". Result: no autosuggest, no autocomplete, no next-word prediction. The user types every command, path, and flag by hand on a phone keyboard, often inside an SSH session to a remote machine (reachable over Tailscale).

## 2. Goal

An Android keyboard (IME) that, when used in Termux:

1. Shows a **suggestion strip** while typing a shell command.
2. Suggests **common commands** out of the box (`cat`, `cd`, `pwd`, `ls`, `grep`, `git`, `ssh`, `docker`, `systemctl`, `tmux`, …) plus common flags and subcommands (`git commit -m`, `docker compose up -d`).
3. Suggests from **shell history** — local Termux history and, when SSH'd into a host, **that host's** history (`~/.bash_history`, zsh `~/.zsh_history`, fish history).
4. Acceptance of a suggestion types it into the terminal correctly (no duplicated chars, no broken cursor).
5. Ships as a **sideloadable APK**, distributed to the phone over **Tailscale** (no Play Store).

Non-goals (v1): swipe typing quality parity with Gboard, cloud sync, multi-language support beyond what the base keyboard already has, Play Store release.

## 3. Starting point

Fork **HeliBoard** — https://github.com/HeliBorg/HeliBoard (Kotlin/Java, AOSP-derived LatinIME, GPL-3.0, Gradle + NDK for native dictionary code).

Fork under the user's GitHub account as `termux-keyboard` (rename on fork). Keep `upstream` remote so HeliBoard updates can be merged. GPL-3.0 applies: keep license files and notices, and the fork must remain GPL-3.0.

Do **not** rely on this being the right base without Phase 0 — the base keyboard is a candidate, not a decision.

## 4. Phase 0 — Does this already exist? (FIRST TASK, time-box ~2h)

Before writing code, find out if a solution exists. Produce `docs/prior-art.md` with, for each candidate: what it does, last commit date, license, whether it works in Termux, whether it can use remote SSH history, and a verdict (use as-is / fork instead / borrow ideas / irrelevant).

Search GitHub, F-Droid, Reddit (r/termux, r/androidterminal), the Termux wiki, Termux GitHub issues/discussions, XDA, and Hacker News. Search terms: "termux autosuggest keyboard", "termux autocomplete keyboard", "terminal keyboard suggestions android", "IME shell history", "termux IME no suggestions TYPE_NULL", "keyboard bash history android".

Candidates to evaluate explicitly:

| Candidate | Why check |
|---|---|
| Gboard / SwiftKey in Termux | Does any stock IME give suggestions in Termux when Termux's input type is changed? (Termux `terminal-view` / `soft-keyboard` settings, `termux.properties`: look for any key controlling input type or `enforce-char-based-input`.) |
| Unexpected Keyboard | Popular with Termux users; no suggestions, but check forks/PRs/issues |
| Hacker's Keyboard | Full PC layout; check if it has suggestions in terminals |
| AnySoftKeyboard | Has suggestion engine + plugin/add-on dictionary system; check whether a custom shell dictionary add-on is feasible instead of forking HeliBoard |
| OpenBoard / FlorisBoard / FUTO Keyboard / Simple Keyboard | Alternative bases |
| Termux extra-keys + Termux:API / Termux:Widget | Partial workarounds |
| Shell-side autosuggestion (`zsh-autosuggestions`, `fish`, `atuin`, `mcfly`, `fzf` Ctrl-R, `ble.sh`) | Different approach: suggestions drawn by the shell itself, accepted via an extra key (→ / Tab). Evaluate as a **complement or fallback**, and quantify how good it is on a phone |
| Any existing "terminal IME", "SSH client with autocomplete" (e.g. Termius, JuiceSSH, ConnectBot) | Check whether they solve it natively |

Deliverable also includes a **recommendation**: (A) fork HeliBoard, (B) fork a different keyboard, (C) AnySoftKeyboard add-on, (D) shell-side only, (E) hybrid. Justify with evidence. If a good existing solution is found, stop and report to the user before building.

## 5. Phase 1 — Feasibility spikes (answer these with experiments, not guesses)

Write findings to `docs/spikes.md`. Each spike has a pass/fail result and evidence (logcat excerpts, code references).

**S1. Termux input type.** What `EditorInfo.inputType` / `imeOptions` / `IME flags` does Termux hand to the IME? (Read `TerminalView.onCreateInputConnection` in termux-app source; check `TYPE_NULL`, `TYPE_TEXT_FLAG_NO_SUGGESTIONS`, `TYPE_TEXT_VARIATION_VISIBLE_PASSWORD`.) Log `EditorInfo` in the IME's `onStartInput` and confirm on-device. Package name: `com.termux`.

**S2. What does the IME see?** Termux has no real editable text, so `getTextBeforeCursor` likely returns empty/garbage and `setComposingText` may be unsupported or flaky. Determine exactly which `InputConnection` calls work: `commitText`, `setComposingText`, `deleteSurroundingText`, `sendKeyEvent`, `getTextBeforeCursor`. Conclusion expected: the IME must **track the current command line itself** by observing its own keystrokes, and reset that buffer on Enter, Ctrl-C, Ctrl-D, Ctrl-U, Ctrl-L, and when focus changes. Define the failure modes (user edits the line using arrow keys, shell history recall via ↑, tab completion done by the shell, paste, voice input) and how the buffer resyncs or gracefully disables.

**S3. Forcing suggestions in HeliBoard.** HeliBoard suppresses suggestions for NO_SUGGESTIONS / password / null-type fields. Find where (likely `InputAttributes`, `SuggestedWords`, `Settings`/`SettingsValues` — verify, don't assume) and how to add a per-package override (`com.termux`, plus configurable list) so a "Terminal mode" turns suggestions on and uses the shell engine.

**S4. Acceptance mechanics.** Terminal wants raw bytes. Design the accept action as: suffix-only insertion (user typed `gi`, suggestion `git status` → send `t status`) rather than delete-and-retype, because backspaces in a terminal are shell-dependent. Verify with bash, zsh, fish, inside tmux, inside vim (must not fire in full-screen apps — see S6), and over SSH. Decide how multi-word completion and trailing space behave.

**S5. Getting remote history to the phone.** The IME app cannot read Termux's private files and has no direct view of the SSH session. Evaluate each, pick one, document trade-offs:

1. **Termux-side companion daemon** (Python/Go/Rust, installed in Termux) listening on `127.0.0.1:<port>` (or Unix socket in shared location, or Android `LocalSocket`); IME queries it. Daemon pulls histories by running `ssh <host> 'tail -n N ~/.bash_history'` using the user's existing SSH keys/config, caches them, refreshes in the background.
2. **Shell hook** on the remote (and local) via `PROMPT_COMMAND` / `precmd` / `preexec` that pushes each executed command to the daemon in real time through a reverse tunnel (`ssh -R`) or Tailscale address — gives live context and current host without polling.
3. **Termux `RUN_COMMAND` intent** (`com.termux.permission.RUN_COMMAND`, requires `allow-external-apps=true` in `~/.termux/termux.properties`) so the IME can ask Termux to run a script. Check latency and permission UX.
4. **Shared storage file** sync (`termux-setup-storage`, `/sdcard/...`) read by the IME. Check scoped-storage limits.
5. **Content provider / bound service** exposed by a small companion Android app.
6. **Manual import**: user pastes/imports a history file into the IME settings. Always provide this as the baseline fallback.

Also solve **"which host am I on right now?"** — the IME cannot see the session. Options: wrapper `ssh` function/alias in Termux that tells the daemon the active host; remote shell hook reporting hostname; a manual host chip in the keyboard toolbar; infer from the prompt via OSC escape sequences. Prefer automatic, keep manual override.

Security requirements for S5: history files contain secrets (tokens in commands, passwords in URLs). Default to local-only storage, localhost-only listeners, authenticated socket (shared token), no network exposure, configurable redaction rules (e.g. drop lines matching `password|token|secret|AKIA|-----BEGIN`), and an "ignore commands starting with a space" rule (matches `HISTCONTROL=ignorespace`). Never log history content.

**S6. Context detection.** Suggestions should only be active at a shell prompt. Detect/avoid full-screen apps (vim, less, htop, nano). Options: user toggle in toolbar, alternate-screen escape-sequence tracking via the shell hook, or heuristics. Simplest acceptable v1: a toolbar toggle + auto-reset on Enter.

**S7. Suggestion engine.** Decide between (a) feeding HeliBoard's native dictionary/n-gram engine with a generated `.dict` (use HeliBoard's dictionary tooling; check `dicttool`, personal dictionary, and "add words" paths) and (b) a separate Kotlin `ShellSuggestionProvider` that produces `SuggestedWords` for the strip. Expect (b) to win because shell completion is prefix-over-whole-lines and context-sensitive (previous tokens), not word-level. Define ranking: recency × frequency (frecency), prefix match on the whole line first, then token-level completion, then static command dictionary. Target latency < 30 ms per keystroke for 50k history lines.

**S8. Build & install pipeline.** Confirm the fork builds on this machine or CI (JDK/Android SDK/NDK versions per `build.gradle`). Choose a distinct `applicationId` (e.g. `org.termuxkeyboard.latin`, set via `applicationIdSuffix` or flavor) so it installs **alongside** HeliBoard without clobbering it. Set up signing (a persistent release-style keystore kept out of git; debug-signed is acceptable for v1 but a stable key is required so updates install over previous ones).

## 6. Phase 2 — Implementation (after Phase 1 sign-off)

Implement in vertical slices; each slice is demoable on-device.

**Slice 1 — Terminal mode + static commands.**
- Per-package override: suggestions enabled in `com.termux`.
- Line-buffer tracker (S2) with reset rules.
- Static command dictionary (see §7) via `ShellSuggestionProvider`.
- Suggestion strip shows top 3; tap accepts as suffix (S4).
- Settings page: "Terminal mode" with package list, enable toggle.

**Slice 2 — Local history.**
- Termux companion script (`termux-keyboard-helper`) that reads `~/.bash_history` / `~/.zsh_history` / fish history and exposes it (transport per S5).
- Frecency ranking, dedupe, redaction rules, ignore-space rule.
- Install via a one-liner (`curl … | sh` is **not** acceptable as the only path; also provide copy-paste commands and a `pkg`-installable or git-cloneable script).

**Slice 3 — Remote history over SSH.**
- Host profiles (alias → ssh target) auto-imported from `~/.ssh/config`.
- Active-host detection (S5) and per-host history namespaces; global fallback.
- Background refresh, offline cache, staleness indicator.
- Optional live push of commands via shell hook.

**Slice 4 — Quality.**
- Argument/path awareness: complete flags from history in context of the command; optional `cd`/`ls` path suggestions from history only (do **not** crawl the filesystem over SSH without explicit opt-in).
- Learn from accepted suggestions.
- Toolbar: terminal-mode toggle, host chip, Esc/Tab/Ctrl/arrow keys row (Termux users depend on these — check what Termux's own extra-keys row provides to avoid duplicating it, or integrate HeliBoard's toolbar/ctrl handling).
- Privacy settings: clear cache, per-host disable, redaction patterns.

**Slice 5 — Distribution.**
- GitHub Actions workflow that builds the APK on push/tag and uploads it as an artifact/release.
- Local Tailscale delivery doc: serve the APK from the dev machine (`tailscale serve` or `python3 -m http.server` bound to the tailnet interface; or `tailscale file cp`/Taildrop to the phone), with the exact steps to install and enable the keyboard (Settings → System → Keyboard → On-screen keyboard; allow "Install unknown apps" for the browser/Files app).
- `README.md` for the fork documenting install, helper setup, and security model.

## 7. Static command seed list

Ship a bundled, user-editable list with usage weights. At minimum:

- Navigation/files: `cd`, `ls`, `ls -la`, `pwd`, `cat`, `less`, `head`, `tail`, `tail -f`, `mkdir -p`, `rm`, `rm -rf`, `cp`, `mv`, `touch`, `find`, `ln -s`, `chmod`, `chown`, `du -sh`, `df -h`, `tree`
- Text: `grep`, `grep -r`, `rg`, `sed`, `awk`, `sort`, `uniq`, `wc -l`, `cut`, `xargs`, `echo`, `nano`, `vim`
- System: `ps aux`, `top`, `htop`, `kill`, `killall`, `systemctl status|start|stop|restart`, `journalctl -u`, `journalctl -f`, `sudo`, `whoami`, `uname -a`, `free -h`, `which`, `man`, `history`, `clear`, `exit`
- Network: `ssh`, `scp`, `rsync -avz`, `curl`, `wget`, `ping`, `ip a`, `ss -tulpn`, `tailscale status|ip|up`
- Git: `git status|add|commit -m|push|pull|log --oneline|diff|checkout|branch|stash|clone|rebase|merge`
- Dev: `docker ps|logs -f|compose up -d|compose down|exec -it`, `npm install|run dev|test`, `pnpm`, `python3`, `pip install`, `node`, `make`, `tmux new -s|attach -t|ls`
- Termux: `pkg install|update|upgrade`, `termux-setup-storage`, `termux-clipboard-get`, `termux-open`

Longer term, derive extra static suggestions from `bash-completion` / `fish` completion definitions or `tldr` pages, subject to license compatibility (note sources).

## 8. Constraints & decisions to record

- Min SDK / target SDK: follow HeliBoard's, but verify the user's Android version for sideload quirks (Android 14+ restricted settings for sideloaded IMEs/accessibility).
- No analytics, no telemetry, no INTERNET permission in the IME app if avoidable (HeliBoard itself ships without it — keep it that way; if transport needs network, prefer loopback via Termux helper and justify any permission added).
- Keep patches to upstream files minimal and isolated (new package, e.g. `helium/terminal/…`, hook points small) so upstream merges stay cheap. Record every touched upstream file in `docs/upstream-touchpoints.md`.
- Every decision (fork base, transport, engine) goes in `docs/decisions.md` as a short ADR: context, options, choice, consequences.

## 9. Testing plan

- Unit tests (JVM): line-buffer tracker, frecency ranking, redaction, history parsers (bash with `HISTTIMEFORMAT` timestamps `#1700000000`, zsh extended history `: 1700000000:0;cmd`, fish YAML-ish `- cmd:` format, multiline commands with `\` continuation).
- Instrumented/manual matrix on a real device: bash, zsh, fish × local, SSH, tmux-in-SSH; vim/less behavior; paste; ↑ history recall; tab completion; Ctrl-C.
- Performance: 100k-line history, p95 suggestion latency < 30 ms on the user's phone.
- Security checks: helper rejects non-localhost, rejects missing token; redaction verified; no history in logcat (`adb logcat | grep` sweep).

## 10. Definition of done (v1)

1. In Termux, typing `gi` shows `git`, `git status`, … ; tapping inserts the rest correctly.
2. After SSH'ing to a configured host, typing a few characters of a command previously run on that host surfaces it.
3. APK is built by CI, downloadable over Tailscale, installs next to HeliBoard, and survives updates (stable signing key).
4. Documented setup takes a new user < 10 minutes.
5. `docs/prior-art.md`, `docs/spikes.md`, `docs/decisions.md`, `docs/upstream-touchpoints.md` all exist and are current.

## 11. Working agreements for the agent

- Start with Phase 0. Report the recommendation to the user (summary in `PROGRESS.md`) before Phase 2.
- Cite sources (URLs, file paths + line numbers) for every claim about Termux or HeliBoard internals. Verify against current code; this spec's file/class names are hints, not facts.
- Don't push or open PRs to upstream HeliBoard or Termux. Fork creation and pushes to the user's own fork are fine once Phase 0 recommends proceeding.
- Never commit keystores, tokens, or real history files. Use fixtures with fake data.
- Ask the user only for things you cannot determine: Android version, phone model, preferred shell, SSH hosts for testing, whether they will install Termux:API.
