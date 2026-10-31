# Terminal-mode mock

Static, dependency-free mock of the keyboard's terminal mode, for reviewing behaviour before any
Android code is written. Nothing here talks to a real device or a real shell; the "history" is a
fixture in `history.js`.

Open it from a phone or laptop on the tailnet:

```
https://shayks-macbook-pro.tail0ea196.ts.net:8787/mocks/keyboard/
```

Local: `http://127.0.0.1:8787/mocks/keyboard/` (`python3 -m http.server 8787 --directory .` from the
repo root).

## What it is meant to answer

1. **Ranking.** Type `gi` on the local host, then `ssh web1` + Enter and type `docker co`. The strip
   shows the top 3 by frecency (count × age bucket), host history first, global second, bundled
   static list only to fill gaps.
2. **Suffix-only accept.** Tap a suggestion while the line reads `gi` and the chrome flashes
   `commitText("t status")` — only the missing characters are sent, no delete-and-retype.
   The typed part of each chip is dimmed and the inserted part is bold, so the accept is legible
   before you tap.
3. **Trust model.** Press `TAB`, an arrow, or one of the *simulate* buttons in Controls. The strip
   is replaced by a warning; it returns only after Enter (or *Resync*). That is the behaviour when
   the shell changes the line behind the IME's back.
4. **Host detection.** `ssh web1` + Enter switches the prompt and the host chip; `exit` returns to
   local. The toolbar chip cycles `local → web1 → global` for manual override.
5. **Helper absence.** *Controls → Helper online* off: suggestions collapse to the bundled static
   list, exactly what a user without the Termux helper sees.
6. **Terminal mode off.** No strip at all — stock HeliBoard in Termux, the baseline being fixed.

`Controls` also shows the live IME state (buffer, trusted flag, host) and the `InputConnection` log,
which is what the implementation will actually send.

## Files

- `index.html` — phone frame, terminal, Termux extra-keys row, IME.
- `styles.css` — dark/light palettes, key geometry, strip.
- `app.js` — line buffer, trust model, ranking, key handling.
- `history.js` — fixture corpora and the bundled static command list.

## Limits

This is a UX prototype: no Android, no real ranking data, no helper. Bucket decay, host fallback and
the trust rules are the ones specified in `docs/spikes.md` (S2, S5, S7); anything visual is a
proposal, not a commitment.
