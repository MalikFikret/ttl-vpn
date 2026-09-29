//go:build tools

// Package tools pins build-time dependencies so `go mod tidy` keeps them.
// gomobile needs golang.org/x/mobile/bind in go.mod to generate bindings.
package tools

import _ "golang.org/x/mobile/bind"
