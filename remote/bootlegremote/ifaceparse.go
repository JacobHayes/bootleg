// Adapted from tailscale-android libtailscale/ifaceparse/ifaceparse.go at
// commit 6d63d3e29a1d1f9710f78863a91cc76b39ea2f9a
// (https://github.com/tailscale/tailscale-android).
// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
//
// Android denies app UIDs NETLINK_ROUTE, so Go's net.Interfaces fails and
// tsnet's netmon cannot start (tailscale/tailscale#17311). The app enumerates
// interfaces with java.net.NetworkInterface (which goes through netd) and
// hands them to Go as JSON; this file turns that JSON into netmon.Interfaces.

package bootlegremote

import (
	"encoding/json"
	"fmt"
	"net"
	"net/netip"
	"slices"
	"strings"

	"tailscale.com/net/netmon"
)

// interfaceCountMax bounds the parsed payload; a phone has a few dozen at most.
const interfaceCountMax = 256

type addressJSON struct {
	IP        string `json:"ip"`
	PrefixLen int    `json:"prefixLen"`
}

type interfaceJSON struct {
	Name         string        `json:"name"`
	Index        int           `json:"index"`
	MTU          int           `json:"mtu"`
	Up           bool          `json:"up"`
	Broadcast    bool          `json:"broadcast"`
	Loopback     bool          `json:"loopback"`
	PointToPoint bool          `json:"pointToPoint"`
	Multicast    bool          `json:"multicast"`
	Addresses    []addressJSON `json:"addrs"`
}

// parseInterfacesJSON parses the payload produced by Callbacks.Interfaces.
// Entries without a name and unparsable addresses are skipped, not fatal:
// one odd interface must not take the whole node offline.
func parseInterfacesJSON(payload string) ([]netmon.Interface, error) {
	trimmed := strings.TrimSpace(payload)
	if trimmed == "" {
		return nil, nil
	}
	var parsed []interfaceJSON
	if err := json.Unmarshal([]byte(trimmed), &parsed); err != nil {
		return nil, fmt.Errorf("interfaces JSON: %w", err)
	}
	if len(parsed) > interfaceCountMax {
		return nil, fmt.Errorf("interfaces JSON: %d interfaces exceeds max %d", len(parsed), interfaceCountMax)
	}
	interfaces := make([]netmon.Interface, 0, len(parsed))
	for _, entry := range parsed {
		if entry.Name == "" {
			continue
		}
		netInterface := &net.Interface{Name: entry.Name, Index: entry.Index, MTU: entry.MTU}
		if entry.Up {
			netInterface.Flags |= net.FlagUp | net.FlagRunning
		}
		if entry.Broadcast {
			netInterface.Flags |= net.FlagBroadcast
		}
		if entry.Loopback {
			netInterface.Flags |= net.FlagLoopback
		}
		if entry.PointToPoint {
			netInterface.Flags |= net.FlagPointToPoint
		}
		if entry.Multicast {
			netInterface.Flags |= net.FlagMulticast
		}
		addresses := []net.Addr{}
		for _, address := range entry.Addresses {
			if netAddress, ok := address.netAddr(); ok {
				addresses = append(addresses, netAddress)
			}
		}
		interfaces = append(interfaces, netmon.Interface{Interface: netInterface, AltAddrs: addresses})
	}
	return interfaces, nil
}

func (a addressJSON) netAddr() (net.Addr, bool) {
	parsed, err := netip.ParseAddr(a.IP)
	if err != nil {
		return nil, false
	}
	ip := net.IP(slices.Clone(parsed.AsSlice()))
	// Zoned (link-local IPv6) addresses can't be represented as *net.IPNet.
	if zone := parsed.Zone(); zone != "" {
		return &net.IPAddr{IP: ip, Zone: zone}, true
	}
	bits := parsed.BitLen()
	if a.PrefixLen < 0 || a.PrefixLen > bits {
		return &net.IPAddr{IP: ip}, true
	}
	return &net.IPNet{IP: ip, Mask: net.CIDRMask(a.PrefixLen, bits)}, true
}
