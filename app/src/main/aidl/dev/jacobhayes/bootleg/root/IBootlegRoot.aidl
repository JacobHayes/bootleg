package dev.jacobhayes.bootleg.root;

// Typed root API. Structured values cross as kotlinx.serialization JSON; never shell strings.
// Validation failures surface as IllegalArgumentException / IllegalStateException.
interface IBootlegRoot {
    // JSON GadgetState
    String readGadget();
    // JSON Journal
    String readJournal();
    // desiredJson: JSON Desired; returns JSON ApplyResult
    String apply(String desiredJson);
    String eject(int lun, boolean force);
    // Re-exports a host-/user-ejected or failed slot.
    String reinsert(int lun);
    String bootlegOff();
    String reconcile();
    // format: BlankImageFormat name; returns JSON RootImage
    String createBlankImage(String name, long sizeBytes, String format, boolean preallocate);
    void deleteImage(String path);
    // JSON list of RootImage
    String listImages();
    // Grants the app MANAGE_EXTERNAL_STORAGE via appops; returns true on success.
    boolean grantSelfStorage();
    // HID relay. kind: "keyboard" | "mouse". Root opens that function's /dev/hidgN (matched by device
    // number) and keeps the fd: apps may not use the device-labelled node. client is a token whose
    // death makes root release every key and button.
    void hidOpen(String kind, IBinder client);
    // Nonblocking write with a 50ms deadline; returns a HidWriteResult ordinal. Calls on one binder
    // are synchronous, so reports reach the host in call order.
    int hidWrite(String kind, in byte[] report);
    // A pending host -> device output report (keyboard LEDs), or empty.
    byte[] hidReadOutput(String kind);
    // Releases everything on that function and closes it.
    void hidClose(String kind);
}
