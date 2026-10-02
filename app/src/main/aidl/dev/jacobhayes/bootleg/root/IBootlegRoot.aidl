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
}
