---
layout: default
title: Agent transport (MCP)
---

# Agent transport (MCP)

An optional **MCP** (Model Context Protocol) server exposes Haven's read and write surfaces as tools, so an AI agent can drive the same primitives a human taps — and the user watches every action happen in the same UI. Disabled by default; toggled under **Settings → Agent endpoint**. ~130 tools span connections and sessions, the unified file browser, terminal I/O, media convert/stream, port forwards and tunnels, rclone sync, email and Mail Rules, the host-capability brokers (USB, `read_logcat` via Shizuku, adb-over-VPN), and the full multi-distro PRoot + desktop lifecycle (`install_distro`, `install_desktop`, `start_desktop`, `read_desktop_log`, …). Every write surfaces a non-skippable consent prompt before it runs, and every call is recorded to an in-app audit log. The complete tool surface — every tool, its arguments, and when it prompts — is in the [generated tool reference](https://github.com/GlassHaven/Haven/blob/main/docs/mcp-tools.md), rendered from the same registry the server dispatches. The endpoint is reached over the device's WireGuard tunnel IP by default (surviving network transitions), with an optional same-network LAN bind and a one-tap "Tunnel through SSH profile…" reverse-forward as fallbacks; on-device loopback clients are auto-trusted, while LAN/WireGuard clients pair on first connect and stay consent-gated.

The agent can also reach *out* to your attention — each consent-gated and rendered on a surface you're already looking at: `present_media` (an image or sound in an inline overlay), `present_app` (a single guest GUI app's live window, with Picture-in-Picture), `present_web` (HTML/SVG/PDF inline), `raise_notification`, and `queue_terminal_input` (a line into your terminal view).

## Driving Haven's own UI — the self-hosting loop

The agent can see and operate Haven *itself*, not only the machines Haven connects to. `capture_haven_ui` returns a screenshot of Haven's own rendered screen — also addressable as the MCP resource `ui://haven/screen`, so a client can pull it without a tool call — and `tap_haven_ui` / `swipe_haven_ui` inject pointer input into that window in the captured pixel space. Combined with `install_apk_from_backend`, this closes a self-hosted release-verify loop: an agent builds Haven on the workstation, installs the APK onto the very phone it is operating, then drives the new build and diffs the screen against its expectation — no human hand on the device. Capture is screen-security-aware (it returns a `secure` signal rather than a black frame when FLAG_SECURE is on) and requires Haven foreground; injection is refused while a consent prompt is showing, so it can never self-confirm. Text entry into Haven's own fields is deliberately out of scope — agents enter data through the API tools (`create_connection`, `set_preference`, …), not by simulating typing into the app's chrome.

## Consent tiers and standing policies

Reads are free (no prompt); one-shot writes raise a per-action consent sheet. For workflows that need many consented calls in a row — a drive-and-verify loop, a batch operation — an agent can *propose* a **standing policy** with `create_standing_policy`; the user's tap on its consent sheet installs it. A policy is scoped (an explicit tool list, optionally with pinned arguments like `{"profileId": …}`), rate-capped (calls/minute), and expiring (≤24 h), and is bound to one client name. Covered calls then run without a prompt while still being recorded to the audit log; beyond the rate ceiling, calls fall back to per-action prompts. Live grants are listed — with scope, rate, countdown, and one-tap **Revoke** — at the top of the Agent activity screen (the kill-switch), and `revoke_standing_policy` lets the agent drop its own grant. A policy can never cover app replacement (`install_apk_*`), client un-pairing, or the policy tools themselves, so a reflex can't escalate or renew itself.

---

[← All features](../FEATURES.md) · [Vision](https://github.com/GlassHaven/Haven/blob/main/VISION.md)
