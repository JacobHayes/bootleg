package transport

import (
	"fmt"
	"net"
	"net/netip"
	"sync"
	"time"
)

// tokenBucket allows bursts up to capacity and refills at perSecond. It is
// only used from one goroutine (a session's reader), so it has no lock.
type tokenBucket struct {
	capacity  float64
	perSecond float64
	tokens    float64
	last      time.Time
}

func newTokenBucket(capacity, perSecond int, now time.Time) *tokenBucket {
	if capacity < 1 || perSecond < 1 {
		panic(fmt.Sprintf("transport: token bucket needs positive capacity and rate, got %d, %d", capacity, perSecond))
	}
	return &tokenBucket{capacity: float64(capacity), perSecond: float64(perSecond), tokens: float64(capacity), last: now}
}

func (b *tokenBucket) allow(now time.Time) bool {
	if elapsed := now.Sub(b.last); elapsed > 0 {
		b.tokens = min(b.capacity, b.tokens+elapsed.Seconds()*b.perSecond)
		b.last = now
	}
	if b.tokens < 1 {
		return false
	}
	b.tokens--
	return true
}

// HotspotIngress checks that a connection arrived on the hotspot address
// itself and comes from inside the hotspot subnet. hotspot is the phone's
// interface address with its prefix length, for example 192.168.49.1/24.
func HotspotIngress(hotspot netip.Prefix, port uint16, local, remote net.Addr) error {
	if !hotspot.IsValid() {
		return fmt.Errorf("invalid hotspot prefix")
	}
	localAddress, err := addrPortOf(local)
	if err != nil {
		return fmt.Errorf("local address: %w", err)
	}
	if localAddress != netip.AddrPortFrom(hotspot.Addr(), port) {
		return fmt.Errorf("local address %s is not the hotspot address %s", localAddress, netip.AddrPortFrom(hotspot.Addr(), port))
	}
	remoteAddress, err := addrPortOf(remote)
	if err != nil {
		return fmt.Errorf("remote address: %w", err)
	}
	if !hotspot.Masked().Contains(remoteAddress.Addr()) || remoteAddress.Addr() == hotspot.Addr() {
		return fmt.Errorf("remote address %s is outside the hotspot subnet %s", remoteAddress.Addr(), hotspot.Masked())
	}
	return nil
}

func addrPortOf(address net.Addr) (netip.AddrPort, error) {
	if address == nil {
		return netip.AddrPort{}, fmt.Errorf("missing address")
	}
	if tcp, ok := address.(*net.TCPAddr); ok {
		addrPort := tcp.AddrPort()
		return netip.AddrPortFrom(addrPort.Addr().Unmap(), addrPort.Port()), nil
	}
	parsed, err := netip.ParseAddrPort(address.String())
	if err != nil {
		return netip.AddrPort{}, err
	}
	return netip.AddrPortFrom(parsed.Addr().Unmap(), parsed.Port()), nil
}

// trackingListener applies an optional ingress check to each accepted
// connection, caps concurrent connections, and remembers live connections so
// a stopping listener can close them (hijacked WebSocket connections
// included, which http.Server.Shutdown leaves alone).
type trackingListener struct {
	net.Listener
	check    func(local, remote net.Addr) error
	slots    chan struct{}
	mutex    sync.Mutex
	conns    map[*trackedConn]struct{}
	onReject func(error)
}

func newTrackingListener(inner net.Listener, connectionCountMax int, check func(local, remote net.Addr) error, onReject func(error)) *trackingListener {
	if connectionCountMax < 1 {
		panic("transport: connection cap must be positive")
	}
	return &trackingListener{
		Listener: inner,
		check:    check,
		slots:    make(chan struct{}, connectionCountMax),
		conns:    map[*trackedConn]struct{}{},
		onReject: onReject,
	}
}

func (l *trackingListener) Accept() (net.Conn, error) {
	for {
		conn, err := l.Listener.Accept()
		if err != nil {
			return nil, err
		}
		if l.check != nil {
			if err := l.check(conn.LocalAddr(), conn.RemoteAddr()); err != nil {
				conn.Close()
				if l.onReject != nil {
					l.onReject(err)
				}
				continue
			}
		}
		select {
		case l.slots <- struct{}{}:
		default:
			conn.Close()
			if l.onReject != nil {
				l.onReject(fmt.Errorf("connection cap %d reached", cap(l.slots)))
			}
			continue
		}
		tracked := &trackedConn{Conn: conn, listener: l}
		l.mutex.Lock()
		l.conns[tracked] = struct{}{}
		l.mutex.Unlock()
		return tracked, nil
	}
}

// closeAll closes every live connection.
func (l *trackingListener) closeAll() {
	l.mutex.Lock()
	conns := make([]*trackedConn, 0, len(l.conns))
	for conn := range l.conns {
		conns = append(conns, conn)
	}
	l.mutex.Unlock()
	for _, conn := range conns {
		conn.Close()
	}
}

type trackedConn struct {
	net.Conn
	listener  *trackingListener
	closeOnce sync.Once
}

func (c *trackedConn) Close() error {
	err := c.Conn.Close()
	c.closeOnce.Do(func() {
		c.listener.mutex.Lock()
		delete(c.listener.conns, c)
		c.listener.mutex.Unlock()
		<-c.listener.slots
	})
	return err
}
