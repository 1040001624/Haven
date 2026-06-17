---
layout: default
title: Email & Mail Rules
---

# Email & Mail Rules

An in-app mail client — read and send without leaving Haven — plus inbound
automation that runs against new mail.

## Email

Two engines sit behind one `MailClient` abstraction (`Map<MailEngine, MailClient>`), so a new provider drops in without touching the UI:

- **ProtonMail** — read mail via the Proton bridge protocol (the path the desktop Proton Bridge speaks), decrypted in the Go mailbridge.
- **Generic IMAP/SMTP** — any standard mailbox: read **and** send.

A folder and message list, a reader, and **compose / reply / forward** over IMAP/SMTP. **Multi-account** — a From-row account picker, with the message list and reader showing the active account. **Attachments** — save an attachment to, or attach a file from, any filesystem Haven already browses (local, SFTP, SMB, rclone) plus the Android document picker (SAF). Proton *send* is a follow-up; Proton is read-only today.

**Supported providers.** The generic engine works with any mailbox reachable over standard IMAP + SMTP with a username and password — Gmail, Outlook / Microsoft 365, Fastmail, Yahoo, iCloud, and self-hosted servers (Dovecot, etc.). Providers that require an app-specific password when 2-factor is on (e.g. Gmail, Yahoo, iCloud) work once you generate one. Authentication is username/password only — there is no OAuth / SSO sign-in flow yet, so mailboxes that *only* allow OAuth (some corporate/Google-Workspace tenants) aren't reachable. ProtonMail is the one non-IMAP engine, spoken over the Proton bridge protocol (read-only for now).

## Mail Rules — inbound automation

Rules that run against new mail. Each rule combines **match conditions** (from / to / subject / unread / has-attachment / body / header, with contains / equals / regex / glob) under ALL or ANY, with an ordered list of **actions** — mark read/unread, set/clear flag, move to folder, delete, notify, plus advanced actions an agent can author (run a command, save attachments, send to the agent, invoke an MCP tool). A **master switch** keeps automation inert until you enable it; a **firing history** logs what ran; and **destructive actions matched while Haven is backgrounded are held in a pending-approval queue** — approve to run them on the message, or reject to discard. Rules are authorable over MCP and managed in-app from the Mail screen's overflow menu; the in-app editor builds the human-friendly subset and opens agent-authored advanced rules read-only so it can't corrupt them.

Mail is also exposed to the agent: `list_mail_folders`, `list_mail_messages`, `read_mail_message`, and `send_mail`, plus the Mail Rules tools (`create_mail_rule`, `list_mail_rules`, `delete_mail_rule`).

---

[← All features](../FEATURES.md)
