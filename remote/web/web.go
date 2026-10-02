// Package web embeds the remote-control web UI: static files only, no
// framework and no build step. The transport serves them at / with a
// default-src 'self' CSP, so nothing here may use inline scripts or styles.
package web

import (
	"embed"
	"fmt"
	"io/fs"
)

//go:embed static
var files embed.FS

// FS is the UI root (index.html, app.js, style.css).
var FS = func() fs.FS {
	root, err := fs.Sub(files, "static")
	if err != nil {
		panic(fmt.Sprintf("web: embedded static directory missing: %v", err))
	}
	return root
}()
