package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"strings"

	"golang.org/x/sys/unix"
)

var decompressors = map[string][]string{
	"gz":   {"gzip", "-dc"},
	"xz":   {"xz", "-dc"},
	"zst":  {"zstd", "-dc"},
	"none": nil,
	"":     nil,
}

var (
	tarFlags       = []string{"--numeric-owner", "--xattrs", "--xattrs-include=*"}
	exportExcludes = []string{"proc", "sys", "dev", "run", "tmp", "mnt/android"}
)

func (a *agent) importDistro(c *conn, raw []byte) error {
	var h struct{ Distro, Device, Compression string }
	if err := json.Unmarshal(raw, &h); err != nil {
		return err
	}
	dec, ok := decompressors[h.Compression]
	if !ok {
		return fmt.Errorf("unknown compression %q", h.Compression)
	}
	if !strings.HasPrefix(h.Device, "/dev/") {
		return fmt.Errorf("invalid device %q", h.Device)
	}
	d, err := a.get(h.Distro, h.Device)
	if err != nil {
		return err
	}
	d.terminate()
	d.mu.Lock()
	defer d.mu.Unlock()
	d.Device = h.Device
	if err := d.unmount(); err != nil {
		return err
	}
	if err := pipeline(nil, nil, exec.Command("mkfs.ext4", "-F", "-q", h.Device)); err != nil {
		return err
	}
	if err := d.mount(); err != nil {
		return err
	}
	if err := c.reply(obj{"ok": true}); err != nil {
		d.unmount()
		return err
	}

	pr, pw, err := os.Pipe()
	if err == nil {
		copied := make(chan error, 1)
		go func() {
			_, cerr := io.Copy(pw, &chunkReader{r: c})
			pw.Close()
			copied <- cerr
		}()
		cmds := []*exec.Cmd{exec.Command("tar", append([]string{"-xp", "-C", d.root(), "-f", "-"}, tarFlags...)...)}
		if dec != nil {
			cmds = append([]*exec.Cmd{exec.Command(dec[0], dec[1:]...)}, cmds...)
		}
		err = pipeline(pr, nil, cmds...)
		pr.Close()
		if cerr := <-copied; err == nil && cerr != nil && !errors.Is(cerr, unix.EPIPE) {
			err = fmt.Errorf("receiving tarball: %w", cerr)
		}
	}
	if uerr := d.unmount(); err == nil {
		err = uerr
	}
	if err != nil {
		writeJSON(c, obj{"ok": false, "error": err.Error()})
		return err
	}
	return writeJSON(c, obj{"ok": true})
}

func (a *agent) exportDistro(c *conn, raw []byte) error {
	var h struct{ Distro string }
	if err := json.Unmarshal(raw, &h); err != nil {
		return err
	}
	d, err := a.mounted(h.Distro)
	if err != nil {
		return err
	}
	if err := c.reply(obj{"ok": true}); err != nil {
		return err
	}
	args := append([]string{"-C", d.root(), "-cf", "-", "--one-file-system"}, tarFlags...)
	for _, x := range exportExcludes {
		args = append(args, "--exclude=./"+x+"/*")
	}
	err = pipeline(nil, c.File, exec.Command("tar", append(args, ".")...))
	c.shutdown(unix.SHUT_WR)
	return err
}
