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
- The kernel numbers a config's interfaces in link order, and configfs lists a config's entries
  newest first (readdir of `configs/b.1` showed `hid.bootleg_mouse_wheel`, `hid.bootleg_kbd`,
  `mass_storage.bootleg`, `function1`, `function0` for links made in the reverse order). Windows
  keeps a composite device's child drivers per interface number (`USB\VID_18D1&PID_4EEC&MI_nn`,
  no class in the hardware ID): a function that moves to another's number gets that one's driver
  (a mouse on the keyboard's old number stops working, though the host keeps polling it).
- A CD-ROM LUN uses 2048-byte blocks and f_mass_storage caps it at `256*60*75 - 1` = 1,151,999
  blocks (2,359,295,999 bytes): a bigger file is silently cut off ("file too big ... using only
  first 1151999 blocks" in dmesg) and the host sees a broken disc; fewer than 300 blocks fails the
  open (`fsg_lun_open`, `storage_common.c` at `2ec90535fa34`). A disk LUN has no such cap.
- Windows mounts only the first partition of a removable disk, and a hybrid ISO's first
  partition is its ISO9660 one, so a hybrid ISO exported as a read-only disk boots but shows no
  files in Explorer.
- Writing `/proc/self/fd/N` (an O_NOFOLLOW fd held by the root process) into `lun.N/file` works,
  and `file` reads back as the real path (`/data/media/0/bootleg/x.img`). The kernel prints it
  through the opener's mounts: opened in the root process's own namespace (the app's), it reads
  `/media/0/bootleg/x.img` once that namespace is gone (an APK update), so opens start from init's
  root (`/proc/1/root/data/media/0`).
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
- **An open `/dev/hidgN` across a rebind panics the kernel.** In this kernel
  (`6.1.162-android14-11-g2ec90535fa34`, `drivers/usb/gadget/function/f_hid.c` at `2ec90535fa34`),
  `hidg_bind` runs `cdev_init` + `cdev_device_add` on the function's cdev at every UDC bind and
  `hidg_unbind` runs `cdev_device_del`; `cdev_init` memsets the cdev, emptying its inode list and
  resetting its refcount. ueventd removes the node on unbind and creates a new one on bind. An fd opened under
  one bind and still open when the same linked function is bound again keeps its inode on the
  wiped list, and closing it (process exit included) evicts the inode: `cd_forget` hits
  `list_del corruption` (prev->next == prev) and the kernel panics (`cd_forget ← evict ←
  __fput`). Closing it before that bind is safe: the last close releases the cdev, which detaches
  the inode. Unlink and rmdir with an fd open are safe in this kernel (since "usb: gadget: f_hid:
  fix f_hidg lifetime vs cdev" the function lives until its last close), but kernels without that
  fix free it on unlink, so Bootleg treats unlink and rmdir like a rebind.
- USB tethering: there is no `cmd tethering`, and `cmd connectivity` has no tethering commands.
  A root `app_process` (as the libsu root service is) can call `TetheringManager.startTethering`
  / `stopTethering(TETHERING_USB)` and `registerTetheringEventCallback` by reflection: uid 0 passes
  the `TETHER_PRIVILEGED` check, and hidden-API checks don't apply there. The callback answers
  `onTetheringStarted` within ~5ms. The device uses NCM (`mUsbTetheringFunction: NCM`, interface
  `ncm0`). The carrier config asks for entitlement checks but names no provisioning app, so
  nothing is shown.
- Starting it: the HAL unbinds ~1.3s after the call, links `function1 -> ncm.gs9` next to
  `function0 -> ffs.adb` and rebinds ~0.6s later; `ncm0` is tethered ~1.2s after the call and the
  host gets a DHCP lease (a `10.x.y.z/24`) ~2.7s after it. Stopping is the mirror image (≤ 1.2s;
  the HAL rebinds with only `ffs.adb`). In both directions, and in a `svc usb setFunctions mtp`
  while tethered, Bootleg's `mass_storage.bootleg` and `hid.bootleg_*` links stay linked through
  the HAL's re-plug, and a Bootleg switch leaves the HAL's `ncm.gs9` link alone. `sys.usb.config`
  stays `adb` throughout. Wireless ADB stays up; USB ADB drops for a few seconds.
- With NCM + `mass_storage.bootleg` + both HID functions + ADB linked, the Mac sees all of them at
  once (high speed): a network interface ("Pixel 6", `en8`) with a DHCP address and a working
  route to the internet, the disk, and the keyboard and mouse. After a re-enumeration the Mac
  takes 2–6s to bring the interface's address back.
- macOS adds a "Pixel 6" network service the first time and orders it above Wi-Fi, so the Mac's
  default route moves to the phone while tethered (and back when the interface goes away).
- The kernel has NCM, ECM and EEM but no RNDIS (`CONFIG_USB_CONFIGFS_RNDIS` is not set), and
  Windows ships an NCM driver only from Windows 10 2004, so USB tethering works with macOS, Linux
  and Windows 10 2004 or later. Windows 7 still configures the composite device (the NCM interface
  just has no driver), ~3.6s after it connects; Android tethers `ncm0` only once the host has
  configured the device, and drops and re-adds it on every re-enumeration (ours included).
- Tethering ends on a USB mode change (`svc usb setFunctions mtp`), Android's own switch, and
  unplugging (Android resets the USB functions on disconnect). Airplane mode keeps it on with no
  upstream until Wi-Fi returns. Android announces every change with the sticky
  `android.net.conn.TETHER_STATE_CHANGED` broadcast (extra `tetherArray`: tethered interfaces),
  which any app can receive.
- FunctionFS works as root: `mkdir functions/ffs.<name>`, `mount -t functionfs <name> <dir>`, and a
  process writes descriptors and strings to `<dir>/ep0`; `ep1..` then appear in descriptor order.
  This kernel's f_fs (6.1) rejects CDC functional descriptors (type 0x24, so no Union), wants the
  Microsoft OS descriptor header's `bcdVersion` to be 1, and renumbers interface descriptors,
  endpoints and strings but **not** an IAD's `bFirstInterface`, which must be the
  configuration-wide number. Windows 7's usbccgp gave up on the whole composite device (disks
  included) when an IAD named the wrong interfaces.
- A config with an unready FunctionFS instance linked (ep0 open, no descriptors yet) can't bind
  (ENODEV). With mount option `no_disconnect=1`, a process that closes ep0 leaves its function
  DEACTIVATED: the gadget stays bound (the host sees a dead function), every later bind fails, and
  opening ep0 again makes the kernel unbind the gadget (links stay, in order) until the new process
  has written descriptors. ep0 calls on a function that isn't active fail with EBADFD.
- **ep0 can deadlock a UDC unbind.** f_fs sleeps for the next event, and for a control data stage
  to complete, holding the function's mutex, and `functionfs_unbind` takes that mutex after
  queueing UNBIND. The UDC writer, the USB HAL and anything reading `UDC` then sit in D state until
  the holder is killed (seen with a reader that consumed UNBIND and read again, and with two
  processes holding one ep0). A nonblocking event read only try-locks the mutex, `ppoll` doesn't
  hold it, and a data stage's wait ends with EINTR on a signal.
- Windows 7 binds its inbox RNDIS driver (rndiscmp.inf) to a composite device's IAD function with
  class EF/04/01 or Microsoft OS compatible ID `RNDIS`/`5162001`, interfaces CDC 02/02/FF + CDC
  Data, and started it only at interfaces 0-1 (at 4-5: Code 10, nothing sent). Linux's rndis_host
  without a Union descriptor also assumes interfaces 0/1. Windows keeps each composite child's
  driver per interface number (`USB\VID_18D1&PID_4EEC&MI_nn`) across class changes too: RNDIS at
  MI_04 got the old keyboard driver until that device node was uninstalled in Device Manager.
- `UsbManager.setCurrentFunctions` with *other* functions (`svc usb setFunctions mtp`) makes the
  HAL unbind, unlink its `functionN` links, link the new ones after any links that stay (ours) and
  bind once its FunctionFS functions are ready; the same functions again is a no-op. With an
  unready FunctionFS function of ours linked the HAL still relinks at once, its pull-up fails until
  our daemon writes its descriptors, and the host sees only the final layout.
- Android 17's `TetheringManager.startTethering(TetheringRequest(TETHERING_VIRTUAL = 7)
  .setInterfaceName(name))`, called by uid 0, tethers any existing interface: IpServer gives it a
  /24, runs DHCP, DNS, NAT to the upstream, IPv6 and offload, and untethers when the interface goes
  away. The legacy `tether(iface)` throws ("unsupported after V").
- A TAP interface (`/dev/tun`, `TUNSETIFF` with `IFF_TAP | IFF_NO_PI`) carries Ethernet frames and
  is gone with the last fd. Measured over RNDIS, one frame per USB transfer: 6-7 MiB/s phone to PC.

## Modules

```
core/      pure Kotlin/JVM, no Android deps; everything that can be tested fast
  gadget/    GadgetState, Desired, GadgetPlanner (pure), Op, invariants, fake configfs for tests
  catalog/   Release model, one Scraper per distro, Http interface, recorded fixtures
  image/     MBR writer, image kind sniffing (ISO9660 / MBR / GPT / raw), sha256 utils
  hid/       report descriptors, keyboard/mouse encoders, keymaps (US), boot-key presets,
             text→keystrokes, sticky-modifier state machine
  tether/    USB tethering policy (refuse while disks are exposed), the bounded on/off sequence,
             restore and remembered-choice rules
app/       Android app (Compose, Material 3)
  root/      RootService (libsu, daemon mode) with a typed AIDL API (below)
  service/   BootlegService (foreground, type connectedDevice), reconcile, notification,
             keep-awake policy
  download/  DownloadJobService (user-initiated data transfer jobs), engine, pure resume planner
  catalog/   CatalogRepository (per-feed stale-while-revalidate, caches last good result), OkHttp `Http`
  ui/        Drives (slots), Library, Catalog, Downloads, Settings, Blank image creator,
             HID (touchpad/keyboard), Remote. The UI talks to the system side only through
             `BootlegBackend`
  tile/      Quick Settings TileService
  boot/      BOOT_COMPLETED receiver → restore last desired state (opt-in)
remote/    Go (phase 3): tsnet node + HTTP/WebSocket server + embedded web UI → AAR (gomobile);
           Kotlin implements a typed callback interface. `bootleg` CLI in the same Go module.
rndis/     Go: the RNDIS device (control messages, PACKET_MSG framing, FunctionFS descriptors) and
           `cmd/bootleg-rndis`, a static linux/arm64 daemon shipped in the APK as
           `libbootlegrndis.so` and run by the root service
```

## USB gadget

- **Ownership.** Bootleg owns only names containing `bootleg`: `functions/ffs.bootleg_rndis` (and
  its FunctionFS mount, `/dev/bootleg-rndis`), `functions/hid.bootleg_kbd`,
  `functions/hid.bootleg_mouse_wheel`, `functions/mass_storage.bootleg`, and same-named links in
  `configs/b.1`. It never writes any other path except `UDC` (unbind/rebind). It does not snapshot
  or restore foreign state; the one thing it asks of the HAL is a round trip through another USB
  mode and back (see Prefix).
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
  then prepare reconfigures it while bound and unlinked, and a second switch relinks it. Mass
  storage is the last link of ours, so nothing else of ours moves with it.

  **Prefix** (`LinkOrder.PREFIX`): `ffs.bootleg_rndis`, `hid.bootleg_kbd`, `hid.bootleg_mouse_wheel`,
  linked ahead of every foreign link, so RNDIS is interfaces 0-1 (where Windows starts it, and the
  IAD's `bFirstInterface` is always 0), the keyboard 2 and the mouse 3. Mass storage follows
  wherever configfs appends it (after the HAL's links); its and ADB's numbers may shift. The
  journal's `desired.prefix` is set by Bootleg's first use and cleared only by Bootleg-off; while it
  is set the prefix stays linked whatever HID and tethering do (HID off relays no input, tethering
  off reports the cable unplugged). The planner never unlinks a prefix link except in Bootleg-off
  and establishment, and never relinks one (configfs would append it after the HAL's links). The
  reader keeps the kernel's link order (`ConfigFs.listInLinkOrder`, readdir reversed); the planner
  asserts at every bind that ours are in order, the prefix first and its RNDIS function served.

  **Establishment** (prefix wanted but not first). Configfs only appends, so Bootleg links the
  prefix with the RNDIS function *held* (its daemon has ep0 open but no descriptors, so the config
  can't bind, the HAL's own binds included), has the HAL relink its functions after ours with a
  round trip through another USB mode, then releases the daemon and binds: the host sees only the
  final layout. Refused up front while any LUN has media, while Android's USB tethering is on (the
  mode change would end it), and while a foreign link that isn't the HAL's `functionN` is linked
  (it would stay ahead of ours). Steps, under the gadget lock and HID fence:
  1. Journal `usbFunctionsToRestore` (`getCurrentFunctions`, root, `UsbManager` by reflection).
  2. Prepare (bound): mkdir and mount the RNDIS function, start its daemon held (a start replaces
     any daemon); build HID.
  3. Switch: unbind, unlink ours, link the prefix (a linked function's daemon is replaced only now,
     so the config is bindable just for the relink, as in any switch); then `setCurrentFunctions(other)` (MTP, or PTP
     from MTP) and back to the journaled mode, each waiting (≤ 5s, 50ms polls) until the HAL's links
     have changed and come after ours; then release the daemon and bind. A root process that died
     after the first mode change finds the mode journaled and the daemon held: it skips the first
     step and restores the mode before releasing (a dead daemon's restart does too).
  4. The journal entry is cleared once the prefix is first and served (every plan that releases
     with a round trip pending restores the mode first).

  A failed step sets the journaled mode back (unverified) and fails; the gadget can't bind with the
  held function, so the automatic teardown unlinks everything of ours and binds (ADB back),
  keeping `desired.prefix`. Such a failure isn't retried by itself (each attempt re-plugs USB
  twice): reconcile reports it until the next apply. With nothing foreign linked step 3 has no
  round trip. After establishment the prefix stays first through the HAL's own re-plugs.

  **Nothing linked.** The kernel can't bind a config without functions, so when nothing at all is
  linked during a switch (USB debugging off, no other functions) the UDC can't be bound for
  prepare or cleanup. Only then does work move into the window, as one bounded op list (≤ 80 ops;
  attribute IO ≤ 2ms each) between the unlinks and the links: a detach with nothing else linked
  becomes a single switch (unbind → unlink → reconfigure → relink → bind), an export from nothing
  builds its functions there, and a teardown that leaves nothing linked removes them there and
  stays unbound. The host sees no device meanwhile and the HAL's rebind can't bind an empty config.

  Changes that re-enumerate: establishment, LUN count, any `cdrom` or `ro` change (two switches
  each for the mass-storage ones, one when nothing else is linked). HID and RNDIS tethering on or
  off never touch the gadget. Changes done in place, without re-enumerating: swapping media on a LUN whose type stays the same, or ejecting. The planner
  asserts that no bound-phase op relinks, touches a linked function beyond a media swap, or is
  planned while nothing is linked, and that window ops run only while nothing is linked.
- **Ejection.** A normal eject writes `file=""`. If the host prevents removal, Bootleg tells the
  user and offers "Force eject" (`forced_eject`). An eject started by the host (`file` becomes
  empty) is recorded as `HostEjected`, and Bootleg never re-inserts that media on its own.
  Re-applying an unchanged slot spec keeps the eject; picking the image again in Drives (removing
  the slot and adding it back, or choosing it as the slot's image) is a re-insert the user asked
  for, so the app calls `reinsert` for that slot after the apply. With the
  journal idle, an existing LUN whose `file` is empty (and whose `ro`/`cdrom` still match the slot)
  is a host eject even if the HAL has since unbound the UDC or our link was removed; a missing or
  recreated LUN is not. To tell them apart even when a recreated LUN's defaults match (an RW slot),
  every LUN Bootleg configures carries a marker in `inquiry_string` (vendor `Bootleg`, product
  `Virtual Drive`, revision = a 4-character nonce kept in the journal), written in place, also on a
  linked LUN; a new LUN starts with the kernel's empty value. Only LUNs that still carry the marker
  count as host-ejected. A forced eject also clears the host's prevent-removal flag. A host eject
  sends no broadcast (nothing re-enumerates), so while anything is exported the service polls
  `readJournal` every 3s, which records host ejects (reads only, no HAL wait) for the Drives
  screen, notification and tile. The app also reads the journal whenever it comes to the
  foreground, so a draft never starts from an empty journal while exports are live.
- **Reconnect after a media change** (Settings, default off: macOS, Linux and Windows with its
  default media-change polling notice an in-place change). Some hosts never look again after media
  goes into a LUN in place: Windows 7 with polling off showed nothing (and the kernel served no
  reads) until Disk Management's Rescan Disks. With the switch on, root's `apply` and `reinsert`
  compare the LUN files before and after and, when the change applied fully and media went in or changed without a
  re-enumeration, converge once more with a forced re-enumeration (unbind, bind, same gadget), in the
  same root call under the gadget lock. It is skipped while any LUN has writable media, the changed
  one included: the host may have mounted it and be writing. The re-plug drops every drive and the
  keyboard and mouse for a moment (read-only volumes the host mounted vanish; macOS warns about
  them), which the Settings text says. Drives tells the user to rescan or turn the switch on.
- **Eject all** from the notification or tile toasts when the host refused (macOS keeps
  prevent-removal set until it ejects the disk itself, even after an unmount) or the eject failed.
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
  - every Bootleg LUN has `removable=1`, `nofua=0` and our marker (the planner repairs a
    linked LUN's flags with a detach, the marker in place);
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
  `Failed`), the LUN marker nonce and the in-flight transition (`Mutating` for prepare/cleanup, `Unbinding` for a whole
  switch) are persisted to app storage before each phase, never inside the unbound window. On
  start or after a crash, reconcile reads the journal plus the full live state (UDC, links,
  functions, LUN attributes) and finishes or reverts the transition; leftover unlinked owned
  functions are reconfigured and linked, or removed.
- **HID fds and gadget changes.** No hidg fd is open across a UDC write of Bootleg's or the
  removal of a hid function (see Device facts):
  - The controller takes a `HidNodeGuard`. Before running a plan that has a switch (watching
    every owned hid function plus every linked one, other apps' included, since a rebind
    poisons their fds too) or removes a hid function, it calls `closeAll()` (release-all, then
    close every relay fd; the relay refuses opens until the controller call returns) and then
    `holders()`, a bounded scan of `/proc/*/fd` as root that matches `st_rdev` against each
    function's `dev`. Any holder refuses the operation before its first op (`NeedsAttention`,
    naming the process), with no replans. The same check runs before the controller binds an
    unbound UDC outside a plan (settle), every UDC write of the controller calls `closeAll()`
    again (cheap, so also inside the window), and every rmdir of a hid function scans again
    right before it.
  - Someone else (a USB mode change, `svc usb setFunctions`) can still rebind behind Bootleg.
    The relay records the inode of the node's sysfs directory (`/sys/class/hidg/hidgN`, which
    the kernel recreates on every bind, with no ueventd lag) before each open and checks it on
    every write, read and close, and a watch thread polls it every 50ms while anything is open.
    Gone means unbound and not yet rebound, so the fds are closed at once, which is safe; the
    HAL's rebind comes 350ms or more after an unbind, so a HAL-driven unbind/rebind (USB mode
    change, tethering started outside Bootleg, a reset) normally just closes the session, which is
    told at once and reopens once USB has settled (below). Changed means rebound under an open fd,
    so closing it would panic:
    the fd is quarantined instead (kept open and logged). A quarantined fd is
    unrecoverable without a reboot (process exit closes it too), so while one exists the relay
    refuses opens and the holder scan reports it, which refuses every gadget change that would
    rebind or remove a hid function. Only an unbind and rebind by someone else within one 50ms
    poll quarantines; the user then sees the HID session fail with "restart the phone to use
    keyboard and mouse again", and gadget changes that touch HID fail with the holder named. A
    cable unplug by itself doesn't unbind the UDC in the kernel (whether the Pixel HAL resets
    the gadget on unplug is unmeasured). The check-to-close race (a
    foreign rebind between the check and the close) and an unbind-and-rebind faster than one
    poll stay open: without a kernel fix nothing in userspace can exclude the HAL.
  - The kernel closes a dead root process's fds at exit, which is safe because they were never
    open across a rebind of ours. A stale root process still holding fds shows up as a holder,
    so reconcile can't rebind or remove hid functions under it.
  - `HidController` opens no session while its own HID apply runs (input meanwhile is dropped);
    it opens the new session after the apply returns. Other applies close the relay's fds when
    they re-enumerate.
  - After a close the relay didn't initiate (the watch, a write or a read finding the node
    unbound or rebound), it refuses opens until the UDC is bound and the lost functions' devices
    exist (`/sys/dev/char/M:m`) continuously for the controller's stable-bound window
    (`STABLE_BOUND_MS`, 1s), polled by the watch every 50ms, at most 30s
    (`HidLimits.RELAY_SETTLE_WAIT_MS_MAX`). The HAL unbinds and rebinds more than once after a mode
    switch, and an fd opened between those rebinds would be held across the next one.
  - The client token passed to `hidOpen` is an `IHidRelayClient` (oneway). Whenever the relay
    drops the client's fds on its own (a gadget change's `closeAll()`, or a close it didn't
    initiate) or refuses its open because of a quarantine, it sends
    `onClosed(reason, reopenFollows, restartRequired)`. It sends `onReopenable()` when the
    controller call that fenced it ends, or when the settle above sees USB stable, if the client
    lost fds to it or was refused an open meanwhile (not when a settle gives up at the cap).
    Notices are raised under the relay lock and sent after it, in order.
  - On `onClosed`, `HidSession` leaves `Open` at once (`Reconnecting`), drops queued input, owes a
    release-all and refuses input until the reopened endpoints took it, so nothing typed during
    the gap reaches the host late and no key stays down. With `reopenFollows` it reopens at
    `onReopenable` (or after 30s), otherwise right away; `onReopenable` also cuts a reopen
    backoff short. `restartRequired` is terminal: `Failed` with "restart the phone to use keyboard
    and mouse again", and input no longer retries. A root service death reads as `onClosed` too.
  - The fake configfs models hidg fds: binding a linked hid function (by anyone) while an fd
    opened under an earlier bind is still open, or the executor unbinding, unlinking or removing
    a hid function with an fd open, is a hard test failure. The controller and simulator suites
    (crash at every op included) reopen relay fds after every operation.
- **Dead-man UDC rebind.** Before every unbind (including a re-unbind after a HAL rebind), the
  executor starts a detached root shell (`sleep 15; [ -z "$(cat UDC)" ] && echo <udc> > UDC`) that
  runs independently of the executor process. If the executor dies mid-transition, the gadget
  still comes back (ADB included); in practice the HAL usually rebinds first. Each arm writes a
  token to `/data/adb/bootleg/deadman` that the end of the transition replaces; the shell acts only
  while its token is current (checked again before each write), and each write is bounded
  (`timeout 3`). If its bind fails (an unserved RNDIS function makes the config unbindable) and no
  process holds a `/dev/hidg*` fd, it unlinks every link of ours and binds again: ADB comes back and
  nothing of ours sits on another function's interface number; the next reconcile establishes the
  prefix again. With a hidg fd open it leaves the gadget unbound.
- **Bounded UDC writes.** A UDC write can hang in the kernel (see Device facts), so the executor
  writes `UDC` on a worker thread: after 3s it SIGKILLs the RNDIS daemon (ending its hold on the
  f_fs mutex), and the write has 5s in total. One still pending then is left behind (nothing
  interrupts uninterruptible sleep), every configfs access fails at once (`UdcStuckException`)
  until it returns, and the HID fence stays up.
- **RNDIS function** (`ffs.bootleg_rndis`, mounted `no_disconnect=1` at `/dev/bootleg-rndis`). The
  mount and the daemon live in init's mount namespace (`nsenter -t 1 -m`): the root process's own
  is the app's, which the next root process (after an APK update, say) can't see. The root service runs one `bootleg-rndis` daemon per mount, copied from the APK to the root-owned
  `/data/adb/bootleg/rndis/bootleg-rndis` (so a root process after an APK update still recognizes
  it by `/proc/<pid>/cmdline`). It is its own process (`setsid`, stdio on files), holds a lock file
  for its life, owns ep0 (nonblocking, `ppoll`, one thread; a control data stage the host doesn't
  finish in 500ms is interrupted), the endpoints and the TAP `bootleg_rndis`. Starting one first
  stops any daemon on the mount (two ep0 holders can wedge the kernel's unbind). Control is one
  level-triggered command file (`{release, media}`, written atomically, then SIGUSR1) and one status
  file the daemon rewrites on every change (pid, released, last USB event, host initialized, media,
  drained); root waits ≤ 2s for the status to match. The daemon starts held and disconnected.
  Every USB event (FunctionFS coalesces them), HALT and INITIALIZE end the host's session (its
  filter and queued responses and notifications); frames pass only in an initialized session with
  a filter and the media connected. MAC addresses (the host adapter's and the TAP's) are derived
  from the device serial, locally administered and stable.
  - The reader reports the daemon with the functions: absent, held, ready, or dead (no daemon; a
    linked dead function makes every bind fail). Apply and reconcile skip their stable-bound wait
    while a linked one isn't ready.
  - **Recovery** of a linked dead function (reconcile) is a switch: start a new daemon held
    (reopening ep0 unbinds the gadget, links in place), release it, bind. It waits while any LUN
    has media (the disks keep working; Drives says "eject every disk to restart it"), and runs at
    most once per 30s, so a daemon that keeps dying is left dead and reported. A daemon's death
    sends no USB broadcast, so the root process that started it (it is that process's child) waits
    on it and reconciles when it exits by itself (it is dead, not stopped or replaced).
- **ADB contract.** USB ADB drops for a few seconds on every re-enumeration. Recovery is bounded
  (dead-man ≤ 15s). Wireless ADB is the debugging path during that window.
- **Reconcile.** Triggered by `ACTION_USB_STATE`, service start, a user action, or (root side)
  the RNDIS daemon exiting or a root process starting with the journal mid-transition (the last
  one died in it; the dead-man only brings ADB back), with no periodic polling while idle. It waits (≤ 10s) for the HAL to go quiet: `sys.usb.config`
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
  RW: before `apply`, root opens every slot's image (confined, as below) and checks the whole set,
  so two hard links to one inode can't both be exported with one writable. `ro` is changed only while the LUN has no file, and in-place swaps require `removable=1`.
- **CD-ROM size.** The kernel cuts a CD-ROM off at 1,151,999 blocks of 2048 bytes (see Device
  facts), so Auto (`LunModes.auto`, shared by the app, the remote and restore) picks a CD-ROM only
  for an `.iso` that fits (300 to 1,151,999 blocks), else a read-only disk; slots record whether
  their mode came from Auto (`SlotSpec.auto`). Before every plan the controller checks each
  exported CD-ROM slot's size (`fileSizeBytes`, root's confined open + `fstat`): an explicit one
  that doesn't fit fails its slot ("<name> is 6.20 GB; a CD-ROM holds at most 2.36 GB. Use Disk
  (read-only)"); reconcile, which is also the restore after reboot, turns an Auto one into a
  read-only disk and records why in `Journal.slotNotes` (shown on the slot). Root checks again on
  the fd it hands the kernel, right before writing `lun/file` on a LUN with `cdrom=1`, so the
  inode the kernel opens is the one checked. A file that grows while exported is caught on the next
  plan (its slot fails and the LUN is emptied, unless the host holds it); the kernel log isn't
  parsed. The Drives mode menu disables CD-ROM for such an image with the reason, and remote
  `slots.apply` refuses it (`cdrom_too_large`).
- **Write safety.** `nofua=0`. While a file is exported writable, Bootleg won't let the app open
  it for writing. Sparse RW images show their allocated vs. declared size, and creating one warns
  when free space is less than the declared size (or optionally preallocates with `fallocate`).

## Root boundary

- libsu `RootService` in daemon mode. Gadget state lives in the kernel, so it survives a crash of
  the app or the root process. Media stays exported when the UI dies. On restart the service
  reads the actual state and adopts it.
- The AIDL API (`IBootlegRoot`) is typed. Structured values cross as kotlinx.serialization JSON;
  it never takes shell strings, and root runs programs by argv only, each in its own session
  (`setsid`): a timeout SIGKILLs the whole process group and returns only once the group and the
  output reader are gone (bounded waits):
  - `readGadget()`, `readJournal()`
  - `apply(desired)`, `eject(lun, force)`, `reinsert(lun)`, `reconcile()`, `bootlegOff()`
  - `createBlankImage(name, sizeBytes, format, preallocate)`, `deleteImage(path)`, `listImages()`
  - `grantSelfStorage()`
  - `hidOpen(kind, clientToken)`, `hidWrite(kind, report) → result`, `hidReadOutput(kind)`,
    `hidClose(kind)` (`kind` is `keyboard` or `mouse`)
  - `setUsbTethering(on, mode) → TetherResult`, `usbTethered()` (either transport)
  - later: `setRemotePowerProfile(on)`

  Image paths (`/sdcard/...` is mapped to `/data/media/0/...`) must be normalized (no `.`, `..` or
  empty components), inside an allowed root (`/data/media/0/`), and regular files; a path through
  a symlink is rejected. Root walks the path from a directory fd of the root (opened in init's
  mount namespace through `/proc/1/root`), opening each
  component `O_NOFOLLOW` relative to the previous fd (`/proc/self/fd/<dirfd>/<name>`, since
  `android.system.Os` has no `openat`), so a directory swapped for a symlink mid-check can't
  redirect it. Each directory and the final file are checked with `fstat` (directory, then
  regular file, all on the root's `st_dev`), and `/proc/self/fd/N` of the final fd goes into
  `lun/file`. Deletes are confined to `/data/media/0/bootleg/`, are checked by name inside the
  walked parent fd, and refuse exported files. The unlink itself goes through the FUSE view
  (`/storage/emulated/0/bootleg/<name>`), then by fd to confirm the entry is gone: the FUSE daemon
  caches entries indefinitely and only learns of changes made through it, so a file unlinked under
  `/data/media` keeps showing up to apps (stat works, open fails with ENOENT) and a download of the
  same name would refuse to replace it. Caller check: the binder caller's uid must equal the app's
  uid. Calls are serialized (the controller isn't thread-safe); image formatting has its own lock
  so it never blocks an eject. A delete takes the image lock and then the gadget lock (never the
  other order), so no apply can export the file between the exported check and the unlink.
- **Boot.** After a real reboot the boot receiver either restores the journal's exported slots
  (opt-in) or forgets the journal. Android 15+ also sends BOOT_COMPLETED when an app leaves the
  force-stopped state, while the kernel still exports its drives, so the receiver compares
  `Settings.Global.BOOT_COUNT` with the boot count stored when the journal last became current
  (when a boot is handled, and on every apply, eject, re-insert or Bootleg-off). Equal counts mean
  the same boot: nothing is restored or torn down. An unknown count is treated as a reboot.
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
  `hidOpen` fails while a gadget change is running (see HID fds and gadget changes).

## Storage & files

- `/sdcard/bootleg/` is the image store: images, `.partial` downloads, `.sha256` sidecars. The
  kernel gets `/data/media/0/bootleg/...`.
- Finished downloads and imports move into place without replacing an existing image. A target
  that stats but won't open (a stale FUSE entry) is replaced by a rename (an app's unlink of a stale
  entry fails with ENOENT; a rename goes through);
  "already downloaded" checks use the same test.
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
- Every store operation (list, create, format, sweep, sidecar reads) first pins the store
  directory with the confined walk (no symlink anywhere below the media root) and then works by
  name inside that fd. External programs (`losetup`) get `/proc/<pid>/fd/<n>` of a file opened
  that way, never a path. Sidecars are opened `O_NOFOLLOW`, must be regular files, and are read
  at most 4 KiB + 1 bytes, so one that grows while it's read is refused.

## Catalog

- One `Scraper` per distro, preferring structured sources (JSON APIs, `SHA256SUMS`, release
  indexes). It returns `Release(distro, version, arch, variant, url, sha256 | sumsUrl,
  sizeBytes?, supportedUntil?, sumsSigned, archiveEntry?, archiveSha256?)`.
- Zipped images (Memtest86+ ships only `*.iso.zip`): `archiveEntry` names the image inside the
  `.zip` at `url`. The download verifies the archive (`archiveSha256` or `sumsUrl`), then extracts
  that one entry (bounded size, no path traversal) to `fileName`, verifying `sha256` when known.
- `sumsSigned` is informational in v1: GPG signature verification is not done (documented). HEAD
  failures fall back to a ranged `GET bytes=0-0`.
- A feed is one scraper and the distros it fills (Ubuntu's feed fills Ubuntu and Ubuntu Server).
  `Catalog.fetch(feed)` runs at most 6 feeds at once; each feed gets its own cap of 4 in-flight
  requests, a 15s per-request timeout (10s to connect) and a 30s deadline that starts once it has a
  slot, so a slow or hung upstream only ever fails its own feed. It drops duplicates and rejects
  releases with a non-https URL, an unsafe file name (`/`, `\`, `..`, leading `.`), a malformed
  sha256, or neither a sha256 nor a sums URL; a feed whose every release is rejected has failed.
- Cancellation is never failure: `withDeadline` turns whatever a cancelled call ends with (OkHttp's
  `IOException("Canceled")`) into the caller's cancellation, or into "timed out" when its own
  deadline cancelled it. `OkHttpHttp` uses `enqueue`, so cancelling the coroutine cancels the call
  instead of waiting out a blocking `execute()`.
- Supported-release rules where upstream has no "supported" flag: Ubuntu uses distro-info-data
  dates; Fedora keeps the two newest stable releases; Mint keeps the newest
  point release of the two newest majors; Pop!_OS probes `YY.04` LTS versions from the last 4 years.
  openSUSE images resolve through MirrorCache metalinks (`.meta4`), which pin the versioned file and
  give its SHA-256 (Leap 16 publishes only SHA-512 files).
- `CatalogRepository` (app) is stale-while-revalidate, process-wide, and runs fetches in the app
  scope, so leaving the screen doesn't cancel them:
  - `state: StateFlow<CatalogState>`: every distro (sorted by category, then name
    case-insensitively) with its last good releases, `fetchedAtMs`, `loading`, and the latest
    attempt's `error`/`failedAtMs`; plus `cacheLoaded`, `refreshing` and `offline`.
  - `refreshStale()` (Catalog opened) fetches feeds that were never fetched, are older than 6h or
    whose last attempt failed, except those that failed in the last minute. `refreshAll()`
    (refresh button, pull) and `refresh(distroId)` (a card's Retry) fetch regardless. A feed
    already in flight is joined, never fetched twice; a new fetch waits for a cancelled one to
    clean up. The returned jobs only wait: cancelling one (a remote client going away) never
    cancels a fetch someone else is waiting for.
  - Each feed lands as soon as it finishes and is saved to the on-device JSON cache (last good result
    per distro; distros no scraper declares any more are dropped). A failure keeps the saved
    releases and marks only that feed's cards. A cache write that fails keeps the results in memory.
  - Offline means the app has no usable network: `activeNetwork` is null, which includes networking
    blocked for the app in Doze or background limits. It's checked when the Catalog opens, before
    each fetch, after a failed one, and on every default-network callback (capabilities, lost,
    blocked status). Offline, no fetch starts and a failure isn't recorded against its feed; the
    screen shows "Offline · showing saved results" and hides earlier per-card errors. A failure
    with a network present (one host's DNS included) is that feed's own error. Back online, the
    repository waits for fetches still running, then refetches everything stale or failed, recent
    failures included.
  Nothing is bundled.
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
- Mouse (`hid.bootleg_mouse_wheel`): subclass 1 / protocol 2, no report ID, a 5-byte report
  (buttons, X, Y, Wheel = Generic Desktop 0x38, AC Pan = Consumer 0x0238; all int8 -127..127). HID
  1.11 appendix B.2 lets a boot device append data after the 3 boot bytes, and a boot-protocol host
  (BIOS, boot menus) reads only bytes 0–2. f_hid (6.1, also android14-6.1) answers `SET_PROTOCOL`
  itself, keeps the value in the kernel (`GET_PROTOCOL` reads it back) and never tells userspace, so
  Bootleg can't know the mode and doesn't need to: the same report serves both, with no negotiation
  and no delay. `report_length` 5 also sets the IN endpoint's wMaxPacketSize; a write longer than
  `report_length` is truncated by the kernel.
- Both functions are in the prefix (see USB gadget) and stay linked while HID is off: off closes
  the relay's session after a release-all and refuses input, so the host keeps an idle keyboard and
  mouse and HID on or off never re-enumerates.
- Nonblocking writes with a 50ms deadline, a bounded queue (256), and release-all on
  overflow, disconnect, or controller change.
- `HidSession` (app) owns both relay endpoints and writes from one coroutine. A report the host doesn't take
  within the deadline means the host isn't reading that endpoint (asleep, rebooting, or firmware and
  boot menus, which often poll the keyboard but never the mouse). Stalls are per endpoint: that
  endpoint's queued input is dropped, never replayed late, and its new input is refused
  (`NOT_READING`) until its owed release-all gets through, which is retried with one nonblocking
  write every 100ms so it never holds up the other endpoint. The link stays `Open` and lists the
  endpoints the host isn't reading ("Keyboard connected · the computer isn't reading the mouse"); a
  stall never closes or reopens anything, since the fds are fine. Only `ENXIO`/`ESHUTDOWN` or a
  failed write reopen the endpoints, with backoff (250ms doubling to 5s, at most 40 attempts; the
  next input retries after that). A release owed on an endpoint the host isn't reading doesn't hold
  the remote's input barrier.
  When root drops the fds itself the session is told and reconnects without waiting for input
  (see HID fds and gadget changes). Release-all also happens on stop, on app background and on queue overflow, and resets the
  on-screen modifier and button state. Keyboard LED output reports are polled while idle. A
  session the app's background closed (screen off, another app) is reopened when an activity
  starts again, after that stop has finished, so the link doesn't sit at Stopped until the next
  input.
- Input mapping is pure (`core/hid`): `TouchpadGestures` (drag moves with acceleration, tap = left,
  two-finger tap = right, hold = drag with the left button, two-finger drag scrolls),
  `KeyboardInput` on `StickyModifiers` (held and locked modifiers go to the host at once; a
  latched one only with the next key, so a latched Win never opens the Start menu alone).
- Scrolling: one wheel unit is one detent (no Resolution Multiplier, so no high-resolution
  scrolling). A drag with two fingers down turns into detents at a fixed dp per detent, carrying
  the fractional remainder (reset when a gesture starts); each report is clamped to ±127 and any
  excess is dropped, never queued. Scrolling suppresses the two-finger tap (right click); a second
  finger joining a hold-drag doesn't scroll (the drag keeps moving the pointer), and scroll
  reports carry the held button bits. The direction is a traditional wheel
  (fingers down scroll down, fingers right scroll right); the host's natural-scrolling setting
  applies on top. Scroll has no held state, so release-all is unchanged. With the compatible
  layout the app and the remote controller drop scroll input.
- The typing field is translated purely (`core/hid/TypingField`): each IME update becomes Backspace
  back to the first changed character, then the new characters, and a word still being composed is
  sent at once. The IME's selection and composing region are kept as given, because dropping them
  makes the IME insert its composing word a second time. At most 64 characters are typed per update
  (bigger ones are refused and point to Paste as typing), and the field keeps the last 120.
- Control screen: a header (switch, status, Boot keys, Paste) and a bottom accessory area (Ctrl,
  Alt/Opt, Cmd/Win, Shift chips, then the Pointer / Keys / Type selector) are shared by three panes.
  Buttons switch panes, because a swipe on the touchpad is mouse input. Keys is the first pane; the
  last one selected is kept in the app's preferences, so Control reopens on it. Modifier state lives in `HidController`, so
  switching panes keeps it. Keys keeps Esc, Del, the arrows and Enter low and never scrolled away.
  Type puts its field directly above the accessory area. Selecting Type focuses the field and opens
  the IME, Back clears focus, and selecting another pane hides the IME. One owner handles the IME
  inset: `MainActivity` uses `adjustResize` with edge-to-edge, and `BootlegRoot` applies
  `imePadding` for Control and hides the bottom navigation while Control's IME is up. Control
  only applies and consumes its own Scaffold padding.
- Paste-as-typing sends one report every 8ms (~60 characters a second). Boot-key plans re-send a
  held key every 200ms, so a host that resets USB while booting still sees it.
- Debug builds expose only fixed harmless sequences to adb (`HID_SEND`: `f24`, `shift`, `mouse`
  cancelling move, `scroll` one detent up and back); there is no arbitrary typing from adb.

## USB tethering

Bootleg runs no network stack of its own: Android runs DHCP, DNS, NAT, IPv6 and picks the upstream
(no picker) on whichever interface carries the host's traffic. Carrier entitlement checks stay on
(`exemptFromEntitlementCheck` is never set). One switch and a mode (`TetherMode`, a preference on
the Drives card), one transport at a time: **RNDIS** (default) or **NCM**. A request turns the other
transport off first, then the mode's on (or everything off); an NCM change that would be refused
(disks exposed) is refused before RNDIS changes. Tethering on is a use of Bootleg: it sets the
prefix up first when it isn't (`establishPrefix`, without disks; once NCM is on the mode change it
needs would end NCM).

- **RNDIS transport** (no USB change, so it works with disks exposed once the prefix is up). On:
  Android tethers the TAP (`TETHERING_VIRTUAL` by name, ≤ 2s for the answer, one retry for a
  transient error, ≤ 3s until reported), then the daemon's medium connects (it sends
  `MEDIA_CONNECT`) and frames pass. Off is the mirror image, medium first; the daemon reports it
  only once the frame being written to the host has finished (≤ 500ms), else off fails. Android's
  tethering of the TAP and the medium each converge on their own, so a failed stop or a root
  process that died between them is repaired by the next request; a start that fails is taken
  back down. Unplugging changes nothing; Android untethers the TAP if it goes (the daemon died).
  Without the API to tether an interface by name, RNDIS fails as unsupported.
- **NCM transport** (`IBootlegRoot.setUsbTethering(on, mode)`, under the gadget lock so no apply, eject or
  reconcile of ours overlaps the HAL's re-plug). `UsbTetherController` (core, fake clock in tests)
  runs the sequence against a `TetherPort`: the root service's `TetheringManager`, by reflection,
  with one event callback registered for the root process's life. Until that callback has
  reported once (≤ 2s), the state is unknown and every call fails closed.
  1. Gate: a transition marker left by a root process that died mid-change is waited out (below).
  2. Read the live gadget (a read error fails the call). `TetherPolicy` decides: already in that
     state → `Unchanged` (no re-plug, allowed with disks exposed); any LUN with media (`file` set,
     linked or not) → `Refused` naming those LUNs ("Eject every disk first: turning USB
     tethering on or off re-plugs USB"). HID may stay on; it re-enumerates.
  3. Close HID: the HAL's rebind poisons every hidg fd open across it (see HID fds and gadget
     changes), and Bootleg can't time it. The root call runs inside the same fence as a controller
     call: `closeAll()` releases and closes the relay's fds and refuses opens until the root call
     returns, then the holder scan covers every linked and owned hid function; any holder fails
     the call (`Failed`, naming it) before Android is asked.
  4. Write the marker (`/data/adb/bootleg/tether-transition.json`: requested state and
     `elapsedRealtime`), then request it. A start waits ≤ 2s for `onTetheringStarted` /
     `onTetheringFailed(error)` (a fresh callback per attempt, so a late answer only reaches an
     abandoned attempt); a failure is `Failed` with the error's name (entitlement, unsupported,
     ...). One retry, only for `TETHER_ERROR_SERVICE_UNAVAIL` and `TETHER_ERROR_INTERNAL_ERROR`.
     No answer withdraws the request with a stop, and its marker is *unverified*: the start may
     still be queued, so nothing proves it is over until the marker's full window has passed.
  5. Wait (≤ 5s, 100ms polls of the callback's snapshot) until a USB interface is tethered (on) or
     none is (off). Android reports the interface before the HAL has finished, so:
  6. Wait (≤ 5s, 100ms polls) for the HAL's switch to finish: an `ncm.*`/`rndis.*` link present
     (on) or absent (off) in `configs/b.1`, and the UDC bound for 1s without a drop.
  7. Clear the marker (or, if the switch didn't settle, rewrite it unverified) and `reconcile()`
     (HAL quiet, stable bound, then re-link anything of ours the HAL dropped). Its result rides
     along in `TetherResult.Changed`; tethering changed even if the reconcile needs attention.
  8. Whatever happened, a marker still left (switch not settled, a start or stop Android never
     finished) is waited out before the call returns, so the fence on HID opens lasts until the
     HAL can no longer re-plug for this request.

  A start that never comes up (no computer attached, ...) is stopped again and waited out (steps
  5–6 for off) before `Failed` returns; the marker is rewritten for the stop before it is issued.
  Each start/stop costs one HAL re-plug (≈ 0.6s unbound); a call takes ~3–5s. Worst case ≈ 60s:
  marker gate 10s, holder scan 2s, start 2 × 2s, interface 5s, switch 5s, reconcile's own bounds
  (≈ 20s), then a left marker's window (≤ 10s; a failed start's withdrawal is bounded the same
  way). The reconcile is the controller's, called while the root call already holds the gadget
  lock.
- **Transition marker** (`requestedOn`, `startedAtMs` in `elapsedRealtime`, `verified`). It
  matters when the root process dies mid-change, or after a start Android never answered: the
  next `apply`, `reinsert`, `reconcile`, `bootlegOff`, adoption at root start or tethering call first waits, at most until 10s after the
  marker was written. A verified marker is done early once Android reports the requested state and
  the HAL's switch has settled; an unverified one (no answer, or an unreadable file, which counts
  as unverified and written when this root process first found it unreadable) always waits the
  full window. Then it is deleted. A marker from
  an earlier boot has either a future `startedAtMs` (deleted unread) or a window that has passed
  (deleted after one check); at worst it costs one bounded wait. So disks are never exported into
  a re-plug of the HAL's. Waiting out a marker inside its window closes the relay first, and the
  relay refuses opens while a marker is inside its window, so a new root process opens no hidg fd
  into the re-plug a dead one left pending. That refusal is a wait, not a failure: the client is
  sent `onClosed(reopenFollows = true)` (so `HidSession` waits in `Reconnecting` instead of
  spending its reopen attempts), the window end is fixed at the first refusal, and the watch sends
  `onReopenable` once the marker is gone or that window has passed (as does the end of the
  tethering call, which clears its marker before returning). One predicate decides every reopen
  notice: no gadget change, settle, pending re-plug or quarantine.
- **Observation** (app, no polling): `TetherObserver` registers for the sticky
  `TETHER_STATE_CHANGED` broadcast in the app process (`tetherArray`: tethered interfaces; on when
  one matches `rndis\d+`/`ncm\d+`/`usb\d+`) and publishes `tether: StateFlow<Boolean?>`, null until
  the first broadcast (the remote's `State.tether` is absent meanwhile). A root call's result is
  published at once (root's callback is the authority), and broadcasts update it after that.
  Root decides from its own callback; the app's value drives display and the remembered choice.
- **Remembered choice** (app preferences, all reads and writes on the main thread): a Bootleg
  request (app, web UI, CLI) that returns `Changed` or `Unchanged` sets it to the returned state,
  whether or not the requesting remote session is still there. An *external* stop (Android's
  switch, unplug, a USB mode change) clears it, as a host eject does for a disk: a broadcast going
  from on to off while no Bootleg request is in flight and before `ACTION_SHUTDOWN`, confirmed
  10s later (no broadcast since saying on, still no request in flight) by root (`usbTethered()`
  off) and by the live gadget (the HAL's `ncm.*`/`rndis.*` function unlinked), so a stale
  broadcast can't clear a choice a later request set. Every re-enumeration of Bootleg's drops and
  re-adds `ncm0` (back after ~4s with a Windows 7 host) without stopping tethering: root's callback
  may say off meanwhile, but the HAL's link stays, so it never clears the choice. The first broadcast after the process starts (the sticky
  one) only initializes, so a boot's initial "off" never clears it. A stop while the app process
  isn't running, or an off that Android announces before `ACTION_SHUTDOWN`, goes unseen or
  ignored respectively: the next reboot may then turn tethering back on, or not.
- **Restore after reboot** (same opt-in as the disks, real reboots only via `BOOT_COUNT`):
  `BootlegService` on `ACTION_RESTORE`, before registering for `USB_STATE`, calls
  `setUsbTethering(true)` when the choice is on. Nothing in Bootleg exports disks that early (no
  reconcile has run, Remote isn't started at boot); only a user acting within those seconds
  could, and then root refuses the toggle and tethering is not restored (logged). The call's own
  reconcile re-exports the journal's slots and HID, so a restore costs two re-plugs (the HAL's for
  NCM, then ours for disks and HID) and tethering never toggles with disks exposed. The
  `USB_STATE` reconcile that follows restores the disks in every other case (`Unchanged`,
  `Failed` after the withdrawal settled, `Refused`).
- **HID during the re-plug:** the relay is closed from before the request until the call returns
  (step 3), so the host loses the keyboard and mouse for the whole call (~3–5s), not just the
  ~1s re-plug. `HidSession` sees its writes fail and reopens with its usual backoff once the
  fence lifts; input meanwhile is dropped, never replayed late.
- **Surfaces:** a "USB tethering" card on Drives (switch, RNDIS/NCM buttons, the hosts the mode
  serves, the refusal reason while disks are exposed in NCM, and a note that NCM's re-plug briefly
  drops the keyboard and mouse). Choosing the other mode while tethering is on switches over (a
  refused switch keeps the old mode). The notification and the Quick Settings tile subtitle mention
  it when it's on; the remote's `State.tether` carries the mode. Debug hooks `TETHER_ON`,
  `TETHER_OFF`, `TETHER_MODE`, `TETHER_STATE`. The app's broadcast observer counts `bootleg_rndis`
  as tethered too.

## Remote (phase 3)

tsnet runs in the app, with no VPN slot. Kotlin supplies the interface list via
`netmon.RegisterInterfaceGetter`. Set `TS_LOGS_DIR`/`TMPDIR`. `ACCESS_LOCAL_NETWORK` is
required for direct LAN paths and incoming hotspot connections. Log upload is off.

### Ownership
- **Kotlin `core/remote`** has all authority, in `RemoteController`: pure, deterministic,
  JVM-tested, and driven by an injected clock. A single actor serializes it.
  - It owns authorization, pairing, sessions, the lease, mutation gating, input ownership,
    paste/boot-key jobs and remote catalog downloads.
  - Blocking work (gadget/root calls, HID writes) runs on a separate bounded executor with
    deadlines (10s root op). It never runs on the actor or the main thread.
- **Go** (`remote/`) only does transport:
  - sockets and the tsnet node;
  - `WhoIs`, with identity bound to the accepted connection and listener, never to request
    data, and bounded before it crosses the bridge (≤ 32 tags, ≤ 256 UTF-16 units per string,
    valid UTF-8, `connJson` ≤ 16 KiB), or the connection is refused before `Open`;
  - exact Origin/Host checks, size/rate/connection limits, and frame-length validation;
  - WebSocket ping/pong.

  Go holds no lease or authorization state.
- **Lease epoch.** Every lease change bumps a 16-bit epoch.
  - Every mutation request and every binary HID frame carries the epoch the client believes it
    holds.
  - It is checked when Kotlin admits the request, then again in the same actor step that
    dispatches the effect.
  - A disk operation that has already started finishes atomically (the gadget controller is
    serialized). Its result goes to whoever is connected.

### Lease, takeover, local priority
- The lease is required for **every** mutation: disks, HID enable, HID input, paste, boot keys.
  Missing it gives `lease_required`.
- **Takeover:** `lease.takeover{holderSession, epoch}` prompts the holder.
  - The lease moves on the holder's consent, or when the holder session is dead.
  - Kotlin re-checks the holder and epoch at transfer.
  - Nothing ever transfers automatically on a timer.
- **Local priority:** local capture (Control tab in the foreground) preempts any remote lease.
  While it's active, remote takeover is refused.
- **RW permission:** remote RW mounts need the per-device "allow writable disks" flag, stored on
  the approval record and settable only in the app.

### Liveness and release
- **Heartbeat:** Go pings every 5s. A session with no pong for 15s is dead, and Go calls
  `Closed(sessionId, "dead")`.
- **End of session** (dead, revoke, takeover, close or lease loss), in this order:
  1. Bump the epoch.
  2. Cancel producers.
  3. Drop that session's queued input.
  4. Release-all on the priority lane.
  5. New input is refused until the HidSession owed-release barrier clears.

  That's the controller's state order. When a step's effects are dispatched, HID effects
  (release-all first) go out before any transport or store call. If any port throws mid-step,
  the actor releases HID, closes every session and fails closed.

  Release retries back off for up to 60s, then the session reports `release_pending` until the
  HID node reopens or HID is turned off. The host can't get a release while its USB is down
  (documented limit).
- **Idle:** after 30s with no input, held keys are released and the lease is kept. After 10
  minutes idle, the lease is released.

### Auth
- **Tailnet:**
  - Unknown node IDs are `pending`: they get no state and no control. At most 4 can be pending,
    with oldest-first eviction.
  - Approval happens only in the unlocked app. A notification opens the app and can't approve
    anything itself.
  - Approvals live in `noBackupFilesDir`, expire after 180 days, and can be revoked. A revoke
    closes live sessions within one actor step.
  - Node and user names are untrusted display text.
- **Hotspot:** pairing is enabled explicitly in the app.
  - **Codes:** 6 digits, single use, 2-minute lifetime, invalidated when pairing or the hotspot
    stops.
  - **Lockout:** at most 10 attempts per hour globally, persisted across restarts, and reset
    only from the phone.
  - **Tokens:** 256-bit, stored as SHA-256 in `noBackupFilesDir`, 90-day expiry, revocable.
    Browsers get a host-only HttpOnly SameSite=Strict cookie. The CLI keeps a 0600 file. Tokens
    never appear in logs or URLs.
  - **Ingress:** the listener binds the hotspot address. Each connection's local address must
    equal it and its remote address must be in the hotspot subnet and not the phone's own
    hotspot address (a local app looping back through the hotspot IP), or it's rejected. The
    reservation ending closes the listener and its sessions, also while offline mode is still
    starting (the start is superseded, and the listener only starts on a reservation still open).
    A `/pair` body must arrive within 5s (a per-request read deadline, so WebSockets get none).
  - **Accepted risk (no TLS):** a malicious hotspot participant could sniff a token over plain
    HTTP.
- **Both transports:**
  - `/pair` and `/ws` require an exact scheme/host/port Origin match. `Origin: null` is rejected.
  - A missing Origin is allowed only for non-browser clients that authenticate by WhoIs or
    bearer token, and cookies are ignored on that path. The one exception is the CLI's first
    `/pair`, which goes through the enabled, code-authenticated, globally limited bootstrap.
  - No CORS. CSP `default-src 'self'; connect-src 'self' ws://<host>; frame-ancestors 'none'`
    (older Safari doesn't let `'self'` cover `ws:`; `<host>` is the already-checked Host) and
    `X-Frame-Options: DENY`.
  - `GET /api/transport` tells the page which transport it's on and whether a pairing cookie is
    present (`{transport, hasToken}`), so the hotspot page can show the pairing form. It reveals
    nothing about the token.
- **Plain HTTP on the tailnet:** WireGuard encrypts the path. The page is HTTP and the socket is
  `ws://`, so there's no mixed content.
  - There are no secure-context APIs, so paste goes through a textarea and pointer lock has a
    drag fallback.
  - The CLI resolves the host once and pins that address. Without a token file it only talks
    to an address in Tailscale's ranges (`100.64.0.0/10`, `fd7a:115c:a1e0::/48`), so a hijacked
    name on the LAN gets nothing. It refuses redirects and proxies.

### Go↔Kotlin bridge (gomobile)
`remote/PROTOCOL.md` is the exact contract (bridge, connJson, messages, errors, close codes,
frames, pairing); a `core` test keeps it in sync with `core/remote/Protocol.kt` and
`remote/protocol/protocol.go`.

- **Go → Kotlin.** All of these only enqueue and return within 20ms, on Go goroutine threads.
  Go never holds a lock across a callback. Session IDs are int64.
  - `Open(sessionId, connJson)`, never dropped
  - `Command(sessionId, json)`; overflowing the session's queue closes it (`rate_limited`)
  - `Hid(sessionId, bytes) → queued | queue_full` (a dropped frame makes Kotlin release the
    holder's keys; release-all frames always get through)
  - `Closed(sessionId, reason)`, exactly once per session
- **Authorization fails closed.** Until Kotlin calls `Authorized(sessionId)`, Go forwards only
  `hello`, or `/pair` on the hotspot.
- **Queues:** per session, 64 inbound plus a priority lane for revoke, release and close.
- **Kotlin → Go:**
  - `Authorized(sessionId)`
  - `Send(sessionId, json)`: bounded to 32 outbound messages; a slow consumer is disconnected.
  - `Close(sessionId, reason)`
  - `Revoke(kind, id)`, with kind `node` (stable node ID) or `token` (token SHA-256)

  State pushes are coalesced to at most 10/s.
- **Late callbacks:** a callback that finishes late is fenced by the epoch, not by timing.
- **Kotlin side** (`core/remote`): `RemoteController.handle(event) → effects` is the pure state
  machine; `RemoteActor` queues events, runs one step at a time and dispatches effects to ports
  (`TransportPort`, `DevicePort`, `HidPort`, `ApprovalStore`, `PairingStore`, `Notifier`). Jobs run
  after the event in each step, so a close or revoke stops them before any further report. The
  actor polls `HidPort.releaseOwed` (the HidSession barrier) every 10ms while input waits on it.

### Protocol v1
- **Handshake:** `hello{v:1}` → `welcome{v:1, sessionId, auth, stateRev, lease{holder, epoch}}`.
  A version mismatch closes with 4400.
- **Requests:** `{id, t, epoch?, ...}` → `{id, ok, error?{code, msg}}`.
  - IDs are deduplicated per session over the last 64; a duplicate gets the cached reply.
  - `ifRev` is checked and applied atomically in the actor step.
  - Images are named by server `imageId`, never by path.
- **Requests and pushes:**
  - `state.get`
  - `lease.take` (only when free), `lease.takeover{holderSession}` with the holder's epoch in
    the envelope (the holder answers `lease.takeover_reply{allow}`; 30s without an answer
    declines), `lease.release`
  - `slots.apply{slots:[{lun, imageId, mode}], ifRev}`, `slot.eject{lun, force}`,
    `slot.reinsert{lun}`
  - `hid.set{keyboard, mouse}`
  - `tether.set{on}`: in the phone's mode; an NCM change is refused with `disks_exposed` by root,
    from the live LUNs under the gadget lock; 75s deadline. Clients always warn that turning it off may cut the remote (they can't
    tell whether it reaches the phone over that USB link).
  - `bootkey.run{vendor, action}`, `bootkey.cancel`
  - `paste.prepare{text}` → `{planId, unsupported, durationMs}`. Then `paste.run{planId}`, which
    is bound to the plan and the epoch, and `paste.cancel`. At most 4 plans per session, each
    living 2 minutes.
  - `pair{code, tokenHash}`: the hotspot `/pair` exchange. Go mints the token and forwards its
    hash on a short-lived session; Kotlin answers `{ok}` once and stores the hash on success.
  - `paste.run` and `bootkey.run` answer when the job ends.
  - Pushes: `welcome`, `auth`, `state{rev, ..., lease}`, `lease{lease:{holder, epoch, local}}`,
    `lease.takeover_request`, `lease.takeover_cancelled`.
- **Binary frames:** `epoch(u16 BE) op ...`, with exact lengths.
  - `01 uu` key down. A duplicate down is idempotent. Beyond 6 non-modifier keys the report
    sends rollover.
  - `02 uu` key up.
  - `03 dx dy` mouse move (int8).
  - `04 bb` mouse buttons (low 3 bits).
  - `06 wheel pan` scroll (int8 each).
  - `05` release-all. It's coalesced and exempt from rate limits, for the lease holder only.
  - Limits: 200 frames/s, paste ≤ 64 KiB and ≤ 5 min, boot keys within BootKeyMasher limits.
- **Connections:** at most 8, with at most 4 pending.
- **Reconnect:** a reconnect gets a fresh session and snapshot. Nothing is replayed.

### Android
- **Build:** `:app` depends on `remote/build/bootlegremote.aar` through the `buildRemoteAar` Gradle
  task (`mise run remote:aar`). Its inputs are `remote/` (minus `build/`) and `mise.toml`/
  `mise.lock`, and its output is cacheable, so `mise run check` only re-runs gomobile when Go
  code changes (warm: an up-to-date check). The APK is arm64-only (`libgojni.so`).
- **RemoteManager** (`app/.../remote/`) owns the node and the controller in the app process:
  - `Bootlegremote.start(noBackupFilesDir/tailscale, "bootleg", authKey, callbacks)`. The hostname
    is `bootleg`; the CLI defaults to it.
  - `RemoteActor.create(...)` with the ports: `GoTransport` (gomobile statics),
    `RemoteDevice` (the same `BootlegBackend` as the Drives screen; `setHid` goes through
    `HidController.setSpec` so the HID session closes before the functions go and reopens
    after), `HidController.remotePort` (the app's `HidSession`; `releaseOwed` is its owed
    release-all barrier), approval/pairing stores as atomic JSON files (temp, fsync, rename,
    directory fsync) in `noBackupFilesDir/remote`, and notifications. The actor runs on
    `Dispatchers.IO.limitedParallelism(1)`; device calls on another single-thread lane.
  - While Remote is on, the HID session stays open in the background (backgrounding only
    releases everything) and reopens whenever HID is enabled, so remote input finds it open.
  - Kotlin supplies the interface list (`NetworkInterfaces`) and forwards default-network
    changes (`registerDefaultNetworkCallback` → `Bootlegremote.networkChanged`).
  - Dev/test enrollment: an auth key in `filesDir/ts-authkey` registers the node and is deleted
    once it reaches Running (status is read once more after `start` returns, since callbacks
    during `start` are dropped). Users sign in through `loginURL` (the Remote screen opens the
    browser). Node state persists, so later starts need neither.
- **RemoteService:** `connectedDevice` FGS with `FOREGROUND_SERVICE_CONNECTED_DEVICE`;
  `CHANGE_WIFI_STATE` (hotspot) is the prerequisite.
  - It starts only from a visible user action (the Remote switch). Remote is off by default and
    not started on boot in v1; after a process death the sticky service restarts the node only
    if the switch is still on. If Android refuses the FGS start (a background caller such as the
    debug hook), the node still runs while the process lives.
  - One silent channel (`remote`) for the service notification and the "waiting for approval"
    one; both only open the Remote tab. Approvals also work in-app when notifications are
    denied.
- **UI:** a Remote tab (Downloads moved under Library: a top-bar button with the active count;
    Catalog still links to it; the bottom bar stays at 6). Switch, tailnet status (name, IPs,
    sign-in button), pending approvals with Approve (+ "Allow writable disks") / Deny, the
    controller (holder, local-priority chip, release-pending, keep-awake), connected sessions,
    approved devices (RW switch, revoke), offline mode (LocalOnlyHotspot, Wi-Fi QR code drawn
    from zxing's matrix, URL, pairing code with countdown, lockout reset) and paired tokens.
- **Local capture:** the Control tab resumed → `localCaptureStarted`, paused or left →
  `localCaptureStopped`.
- **Permissions:** `ACCESS_LOCAL_NETWORK` is requested when Remote is turned on, after an
  explanation ("Use relays only" skips it; a warning card offers it again).
  `NEARBY_WIFI_DEVICES` (`neverForLocation`) is requested when the hotspot starts.
- **Hotspot address:** the LocalOnlyHotspot interface isn't a `Network` apps can query, so the
  app takes the new up, private-IPv4 `wlanN`/`swlanN`/`apN` interface that isn't the default
  network's and passes its CIDR to `Bootlegremote.startHotspot`.
- **Hotspot lifecycle:** `OfflineMode` shares the node's lifecycle mutex. Every stop (the
  hotspot's or the node's) bumps a generation; Go's listener starts only under that lock, after
  Android's start returns, if the generation and the node are unchanged. A superseded start
  closes its late reservation and never starts the listener. Android's start is bounded (15s).
- **Keep-awake:** counts authenticated (approved) live sessions only.
  - App side: a `WIFI_MODE_FULL_LOW_LATENCY` Wi-Fi lock and a partial wake lock. Root side
    (`IBootlegRoot.setKeepAwake`, fixed argv): `stay_on_while_plugged_in` 7, `cmd wifi
    force-low-latency-mode enabled` and `input keyevent KEYCODE_WAKEUP` (screen on, never
    unlocks).
  - The prior `stay_on_while_plugged_in` is persisted (`keepawake-prior.json`) before the first
    change and restored when the last session ends, or by the app on its next process start
    whether or not Remote is turned back on. Forced low-latency mode can't be read back, so
    restoring turns it off.
  - The FGS isn't assumed to exempt the app from Doze. Unplugged idle and reconnect behaviour
    is tested manually.
- **Debug hooks** (debug builds, DUMP-protected `CommandProvider`): `REMOTE_ON`, `REMOTE_OFF`,
  `REMOTE_STATE`, `REMOTE_APPROVE --extra node:s:<id>`, `REMOTE_REVOKE --extra node:s:<id>`.
  They stand in for the in-app buttons while the screen is locked.

### Catalog downloads from the remote
- **Authority:** the lease holder (current epoch) may start and cancel catalog downloads, the
  same authority as a read-only mount: no per-device flag, no confirmation on the phone. Any
  approved session may list the catalog and sees download progress. `RemoteController` decides
  (lease, epoch, entry lookup, bounds); Go only carries the messages.
- **One download path:** `download.start` resolves the `entryId` to a `Release` from the app's
  cached catalog (never a URL or path from the client) and goes through the same `Downloads`
  admission and `DownloadScheduler` job as the Catalog tab: same record, engine, resume, SHA-256
  check, extraction, notifications, "Wi-Fi only" and library result. A paused or failed download
  resumes from its partial file. The controller sees the catalog and the download records through
  `DownloadPort` flows (latest wins, like device snapshots) and runs `start`/`cancel` on its own
  lane and dispatcher, one at a time with a 10 s deadline, so a download request never waits on a
  gadget operation. The catalog is consumed only as a snapshot (distros, releases, last fetch,
  error, refreshing) plus "refresh if stale": the repository's `refreshStale()`, the call the
  Catalog screen makes on open, decides which feeds are due (see Catalog), and the controller asks
  at most once per 60 s.
- **Background scheduling:** Android schedules a user-initiated job only while the app is
  visible or may start activities from the background (AOSP `canScheduleUserInitiatedJobs`); the
  Remote `connectedDevice` FGS doesn't qualify, so with the phone in a drawer `schedule` returns
  `RESULT_FAILURE`. Then root sends the app's `ScheduleDownloadReceiver` (exported, guarded by
  `DUMP`) a broadcast with `am broadcast --allow-background-activity-starts`; while it is being
  delivered the process holds background-activity-start privileges, and the receiver schedules
  the same job for the record that's waiting for it (a broadcast nobody asked for schedules
  nothing). Only if that fails too is the download marked failed and `download.start` answers
  `device`.
- **Bounds:** ≤ 4 downloads queued or running when the remote starts one, checked atomically with
  the record's admission (`too_many_downloads`; ≤ 2 transfer at once, as on the phone; the
  phone's own starts are never refused for it); ≤ 64 download records; free space ≥ what's left
  to fetch + 512 MiB (`storage_low`; the engine re-checks); `catalog.list` ≤ 256 entries / 48 KiB;
  `downloads` pushes ≤ 64 entries at ≤ 2/s.
- **Not held input:** revoke, takeover, lease loss and disconnect leave downloads running; a
  `download.*` call already dispatched finishes and its reply goes nowhere if the session ended.

### CLI (`remote/cmd/bootleg`)
- **Connection:** uses the Mac's own Tailscale. Offline, it takes `--host` plus a 0600 token file.
- **Commands:** `status`, `images`, `mount`, `eject`, `hid on|off`, `tether on|off|status`,
  `type <text>|-`, `key <chord> [--repeat --interval]`, `scroll <detents> [--horizontal]`,
  `bootkey`, `lease take|takeover|release`, `pair <code>`, `catalog [--arch] [--category]`,
  `download <entry> [--follow]`, `download --cancel <entry>`, `downloads`. An entry is its
  `entryId` or exact file name; `--follow` prints progress until the download finishes (exit 0
  verified or unverifiable, 5 failed).
- **`kbd`:** decoded text and key taps only, since terminals don't report key-up. It restores
  the terminal and sends release-all on signals and errors.
- **Takeover consent:** `kbd` and `lease take|takeover` prompt on a `lease.takeover_request`
  ("<name> asks for control. Allow? [y/N]"): in `kbd` the next y/n keystroke answers (and isn't
  typed), in `lease` a line from stdin; no answer within `timeoutMs` declines. One-shot commands
  decline at once so the requester isn't left waiting 30 s. The web UI shows an Allow / Decline
  card. Clients drop queued input whenever the lease epoch or holder changes.
- **Dense LUNs:** `mount` appends at LUN n (the next one); `--lun N` only replaces an existing
  slot. The web UI offers "LUN k (replace)" and "LUN n (new)". Both default the mode to the
  image's `defaultMode`.
- **Exit codes and deadlines:** 0 ok, 2 usage, 3 auth or pending, 4 lease, 5 device, 6 timeout.
  Every command has a deadline.

### Verification
- **L1/L2 (Kotlin):** `RemoteController` property and fault tests with a fake clock and a fake
  HidSession:
  - revoke during paste, takeover during boot-key mashing;
  - stale epoch frames after re-acquire;
  - late callbacks, half-open connections, malformed frames, slow readers;
  - restart mid-session;
  - pairing expiry, single use and lockout persistence;
  - duplicate request IDs.
- **L1/L2 (Go):** `go test` for the Origin/Host matrix, the hotspot ingress check, frame codec
  and lengths, rate limits, and the bridge against a fake Kotlin, including slow consumers.
- **Every check:** `mise run check` runs `go test` and a cached AAR/Kotlin compile check (the
  inputs-tracked Gradle task rebuilds only when `remote/` changes).
- **L4 `check:device:remote`** (`scripts/check-device-remote.sh`, ~35s warm, takes the device
  lock; the real `bootleg` CLI built into `build/bin`, over wireless adb because mounts and HID
  re-enumerate USB):
  1. Remote on through the debug hook; wait for the node to be Running. Any earlier approval of
     this Mac is revoked first, so every run starts unapproved.
  2. `status` → pending, exit 3; approve through the hook; `status` → approved.
  3. `mount` a cached, formatted 8 MB FAT image (copied into the library as
     `zz-bootleg-l4-remote.img`) read-only; the Mac mounts it read-only and reads it;
     `diskutil eject`.
  4. `hid on`, F24 and Shift taps only, `hid off`; the Mac enumerates and drops both devices.
  5. A background `bootleg lease take` holds a live session; revoke through the hook → it exits
     3 within 5s and the next `status` exits 3.
  6. Drives off, gadget clean (`function0` → `ffs.adb`), Remote off, USB adb back. An EXIT trap
     ejects, revokes and turns Remote and the drives off whatever happens.
  The time is mostly three gadget re-enumerations (mount, `hid on`, `hid off`) plus the final
  drives-off, 3.5–4s each, so it misses the 30s target by about 5s.
- **L4 `check:device:web`** (`scripts/check-device-web.sh` + `scripts/web-check/check.mjs`; run
  by hand, not part of `check:all`; takes the device lock per run). The phone's real page in the
  installed Google Chrome, driven by `playwright-core` (pinned in `scripts/web-check/package.json`,
  installed into `build/web-check`; it downloads no browser), over the tailnet:
  1. Response headers on every asset (CSP, `X-Frame-Options`, `nosniff`, no CORS) and a
     foreign-Origin `/ws` upgrade refused with 403.
  2. The page shows pending; approve through the hook; the page turns approved without a reload.
  3. Take control; the image picker lists library images by name (never ids or paths).
  4. Expose a 64 MB FAT32 blank (debug `CREATE_BLANK`) read-only from the page; the Mac mounts it
     read-only. The Mac unmounts it; the page's Eject is refused (macOS holds the medium even
     unmounted), the row offers Force eject, and the phone reads LUN 0 back empty.
  5. HID on from the page; F24 from the "More keys" overflow writes reports (`HID_STATE`); F24
     held in the capture area is released when another tab comes to the front; HID off.
  6. Paste prepare shows the plan (including a character the US layout can't type); Cancel,
     never run.
  7. A second browser context (same node, second session) asks to take over: decline keeps the
     lease, allow moves it.
  8. Revoke through the hook: both pages say revoked, their sockets close and they don't
     reconnect (a reconnect would show up as a new pending request).
  Only F24 reaches the Mac; no mouse button is ever sent. Every step saves a screenshot to
  `build/web-check/shots` (`BOOTLEG_WEB_HEADFUL=1` shows the window). An EXIT trap ejects,
  revokes, turns HID, Remote and the drives off and deletes the image.
- **Known gaps:**
  - Host-captured releases need a Mac capture helper. That needs the user to grant Input
    Monitoring once; until then releases are proved by phone-side `HID_STATE` plus
    enumeration.
  - A never-approved identity through the real transport is covered by revoking this Mac
    first. A *denied* or evicted identity, and two identities at once (takeover between
    computers), need a second tailnet identity (an ephemeral key from the user); until then
    they're covered at L2 and by the CLI's fake-phone tests.
  - Hotspot hardware checks are manual; the hotspot policy is tested at L2.

## Hard limits

| Limit | Value |
|---|---|
| LUNs | ≤ 8 |
| Concurrent downloads | ≤ 2 |
| Catalog fetch | 6 feeds at once, 4 requests per feed, 15s per request (10s connect), 30s per feed |
| HID queue | ≤ 256 |
| Paste | ≤ 64 KiB |
| Root op timeout | 10s |
| Reconcile | 1 per 2s, max 5 attempts |
| Gadget replans | ≤ 3 |
| hidg holder scan | ≤ 4096 processes, ≤ 262144 fds, 2s (incomplete counts as held) |
| Quarantined hidg fds | ≤ 1 per function (the first blocks HID opens and hid rebinds until reboot) |
| WebSocket message | ≤ 64 KiB |
| RNDIS daemon | one per mount; start ≤ 3s, stop ≤ 2s then SIGKILL (≤ 2s), command ≤ 2s; ≤ 16 queued control responses; control data stage 500ms; ≤ 16 KiB per host transfer; recovery ≤ 1 per 30s; stderr ≤ 200 lines |
| Prefix establishment | each USB mode step ≤ 5s (50ms polls); after a failure, none until the next apply |
| UDC write | SIGKILL of the RNDIS daemon after 3s, 5s in total; then every configfs access fails until it returns |
| Tethering | start answer 2s (2 attempts, transient errors only), interface 5s, HAL switch 5s, marker 10s; whole call ≈ 60s worst case, remote `tether.set` deadline 75s |

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
  `hidutil` must list both Bootleg devices with the expected usage pages. A Swift capture helper
  (`scripts/hidcapture.swift`, IOHIDManager on the gadget's vendor/product) records input values
  while F24 and Shift taps and cancelling mouse deltas are sent, and the check asserts that exactly
  those were seen. With the wheel layout the helper (`--describe`, enumeration only) must show a
  5-byte mouse report whose descriptor has Wheel and AC Pan; the check then parks the Mac's pointer
  in the middle of the menu bar (`--park-pointer`: absolute position recorded, warp, read back;
  the pointer is warped back at the end or on SIGTERM), sends one detent up and one down, and
  asserts exactly those wheel values arrived. The scroll is sent only once parked and capturing: a
  parking or capture error fails the check, and without Input Monitoring the scroll is skipped.
  HID off must leave both devices enumerated (idle) without a re-enumeration. Without Input Monitoring for the terminal the helper prints SKIP (it only calls
  `IOHIDCheckAccess`, never anything that prompts) and the check falls back to enumeration plus
  written-report counts. A device another app has seized (Karabiner-Elements seizes keyboards)
  can't be opened; that device's capture is skipped the same way. An EXIT/INT/TERM trap turns HID
  off again once the check has enabled it.
- L4 tethering (`mise run check:device:tether`, `scripts/check-device-tether.sh`, ~50s, takes the
  device lock, wireless adb, every adb call bounded; not in `check:all`): HID on with its session
  open (F24 sent), `TETHER_ON` (the Drives switch's path) must return `Changed` with no transition
  marker left, an `ncm.*` link next to `ffs.adb` and both HID links, no `/dev/hidg*` fd shown as
  deleted, and F24 must still reach the host (this and the same checks after `TETHER_OFF` are the
  device side of the HID fd rule for tethering); the Mac must
  get a new interface with an IPv4 address and fetch `generate_204` through it with
  `curl --interface`. A read-only FAT fixture is then exposed (HID kept in the desired state) and
  mounted while the curl still works, `TETHER_OFF` must be `Refused` naming LUN 0, the Mac ejects
  the volume and `TETHER_OFF` must then succeed and the interface go away. An EXIT/INT/TERM/HUP
  trap first waits (≤ 70s) until no tethering change is in flight (root's marker gone), then
  ejects, turns the drives and tethering off and puts HID back as it found it; if a change is
  still in flight it changes nothing and fails. It changes nothing on the
  Mac beyond what macOS does for any tethering phone (see device facts).
- RNDIS: `go test` covers the codec and session rules (with fuzzing) and the descriptor blob as f_fs
  6.1 parses it; core tests cover the prefix (planner properties, establishment and its crash
  recovery, dead-daemon restart) against a configfs fake that models FunctionFS, and the tethering
  transports; the dead-man's script and the bounded UDC write run in JVM tests. L4
  `check:device:rndis` sets the prefix up, checks it on the phone and the Mac (`ioreg`), runs a HAL
  round trip with HID off and no hidg fd open (restoring the original USB mode on every exit),
  mounts a disk after it, and toggles RNDIS tethering, failing on any re-enumeration (the volume's
  disk changes or DiskArbitration logs its removal). Cleanup acts only under its own lock and on
  what it exported, and removes a disk only after a successful state read shows it ejected.
- L3 tolerates upstream flakiness, not breakage: a scraper (and each image probe or sums fetch)
  that fails on a timeout, connection reset, 5xx, 425 or 429 is retried once (both scraper attempts
  share a 32s budget). At most one scraper per run may still fail that way; it is skipped with a
  `WARN L3:` line naming it. Parse, format and validation errors, or a second transient scraper,
  fail the run.
- Budget (warm cache), totalling ~2m50s: L0 ~15s, L1+L2 ~35s, L3 ~45s run in parallel with L4,
  and L4 ~100s.
- Accepted risks (v1): no TLS in offline hotspot mode (WPA2/3 link only), no checksum signature
  verification, no USB 3 (the cable negotiates high speed).
