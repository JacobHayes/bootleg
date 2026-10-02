# Bootleg — spec

Bootleg turns a rooted Android phone into a bootable USB drive and remote keyboard/mouse
for whatever computer it's plugged into (DriveDroid + USB HID + a small remote KVM).

Target device: Pixel 6 (oriole), Android 17 / API 37, kernel 6.1, Magisk root.
Confirmed on device: configfs gadget `g1` on UDC `11110000.dwc3`;
`CONFIG_USB_CONFIGFS_MASS_STORAGE=y` and `CONFIG_USB_CONFIGFS_F_HID=y`;
`mass_storage.N/lun.0` exposes `cdrom`, `ro`, `removable`, `nofua`, `file`,
`forced_eject`, `inquiry_string`.

## Decisions

| Area | Decision |
|---|---|
| Name / package | Bootleg, `dev.jacobhayes.bootleg`; GitHub `JacobHayes/bootleg`, public, MIT |
| UI | Kotlin + Jetpack Compose + Material 3 |
| Toolchain | `mise.toml` (all `latest` + `mise.lock`, Java on 21 LTS); SDK in `.android-sdk/` |
| Root | Magisk `su`; app's root grant pre-seeded in Magisk's DB for testing |
| Disk mode | Auto: `.iso` → read-only CD-ROM, `.img` → read-only disk; per-slot override: CD-ROM / disk RO / disk RW |
| Multi-disk | Up to 8 slots exposed at once (one `mass_storage` LUN each) |
| Composite gadget | Never replace the USB function: add disks/HID next to `ffs.adb` and leave other apps' functions alone |
| Storage | `/sdcard/bootleg/` (kernel is given the `/data/media/0/...` path, not the FUSE path); can also pick any file on the phone |
| Catalog | Live-scraped at runtime from each distro's release index/API; **no bundled fallback list**. Images you already downloaded still work offline |
| Catalog scope | Supported releases (not EOL), x86_64 + arm64, arch filter defaults to x86_64 |
| Distros | Desktop: Ubuntu (+flavors), Fedora, Debian, Mint, Pop!_OS, openSUSE, Arch, Manjaro, NixOS, Omarchy. Server: Ubuntu Server, Debian netinst, Alpine, Rocky, AlmaLinux, Proxmox VE, TrueNAS. Rescue: SystemRescue, GParted Live, Clonezilla, Memtest86+, ShredOS, netboot.xyz |
| Downloads | Resumable user-initiated transfer jobs, optional Wi-Fi only, SHA256 checked against the distro's published sums |
| Blank images | Create sparse images of a chosen size: raw, FAT32 (MBR), exFAT, ext4 |
| Persistence | Re-expose the last mounted set after reboot (opt-in) |
| Surfaces | Ongoing notification (what's mounted + Eject) and a Quick Settings tile |
| HID (phase 2) | Own HID functions: boot-protocol keyboard + mouse. Touchpad UI, on-screen keyboard with F-keys, modifiers (Ctrl/Alt/Option/Cmd/Win/Shift), held + sticky modifiers, boot-key helper (F12/F11/F10/F2/Del/Esc/Option per vendor), paste-as-typing |
| Remote (phase 3) | Phone runs an embedded Tailscale node (Go `tsnet` via gomobile; no VPN slot). Web UI + CLI. Auth = Tailscale WhoIs identity + one-time in-app approval per device (pairing). No PIN. No video (you look at the target's screen) |
| Keep-awake | Only while a remote session is connected (disks are served by the kernel; no keep-awake needed, verified by a screen-off test): Wi-Fi low-latency mode, Wi-Fi lock, wake lock, `stay_on_while_plugged_in`, screen wake. Everything is restored when idle (same knobs as `~/phonekb.py`) |
| Offline remote | "Offline mode" starts a phone-hosted local-only hotspot. The web UI and CLI reach it with a paired bearer token (QR code / copy). Tailscale direct connections may also work on that network |
| Dropped | Ventoy-style multi-ISO menu, camera video |

## Phases

1. **Disks**: gadget manager, catalog + scrapers, downloads, verify, library, slots, blank images, tile, notification, boot restore.
2. **HID**: keyboard/mouse gadget functions, touchpad/keyboard UI, sticky modifiers, boot-key helper, paste-as-typing.
3. **Remote**: tsnet node, WhoIs + pairing, web UI, `bootleg` CLI.

Each phase gets deployed to the phone and tested on it before the next one starts.

## Safety rules (TigerStyle-inspired)

- **Explicit limits**: ≤ 8 LUNs, ≤ 2 concurrent downloads, bounded HID report queue,
  timeouts on every root command and network call.
- **Assertions** on arguments, return values and invariants, covering both the positive space
  (what must hold) and the negative space (what must never happen). Example: the ADB function
  is always linked after a gadget change, and a CD-ROM LUN is never writable.
- **Plan, then apply**: a pure `GadgetPlanner` maps (current state, desired state) to an
  ordered list of configfs ops. A thin executor applies them as root.
- **Verify after write**: read configfs back after applying and compare it to the desired state.
  Failures stay per slot; ADB drops for a few seconds on each re-enumeration, and recovery is
  bounded by an independent dead-man rebind (see ARCHITECTURE.md).
- **Logical interfaces**: shell/root, filesystem, network and clock sit behind interfaces, so tests
  can drive the real logic against deterministic fakes.

## Verification ladder

L0–L3 are the committed checks (CI-able, offline except L3). L4 is the local end-to-end flow
on the real phone, run often by humans and agents. Target: **all of L0–L4 under 3 minutes**
with a warm Gradle cache. Slow checks (full-image hashing, reboot/unlock, sustained writes,
network transitions, real PC firmware boot) live in a separate manual `release` suite.

| Rung | Time | What |
|---|---|---|
| L0 | ~5s | ktlint, compile, warnings as errors |
| L1 | ~10s | JVM unit tests: planner, HID report encoding, checksum parsing, MBR writer, scrapers against recorded fixtures (golden snapshots of each distro's index pages/APIs) |
| L2 | ~30s | Property/fuzz tests; deterministic gadget simulator (real executor + fake configfs, including Android's USB HAL rewriting the config, with seeded fault injection) must converge (given the HAL eventually goes quiet), including crash-at-every-op |
| L3 | ~1 min | Live catalog smoke test: fetch every distro's metadata and checksum file and HEAD each image URL (no image downloads); `./verify refresh-fixtures` re-records the L1 snapshots |
| L4 (local) | ~90s | Real phone: install, expose tiny deterministic fixtures (FAT image as RO disk, ISO9660 as CD-ROM, FAT image RW, multi-LUN). macOS auto-mounts them without sudo; file hashes are compared, `diskutil info` confirms RO/removable, an RW write on the Mac is verified on the phone after eject, and a host eject does not get re-inserted. One HAL mode change (`svc usb setFunctions mtp` and back) must keep the Bootleg links. HID enumerates and harmless input is captured. Remote: authorized and denied requests |

Entry points are mise tasks: `mise run check` (L0–L2), `mise run check:catalog` (L3),
`mise run check:device` (L4), `mise run check:all` (L0–L4), `mise run fixtures:refresh`.
