// Package ttlvpn exposes a minimal API (for gomobile) that runs tun2socks
// in direct mode and forces a custom TTL / hop limit on outbound sockets.
package ttlvpn

import (
	"errors"
	"fmt"
	"sync"

	"github.com/xjasonlyu/tun2socks/v2/dialer"
	"github.com/xjasonlyu/tun2socks/v2/engine"
)

var (
	mu      sync.Mutex
	running bool
)

// Start runs the engine on the TUN file descriptor from Android's VpnService.
// The engine takes ownership of fd and closes it on Stop.
func Start(fd, mtu, ttl int) error {
	mu.Lock()
	defer mu.Unlock()

	if running {
		return errors.New("engine already running")
	}

	// Validate here: engine.Start() calls log.Fatalf on failure,
	// which would kill the whole app instead of returning an error.
	if fd <= 0 {
		return fmt.Errorf("invalid fd: %d", fd)
	}
	if mtu < 1280 || mtu > 65535 {
		return fmt.Errorf("invalid mtu: %d", mtu)
	}
	if ttl < 1 || ttl > 255 {
		return fmt.Errorf("invalid ttl: %d", ttl)
	}

	engine.Insert(&engine.Key{
		Device:   fmt.Sprintf("fd://%d", fd),
		Proxy:    "direct://",
		MTU:      mtu,
		LogLevel: "info",
	})
	engine.Start()

	// Must come AFTER engine.Start(): startup calls dialer.Reset(),
	// which would silently wipe any option registered earlier.
	dialer.RegisterSockOpt(withTTL(ttl))

	running = true
	return nil
}

// Stop shuts the engine down and closes the TUN fd.
func Stop() {
	mu.Lock()
	defer mu.Unlock()

	if !running {
		return
	}
	engine.Stop()
	dialer.Reset()
	running = false
}