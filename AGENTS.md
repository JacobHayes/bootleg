# Bootleg — agent guide

Bootleg turns a rooted Android phone into a bootable USB drive (ISO/IMG over the configfs
mass_storage gadget), a USB keyboard/mouse, and a remote KVM-style controller.

- **What & why:** `SPEC.md` holds the requirements and decisions. Read it before you change
  behavior.
- **How:** `ARCHITECTURE.md` covers the design, device facts, gadget ownership rules and
  hard limits.

If you change a decision, update those docs in the same change.

## Toolchain

- Everything comes from `mise.toml` (tools at `latest`, pinned by `mise.lock`; Java stays on
  the 21 LTS line). Run commands through mise: `mise run <task>` or `mise exec -- <cmd>`.
- The Android SDK lives in `.android-sdk/` (git-ignored). `local.properties` must point
  `sdk.dir` at it.
- Gradle: Kotlin, AGP with built-in Kotlin, Compose. Versions are in
  `gradle/libs.versions.toml`. Add dependencies sparingly; each one is a supply-chain and
  maintenance cost.

## Verification ladder

Run the fastest rung that covers your change, then climb. All of L0–L4 should take under
3 minutes with a warm cache.

| Task | Rungs | What |
|---|---|---|
| `mise run check` | L0–L2 | ktlint, compile with warnings as errors, unit + property + deterministic gadget simulation, UI screenshot goldens (offline) |
| `mise run check:catalog` | L3 | live distro metadata/checksum/URL smoke test (network) |
| `mise run check:device` | L4 | real phone end to end, `scripts/check-device.sh` (local only, not CI; takes the device lock) |
| `mise run check:device:hid` | L4 | HID only: Mac enumerates keyboard + mouse, harmless input, off again (<30s) |
| `mise run check:device:remote` | L4 | Remote: the real `bootleg` CLI over the tailnet (pending, approve, RO mount, harmless HID, revoke) |
| `mise run check:device:web` | L4 | Remote web UI in Chrome (playwright-core, local install; manual, not in `check:all`): pending → approve, RO mount + eject, F24, release on tab switch, paste prepare/cancel, takeover, revoke; screenshots in `build/web-check/shots` |
| `mise run check:device:ui [-- TEST...]` | L4 | The app's own UI on the phone (`scripts/e2e-ui.sh`; manual, not in `check:all`): small independent tests selectable by name, area or glob (`--list`); per-test results appended to `build/e2e-ui/results.tsv`, screenshots in `build/e2e-shots/<test>` |
| `mise run check:all` | L0–L4 | `check`, then `check:catalog` in parallel with `check:device` and `check:device:remote` |
| `mise run fixtures:refresh` | | re-record catalog fixtures; review the diff before committing |
| `mise run screenshots:record` | | re-record UI goldens (`app/src/test/screenshots/`); look at the PNGs before committing |
| `mise run format` | | ktlint auto-fix |

- UI changes: re-record the screenshot goldens and look at them (they render the screens against
  `FakeBootlegBackend` with Robolectric, so no phone or unlock is needed).
- L4 never exposes unformatted media (macOS shows a modal dialog), ejects on the Mac before the
  phone drops a volume, and fails if DiskArbitration saw a mounted volume vanish.
- Tests for network code are tagged `live` and stay out of the default run. Offline tests replay
  recorded fixtures.
- A change isn't done until `mise run check` passes. Changes that touch the device run
  `check:device` too.

## Engineering principles (TigerStyle-inspired)

- **Explicit limits:** bound every loop, queue, retry, timeout, size and concurrency, and name the
  constant (`...Max`, `...Ms`).
- **Assertions:** check arguments, return values and invariants, covering both the positive
  space (what must hold) and the negative space (what must never happen). Fail loudly with a
  clear message.
- **Pure core, thin shell:** decision logic lives in `core/` (plain Kotlin, JVM-tested in
  seconds). Android, root, network and the clock sit behind interfaces with deterministic fakes.
- **Plan, apply, verify:** compute a plan purely, apply it, read the result back, and compare.
  Failures stay scoped (per slot), and recovery is bounded.
- **Naming:** descriptive, no abbreviations, units and qualifiers as suffixes (`timeoutMs`,
  `sizeBytes`, `lunCountMax`).
- **Comments** explain why, not what. Keep them sparse.
- **Simplicity:** small surface areas, few dependencies, no speculative abstractions.
- **Lean code and tests:** every line is a maintenance cost. Write the least code that meets the
  requirement and keeps it safe; no modes or options nobody asked for. Keep tests few and
  high-value: scenario and property tests that would catch real bugs. Don't write near-duplicate
  tests or tests that only restate the implementation, and delete them when you find them.

## Device rules

- Target: Pixel 6, Android 17, Magisk root, SELinux enforcing. Use wireless adb for anything
  that re-enumerates USB, because USB adb drops for a few seconds when that happens.
- Bootleg owns only USB gadget names containing `bootleg`. Never modify other functions or links
  (`ffs.adb`, `functionN`, other apps' `hid.*`). Always leave the gadget bound with ADB working.
- The phone is a shared resource. Serialize device access with the lock directory: take it with
  exactly `mkdir /private/tmp/claude-501/bootleg-device.lock` (retry while it exists), release
  it with `rmdir /private/tmp/claude-501/bootleg-device.lock`.
- Never run `magisk --sqlite` queries that can return NULL columns (e.g. `sqlite_master`):
  Magisk turns each value into a string from `sqlite3_column_text`, and a NULL crashes `magiskd`.
  Every `su` then fails until the phone reboots, and the reboot needs a manual unlock.
- The Mac is the USB host in tests. Never send real keystrokes to it. Only harmless input is
  allowed: F20–F24, Shift taps, and mouse moves that cancel out.

## Gotchas

- With JUnit 5, `@Test fun x() = runBlocking { ... }` returns a non-Unit value, so JUnit skips
  the test silently. Use `runBlocking<Unit> { ... }` or a block body.

## Workflow

- Clear, logical commits; keep docs in sync with decisions; fold fixes into the commit they fix instead of piling up "fix"
  commits.
- Avoid historical narrative. Docs describe the current design and facts, not how they came
  about (no "revision 2 after review 1", "found during X", dated spike notes). Commit messages
  describe the change itself. Don't commit test-run logs, findings lists or spike reports;
  report those instead.
- Design changes get a critique pass (Codex `gpt-6-astra`, medium effort).
- Code gets reviewed in small, focused diffs (one logical commit or component at a time), not
  one big diff per phase (Codex `gpt-6-luna`, high effort). Small diffs get faster, sharper
  reviews.
