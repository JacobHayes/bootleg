# Bootleg

Bootleg turns a rooted Android phone into a bootable USB drive and a USB keyboard and mouse for
whatever computer it's plugged into. You can also drive it from another computer over Tailscale.

- **Drives.** Expose up to 8 ISO/IMG files at once over USB, as a CD-ROM, a read-only disk or a
  writable disk. Boot installers or live systems, or carry a writable "flash drive" around on
  your phone.
- **Catalog.** Browse current releases of popular distros and download them with checksum
  verification. The list is scraped live from each project's own release data. It covers:
  - Desktop: Ubuntu, Fedora, Debian, Mint, Pop!_OS, openSUSE, Arch, NixOS and Omarchy.
  - Server: Ubuntu Server, Debian netinst, Alpine, Rocky, AlmaLinux, Proxmox VE and TrueNAS.
  - Rescue: SystemRescue, GParted, Clonezilla, Memtest86+, ShredOS and netboot.xyz.
- **Blank images.** Create raw, FAT32, exFAT or ext4 disk images to use as writable drives or as
  install targets.
- **Keyboard and mouse.** A BIOS-compatible keyboard and mouse:
  - a touchpad, with a scroll wheel (two-finger drag), or a plain boot mouse for picky firmware;
  - sticky Ctrl, Alt (Option), Cmd (Win) and Shift;
  - F1–F24;
  - boot-menu key presets for each vendor;
  - paste-as-typing.
- **USB tethering.** Share the phone's internet with the computer over the same USB connection
  (Android's own USB tethering), alongside the keyboard and mouse.
- **Remote control.**
  - The phone runs an embedded Tailscale node with no VPN slot. Any approved device on your
    tailnet can use the web UI or the `bootleg` CLI to mount images, type, send boot keys,
    turn USB tethering on or off and start catalog downloads.
  - Offline, it can serve the same UI over a local hotspot instead, with a pairing code.

Bootleg needs root (Magisk) and a kernel with configfs USB gadget support (`mass_storage`,
`hid`). It is developed and tested on a Pixel 6 running Android 17.

## Docs

- [SPEC.md](SPEC.md): requirements and decisions.
- [ARCHITECTURE.md](ARCHITECTURE.md): design, device facts, the USB gadget ownership rules,
  hard limits and the verification ladder.
- [remote/PROTOCOL.md](remote/PROTOCOL.md): the remote-control protocol.
- [AGENTS.md](AGENTS.md): how to build, verify and contribute, for humans and coding agents.

## Building

Toolchains come from [mise](https://mise.jdx.dev). The Android SDK goes in `.android-sdk/`.

```sh
mise install
mise exec -- sdkmanager --sdk_root=$PWD/.android-sdk "platform-tools" "platforms;android-37.1" "build-tools;37.0.0" "ndk;30.0.16248370"
echo "sdk.dir=$PWD/.android-sdk" > local.properties
mise run check     # lint, unit/property/simulation tests, screenshot tests, Go tests, build
mise run deploy    # install the debug build on the connected phone and grant root through Magisk
```

`mise run remote:cli` builds the `bootleg` CLI into `build/bin/`.

## Verification

| Command | What it does |
|---|---|
| `mise run check` | Offline checks (L0–L2). |
| `mise run check:catalog` | Live smoke test against every distro's servers (L3). |
| `mise run check:device` | End-to-end disk check on a connected phone, with this Mac as the USB host (L4). |
| `mise run check:device:hid` | End-to-end keyboard and mouse check (L4). |
| `mise run check:device:remote` | End-to-end remote check over Tailscale (L4). |
| `mise run check:all` | Everything. |

The device checks only expose formatted test images. They only send harmless input: F24, Shift,
mouse moves that cancel out, and one scroll detent up and back with the pointer parked on the
menu bar (and put back afterwards).

## Safety

- **USB gadget.** Bootleg only touches USB gadget entries with `bootleg` in their name. It never
  removes ADB or other apps' functions.
- **Recovery.** Every change is planned, applied, read back and checked. An independent timer
  re-enables USB if Bootleg dies mid-change.
- **Remote control.**
  - Each new device must be approved on the unlocked phone.
  - One session controls at a time, and every key is released when a session ends.
  - Writable disks need a separate per-device permission.

## License

[MIT](LICENSE)
