//go:build linux

package main

import (
	"fmt"
	"os"
	"syscall"
	"unsafe"
)

const (
	tunSetInterface    = 0x400454ca // TUNSETIFF
	interfaceFlagTap   = 0x0002     // IFF_TAP
	interfaceFlagNoPI  = 0x1000     // IFF_NO_PI: frames without the 4-byte packet info
	interfaceRequestSz = 40         // struct ifreq on 64-bit
)

// openTap creates the TAP interface name (non-persistent: it goes away with the fd), sets its MAC
// address and brings it up. Android's tethering configures the address once asked to tether it.
func openTap(name string, mac [6]byte) (*os.File, error) {
	// A blocking fd wrapped with NewFile: os.OpenFile would make it nonblocking for the runtime
	// poller, and reads then fail with "not pollable" once the TAP is attached.
	fd, err := syscall.Open("/dev/tun", syscall.O_RDWR|syscall.O_CLOEXEC, 0)
	if err != nil {
		return nil, err
	}
	tun := os.NewFile(uintptr(fd), "/dev/tun")
	var request [interfaceRequestSz]byte
	copy(request[:16], name)
	*(*uint16)(unsafe.Pointer(&request[16])) = interfaceFlagTap | interfaceFlagNoPI
	if err := ioctl(tun.Fd(), tunSetInterface, &request); err != nil {
		tun.Close()
		return nil, fmt.Errorf("TUNSETIFF: %w", err)
	}
	socket, err := syscall.Socket(syscall.AF_INET, syscall.SOCK_DGRAM|syscall.SOCK_CLOEXEC, 0)
	if err != nil {
		tun.Close()
		return nil, err
	}
	defer syscall.Close(socket)
	// SIOCSIFHWADDR: sa_family ARPHRD_ETHER (1), then the address.
	request = [interfaceRequestSz]byte{}
	copy(request[:16], name)
	*(*uint16)(unsafe.Pointer(&request[16])) = syscall.ARPHRD_ETHER
	copy(request[18:24], mac[:])
	if err := ioctl(uintptr(socket), syscall.SIOCSIFHWADDR, &request); err != nil {
		tun.Close()
		return nil, fmt.Errorf("SIOCSIFHWADDR: %w", err)
	}
	request = [interfaceRequestSz]byte{}
	copy(request[:16], name)
	if err := ioctl(uintptr(socket), syscall.SIOCGIFFLAGS, &request); err != nil {
		tun.Close()
		return nil, fmt.Errorf("SIOCGIFFLAGS: %w", err)
	}
	*(*uint16)(unsafe.Pointer(&request[16])) |= syscall.IFF_UP
	if err := ioctl(uintptr(socket), syscall.SIOCSIFFLAGS, &request); err != nil {
		tun.Close()
		return nil, fmt.Errorf("SIOCSIFFLAGS: %w", err)
	}
	return tun, nil
}

func ioctl(fd, request uintptr, argument *[interfaceRequestSz]byte) error {
	if _, _, errno := syscall.Syscall(syscall.SYS_IOCTL, fd, request, uintptr(unsafe.Pointer(&argument[0]))); errno != 0 {
		return errno
	}
	return nil
}
