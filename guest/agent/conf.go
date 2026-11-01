package main

import (
	"os"
	"path/filepath"
	"strconv"
	"strings"
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
