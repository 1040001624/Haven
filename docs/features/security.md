---
layout: default
title: Security & privacy
---

# Security & privacy

Screen lock with biometric or device PIN/password/pattern, configurable timeout (immediate/30s/1m/5m/never), no telemetry or analytics, local storage only. Keyboard security: all credential fields set `IME_FLAG_NO_PERSONALIZED_LEARNING` to prevent keyboard apps from recording passwords, with a warning when the active keyboard has internet access. Encrypted backup/restore with AES-256-GCM. See the [privacy policy](../privacy-policy.html).

The encrypted backup wire format (PBKDF2/AES-GCM envelope) and a Python recipe for manual decryption are documented in [Backup file format](../backup-format.md).

---

[← All features](../FEATURES.md)
