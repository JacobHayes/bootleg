# RNDIS on Windows: manual checklist

The RNDIS data path needs a host with an RNDIS driver, which the Mac running the L4 scripts lacks.
Run this by hand on a Windows 7 (or later) PC after a change to `rndis/`, the prefix or tethering;
`mise run check:device:rndis` covers the rest. Hold the device lock for the whole run.

Once per PC: Windows keeps a driver per interface number (`USB\VID_18D1&PID_4EEC&MI_nn`). In Device
Manager (View, Show hidden devices) uninstall every device with `VID_18D1&PID_4EEC&MI_0[0-3]`, then
replug the phone.

1. Phone: Drives, mode RNDIS, tethering off, no disk; Control, keyboard and mouse on (sets the
   prefix up; USB re-plugs).
2. Device Manager: "Remote NDIS Compatible Device" (MI_00) without a warning sign, "HID Keyboard
   Device" (MI_02), "HID-compliant mouse" (MI_03); the adapter shows "Network cable unplugged".
3. Phone: tethering on. `ipconfig` shows a `10.x.y.z` address with the phone as gateway and DNS;
   `ping 1.1.1.1`, `nslookup example.com` and `http://example.com` work; a few-MB download runs at
   several MB/s.
4. Phone: expose a read-only image. It shows in Explorer; the network keeps working.
5. Phone: tethering off. The adapter shows "Network cable unplugged"; the disk stays. Eject it.
6. Phone: mode NCM, tethering on (USB re-plugs; Windows 7 has no NCM driver), then off and back
   to RNDIS; keyboard and mouse off (they stay listed, idle).

Report the Windows version and per-step results with the change; don't commit them.
