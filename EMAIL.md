# Haven Embedded Email — Feature Plan

## Context

Haven's connections feature currently supports SSH / RCLONE / VNC / RDP / SMB / RETICULUM / LOCAL / MOSH connection types. Each is a row in `ConnectionProfile` keyed by `connectionType`, dispatched on connect, and routed to a destination screen (e.g. an rclone remote opens the SFTP **file browser**).

Goal: add an **EMAIL** connection type that, instead of a file view, opens a **K-9-style mail client** (`feature/mail`) — and that composes Haven's existing layered connection security into a *fully flexible email security chain* (tunnel transport + SPA/port-knock pre-connect + a multi-factor auth chain), adding a message-layer (PGP) on top. The differentiators: **ProtonMail on mobile** (no mainstream Android client offers this) and mail that the on-device agent/MCP can reach across providers.

**Locked decisions:**
- **Engine = Hybrid**: JVM IMAP/SMTP/Graph client rides the security chain directly via `TunnelResolver.socketFactory`; a **Go gomobile bridge** handles ProtonMail (`go-proton-api`/`gluon`) and PGP (`gopenpgp`).
- **MVP = ProtonMail-first** vertical slice.
- **Security in v1 = all four layers**: tunnel routing, SPA + port-knock, PGP message crypto, OAuth/XOAUTH2 (as a first-class auth method, even though the Proton slice itself authenticates via SRP).

**Key risk — RETIRED (2026-06-06).** A host compile spike inside `rclone-android/go` confirmed the vendored `rclone/go-proton-api` exposes the full Proton **Mail** vertical (not just Drive): `Manager.NewClientWithLogin` (SRP) + `Client.Auth2FA`; `GetSalts`/`Salts.SaltForKey` + `Unlock` → decryption keyrings; `GetLabels` (folders); `GetMessageMetadata`/`GetMessage`; `BuildRFC822(kr, msg, attData)` (decrypt → standard MIME); `GetAttachment`; `CreateDraft`/`SendDraft`. The spike built clean (`go build`, exit 0).

**Consequence — `gluon` dropped from v1.** Because `BuildRFC822` already decrypts to standard MIME, v1 calls `go-proton-api` directly and parses MIME on the Kotlin side with Apache Mime4j; `gluon` (the local IMAP-server layer) is only needed if we later expose Proton as on-device IMAP — deferred, not v1. (Earlier mentions of `gluon`/`emersion/go-message` in the diagrams below are historical; the shipped read path uses neither directly.)

## Implementation status (2026-06-07)

The Proton read-only vertical slice is built and compiles end-to-end (`:app:compileX64DebugKotlin`). Scope was sharpened from the locked decisions above with the maintainer:

- **v1 = ProtonMail read-only** (connect → folders → list → read decrypted message). **Send/compose deferred to v1.1** — the Go `send` path is a deliberate 501 stub; it's the hardest/riskiest piece (per-recipient encryption) and is kept off the first release.
- **OAuth/XOAUTH2 token store deferred to Stage 2b** (built with the Gmail JVM engine that consumes it) — no dead encrypted-credential code in a Proton-only v1.
- **Security layers in v1 = three working** (tunnel routing via `socksEndpoint`, native PGP via the bridge, SPA/port-knock pre-connect now wired into `connectEmail` — which `connectRclone` lacked). OAuth is the deferred fourth. SPA/knock for Proton guards the user's *tunnel ingress* (`profile.host`), not Proton's servers.
- **MIME parsing = Apache James Mime4j** (Apache-2.0); the reader renders **plain text only** (no WebView) so remote images/scripts never load.
- **Reader/session security:** `saltedKeyPass` (keyring-unlock secret) is held in-memory only, never persisted in v1; re-auth on process death.
- Built modules: `core/mail` (`MailClient`/`ProtonMailClient`/`MailSessionManager`), `feature/mail` (`MailScreen`/`MailViewModel`/`ProtonMailBackend`/`MimeParser`), `EMAIL` connection type + edit dialog + `connectEmail`, `Screen.Mail` nav, and read-only MCP tools (`list_mail_folders`/`list_mail_messages`/`read_mail_message`). DB schema 60→61.
- **Not yet done:** on-device verification with a real Proton account (`appVersion` borrowed from rclone's working Drive default — unverified for Mail data endpoints); locale translations for the new strings; live staged 2FA/mailbox-password prompt (v1 reads stored mailbox password + a linked TOTP secret instead); wiring `WithUserAgent` in the Go bridge.

## Architecture (mapped to existing seams)

```
ConnectionProfile(connectionType = "EMAIL")
        │
        ▼  feature/connections: connectEmail()  (mirror connectRclone, ConnectionsViewModel.kt:1657)
SECURITY CHAIN  ── all reused, not rebuilt ──
  Transport   TunnelResolver.dial / .socketFactory / .socksEndpoint   (core/tunnel/TunnelResolver.kt:47,62,116)
  Pre-connect SpaSender + PortKnocker via buildKnockHook              (core/spa, core/knock; ConnectionsViewModel ~969-1012)
  Auth        emailAuthMethods: {Password | XOAUTH2 | ProtonSRP | TOTP}  (extend ConnectionProfile.authMethods model)
  Message     MailCryptoService → gopenpgp (Go bridge)
        │
        ▼  ENGINE (hybrid)
  Proton  → Go mailbridge (gomobile): go-proton-api + gluon + gopenpgp   [v1]
  IMAP/SMTP & Gmail/Outlook → JVM Jakarta Mail + Mime4j, socketFactory   [stage 2]
        │
        ▼  core/mail: MailSessionManager (mirror RcloneSessionManager) + MailBackend + MailTransportSelector
        ▼  feature/mail: MailScreen → folder list → message list → reader → compose   (mirror feature/sftp)
        ▼  app/navigation: Screen.Mail + navigateToEmail receiver (mirror onNavigateToRclone, HavenNavHost ~473-477)
```

**Why hybrid routes two ways:** a JVM client accepts a `javax.net.SocketFactory`, so Jakarta Mail plugs straight into `TunnelResolver.socketFactory(profile)` and the JVM `authMethods` chain. The Go/Proton client is an FFI consumer, so it rides the tunnel via the localhost **SOCKS5** `socksEndpoint(profile)` — the same mechanism rclone already uses (`RbSetProxy`).

## Provider matrix

| Provider | Auth | Protocol (v1) | Engine | Security-chain hook | Distribution caveat | Stage |
|---|---|---|---|---|---|---|
| **ProtonMail** | SRP + mailbox pw + TOTP | Proton API (`go-proton-api`) | Go bridge | SOCKS `socksEndpoint` + SPA/knock; **PGP native** | Unofficial/reverse-engineered API; fragile to Proton changes | **v1** |
| **Generic IMAP/SMTP** (Dovecot, Fastmail, iCloud, Yahoo) | password / **app-password** | IMAP 993 + SMTP 465/587 | JVM Jakarta Mail | `socketFactory` + SPA/knock; PGP optional | None — app-passwords need no OAuth verification | **stage 2 (first)** |
| **Gmail / Workspace** | **XOAUTH2** (token as password) | IMAP 993 + SMTP 465 | JVM Jakarta Mail | `socketFactory`; token = auth layer; PGP optional | Scope `https://mail.google.com/` is **restricted** → CASA assessment for a *published* app (Testing/personal-use exempt). Android loopback redirect deprecated → **deep-link**. BYO OAuth client per user. | stage 2 |
| **Microsoft** (Outlook.com personal + M365 work/school) | **OAuth2** (token) | IMAP 993 + SMTP 587 | JVM Jakarta Mail | `socketFactory`; token = auth layer | Basic auth dead (SMTP 100% by 2026-04-30). Free Azure app reg (multi-tenant + personal), **no CASA**. | stage 2 |
| **Future:** Gmail REST · MS Graph · Fastmail JMAP | OAuth2 | REST / JMAP | JVM (HTTP, via `socketFactory`/proxy) | `socketFactory` | Modern alternatives to IMAP; not needed for parity | stage 4+ |

All non-Proton providers share one JVM code path (Jakarta Mail + Mime4j); they differ only in **auth method** (`emailAuthMethods`) and **server coordinates**. The PGP message layer (`MailCryptoService`) is provider-agnostic and opt-in for any account.

### Per-provider notes
- **Gmail** — XOAUTH2 over IMAP/SMTP; scope `https://mail.google.com/`. Refresh tokens need consent with `access_type=offline`. Pragmatic path: user supplies their own OAuth **Desktop** client (Testing mode) → unverified-app warning is expected. A published F-Droid build with this restricted scope would need annual CASA; surface this, don't hide it. (Gmail REST API is an alt but uses the same restricted scope.)
- **Microsoft** — OAuth2 scopes `https://outlook.office.com/IMAP.AccessAsUser.All`, `.../SMTP.Send`, `offline_access`. Works for personal MSA (outlook.com) and Entra work/school via one multi-tenant+personal app registration; delegated consent, no admin approval. Lighter than Google (no CASA).
- **Generic IMAP/SMTP** — plain password or provider app-password (Fastmail, iCloud, Yahoo all require app-passwords with 2FA on; Yahoo has dropped plain passwords). Self-hosted Dovecot: plain or XOAUTH2/OAUTHBEARER at the admin's choice. This is the lowest-friction path and the stage-2 proof of the JVM engine.
- **Future protocols** — Microsoft **Graph** (`Mail.Read`/`Mail.Send`) and Fastmail **JMAP** are cleaner than IMAP and worth adding once the IMAP path is solid; both are HTTP and ride `socketFactory`/proxy through the same tunnel.

## OAuth / XOAUTH2 sub-architecture (Gmail + Microsoft)

**Reuse `core/stepca`'s proven OAuth machinery — do not write a new flow:**
- `OidcAuthClient.kt` — generic PKCE authorization-code client (`authorize()` builds the auth URL, exchanges the code at the token endpoint, supports an optional client secret). Parameterize it (or add a thin `OAuth2AuthClient` sibling) for provider auth/token endpoints, scopes, and to extract **access + refresh tokens** (not `id_token`).
- `Pkce.kt` — RFC 7636 verifier/challenge. Reuse as-is.
- `OidcRedirectActivity` + `OidcRedirectBus` — **deep-link** redirect catcher (custom scheme `haven://…`, state-keyed `CompletableDeferred`). Register a mail callback host in the manifest. Chosen over a 127.0.0.1 loopback because **Google deprecated Android loopback redirects**.
- `CustomTabsIntent` launcher (`OidcAuthClient.DefaultLauncher`) — reuse for the consent screen.

**Build new (the only gap): OAuth token persistence.**
- `OAuth2Token` entity + `OAuth2TokenRepository` mirroring `TotpSecret`/`TotpSecretRepository`, with both `accessToken`/`refreshToken` encrypted at rest via `core/security/CredentialEncryption.kt`. Fields: `provider`, `userEmail`, `accessToken`, `refreshToken?`, `expiresAt?`, `scopes`. Add a `refreshAccessToken()` path (uses `offline_access`).
- `emailAuthMethods` carries `XOAUTH2:<tokenId>` referencing this store (parallel to `TOTP:<secretId>`).

**XOAUTH2 wiring into the JVM engine (Jakarta Mail):**
- `mail.imap.auth.mechanisms=XOAUTH2` / `mail.smtp.auth.mechanisms=XOAUTH2`; pass the access token as the password.
- Inject `TunnelResolver.socketFactory(profile)` via `mail.imap.ssl.socketFactory` / `mail.smtp.ssl.socketFactory` so the authenticated session rides the per-profile tunnel; SPA/knock run in the same pre-connect hook as all other connections.

**Honest distribution caveat (F-Droid):** the Google-recommended Android OAuth path (Google Identity Services SDK) needs Play Services and isn't F-Droid-friendly. Haven's path is BYO OAuth client + deep-link, which works for personal/Testing use with an unverified-app warning; a widely-published build with Gmail's restricted scope would require CASA. Microsoft has no equivalent gate.

## Embedding decisions (the "termlib" question)

Haven embeds engines as **git submodules** wired via `includeBuild()` + `dependencySubstitution()` (`settings.gradle.kts`), in three flavours: pure-Kotlin (et-kotlin, mosh-kotlin), Go→gomobile→`libgojni.so` (rclone-android), and native JNI (termlib C/C++, rdp-kotlin Rust).

- **Proton + PGP → Go, via the existing rclone-android gomobile bind.** Add a `./mailbridge` Go package to the bind invocation that already builds `./wgbridge ./tsbridge` (`rclone-android/tools/build-android.sh`), so it lands in the **same `libgojni.so`** — no new native module. Deps are already vendored & building for Android (`rclone-android/go/go.mod`: `ProtonMail/gluon`, `gopenpgp/v2`, `go-crypto`, `go-srp`, `go-mime` lines 28-33; `rclone/go-proton-api`, `Proton-API-Bridge` lines 192-193; `emersion/go-message` line 95).
- **Generic IMAP/SMTP + Gmail/Outlook → JVM Jakarta Mail (Apache-2.0) + Apache Mime4j**, added as plain Gradle deps. Rides the security chain natively.
- **Do NOT fork the K-9/Thunderbird app.** Its backend is tightly coupled to its UI; extraction cost is high. Borrow ideas (offline store, sync state machine) in stage 3, not as a dependency.
- **Licensing:** Haven is **AGPLv3** (`LICENSE:1`). MIT (emersion/Proton), Apache-2.0 (Jakarta Mail/Mime4j, new Thunderbird), and GPLv2-or-later/EPL+CPE are all compatible.

## Components to add / change

**core/data** — `ConnectionProfile.kt`
- Add EMAIL fields: `emailProvider` ("proton" | "imap" | "gmail" | "outlook"), `emailServer`, `emailPort`, `emailSmtpPort`, `emailTls`, `emailUsername`, plus `emailAuthMethods` (extends the existing ordered `authMethods` spec at ~`:259-324` with `XOAUTH2:<tokenId>` and `PROTON_SRP`).
- Add `val isEmail get() = connectionType == "EMAIL"`; include in the non-terminal guards.
- Room migration (follow existing migration pattern in `core/data`).
- New `OAuth2Token` entity + `OAuth2TokenRepository` (token + refresh), mirroring `TotpSecret`/`TotpSecretRepository` and reusing `CredentialEncryption` for at-rest encryption. The OAuth flow itself reuses `core/stepca` (`OidcAuthClient`/`Pkce`/`OidcRedirectActivity`+`OidcRedirectBus`/`CustomTabsIntent`) — deep-link redirect, not loopback. See "OAuth / XOAUTH2 sub-architecture".

**core/mail** (new module, mirrors `core/rclone`)
- `MailSessionManager` — mirror `RcloneSessionManager` (`SessionState`, `registerSession/connectSession/isProfileConnected/getClientForProfile`, OAuth loopback like `startOAuthFlow`).
- `MailClient` interface + `MailFolder`/`MailMessage`/`MailAddress`/`MailAuthSpec` models.
- `MailCryptoService` (encrypt/decrypt/sign/verify) → gopenpgp via the bridge.
- `ProtonMailClient` (Go bridge) for v1.

**rclone-android** (extend, no new module)
- `go/mailbridge/mailbridge.go` — exposed RPCs: `protonLogin`, `listFolders`, `listMessages`, `fetchMessage` (returns decrypted MIME), `sendMessage` (sign), `pgpEncrypt/Decrypt/Sign/Verify`. Dialer/HTTP client bound to the SOCKS endpoint passed from Kotlin.
- `tools/build-android.sh` — add `./mailbridge` to the `gomobile bind` package list; `-javapkg=sh.haven.mail.binding`.
- `kotlin/.../bridge/MailBridge.kt` — thin wrapper mirroring `RcloneBridge.rpc`.

**feature/mail** (new module, mirrors `feature/sftp`)
- `MailScreen` (accepts `pendingProfileId`), `MailViewModel`, composables: folder list → threaded message list → MIME reader → compose/send.
- `MailTransportSelector` — mirror `feature/sftp/.../transport/TransportSelector.kt` (factory dispatch by profileId; resolves Proton-Go vs JVM backend).
- `MailBackend` interface (message-centric; do **not** reuse `FileBackend`).

**feature/connections**
- `connectEmail(profile)` dispatch in `connectProfile` (next to `connectRclone`, ~`:1380`); `_navigateToEmail` StateFlow (mirror `_navigateToRclone` `:608`).
- Reuse the SPA/knock pre-connect hook before opening the mail socket.
- `ConnectionEditDialog`: EMAIL branch with provider picker (Proton / Generic IMAP / Gmail / Outlook) and per-provider fields. OAuth providers (Gmail/Outlook) launch the deep-link consent flow and store a token id; app-password/password providers (Proton, generic IMAP) take server/port/user/secret.

**core/ui** — `Screen.kt`: add `Mail("mail", R.string.nav_mail, Icons.Filled.Mail)`.

**app** — `HavenNavHost.kt`: render `Screen.Mail -> MailScreen(...)` in the pager `when`; add `navigateToEmail` receiver + `pendingEmailProfileId` (mirror the rclone wiring ~`:473-477`).

## Staged delivery

- **Stage 1 — Proton vertical slice (v1 MVP), proves the whole chain.**
  0. Go spike: confirm `go-proton-api` + `gluon` do Proton **Mail** (login/list/fetch). *Gate.*
  1. EMAIL connection type + edit dialog (Proton + Generic IMAP fields) + nav to `feature/mail`.
  2. `mailbridge.go`: Proton SRP login, folders, list, fetch+**PGP-decrypt**, send+**sign** — dialer pinned to `socksEndpoint` (**tunnel routing**) with **SPA/knock** pre-connect.
  3. `feature/mail` UI: folder list → message list → reader (MIME) → compose/send.
  4. OAuth/XOAUTH2 auth-method scaffolding implemented so a Gmail account can be added in the same build (JVM path stub).
- **Stage 2 — JVM path** (Jakarta Mail + Mime4j, all riding `TunnelResolver.socketFactory`):
  - 2a. **Generic IMAP/SMTP** password/app-password — proves the JVM engine over the tunnel against a local Dovecot (lowest friction, no OAuth).
  - 2b. **Gmail** XOAUTH2 — deep-link consent (step-ca reuse) + `OAuth2Token` store + refresh.
  - 2c. **Microsoft** OAuth2 — IMAP/SMTP for personal MSA + work/school.
- **Stage 3 — Offline:** Room store + sync state machine + threading + search.
- **Stage 4 — Push & polish:** IMAP IDLE / Gmail Pub-Sub / Graph subscriptions, notifications, attachments.
- **Stage 5 — Agent/MCP mail tools:** list/search/send/triage, added to the existing MCP tool surface (`app/.../agent/McpTools.kt`).
- **Stage 6 — Modern protocols:** Microsoft **Graph** (`Mail.Read`/`Mail.Send`) and Fastmail **JMAP** as cleaner HTTP alternatives to IMAP (still ride `socketFactory`/proxy).

## Verification

- **Unit:** `MailCryptoService` PGP round-trips (encrypt→decrypt, sign→verify) against known vectors; `emailAuthMethods` parsing tests (mirror existing `ConnectionProfile` tests).
- **Bridge:** gomobile build emits `libgojni.so` containing mailbridge symbols; Go test for Proton login/list/fetch against a test account or recorded fixtures.
- **End-to-end (real Proton test account):** add an EMAIL(Proton) connection bound to a Tailscale tunnel + a knock/SPA-guarded path; connect → confirm pre-connect packets in the connection log → folders list → open an encrypted message (decrypts) → send a signed message. **Negative test:** with the tunnel down / source IP not SPA-authorized, the connection must fail — proving traffic actually rides the chain.
- **Stage 2 (generic IMAP):** connect to a local Dovecot (in proot) over the tunnel; list/read/send via the JVM path.
- **Stage 2 (Gmail/Microsoft OAuth):** with a BYO OAuth client, the deep-link consent returns to the app, the token persists encrypted, an **expired access token refreshes** via `offline_access`, and an XOAUTH2 IMAP/SMTP round-trip succeeds over the tunnel. **Negative:** a revoked/expired token surfaces a re-auth prompt, not a silent failure.
- **Build/F-Droid:** `./gradlew assembleRelease` with the extended gomobile bind; deps already build in the rclone module, so reproducibility should hold — confirm.
- **Explicitly out of scope for v1:** offline store, push, threading, full-text search, attachments beyond inline — deferred to stages 3-4.

## Reused building blocks (don't rebuild)
- Transport: `core/tunnel/TunnelResolver.kt` (`dial`/`socketFactory`/`socksEndpoint`), `Tunnel.kt`, `TunnelManager.kt`.
- Pre-connect: `core/spa/SpaSender.kt`, `core/knock/PortKnocker.kt`, hook in `ConnectionsViewModel` (~`:969-1012`).
- Auth/crypto at rest: `ConnectionProfile.authMethods` model, `core/security/CredentialEncryption.kt`, `Totp.kt`, `SshKeyRepository`/`TotpSecretRepository`.
- OAuth (Gmail/Microsoft): `core/stepca/OidcAuthClient.kt`, `Pkce.kt`, `OidcRedirectActivity.kt`, `OidcRedirectBus.kt`, and the `CustomTabsIntent` launcher — generic PKCE auth-code + deep-link redirect, reused rather than rebuilt.
- Session/bridge/nav patterns: `core/rclone/RcloneSessionManager.kt`, `RcloneClient.kt`, `rclone-android` gomobile build, `feature/sftp` (TransportSelector/Screen/ViewModel), `HavenNavHost` rclone wiring.
- Message crypto libs (vendored): `gopenpgp/v2`, `go-proton-api`, `gluon`, `go-message` in `rclone-android/go/go.mod`.
