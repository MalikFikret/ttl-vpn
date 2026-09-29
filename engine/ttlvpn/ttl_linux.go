package ttlvpn

import (
	"syscall"

	"github.com/xjasonlyu/tun2socks/v2/dialer"
	"golang.org/x/sys/unix"
)

// withTTL returns a socket option that sets the IPv4 TTL / IPv6 hop limit
// on every outbound socket the engine creates (TCP dials and UDP listens).
func withTTL(ttl int) dialer.SocketOption {
	return dialer.SocketOptionFunc(func(_, _ string, c syscall.RawConn) error {
		var innerErr error
		if err := c.Control(func(fd uintptr) {
			innerErr = setTTL(int(fd), ttl)
		}); err != nil {
			return err
		}
		return innerErr
	})
}

func setTTL(fd, ttl int) error {
	// Ask the socket for its address family instead of guessing from the network string.
	domain, err := unix.GetsockoptInt(fd, unix.SOL_SOCKET, unix.SO_DOMAIN)
	if err != nil {
		return err
	}

	switch domain {
	case unix.AF_INET:
		return unix.SetsockoptInt(fd, unix.IPPROTO_IP, unix.IP_TTL, ttl)
	case unix.AF_INET6:
		if err := unix.SetsockoptInt(fd, unix.IPPROTO_IPV6, unix.IPV6_UNICAST_HOPS, ttl); err != nil {
			return err
		}
		// Dual-stack IPv6 sockets can also carry IPv4-mapped traffic.
		// Best effort: this fails harmlessly on IPv6-only sockets.
		_ = unix.SetsockoptInt(fd, unix.IPPROTO_IP, unix.IP_TTL, ttl)
		return nil
	default:
		return nil
	}
}