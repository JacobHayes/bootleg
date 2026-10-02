// hidcapture: records input values from one USB HID device (the phone's Bootleg keyboard + mouse)
// for a fixed time and prints them as JSON lines, for the L4 HID check.
//
// Usage: hidcapture [--vendor 0x18d1] [--product 0x4ee7] [--seconds 6]
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
//   {"status":"ready","opened":[...],"refused":{kind: "0x..."}}  send input after this
//   {"ts":T,"page":P,"usage":U,"value":V}   each input value; T is the event's host time, so
//                                           values from one report share it
//   {"status":"done","values":N}            at the end
// Exit codes: 0 done, 2 bad arguments, 3 skip (no permission, or every device seized), 4 no matching device.

import Foundation
import IOKit.hid

let exitBadUsage: Int32 = 2
let exitNoPermission: Int32 = 3
let exitNoDevice: Int32 = 4
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
var arguments = CommandLine.arguments.dropFirst()
while let flag = arguments.popFirst() {
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

// The manager only enumerates; each device is opened on its own so a refusal is attributable.
let manager = IOHIDManagerCreate(kCFAllocatorDefault, IOOptionBits(kIOHIDOptionsTypeNone))
IOHIDManagerSetDeviceMatching(manager, [kIOHIDVendorIDKey: vendorId, kIOHIDProductIDKey: productId] as CFDictionary)
IOHIDManagerScheduleWithRunLoop(manager, CFRunLoopGetCurrent(), CFRunLoopMode.defaultMode.rawValue)
var devices: [IOHIDDevice] = []
let deviceDeadline = Date().addingTimeInterval(deviceWaitSeconds)
while Date() < deviceDeadline {
    CFRunLoopRunInMode(.defaultMode, 0.1, false)
    devices = Array((IOHIDManagerCopyDevices(manager) as? Set<IOHIDDevice>) ?? [])
    if devices.count >= expectedDeviceCount { break }
}

var valueCount = 0
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
guard !devices.isEmpty else { fail(exitNoDevice, String(format: "no HID device %04x:%04x", vendorId, productId)) }
guard !opened.isEmpty else {
    emit(["status": "skip", "reason": "every matching device is seized by another app (refused: \(refused))"])
    exit(exitNoPermission)
}
emit(["status": "ready", "opened": openedKinds.sorted(), "refused": refused])

let captureEnd = Date().addingTimeInterval(seconds)
while Date() < captureEnd {
    CFRunLoopRunInMode(.defaultMode, captureEnd.timeIntervalSinceNow, false)
}
opened.forEach { IOHIDDeviceClose($0, IOOptionBits(kIOHIDOptionsTypeNone)) }
emit(["status": "done", "values": valueCount])
