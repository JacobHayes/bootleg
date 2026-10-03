# Bootleg remote protocol v1

The contract between the browser UI / `bootleg` CLI, the Go transport (`remote/`) and the Kotlin
controller (`core/src/main/kotlin/dev/jacobhayes/bootleg/core/remote/`). Wire names live in
`remote/protocol/protocol.go` (Go) and `core/remote/Protocol.kt` (Kotlin); `ProtocolTest` checks
that both agree and that every request type, push type, error code and close reason appears in
this file, so update all three together.

Kotlin holds all authority (auth, pairing, lease, mutations). Go holds none: it carries bytes,
establishes identity from the accepted connection, and enforces transport limits.

## 1. Go ↔ Kotlin bridge (gomobile)

Session IDs are `int64`, chosen by Go, unique for the life of the process (never reused). For one
session the calls arrive in order: `open`, then `command`/`hid`, then exactly one `closed` after
the last command or frame.

### Go → Kotlin (`RemoteActor`)

Every call only enqueues and returns within 20 ms, on any goroutine. Go never holds a lock across
a call.

| Call | Kotlin side | Notes |
|---|---|---|
| `open(sessionId, connJson)` | `RemoteActor.open` | Never dropped. |
| `command(sessionId, json)` | `RemoteActor.command` | One WebSocket text message (≤ 64 KiB). If the session's 64-entry queue is full, Kotlin closes the session with `rate_limited`. |
| `hid(sessionId, bytes) → int` | `RemoteActor.hid` (`true` → 0 queued, `false` → 1 queue_full) | One binary message. On queue_full the frame is dropped and Kotlin releases the holder's keys on its priority lane; Go keeps reading. A release-all frame (op `05`) is always queued. |
| `closed(sessionId, reason)` | `RemoteActor.closed` | Idempotent; never dropped. `reason` is Go's: `client_closed`, `dead` (no pong for 15 s), `slow_consumer`, `rate_limited`, `malformed`, `version`, `revoked`, `unauthorized`, `listener_stopped`, `write_failed`, `pair_done`. Kotlin treats every one as the end of the session (release-all if it held the lease). |

Before Kotlin calls `authorized(sessionId)`, Go forwards **only** the session's first `hello` and
drops binary frames; other requests get `not_authorized` from Go. Authorization fails closed.

#### `connJson`

Identity comes from the accepted connection and listener, never from request data.

```json
{"transport":"tailnet","remoteAddr":"100.64.0.7:51234","identity":{"kind":"node","nodeId":"nXXXXCNTRL","user":"alice@example.com","tags":[],"name":"alices-mac"},"browser":true,"userAgent":"Mozilla/5.0 ..."}
{"transport":"hotspot","remoteAddr":"192.168.49.23:51234","identity":{"kind":"token","tokenHash":"<64 lower-case hex>"},"browser":false,"userAgent":"bootleg/1"}
{"transport":"hotspot","remoteAddr":"192.168.49.23:51234","identity":{"kind":"pair","tokenHash":"<64 lower-case hex>"},"browser":false}
```

- `transport` is `tailnet` (identity kind `node`) or `hotspot` (kind `token` or `pair`); any
  other combination is malformed.
- `node`: `nodeId` (required, the Tailscale stable node ID), `user` (login name, empty for tagged
  nodes), `tags` (≤ 32), `name`. Each string ≤ 256 characters. `user`, `tags`, `name` and
  `userAgent` are untrusted display text.
- `token`: `tokenHash` is the lower-case hex SHA-256 of the presented token's ASCII text (bearer
  header for non-browser clients, `bootleg_token` cookie for browsers). Go never forwards a raw
  token. An unknown or expired hash is closed at once with `unauthorized`.
- `pair`: a `/pair` exchange (§3); `tokenHash` is the hash of the token Go just minted.
- Unknown extra fields are ignored. Malformed `connJson` (or over 16 KiB of UTF-8) is closed with `malformed`.

### Kotlin → Go (`TransportPort`)

| Call | Meaning |
|---|---|
| `authorized(sessionId)` | Forward everything from this session from now on. |
| `send(sessionId, json) → bool` | Queue one text message (≤ 32 per session). `false` if the session is gone, closing, or its queue is full (Go then closes it as `slow_consumer`); Kotlin ends the session too. |
| `close(sessionId, reason)` | Flush queued messages, then close with `reason` in the close frame (codes below); idempotent. |
| `revoke(kind, id) → int` | `kind` `node` with a stable node ID, or `token` with a token hash: close every live connection of it with `revoked`. Kotlin also closes each session it knows. |

Late callbacks are fenced by the lease epoch in Kotlin, not by timing.

#### Close reasons (Kotlin → Go)

Go maps a reason to a WebSocket code with `protocol.CloseCodeFor`; reasons it doesn't name get
4000 and the reason text tells the client why.

| `reason` | WebSocket code | When |
|---|---|---|
| `version` | 4400 | `hello.v` is not 1. |
| `revoked` | 4401 | Approval or token revoked in the app (or evicted by a newer one). |
| `expired` | 4401 | The approval (180 days) or token (90 days) expired. |
| `unauthorized` | 4401 | A hotspot token Kotlin doesn't know. |
| `malformed` | 4402 | Bad JSON envelope, missing/oversized `id`, a second `hello`, a malformed binary frame, a `pair` command outside a `/pair` exchange, bad `connJson`. |
| `slow_consumer` | 4408 | `send` returned false. |
| `rate_limited` | 4429 | Kotlin's 64-entry inbound queue for the session overflowed. |
| `listener_stopped` | 4503 | The hotspot stopped. |
| `denied` | 4000 | The user denied a pending node. |
| `pending_evicted` | 4000 | A newer unknown node pushed this pending node out (at most 4 pending). |
| `too_many_connections` | 4000 | More than 8 sessions, or more than 4 unauthorized (Go normally refuses these first with HTTP 503). |
| `shutdown` | 4000 | Remote turned off, or the controller failed closed. |

## 2. WebSocket protocol (`/ws`)

Text messages are JSON objects; binary messages are HID frames. Null fields are omitted on output
(absent means null). Unknown request fields are rejected (`bad_request`).

### 2.1 Handshake

The first message is `{"id":0,"t":"hello","v":1}` (`id` is ignored). Kotlin answers with a push:

```json
{"t":"welcome","v":1,"sessionId":17,"auth":"approved","allowRw":false,"stateRev":12,"lease":{"holder":3,"holderName":"alices-mac","epoch":41,"local":false}}
```

- `auth`: `approved`, or `pending` (unknown tailnet node waiting for approval in the app: no state,
  no control; its requests get `pending`).
- `stateRev` and `lease` only when `approved`; a `state` push follows immediately.
- When the user approves a pending node, its sessions get `{"t":"auth","auth":"approved","allowRw":false}`
  and then `state` and `lease` pushes. The same push announces a changed `allowRw`.
- A second `hello` closes with `malformed`; `v` ≠ 1 closes with `version`.

A reconnect is a fresh session with a fresh snapshot; nothing is replayed.

### 2.2 Requests and replies

```json
{"id":7,"t":"lease.take"}
{"id":8,"t":"slot.eject","epoch":41,"lun":0,"force":false}
```

- `id`: an integer or a non-empty string, ≤ 64 characters as JSON; replies echo it exactly. A
  missing or invalid `id` closes the session with `malformed`. Kotlin remembers the last 64 IDs
  per session: a repeat gets the cached reply and is never executed twice; a repeat of a request
  whose reply is still pending is answered once, when it completes.
- `epoch`: integer 0–65535, the lease epoch the client believes it holds.
- Replies carry no `t`. Result fields sit beside `id` and `ok`:
  `{"id":7,"ok":true,"epoch":42,"lease":{...}}`, or
  `{"id":8,"ok":false,"error":{"code":"lease_held","msg":"..."}}`. `msg` is human-readable.
- Requests before `hello` get `hello_required`.
- Replies come in request order except deferred ones (`lease.takeover`, device operations,
  `paste.run`, `bootkey.run`, `download.start`, `download.cancel`), which come when they complete.

**Mutations** (M) need the lease and the current epoch in the envelope: not the holder →
`lease_required`; holder with a missing or different epoch → `stale_epoch`. The epoch is checked
when the request is admitted and again in the same actor step that dispatches its effect.

| `t` | Body | M | Reply fields |
|---|---|---|---|
| `hello` | `v` | | (`welcome` push) |
| `state.get` | — | | `state: State`, `lease: Lease` |
| `lease.take` | — | | `epoch`, `lease` |
| `lease.takeover` | `holderSession` (int); envelope `epoch` required | | `epoch`, `lease` (deferred) |
| `lease.takeover_reply` | `allow` (bool) | M | — |
| `lease.release` | — | M | `epoch`, `lease` |
| `slots.apply` | `slots: [{lun, imageId, mode?}]`, `ifRev` | M | `slotFailures` (deferred) |
| `slot.eject` | `lun`, `force`? (false), `ifRev`? | M | `slotFailures` (deferred) |
| `slot.reinsert` | `lun`, `ifRev`? | M | `slotFailures` (deferred) |
| `hid.set` | `keyboard`, `mouse`, `ifRev`? | M | `slotFailures` (deferred); the mouse layout stays the phone's setting |
| `tether.set` | `on`, `ifRev`? | M | `tether: Tether`, `warning`? (deferred) |
| `bootkey.run` | `vendor`, `action`, `keyIndex`? (0), `durationMs`? (20000) | M | `durationMs` (when the keys finish) |
| `bootkey.cancel` | — | M | `cancelled` |
| `paste.prepare` | `text` | M | `planId`, `unsupported`, `unsupportedCount`, `durationMs` |
| `paste.run` | `planId` | M | `durationMs` (when the typing finishes) |
| `paste.cancel` | — | M | `cancelled` |
| `catalog.list` | `arch`? (`x86_64`), `category`? (all), `distroId`? (all) | | `Catalog` fields inline |
| `download.start` | `entryId` | M | `entryId`, `fileName`, `result` (deferred) |
| `download.cancel` | `entryId` | M | `cancelled` (deferred) |

Details:

- **`lease.take`** succeeds only when the lease is free (or already yours: same epoch back).
  Otherwise `lease_held`, or `local_priority` while the phone's Control tab has it.
- **`lease.takeover`** names the holder and its epoch as the requester last saw them. Wrong holder
  or epoch → `lease_changed`; another takeover pending → `busy`; local capture → `local_priority`.
  Otherwise the holder gets a `lease.takeover_request` push and the requester's reply waits until
  the holder answers `lease.takeover_reply{allow:true}` (the lease moves), answers `allow:false`
  (`takeover_declined`), says nothing for 30 s (`takeover_declined`; the holder gets
  `lease.takeover_cancelled`), or the holder's session ends or releases the lease (the lease
  moves). Holder and epoch are re-checked at transfer (`lease_changed`). Nothing transfers on a
  timer.
- **`lease.takeover_reply`** without a pending takeover → `not_found`.
- **Local capture** (the phone's Control tab in the foreground) preempts any remote lease
  (`lease.local` true, no holder); remote `take`/`takeover` get `local_priority` until it ends.
- **`slots.apply`** carries the full desired slot list. `lun`s must be exactly `0..n-1`, n ≤ 8
  (`bad_request`). `imageId`s come from `state.images` or `state.slots` (`not_found` otherwise);
  clients never send paths. `mode`: `cdrom`, `disk_ro`, `disk_rw`; absent → the image's
  `defaultMode`. `disk_rw` (and `slot.reinsert` of an RW slot) needs the approval's "allow
  writable disks" flag, set only in the app (`rw_not_allowed`). HID functions stay as they are.
- **`ifRev`** must equal the current state `rev` in the actor step that starts the operation
  (`rev_conflict`). One device operation runs at a time (`busy`), counting one that timed out but is still running on the phone.
- **Device results**: `{"slotFailures":{}}`, or `{"slotFailures":{"<lun>":"reason"}}` when some
  slots failed and the rest were applied; errors `device`, `device_attention` (needs the user on
  the phone), `timeout` (10 s). A started operation finishes atomically even if the session ends;
  its reply then goes nowhere, and every session sees the result in `state`.
- **`tether.set`** turns the phone's USB tethering on or off. Either way USB re-plugs, so the
  phone refuses it with `disks_exposed` while any LUN holds media (it checks the live gadget, so a
  slot the host already ejected doesn't count); HID may stay on and loses the host for about a
  second, as with `hid.set`. Asking for the current state answers at once without a re-plug (also
  with disks exposed). Android failures (carrier entitlement, unsupported, nothing came up within
  5 s) are `device`. The reply carries the state Android reports afterwards, and `warning` (a
  string) when tethering changed but the gadget then needs attention on the phone (slot failures
  are named in it). It can take up to 75 s (`timeout` after that; it still finishes). Turning it
  off may cut the remote; clients always warn first, since they can't tell whether they reach the
  phone over that USB link.
- **`bootkey.run`**: `vendor` is a lower-case `BootVendor` (`generic`, `dell`, `lenovo`, `hp`,
  `asus`, `acer`, `msi`, `gigabyte`, `intel_nuc`, `apple_mac`, `microsoft_surface`; else
  `bad_request`); `action` is `menu` or `setup`; `keyIndex` picks among the preset's keys, most
  likely first (`not_found`). `durationMs` 1–30000. A tap key is mashed (every
  max(100, durationMs/200) ms); a modifier is held for `durationMs`, re-sent every 200 ms. Keys a
  USB keyboard can't send → `unsupported`.
- **Jobs** (`paste.run`, `bootkey.run`) answer when they finish: `ok` with `durationMs`, or
  `cancelled` (with the reason in `msg`) if cancelled, preempted, or the lease moved. One job at a
  time (`busy`); they need the keyboard on (`hid_off`) and no owed release-all (`release_pending`).
  While one runs, keyboard frames are ignored (mouse frames still work). `*.cancel` stops it and
  releases all keys; `cancelled` says whether one was running.
- **`paste.prepare`**: `text` ≤ 64 KiB of UTF-8 (`too_large`) and ≤ 5 minutes of typing at one
  report per 8 ms (`too_long`, about 18 700 characters). US layout: printable ASCII, `\n`, `\t`;
  other characters are skipped and listed in `unsupported` (`[{index, codePoint}]`, the first 64;
  `unsupportedCount` is the total). At most 4 plans per session (`too_many_plans`), each valid for
  2 minutes, single use, and void once the lease changes (`not_found`, `plan_expired`,
  `stale_epoch`).
- **`catalog.list`** needs an approved session, not the lease (like `state.get`). It answers from
  the app's cached catalog (the same one the phone's Catalog tab shows), never a scrape per
  request. `arch` is `x86_64` or `arm64`; `category` is `desktop`, `server` or `rescue`
  (`bad_request` otherwise); `distroId` picks one distro. The request also lets the phone refresh
  a stale catalog in the background (its own rule, as when the Catalog tab opens: each feed older
  than 6 h, never fetched, or failed more than 60 s ago), at most once per 60 s for all sessions
  together; `refreshing` says one is running and a `catalog` push follows when it ends. The reply is bounded: at most 256 entries and
  48 KiB of JSON; whatever doesn't fit is left out and `truncated` is true (narrow it with
  `arch`, `category` or `distroId`).
- **`download.start`** starts the catalog entry's download exactly as the phone's Catalog does:
  the same user-initiated job, resume, SHA-256 check, archive extraction and "Wi-Fi only"
  setting, landing in the library. Clients name entries by `entryId` only, never a URL or path.
  `result` is `started`, `active` (already queued or running) or `downloaded` (already in the
  library; nothing starts). A paused or failed download is resumed from its partial file. Errors:
  `not_found` (unknown entry), `too_many_downloads` (4 downloads already queued or running, from
  any source, or 64 download records; at most 2 transfer at once), `storage_low` (free space under
  what's left to fetch plus 512 MiB), `busy` (another `download.*` request is in flight), `device`
  (the phone refused to schedule it), `timeout` (10 s: the outcome is unknown, and the download
  may still start; `downloads` pushes show it).
- **`download.cancel`** stops a queued, running, paused or failed download and deletes its
  partial file and record, as the phone's Cancel does. `cancelled` is false when there was
  nothing to cancel (no download for the entry, or it already finished: delete a finished image
  on the phone). Same `busy` and `timeout` as `download.start`.
- **Downloads outlive the lease and the session.** They are not held input: a revoke, takeover,
  lease loss or disconnect leaves them running, and every approved session keeps seeing them in
  `downloads` pushes. A `download.*` reply whose session is gone goes nowhere.

#### Error codes (from Kotlin)

| `code` | Meaning |
|---|---|
| `bad_request` | Malformed body, wrong field types, unknown fields, out-of-range values. |
| `unknown_type` | Unknown `t`. |
| `hello_required` | Request before `hello`. |
| `pending` | Waiting for approval on the phone. |
| `lease_required` | You don't hold the lease. |
| `stale_epoch` | You hold the lease but sent a different (or no) epoch; or the paste plan belongs to an older lease. |
| `lease_held` | `lease.take` while someone else holds it. |
| `local_priority` | The phone's Control tab holds the lease. |
| `lease_changed` | `lease.takeover` named the wrong holder or epoch, or the lease moved before transfer. |
| `takeover_declined` | The holder said no or didn't answer in 30 s. |
| `busy` | A device operation, job or takeover is already running. |
| `rev_conflict` | `ifRev` is not the current state `rev`. |
| `not_found` | Unknown image, slot, plan or boot key, or no pending takeover. |
| `rw_not_allowed` | Writable disks are not allowed for this device. |
| `release_pending` | A release-all hasn't reached the host yet. |
| `hid_off` | The keyboard is off. |
| `too_large` | Paste over 64 KiB. |
| `too_long` | Paste over 5 minutes. |
| `too_many_plans` | 4 paste plans already. |
| `plan_expired` | The paste plan is over 2 minutes old. |
| `cancelled` | The job was cancelled, preempted, or lost its lease. |
| `unsupported` | That boot key needs a hardware button. |
| `pairing_disabled` | Pairing is off or the code expired. |
| `pairing_locked` | Too many pairing attempts; reset on the phone. |
| `bad_code` | Wrong pairing code. |
| `device` | The gadget operation failed. |
| `disks_exposed` | `tether.set` while a disk is exposed. |
| `device_attention` | The gadget needs the user's attention on the phone. |
| `timeout` | The device didn't answer within 10 s (75 s for `tether.set`). |
| `too_many_downloads` | 4 downloads are already queued or running. |
| `storage_low` | Not enough free space on the phone for that download. |

Go itself also answers `not_authorized` (request before authorization), `bad_request`,
`rate_limited` and `unavailable`.

### 2.3 Shared objects

```
Lease = {holder?: int, holderName?: string, epoch: int, local: bool}
        // holder: the holding session's id; absent when free or local
State = {rev: int, slots: [Slot], images: [Image], hid: Hid, tether?: Tether, lease: Lease}
Slot  = {lun: int, imageId: string, name: string, mode: "cdrom"|"disk_ro"|"disk_rw",
         status: "exported"|"host_ejected"|"user_ejected"|"failed"}
Image = {imageId: string, name: string, sizeBytes: int, defaultMode: "cdrom"|"disk_ro"|"disk_rw",
         verified?: true}
Hid   = {keyboard: bool, mouse: bool, wheel: bool, status?: "release_pending",
         link?: "stopped"|"connecting"|"open"|"reconnecting"|"failed"|"restart_required"}
Tether = {on: bool}   // the phone's USB tethering, as Android reports it; absent until known

Catalog  = {catalogRev: int, refreshing: bool, truncated: bool, distros: [Distro]}
Distro   = {distroId: string, name: string, category: "desktop"|"server"|"rescue",
            fetchedAtMs?: int, error?: string, entries: [Entry]}
Entry    = {entryId: string, version: string, variant?: string, arch: "x86_64"|"arm64",
            fileName: string, sizeBytes?: int, supportedUntil?: string, state: EntryState}
Download = {entryId: string, fileName: string, title: string, status: DownloadStatus,
            downloadedBytes: int, totalBytes?: int, verified?: true, error?: string}
EntryState     = "available" | DownloadStatus | "downloaded"   // downloaded: in the library
DownloadStatus = "queued" | "running" | "paused" | "verifying" | "completed" | "failed"
```

`Image.verified` is true when the image's SHA-256 matched the distro's published sum (a
`.sha256` sidecar from a finished download). `entryId` is opaque (16 hex characters), derived
from the entry's file name, so it is stable across catalog refreshes and also names the entry's
download; `catalogRev` counts catalog changes. `fetchedAtMs` is wall-clock Unix ms; `error` is
the last refresh's error for that distro (its entries are then the last good ones). A download
started on the phone shows up too. All strings are untrusted display text.

`imageId` is opaque (16 hex characters), stable for a path; a slot exposing a file outside the
image library still has one, so `slots.apply` can keep it. `hid.wheel` is true when the mouse is on with the scroll-wheel layout
(false with the phone's "Compatible mouse (no wheel)" setting, or when an older phone omits it):
clients send `06` frames only when it is true. `hid.status` is `release_pending` once
release-all retries have failed for 60 s; input is refused until the HID node reopens or HID is
turned off. `hid.link` (present while the keyboard or mouse is on) is the phone's HID session:
`stopped` until input opens it, `reconnecting` while the phone reopens its devices (after a USB
gadget change or re-enumeration; input is refused meanwhile, not queued), `failed` after the
reopen attempts ran out (the next input retries), and `restart_required` when only restarting the
phone brings the keyboard and mouse back.

### 2.4 Pushes

| `t` | Body | When |
|---|---|---|
| `welcome` | see §2.1 | Answer to `hello`. |
| `auth` | `auth`, `allowRw` | Approval granted or RW flag changed in the app. |
| `state` | `State` fields inline (`rev`, `slots`, `images`, `hid`, `tether`, `lease`) | State changed; ≤ 10/s per session (coalesced, latest wins). |
| `lease` | `lease: Lease` | Lease changed, or you sent input with a stale epoch; ≤ 10/s per session. |
| `lease.takeover_request` | `requester` (int), `requesterName`, `epoch`, `timeoutMs` | To the holder: someone asks for the lease. |
| `lease.takeover_cancelled` | `requester` | To the holder: the request was withdrawn or timed out. |
| `downloads` | `downloads: [Download]`, `truncated` | After `welcome` (approved) and whenever a download changes; ≤ 2/s per session (coalesced, latest wins). Active downloads first; at most 64 and 48 KiB, `truncated` when some finished or paused ones didn't fit. |
| `catalog` | `catalogRev`, `refreshing` | The cached catalog changed or a refresh started or ended; ≤ 10/s per session. Send `catalog.list` again for the entries. |

Pending sessions get no `state`, `lease`, `downloads` or `catalog` pushes.

### 2.5 Lease epoch

Every lease change (take, release, takeover, local capture start/stop, idle release, session end)
increments a 16-bit epoch (wrapping 65535 → 0). Every mutation and every binary frame carries the
epoch the client believes it holds; anything else is dropped (frames) or refused (requests).

End of a holder's session or lease (dead, revoke, takeover, close, release, idle, local capture),
in one actor step: bump the epoch → cancel jobs → drop that session's queued input → release-all
on the priority lane → refuse input until the HID session confirms the release reached the host.
Release-all is retried at +1, +3, +7, +15, +31 s; after 60 s `hid.status` is `release_pending`.

Idle (no input from the holder): after 30 s held keys and buttons are released (the lease stays);
after 10 minutes the lease is released.

### 2.6 Binary HID frames

`epoch (u16 big-endian) · op (u8) · operands`. Lengths are exact; anything else is malformed and
closes the session (`malformed`).

| op | Length | Operands | Meaning |
|---|---|---|---|
| `01` | 4 | usage `uu` | Key down, keyboard-page usage `0x04–0xE7` (others are malformed). Modifiers (`E0–E7`) set modifier bits; usages Bootleg has no key for are ignored. A duplicate down is idempotent. With more than 6 non-modifier keys down the report is rollover (all slots `0x01`). |
| `02` | 4 | usage `uu` | Key up. Same usage rules. |
| `03` | 5 | `dx dy` (int8) | Relative mouse move; senders never emit -128, receivers clamp it to -127; `0 0` is ignored. |
| `04` | 4 | `bb` | Mouse buttons: bit 0 left, 1 right, 2 middle; other bits → malformed. |
| `05` | 3 | — | Release all keys and buttons. Holder only, coalesced, exempt from the rate limit. |
| `06` | 5 | `wheel pan` (int8) | Scroll: `wheel` > 0 scrolls up, `pan` > 0 scrolls right, in detents. Same -128 rule as `03`; `0 0` is ignored. Ignored (not malformed) while the mouse is off or `hid.wheel` is false. No held state, so release-all is unaffected. Counts toward the frame rate limit. |

Frames from a session that isn't the holder, or with another epoch, are dropped and trigger a
`lease` push to that session. At most 200 frames per second per session (Go closes with
`rate_limited` beyond that; Kotlin drops the excess too).

## 3. Hotspot pairing (`POST /pair`)

Pairing is turned on in the app, which shows a 6-digit code: single use, valid 2 minutes,
invalidated when pairing or the hotspot stops. At most 10 attempts per hour (globally, persisted);
the 11th locks pairing until it's reset on the phone. Attempts while pairing is off don't count.

1. The client posts `{"code":"123456"}`.
2. Go mints a 256-bit token, opens a session with identity `{"kind":"pair","tokenHash":...}`
   and sends one command without `id` or `hello`:
   `{"t":"pair","code":"123456","tokenHash":"<hex>"}`.
3. Kotlin answers within 5 s with one message, `{"ok":true}` or
   `{"ok":false,"error":{"code":"bad_code","msg":"..."}}`, and on success stores the hash as a
   paired device (label = the user agent, 90-day expiry, revocable). One attempt per exchange; a
   second command gets `bad_request`. Kotlin doesn't close the exchange: Go does after the verdict
   (`closed(sessionId, "pair_done")`).
4. Go answers the HTTP request: browsers get the token as a host-only `HttpOnly; SameSite=Strict`
   cookie `bootleg_token`; the CLI gets the token in the body and keeps it in a 0600 file.

## 4. Limits

| Limit | Value |
|---|---|
| Protocol version | 1 |
| Connections / unauthorized connections | 8 / 4 |
| Pending tailnet nodes (oldest evicted) | 4 |
| Approvals / lifetime | 64 / 180 days |
| Tokens / lifetime / size | 16 / 90 days / 256 bits |
| Pairing code | 6 digits, 2 min, single use |
| Pairing attempts | 10 per hour, then locked until reset in the app |
| Inbound queue per session (Kotlin) | 64 + a priority lane (open, close, revoke, release-all) |
| Outbound queue per session (Go) | 32 |
| Text message | 64 KiB |
| `connJson` | 16 KiB of UTF-8 |
| Request id / cached replies | ≤ 64 characters / last 64 per session |
| Takeover prompt | 30 s |
| Idle: release keys / release lease | 30 s / 10 min |
| Release-all retries | up to 60 s, then `release_pending` |
| State and lease pushes | ≤ 10/s each per session |
| Binary frames | ≤ 200/s per session (`05` exempt) |
| Paste | ≤ 64 KiB, ≤ 5 min, 8 ms per report, ≤ 4 plans per session, 2 min each |
| Boot keys | ≤ 30 s, ≥ 100 ms between taps |
| Device operation | 10 s (`tether.set` 75 s), one at a time |
| Heartbeat | ping every 5 s, dead after 15 s without pong |
| `catalog.list` reply | ≤ 256 entries, ≤ 48 KiB |
| Catalog refresh from the remote | stale feeds only (older than 6 h), ≤ 1 per 60 s |
| Downloads | ≤ 4 queued or running (≤ 2 transferring); one `download.*` request at a time, 10 s |
| `downloads` push | ≤ 64 downloads, ≤ 48 KiB, ≤ 2/s per session |
