# termlib upstream alignment

Haven builds against a fork of `connectbot/termlib` wired in as a git
submodule at `/termlib`. The fork exists because termlib didn't always
expose the seams Haven needs (per-keyboard-mode input types, live IME
composition flow, scroll drift fixes, …). The long-term policy is to
shrink the fork to a handful of patches by upstreaming everything that
could possibly benefit other termlib hosts, and keeping Haven's
opinionated UX in the `:core:terminal-haven` module that wraps termlib
from outside.

## Where things live

- `termlib/` — fork tracking `upstream/main`, with the active branch
  `feature/154-cf-access-diagnostics` carrying Haven-only patches
  (upstream is pulled in by merge — see `termlib/REBASE.md`).
- `termlib/REBASE.md` — rebase checklist (inside the submodule so it
  travels with the fork).
- `core/terminal-haven/` — Haven-owned wrapper around termlib's public
  API. `HavenTerminal()` delegates to `Terminal()`; `HavenKeyboardMode`
  maps Haven's Secure/Standard/Raw onto termlib's `allowStandardKeyboard`
  + `rawKeyboardMode` flags.

## Upstream PR pipeline

Open PRs against `connectbot/termlib` in this order when a Haven patch
turns out to be more broadly useful:

1. **Universal bug fixes** — scroll drift, Copy/Paste viewport clamp,
   tmux scrollback corruption, Enter/IME double-fire fixes. File
   individually with reproducers and tests. Low risk.
2. **Small API additions** — `onPasteShortcut` hook, `getSnapshotLineTexts()`
   accessor, `onTerminalDoubleTap` callback, `enableAltScreen` param.
   Small surface, frame as "host-useful extension points".
3. **Design-discussion items** — `composingText: StateFlow<String>` (for
   compose-mode hosts), making `ImeInputView` `open` + factory seam
   (lets hosts add Raw / voice toggles without forking). Open an issue
   first.
4. **Large refactors** — gesture callback architecture, URL wrap-boundary
   detector re-introduction (upstream reverted #155). Last.

As each PR lands upstream the corresponding patch gets dropped from
`termlib/feature/154-cf-access-diagnostics` on the next monthly upstream merge.

## If a PR is rejected

Retain the fork patch. Document the rejection inline below so the
reasoning survives turnover.

### Rejection log

*(Empty so far.)*
