# The MCP backbone — re-architecture

Status: **design / forward-looking.** This plans a rebuild of Haven's agent
transport. It is written to the depth [VISION.md](../../VISION.md) demands
because the MCP endpoint is not a feature — it is, per the vision's own words,
*"the moat"* (§Face, §1a/§1b). A moat that is less reliable and less secure than
the SSH terminal sitting beside it is a liability, not a moat. This document is
the plan to make the agent transport a first-class carrier: **equal to SSH in
reliability, and stronger than it is today in security**, without losing the
consent model that is the one part already built right.

The companion inward-broker design is [bridges.md](bridges.md); this is its
transport-layer sibling.

---

## 1. Thesis

VISION §1a describes what shipped: *"MCP / JSON-RPC over HTTP loopback (port
range 8730–8739), Streamable HTTP stateless transport."* That sentence is
accurate and it is also the whole problem. The endpoint is:

- a **hand-rolled HTTP/1.1 server** on raw `ServerSocket`s and daemon threads,
  living inside the same 1,537-line class as the transport and the same process
  as the UI;
- **started from exactly one place** — a preference collector in
  `HavenApp.onCreate` — and never health-checked or restarted on foreground,
  network change, or a timer;
- trusted on the basis of **network position and a self-asserted name**, with
  **no cryptographic authentication anywhere in the path**;
- **plaintext at the MCP layer** on every carrier, borrowing whatever security
  the carrier happens to provide.

The SSH terminal — the thing the user rightly compares it to — is none of these.
It is a mutually authenticated (host-key TOFU + user key/FIDO), encrypted,
integrity-protected, persistent channel owned by a `specialUse` foreground
service with active reconnect. MCP has *no* equivalent at its own layer. It is a
tool surface bolted onto a socket, defended entirely by the carrier under it —
and its default carrier trust assumptions are weaker than the ones SSH makes.

The security rent VISION demands of *"every feature"* has not been paid on the
backbone. This plan pays it.

### Every claim here is grounded

This design follows three full read-throughs of the backbone (transport,
security/consent, dispatch/tool/client). Where a risk rests on Android platform
behavior rather than a line of Haven code — cross-app loopback reachability,
browser DNS-rebinding to loopback, SQLCipher-at-rest — it is flagged as
**platform-inferred, not PoC'd**. Under-claiming is the house style.

---

## 2. The backbone as it is today

### 2.1 Transports — five carriers, one gate, one-shot framing

Five paths reach the server; all funnel into one `handleConnection(input,
output, isLoopback)` so pairing/consent/audit are identical regardless of path
(a genuine strength worth preserving):

| # | Carrier | Setup | Default | Origin seen |
|---|---------|-------|---------|-------------|
| 1 | **Loopback** | `ServerSocket(127.0.0.1)`, first free of 8730–8739 | on | `isLoopback=true` |
| 2 | **LAN** | kernel socket on the Wi-Fi IPv4 | off | `isLoopback=false` |
| 3 | **WireGuard** | userspace `TunneledServerSocket` on the WG IP | on | `isLoopback=false` |
| 4 | **near SSH `-R`** | `McpNearCarrier` rides the user's interactive session, `-R 127.0.0.1:8730→127.0.0.1:8730` | — | **`isLoopback=true`** |
| 5 | **headless SSH `-R`** | `McpTunnelManager`'s own reconnecting session, carries adb + guest-service forwards | — | per-forward |

Clients do not connect to these directly — they point at a **local failover
proxy** (`~/.local/bin/haven-mcp-failover.py`) on `127.0.0.1:8788`, which picks a
backend per request (near `-R` first, then LAN via `ip neigh`). The proxy exists
because the server **closes the TCP connection after every response** — no
keep-alive — and a naive byte-splice propagated that close and broke the MCP
client's second keep-alive request with `ECONNRESET`. The proxy terminates
client keep-alive locally and opens a fresh backend per request. That contract —
one request per connection, `Content-Length`-framed, ports 8730–8739 — is load
bearing and any rewrite must honor or retire it in lockstep.

The framing itself (`handleConnection`) is hand-rolled: request-line split,
header loop that extracts only `content-length` and `mcp-session-id`, and a body
read of:

```kotlin
val buf = CharArray(contentLength)          // no upper bound
while (read < contentLength) { … reader.read(buf, …) … }   // chars vs a byte length
```

Two defects live in those two lines (§2.3).

### 2.2 Lifecycle — why it dies backgrounded and does not revive

`mcpServer.start()` has **one caller**: the `mcpAgentEndpointEnabled` collector
in `HavenApp.onCreate`. `MainActivity.onResume` never (re)starts or health-checks
it. The accept loops are **daemon threads in the Application process** (WG uses a
`Dispatchers.IO` coroutine). When Android reclaims the process — LMK, task
swipe, Doze, `specialUse` FGS pressure — every daemon thread dies with it. The
`specialUse` FGS makes the process *harder* to reclaim; it does not keep a dead
thread alive.

The `McpForegroundParticipantModule` shim is **passive**. It contributes:

- `activeSessions` — reads `server.isRunning`; `disconnectAll()` can `stop()` but
  **never `start()`**;
- `ForegroundKeepAlive.isActive = server.isRunning` — keeps the FGS alive *while*
  the server runs, but can't resurrect it;
- `ForegroundReviveHook.reviveNow()` — kicks the **`McpTunnelManager` tunnel**,
  **not** the MCP accept loop.

So on return-to-foreground / network-available, SSH sessions and the headless
tunnel revive — but nothing re-checks the MCP accept loop. `start()` even has an
`isHealthy()` guard that heals a "process-suspend zombie" (`isRunning==true` but
socket/thread dead) — but only if `start()` is *called*, and it is not, outside
the pref collector. **A zombie server in a surviving process stays wedged until
the user toggles the endpoint.** This is exactly the outage observed in the
field: the near SSH tunnel stayed up, the phone's MCP server behind it was dead,
and `/mcp reconnect` couldn't fix it because that only re-dials the client→proxy
leg. The failover proxy papers over the *process-restart* case; it cannot paper
over a live-process zombie.

### 2.3 Security — the weak half

The consent *state machine* is carefully built and well-tested (§4). The
*authentication and transport-trust* layer is where the moat leaks.

**No cryptographic client authentication exists.** Trust is decided by two
mechanisms, both spoofable:

1. **Network position.** `trusted = isLoopback && trustLoopbackEnabled`, and
   `trustLoopbackMcpClients` **defaults to true**. A trusted request skips *both*
   pairing *and* all per-action consent. So by default, anything arriving on
   127.0.0.1 gets consent-free access to every tool — including destructive ones.
2. **A self-asserted name.** Off-loopback, pairing checks `clientName in
   allowlist`, where `clientName` is `clientInfo.name` — chosen by the client. It
   is not a credential. A LAN/WG peer that guesses a paired name (`"claude-code"`)
   passes the gate. Worse, **every grant** — session-allow, persistent bypass,
   Tier-3 standing policy, the notification-Allow window — is keyed on this same
   spoofable name, so a client that names itself after a trusted one **inherits
   its grants**.

**The `isLoopback` inference is the root flaw**, and it fails in two directions:

- **Co-resident apps (platform-inferred).** On stock Android, 127.0.0.1 is not
  per-app-namespaced; any app with `INTERNET` can connect to another app's
  loopback listener. The server's own kdoc defends auto-trust with *"all local
  processes on Android already have at least as much access to this app's
  data"* — which is **false** for the app sandbox. A co-resident malicious app,
  or a **web page** the user opens (the endpoint answers `OPTIONS` and every
  response with `Access-Control-Allow-Origin: *`, validates no `Host`/`Origin`,
  and auto-trusts loopback → DNS-rebinding-class drive-by), reaches the full tool
  surface with no consent.
- **Reverse-tunneled remote traffic — highest impact.** Both the near carrier
  and the tunnel manager forward with `targetHost=127.0.0.1`, so traffic from the
  *remote SSH host's* localhost is delivered to the **phone's** loopback →
  `isLoopbackAddress==true` → auto-trusted. `isLoopback` cannot distinguish an
  on-device process from tunneled-remote traffic. **Any process or user on the
  workstation the phone tunnels to reaches Haven consent-free.** This is the
  default agent-driving path.

**Plaintext at the MCP layer on every carrier.** No TLS. WG and SSH encrypt the
*carrier*, so the bytes are protected in transit there; the **LAN binder**
(off by default) exposes plaintext MCP on the Wi-Fi IP, and write-tool arguments
carry secrets (`create_connection` passwords, `set_ssh_key_option`,
`create_totp_secret`) in cleartext to any LAN sniffer. Redaction is audit-DB-only
and never touches the wire.

**Parser DoS.** `CharArray(contentLength)` with no cap → a `Content-Length:
2000000000` forces a ~4 GB allocation → `OutOfMemoryError`, reachable from any
carrier. Header count/length is unbounded (Slowloris, bounded only by the 70 s
`soTimeout`). Thread-per-connection with no pool. The byte-vs-char body read also
truncates multibyte UTF-8 bodies → the read loop never completes → 70 s hang
(ASCII JSON dodges it today).

### 2.4 The tool + client layers

- **`McpTools.kt` is an 11,667-line God file**: one class, ~50 constructor deps,
  **182 tools in a single `linkedMapOf`**, schemas hand-built inline with an
  `org.json` DSL, no compile-time arg/schema validation. Every new tool and every
  domain (mail/usb/desktop/rclone/tunnels) edits one file — a permanent
  merge-conflict choke point. Capability gates (`serve_file`,
  `queue_terminal_input`) are special-cased **by tool name inside the transport
  layer**, and a reserved-key content channel (`__mcpContent`/`__imageBase64`)
  is an implicit handler↔server contract.
- **Two independent hand-rolled JSON-RPC stacks.** The server and
  `GuestMcpClient` each re-implement framing/session/SSE on `org.json` + raw
  sockets. No batch, no server→client push (`GET /mcp` → 405), `listChanged`
  hardwired false, no protocol-version negotiation (the client's requested
  version is read then ignored).
- **§1b (Haven as MCP client of other apps) is essentially unbuilt.**
  `GuestMcpClient` only proxies an in-proot server the agent itself launched and
  registered by port. The vision's Android `<intent-filter>`/`meta-data` app
  discovery, Settings capability bundles, and unified one-surface proxying do not
  exist.

### 2.5 SSH terminal vs MCP today — the gap in one table

| Property | SSH terminal | MCP backbone today |
|---|---|---|
| Client auth | user key / FIDO2, host-key TOFU | **none** (position + spoofable name) |
| Encryption/integrity | always (SSH transport) | **none at MCP layer**; borrowed from carrier; plaintext on LAN |
| Persistence | one warm channel, keep-alive | **connection-per-request**, one-shot |
| Lifecycle owner | `specialUse` FGS + active reconnect | Application-process daemon threads, **single-caller start** |
| Self-revive | yes (reconnect, revive hooks) | **no** — zombie until pref toggle |
| Trust boundary | possession of a private key | *"can deliver bytes to the socket"* |

The consent gate is the one column where MCP is genuinely ahead of a raw shell —
and the plan keeps it.

---

## 3. Design principles (the invariants)

A re-architecture earns the right to touch this code only if it preserves what is
already correct. From the tests and VISION:

1. **UI is ground truth; no hidden channel.** Every agent action and reach-out
   lands on a surface the user can see (VISION §4). The transport is addressable
   from outside the UI process precisely so the agent is *another caller on the
   same surfaces* — never a bypass layer.
2. **The tier is a property of the verb, not the caller** (VISION §Consent
   tiers). A human tapping a Tier-4 action *is* the consent; an agent calling it
   prompts. Reflexes never self-escalate.
3. **Fail-closed when no foreground activity can render the prompt.** Consent
   returns DENY immediately when backgrounded; it never silently waits or
   auto-allows. This is the strongest property in the current design.
4. **DENY is never cached; only ALLOW is.** A misclick can't lock the agent out;
   a denial can't be replayed into an allow.
5. **The consent sheet is non-dismissable; back = Deny.**
6. **Standing policies can only *remove* a prompt the user pre-authorized, never
   widen scope.** Denylist of self-escalating tools enforced at create *and*
   eval; server-side rate/expiry clamps.
7. **Pairing is add-only via an on-device human tap.** No MCP tool can self-add a
   client or grant auto-approval.
8. **Everything is audited, including DENY, with redaction owned by the
   recorder.**
9. **Deterministic ports (8730–8739)** so cached clients, the `-R` forwards, and
   the proxy agree; **MCP-over-SSH forwards are non-critical** so a bind failure
   never tears down the user's terminal.
10. **MCP is the moat — keep it Haven-specific.** Do not genericize the backbone
    into a shared cross-project spec; composition over SSH/RFB is the product.

Everything below changes *how trust is established and how the server stays
alive*; none of it weakens 1–10.

---

## 4. Target architecture

Six layers, each a clean seam. The current code collapses transport, protocol,
tools, auth, and lifecycle into two God classes; the target separates them so
each can be tested, hardened, and reused (host *and* client) independently.

### Layer A — one MCP protocol core (shared by host and client)

Extract a single `mcp-core` module owning framing, JSON-RPC 2.0 (with batch),
the streamable-HTTP session lifecycle, protocol-version negotiation, typed
request/response/notification/content models, and error mapping. Both the server
and `GuestMcpClient` sit on it — the second hand-rolled stack disappears.

**Framing decision.** Replace the hand-rolled parser with a real embedded HTTP
engine so the parser-DoS *class* is gone (bounded bodies, correct byte framing,
header caps, a bounded worker pool, optional keep-alive). Two candidates:

- **Ktor CIO server engine** — coroutine-native, already the concurrency model
  the WG path uses; proven on Android. Adds Ktor to the graph.
- **Official Kotlin MCP SDK** (`io.modelcontextprotocol:kotlin-sdk`) — spec
  framing + client/server for free, but drags Ktor + `kotlinx-io` and its own
  opinions, and must clear F-Droid reproducible-build + Gradle
  dependency-verification.

Recommendation: adopt a minimal real HTTP engine (Ktor CIO) for framing and keep
a **thin, Haven-owned JSON-RPC/MCP layer** on top, rather than the full SDK — the
SDK's surface is larger than Haven needs and the consent/audit wiring is
bespoke. This is the ladder's rung-4 answer (use an installed-class dependency
for the hard part — HTTP framing — and hand-write only the thin part that is
genuinely Haven-specific). Keep-alive then becomes possible, which lets the
failover proxy be **retired** rather than perpetually maintained (Stage 4).

### Layer B — carriers with *explicit* origin trust

Kill the `isLoopback ⇒ device-trust` inference. Each carrier tags the true trust
origin of the sockets it accepts, set at bind time, not read from the peer
address:

- `ORIGIN_DEVICE` — a genuine on-device local process.
- `ORIGIN_TUNNELED` — a reverse-tunnel (`-R`) carrier. **Never** device-trusted,
  because the far end is a remote host.
- `ORIGIN_LAN` / `ORIGIN_WG` — networked peers, always full gate.

The near and headless `-R` carriers must **not** be routed through the
device-trusted binder. The cleanest way to make `ORIGIN_DEVICE` mean what it
says is a **Unix-domain socket with `SO_PEERCRED`** for the on-device path:
peer-uid is checked, so a co-resident app is a *different uid* and is refused
device-trust; a `-R` tunnel can't forge a uid. TCP loopback stays available for
clients that need it (adb-forward), but at `ORIGIN_LAN`-equivalent trust (full
gate + token), not auto-trust. This single change closes the co-resident-app,
DNS-rebinding, and reverse-tunnel holes at once.

### Layer C — real per-client authentication

At pairing time, mint a **per-client secret** (random token) bound to the
authenticated identity and returned once to the client. Every subsequent request
carries it (`Authorization: Bearer …` / bound to the `Mcp-Session-Id`). The
allowlist, bypass set, and standing policies key on the **authenticated
identity**, not `clientInfo.name`. Consequences:

- Name-spoofing dies (identity is a secret, not a string).
- Grant inheritance dies (grants bind to identity).
- A tunneled or LAN client with no token can `initialize` and prompt for
  pairing, but cannot inherit another client's trust.
- DNS-rebinding dies (a browser page has no token).

`ORIGIN_DEVICE` (uid-checked Unix socket) may skip the token as today's loopback
does — but that is now a *real* device-local guarantee, not a spoofable one.

### Layer D — service-owned, actively supervised lifecycle

Move server ownership out of the `HavenApp` pref collector and into the FGS
domain (`SshConnectionService` / `SessionManagerRegistry`), as an **active**
participant:

- A real `ForegroundReviveHook` for the MCP server (not just the tunnel) that
  calls `isHealthy()` and `start()` on return-to-foreground, on
  network-available, and on a heartbeat — the same triggers SSH reconnect uses.
- Restart the accept loop on death; treat a zombie (`isRunning && !healthy`) as
  dead.

Net: **MCP availability becomes `FGS-alive`, the same bar as SSH** — the
outage that needed a manual endpoint toggle becomes a self-heal. The failover
proxy's role shrinks to carrier-selection (near vs LAN vs WG) and, once
keep-alive lands (Layer A), can be retired.

### Layer E — the tool layer as a registry of providers

Split `McpTools.kt` into per-domain `ToolProvider`s (namespace, runtime, gateway,
presence, brokers, …) contributed via Hilt multibinding into a `ToolRegistry`.
Make the cross-cutting concerns **declarative on the tool**, not special-cased in
the transport:

- `capability` gate (feature on/off) as a field, not a name check in
  `handleToolsCall`;
- `consentLevel` + `summary` as today, but co-located with the handler;
- a **typed result model** (text / image / file-ref / guest-passthrough) that
  retires the reserved `__` keys;
- a schema DSL (or codegen from Kotlin types) to kill the inline `org.json`
  boilerplate and give compile-time validation.

This is also what makes §1b tractable: external tools are just another provider.

### Layer F — bidirectional (§1b) on the shared core

With Layer A, Haven-as-client is first-class. A `ClientRegistry` + pluggable
discovery: the in-proot port registration that exists today, and — later — the
Android `<intent-filter>` + `meta-data` enumeration VISION §1b specifies (no
external bytecode; each plugin a separately source-built APK, so F-Droid
reproducibility holds). External tools fold into the one surface through the same
consent + audit stack, indistinguishable to the agent. Server→client
notifications (SSE, now that the core supports it) deliver `listChanged` so a
newly-discovered capability appears without a poll.

---

## 5. Security hardening — the concrete checklist

Ordered by impact. Items 1–5 are the ones that make MCP *safe*; several are
small enough to ship ahead of the full re-arch (Stage 0).

1. **Explicit per-carrier origin trust; tunneled carriers are never
   device-trusted** (Layer B). Closes the reverse-tunnel hole.
2. **`ORIGIN_DEVICE` = uid-checked Unix socket** (or, minimally, default
   `trustLoopbackMcpClients` to **off**). Closes co-resident-app trust.
3. **Real pairing token; identity-keyed allowlist and grants** (Layer C). Closes
   name-spoofing, grant inheritance, DNS-rebinding.
4. **No plaintext on LAN** — require an encrypting carrier or add TLS to the LAN
   binder. Stops secret-argument leakage.
5. **Parser caps** — bound `Content-Length` (cap the buffer), cap header
   count/size, read the body as **bytes** against the byte length, bounded worker
   pool, validate `Host`/`Origin`, and **drop `Access-Control-Allow-Origin: *`**
   on the state-changing endpoint. Closes the OOM, the multibyte hang, Slowloris,
   and browser CSRF. *(Stage-0 candidate — no protocol change.)*
6. **Notification-Allow window** (`armRetryWindow`) → operation-scoped and
   single-use, matching the `EVERY_CALL` `retryGrant` semantics (today it is a
   120 s client::tool window that admits differing arguments).
7. **Redaction beyond key-name and beyond the audit DB** — content-based secret
   detection (so a password in `send_terminal_input.text` or a `url`/`command`
   arg is caught), scrub client names from Logcat, and confirm/enable
   **SQLCipher at-rest** for the audit table *(at-rest encryption unverified —
   confirm during Stage 0)*.

---

## 6. Migration — staged by leverage, not effort

Big-bang rewrites of a load-bearing transport are how you ship a broken v5.59.3.
Each stage is independently shippable and ordered so the reliability + security
wins land first, protocol churn last.

- **Stage 0 — safe hardening (days).** Parser caps + byte-accurate body +
  bounded pool + `Host`/`Origin` validation + drop `ACAO:*` on POST (§5.5);
  tighten the notification-Allow window (§5.6); confirm audit at-rest encryption
  (§5.7). No wire-protocol change, no client change. Closes the OOM, the
  multibyte hang, and browser CSRF immediately.
- **Stage 1 — reliability (the outage fix).** Active MCP revive hook +
  service-owned supervision (Layer D). MCP self-heals to SSH's bar. No protocol
  change; the failover proxy stays.
- **Stage 2 — trust origin.** Explicit per-carrier origin tags; tunneled
  carriers off the trusted binder; default `trustLoopback` off or the
  uid-checked device socket (Layers B). Highest-impact security change; carrier
  wiring only, tools untouched.
- **Stage 3 — authentication.** Pairing token; identity-keyed allowlist/grants
  (Layer C). Requires a one-time re-pair of existing clients (surfaced as a
  prompt).
- **Stage 4 — protocol core + keep-alive.** Extract `mcp-core` on a real HTTP
  engine (Layer A); retire the second hand-rolled stack; add batch / SSE /
  version negotiation. Keep-alive lands → **retire the failover proxy** in
  lockstep with the proxy-contract invariant.
- **Stage 5 — tool registry + §1b plugin bus.** Split `McpTools` into providers
  (Layer E); build Android-app MCP discovery on the shared client core (Layer F).

Stages 0–2 are the ones that make the moat defensible and are worth prioritizing;
3–5 are the ones that make it *architecturally* first-class and unlock the
bidirectional vision.

---

## 7. Scope boundaries

- **Not an SDK adoption for its own sake.** Bring in a real HTTP engine for
  framing; keep the MCP/consent/audit layer Haven-owned. No external bytecode —
  F-Droid reproducibility is non-negotiable (VISION §1b).
- **MCP stays the moat.** No generic cross-project transport spec; the backbone
  is Haven-specific by design.
- **Don't break the failover-proxy contract** (one-shot, `Content-Length`, ports
  8730–8739) except by retiring the proxy in the same change (Stage 4).
- **The consent model is not up for redesign** — it is the part already right.
  This plan changes authentication and lifecycle *underneath* it, not the tiers,
  the fail-closed rule, or the verb-not-caller principle.

---

## 8. Open questions

1. **Unix socket vs TCP loopback for `ORIGIN_DEVICE`** — a Unix socket gives
   `SO_PEERCRED` uid auth but some MCP clients only speak TCP; may need both,
   with TCP loopback demoted to full-gate + token.
2. **Failover proxy: retire or keep?** Keep-alive (Stage 4) removes its
   *reason*, but it also does carrier-selection (near/LAN/WG). Decide whether
   that logic moves into the client's MCP config or a slimmer proxy.
3. **Kotlin MCP SDK vs Ktor-CIO-plus-thin-layer** — settle after a spike that
   measures the SDK's transitive weight against dependency-verification cost.
4. **Audit at-rest encryption** — is the Room DB already SQLCipher-backed? If
   not, Stage 0.
5. **Token delivery to headless/cron agents** — how a non-interactive client
   obtains its pairing token without a human tap (a scoped provisioning flow?).

---

*This document is the transport-layer half of "every feature must pay its
security rent" (VISION §Architectural direction, layer 3). The tool surface is
wide and the consent model is sound; the backbone underneath them is the debt.
This is the plan to retire it.*
