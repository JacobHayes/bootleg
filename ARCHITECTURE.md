# Bootleg — architecture

See SPEC.md for the requirements. This file covers how Bootleg is built.

## Device facts (Pixel 6, Android 17)

- Root (Magisk `su`, SELinux enforcing) can create `functions/mass_storage.bootleg`, write
  `lun.0/file=/data/media/0/bootleg/x.img` (the kernel open succeeds), symlink it into `configs/b.1`,
  then unbind and rebind the UDC. The Mac enumerates an 8.4 MB removable disk ("File-Stor
  Gadget"), and `diskutil info` reports Media Read-Only: Yes. USB ADB comes back in ~3–4s, and
  wireless ADB stays up throughout.
- The Pixel gadget HAL (V2_0) manages only `configs/b.1/functionN` links. `svc usb setFunctions
  mtp`, switching back, and `svc usb resetUsbGadget` all left our named link (and USB HID Client's
  `hid.*` links) in place, so the HAL does not fight foreign-named links.
- The link runs at high speed (480 Mb/s) even though `max_speed=super-speed-plus`, likely because of
  the cable or port. Expect about 30–40 MB/s.
- macOS can't open `/dev/rdiskN` without sudo (root:operator), so host-side checks rely on
  auto-mounted filesystems and `diskutil info`.
- `newfs_msdos`, `mkfs.exfat`, `mke2fs` and `losetup` (toybox) are on the phone.
- Writing `/proc/self/fd/N` (an O_NOFOLLOW fd held by the root process) into `lun.N/file` works,
  and `file` reads back as the real path (`/data/media/0/bootleg/x.img`).
- macOS sets prevent-removal on a mounted LUN, so a normal eject (`file=""`) fails with EBUSY.
  `forced_eject=1` empties the LUN, but macOS keeps the volume "mounted" (≥ 30s; accessing it hangs)
  until the next re-enumeration. Ejecting on the Mac (`diskutil eject`) clears the LUN by itself
  (recorded as `HostEjected`). While `file` is set, writes to `ro` fail even with an unchanged value.
- libsu returns the root binder to the app by broadcast, and Android delivers broadcasts to a
  process one at a time. Code that waits for the first root bind inside a broadcast receiver
  (`goAsync`) deadlocks until it times out, so receivers hand work to the service, and the debug
  control surface is a content provider.
- Magisk 31's `magisk --sqlite` crashes `magiskd` when a selected column is NULL (root is then
  gone until reboot). Only select non-NULL columns.
- toybox `losetup -a` shows no backing file names (APEX loops), so stale loop devices are found per
  image with `losetup -j FILE`. `newfs_msdos` defaults to 4 KiB clusters, too few for FAT32 below
  ~260 MiB, so small images use 512 B clusters (`-c 1`).
- The HAL rebinds the UDC by itself after any unbind: ~350ms later when sampled every 50ms, once
  ~700ms (adbd reopening FunctionFS triggers it). It also drops the UDC once 5–560ms after one of
  our binds and rebinds 0.5–1s after that. Writing the UDC to bind takes 61–76ms and to unbind
  9–20ms (root shell builtins timed with `EPOCHREALTIME`, no fork in the timed span), so with
  in-process IO a switch (unbind → links → bind) is unbound for ~70–95ms (measured for 3 LUNs, for
  HID, and for a detach + relink). Configfs refuses `symlink` into `configs/b.1` while bound
  (EINVAL), and refuses `lun.N` mkdir/rmdir and HID attribute writes while the function is linked.
  The kernel refuses to bind a config with no functions linked (EINVAL).
  Unlinking from a bound config force-unbinds it. Forking `getprop` costs ~110ms per read, so USB
  properties are read in-process (`android.os.SystemProperties` by reflection).

## Modules

```
core/      pure Kotlin/JVM, no Android deps; everything that can be tested fast
  gadget/    GadgetState, Desired, GadgetPlanner (pure), Op, invariants, fake configfs for tests
  catalog/   Release model, one Scraper per distro, Http interface, recorded fixtures
  image/     MBR writer, image kind sniffing (ISO9660 / MBR / GPT / raw), sha256 utils
  hid/       report descriptors, keyboard/mouse encoders, keymaps (US), boot-key presets,
             text→keystrokes, sticky-modifier state machine
app/       Android app (Compose, Material 3)
  root/      RootService (libsu, daemon mode) with a typed AIDL API (below)
  service/   BootlegService (foreground, type connectedDevice), reconcile, notification,
             keep-awake policy
  download/  DownloadJobService (user-initiated data transfer jobs), engine, pure resume planner
  catalog/   CatalogRepository (runs core scrapers, caches last good result), OkHttp `Http`
  ui/        Drives (slots), Library, Catalog, Downloads, Settings, Blank image creator,
             HID (touchpad/keyboard), Remote. The UI talks to the system side only through
             `BootlegBackend`
  tile/      Quick Settings TileService
  boot/      BOOT_COMPLETED receiver → restore last desired state (opt-in)
remote/    Go (phase 3): tsnet node + HTTP/WebSocket server + embedded web UI → AAR (gomobile);
           Kotlin implements a typed callback interface. `bootleg` CLI in the same Go module.
```

## USB gadget

- **Ownership.** Bootleg owns only names containing `bootleg`: `functions/mass_storage.bootleg`,
  `functions/hid.bootleg_kbd`, `functions/hid.bootleg_mouse`, and same-named links in
  `configs/b.1`. It never writes any other path except `UDC` (unbind/rebind). It does not
  snapshot or restore foreign state.
- **Planner** (pure): `plan(current, desired) -> Plan`, a list of stages built from Ops (`Unbind`,
  `Mkdir`, `Write`, `Symlink`, `Unlink`, `Rmdir`, `Bind`). Because the HAL rebinds a few hundred ms
  after any unbind, each stage keeps the unbound window down to relinking:
  - **Prepare** (bound): everything legal on an *unlinked* function: mkdir functions and LUNs,
    LUN attributes and `file`, HID `subclass`/`protocol`/`report_length`/`report_desc`. A new
    function is complete before its link appears. On a *linked* function only in-place media swaps
    run (`file=""`, then the new path).
  - **Switch**: unbind → unlink removed functions → link new ones → bind. Nothing else.
  - **Cleanup** (bound): rmdir functions (and their LUNs) that are now unlinked and unwanted.

  A linked `mass_storage.bootleg` whose LUN set, `ro`/`cdrom`, `removable` or `nofua` must change
  needs reconfiguring while unlinked, so the plan has two stages: a detach switch (unbind → unlink
  → bind; the host sees the gadget without disks, which also clears the host's prevent-removal),
  then prepare reconfigures it while bound and unlinked, and a second switch relinks it.

  **Nothing linked.** The kernel can't bind a config without functions, so when nothing at all is
  linked during a switch (USB debugging off, no other functions) the UDC can't be bound for
  prepare or cleanup. Only then does work move into the window, as one bounded op list (≤ 80 ops;
  attribute IO ≤ 2ms each) between the unlinks and the links: a detach with nothing else linked
  becomes a single switch (unbind → unlink → reconfigure → relink → bind), an export from nothing
  builds its functions there, and a teardown that leaves nothing linked removes them there and
  stays unbound. The host sees no device meanwhile and the HAL's rebind can't bind an empty config.

  Changes that re-enumerate: LUN count, HID function set, any `cdrom` or `ro` change (two switches
  each for the mass-storage ones, one when nothing else is linked). Changes done in place, without
  re-enumerating: swapping media on a LUN whose type stays the same, or ejecting. The planner
  asserts that no bound-phase op relinks, touches a linked function beyond a media swap, or is
  planned while nothing is linked, and that window ops run only while nothing is linked.
- **Ejection.** A normal eject writes `file=""`. If the host prevents removal, Bootleg tells the
  user and offers "Force eject" (`forced_eject`). An eject started by the host (`file` becomes
  empty) is recorded as `HostEjected`, and Bootleg never re-inserts that media on its own. With the
  journal idle, an existing LUN whose `file` is empty (and whose `ro`/`cdrom` still match the slot)
  is a host eject even if the HAL has since unbound the UDC or our link was removed; a missing or
  recreated LUN is not. A forced eject also clears the host's prevent-removal flag.
- **Executor** (root, in-process file IO, no shell per op): before each prepare/cleanup op it
  re-reads the owned state (functions, links, LUNs; no USB properties) and aborts and replans (at
  most 3 times) if it changed. Inside a switch it re-reads only the UDC and our own links, and
  writes no journal. If the HAL rebinds mid-switch (UDC found bound, or a link refused), the switch
  re-arms the dead-man, unbinds again and carries on, at most 5 times; a UDC already bound by the
  HAL after our last relink is accepted, since the host then saw the finished gadget.

  **Stable bound.** Every prepare/cleanup op runs with the UDC *stably* bound: bound for 1s, and
  after our own bind also at least 1.6s past it (the HAL's drop comes ≤ 600ms after our bind and
  its rebind ≤ 1s after that, and both can fall between two polls). The wait is bounded (3s) and
  true only once the full interval has passed. `apply`, `reinsert` and `reconcile` start with it
  (sampled during the HAL-quiet wait, so a steady gadget costs nothing extra), the executor repeats
  it before each prepare and cleanup list and after each of its binds, and a UDC the HAL drops
  between ops stops the stage and replans. If the UDC is still unbound after the wait (the HAL
  didn't rebind), the controller binds it (≤ 3 tries) and waits again. A UDC that keeps dropping
  returns `NeedsAttention` without reconfiguring anything; one that can't be bound at the end of
  an operation leads to Bootleg-off. Then it reads back and asserts the invariants:
  - our links exist and the UDC is bound (unless nothing at all is linked);
  - `cdrom=1 ⇒ ro=1`;
  - ≤ 8 LUNs;
  - no writable file is exported twice;
  - every `file` path is canonical and inside an allowed root;
  - no foreign function or foreign link target (notably `ffs.adb`) present before the operation is
    gone afterwards. Links are compared by target because the HAL renumbers `functionN`;
    additions are allowed, and ADB may be absent before (USB debugging off). A loss fails the
    operation with `NeedsAttention`.

  Failures are per slot: a failed op marks only that slot `Failed(reason)`, and the
  unaffected exports stay in place. The full **Bootleg-off** (remove all our functions, rebind) is
  used only when the UDC can't be bound at all, or when the user asks for an emergency teardown.
- **Journal.** The desired state, each slot's intent (`Exported`, `HostEjected`, `UserEjected`,
  `Failed`) and the in-flight transition (`Mutating` for prepare/cleanup, `Unbinding` for a whole
  switch) are persisted to app storage before each phase, never inside the unbound window. On
  start or after a crash, reconcile reads the journal plus the full live state (UDC, links,
  functions, LUN attributes) and finishes or reverts the transition; leftover unlinked owned
  functions are reconfigured and linked, or removed.
- **Dead-man UDC rebind.** Before every unbind (including a re-unbind after a HAL rebind), the
  executor starts a detached root shell (`sleep 15; [ -z "$(cat UDC)" ] && echo <udc> > UDC`) that
  runs independently of the executor process. If the executor dies mid-transition, the gadget
  still comes back (ADB included); in practice the HAL usually rebinds first.
- **ADB contract.** USB ADB drops for a few seconds on every re-enumeration. Recovery is bounded
  (dead-man ≤ 15s). Wireless ADB is the debugging path during that window.
- **Reconcile.** Triggered by `ACTION_USB_STATE`, service start, or a user action, with no
  periodic polling while idle. It waits (≤ 10s) for the HAL to go quiet: `sys.usb.config`
  unchanged for 1s, and equal to `sys.usb.state` when that is set (the Pixel 6 leaves
  `sys.usb.state` empty; a stable empty config with an empty state also counts as quiet). The UDC
  then gets the stable-bound wait, so the HAL rebinds rather than us; if it won't stay bound,
  reconcile returns `NeedsAttention` instead of acting. Then it compares the full state to the journal: UDC bound, links, LUN
  `file`/`ro`/`cdrom`. `HostEjected`/`UserEjected` slots are never re-inserted (only an explicit
  re-insert or a changed slot in `apply` brings media back). A LUN emptied while the journal phase
  is not idle is our own interrupted swap, not a host eject. It is rate-limited to 1 attempt per
  2s, with a maximum of 5 attempts before `NeedsAttention`.
- **HAL races.** If the UDC gets bound by someone else (HAL, dead-man) while one of our switches
  was interrupted, the host may have enumerated a half-built gadget, so the replan forces one more
  unbind/bind even when the topology already looks right.
- **Backing-file identity.** Root opens the image without following symlinks anywhere below the
  allowed root (see Root boundary), `fstat`s it, and writes
  `/proc/<rootpid>/fd/<n>` into `lun/file`. The kernel opens the same inode, which closes the
  check-then-open race. Duplicate exports are rejected by `(st_dev, st_ino)` whenever any copy is
  RW. `ro` is changed only while the LUN has no file, and in-place swaps require `removable=1`.
- **Write safety.** `nofua=0`. While a file is exported writable, Bootleg won't let the app open
  it for writing. Sparse RW images show their allocated vs. declared size, and creating one warns
  when free space is less than the declared size (or optionally preallocates with `fallocate`).

## Root boundary

- libsu `RootService` in daemon mode. Gadget state lives in the kernel, so it survives a crash of
  the app or the root process. Media stays exported when the UI dies. On restart the service
  reads the actual state and adopts it.
- The AIDL API (`IBootlegRoot`) is typed. Structured values cross as kotlinx.serialization JSON;
  it never takes shell strings, and root runs programs by argv only:
  - `readGadget()`, `readJournal()`
  - `apply(desired)`, `eject(lun, force)`, `reinsert(lun)`, `reconcile()`, `bootlegOff()`
  - `createBlankImage(name, sizeBytes, format, preallocate)`, `deleteImage(path)`, `listImages()`
  - `grantSelfStorage()`
  - `hidOpen(kind, clientToken)`, `hidWrite(kind, report) → result`, `hidReadOutput(kind)`,
    `hidClose(kind)` (`kind` is `keyboard` or `mouse`)
  - later: `setRemotePowerProfile(on)`

  Image paths (`/sdcard/...` is mapped to `/data/media/0/...`) must be normalized (no `.`, `..` or
  empty components), inside an allowed root (`/data/media/0/`), and regular files; a path through
  a symlink is rejected. Root walks the path from a directory fd of the root, opening each
  component `O_NOFOLLOW` relative to the previous fd (`/proc/self/fd/<dirfd>/<name>`, since
  `android.system.Os` has no `openat`), so a directory swapped for a symlink mid-check can't
  redirect it. Each directory and the final file are checked with `fstat` (directory, then
  regular file, all on the root's `st_dev`), and `/proc/self/fd/N` of the final fd goes into
  `lun/file`. Deletes are confined to `/data/media/0/bootleg/`, unlink by name inside the walked
  parent fd, and refuse exported files. Caller check: the binder caller's uid must equal the app's
  uid. Calls are serialized (the controller isn't thread-safe); image formatting has its own lock
  so it never blocks an eject. A delete takes the image lock and then the gadget lock (never the
  other order), so no apply can export the file between the exported check and the unlink.
- With no journal (first start), the root service adopts Bootleg's live exports (`apply` of the
  live state) instead of letting `reconcile` remove them.
- The journal lives at `/data/adb/bootleg/journal.json` (dir 0700), written temp + fsync + rename +
  directory fsync (so the rename itself survives a power loss).
- HID: root holds the `/dev/hidgN` fds and relays reports; the app sends each one over AIDL
  (`hidWrite`, no shell per report). Handing the fd to the app doesn't work: the nodes carry the
  generic `device` SELinux label, and binder checks a passed fd against the receiver
  (`avc: denied { read write } ... untrusted_app ... device:chr_file`). Relabeling the node would
  weaken policy, so Bootleg doesn't. The node is found by device number: root reads the function's
  `dev` attribute (`major:minor`) and matches it against `st_rdev` of each `/dev/hidg*` (never by
  index, since other apps' `hid.*` functions own hidg nodes too), opens it
  `O_RDWR|O_NONBLOCK|O_NOFOLLOW` and re-checks the opened fd with `fstat`. Each `hidWrite` checks
  the report length, then writes nonblocking, polling for at most the 50ms deadline, so a host that
  stops polling never holds a binder thread longer. HID calls use their own lock, never the gadget
  lock. Calls are synchronous from the session's single writer (well under 1ms each), so reports
  arrive in order. `hidOpen` links to a client token; if the app process dies, root writes
  release-all to both functions and closes them. The app reopens on `ENXIO`/`ESHUTDOWN` (host off).

## Storage & files

- `/sdcard/bootleg/` is the image store: images, `.partial` downloads, `.sha256` sidecars. The
  kernel gets `/data/media/0/bootleg/...`.
- Picking a file: if its `/sdcard/...` path maps to `/data/media/0/...`, use it in place. If the
  picker returns a document-provider URI with no usable path, offer "Import" (copy into the store).
- The app grants itself `MANAGE_EXTERNAL_STORAGE` via root `appops` so it can browse files.
- Blank images: `ftruncate` (sparse) or `posix_fallocate` (preallocate). For formatted images:
  write the MBR (Kotlin, one partition at 1 MiB), `losetup -f -s -o 1048576 -S <bytes>`, then
  `newfs_msdos -F 32 -o 2048` / `mkfs.exfat` / `mke2fs -t ext4`. The loop device is detached in a
  finally block; if `losetup` fails, times out or prints anything but one loop device, whatever
  it attached is found with `losetup -j <file>` and detached. Loop devices still attached to store
  images are swept on start (crash cleanup). Names match `[A-Za-z0-9._-]` and end in `.img`
  (≤ 100 chars). FAT32 needs a ≥ 33 MiB partition; MBR caps at 2 TiB. New files get the store
  directory's owner, group (0660) and SELinux label, then a media scan, so the app,
  MediaProvider and MTP see them.

## Catalog

- One `Scraper` per distro, preferring structured sources (JSON APIs, `SHA256SUMS`, release
  indexes). It returns `Release(distro, version, arch, variant, url, sha256 | sumsUrl,
  sizeBytes?, supportedUntil?, sumsSigned, archiveEntry?, archiveSha256?)`.
- Zipped images (Memtest86+ ships only `*.iso.zip`): `archiveEntry` names the image inside the
  `.zip` at `url`. The download verifies the archive (`archiveSha256` or `sumsUrl`), then extracts
  that one entry (bounded size, no path traversal) to `fileName`, verifying `sha256` when known.
- `sumsSigned` is informational in v1: GPG signature verification is not done (documented). HEAD
  failures fall back to a ranged `GET bytes=0-0`.
- Bounded parallel fetch (4), 20s timeout. Each scraper fails independently and its error shows in
  the UI. `Catalog` enforces the cap on in-flight requests across all scrapers (so scrapers may fan
  out internally), gives each scraper a 60s deadline, drops duplicates, and rejects releases with a
  non-https URL, an unsafe file name (`/`, `\`, `..`, leading `.`), a malformed sha256, or neither a
  sha256 nor a sums URL.
- Supported-release rules where upstream has no "supported" flag: Ubuntu uses distro-info-data
  dates (flavor LTS = 3 years); Fedora keeps the two newest stable releases; Mint keeps the newest
  point release of the two newest majors; Pop!_OS probes `YY.04` LTS versions from the last 4 years.
  openSUSE images resolve through MirrorCache metalinks (`.meta4`), which pin the versioned file and
  give its SHA-256 (Leap 16 publishes only SHA-512 files).
- The last good result per distro is cached on device and shown as stale with its age. Nothing is
  bundled.
- Downloads: user-initiated data transfer jobs (`JobInfo.setUserInitiated`). Wi-Fi only means a
  network request with `TRANSPORT_WIFI`, not just UNMETERED. Resume uses `Range` + `If-Range`
  (ETag/Last-Modified):
  - `206` with a matching `Content-Range` offset → append;
  - `200` → restart;
  - `416` → re-verify.

  Disk space is checked before starting. The whole file is hashed, then atomically renamed from
  `.partial`. ≤ 2 concurrent.

## Keep-awake

Applies only while a remote session is connected:
- `WifiLock(LOW_LATENCY)` + partial wake lock;
- root `cmd wifi force-low-latency-mode enabled`;
- `stay_on_while_plugged_in=7` and a screen wake, with prior values persisted before changing and
  restored on disconnect and on service start.

The Wi-Fi hi-perf mode is dropped because it's deprecated. Disk export needs none of this. This is verified on the device with the screen off.

## HID

- Keyboard: 8-byte boot-format report (mods, reserved, 6 keys), no report ID, subclass 1 /
  protocol 1. The report format is the same in boot and report protocol, so no conversion is
  needed. Keyboard LEDs (output report) are read. The descriptor is HID 1.11 appendix B.1 except
  that the key array spans usages 0x00–0xFF (B.1 stops at 0x65), so report-protocol hosts accept
  F13–F24 (used by the L4 harmless-input test), Intl/Lang and media keys.
- Mouse: v1 uses a 3-byte boot mouse (buttons, dx, dy), subclass 1 / protocol 2. Scroll wheel is
  deferred until `SET_PROTOCOL` handling is proven.
- Nonblocking writes with a 50ms deadline, a bounded queue (256), and release-all on
  overflow, disconnect, or controller change.
- `HidSession` (app) owns both relay endpoints and writes from one coroutine. A report the host doesn't take
  within the deadline means the host stopped polling (asleep, rebooting): queued input is dropped,
  never replayed late, and new input is refused until the owed release-all (all-zero keyboard and
  mouse reports) gets through. After 2s of that, or on `ENXIO`/`ESHUTDOWN`, the endpoints are reopened
  with backoff (250ms doubling to 5s, at most 40 attempts; the next input retries after that).
  Release-all also happens on stop, on app background and on queue overflow, and resets the
  on-screen modifier and button state. Keyboard LED output reports are polled while idle.
- Input mapping is pure (`core/hid`): `TouchpadGestures` (drag moves with acceleration, tap = left,
  two-finger tap = right, hold = drag with the left button; no wheel yet, so no scroll gesture),
  `KeyboardInput` on `StickyModifiers` (held and locked modifiers go to the host at once; a
  latched one only with the next key, so a latched Win never opens the Start menu alone).
- Paste-as-typing sends one report every 8ms (~60 characters a second). Boot-key plans re-send a
  held key every 200ms, so a host that resets USB while booting still sees it.
- Debug builds expose only fixed harmless sequences to adb (`HID_SEND`: `f24`, `shift`, `mouse`
  cancelling move); there is no arbitrary typing from adb.

## Remote (phase 3) — feasibility spike first

- Go `tsnet` with pinned versions. Android interface enumeration and network-change handling
  are adapted from tailscale-android `libtailscale/net.go`. State lives in app-private
  `noBackup` storage, single instance. Enrollment is an in-app login URL. An auth key file is
  only an optional dev/test path; nothing is shipped.
- Spike result (`remote/SPIKE.md`): this works on the device. The Go module is in `remote/`,
  and `mise run remote:aar` builds it into a gomobile AAR. Required on Android 17:
  - Kotlin supplies the interface list through `netmon.RegisterInterfaceGetter`.
  - Set `TS_LOGS_DIR`/`TMPDIR`, or tsnet panics.
  - Grant the runtime permission `ACCESS_LOCAL_NETWORK`, or LAN `sendto` fails with EPERM and
    traffic only goes through DERP.

  Log upload is off. The HTTP/WebSocket server lives in Go as a thin transport shell (WhoIs sits
  next to the listener). Decisions stay in Kotlin `core/` and are reached through a narrow
  callback interface.
- Auth: `WhoIs` on tsnet peer addresses gives the stable node ID. A node is paired by in-app
  approval and can be revoked; approvals are re-checked per session. Tagged nodes are shown as
  tags.
- Offline mode: `LocalOnlyHotspot` (WPA2/3, random passphrase shown as a QR code). The server
  binds only to the hotspot interface address and closes when the reservation ends. Pairing: a
  short-lived 6-digit code shown on the phone is exchanged for a revocable random token. The
  WPA2/3 link encrypts traffic, so there's no TLS in v1 (accepted risk, documented).
- Server hardening:
  - strict Origin/Host checks;
  - session cookie (HttpOnly, SameSite=Strict);
  - frame and message size limits;
  - one controller lease at a time;
  - mounting RW remotely needs a per-device permission;
  - all keys and buttons are released on disconnect, timeout or takeover.
- Protocol: WebSocket JSON for control, compact binary frames for HID (usage codes, mouse
  deltas).

## Hard limits

| Limit | Value |
|---|---|
| LUNs | ≤ 8 |
| Concurrent downloads | ≤ 2 |
| Catalog fetch | concurrency 4, 20s per request |
| HID queue | ≤ 256 |
| Paste | ≤ 64 KiB |
| Root op timeout | 10s |
| Reconcile | 1 per 2s, max 5 attempts |
| Gadget replans | ≤ 3 |
| WebSocket message | ≤ 64 KiB |

## Verification notes

- L2 adds crash-at-every-op tests: for each prefix of a plan, kill the executor, then run
  reconcile against the fake configfs plus the journal and assert convergence. The
  `HostEjected` state must be preserved.
- The L2 gadget suites run against a fake HAL that behaves as measured (rebind 350–700ms after any
  unbind, post-bind drop and rebind) with per-op costs on a fake clock taken from the device
  (UDC unbind 20ms, bind 70ms, the slow end of the measurements) or estimated (journal fsync 5ms,
  dead-man fork 20ms, attribute IO ≤ 2ms). Clean runs assert that nobody else binds inside our
  unbound window and that every window, from the start of the unbind write to the end of the bind,
  stays under 95ms: the two UDC writes plus ≤ 6 relinks with their re-reads, so a journal fsync,
  dead-man fork, poll or getprop inside the window fails it. Controller tests audit every
  reconfiguration op at the moment it reaches the kernel model: stably bound with `Mutating`
  journaled, or inside a window with nothing linked.
- L4 HID capture (`mise run check:device:hid`, `scripts/check-device-hid.sh`): the Mac's
  `hidutil` must list both Bootleg devices with the expected usage pages. A Swift capture helper records events while F24 and Shift taps and cancelling
  mouse deltas are sent, and asserts that exactly those were seen. If macOS denies capture
  (Input Monitoring), the test falls back to enumeration only.
- L3 tolerates upstream flakiness, not breakage: a scraper (and each image probe or sums fetch)
  that fails on a timeout, connection reset, 5xx, 425 or 429 is retried once (both scraper attempts
  share a 32s budget). At most one scraper per run may still fail that way; it is skipped with a
  `WARN L3:` line naming it. Parse, format and validation errors, or a second transient scraper,
  fail the run.
- Budget (warm cache), totalling ~2m50s: L0 ~15s, L1+L2 ~35s, L3 ~45s run in parallel with L4,
  and L4 ~100s.
- Accepted risks (v1): no TLS in offline hotspot mode (WPA2/3 link only), no checksum signature
  verification, no USB 3 (the cable negotiates high speed).
