//go:build !linux

package ttlvpn

import "github.com/xjasonlyu/tun2socks/v2/dialer"

// withTTL is not supported outside Linux/Android.
// This file only exists so the package compiles on the dev machine.
func withTTL(_ int) dialer.SocketOption {
	return dialer.UnsupportedSocketOption
}