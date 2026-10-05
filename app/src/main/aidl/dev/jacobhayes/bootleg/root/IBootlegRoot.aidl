package dev.jacobhayes.bootleg.root;

// Typed root API. Structured values cross as kotlinx.serialization JSON; never shell strings.
// Validation failures surface as IllegalArgumentException / IllegalStateException.
interface IBootlegRoot {
    // JSON GadgetState
    String readGadget();
    // JSON Journal
    String readJournal();
    // desiredJson: JSON Desired; returns JSON ApplyResult. reconnect: re-plug USB after media went in
    // or changed in place (skipped while any LUN has writable media), for hosts that never look again.
    String apply(String desiredJson, boolean reconnect);
    String eject(int lun, boolean force);
    // Re-exports a host-/user-ejected or failed slot.
    String reinsert(int lun, boolean reconnect);
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
    // number) and keeps the fd: apps may not use the device-labelled node. client is an
    // IHidRelayClient: root tells it when it drops the fds itself, and its death makes root release
    // every key and button.
    void hidOpen(String kind, IBinder client);
    // Nonblocking write with a 50ms deadline; returns a HidWriteResult ordinal. Calls on one binder
    // are synchronous, so reports reach the host in call order.
    int hidWrite(String kind, in byte[] report);
    // A pending host -> device output report (keyboard LEDs), or empty.
    byte[] hidReadOutput(String kind);
    // Releases everything on that function and closes it.
    void hidClose(String kind);
    // Remote keep-awake knobs: Settings.Global stay_on_while_plugged_in (0-7), Wi-Fi forced
    // low-latency mode, and (wakeScreen) a KEYCODE_WAKEUP, which turns the screen on but never unlocks.
    void setKeepAwake(int stayOnWhilePluggedIn, boolean lowLatency, boolean wakeScreen);
    // USB tethering on or off over a mode's network function (TetherMode.wireName). Neither re-plugs USB;
    // on in a mode the prefix isn't set up with does (refused while a disk is exposed).
    // Returns JSON TetherResult. Holds the gadget lock for the whole change.
    String setUsbTethering(boolean on, String mode);
    // Whether Android reports Bootleg's interface tethered.
    boolean usbTethered();
    // Sends the app's ScheduleDownloadReceiver a broadcast that may start activities from the
    // background (am broadcast --allow-background-activity-starts), so it can schedule the
    // download's user-initiated job while the app is in the background. Returns once delivered.
    // token: the app's one-time token for this attempt (32 lower-case hex digits).
    void sendScheduleDownloadBroadcast(String fileName, String token);
}
