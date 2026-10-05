// Bootleg remote web UI. No framework, no build step; served under
// `default-src 'self'`, so no inline script or style. Untrusted text (image
// and device names) only ever goes into textContent.
"use strict";

const PROTOCOL_VERSION = 1;
const REQUEST_TIMEOUT_MS = 15000;
// The phone allows tether.set 75 s (setting its USB functions up, then Android).
const TETHER_REQUEST_TIMEOUT_MS = 80000;
const RECONNECT_DELAY_MIN_MS = 1000;
const RECONNECT_DELAY_MAX_MS = 15000;
// Under the server's 200 frames/s so a burst never ends the session.
const FRAMES_PER_SECOND_MAX = 150;
const FRAME_QUEUE_LENGTH_MAX = 256;
const MOUSE_DELTA_MAX = 127;
const LUN_COUNT_MAX = 8;
const TAP_MOVE_PX_MAX = 4;
const TAP_DURATION_MS_MAX = 250;
// Wheel events: pixels per detent (browsers report ~100 px per notch), and
// detents per line or page for the other delta modes. Two-finger touch drags
// scroll one detent per TOUCH_SCROLL_PX_PER_DETENT of centroid travel.
const WHEEL_PX_PER_DETENT = 100;
const WHEEL_DETENTS_PER_LINE = 1 / 3;
const WHEEL_DETENTS_PER_PAGE = 10;
const TOUCH_SCROLL_PX_PER_DETENT = 20;
const SCROLL_DETENTS_MAX = 127;
// One WebSocket text message (protocol.MessageBytesMax), counted in UTF-8.
const MESSAGE_BYTES_MAX = 64 * 1024;
// paste.prepare text, in UTF-8 bytes.
const PASTE_TEXT_BYTES_MAX = 64 * 1024;
const TAKEOVER_TIMEOUT_MS_DEFAULT = 30000;
const FUNCTION_KEY_COUNT_VISIBLE = 12;
const FUNCTION_KEY_COUNT_MAX = 24;
// Close reasons after which reconnecting would only ask the phone for approval again.
const CLOSE_REASONS_FINAL = new Set(["revoked", "expired", "denied", "unauthorized"]);

const OP_KEY_DOWN = 1, OP_KEY_UP = 2, OP_MOUSE_MOVE = 3, OP_MOUSE_BUTTONS = 4, OP_RELEASE_ALL = 5, OP_SCROLL = 6;
const MODIFIER_USAGE_MIN = 0xe0;

const BOOT_VENDORS = [
  ["generic", "Other / unknown"], ["dell", "Dell"], ["lenovo", "Lenovo"], ["hp", "HP"],
  ["asus", "ASUS"], ["acer", "Acer"], ["msi", "MSI"], ["gigabyte", "Gigabyte"],
  ["intel_nuc", "Intel NUC"], ["apple_mac", "Apple Mac (Intel)"], ["microsoft_surface", "Microsoft Surface"],
];

const MODE_LABELS = { cdrom: "CD-ROM", disk_ro: "Read-only", disk_rw: "Read-write" };
const STATUS_LABELS = { exported: "Inserted", host_ejected: "Ejected by the computer", user_ejected: "Ejected", failed: "Failed" };

const CHORD_ALIASES = {
  ctrl: "ControlLeft", control: "ControlLeft", shift: "ShiftLeft", alt: "AltLeft", option: "AltLeft",
  cmd: "MetaLeft", win: "MetaLeft", meta: "MetaLeft", esc: "Escape", tab: "Tab", enter: "Enter",
  space: "Space", delete: "Delete", printscreen: "PrintScreen", q: "KeyQ",
};

const $ = (id) => document.getElementById(id);

const app = {
  transport: "tailnet",
  socket: null,
  reconnectDelayMs: RECONNECT_DELAY_MIN_MS,
  reconnectTimer: 0,
  sessionId: null,
  auth: null,
  lease: { holder: null, epoch: 0 },
  state: null,
  keyCodes: {},
  nextRequestId: 1,
  pending: new Map(),
  pressed: new Set(),
  buttons: 0,
  pastePlanId: null,
  // LUNs whose plain eject the computer refused: their row offers Force eject.
  forceEjectLuns: new Set(),
  frames: [],
  frameTokens: FRAMES_PER_SECOND_MAX,
  frameRefillAt: performance.now(),
  frameTimer: 0,
  moveX: 0,
  moveY: 0,
  moveScheduled: false,
  // Pending scroll in detents (fractional); wheel + is up, pan + is right.
  scrollWheel: 0,
  scrollPan: 0,
  scrollScheduled: false,
  // Touch pointers on the pad (id → {x, y}); two of them scroll.
  touches: new Map(),
  pointerLocked: false,
  drag: null,
  // The pending lease.takeover_request: { requester, name, timer }.
  takeover: null,
  initialized: false,
  // The last catalog.list reply, the downloads push and the catalog push's rev.
  catalog: null,
  downloads: [],
  catalogRev: 0,
};

const utf8 = new TextEncoder();
function utf8Length(text) { return utf8.encode(text).length; }

function log(message) { $("log").textContent = message; }

function holdsLease() {
  return app.sessionId !== null && app.lease.holder === app.sessionId;
}

// ---- Connection -------------------------------------------------------

// Builds and binds the page once; retries of start() only refetch.
function init() {
  if (app.initialized) return;
  app.initialized = true;
  buildStaticControls();
  bindControls();
}

async function start() {
  init();
  try {
    const [transportInfo, keyCodes] = await Promise.all([
      fetch("api/transport").then((response) => response.json()),
      fetch("keycodes.json").then((response) => response.json()),
    ]);
    app.transport = transportInfo.transport;
    app.keyCodes = keyCodes;
    if (app.transport === "hotspot" && !transportInfo.hasToken) {
      showPairing();
      return;
    }
  } catch (error) {
    setBanner("bad", "Can't reach the phone. Retrying…");
    scheduleReconnect();
    return;
  }
  connect();
}

function connect() {
  clearTimeout(app.reconnectTimer);
  setBanner("", "Connecting…");
  const socket = new WebSocket(`ws://${location.host}/ws`);
  socket.binaryType = "arraybuffer";
  app.socket = socket;
  socket.addEventListener("open", () => {
    app.reconnectDelayMs = RECONNECT_DELAY_MIN_MS;
    socket.send(JSON.stringify({ id: 0, t: "hello", v: PROTOCOL_VERSION }));
  });
  socket.addEventListener("message", (event) => {
    if (typeof event.data === "string") handleMessage(JSON.parse(event.data));
  });
  socket.addEventListener("close", (event) => onClosed(socket, event));
}

function onClosed(socket, event) {
  if (app.socket !== socket) return;
  app.socket = null;
  app.sessionId = null;
  dropInput();
  hideTakeover();
  for (const { reject, timer } of app.pending.values()) {
    clearTimeout(timer);
    reject(new Error("disconnected"));
  }
  app.pending.clear();
  render();
  if (event.code === 4400) {
    setBanner("bad", "This page is out of date. Reload it.");
    return;
  }
  if (event.code === 4401 && app.transport === "hotspot") {
    showPairing("This browser is no longer paired.");
    return;
  }
  // A reconnect after a revoke or deny would show up on the phone as a new
  // approval request, so the page stops here until the user reloads it.
  if (event.code === 4401 || CLOSE_REASONS_FINAL.has(event.reason)) {
    const what = event.reason === "denied" ? "This device was denied" : event.reason === "expired" ? "This device's approval expired" : "Access was revoked";
    setBanner("bad", `${what} on the phone. Reload the page to ask again.`);
    return;
  }
  const reason = event.reason ? ` (${event.reason})` : "";
  setBanner("bad", `Disconnected${reason}. Reconnecting…`);
  scheduleReconnect();
}

function scheduleReconnect() {
  clearTimeout(app.reconnectTimer);
  app.reconnectTimer = setTimeout(() => (app.keyCodes.KeyA ? connect() : start()), app.reconnectDelayMs);
  app.reconnectDelayMs = Math.min(app.reconnectDelayMs * 2, RECONNECT_DELAY_MAX_MS);
}

function handleMessage(message) {
  const lease = message.lease || message.state?.lease;
  if (lease) setLease(normalizeLease(lease));
  if (message.t === "lease.takeover_request") {
    showTakeover(message);
  } else if (message.t === "lease.takeover_cancelled") {
    if (app.takeover && app.takeover.requester === message.requester) {
      hideTakeover();
      log("The takeover request was withdrawn.");
    }
  } else if (message.t === "welcome") {
    app.sessionId = message.sessionId;
    app.auth = message.auth;
    if (app.auth === "approved") { refreshState(); listCatalog(); }
  } else if (message.t === "auth") {
    app.auth = message.auth;
    if (app.auth === "approved") { refreshState(); listCatalog(); }
  } else if (message.t === "downloads") {
    onDownloads(Array.isArray(message.downloads) ? message.downloads : []);
  } else if (message.t === "catalog") {
    if (message.catalogRev !== app.catalogRev) listCatalog();
    app.catalogRev = message.catalogRev;
  } else if (message.t === "state") {
    app.state = message;
  } else if (message.t === undefined && message.id !== undefined && app.pending.has(message.id)) {
    const { resolve, reject, timer } = app.pending.get(message.id);
    app.pending.delete(message.id);
    clearTimeout(timer);
    if (message.state) app.state = message.state;
    if (message.ok) resolve(message);
    else reject(new Error(message.error ? `${message.error.code}${message.error.msg ? ": " + message.error.msg : ""}` : "failed"));
  }
  render();
}

function normalizeLease(lease) {
  return { holder: lease.holder ?? null, holderName: lease.holderName || "", epoch: lease.epoch || 0, local: !!lease.local };
}

// A new epoch or holder voids everything tied to the old lease: queued
// frames carry its epoch, held keys and buttons were released by the phone,
// and a takeover prompt no longer applies.
function setLease(lease) {
  const previous = app.lease;
  app.lease = lease;
  if (lease.epoch !== previous.epoch || lease.holder !== previous.holder) {
    dropInput();
    hideTakeover();
  }
}

function dropInput() {
  app.frames = [];
  clearTimeout(app.frameTimer);
  app.pressed.clear();
  app.buttons = 0;
  app.moveX = app.moveY = 0;
}

// ---- Takeover prompt ----------------------------------------------------

function showTakeover(message) {
  hideTakeover();
  const name = message.requesterName || `Session ${message.requester}`;
  const timeoutMs = message.timeoutMs > 0 ? message.timeoutMs : TAKEOVER_TIMEOUT_MS_DEFAULT;
  app.takeover = {
    requester: message.requester,
    timer: setTimeout(() => {
      hideTakeover();
      log(`No answer to ${name}; the phone declined.`);
    }, timeoutMs),
  };
  $("takeover-text").textContent = `${name} asks to take control. Allow?`;
  $("takeover").hidden = false;
}

function hideTakeover() {
  if (app.takeover) clearTimeout(app.takeover.timer);
  app.takeover = null;
  $("takeover").hidden = true;
}

function answerTakeover(allow) {
  if (!app.takeover) return;
  hideTakeover();
  if (allow) releaseAll();
  act(allow ? "Hand over control" : "Decline takeover", "lease.takeover_reply", { allow });
}

function request(type, fields = {}, mutation = true) {
  return new Promise((resolve, reject) => {
    if (!app.socket || app.socket.readyState !== WebSocket.OPEN) {
      reject(new Error("not connected"));
      return;
    }
    const id = app.nextRequestId++;
    const message = { ...fields, id, t: type };
    if (mutation) message.epoch = app.lease.epoch;
    const encoded = JSON.stringify(message);
    const size = utf8Length(encoded);
    if (size > MESSAGE_BYTES_MAX) {
      reject(new Error(`too large to send (${size} bytes; the limit is ${MESSAGE_BYTES_MAX})`));
      return;
    }
    const timer = setTimeout(() => {
      app.pending.delete(id);
      reject(new Error("timed out"));
    }, type === "tether.set" ? TETHER_REQUEST_TIMEOUT_MS : REQUEST_TIMEOUT_MS);
    app.pending.set(id, { resolve, reject, timer });
    app.socket.send(encoded);
  });
}

async function act(label, type, fields) {
  try {
    const reply = await request(type, fields);
    log(`${label}: done`);
    // The phone pushes state on changes; this just avoids waiting for it.
    if (!type.startsWith("lease.") && !type.startsWith("paste.")) refreshState();
    return reply;
  } catch (error) {
    log(`${label}: ${error.message}`);
    return null;
  }
}

async function setTether(on) {
  const reply = await act(`USB tethering ${on ? "on" : "off"}`, "tether.set", { on });
  if (reply && reply.warning) log(`USB tethering: ${reply.warning}`);
}

function refreshState() {
  request("state.get", {}, false).catch((error) => log(`State: ${error.message}`));
}

// ---- Pairing (offline hotspot) ---------------------------------------

function showPairing(message) {
  $("main").hidden = true;
  $("pair").hidden = false;
  setBanner("warn", message || "Pair this browser with the phone.");
  $("pair-code").focus();
}

async function pair(event) {
  event.preventDefault();
  const code = $("pair-code").value.trim();
  $("pair-error").hidden = true;
  try {
    const response = await fetch("pair", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ code }),
    });
    const reply = await response.json();
    if (!reply.ok) throw new Error(reply.error ? reply.error.msg || reply.error.code : `HTTP ${response.status}`);
    $("pair").hidden = true;
    $("pair-code").value = "";
    connect();
  } catch (error) {
    $("pair-error").textContent = `Pairing failed: ${error.message}`;
    $("pair-error").hidden = false;
  }
}

// ---- Rendering --------------------------------------------------------

function setBanner(kind, text) {
  const banner = $("banner");
  banner.className = `banner ${kind}`;
  banner.textContent = text;
}

function describeLease() {
  const lease = app.lease;
  if (lease.local) return "The phone's Control tab has priority.";
  if (lease.holder === null) return "Nobody has control.";
  if (holdsLease()) return "You have control.";
  const name = lease.holderName ? ` (${lease.holderName})` : "";
  return `Session ${lease.holder}${name} has control.`;
}

function render() {
  const connected = app.socket && app.sessionId !== null;
  const approved = connected && app.auth === "approved";
  if (connected && !approved) {
    setBanner("warn", "Waiting for approval: approve this device on your phone (Bootleg → Remote).");
  } else if (approved) {
    setBanner(holdsLease() ? "ok" : "", `Connected. ${describeLease()}`);
  }
  $("main").hidden = !approved;
  if (!approved) return;

  $("lease-text").textContent = `${describeLease()} Epoch ${app.lease.epoch}.`;
  $("lease-take").disabled = app.lease.holder !== null || app.lease.local;
  $("lease-takeover").disabled = app.lease.holder === null || holdsLease() || app.lease.local;
  $("lease-release").disabled = !holdsLease();
  for (const element of document.querySelectorAll(".needs-lease")) element.disabled = !holdsLease();
  const scrollHint = canScroll() ? " Scroll with the wheel or two fingers." : "";
  $("pad-hint").textContent = holdsLease()
    ? (app.pointerLocked ? "Captured. Press Esc to let go of the mouse." : "Click here to capture keyboard and mouse.") + scrollHint
    : "Take control to type or move the mouse.";
  renderState();
  renderCatalog();
}

// ---- Catalog and downloads ------------------------------------------------

const ENTRY_STATE_LABELS = {
  available: "", queued: "Queued", running: "Downloading", paused: "Paused", verifying: "Verifying",
  completed: "Downloaded", failed: "Failed", downloaded: "In library",
};

function listCatalog() {
  const fields = { arch: $("catalog-arch").value, category: $("catalog-category").value };
  request("catalog.list", fields, false)
    .then((reply) => {
      app.catalog = reply;
      app.catalogRev = reply.catalogRev;
      render();
    })
    .catch((error) => log(`Catalog: ${error.message}`));
}

// A download that finished changes its entry's state: ask for the list again.
function onDownloads(downloads) {
  const before = new Map(app.downloads.map((download) => [download.entryId, download.status]));
  app.downloads = downloads;
  const finished = downloads.some((download) => download.status === "completed" && before.get(download.entryId) !== "completed");
  const removed = [...before.keys()].some((entryId) => !downloads.some((download) => download.entryId === entryId));
  if (finished || removed) listCatalog();
}

function formatBytes(bytes) {
  if (!Number.isFinite(bytes) || bytes <= 0) return "";
  const units = ["B", "KiB", "MiB", "GiB", "TiB"];
  let value = bytes, unit = 0;
  while (value >= 1024 && unit < units.length - 1) { value /= 1024; unit++; }
  return `${value.toFixed(unit === 0 ? 0 : 1)} ${units[unit]}`;
}

function describeDownload(download) {
  if (download.status === "running" && download.totalBytes > 0) {
    return `${Math.floor((download.downloadedBytes * 100) / download.totalBytes)}% of ${formatBytes(download.totalBytes)}`;
  }
  if (download.status === "completed") return download.verified ? "SHA-256 verified" : "Downloaded (no published checksum)";
  const label = ENTRY_STATE_LABELS[download.status] || download.status;
  return download.error ? `${label}: ${download.error}` : label;
}

function isActive(status) { return status === "queued" || status === "running" || status === "verifying"; }

function entryRow(text, stateText, download, actions) {
  const row = document.createElement("div");
  row.className = "entry";
  const what = document.createElement("span");
  what.className = "what";
  what.textContent = text;
  const state = document.createElement("span");
  state.className = "state";
  state.textContent = stateText;
  row.append(what, state);
  if (download && download.status === "running" && download.totalBytes > 0) {
    const bar = document.createElement("progress");
    bar.max = download.totalBytes;
    bar.value = Math.min(download.downloadedBytes, download.totalBytes);
    row.append(bar);
  }
  for (const action of actions) {
    action.disabled = !holdsLease();
    row.append(action);
  }
  return row;
}

function cancelButton(download) {
  return button("Cancel", () => act(`Cancel ${download.fileName}`, "download.cancel", { entryId: download.entryId }));
}

function renderCatalog() {
  const downloadsById = new Map(app.downloads.map((download) => [download.entryId, download]));
  // Downloads still in progress or stopped; finished ones show in the catalog as in the library.
  const unfinished = app.downloads.filter((download) => download.status !== "completed");
  $("downloads").replaceChildren(...unfinished.map((download) =>
    entryRow(download.title || download.fileName, describeDownload(download), download, [cancelButton(download)])));

  const catalog = app.catalog;
  const status = [];
  if (!catalog) status.push("Loading…");
  else {
    if (catalog.refreshing) status.push("Refreshing from the distros…");
    if (catalog.truncated) status.push("More entries than fit; pick another category.");
  }
  $("catalog-status").textContent = status.join(" ");
  if (!catalog) return;
  const verifiedNames = new Set(((app.state || {}).images || []).filter((image) => image.verified).map((image) => image.name));
  const children = [];
  for (const distro of catalog.distros || []) {
    const heading = document.createElement("h3");
    heading.textContent = distro.error ? `${distro.name} (last good list: ${distro.error})` : distro.name;
    children.push(heading);
    for (const entry of distro.entries || []) {
      const download = downloadsById.get(entry.entryId);
      const state = download && download.status !== "completed" ? download.status : entry.state;
      const text = [entry.version, entry.variant, formatBytes(entry.sizeBytes), entry.fileName].filter(Boolean).join(" · ");
      let stateText = download && download.status !== "completed" ? describeDownload(download) : (ENTRY_STATE_LABELS[state] ?? state);
      if (state === "downloaded" && verifiedNames.has(entry.fileName)) stateText = "In library, SHA-256 verified";
      const actions = [];
      if (isActive(state)) {
        actions.push(cancelButton({ entryId: entry.entryId, fileName: entry.fileName }));
      } else if (state !== "downloaded" && state !== "completed") {
        const label = state === "paused" || state === "failed" ? "Resume" : "Download";
        actions.push(button(label, () => act(`Download ${entry.fileName}`, "download.start", { entryId: entry.entryId })));
      }
      children.push(entryRow(text, stateText, download, actions));
    }
  }
  $("catalog").replaceChildren(...children);
}

function renderState() {
  const state = app.state || {};
  const slots = (state.slots || []).filter((slot) => slot.imageId);
  const images = state.images || [];
  const imageNames = new Map(images.map((image) => [image.imageId, image.name]));

  const body = $("slots").querySelector("tbody");
  body.replaceChildren(...slots.map((slot) => {
    const row = document.createElement("tr");
    const name = slot.name || imageNames.get(slot.imageId) || slot.imageId;
    const mode = MODE_LABELS[slot.mode] || slot.mode || "";
    const status = STATUS_LABELS[slot.status] || slot.status || "";
    for (const text of [String(slot.lun), name, mode, status]) {
      const cell = document.createElement("td");
      cell.textContent = text;
      row.append(cell);
    }
    // Ejecting an ejected slot does nothing on the phone, so offer only the
    // action that applies.
    const actions = document.createElement("td");
    let action;
    if (slot.status !== "exported") {
      app.forceEjectLuns.delete(slot.lun);
      action = button("Reinsert", () => act(`Reinsert LUN ${slot.lun}`, "slot.reinsert", { lun: slot.lun }));
    } else if (app.forceEjectLuns.has(slot.lun)) {
      action = button("Force eject", () => ejectSlot(slot.lun, true));
    } else {
      action = button("Eject", () => ejectSlot(slot.lun, false));
    }
    action.disabled = !holdsLease();
    actions.append(action);
    row.append(actions);
    return row;
  }));
  $("slots-empty").hidden = slots.length > 0;
  $("slots").hidden = slots.length === 0;

  const imageSelect = $("expose-image");
  const selectedImage = imageSelect.value;
  imageSelect.replaceChildren(...images.map((image) => option(image.imageId, image.name)));
  if (images.some((image) => image.imageId === selectedImage)) imageSelect.value = selectedImage;
  if (imageSelect.value !== selectedImage) applyDefaultMode();

  // slots.apply needs LUNs exactly 0..n-1: replace an existing LUN or add
  // LUN n. "new" stays "new" across renders even as n changes.
  const lunSelect = $("expose-lun");
  const selectedLun = lunSelect.value;
  const existing = (state.slots || []).map((slot) => slot.lun).sort((a, b) => a - b);
  const luns = existing.map((lun) => option(String(lun), `LUN ${lun} (replace)`));
  if (existing.length < LUN_COUNT_MAX) {
    luns.push(option("new", `LUN ${existing.length} (new)`));
  } else {
    const placeholder = option("", `All ${LUN_COUNT_MAX} LUNs in use: pick one to replace`);
    placeholder.disabled = true;
    luns.unshift(placeholder);
  }
  lunSelect.replaceChildren(...luns);
  const values = luns.filter((element) => !element.disabled).map((element) => element.value);
  lunSelect.value = values.includes(selectedLun) ? selectedLun : (existing.length < LUN_COUNT_MAX ? "new" : "");

  // Absent until the phone knows.
  const tether = state.tether;
  const mode = tether && tether.mode ? ` (${tether.mode.toUpperCase()})` : "";
  $("tether-state").textContent = tether ? (tether.on ? "On" : "Off") + mode : "Unknown";
  $("tether-on").hidden = !!(tether && tether.on);
  $("tether-off").hidden = !(tether && tether.on);
  for (const id of ["tether-on", "tether-off"]) $(id).disabled = !holdsLease();

  const hid = state.hid || {};
  if (document.activeElement !== $("hid-keyboard")) $("hid-keyboard").checked = !!hid.keyboard;
  if (document.activeElement !== $("hid-mouse")) $("hid-mouse").checked = !!hid.mouse;
  // The owed-release status wins; otherwise the link, unless it is open, then the endpoints the computer ignores
  // (firmware and boot menus often read only the keyboard).
  const notReading = hid.link === "open" && hid.notReading && hid.notReading.length
    ? `the computer isn't reading the ${hid.notReading.join(" or ")}`
    : "";
  $("hid-status").textContent = hid.status || (hid.link && hid.link !== "open" ? hid.link.replace("_", " ") : notReading);
}

// The computer may hold the medium (macOS keeps it locked even after an
// unmount): the phone answers device_attention, and the row then offers a
// forced eject. Eject the volume on the computer first.
async function ejectSlot(lun, force) {
  const label = `${force ? "Force eject" : "Eject"} LUN ${lun}`;
  try {
    await request("slot.eject", { lun, force });
    log(`${label}: done`);
    app.forceEjectLuns.delete(lun);
    refreshState();
  } catch (error) {
    if (!force && error.message.startsWith("device_attention")) {
      app.forceEjectLuns.add(lun);
      log(`${label}: the computer is holding the disk. Eject it there first, then use Force eject.`);
    } else {
      log(`${label}: ${error.message}`);
    }
    render();
  }
}

// Sets the expose mode for the selected image: the phone's defaultMode, or
// the file-name rule only if it sent none.
function applyDefaultMode() {
  const imageId = $("expose-image").value;
  const image = ((app.state || {}).images || []).find((candidate) => candidate.imageId === imageId);
  if (!image) return;
  const name = image.name || "";
  $("expose-mode").value = image.defaultMode || (name.toLowerCase().endsWith(".iso") ? "cdrom" : "disk_ro");
}

function button(label, onClick) {
  const element = document.createElement("button");
  element.type = "button";
  element.className = "secondary";
  element.textContent = label;
  element.addEventListener("click", onClick);
  return element;
}

function option(value, label) {
  const element = document.createElement("option");
  element.value = value;
  element.textContent = label;
  return element;
}

// `unsupported` is [{index, codePoint}] (the first 64); `unsupportedCount`
// is the total.
function describePastePlan(text, plan) {
  const characters = [...text].length;
  const seconds = Math.max(1, Math.ceil((plan.durationMs || 0) / 1000));
  const skipped = Array.isArray(plan.unsupported) ? plan.unsupported : [];
  const skippedCount = Number.isInteger(plan.unsupportedCount) ? plan.unsupportedCount : skipped.length;
  let description = `${characters} characters, about ${seconds}s of typing.`;
  if (skippedCount > 0) {
    const shown = [...new Set(skipped.map((entry) => describeCodePoint(entry.codePoint)))].join(" ");
    description += ` Skips ${skippedCount} it can't type (US layout): ${shown}${skippedCount > skipped.length ? " …" : ""}`;
  }
  return description;
}

function describeCodePoint(codePoint) {
  if (!Number.isInteger(codePoint) || codePoint < 0 || codePoint > 0x10ffff) return "?";
  const hex = `U+${codePoint.toString(16).toUpperCase().padStart(4, "0")}`;
  // Control and invisible characters by number only.
  return codePoint < 0x20 || (codePoint >= 0x7f && codePoint < 0xa0) || /\p{Cf}|\p{Z}/u.test(String.fromCodePoint(codePoint))
    ? hex
    : `${String.fromCodePoint(codePoint)} (${hex})`;
}

// ---- HID frames -------------------------------------------------------

function encodeFrame(op, first, second) {
  const epoch = app.lease.epoch & 0xffff;
  const head = [epoch >> 8, epoch & 0xff, op];
  if (op === OP_KEY_DOWN || op === OP_KEY_UP || op === OP_MOUSE_BUTTONS) head.push(first & 0xff);
  if (op === OP_MOUSE_MOVE || op === OP_SCROLL) head.push(first & 0xff, second & 0xff);
  return new Uint8Array(head);
}

function sendFrame(op, first, second) {
  if (!holdsLease() || !app.socket) return;
  if (op === OP_RELEASE_ALL) {
    // Exempt from pacing and sent ahead of anything queued.
    app.frames = [];
    app.socket.send(encodeFrame(op));
    return;
  }
  if (app.frames.length >= FRAME_QUEUE_LENGTH_MAX) {
    releaseAll("input queue overflow");
    return;
  }
  app.frames.push(encodeFrame(op, first, second));
  pumpFrames();
}

function pumpFrames() {
  clearTimeout(app.frameTimer);
  const now = performance.now();
  app.frameTokens = Math.min(FRAMES_PER_SECOND_MAX, app.frameTokens + ((now - app.frameRefillAt) / 1000) * FRAMES_PER_SECOND_MAX);
  app.frameRefillAt = now;
  while (app.frames.length && app.frameTokens >= 1 && app.socket) {
    app.socket.send(app.frames.shift());
    app.frameTokens -= 1;
  }
  if (app.frames.length) app.frameTimer = setTimeout(pumpFrames, 1000 / FRAMES_PER_SECOND_MAX);
}

// Repeats are cheap: the server coalesces release-all frames with the same
// epoch.
function releaseAll(why) {
  app.pressed.clear();
  app.buttons = 0;
  app.moveX = app.moveY = 0;
  app.scrollWheel = app.scrollPan = 0;
  sendFrame(OP_RELEASE_ALL);
  if (why) log(`Released all keys (${why}).`);
}

function queueMove(dx, dy) {
  app.moveX += dx;
  app.moveY += dy;
  if (app.moveScheduled) return;
  app.moveScheduled = true;
  requestAnimationFrame(() => {
    app.moveScheduled = false;
    while (app.moveX !== 0 || app.moveY !== 0) {
      const stepX = Math.max(-MOUSE_DELTA_MAX, Math.min(MOUSE_DELTA_MAX, Math.round(app.moveX)));
      const stepY = Math.max(-MOUSE_DELTA_MAX, Math.min(MOUSE_DELTA_MAX, Math.round(app.moveY)));
      if (stepX === 0 && stepY === 0) break;
      app.moveX -= stepX;
      app.moveY -= stepY;
      sendFrame(OP_MOUSE_MOVE, stepX, stepY);
    }
  });
}

// The phone's mouse takes scroll frames when it says so (hid.wheel); older
// phones don't say, and get none.
function canScroll() {
  return holdsLease() && !!(app.state && app.state.hid && app.state.hid.wheel);
}

// Whole detents go out (clamped per frame); the fraction waits for more.
function queueScroll(wheel, pan) {
  if (!canScroll()) return;
  // Bounded while waiting for the frame: at most one frame's worth plus the fraction.
  const pendingMax = SCROLL_DETENTS_MAX + 1;
  app.scrollWheel = Math.max(-pendingMax, Math.min(pendingMax, app.scrollWheel + wheel));
  app.scrollPan = Math.max(-pendingMax, Math.min(pendingMax, app.scrollPan + pan));
  if (app.scrollScheduled) return;
  app.scrollScheduled = true;
  requestAnimationFrame(() => {
    app.scrollScheduled = false;
    const stepWheel = Math.max(-SCROLL_DETENTS_MAX, Math.min(SCROLL_DETENTS_MAX, Math.trunc(app.scrollWheel)));
    const stepPan = Math.max(-SCROLL_DETENTS_MAX, Math.min(SCROLL_DETENTS_MAX, Math.trunc(app.scrollPan)));
    if (stepWheel === 0 && stepPan === 0) return;
    // A burst beyond one frame's range is dropped, not queued.
    app.scrollWheel = Math.max(-1, Math.min(1, app.scrollWheel - stepWheel));
    app.scrollPan = Math.max(-1, Math.min(1, app.scrollPan - stepPan));
    sendFrame(OP_SCROLL, stepWheel, stepPan);
  });
}

function wheelDetents(event, delta) {
  if (event.deltaMode === WheelEvent.DOM_DELTA_LINE) return delta * WHEEL_DETENTS_PER_LINE;
  if (event.deltaMode === WheelEvent.DOM_DELTA_PAGE) return delta * WHEEL_DETENTS_PER_PAGE;
  return delta / WHEEL_PX_PER_DETENT;
}

function setButtons(buttons) {
  if (buttons === app.buttons) return;
  app.buttons = buttons;
  sendFrame(OP_MOUSE_BUTTONS, buttons);
}

function buttonBit(button) {
  return [1, 4, 2][button] || 0; // DOM left/middle/right → HID bits 0/2/1
}

function chordUsages(chord) {
  return chord.split("+").map((part) => {
    const code = CHORD_ALIASES[part] || part;
    const usage = app.keyCodes[code] ?? app.keyCodes[code.toUpperCase()];
    if (usage === undefined) throw new Error(`unknown key ${part}`);
    return usage;
  });
}

function tapChord(chord) {
  const usages = chordUsages(chord);
  for (const usage of usages) sendFrame(OP_KEY_DOWN, usage);
  for (const usage of usages.reverse()) sendFrame(OP_KEY_UP, usage);
}

// ---- Capture ----------------------------------------------------------

function onKeyDown(event) {
  if (!holdsLease()) return;
  const usage = app.keyCodes[event.code];
  if (usage === undefined) return;
  event.preventDefault();
  if (event.repeat || app.pressed.has(usage)) return; // the host repeats held keys itself
  app.pressed.add(usage);
  sendFrame(OP_KEY_DOWN, usage);
}

function onKeyUp(event) {
  const usage = app.keyCodes[event.code];
  if (usage === undefined) return;
  event.preventDefault();
  if (app.pressed.delete(usage)) sendFrame(OP_KEY_UP, usage);
  // macOS browsers drop keyup for keys pressed while Cmd is down.
  if (event.code === "MetaLeft" || event.code === "MetaRight") {
    for (const held of [...app.pressed]) {
      if (held < MODIFIER_USAGE_MIN) {
        app.pressed.delete(held);
        sendFrame(OP_KEY_UP, held);
      }
    }
  }
}

function bindCapture() {
  const pad = $("pad");
  pad.addEventListener("keydown", onKeyDown);
  pad.addEventListener("keyup", onKeyUp);
  pad.addEventListener("focus", () => pad.classList.add("active"));
  pad.addEventListener("blur", () => {
    pad.classList.remove("active");
    releaseAll();
  });
  pad.addEventListener("contextmenu", (event) => event.preventDefault());

  pad.addEventListener("click", () => {
    if (!holdsLease() || app.pointerLocked || app.drag) return;
    pad.focus();
    if (pad.requestPointerLock && !app.pointerLockFailed) {
      try {
        const result = pad.requestPointerLock();
        if (result && result.catch) result.catch(() => { app.pointerLockFailed = true; });
      } catch {
        app.pointerLockFailed = true;
      }
    }
  });
  document.addEventListener("pointerlockchange", () => {
    app.pointerLocked = document.pointerLockElement === pad;
    if (!app.pointerLocked) releaseAll();
    render();
  });
  document.addEventListener("pointerlockerror", () => {
    app.pointerLockFailed = true;
    log("Pointer lock unavailable: drag inside the box to move the mouse, tap to click.");
  });
  document.addEventListener("mousemove", (event) => {
    if (app.pointerLocked) queueMove(event.movementX, event.movementY);
  });
  document.addEventListener("mousedown", (event) => {
    if (app.pointerLocked) setButtons(app.buttons | buttonBit(event.button));
  });
  document.addEventListener("mouseup", (event) => {
    if (app.pointerLocked) setButtons(app.buttons & ~buttonBit(event.button));
  });

  // Wheel (pointer locked or not): deltaY > 0 scrolls down, deltaX > 0 right.
  pad.addEventListener("wheel", (event) => {
    if (!holdsLease()) return;
    event.preventDefault();
    queueScroll(-wheelDetents(event, event.deltaY), wheelDetents(event, event.deltaX));
  }, { passive: false });

  // Drag fallback (touch, or no pointer lock): drag moves, a short tap clicks,
  // two touch fingers scroll (fingers down scroll down, like the phone's pad).
  pad.addEventListener("pointerdown", (event) => {
    if (!holdsLease() || app.pointerLocked || (!app.pointerLockFailed && event.pointerType === "mouse")) return;
    pad.setPointerCapture(event.pointerId);
    if (event.pointerType === "touch") app.touches.set(event.pointerId, { x: event.clientX, y: event.clientY });
    if (app.touches.size >= 2) {
      app.drag = null; // a scroll is neither a move nor a click
      return;
    }
    app.drag = { id: event.pointerId, x: event.clientX, y: event.clientY, startX: event.clientX, startY: event.clientY, at: performance.now() };
  });
  pad.addEventListener("pointermove", (event) => {
    const touch = app.touches.get(event.pointerId);
    if (touch && app.touches.size === 2) {
      // Each finger's move shifts the centroid by half of it.
      const dx = (event.clientX - touch.x) / 2, dy = (event.clientY - touch.y) / 2;
      queueScroll(-dy / TOUCH_SCROLL_PX_PER_DETENT, dx / TOUCH_SCROLL_PX_PER_DETENT);
    }
    if (touch) {
      touch.x = event.clientX;
      touch.y = event.clientY;
    }
    if (!app.drag || event.pointerId !== app.drag.id) return;
    queueMove(event.clientX - app.drag.x, event.clientY - app.drag.y);
    app.drag.x = event.clientX;
    app.drag.y = event.clientY;
  });
  const endDrag = (event) => {
    app.touches.delete(event.pointerId);
    if (!app.drag || event.pointerId !== app.drag.id) return;
    const moved = Math.hypot(event.clientX - app.drag.startX, event.clientY - app.drag.startY);
    if (moved <= TAP_MOVE_PX_MAX && performance.now() - app.drag.at <= TAP_DURATION_MS_MAX) clickButton(1);
    app.drag = null;
  };
  pad.addEventListener("pointerup", endDrag);
  pad.addEventListener("pointercancel", (event) => {
    app.touches.delete(event.pointerId);
    app.drag = null;
  });

  window.addEventListener("blur", () => releaseAll());
  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState !== "visible") releaseAll();
  });
  window.addEventListener("pagehide", () => releaseAll());
}

function clickButton(bit) {
  setButtons(app.buttons | bit);
  setButtons(app.buttons & ~bit);
}

// ---- Controls ---------------------------------------------------------

function buildStaticControls() {
  // F1–F12 always; F13–F24 in the "More keys" overflow.
  for (let number = 1; number <= FUNCTION_KEY_COUNT_MAX; number++) {
    const key = document.createElement("button");
    key.type = "button";
    key.className = "needs-lease";
    key.dataset.chord = `F${number}`;
    key.textContent = `F${number}`;
    $(number <= FUNCTION_KEY_COUNT_VISIBLE ? "fkeys" : "fkeys-extra").append(key);
  }
  $("boot-vendor").replaceChildren(...BOOT_VENDORS.map(([value, label]) => option(value, label)));
}

function bindControls() {
  $("pair-form").addEventListener("submit", pair);
  $("lease-take").addEventListener("click", () => act("Take control", "lease.take"));
  $("lease-takeover").addEventListener("click", () => {
    log("Asked the current controller to hand over…");
    act("Take over", "lease.takeover", { holderSession: app.lease.holder });
  });
  $("takeover-allow").addEventListener("click", () => answerTakeover(true));
  $("takeover-decline").addEventListener("click", () => answerTakeover(false));
  $("lease-release").addEventListener("click", () => {
    releaseAll();
    act("Release", "lease.release");
  });

  $("expose-form").addEventListener("submit", (event) => {
    event.preventDefault();
    const state = app.state || {};
    // Keep every existing slot as it is; the list must stay 0..n-1.
    const slots = (state.slots || [])
      .map((slot) => ({ lun: slot.lun, imageId: slot.imageId, mode: slot.mode }))
      .sort((a, b) => a.lun - b.lun);
    const choice = $("expose-lun").value;
    const wanted = { imageId: $("expose-image").value, mode: $("expose-mode").value };
    let lun;
    if (choice === "new") {
      lun = slots.length;
      if (lun >= LUN_COUNT_MAX) {
        log(`All ${LUN_COUNT_MAX} LUNs are in use; pick one to replace.`);
        return;
      }
      slots.push({ lun, ...wanted });
    } else {
      lun = Number(choice);
      const index = slots.findIndex((slot) => slot.lun === lun);
      if (choice === "" || index < 0) {
        log("Pick a LUN to replace.");
        return;
      }
      slots[index] = { lun, ...wanted };
    }
    act(`Expose on LUN ${lun}`, "slots.apply", { slots, ifRev: state.rev });
  });
  $("expose-image").addEventListener("change", applyDefaultMode);

  $("tether-on").addEventListener("click", () => setTether(true));
  $("tether-off").addEventListener("click", () => {
    const warning = "Turn USB tethering off? If this page reaches the phone over that USB link, it disconnects.";
    if (window.confirm(warning)) setTether(false);
  });
  $("catalog-category").addEventListener("change", listCatalog);
  $("catalog-arch").addEventListener("change", listCatalog);

  $("hid-apply").addEventListener("click", () =>
    act("Keyboard & mouse", "hid.set", { keyboard: $("hid-keyboard").checked, mouse: $("hid-mouse").checked }));

  for (const container of [$("toolbar"), $("fkeys"), $("fkeys-extra")]) {
    container.addEventListener("click", (event) => {
      const chord = event.target.dataset?.chord;
      if (!chord) return;
      try {
        tapChord(chord);
      } catch (error) {
        log(error.message);
      }
    });
  }
  $("click-left").addEventListener("click", () => clickButton(1));
  $("click-right").addEventListener("click", () => clickButton(2));
  $("release-all").addEventListener("click", () => releaseAll("button"));

  const bootRun = (action) => act(`Boot keys (${action})`, "bootkey.run", { vendor: $("boot-vendor").value, action });
  $("boot-menu").addEventListener("click", () => {
    log("Sending boot-menu keys; power the target on now.");
    bootRun("menu");
  });
  $("boot-setup").addEventListener("click", () => {
    log("Sending setup keys; power the target on now.");
    bootRun("setup");
  });
  $("boot-cancel").addEventListener("click", () => act("Stop boot keys", "bootkey.cancel"));

  $("paste-prepare").addEventListener("click", async () => {
    const text = $("paste-text").value;
    if (!text) return;
    // The phone's limit is 64 KiB of UTF-8, and the whole request must fit
    // one 64 KiB message (request() checks that too, after JSON escaping).
    const bytes = utf8Length(text);
    if (bytes > PASTE_TEXT_BYTES_MAX) {
      log(`Prepare paste: the text is ${bytes} bytes of UTF-8; the limit is ${PASTE_TEXT_BYTES_MAX}. Shorten it.`);
      return;
    }
    const plan = await act("Prepare paste", "paste.prepare", { text });
    if (!plan) return;
    app.pastePlanId = plan.planId;
    $("paste-plan").textContent = describePastePlan(text, plan);
    $("paste-run").hidden = false;
    $("paste-cancel").hidden = false;
  });
  $("paste-run").addEventListener("click", async () => {
    $("paste-run").hidden = true;
    log("Typing…");
    await act("Paste", "paste.run", { planId: app.pastePlanId });
    app.pastePlanId = null;
    $("paste-cancel").hidden = true;
    $("paste-plan").textContent = "";
  });
  $("paste-cancel").addEventListener("click", () => {
    act("Cancel paste", "paste.cancel");
    app.pastePlanId = null;
    $("paste-run").hidden = $("paste-cancel").hidden = true;
    $("paste-plan").textContent = "";
  });

  bindCapture();
}

start();
