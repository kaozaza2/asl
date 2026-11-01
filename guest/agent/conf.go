package main

import (
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strconv"
	"strings"

	"golang.org/x/sys/unix"
)

// wslConf maps "section.key" (lowercase) to the raw value of the first readable file in root/etc among names.
type wslConf map[string]string

func loadConf(root string, names ...string) wslConf {
	c := wslConf{}
	for _, name := range names {
		data, err := os.ReadFile(filepath.Join(root, "etc", name))
		if err != nil {
			continue
		}
		section := ""
		for _, line := range strings.Split(string(data), "\n") {
			line = strings.TrimSpace(line)
			switch {
			case line == "" || line[0] == '#' || line[0] == ';':
			case line[0] == '[' && strings.HasSuffix(line, "]"):
				section = strings.ToLower(strings.TrimSpace(line[1 : len(line)-1]))
			default:
				if k, v, ok := strings.Cut(line, "="); ok {
					v = strings.TrimSpace(v)
					if len(v) >= 2 && v[0] == '"' && v[len(v)-1] == '"' {
						v = v[1 : len(v)-1]
					}
					c[section+"."+strings.ToLower(strings.TrimSpace(k))] = v
				}
			}
		}
		return c
	}
	return c
}

func (c wslConf) bool(key string, def bool) bool {
	if b, err := strconv.ParseBool(strings.ToLower(c[key])); err == nil {
		return b
	}
	return def
}

func (c wslConf) str(key, def string) string {
	if v := c[key]; v != "" {
		return v
	}
	return def
}

// setDefaultUser points /etc/wsl.conf [user] default at an existing account, keeping the rest of the file.
func (a *agent) setDefaultUser(id, user string) error {
	j, err := a.jail(id)
	if err != nil {
		return err
	}
	defer unix.Close(j.fd)
	if j.passwd(0, user) == nil {
		return fmt.Errorf("user %q does not exist in this distribution", user)
	}
	var lines []string
	if f, err := j.open("/etc/wsl.conf", unix.O_RDONLY, 0); err == nil {
		data, err := io.ReadAll(f)
		f.Close()
		if err != nil {
			return err
		}
		if text := strings.TrimRight(string(data), "\n"); text != "" {
			lines = strings.Split(text, "\n")
		}
	}
	entry := "default=" + user
	var out []string
	section, done := "", false
	for _, line := range lines {
		t := strings.TrimSpace(line)
		if strings.HasPrefix(t, "[") && strings.HasSuffix(t, "]") {
			if section == "user" && !done {
				out, done = append(out, entry), true
			}
			section = strings.ToLower(strings.TrimSpace(t[1 : len(t)-1]))
		} else if k, _, ok := strings.Cut(t, "="); ok && section == "user" && strings.EqualFold(strings.TrimSpace(k), "default") {
			if !done {
				out, done = append(out, entry), true
			}
			continue
		}
		out = append(out, line)
	}
	if !done {
		if section != "user" {
			out = append(out, "[user]")
		}
		out = append(out, entry)
	}
	f, err := j.open("/etc/wsl.conf", unix.O_WRONLY|unix.O_CREAT|unix.O_TRUNC, 0o644)
	if err != nil {
		return err
	}
	_, err = f.WriteString(strings.Join(out, "\n") + "\n")
	if cerr := f.Close(); err == nil {
		err = cerr
	}
	if err != nil {
		return err
	}
	d, err := a.mounted(id)
	if err != nil {
		return err
	}
	d.mu.Lock()
	d.DefaultUser = user
	d.mu.Unlock()
	return nil
}
