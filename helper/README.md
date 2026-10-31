# termux-keyboard-helper

Feeds shell history to the keyboard automatically, so nothing has to be exported or imported by
hand.

It runs inside Termux, binds `127.0.0.1` only, and answers two questions: *what has been run
locally lately* and *what has been run on host X lately*. The keyboard fetches one host at a time,
parses the raw text itself, and applies redaction and ranking there — the helper never decides what
is safe to show.

## Install

```sh
pkg install python          # once
git clone <this repo> && cd termux-keyboard/helper
./install.sh --with-ssh-wrapper
```

`install.sh` copies the helper to `$PREFIX/bin`, generates a token in
`~/.config/termux-keyboard/config.json` (mode 0600) and prints it. Paste that token into
**Termux Keyboard → Terminal mode → Helper token** and switch **Fetch history from Termux** on.

`--with-ssh-wrapper` appends a shell function to `~/.bashrc` (or `~/.zshrc`) that tells the helper
which host you are ssh'd into. Without it you can still set the host manually through the API, or
edit `hosts` in the config and pick one in the keyboard toolbar (manual chip, slice 4).

## Run it

```sh
termux-keyboard-helper                       # foreground, ctrl-c to stop
nohup termux-keyboard-helper >/dev/null 2>&1 &   # background for this session
```

There is no autostart by default. If you want one, add to `~/.bashrc` once:

```sh
pgrep -f termux-keyboard-helper >/dev/null || (nohup termux-keyboard-helper >/dev/null 2>&1 &)
```

## Check it

```sh
termux-keyboard-helper --selftest            # local history + reachable hosts
termux-keyboard-helper --token               # print the token again
curl -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8765/v1/status
```

## API

| Request | Meaning |
|---|---|
| `GET /v1/status` | `{ok, version, activeHost, hosts[], localBytes}` |
| `GET /v1/history?host=local` | raw history text of the local shell files |
| `GET /v1/history?host=<alias>` | raw history text fetched with `ssh <alias> 'cat ~/.bash_history …'` |
| `POST /v1/active-host` `{"host":"web1"}` | set the host the wrapper is connected to (`null` = local) |

Every request needs `Authorization: Bearer <token>`; anything else gets 401. Unknown hosts get 404,
ssh failures 502.

## What it reads

- local: `~/.bash_history`, `~/.zsh_history`, `~/.local/share/fish/fish_history`
- remote: the same three files over a single `ssh -o BatchMode=yes <alias> '…'` call, using your
  existing keys and `~/.ssh/config`. Enable `ControlMaster` if you want refreshes to be instant;
  without it each refresh pays one connection.
- hosts come from `~/.ssh/config` (`Host` lines, wildcards skipped) plus the `hosts` list in the
  config.

Caching: local 30 s, remote 5 min, 20 000 lines per source. Nothing is written to disk.

## Security

- binds loopback only; refuses every request without the token; constant-time compare
- never logs command text; the token and config live in `~/.config` (0600)
- the keyboard applies redaction (password/token/secret/AKIA/private key), the ignore-space rule and
  ranking, and stores only what it uses
- the ssh call is `BatchMode=yes` with a timeout, and the host string is validated against
  `[A-Za-z0-9_.@:-]+` before it reaches `subprocess`
