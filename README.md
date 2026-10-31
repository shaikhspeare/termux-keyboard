# termux-keyboard

Goal: a HeliBoard-based Android keyboard with shell autosuggestions in Termux — common commands plus local and SSH-host bash history — delivered as a sideloaded APK over Tailscale.

- Full spec for the implementing agent: [SPEC.md](SPEC.md)
- Progress tracker: [PROGRESS.md](PROGRESS.md)
- Phase 0 prior art + recommendation: [docs/prior-art.md](docs/prior-art.md)
- Phase 1 spikes S1–S8: [docs/spikes.md](docs/spikes.md)
- Decisions (ADRs): [docs/decisions.md](docs/decisions.md)
- Behaviour mock for review: [mocks/keyboard](mocks/keyboard/README.md) — served on the tailnet at
  `https://shayks-macbook-pro.tail0ea196.ts.net:8787/mocks/keyboard/`

Status: Phase 0 and Phase 1 are complete; Phase 2 (the fork) is gated on sign-off in
`PROGRESS.md`.

Upstream: https://github.com/HeliBorg/HeliBoard (GPL-3.0). The fork has not been created yet; that
is part of the spec (after Phase 0).

## Mock server

```sh
python3 -m http.server 8787 --bind 127.0.0.1 --directory .
tailscale serve --bg --https=8787 http://127.0.0.1:8787
```
