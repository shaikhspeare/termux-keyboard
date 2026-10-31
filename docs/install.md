# Install

The keyboard is a sideloaded APK. It installs **next to** HeliBoard (`applicationId`
`org.termuxkeyboard.latin`), so nothing is clobbered.

## 1. Get the APK onto the phone

Any of these works; the first is the one this project uses.

**Tailscale, from the dev machine (served for this session):**

```
https://shayks-macbook-pro.tail0ea196.ts.net:8787/app/build/outputs/apk/debug/HeliBoard_4.3-beta1-debug.apk
```

Open the URL on the phone (Tailscale must be up on both ends) and download.

**Tailscale Serve, permanently:**

```sh
python3 -m http.server 8787 --bind 127.0.0.1 --directory .      # from the repo root
tailscale serve --bg --https=8787 http://127.0.0.1:8787
```

**Taildrop:**

```sh
tailscale file cp app/build/outputs/apk/debug/HeliBoard_4.3-beta1-debug.apk phone:
```

**GitHub Actions:** every push builds an APK artifact; tagged builds attach it to a release.

**Over adb (USB or `adb connect` over the tailnet):**

```sh
adb install -r app/build/outputs/apk/debug/HeliBoard_4.3-beta1-debug.apk
```

## 2. Enable the keyboard

1. Android may ask to allow installing unknown apps for the browser/Files app — allow it once.
2. Settings → System → Languages & input → On-screen keyboard → **Termux Keyboard** → enable.
3. Some Android 14+ builds hide sideloaded IMEs behind *Allow restricted settings*
   (App info → ⋮ → Allow restricted settings).
4. Pick "Termux Keyboard" as the current keyboard.

## 3. Use it in Termux

Open Termux and type the first letters of a command. The strip above the keyboard shows prefix
completions; tapping one sends only the missing suffix. `Enter` starts a new line and resyncs.

Settings → **Terminal mode** has the on/off switch, the package list (default `com.termux`) and
history import.

## 4. History

*Import history file* accepts a bash `~/.bash_history`, zsh `~/.zsh_history` or fish
`fish_history`. Transfer the file however you like (`tailscale file cp`, `adb pull`, a cloud drive)
and pick it in the dialog. Only parsed, redacted commands are stored.

Live history from a running Termux session (and per-host history over SSH) needs the Termux-side
helper, which is slice 3 and not in this build.

## 5. Updates

Android only installs an update signed with the same key. CI signs with the keystore in the
repository secrets (`TERMUX_KEYBOARD_KEYSTORE_BASE64` plus password/alias/key-password); without
those it falls back to the debug APK, which is fine for a one-off but not for updates.

To create your own key once:

```sh
keytool -genkeypair -keystore ~/.termux-keyboard/sideload.jks -alias sideload \
  -keyalg RSA -keysize 4096 -validity 10000 -storepass "$(openssl rand -hex 16)"
```

Keep the keystore and passwords out of git. For local builds:

```sh
export TERMUX_KEYBOARD_KEYSTORE=$HOME/.termux-keyboard/sideload.jks
export TERMUX_KEYBOARD_KEYSTORE_PASSWORD=...
export TERMUX_KEYBOARD_KEY_ALIAS=sideload
export TERMUX_KEYBOARD_KEY_PASSWORD=...
./gradlew :app:assembleRelease
```
