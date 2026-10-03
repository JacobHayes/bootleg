// hidcapture: records input values from one USB HID device (the phone's Bootleg keyboard + mouse)
// for a fixed time and prints them as JSON lines, for the L4 HID check.
//
// Usage: hidcapture [--vendor 0x18d1] [--product 0x4ee7] [--seconds 6] [--park-pointer]
//
// --describe prints each matching device's kind, MaxInputReportSize and report descriptor (hex),
// one JSON line each, and exits; it only enumerates (no open, no Input Monitoring needed), so it
// shows the layout the Mac parsed without any input being sent.
//
// --park-pointer (for the scroll check): before "ready", record the pointer's position, warp it to
// an inert spot (the middle of the main display's menu bar, away from the status items on the
// right, where scrolling does nothing) and read it back; at the end, or on SIGTERM/SIGINT, warp it
// back to the recorded absolute position (relative moves would clamp at screen edges). If the
// pointer doesn't land where it was sent, it exits 5 without "ready", so the caller never scrolls.
//
// Reading another device's input needs macOS Input Monitoring for the terminal running this.
// This tool only checks that permission (IOHIDCheckAccess); it never requests it and never opens a
// device without it, because either would show the system prompt. Without it, it prints one
// `{"status":"skip",...}` line and exits 3, and the caller falls back to enumeration checks.
//
// Devices are opened without seizing, so the input still reaches macOS (which is why only harmless
// input is ever sent). A device another app has seized (Karabiner-Elements seizes keyboards and
// re-emits them from its virtual keyboard) refuses a second opener; it is listed under "refused"
// and its input can't be captured here.
//
// Output, one JSON object per line:
//   {"status":"ready","opened":[...],"refused":{kind: "0x..."},"parked":{...}}  send input after this
//   {"ts":T,"page":P,"usage":U,"value":V}   each input value; T is the event's host time, so
//                                           values from one report share it
//   {"status":"done","values":N,"restored":{...}}  at the end ("restored" with --park-pointer)
// Exit codes: 0 done, 2 bad arguments, 3 skip (no permission, or every device seized), 4 no matching
// device, 5 the pointer could not be parked or restored.

import CoreGraphics
import Foundation
import IOKit.hid

let exitBadUsage: Int32 = 2
let exitNoPermission: Int32 = 3
let exitNoDevice: Int32 = 4
let exitPointer: Int32 = 5
let pointerTolerance = 1.0
let captureSecondsMax = 60.0
let deviceWaitSeconds = 3.0
let expectedDeviceCount = 2

func emit(_ object: [String: Any]) {
    let data = try! JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])
    FileHandle.standardOutput.write(data)
    FileHandle.standardOutput.write("\n".data(using: .utf8)!)
}

func fail(_ code: Int32, _ message: String) -> Never {
    FileHandle.standardError.write("hidcapture: \(message)\n".data(using: .utf8)!)
    exit(code)
}

func parseInt(_ text: String) -> Int? {
    text.lowercased().hasPrefix("0x") ? Int(text.dropFirst(2), radix: 16) : Int(text)
}

func intProperty(_ device: IOHIDDevice, _ key: String) -> Int {
    (IOHIDDeviceGetProperty(device, key as CFString) as? NSNumber)?.intValue ?? 0
}

func kind(_ device: IOHIDDevice) -> String {
    switch (intProperty(device, kIOHIDPrimaryUsagePageKey), intProperty(device, kIOHIDPrimaryUsageKey)) {
    case (1, 6): return "keyboard"
    case (1, 2): return "mouse"
    case let (page, usage): return "usage \(page)/\(usage)"
    }
}

var vendorId = 0x18d1
var productId = 0x4ee7
var seconds = 6.0
var parkPointer = false
var describeOnly = false
var arguments = CommandLine.arguments.dropFirst()
while let flag = arguments.popFirst() {
    if flag == "--park-pointer" {
        parkPointer = true
        continue
    }
    if flag == "--describe" {
        describeOnly = true
        continue
    }
    guard let value = arguments.popFirst() else { fail(exitBadUsage, "\(flag) needs a value") }
    switch flag {
    case "--vendor": vendorId = parseInt(value) ?? -1
    case "--product": productId = parseInt(value) ?? -1
    case "--seconds": seconds = Double(value) ?? -1
    default: fail(exitBadUsage, "unknown argument \(flag)")
    }
}
guard (0...0xffff).contains(vendorId), (0...0xffff).contains(productId) else { fail(exitBadUsage, "bad vendor/product id") }
guard seconds > 0, seconds <= captureSecondsMax else { fail(exitBadUsage, "--seconds must be in (0, \(captureSecondsMax)]") }

func matchingDevices() -> [IOHIDDevice] {
    let manager = IOHIDManagerCreate(kCFAllocatorDefault, IOOptionBits(kIOHIDOptionsTypeNone))
    IOHIDManagerSetDeviceMatching(manager, [kIOHIDVendorIDKey: vendorId, kIOHIDProductIDKey: productId] as CFDictionary)
    IOHIDManagerScheduleWithRunLoop(manager, CFRunLoopGetCurrent(), CFRunLoopMode.defaultMode.rawValue)
    var found: [IOHIDDevice] = []
    let deadline = Date().addingTimeInterval(deviceWaitSeconds)
    while Date() < deadline {
        CFRunLoopRunInMode(.defaultMode, 0.1, false)
        found = Array((IOHIDManagerCopyDevices(manager) as? Set<IOHIDDevice>) ?? [])
        if found.count >= expectedDeviceCount { break }
    }
    return found
}

if describeOnly {
    let found = matchingDevices()
    guard !found.isEmpty else { fail(exitNoDevice, String(format: "no HID device %04x:%04x", vendorId, productId)) }
    for device in found.sorted(by: { kind($0) < kind($1) }) {
        let descriptor = (IOHIDDeviceGetProperty(device, kIOHIDReportDescriptorKey as CFString) as? Data) ?? Data()
        emit([
            "kind": kind(device),
            "maxInputReportSize": intProperty(device, kIOHIDMaxInputReportSizeKey),
            "reportDescriptor": descriptor.map { String(format: "%02x", $0) }.joined(),
        ])
    }
    exit(0)
}

// Check only: IOHIDRequestAccess, or opening a device without access, would prompt the user.
let access = IOHIDCheckAccess(kIOHIDRequestTypeListenEvent)
guard access == kIOHIDAccessTypeGranted else {
    let state = access == kIOHIDAccessTypeDenied ? "denied" : "not yet decided"
    emit([
        "status": "skip",
        "reason": "Input Monitoring is \(state) for this terminal; grant it once in System Settings > Privacy & Security > Input Monitoring to enable host-side capture",
    ])
    exit(exitNoPermission)
}

var valueCount = 0
var signalSources: [DispatchSourceSignal] = []

func pointerLocation() -> CGPoint? { CGEvent(source: nil)?.location }

func near(_ a: CGPoint, _ b: CGPoint) -> Bool { abs(a.x - b.x) <= pointerTolerance && abs(a.y - b.y) <= pointerTolerance }

func pointJson(_ point: CGPoint) -> [String: Any] { ["x": Double(point.x), "y": Double(point.y)] }

/** Warps the pointer and confirms it landed there. */
func warp(to point: CGPoint) -> Bool {
    guard CGWarpMouseCursorPosition(point) == .success else { return false }
    // Hardware moves right after a warp are briefly suppressed; reconnect them at once.
    CGAssociateMouseAndMouseCursorPosition(1)
    guard let landed = pointerLocation() else { return false }
    return near(landed, point)
}

var pointerHome: CGPoint?

/** Puts the pointer back where it was (absolute), once. */
func restorePointer() -> [String: Any]? {
    guard let home = pointerHome else { return nil }
    pointerHome = nil
    guard warp(to: home) else {
        FileHandle.standardError.write("hidcapture: could not restore the pointer to \(home)\n".data(using: .utf8)!)
        exit(exitPointer)
    }
    return pointJson(home)
}

var parked: [String: Any] = [:]
if parkPointer {
    guard let home = pointerLocation() else { fail(exitPointer, "cannot read the pointer position") }
    let display = CGDisplayBounds(CGMainDisplayID())
    // Middle of the menu bar, 2 points below the top: no app menu or status item reacts to scrolling there.
    let inert = CGPoint(x: display.midX.rounded(), y: display.minY + 2)
    pointerHome = home
    // Handlers first, so a signal at any point after the warp still puts the pointer back.
    for signalNumber in [SIGTERM, SIGINT] {
        signal(signalNumber, SIG_IGN)
        let source = DispatchSource.makeSignalSource(signal: signalNumber, queue: .main)
        source.setEventHandler {
            let restored = restorePointer()
            emit(["status": "done", "values": valueCount, "restored": restored ?? [:]])
            exit(0)
        }
        source.resume()
        signalSources.append(source)
    }
    guard warp(to: inert) else {
        _ = restorePointer()
        fail(exitPointer, "could not park the pointer at \(inert)")
    }
    parked = ["from": pointJson(home), "at": pointJson(inert)]
}

// The manager only enumerates; each device is opened on its own so a refusal is attributable.
let devices = matchingDevices()

let inputCallback: IOHIDValueCallback = { _, _, _, value in
    let element = IOHIDValueGetElement(value)
    emit([
        "ts": IOHIDValueGetTimeStamp(value),
        "page": Int(IOHIDElementGetUsagePage(element)),
        "usage": Int(IOHIDElementGetUsage(element)),
        "value": IOHIDValueGetIntegerValue(value),
    ])
    valueCount += 1
}
var opened: [IOHIDDevice] = []
var openedKinds: [String] = []
var refused: [String: String] = [:]
for device in devices {
    let result = IOHIDDeviceOpen(device, IOOptionBits(kIOHIDOptionsTypeNone))
    guard result == kIOReturnSuccess else {
        refused[kind(device)] = String(format: "0x%08x", result)
        continue
    }
    IOHIDDeviceRegisterInputValueCallback(device, inputCallback, nil)
    IOHIDDeviceScheduleWithRunLoop(device, CFRunLoopGetCurrent(), CFRunLoopMode.defaultMode.rawValue)
    opened.append(device)
    openedKinds.append(kind(device))
}
guard !devices.isEmpty else {
    _ = restorePointer()
    fail(exitNoDevice, String(format: "no HID device %04x:%04x", vendorId, productId))
}
guard !opened.isEmpty else {
    _ = restorePointer()
    emit(["status": "skip", "reason": "every matching device is seized by another app (refused: \(refused))"])
    exit(exitNoPermission)
}
emit(["status": "ready", "opened": openedKinds.sorted(), "refused": refused, "parked": parked])

let captureEnd = Date().addingTimeInterval(seconds)
while Date() < captureEnd {
    CFRunLoopRunInMode(.defaultMode, captureEnd.timeIntervalSinceNow, false)
}
opened.forEach { IOHIDDeviceClose($0, IOOptionBits(kIOHIDOptionsTypeNone)) }
emit(["status": "done", "values": valueCount, "restored": restorePointer() ?? [:]])
