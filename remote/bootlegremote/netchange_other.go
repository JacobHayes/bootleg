//go:build !android

package bootlegremote

// Only Android's netmon takes default-route hints from the app.
func updateDefaultRoute(interfaceName, gatewayIP string) {}
