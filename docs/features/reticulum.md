---
layout: default
title: Reticulum mesh
---

# Reticulum mesh

Connect over [Reticulum](https://reticulum.network) mesh networks with native Kotlin transport (reticulum-kt + rnsh-kt). Two-way terminal sessions over IFAC-protected TCP gateways, announce-based rnsh node discovery via scan button, configurable IFAC network name and passphrase. No Python runtime or Chaquopy dependency — pure Kotlin implementation with Flow-based I/O.

Beyond the shell, the mesh carries files and tunnels — the same surface area SSH has, layered on the rnsh remote command-exec substrate (each operation runs one busybox-portable command over its own Reticulum Link):

- **File transfer** — the unified file browser lists, downloads, and uploads files on an rnsh destination, plus mkdir/rename/delete. Directory listings parse `ls -la` (no GNU `stat`/`find` assumed, so it works against a busybox OpenWRT router as well as a full Linux host). Uploads stream over a **single Link**: the bytes are octal-encoded and fed as `printf` commands to one interactive `sh` over stdin, device-verified byte-identical (all 256 byte values, including NUL and high bytes). Small-file oriented — one command per directory op and a stdin stream per upload, well suited to configs, keys, and scripts rather than large media over a low-bandwidth mesh.
- **Tunnelable port** — `-L` local forwarding and `-D` SOCKS5 dynamic forwarding tunnel TCP across the mesh (one Link per connection, bridged to a remote `nc`), so you can reach a LAN host behind a Reticulum gateway.

Reticulum is the only transport in Haven that survives full internet loss, so anything routed through it is a uniquely offline-capable path, not a duplicate of SSH.

---

[← All features](../FEATURES.md)
