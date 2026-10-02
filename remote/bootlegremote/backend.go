package bootlegremote

import (
	"context"
	"errors"
	"fmt"
	"log"
	"net"
	"strings"

	"tailscale.com/client/local"
	"tailscale.com/ipn"
	"tailscale.com/tsnet"
)

// backend is the slice of tsnet that the lifecycle and status code use. Tests
// swap in a fake through startBackend so Start/Stop ordering and the status
// watcher can be exercised without a tailnet.
type backend interface {
	Listen(address string) (net.Listener, error)
	Status(ctx context.Context) (status, error)
	WatchBus(ctx context.Context) (busWatcher, error)
	WhoIs(ctx context.Context, remoteAddress string) (peerIdentity, error)
	InjectNetworkEvent()
	Close() error
}

// busWatcher yields one IPN bus notification at a time. changed reports
// whether the notification can change the status snapshot.
type busWatcher interface {
	Next() (changed bool, errorMessage string, err error)
	Close() error
}

type peerIdentity struct {
	NodeID string
	Name   string
	User   string
	Tags   []string
}

type backendConfig struct {
	stateDir string
	hostname string
	authKey  string
	verbose  bool
}

var startBackend = startTsnetBackend

type tsnetBackend struct {
	server      *tsnet.Server
	localClient *local.Client
}

func startTsnetBackend(config backendConfig) (backend, error) {
	server := &tsnet.Server{
		Dir:       config.stateDir,
		Hostname:  config.hostname,
		AuthKey:   strings.TrimSpace(config.authKey),
		Ephemeral: false,
		UserLogf:  log.Printf,
	}
	if config.verbose {
		server.Logf = log.Printf
	}
	if err := server.Start(); err != nil {
		server.Close()
		return nil, fmt.Errorf("tsnet start: %w", err)
	}
	localClient, err := server.LocalClient()
	if err != nil {
		server.Close()
		return nil, fmt.Errorf("tsnet local client: %w", err)
	}
	return &tsnetBackend{server: server, localClient: localClient}, nil
}

func (b *tsnetBackend) Listen(address string) (net.Listener, error) {
	return b.server.Listen("tcp", address)
}

func (b *tsnetBackend) Status(ctx context.Context) (status, error) {
	tailnetStatus, err := b.localClient.StatusWithoutPeers(ctx)
	if err != nil {
		return status{}, err
	}
	result := status{
		BackendState: tailnetStatus.BackendState,
		TailscaleIPs: []string{},
		LoginURL:     tailnetStatus.AuthURL,
		Health:       tailnetStatus.Health,
	}
	for _, ip := range tailnetStatus.TailscaleIPs {
		result.TailscaleIPs = append(result.TailscaleIPs, ip.String())
	}
	if tailnetStatus.Self != nil {
		result.DNSName = tailnetStatus.Self.DNSName
	}
	if tailnetStatus.CurrentTailnet != nil {
		result.MagicDNSSuffix = tailnetStatus.CurrentTailnet.MagicDNSSuffix
	}
	return result, nil
}

func (b *tsnetBackend) WatchBus(ctx context.Context) (busWatcher, error) {
	watcher, err := b.localClient.WatchIPNBus(ctx, ipn.NotifyInitialState|ipn.NotifyNoPrivateKeys)
	if err != nil {
		return nil, err
	}
	return tsnetBusWatcher{watcher: watcher}, nil
}

type tsnetBusWatcher struct{ watcher *local.IPNBusWatcher }

func (w tsnetBusWatcher) Next() (bool, string, error) {
	notification, err := w.watcher.Next()
	if err != nil {
		return false, "", err
	}
	errorMessage := ""
	if notification.ErrMessage != nil {
		errorMessage = *notification.ErrMessage
	}
	changed := notification.State != nil || notification.BrowseToURL != nil || notification.SelfChange != nil ||
		notification.LoginFinished != nil || notification.Health != nil || notification.ErrMessage != nil
	return changed, errorMessage, nil
}

func (w tsnetBusWatcher) Close() error { return w.watcher.Close() }

func (b *tsnetBackend) WhoIs(ctx context.Context, remoteAddress string) (peerIdentity, error) {
	who, err := b.localClient.WhoIs(ctx, remoteAddress)
	if err != nil {
		return peerIdentity{}, err
	}
	if who.Node == nil {
		return peerIdentity{}, errors.New("whois: no node")
	}
	identity := peerIdentity{
		NodeID: string(who.Node.StableID),
		Name:   who.Node.ComputedName,
		Tags:   append([]string(nil), who.Node.Tags...),
	}
	if identity.Name == "" {
		identity.Name = who.Node.Name
	}
	// Tagged nodes carry a placeholder user profile; their tags identify them.
	if who.UserProfile != nil && len(identity.Tags) == 0 {
		identity.User = who.UserProfile.LoginName
	}
	if identity.NodeID == "" {
		return peerIdentity{}, errors.New("whois: empty stable node id")
	}
	return identity, nil
}

func (b *tsnetBackend) InjectNetworkEvent() {
	if monitor, ok := b.server.Sys().NetMon.GetOK(); ok {
		monitor.InjectEvent()
	}
}

func (b *tsnetBackend) Close() error { return b.server.Close() }
