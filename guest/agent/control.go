package main

import (
	"bufio"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"os"
	"slices"
	"sort"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"golang.org/x/sys/unix"
)

type agent struct {
	mu      sync.Mutex
	distros map[string]*distro
	android atomic.Value
	ctl     atomic.Pointer[ctlConn]
}

type ctlConn struct {
	mu sync.Mutex
	w  io.Writer
}

func (c *ctlConn) send(v any) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if err := writeJSON(c.w, v); err != nil {
		log.Printf("control: %v", err)
	}
}

func (a *agent) emit(v any) {
	if c := a.ctl.Load(); c != nil {
		c.send(v)
	}
}

type request struct {
	ID           json.RawMessage `json:"id"`
	Op           string          `json:"op"`
	Distro       string          `json:"distro"`
	User         string          `json:"user"`
	Distros      []distroConfig  `json:"distros"`
	AndroidMount *string         `json:"androidMount"`
}

func (a *agent) control(c *conn, _ []byte) error {
	ctl := &ctlConn{w: c}
	if err := c.reply(obj{"ok": true}); err != nil {
		return err
	}
	a.ctl.Store(ctl)
	defer a.ctl.CompareAndSwap(ctl, nil)
	sc := bufio.NewScanner(c)
	sc.Buffer(nil, 1<<20)
	for sc.Scan() {
		var req request
		if err := json.Unmarshal(sc.Bytes(), &req); err != nil {
			log.Printf("control: bad request: %v", err)
			continue
		}
		go func() {
			res, err := a.do(req)
			resp := obj{"id": req.ID, "ok": err == nil}
			if err != nil {
				resp["error"] = err.Error()
			} else {
				resp["result"] = res
			}
			ctl.send(resp)
			if req.Op == "shutdown" && err == nil {
				a.powerOff()
			}
		}()
	}
	return sc.Err()
}

func (a *agent) do(req request) (any, error) {
	switch req.Op {
	case "hello":
		var u unix.Utsname
		unix.Uname(&u)
		return obj{"version": version, "kernel": unix.ByteSliceToString(u.Release[:])}, nil
	case "configure":
		return obj{}, a.configure(req.Distros, req.AndroidMount)
	case "start":
		d, err := a.get(req.Distro, "")
		if err != nil {
			return nil, err
		}
		pid, err := a.start(d)
		return obj{"pid": pid}, err
	case "setDefaultUser":
		return obj{}, a.setDefaultUser(req.Distro, req.User)
	case "terminate":
		d, err := a.get(req.Distro, "")
		if err != nil {
			return nil, err
		}
		return obj{}, d.terminate()
	case "list":
		a.mu.Lock()
		ds := make([]*distro, 0, len(a.distros))
		for _, d := range a.distros {
			ds = append(ds, d)
		}
		a.mu.Unlock()
		sort.Slice(ds, func(i, j int) bool { return ds[i].ID < ds[j].ID })
		list := []obj{}
		for _, d := range ds {
			d.mu.Lock()
			state := "stopped"
			if d.pid != 0 {
				state = "running"
			}
			list = append(list, obj{"id": d.ID, "state": state, "pid": d.pid})
			d.mu.Unlock()
		}
		return list, nil
	case "shutdown":
		return obj{}, nil
	}
	return nil, fmt.Errorf("unknown op %q", req.Op)
}

func (a *agent) powerOff() {
	a.mu.Lock()
	ds := make([]*distro, 0, len(a.distros))
	for _, d := range a.distros {
		ds = append(ds, d)
	}
	a.mu.Unlock()
	var wg sync.WaitGroup
	for _, d := range ds {
		wg.Add(1)
		go func() {
			defer wg.Done()
			d.terminate()
			d.mu.Lock()
			d.unmount()
			d.mu.Unlock()
		}()
	}
	wg.Wait()
	unix.Sync()
	log.Print("powering off")
	unix.Reboot(unix.LINUX_REBOOT_CMD_POWER_OFF)
}

func (a *agent) watchPorts() {
	var last []int
	var lastCtl *ctlConn
	t := time.NewTicker(time.Second)
	defer t.Stop()
	for range t.C {
		ports := listeningPorts()
		if ctl := a.ctl.Load(); ctl != nil && (ctl != lastCtl || !slices.Equal(ports, last)) {
			ctl.send(obj{"event": "ports", "ports": ports})
			last, lastCtl = ports, ctl
		}
	}
}

// minForwardedPort matches dev.asl.core.MIN_FORWARDED_PORT: binding below it needs CAP_NET_BIND_SERVICE.
const minForwardedPort = 1024

func listeningPorts() []int {
	const listen = "0A"
	set := map[int]bool{}
	for _, f := range []string{"/proc/net/tcp", "/proc/net/tcp6"} {
		data, err := os.ReadFile(f)
		if err != nil {
			continue
		}
		for _, line := range strings.Split(string(data), "\n") {
			fields := strings.Fields(line)
			if len(fields) < 4 || fields[3] != listen {
				continue
			}
			_, hex, _ := strings.Cut(fields[1], ":")
			if p, err := strconv.ParseUint(hex, 16, 16); err == nil && p >= minForwardedPort {
				set[int(p)] = true
			}
		}
	}
	ports := make([]int, 0, len(set))
	for p := range set {
		ports = append(ports, p)
	}
	slices.Sort(ports)
	return ports
}
