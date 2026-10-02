package bootlegremote

import "tailscale.com/net/netmon"

func updateDefaultRoute(interfaceName, gatewayIP string) {
	netmon.UpdateLastKnownDefaultRouteInterface(interfaceName)
	netmon.UpdateLastKnownDefaultGateway(gatewayIP)
}
