package main

import (
	"encoding/json"
	"fmt"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	vhciDir   = "/sys/devices/platform/vhci_hcd.0"
	vdevFree  = 4
	speedSS   = 5
	usbipPoll = time.Second
)

var usbipMu sync.Mutex

// vhciPorts maps port number to state, keeping only ports on the given hub ("hs" or "ss").
func vhciPorts(hub string) (map[int]int, error) {
	data, err := os.ReadFile(vhciDir + "/status")
	if err != nil {
		return nil, err
	}
	ports := map[int]int{}
	for _, line := range strings.Split(string(data), "\n") {
		f := strings.Fields(line)
		if len(f) < 3 || f[0] != hub {
			continue
		}
		port, perr := strconv.Atoi(f[1])
		state, serr := strconv.Atoi(f[2])
		if perr == nil && serr == nil {
			ports[port] = state
		}
	}
	return ports, nil
}

func (a *agent) usbip(c *conn, raw []byte) error {
	var h struct {
		Busid string
		Devid uint32
		Speed int
	}
	if err := json.Unmarshal(raw, &h); err != nil {
		return err
	}
	hub := "hs"
	if h.Speed >= speedSS {
		hub = "ss"
	}
	usbipMu.Lock()
	ports, err := vhciPorts(hub)
	port := -1
	for p, state := range ports {
		if state == vdevFree && (port < 0 || p < port) {
			port = p
		}
	}
	if err == nil && port < 0 {
		err = fmt.Errorf("no free %s vhci port", hub)
	}
	if err == nil {
		// Reply before attaching: once attached the kernel may send CMD_SUBMIT on this socket at any moment.
		err = c.reply(obj{"ok": true, "port": port})
	}
	if err == nil {
		attach := fmt.Sprintf("%d %d %d %d", port, c.fd, h.Devid, h.Speed)
		if err = os.WriteFile(vhciDir+"/attach", []byte(attach), 0); err != nil {
			err = fmt.Errorf("attach %s on port %d: %w", h.Busid, port, err)
		}
	}
	usbipMu.Unlock()
	if err != nil {
		return err
	}
	// The kernel owns the data phase; our fd only has to outlive the attachment.
	t := time.NewTicker(usbipPoll)
	defer t.Stop()
	for range t.C {
		if ports, err := vhciPorts(hub); err != nil || ports[port] == vdevFree {
			return nil
		}
	}
	return nil
}
