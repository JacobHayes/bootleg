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
             keep-awake policy; DownloadJobService (user-initiated data transfer jobs)
  ui/        Library, Catalog, Slots, Blank image creator, HID (touchpad/keyboard), Remote
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
- **Planner** (pure): `plan(current, desired) -> List<Op>`, where Op is `Unbind`, `Mkdir`, `Write`,
  `Symlink`, `Unlink`, `Rmdir`, `Bind`. Changes that re-enumerate (unbind → change → bind): LUN
  count, HID function set, any `cdrom` or `ro` change on a LUN. Changes done in place, without
  re-enumerating: swapping media on a LUN whose type stays the same (write `file=""`, then the new
  path), or ejecting.
- **Ejection.** A normal eject writes `file=""`. If the host prevents removal, Bootleg tells the
  user and offers "Force eject" (`forced_eject`). An eject started by the host (`file` becomes
  empty) is recorded as `HostEjected`, and Bootleg never re-inserts that media on its own.
- **Executor** (root): before running any destructive op, re-read the paths it touches; if foreign
  state changed since planning, abort and replan (at most 3 times). After applying, read back
  and assert the invariants:
  - our links exist and the UDC is bound;
  - `cdrom=1 ⇒ ro=1`;
  - ≤ 8 LUNs;
  - no writable file is exported twice;
  - every `file` path is canonical and inside an allowed root;
  - foreign links are unchanged.

  Failures are per slot: a failed op marks only that slot `Failed(reason)`, and the
  unaffected exports stay in place. The full **Bootleg-off** (remove all our functions, rebind) is
  used only when the UDC can't be bound at all, or when the user asks for an emergency teardown.
- **Journal.** The desired state, each slot's intent (`Exported`, `HostEjected`, `UserEjected`,
  `Failed`) and the in-flight transition (`Unbinding` → `Mutating` → `Binding`) are persisted to app
  storage before each step. On start or after a crash, reconcile reads the journal plus the full
  live state (UDC, links, functions, LUN attributes) and finishes or reverts the transition.
- **Dead-man UDC rebind.** Before any unbind, the executor starts a detached root shell
  (`sleep 15; [ -z "$(cat UDC)" ] && echo <udc> > UDC`) that runs independently of the executor
  process. If the executor dies mid-transition, the gadget still comes back (ADB included).
- **ADB contract.** USB ADB drops for a few seconds on every re-enumeration. Recovery is bounded
  (dead-man ≤ 15s). Wireless ADB is the debugging path during that window.
- **Reconcile.** Triggered by `ACTION_USB_STATE`, service start, or a user action, with no
  periodic polling while idle. It waits for the HAL to go quiet (`sys.usb.state ==
  sys.usb.config` for 1s), then compares the full state to the journal: UDC bound, links, LUN
  `file`/`ro`/`cdrom`. `HostEjected`/`UserEjected` slots are never re-inserted. It is
  rate-limited to 1 attempt per 2s, with a maximum of 5 attempts before `NeedsAttention`.
- **Backing-file identity.** Root opens the image (`O_NOFOLLOW`), `fstat`s it, and writes
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
- The AIDL API is typed. It never takes shell strings or free-form paths:
  - `readGadget()`
  - `apply(desired: list of {lun, imageId, mode})`
  - `ejectLun(i, force)`
  - `openHid(kind) → ParcelFileDescriptor`
  - `createBlankImage(name, sizeBytes, format)`
  - `setRemotePowerProfile(on)`

  `imageId` resolves to a canonical real path under `/data/media/0/bootleg/` or another allowed
  root. Symlinks are rejected; the file is opened by fd and checked (`fstat`) before its path
  goes into `lun/file`. Caller check: the binder caller's uid must equal the app's uid.
- HID: the root process opens `/dev/hidgN` and hands back the fd. The app writes reports
  directly, with no shell per report. The fd is reopened on `ENXIO`/`ESHUTDOWN` (host off).

## Storage & files

- `/sdcard/bootleg/` is the image store: images, `.partial` downloads, `.sha256` sidecars. The
  kernel gets `/data/media/0/bootleg/...`.
- Picking a file: if its `/sdcard/...` path maps to `/data/media/0/...`, use it in place. If the
  picker returns a document-provider URI with no usable path, offer "Import" (copy into the store).
- The app grants itself `MANAGE_EXTERNAL_STORAGE` via root `appops` so it can browse files.
- Blank images: `truncate` (sparse). For formatted images: write the MBR (Kotlin, one partition at
  1 MiB), `losetup -o 1048576 --sizelimit`, then `newfs_msdos -F 32` / `mkfs.exfat` /
  `mke2fs -t ext4`. The loop device is detached in a finally block, and stale
  `bootleg` loop devices are swept on start (crash cleanup). FAT32 needs ≥ 33 MiB; MBR caps at 2 TiB. Formatters are
  probed at startup.

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
  the UI.
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

## Remote (phase 3) — feasibility spike first

- Go `tsnet` with pinned versions. Android interface enumeration and network-change handling
  are adapted from tailscale-android `libtailscale/net.go`. State lives in app-private
  `noBackup` storage, single instance. Enrollment is an in-app login URL. An auth key file is
  only an optional dev/test path; nothing is shipped.
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
- L4 HID capture: the Mac's `hidutil`/`ioreg` must list both Bootleg devices with the expected
  usage pages. A Swift capture helper records events while F24 and Shift taps and cancelling
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
