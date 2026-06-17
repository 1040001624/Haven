---
layout: default
title: Terminal
---

# Terminal

VT100/xterm emulator with multi-tab sessions, [Mosh](https://mosh.org) (Mobile Shell) for roaming connections and [Eternal Terminal](https://eternalterminal.dev) (ET) for persistent sessions — both with pure Kotlin protocol implementations (no native binaries), tmux/zellij/screen auto-attach with **session restore** (remembers previously open sessions and offers to reopen them), tab reordering via long-press menu, color-coded tabs matching connection profiles, mouse mode for TUI apps, configurable keyboard toolbar (Esc, Tab, Ctrl, Alt, AltGr, arrows, Delete, Insert, Home/End/PgUp/PgDn, F1–F12, custom macro keys with presets for common combos like Ctrl+C/D/Z and Ctrl+Alt+Delete), text selection with copy and Open URL, OSC 133 shell integration with one-tap "copy last command output", configurable font size, and six color schemes. The bundled Hack Nerd Font Mono renders Powerline / Devicons / Font Awesome / Material Design glyphs in shell prompts out of the box.

## OSC escape sequences

Remote programs can interact with Android through standard terminal escape sequences:

| OSC | Function | Example |
|-----|----------|---------|
| 52 | Set clipboard | `printf '\e]52;c;%s\a' "$(echo -n text \| base64)"` |
| 8 | Hyperlinks | `printf '\e]8;;https://example.com\aClick\e]8;;\a'` |
| 9 | Notification | `printf '\e]9;Build complete\a'` |
| 777 | Notification (with title) | `printf '\e]777;notify;CI;Pipeline green\a'` |
| 7 | Working directory | `printf '\e]7;file:///home/user\a'` |

Notifications appear as a toast in the foreground or as an Android notification in the background.

---

[← All features](../FEATURES.md)
