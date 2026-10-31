# probe-ime

A deliberately dumb IME used to measure what Termux tells a keyboard, and what an
`InputConnection` actually delivers. It is test scaffolding for `docs/spikes.md`, not part of the
keyboard.

## What it does

- Logs `EditorInfo` in `onStartInput` / `onStartInputView` (input type, class, variation, flags,
  `imeOptions`, package, `fieldId`, initial selection).
- Exposes one command per `InputConnection` behaviour, callable from the host over an exported
  broadcast receiver:
  `g`/`i`/`a`/`b` (`commitText` one char), `suffix` (`commitText("t status")`), `ENTER`,
  `ctrl` (`commitText("\u0003")`), `COMP` (`setComposingText` + `finishComposingText`),
  `componly` (`setComposingText` without finishing), `DEL` (`deleteSurroundingText`), `KEYA`
  (`sendKeyEvent`), `READ` (`getTextBeforeCursor`/`getTextAfterCursor`/`getSelectedText`), `SEL`
  (`getExtractedText` + caps mode), `sock` / `lserver` / `lsock` (socket permission probes),
  `CLR` (clear the on-screen log).

## Build

```sh
./tools/probe-ime/build.sh
```

Uses only the Android SDK (`aapt2`, `d8`, `apksigner`) and `javac` — no Gradle, no network.

## Drive it

```sh
adb install --no-incremental -r -t tools/probe-ime/build/probe.apk
adb shell ime enable dev.probe.ime/.ProbeIME
adb shell ime set dev.probe.ime/.ProbeIME
adb logcat -c
adb shell am start -n com.termux/.app.TermuxActivity
adb shell am broadcast -a dev.probe.ime.CMD -p dev.probe.ime --es cmd READ
adb logcat -d -s PROBEIME:I
```

`cmd` values are single tokens; that is why `suffix` exists instead of sending a string with spaces
through `am broadcast`.

## Warning

It is an exported receiver with no permission check and logs everything it sees to logcat. Install
it on a test device only.
