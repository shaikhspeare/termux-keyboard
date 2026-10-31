(function () {
  'use strict';

  const HISTORY = window.HISTORY;
  const STATIC = window.STATIC;
  const $ = (sel) => document.querySelector(sel);

  const state = {
    host: 'local',          // local | web1 | global
    terminalMode: true,
    helperOnline: true,
    buffer: '',
    trusted: true,
    reason: '',
    shift: false,
    ctrl: false,
    commits: [],
    sheetOpen: false,
    transcript: [],
  };

  const HOSTS = ['local', 'web1', 'global'];

  /* ---------------- history ranking (mirrors ShellSuggestionProvider) ---------------- */

  function decay(tMinutes) {
    const days = tMinutes / (60 * 24);
    return days < 4 ? 1 : days < 15 ? 0.75 : days < 31 ? 0.5 : days < 91 ? 0.25 : 0.1;
  }

  function candidates() {
    const buf = state.buffer;
    if (!state.terminalMode || !state.trusted || buf.length === 0) return [];
    const out = [];
    const add = (c, n, t, source, mult) => {
      if (c === buf || !c.startsWith(buf) || out.some((x) => x.c === c)) return;
      out.push({ c, source, n, score: n * decay(t) * mult });
    };
    if (state.helperOnline) {
      for (const e of HISTORY[state.host] || []) add(e.c, e.n, e.t, state.host, 1);
      if (state.host !== 'global') {
        for (const e of HISTORY.global) add(e.c, e.n, e.t, 'global', 0.5);
      }
    }
    out.sort((a, b) => b.score - a.score || a.c.length - b.c.length);
    const top = out.slice(0, 3);
    for (const c of STATIC) {
      if (top.length >= 3) break;
      if (c === buf || !c.startsWith(buf) || top.some((x) => x.c === c)) continue;
      top.push({ c, source: 'static', n: 0, score: 0 });
    }
    return top.slice(0, 3);
  }

  /* ---------------- terminal model ---------------- */

  function promptStr() {
    return state.host === 'web1' ? 'web1:~$ ' : '~ $ ';
  }

  function flash(text) {
    const t = $('.chrome-title');
    t.textContent = text;
    clearTimeout(flash._t);
    flash._t = setTimeout(() => { t.textContent = 'terminal mode'; }, 1600);
  }

  function insert(text) {
    state.buffer += text;
    state.commits.push('commitText("' + text.replace(/\n/g, '\\n') + '")');
    flash('commitText("' + text.replace(/\n/g, '\\n') + '")');
  }

  function markUntrusted(reason) {
    state.trusted = false;
    state.reason = reason;
  }

  function resync() {
    state.buffer = '';
    state.trusted = true;
    state.reason = '';
    state.commits = [];
  }

  function accept(suggestion) {
    const suffix = suggestion.c.slice(state.buffer.length);
    if (!suffix) return;
    insert(suffix);
    state.trusted = true;                 // we performed this insertion ourselves
    state.reason = '';
  }

  function outputFor(line) {
    const map = {
      'git status': [
        "On branch main",
        "Your branch is up to date with 'origin/main'.",
        "",
        "nothing to commit, working tree clean",
      ],
      'ls': ['Android  Music  Projects  Downloads  termux-keyboard'],
      'ls -la': [
        "total 48",
        "drwx------  8 u0_a208 u0_a208 4096 Oct 31 17:02 .",
        "drwx------ 12 u0_a208 u0_a208 4096 Oct 31 16:44 ..",
        "drwxr-xr-x  4 u0_a208 u0_a208 4096 Oct 31 16:51 termux-keyboard",
      ],
      'docker ps': [
        "CONTAINER ID   IMAGE        STATUS         PORTS                  NAMES",
        "8f2c1d4e9a11   nginx:1.27   Up 4 days      0.0.0.0:80->80/tcp     web",
        "1c9a7b3d2e55   postgres:16  Up 4 days      5432/tcp               db",
      ],
      'systemctl status nginx': [
        "● nginx.service - A high performance web server",
        "     Loaded: loaded (/lib/systemd/system/nginx.service; enabled)",
        "     Active: active (running) since Mon 2026-10-27 09:14:02 UTC",
      ],
      'pkg update': ["Hit:1 https://packages.termux.dev/apt/termux-main stable InRelease", "Reading package lists... Done"],
      'ssh web1': [
        "Welcome to Ubuntu 24.04.1 LTS (GNU/Linux 6.8.0-45-generic x86_64)",
        "Last login: Fri Oct 31 16:58:12 2026 from 100.107.215.42",
      ],
    };
    if (map[line]) return map[line].map((text) => ({ type: 'out', text }));
    if (line === 'clear') return [];
    if (/^(cd|pwd|echo|export|pkg upgrade)/.test(line)) return [];
    return [{ type: 'err', text: 'bash: ' + line.split(/\s+/)[0] + ': command not found' }];
  }

  function run() {
    const line = state.buffer;
    if (!line.trim()) {
      state.buffer = '';
      state.commits = [];
      return;
    }
    state.transcript.push({ type: 'cmd', prompt: promptStr(), text: line });
    if (line === 'clear') {
      state.transcript = [];
    } else {
      for (const l of outputFor(line)) state.transcript.push(l);
    }
    if (line.startsWith('ssh ')) {
      const host = line.split(/\s+/)[1];
      if (HISTORY[host]) {
        state.host = host;
        state.transcript.push({ type: 'sys', text: '[wrapper] active host -> ' + host });
      }
    } else if (line === 'exit' && state.host === 'web1') {
      state.host = 'local';
      state.transcript.push({ type: 'sys', text: '[wrapper] active host -> local' });
    }
    state.buffer = '';
    state.commits = [];
    state.trusted = true;
    state.reason = '';
  }

  function ctrlCombo(ch) {
    state.ctrl = false;
    const c = ch.toLowerCase();
    if (c === 'c') {
      state.transcript.push({ type: 'out', text: promptStr() + state.buffer + '^C' });
      state.buffer = '';
      state.trusted = true;
      state.reason = '';
      state.commits = [];
      flash('commitText("\\u0003")');
    } else if (c === 'u') {
      state.buffer = '';
      state.trusted = true;
      flash('commitText("\\u0015")');
    } else if (c === 'l') {
      state.transcript = [];
      flash('commitText("\\u000c")');
    } else if (c === 'd') {
      state.transcript.push({ type: 'sys', text: 'Ctrl-D — the shell would exit here' });
    } else {
      state.buffer = '';
      markUntrusted('Ctrl-' + c.toUpperCase() + ' sent');
    }
  }

  function typeChar(ch) {
    if (state.ctrl) { ctrlCombo(ch); return; }
    insert(LETTER.test(ch) && state.shift ? ch.toUpperCase() : ch);
    if (state.shift) state.shift = false;
  }

  function controlKey(code) {
    if (state.ctrl && /^[a-z]$/i.test(code)) { ctrlCombo(code); return; }
    switch (code) {
      case 'SPACE':
        typeChar(' ');
        return;
      case 'BACK':
        if (state.buffer.length) state.buffer = state.buffer.slice(0, -1);
        state.commits.push('deleteSurroundingText(1,0)');
        flash('deleteSurroundingText(1,0)');
        return;
      case 'ENTER':
        run();
        return;
      case 'SHIFT':
        state.shift = !state.shift;
        return;
      case 'CTRL':
        state.ctrl = !state.ctrl;
        return;
      case 'ALT':
        markUntrusted('Alt sent');
        return;
      case 'TAB':
        markUntrusted('Tab sent — shell completion may have changed the line');
        return;
      case 'ESC':
        markUntrusted('Esc sent');
        return;
      case 'UP':
        markUntrusted('Up sent — shell history recall replaces the line');
        return;
      case 'DOWN':
        markUntrusted('Down sent');
        return;
      case 'LEFT':
      case 'RIGHT':
      case 'HOME':
      case 'END':
      case 'PGUP':
      case 'PGDN':
        markUntrusted('cursor key sent');
        return;
      default:
        typeChar(code);
    }
  }

  /* ---------------- rendering ---------------- */

  function notice(text, kind, action) {
    const wrap = document.createElement('div');
    wrap.className = 'notice ' + kind;
    const span = document.createElement('span');
    span.className = 'grow';
    span.textContent = text;
    wrap.append(span);
    if (action) {
      const b = document.createElement('button');
      b.type = 'button';
      b.textContent = action.label;
      b.onclick = action.onClick;
      wrap.append(b);
    }
    return wrap;
  }

  function toolChip(label, on, onClick) {
    const b = document.createElement('button');
    b.type = 'button';
    b.className = 'chip tool' + (on ? ' on' : '');
    const dot = document.createElement('span');
    dot.className = 'dot';
    const t = document.createElement('span');
    t.textContent = label;
    b.append(dot, t);
    b.onclick = onClick;
    return b;
  }

  function renderStrip() {
    const strip = $('#strip');
    strip.textContent = '';

    if (!state.terminalMode) {
      strip.append(notice('Terminal mode off — no suggestions, as in stock HeliBoard.',
        'muted', { label: 'Enable', onClick: () => { state.terminalMode = true; render(); } }));
      return;
    }
    if (!state.trusted) {
      strip.append(notice(state.reason + ' — strip hidden until Enter.',
        'warn', { label: 'Resync', onClick: () => { resync(); render(); } }));
      return;
    }

    const sug = candidates();
    if (sug.length === 0) {
      strip.append(
        toolChip('host: ' + state.host, state.host !== 'local', cycleHost),
        toolChip('terminal', state.terminalMode, () => { state.terminalMode = !state.terminalMode; render(); }),
        toolChip('helper ' + (state.helperOnline ? 'online' : 'offline'), state.helperOnline, () => {
          state.helperOnline = !state.helperOnline; render();
        })
      );
      return;
    }

    for (const s of sug) {
      const b = document.createElement('button');
      b.type = 'button';
      b.className = 'chip suggest';
      b.title = 'insert suffix: ' + s.c.slice(state.buffer.length);
      const label = document.createElement('span');
      label.className = 'label';
      // typed part dimmed, inserted suffix bright: shows exactly what a tap will add
      label.style.fontSize = s.c.length > 22 ? '9.5px' : s.c.length > 18 ? '10.5px' : s.c.length > 14 ? '11px' : '12px';
      const typed = document.createElement('span');
      typed.className = 'typed';
      typed.textContent = s.c.slice(0, state.buffer.length);
      const add = document.createElement('span');
      add.className = 'add';
      add.textContent = s.c.slice(state.buffer.length);
      label.append(typed, add);
      const sub = document.createElement('span');
      sub.className = 'sub';
      sub.textContent = s.source === 'static' ? 'static' : s.source + ' · ' + s.n + '×';
      b.append(label, sub);
      b.onclick = () => { accept(s); render(); };
      strip.append(b);
    }
  }

  function renderTerminal() {
    const t = $('#transcript');
    t.textContent = '';
    for (const item of state.transcript) {
      const div = document.createElement('div');
      div.className = 'ln ' + item.type;
      if (item.type === 'cmd') {
        const p = document.createElement('span');
        p.className = 'p';
        p.textContent = item.prompt || '';
        div.append(p, document.createTextNode(item.text));
      } else {
        div.textContent = item.text;
      }
      t.append(div);
    }
    $('#prompt').textContent = promptStr();
    $('#buf').textContent = state.buffer;
    t.scrollTop = t.scrollHeight;
  }

  function render() {
    renderTerminal();
    renderStrip();
    if (state.sheetOpen) renderSheet();
    // keyboard modifier state
    document.querySelectorAll('[data-key="CTRL"]').forEach((b) => b.classList.toggle('armed', state.ctrl));
    document.querySelectorAll('[data-key="SHIFT"]').forEach((b) => b.classList.toggle('on', state.shift));
    document.querySelectorAll('#keys [data-key]').forEach((b) => {
      const k = b.dataset.key;
      if (LETTER.test(k) && k.length === 1) b.textContent = state.shift ? k.toUpperCase() : k;
    });
  }

  function cycleHost() {
    const i = HOSTS.indexOf(state.host);
    state.host = HOSTS[(i + 1) % HOSTS.length];
    render();
  }

  /* ---------------- keyboard ---------------- */

  const LETTER = /^[a-z]$/i;

  function key(label) {
    const b = document.createElement('button');
    b.type = 'button';
    b.className = 'key' + (LETTER.test(label) ? '' : ' mod');
    b.dataset.key = label;
    b.textContent = LETTER.test(label) && state.shift ? label.toUpperCase() : label;
    return b;
  }

  const LAYOUT = [
    { cls: 'row nums', keys: ['1', '2', '3', '4', '5', '6', '7', '8', '9', '0'] },
    { cls: 'row home', keys: ['q', 'w', 'e', 'r', 't', 'y', 'u', 'i', 'o', 'p'] },
    { cls: 'row', keys: ['a', 's', 'd', 'f', 'g', 'h', 'j', 'k', 'l'] },
    { cls: 'row', keys: ['SHIFT', 'z', 'x', 'c', 'v', 'b', 'n', 'm', 'BACK'] },
    { cls: 'row', keys: [',', 'SPACE', '.', 'ENTER'] },
  ];

  const LABEL = { SHIFT: '⇧', BACK: '⌫', ENTER: '⏎', SPACE: 'space', NUM: '?123' };
  const CLASS = { SHIFT: 'key mod wide', BACK: 'key mod wide', ENTER: 'key enter', SPACE: 'key space', COMMA: 'key' };

  function buildKeyboard() {
    const root = $('#keys');
    root.textContent = '';
    for (const row of LAYOUT) {
      const r = document.createElement('div');
      r.className = row.cls;
      for (const k of row.keys) {
        const b = document.createElement('button');
        b.type = 'button';
        b.className = CLASS[k] || (LETTER.test(k) || /[0-9.,]/.test(k) ? 'key' : 'key mod');
        b.dataset.key = k;
        b.textContent = LABEL[k] || (LETTER.test(k) && state.shift ? k.toUpperCase() : k);
        r.append(b);
      }
      root.append(r);
    }
  }

  /* ---------------- controls sheet ---------------- */

  function renderSheet() {
    const body = $('#sheetBody');
    body.textContent = '';

    const h = (text) => { const e = document.createElement('h3'); e.textContent = text; return e; };

    body.append(h('Try this'));
    const ul = document.createElement('ul');
    ul.className = 'checks';
    [
      'Type gi — strip shows git completions from the active host.',
      'Tap a suggestion — only the missing suffix is sent (watch the commit flash).',
      'Press TAB or ↑ — the buffer becomes untrusted and the strip hides.',
      'Press Enter — line executes and the buffer resyncs.',
      'Type ssh web1 then Enter — the host chip switches to web1 and its history takes over.',
      'Turn the helper off — suggestions collapse to the bundled static list.',
    ].forEach((text, i) => {
      const li = document.createElement('li');
      const n = document.createElement('span');
      n.className = 'num';
      n.textContent = String(i + 1);
      const s = document.createElement('span');
      s.textContent = text;
      li.append(n, s);
      ul.append(li);
    });
    body.append(ul);

    body.append(h('Switches'));
    const toggles = document.createElement('div');
    toggles.className = 'toggles';
    toggles.append(
      makeToggle('Terminal mode', 'force suggestions for com.termux', state.terminalMode, () => {
        state.terminalMode = !state.terminalMode; render();
      }),
      makeToggle('Helper online', 'loopback daemon in Termux', state.helperOnline, () => {
        state.helperOnline = !state.helperOnline; render();
      })
    );
    body.append(toggles);

    body.append(h('Active host'));
    const seg = document.createElement('div');
    seg.className = 'seg';
    for (const host of HOSTS) {
      const b = document.createElement('button');
      b.type = 'button';
      b.className = state.host === host ? 'on' : '';
      b.textContent = host;
      b.onclick = () => { state.host = host; render(); };
      seg.append(b);
    }
    body.append(seg);

    body.append(h('Simulate events the IME cannot see'));
    const sim = document.createElement('div');
    sim.className = 'seg';
    [
      ['Paste', () => { insert('echo pasted'); markUntrusted('paste inserted text'); render(); }],
      ['↑ recall', () => { state.buffer = 'git log --oneline -10'; markUntrusted('history recall replaced the line'); render(); }],
      ['Tab complete', () => { state.buffer = 'git commit'; markUntrusted('shell completion extended the line'); render(); }],
      ['Cursor left', () => { markUntrusted('cursor key sent'); render(); }],
    ].forEach(([label, fn]) => {
      const b = document.createElement('button');
      b.type = 'button';
      b.textContent = label;
      b.onclick = fn;
      sim.append(b);
    });
    body.append(sim);

    body.append(h('IME state'));
    const dbg = document.createElement('div');
    const rows = [
      ['package', 'com.termux'],
      ['inputType', '0x0 (TYPE_NULL)'],
      ['buffer', JSON.stringify(state.buffer)],
      ['trusted', String(state.trusted) + (state.trusted ? '' : ' — ' + state.reason)],
      ['host', state.host],
      ['helper', state.helperOnline ? 'online' : 'offline'],
    ];
    for (const [k, v] of rows) {
      const r = document.createElement('div');
      r.className = 'debug-row';
      const a = document.createElement('span'); a.textContent = k;
      const b = document.createElement('span'); b.textContent = v;
      r.append(a, b);
      dbg.append(r);
    }
    body.append(dbg);

    body.append(h('Ranking for current input'));
    const pre = document.createElement('pre');
    pre.className = 'debug';
    const sug = candidates();
    pre.textContent = sug.length === 0
      ? '(no suggestions)'
      : sug.map((s) => s.c.padEnd(34) + ' ' + String(s.score.toFixed(1)).padStart(6) + '  ' + s.source).join('\n');
    body.append(pre);

    body.append(h('InputConnection log'));
    const log = document.createElement('pre');
    log.className = 'debug';
    log.textContent = state.commits.length ? state.commits.join('\n') : '(nothing since the last commit)';
    body.append(log);

    const reset = document.createElement('button');
    reset.type = 'button';
    reset.className = 'chrome-btn';
    reset.style.marginTop = '18px';
    reset.textContent = 'Reset demo';
    reset.onclick = () => { resetAll(); };
    body.append(reset);
  }

  function makeToggle(name, hint, on, onClick) {
    const row = document.createElement('div');
    row.className = 'toggle';
    const left = document.createElement('div');
    left.className = 'name';
    const n = document.createElement('span'); n.textContent = name;
    const h = document.createElement('span'); h.className = 'hint'; h.textContent = hint;
    left.append(n, h);
    const sw = document.createElement('button');
    sw.type = 'button';
    sw.className = 'switch' + (on ? ' on' : '');
    sw.setAttribute('aria-pressed', String(on));
    const knob = document.createElement('span');
    knob.className = 'knob';
    sw.append(knob);
    sw.onclick = onClick;
    row.append(left, sw);
    return row;
  }

  /* ---------------- wiring ---------------- */

  function resetAll() {
    state.host = 'local';
    state.terminalMode = true;
    state.helperOnline = true;
    state.buffer = '';
    state.trusted = true;
    state.reason = '';
    state.shift = false;
    state.ctrl = false;
    state.commits = [];
    state.transcript = [
      { type: 'sys', text: 'mock session — nothing here runs on a device' },
      { type: 'cmd', prompt: '~ $ ', text: 'ls' },
      { type: 'out', text: 'Android  Music  Projects  Downloads  termux-keyboard' },
      { type: 'cmd', prompt: '~ $ ', text: 'git status' },
      { type: 'out', text: 'nothing to commit, working tree clean' },
    ];
    render();
  }

  document.addEventListener('click', (ev) => {
    const keyEl = ev.target.closest('[data-key]');
    if (keyEl) {
      controlKey(keyEl.dataset.key);
      render();
    }
  });

  $('#openSheet').onclick = () => { state.sheetOpen = true; $('#sheet').hidden = false; render(); };
  $('#closeSheet').onclick = () => { state.sheetOpen = false; $('#sheet').hidden = true; };
  $('#sheet').onclick = (ev) => {
    if (ev.target === $('#sheet')) { state.sheetOpen = false; $('#sheet').hidden = true; }
  };

  buildKeyboard();
  resetAll();
})();
