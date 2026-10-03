package dev.jacobhayes.bootleg.root;

// The app's HID client token (passed to hidOpen). Root tells it when the relay drops the client's
// fds on its own, so the session stops reporting a link that is gone, and when opens work again.
oneway interface IHidRelayClient {
    // Root released and closed (or quarantined) the client's hidg fds, or refused its open.
    // reopenFollows: opens are refused for now; onReopenable follows when the gadget change ends or
    // USB has stayed bound for the stable-bound window (at most 30s later).
    // restartRequired: an fd was quarantined; no open works until the phone restarts.
    void onClosed(String reason, boolean reopenFollows, boolean restartRequired);
    // Opens are safe again.
    void onReopenable();
}
