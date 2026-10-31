# termux-keyboard

Goal: a HeliBoard-based Android keyboard with shell autosuggestions in Termux — common commands plus local and SSH-host bash history — delivered as a sideloaded APK over Tailscale.

- Full spec for the implementing agent: [SPEC.md](SPEC.md)
- Progress tracker: [PROGRESS.md](PROGRESS.md)
- Phase 0 prior art + recommendation: [docs/prior-art.md](docs/prior-art.md)
- Phase 1 spikes S1–S8: [docs/spikes.md](docs/spikes.md)
- Decisions (ADRs): [docs/decisions.md](docs/decisions.md)
- Behaviour mock for review: [mocks/keyboard](mocks/keyboard/README.md) — served on the tailnet at
  `https://shayks-macbook-pro.tail0ea196.ts.net:8787/mocks/keyboard/`
- Install guide (sideload, enable, history import, updates): [docs/install.md](docs/install.md)
- Upstream touchpoints: [docs/upstream-touchpoints.md](docs/upstream-touchpoints.md)

Status: Phase 0 and Phase 1 are complete; Phase 2 (the fork) is gated on sign-off in
`PROGRESS.md`.

Upstream: https://github.com/HeliBorg/HeliBoard (GPL-3.0). The fork has not been created yet; that
is part of the spec (after Phase 0).

## CI

`.github/workflows/build.yml` builds on every push and pull request, runs the terminal unit tests,
uploads the APK as an artifact, and attaches it to a GitHub release for `v*` tags. If
`TERMUX_KEYBOARD_KEYSTORE_BASE64` + password/alias/key-password secrets are present it signs a
release build (update-safe); otherwise it falls back to the debug APK and warns.

## Install (sideload)

The fork installs next to HeliBoard: its `applicationId` is `org.termuxkeyboard.latin` and its
providers are package-scoped.

```sh
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/HeliBoard_4.3-beta1-debug.apk
adb shell ime enable org.termuxkeyboard.latin.debug/helium314.keyboard.latin.LatinIME
adb shell ime set org.termuxkeyboard.latin.debug/helium314.keyboard.latin.LatinIME
```

On a phone: Settings → System → Languages & input → On-screen keyboard → enable "Termux Keyboard",
then pick it as the current keyboard. Android 14+ requires allowed "restricted settings" for
sideloaded IMEs in some builds.

## Terminal mode

Enabled by default for `com.termux` (Settings → Terminal mode). Terminal apps and their package
list, plus history import, live on that screen.

**What it does.** Termux tells the IME "no text field" (`InputType.TYPE_NULL`), which is why no
stock keyboard suggests anything there. Terminal mode overrides that for the configured packages,
tracks the command line from the keys it sends, and shows prefix completions in the normal
suggestion strip. Accepting a suggestion sends **only the missing suffix** — the terminal is never
rewritten, so bash/zsh/fish/tmux/SSH all behave as if the user typed the characters.

**Trust model.** Any key that can move the cursor or replace the line behind the keyboard's back
marks the line untrusted: suggestions hide until Enter starts a new line. Note that Termux's own
extra-keys row (Tab, arrows, Ctrl) is part of the terminal, not of the keyboard, so the keyboard
cannot see those keys — press Enter to resync after using them.

## History and privacy

- *Settings → Terminal mode → Fetch history from Termux* pulls local history — and the history of
  the host you are ssh'd into — from `helper/termux-keyboard-helper`, a small loopback daemon you
  start once in Termux ([helper/README.md](helper/README.md)).
- *Settings → Terminal mode → Import history file* merges a bash, zsh or fish history file into the
  suggestions, for when you do not want a daemon.
- Only parsed entries are stored, in app-private storage, and lines matching
  `password|passwd|token|secret|api_key|AKIA|BEGIN … PRIVATE KEY` plus lines starting with a space
  are dropped before anything is written.
- The keyboard declares `INTERNET` solely to reach that helper on `127.0.0.1` (Android refuses even
  loopback sockets without it), allows cleartext only for loopback via a network-security config,
  and contains no telemetry or outbound connections. The helper is opt-in and off by default.

## Known limitations

- Long commands collapse the strip to one suggestion plus the "…" popup; short ones (e.g. `git …`)
  show three.
- History refresh happens when the keyboard opens (at most once a minute), not per keystroke.
- No terminal toolbar keys yet; Termux's own extra-keys row covers Esc/Tab/Ctrl/arrows.

## Mock server

```sh
python3 -m http.server 8787 --bind 127.0.0.1 --directory .
tailscale serve --bg --https=8787 http://127.0.0.1:8787
```
