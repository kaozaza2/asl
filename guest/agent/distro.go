package main

import (
	"cmp"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"os"
	"os/exec"
	"os/signal"
	"path"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"golang.org/x/sys/unix"
)

const distrosDir = "/run/asl/distros/"

// sigPoweroff is SIGRTMIN+4 as systemd counts it (glibc SIGRTMIN is 34).
const sigPoweroff = syscall.Signal(38)

type distroConfig struct {
	ID          string `json:"id"`
	Name        string `json:"name"`
	Device      string `json:"device"`
	DefaultUser string `json:"defaultUser"`
	Systemd     bool   `json:"systemd"`
	Hostname    string `json:"hostname"`
}

type distro struct {
	distroConfig
	mu      sync.Mutex
	pid     int
	systemd bool
	done    chan struct{}
	mounted bool
	// oobeClaimed keeps a second tab from starting the image's first-run setup while one is running.
	oobeClaimed atomic.Bool
}

// holderSpec is everything distro-init needs; it is passed in the ASL_DISTRO environment variable.
type holderSpec struct {
	Root, Hostname, Boot, Android, AndroidTarget string
	Systemd, Hosts, Resolv                       bool
}

func validID(id string) error {
	if id == "" || id == "." || id == ".." || strings.ContainsAny(id, "/\x00") {
		return fmt.Errorf("invalid distro id %q", id)
	}
	return nil
}

// get looks up a distro; a non-empty device registers an unknown id (import of a new disk).
func (a *agent) get(id, device string) (*distro, error) {
	if err := validID(id); err != nil {
		return nil, err
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	d := a.distros[id]
	if d == nil {
		if device == "" {
			return nil, fmt.Errorf("unknown distro %q", id)
		}
		d = &distro{distroConfig: distroConfig{ID: id, Device: device}}
		a.distros[id] = d
	}
	return d, nil
}

func (a *agent) configure(cfgs []distroConfig, android *string) error {
	for _, cfg := range cfgs {
		if err := validID(cfg.ID); err != nil {
			return err
		}
	}
	mp := ""
	if android != nil {
		mp = *android
	}
	if mp != "" {
		if err := ensureShared(mp); err != nil {
			return err
		}
	}
	a.android.Store(mp)
	type pending struct {
		d   *distro
		cfg distroConfig
	}
	var updates []pending
	a.mu.Lock()
	next := make(map[string]*distro, len(cfgs))
	for _, cfg := range cfgs {
		d := a.distros[cfg.ID]
		if d == nil {
			d = &distro{distroConfig: cfg}
		} else {
			updates = append(updates, pending{d, cfg})
		}
		next[cfg.ID] = d
	}
	a.distros = next
	a.mu.Unlock()
	for _, u := range updates {
		u.d.mu.Lock()
		u.d.distroConfig = u.cfg
		u.d.mu.Unlock()
	}
	return nil
}

func (d *distro) root() string { return distrosDir + d.ID + "/root" }

// mount and unmount require d.mu.
func (d *distro) mount() error {
	if d.mounted {
		return nil
	}
	if d.Device == "" {
		return fmt.Errorf("distro %q has no device", d.ID)
	}
	if err := os.MkdirAll(d.root(), 0o755); err != nil {
		return err
	}
	if err := unix.Mount(d.Device, d.root(), "ext4", 0, ""); err != nil {
		return fmt.Errorf("mount %s: %w", d.Device, err)
	}
	d.mounted = true
	return nil
}

func (d *distro) unmount() error {
	if !d.mounted {
		return nil
	}
	if err := unix.Unmount(d.root(), 0); err != nil {
		return fmt.Errorf("unmount %s: %w", d.root(), err)
	}
	d.mounted = false
	return nil
}

func (a *agent) mounted(id string) (*distro, error) {
	d, err := a.get(id, "")
	if err != nil {
		return nil, err
	}
	d.mu.Lock()
	defer d.mu.Unlock()
	return d, d.mount()
}

// effectiveUser resolves wsl.conf [user] default, then the host's default, then root; it requires d.mu.
func (d *distro) effectiveUser(conf wslConf) string {
	return conf.str("user.default", cmp.Or(d.DefaultUser, "root"))
}

func (d *distro) defaultUser() string {
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.effectiveUser(loadConf(d.root(), "wsl.conf", "asl.conf"))
}

func (a *agent) start(d *distro) (int, error) {
	d.mu.Lock()
	defer d.mu.Unlock()
	if d.pid != 0 {
		return d.pid, nil
	}
	if err := d.mount(); err != nil {
		return 0, err
	}
	conf := loadConf(d.root(), "wsl.conf", "asl.conf")
	spec := holderSpec{
		Root:     d.root(),
		Hostname: conf.str("network.hostname", d.Hostname),
		Boot:     conf["boot.command"],
		Systemd:  conf.bool("boot.systemd", d.Systemd),
		Hosts:    conf.bool("network.generatehosts", true),
		Resolv:   conf.bool("network.generateresolvconf", true),
	}
	if spec.Hostname == "" {
		spec.Hostname = "asl"
	}
	if mp := a.android.Load().(string); mp != "" && conf.bool("automount.enabled", true) {
		spec.Android = mp
		spec.AndroidTarget = path.Join("/", conf.str("automount.root", "/mnt/"), "android")
	}
	specJSON, err := json.Marshal(spec)
	if err != nil {
		return 0, err
	}
	r, w, err := os.Pipe()
	if err != nil {
		return 0, err
	}
	defer r.Close()
	cmd := exec.Command("/proc/self/exe", "distro-init", d.ID)
	cmd.Args[0] = "asl-init"
	cmd.Env = []string{"ASL_DISTRO=" + string(specJSON)}
	cmd.Stdout, cmd.Stderr = os.Stderr, os.Stderr
	cmd.ExtraFiles = []*os.File{w}
	cmd.SysProcAttr = &syscall.SysProcAttr{Cloneflags: unix.CLONE_NEWNS | unix.CLONE_NEWPID | unix.CLONE_NEWUTS | unix.CLONE_NEWIPC}
	pid, wait, err := spawn(cmd)
	w.Close()
	if err != nil {
		return 0, err
	}
	if msg, _ := io.ReadAll(r); string(msg) != "ok" {
		code := exitCode(<-wait)
		return 0, fmt.Errorf("distro-init failed (exit %d): %s", code, msg)
	}
	d.pid, d.systemd, d.done = pid, spec.Systemd, make(chan struct{})
	go func(done chan struct{}) {
		ws := <-wait
		d.mu.Lock()
		d.pid = 0
		d.mu.Unlock()
		close(done)
		log.Printf("distro %s stopped (exit %d)", d.ID, exitCode(ws))
		a.emit(obj{"event": "distro", "distro": d.ID, "state": "stopped"})
	}(d.done)
	log.Printf("distro %s running as pid %d", d.ID, pid)
	a.emit(obj{"event": "distro", "distro": d.ID, "state": "running", "user": d.effectiveUser(conf)})
	return pid, nil
}

func (d *distro) terminate() error {
	d.mu.Lock()
	pid, done, systemd := d.pid, d.done, d.systemd
	d.mu.Unlock()
	if pid == 0 {
		return nil
	}
	sig := unix.SIGTERM
	if systemd {
		sig = sigPoweroff
	}
	unix.Kill(pid, sig)
	select {
	case <-done:
	case <-time.After(10 * time.Second):
		unix.Kill(pid, unix.SIGKILL)
		<-done
	}
	return nil
}

var distroMounts = []mnt{
	{"proc", "/proc", "proc", "", nosuid},
	{"sysfs", "/sys", "sysfs", "", nosuid},
	{"cgroup2", "/sys/fs/cgroup", "cgroup2", "nsdelegate", nosuid},
	{"/dev", "/dev", "", "", bindTree},
	{"tmpfs", "/dev/shm", "tmpfs", "mode=1777", unix.MS_NOSUID | unix.MS_NODEV},
	{"tmpfs", "/run", "tmpfs", "mode=0755", unix.MS_NOSUID | unix.MS_NODEV},
	{"tmpfs", "/tmp", "tmpfs", "mode=1777", unix.MS_NOSUID | unix.MS_NODEV},
}

// distroInit runs as PID 1 of a fresh pid/mount/uts/ipc namespace and reports readiness on fd 3.
func distroInit() (err error) {
	ready := os.NewFile(3, "ready")
	defer func() {
		if err != nil {
			ready.WriteString(err.Error())
		}
		ready.Close()
	}()
	var s holderSpec
	if err := json.Unmarshal([]byte(os.Getenv("ASL_DISTRO")), &s); err != nil {
		return err
	}
	if err := unix.Mount("", "/", "", unix.MS_REC|unix.MS_SLAVE, ""); err != nil {
		return fmt.Errorf("make / rslave: %w", err)
	}
	mounts := distroMounts
	if s.Android != "" {
		mounts = append(mounts, mnt{s.Android, s.AndroidTarget, "", "", bindTree})
	}
	if err := mountAll(s.Root, mounts); err != nil {
		return err
	}
	var resolv []byte
	for i := 0; s.Resolv && i < 50 && !strings.Contains(string(resolv), "nameserver"); i++ {
		if i > 0 {
			time.Sleep(100 * time.Millisecond)
		}
		resolv, _ = os.ReadFile("/etc/resolv.conf")
	}
	if err := unix.Chroot(s.Root); err != nil {
		return fmt.Errorf("chroot: %w", err)
	}
	if err := os.Chdir("/"); err != nil {
		return err
	}
	if err := unix.Sethostname([]byte(s.Hostname)); err != nil {
		return fmt.Errorf("sethostname: %w", err)
	}
	const notice = "# Generated by ASL. To stop, set [network] %s = false in /etc/wsl.conf\n"
	// /etc/hostname has no notice line: some readers (OpenRC) take its first line verbatim. It is written so that
	// an init such as systemd, which applies /etc/hostname at boot, keeps the name /etc/hosts resolves.
	generated := []struct {
		on       bool
		path, at string
	}{
		{s.Hosts, "/etc/hosts", fmt.Sprintf(notice, "generateHosts") + fmt.Sprintf("127.0.0.1\tlocalhost\n127.0.1.1\t%[1]s.localdomain\t%[1]s\n::1\tlocalhost ip6-localhost ip6-loopback\n", s.Hostname)},
		{s.Hosts, "/etc/hostname", s.Hostname + "\n"},
		{s.Resolv && len(resolv) > 0, "/etc/resolv.conf", fmt.Sprintf(notice, "generateResolvConf") + string(resolv)},
	}
	for _, g := range generated {
		if !g.on {
			continue
		}
		os.Remove(g.path)
		if err := os.WriteFile(g.path, []byte(g.at), 0o644); err != nil {
			log.Printf("%s: %v", g.path, err)
		}
	}
	os.Clearenv()
	os.Setenv("PATH", strings.TrimPrefix(pathEnv, "PATH="))
	startReaper()
	ready.WriteString("ok")
	ready.Close()

	if s.Boot != "" {
		if _, _, err := spawn(exec.Command("/bin/sh", "-c", s.Boot)); err != nil {
			log.Printf("boot command: %v", err)
		}
	}
	if s.Systemd {
		for _, p := range []string{"/lib/systemd/systemd", "/usr/lib/systemd/systemd", "/sbin/init"} {
			if _, err := os.Stat(p); err == nil {
				return unix.Exec(p, []string{p}, []string{pathEnv})
			}
		}
		log.Print("systemd not found, continuing without it")
	}

	sig := make(chan os.Signal, 1)
	signal.Notify(sig, unix.SIGTERM, unix.SIGINT, sigPoweroff)
	<-sig
	unix.Kill(-1, unix.SIGTERM)
	for i := 0; i < 30; i++ {
		if _, err := unix.Wait4(-1, nil, unix.WNOHANG, nil); err == unix.ECHILD {
			break
		}
		time.Sleep(100 * time.Millisecond)
	}
	return nil
}
