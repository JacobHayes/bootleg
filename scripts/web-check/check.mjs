// L4 web check: drives the phone's real web UI in a real browser (Chrome via playwright-core) over
// the tailnet. Run by scripts/check-device-web.sh, which holds the device lock, installs
// playwright-core into build/web-check and cleans the phone up afterwards whatever happens.
//
// Only harmless input ever reaches the Mac: the F24 button in the page's "More keys" overflow.
// The paste plan is prepared and cancelled, never run; nothing is typed into the capture area and
// no mouse button is ever sent.
import { execFileSync } from "node:child_process";
import { existsSync, mkdirSync } from "node:fs";
import http from "node:http";
import { createRequire } from "node:module";
import path from "node:path";

const env = (name) => {
  const value = process.env[name];
  if (!value) throw new Error(`missing env ${name}`);
  return value;
};
const WIFI_SERIAL = env("BOOTLEG_WIFI_SERIAL");
const MAC_NODE = env("BOOTLEG_MAC_NODE");
const HOST = env("BOOTLEG_HOST");
const IMAGE_NAME = env("BOOTLEG_IMAGE_NAME");
const VOLUME = env("BOOTLEG_VOLUME");
const VENDOR_ID = env("BOOTLEG_VENDOR_ID");
const SHOTS_DIR = env("BOOTLEG_SHOTS_DIR");
const MODULES_DIR = env("BOOTLEG_WEB_MODULES");
const HEADFUL = process.env.BOOTLEG_WEB_HEADFUL === "1";

const PROVIDER = "content://dev.jacobhayes.bootleg.debug";
const URL_ROOT = `http://${HOST}/`;
const POLL_MS = 200;
const UI_WAIT_MS = 10000;
const DEVICE_WAIT_MS = 20000;
const VIEWPORT = { width: 1100, height: 900 };
// "é" isn't on the US layout: the plan must name it instead of printing raw objects.
const PASTE_TEXT = "hello é";

const { chromium } = createRequire(path.join(MODULES_DIR, "package.json"))("playwright-core");

const results = [];
const startedMs = Date.now();
let currentStep = "setup";

function fail(message) {
  throw new Error(`[${currentStep}] ${message}`);
}

async function step(name, body) {
  currentStep = name;
  const started = Date.now();
  await body();
  const line = `  ok   ${((Date.now() - started) / 1000).toFixed(1).padStart(5)}s  ${name}`;
  results.push(line);
  console.log(line);
}

// ---- Phone and Mac ----------------------------------------------------------------------------

function adb(...args) {
  return execFileSync("adb", ["-s", WIFI_SERIAL, ...args], { encoding: "utf8" });
}

// The debug provider's JSON reply; throws on an error reply.
function call(method, ...extras) {
  const args = ["shell", "content", "call", "--uri", PROVIDER, "--method", method];
  for (const extra of extras) args.push("--extra", extra);
  const output = adb(...args);
  const json = output.replace(/^[^]*?json=/, "").replace(/}]\s*$/, "");
  let parsed;
  try {
    parsed = JSON.parse(json);
  } catch {
    return output;
  }
  if (parsed && typeof parsed === "object" && !Array.isArray(parsed) && parsed.error) fail(`${method}: ${parsed.error}`);
  return parsed;
}

const remoteState = () => call("REMOTE_STATE");
const hidState = () => call("HID_STATE");

function volumeMounted() {
  return execFileSync("mount", { encoding: "utf8" }).includes(` on /Volumes/${VOLUME} (`);
}

function volumeWritable() {
  const plist = execFileSync("diskutil", ["info", "-plist", `/Volumes/${VOLUME}`]);
  return execFileSync("plutil", ["-extract", "WritableMedia", "raw", "-o", "-", "-"], { input: plist, encoding: "utf8" }).trim();
}

// Bootleg's keyboard/mouse as the Mac's HID manager lists them (USB, generic desktop page).
function hostHidCount() {
  const rows = execFileSync("hidutil", ["list"], { encoding: "utf8" }).split("\n");
  return rows.filter((row) => {
    const fields = row.trim().split(/\s+/);
    return fields[0]?.toLowerCase() === VENDOR_ID && fields[3] === "1" && (fields[4] === "6" || fields[4] === "2") && fields[6] === "USB";
  }).length;
}

async function until(what, predicate, timeoutMs = DEVICE_WAIT_MS) {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const value = await predicate();
    if (value) return value;
    if (Date.now() >= deadline) fail(`timed out after ${timeoutMs} ms waiting for ${what}`);
    await new Promise((resolve) => setTimeout(resolve, POLL_MS));
  }
}

// ---- Browser ----------------------------------------------------------------------------------

const problems = [];

async function openPage(context, label) {
  const page = await context.newPage();
  page.on("console", (message) => {
    if (message.type() === "error" || message.type() === "warning") problems.push(`${label} console ${message.type()}: ${message.text()}`);
  });
  page.on("pageerror", (error) => problems.push(`${label} page error: ${error.message}`));
  page.on("requestfailed", (request) => {
    // The socket closing on revoke is expected; anything else is not.
    if (!request.url().endsWith("/ws")) problems.push(`${label} request failed: ${request.url()} ${request.failure()?.errorText}`);
  });
  return page;
}

let shotIndex = 0;
async function shot(page, name) {
  shotIndex += 1;
  const file = path.join(SHOTS_DIR, `${String(shotIndex).padStart(2, "0")}-${name}.png`);
  await page.screenshot({ path: file, fullPage: true });
}

async function textOf(page, selector) {
  return (await page.locator(selector).textContent()) ?? "";
}

async function waitText(page, selector, pattern, timeoutMs = UI_WAIT_MS) {
  await until(`${selector} to match ${pattern}`, async () => pattern.test(await textOf(page, selector)), timeoutMs);
}

function assertNoProblems() {
  if (problems.length) fail(`browser problems:\n    ${problems.join("\n    ")}`);
}

// ---- Steps ------------------------------------------------------------------------------------

async function checkHeaders() {
  const wantCsp = `default-src 'self'; connect-src 'self' ws://${HOST}; frame-ancestors 'none'`;
  const types = { "": "text/html", "app.js": "javascript", "style.css": "text/css", "keycodes.json": "application/json", "api/transport": "application/json" };
  for (const [file, type] of Object.entries(types)) {
    const response = await fetch(URL_ROOT + file, { redirect: "manual" });
    if (response.status !== 200) fail(`GET /${file} → ${response.status}`);
    const headers = response.headers;
    const expect = (name, value) => {
      if (headers.get(name) !== value) fail(`GET /${file}: ${name} is ${JSON.stringify(headers.get(name))}, want ${JSON.stringify(value)}`);
    };
    expect("content-security-policy", wantCsp);
    expect("x-frame-options", "DENY");
    expect("x-content-type-options", "nosniff");
    expect("referrer-policy", "no-referrer");
    expect("cache-control", "no-store");
    if (headers.get("access-control-allow-origin") !== null) fail(`GET /${file} sends CORS headers`);
    if (!(headers.get("content-type") ?? "").includes(type)) fail(`GET /${file}: content-type ${headers.get("content-type")}, want ${type}`);
    await response.arrayBuffer();
  }
  const transport = await (await fetch(URL_ROOT + "api/transport")).json();
  if (transport.transport !== "tailnet") fail(`api/transport says ${JSON.stringify(transport)}`);
  // A WebSocket from a foreign Origin must be refused before any upgrade.
  // (fetch can't send Upgrade headers, so plain http.)
  const foreignStatus = await new Promise((resolve, reject) => {
    const request = http.get(URL_ROOT + "ws", {
      headers: { Origin: "http://evil.example", Connection: "Upgrade", Upgrade: "websocket", "Sec-WebSocket-Version": "13", "Sec-WebSocket-Key": "dGhlIHNhbXBsZSBub25jZQ==" },
      timeout: UI_WAIT_MS,
    });
    request.on("response", (response) => {
      response.resume();
      resolve(response.statusCode);
    });
    request.on("upgrade", (_response, socket) => {
      socket.destroy();
      resolve(101);
    });
    request.on("timeout", () => request.destroy(new Error("timed out")));
    request.on("error", reject);
  });
  if (foreignStatus !== 403) fail(`a cross-origin /ws upgrade got ${foreignStatus}, want 403`);
}

async function pendingThenApproved(page) {
  await page.goto(URL_ROOT);
  await waitText(page, "#banner", /Waiting for approval/);
  if (await page.locator("#main").isVisible()) fail("controls are visible before approval");
  await until("this Mac to be listed as pending", () => remoteState().pending.some((entry) => entry.nodeId === MAC_NODE), UI_WAIT_MS);
  await shot(page, "pending");
  call("REMOTE_APPROVE", `node:s:${MAC_NODE}`);
  // The page must switch on the auth push, without a reload.
  await waitText(page, "#banner", /Connected\. Nobody has control/);
  await page.locator("#main").waitFor({ state: "visible", timeout: UI_WAIT_MS });
  await shot(page, "approved");
}

async function takeLease(page) {
  await page.click("#lease-take");
  await waitText(page, "#lease-text", /You have control/);
  if (!String(remoteState().lease).startsWith("session:")) fail(`the phone doesn't show a remote lease: ${remoteState().lease}`);
  if (await page.locator("#lease-release").isDisabled()) fail("Release is disabled while holding the lease");
}

async function imagesByName(page) {
  const names = await page.locator("#expose-image option").allTextContents();
  if (!names.includes(IMAGE_NAME)) fail(`the image picker lists ${JSON.stringify(names)}, not ${IMAGE_NAME}`);
  if (names.some((name) => /^[0-9a-f]{16}$/.test(name) || name.includes("/"))) fail(`the image picker shows ids or paths: ${JSON.stringify(names)}`);
}

async function exposeReadOnly(page) {
  if (volumeMounted()) fail(`/Volumes/${VOLUME} is already mounted`);
  await page.selectOption("#expose-image", { label: IMAGE_NAME });
  await page.selectOption("#expose-mode", "disk_ro");
  const lun = await page.locator("#expose-lun").inputValue();
  if (lun !== "new") fail(`the LUN picker defaults to ${lun}, want new`);
  await page.click("#expose-form button[type=submit]");
  const row = page.locator("#slots tbody tr").first();
  await until("the slot row", async () => (await row.count()) > 0 && (await row.textContent()).includes(IMAGE_NAME), UI_WAIT_MS);
  await until(`/Volumes/${VOLUME} to mount`, volumeMounted);
  if (volumeWritable() !== "false") fail(`/Volumes/${VOLUME} is writable`);
  const cells = await row.locator("td").allTextContents();
  if (cells[0] !== "0" || cells[1] !== IMAGE_NAME || cells[2] !== "Read-only" || cells[3] !== "Inserted") fail(`slot row reads ${JSON.stringify(cells)}`);
  await shot(page, "exposed");
}

// The partition behind the mounted volume ("disk7s1"): it goes away with the medium (the whole
// disk stays, empty, like a card reader).
function volumePartition() {
  const plist = execFileSync("diskutil", ["info", "-plist", `/Volumes/${VOLUME}`]);
  return execFileSync("plutil", ["-extract", "DeviceIdentifier", "raw", "-o", "-", "-"], { input: plist, encoding: "utf8" }).trim();
}

// The device node, not `diskutil info`: diskutil can block for minutes while the medium goes.
function partitionPresent(partition) {
  return existsSync(`/dev/${partition}`);
}

async function ejectFromPage(page) {
  // The Mac unmounts first (a mounted volume is never pulled), then the page ejects the medium.
  const partition = volumePartition();
  execFileSync("diskutil", ["unmount", `/Volumes/${VOLUME}`], { stdio: "ignore" });
  await until(`/Volumes/${VOLUME} to unmount`, () => !volumeMounted(), 5000);
  const row = page.locator("#slots tbody tr").first();
  if (!/Inserted/.test(await row.textContent())) fail(`the slot doesn't read Inserted: ${await row.textContent()}`);
  // macOS keeps the medium locked after an unmount: the plain eject is refused and the row
  // offers a forced one.
  await row.getByRole("button", { name: "Eject", exact: true }).click();
  await row.getByRole("button", { name: "Force eject" }).waitFor({ timeout: UI_WAIT_MS });
  await shot(page, "force-eject-offered");
  await row.getByRole("button", { name: "Force eject" }).click();
  await until("the slot to read Ejected", async () => (await row.locator("td").nth(3).textContent()) === "Ejected", UI_WAIT_MS);
  await row.getByRole("button", { name: "Reinsert" }).waitFor({ timeout: UI_WAIT_MS });
  // The phone's read-back is the proof: LUN 0 has no backing file any more. The Mac may keep
  // /dev/<partition> around for a while (it did for > 5 s on macOS 26), so that's only reported.
  await until("the phone's LUN 0 to have no medium", () => (call("STATE").luns?.["0"]?.file ?? "") === "", 5000);
  if (partitionPresent(partition)) console.log(`    note: /dev/${partition} is still present after the forced eject`);
  await shot(page, "ejected");
  if (volumeMounted()) fail("the volume came back after the eject");
}

async function hidOnF24Off(page) {
  if (hostHidCount() !== 0) fail(`a ${VENDOR_ID} keyboard/mouse is already enumerated`);
  await page.check("#hid-keyboard");
  await page.check("#hid-mouse");
  await page.click("#hid-apply");
  await until("the Mac to enumerate the keyboard and mouse", () => hostHidCount() >= 2);
  await until("the page to show HID on", async () => (await page.locator("#hid-keyboard").isChecked()) && (await page.locator("#hid-mouse").isChecked()), UI_WAIT_MS);
  await until("the phone's HID session to open", () => /open/i.test(hidState().link), 5000);
  const before = hidState().written;
  await page.locator("#more-keys summary").click();
  await shot(page, "hid-on-more-keys");
  await page.locator('#more-keys button[data-chord="F24"]').click();
  await until("the F24 down/up reports", () => hidState().written - before >= 2, 5000);
}

// The phone only writes a release report when something is held, so F24 (the one key allowed into
// the capture area) is held down while another tab comes to the front.
// Playwright's keyboard stops at F12, so F24 goes in as a raw DevTools key event.
const F24_KEY = { code: "F24", key: "F24", windowsVirtualKeyCode: 0x87, nativeVirtualKeyCode: 0x87 };

async function releaseOnBlur(context, page) {
  const devtools = await context.newCDPSession(page);
  // Playwright emulates focus, so every page counts as focused and a tab switch never fires blur or
  // visibilitychange. With emulation off this page sees real focus changes.
  await devtools.send("Emulation.setFocusEmulationEnabled", { enabled: false });
  await page.bringToFront();
  await page.focus("#pad");
  const before = hidState().written;
  await devtools.send("Input.dispatchKeyEvent", { type: "rawKeyDown", ...F24_KEY });
  await until("the F24 down report", () => hidState().written - before >= 1, 5000);
  const held = hidState().written;
  const other = await context.newPage();
  try {
    await other.goto("about:blank");
    await other.bringToFront();
    await until("a release report after switching tabs", () => hidState().written > held, 5000);
  } finally {
    await other.close();
    await page.bringToFront();
    // If the tab switch didn't release F24, don't leave it held on the Mac.
    if (hidState().written === held) await page.click("#release-all");
  }
  const settled = hidState().written;
  // The page forgot the key on release, so the late key-up sends nothing.
  await devtools.send("Input.dispatchKeyEvent", { type: "keyUp", ...F24_KEY });
  await new Promise((resolve) => setTimeout(resolve, 300));
  if (hidState().written !== settled) fail("the key-up after the release wrote another report");
}

async function hidOff(page) {
  await page.uncheck("#hid-keyboard");
  await page.uncheck("#hid-mouse");
  await page.click("#hid-apply");
  await until("the Mac to drop the keyboard and mouse", () => hostHidCount() === 0);
  await until("the page to show HID off", async () => !(await page.locator("#hid-keyboard").isChecked()), UI_WAIT_MS);
}

async function pastePrepareCancel(page) {
  const writtenBefore = hidState().written;
  await page.fill("#paste-text", PASTE_TEXT);
  await page.click("#paste-prepare");
  await page.locator("#paste-run").waitFor({ state: "visible", timeout: UI_WAIT_MS });
  const plan = await textOf(page, "#paste-plan");
  if (!plan.includes(`${[...PASTE_TEXT].length} characters`) || !plan.includes("Skips 1") || !plan.includes("é (U+00E9)") || /object/i.test(plan)) fail(`the paste plan reads ${JSON.stringify(plan)}`);
  await shot(page, "paste-plan");
  await page.click("#paste-cancel");
  await page.locator("#paste-run").waitFor({ state: "hidden", timeout: UI_WAIT_MS });
  if ((await textOf(page, "#paste-plan")) !== "") fail("the paste plan is still shown after Cancel");
  await new Promise((resolve) => setTimeout(resolve, 500));
  if (hidState().written !== writtenBefore) fail("a report was written although the paste was cancelled");
}

async function takeover(first, second) {
  await second.goto(URL_ROOT);
  await waitText(second, "#lease-text", /Session \d+ .*has control/);
  const holderBefore = remoteState().lease;

  await second.click("#lease-takeover");
  await first.locator("#takeover").waitFor({ state: "visible", timeout: UI_WAIT_MS });
  if (!/asks to take control/.test(await textOf(first, "#takeover-text"))) fail("the takeover prompt has no question");
  await shot(first, "takeover-prompt");
  await first.click("#takeover-decline");
  await waitText(second, "#log", /declined/i);
  await first.locator("#takeover").waitFor({ state: "hidden", timeout: UI_WAIT_MS });
  if (remoteState().lease !== holderBefore) fail(`the lease moved on a decline: ${remoteState().lease}`);
  await waitText(first, "#lease-text", /You have control/);
  await shot(second, "takeover-declined");

  await second.click("#lease-takeover");
  await first.locator("#takeover").waitFor({ state: "visible", timeout: UI_WAIT_MS });
  await first.click("#takeover-allow");
  await waitText(second, "#lease-text", /You have control/);
  await waitText(first, "#lease-text", /Session \d+ .*has control/);
  if (remoteState().lease === holderBefore) fail("the lease didn't move on allow");
  await shot(second, "takeover-allowed");
}

async function revoke(first, second) {
  call("REMOTE_REVOKE", `node:s:${MAC_NODE}`);
  for (const page of [first, second]) {
    await waitText(page, "#banner", /revoked/i);
    if (await page.locator("#main").isVisible()) fail("controls are still visible after the revoke");
  }
  // A revoked page must stay revoked: no reconnect that would ask the phone for approval again.
  await new Promise((resolve) => setTimeout(resolve, 2500));
  for (const page of [first, second]) {
    if (!/revoked/i.test(await textOf(page, "#banner"))) fail(`the banner changed to ${JSON.stringify(await textOf(page, "#banner"))} after the revoke`);
  }
  const state = remoteState();
  if (state.sessions.length !== 0) fail(`sessions are still open after the revoke: ${JSON.stringify(state.sessions)}`);
  if (state.pending.some((entry) => entry.nodeId === MAC_NODE)) fail("the revoked page asked for approval again");
  await shot(first, "revoked");
}

// ---- Main -------------------------------------------------------------------------------------

mkdirSync(SHOTS_DIR, { recursive: true });
let browser;
let status = 0;
const pages = [];
try {
  await step("headers: CSP, XFO, nosniff, no CORS, foreign Origin refused", checkHeaders);
  browser = await chromium.launch({ channel: "chrome", headless: !HEADFUL });
  const contextA = await browser.newContext({ viewport: VIEWPORT });
  const contextB = await browser.newContext({ viewport: VIEWPORT });
  const first = await openPage(contextA, "A");
  const second = await openPage(contextB, "B");
  pages.push(["A", first], ["B", second]);
  await step("page pending → approve (hook) → approved without reload", () => pendingThenApproved(first));
  await step("take control", () => takeLease(first));
  await step("image picker lists library images by name", () => imagesByName(first));
  await step("expose RO from the page → Mac mounts it read-only", () => exposeReadOnly(first));
  await step("Mac unmounts, page (force) ejects → LUN empty", () => ejectFromPage(first));
  await step("HID on from the page, F24 from More keys → reports written", () => hidOnF24Off(first));
  await step("switching tabs releases all (HID_STATE)", () => releaseOnBlur(contextA, first));
  await step("HID off from the page → Mac drops it", () => hidOff(first));
  await step("paste prepare shows the plan → cancel, nothing typed", () => pastePrepareCancel(first));
  await step("takeover from a second session: decline keeps, allow moves", () => takeover(first, second));
  await step("revoke (hook) → both pages revoked, sockets closed, no re-ask", () => revoke(first, second));
  assertNoProblems();
} catch (error) {
  status = 1;
  console.error(`FAIL ${error.message}${error.cause ? ` (${error.cause.message ?? error.cause})` : ""}`);
  if (problems.length) console.error(`browser problems:\n    ${problems.join("\n    ")}`);
  for (const [label, page] of pages) {
    try {
      console.error(`page ${label}: banner ${JSON.stringify(await textOf(page, "#banner"))}, log ${JSON.stringify(await textOf(page, "#log"))}`);
      await shot(page, `failed-${label}`);
    } catch {
      // The page may be gone; the error above is what matters.
    }
  }
} finally {
  if (browser) await browser.close();
}
console.log("\nL4 web summary");
for (const line of results) console.log(line);
console.log(`${status === 0 ? "PASS" : `FAIL  at "${currentStep}" after`}  ${((Date.now() - startedMs) / 1000).toFixed(1)}s (screenshots in ${SHOTS_DIR})`);
process.exit(status);
