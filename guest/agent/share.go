package main

import (
	"bufio"
	"encoding/json"
	"fmt"
	"os"
	"path"
	"strings"
	"sync"

	"golang.org/x/sys/unix"
)

var shared = struct {
	sync.Mutex
	m map[string]bool
}{m: map[string]bool{}}

// ensureShared turns dir into a shared bind mount so mounts beneath it propagate into every distro.
func ensureShared(dir string) error {
	shared.Lock()
	defer shared.Unlock()
	if shared.m[dir] {
		return nil
	}
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return err
	}
	if err := unix.Mount(dir, dir, "", unix.MS_BIND, ""); err != nil {
		return fmt.Errorf("bind %s: %w", dir, err)
	}
	if err := unix.Mount("", dir, "", unix.MS_SHARED, ""); err != nil {
		return fmt.Errorf("make %s shared: %w", dir, err)
	}
	shared.m[dir] = true
	return nil
}

func mountedFS(dir, fstype string) bool {
	f, err := os.Open("/proc/self/mounts")
	if err != nil {
		return false
	}
	defer f.Close()
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		if m := strings.Fields(sc.Text()); len(m) > 2 && m[1] == dir && m[2] == fstype {
			return true
		}
	}
	return false
}

func (a *agent) share9p(c *conn, raw []byte) error {
	var h struct{ Tag, Mountpoint string }
	if err := json.Unmarshal(raw, &h); err != nil {
		return err
	}
	if h.Tag == "" {
		h.Tag = "android"
	}
	if !path.IsAbs(h.Mountpoint) {
		return fmt.Errorf("invalid mountpoint %q", h.Mountpoint)
	}
	mp := path.Clean(h.Mountpoint)
	if err := ensureShared(mp); err != nil {
		return err
	}
	for mountedFS(mp, "9p") {
		if err := unix.Unmount(mp, unix.MNT_DETACH); err != nil {
			return fmt.Errorf("unmount stale share: %w", err)
		}
	}
	if err := c.reply(obj{"ok": true}); err != nil {
		return err
	}
	opts := fmt.Sprintf("trans=fd,rfdno=%d,wfdno=%d,version=9p2000.L,msize=262144,cache=none", c.fd, c.fd)
	if err := unix.Mount(h.Tag, mp, "9p", 0, opts); err != nil {
		return fmt.Errorf("mount 9p on %s: %w", mp, err)
	}
	return nil
}
