package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"os"
	"os/exec"
	"strconv"
	"strings"

	"golang.org/x/sys/unix"
)

const (
	version  = "0.1.0"
	pathEnv  = "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
	nosuid   = unix.MS_NOSUID | unix.MS_NODEV | unix.MS_NOEXEC
	bindTree = unix.MS_BIND | unix.MS_REC
)

type obj = map[string]any

type mnt struct {
	src, target, fstype, data string
	flags                     uintptr
}

var bootMounts = []mnt{
	{"proc", "/proc", "proc", "", nosuid},
	{"sysfs", "/sys", "sysfs", "", nosuid},
	{"devtmpfs", "/dev", "devtmpfs", "mode=0755", unix.MS_NOSUID},
	{"devpts", "/dev/pts", "devpts", "gid=5,mode=0620,ptmxmode=0666", unix.MS_NOSUID | unix.MS_NOEXEC},
	{"tmpfs", "/dev/shm", "tmpfs", "mode=1777", unix.MS_NOSUID | unix.MS_NODEV},
	{"tmpfs", "/run", "tmpfs", "mode=0755", unix.MS_NOSUID | unix.MS_NODEV},
	{"cgroup2", "/sys/fs/cgroup", "cgroup2", "nsdelegate", nosuid},
}

var devLinks = map[string]string{
	"/dev/fd":     "/proc/self/fd",
	"/dev/stdin":  "/proc/self/fd/0",
	"/dev/stdout": "/proc/self/fd/1",
	"/dev/stderr": "/proc/self/fd/2",
}

var services = map[string]func(*agent, *conn, []byte) error{
	"control": (*agent).control,
	"session": (*agent).session,
	"fs":      (*agent).fs,
	"file":    (*agent).file,
	"forward": (*agent).forward,
	"usbip":   (*agent).usbip,
	"import":  (*agent).importDistro,
	"export":  (*agent).exportDistro,
	"share9p": (*agent).share9p,
}

func main() {
	log.SetFlags(0)
	log.SetPrefix("asl-init: ")
	if len(os.Args) == 3 && os.Args[1] == "distro-init" {
		if err := distroInit(); err != nil {
			log.Fatalf("distro-init %s: %v", os.Args[2], err)
		}
		return
	}
	if err := mountAll("", bootMounts); err != nil {
		log.Print(err)
	}
	if _, err := unix.FcntlInt(2, unix.F_GETFD, 0); err != nil {
		if fd, err := unix.Open("/dev/console", unix.O_RDWR, 0); err == nil {
			for i := 0; i < 3; i++ {
				unix.Dup3(fd, i, 0)
			}
		}
	}
	for link, target := range devLinks {
		os.Symlink(target, link)
	}
	if t := cmdline("asl.time", 0); t != 0 {
		tv := unix.Timeval{Sec: int64(t)}
		if err := unix.Settimeofday(&tv); err != nil {
			log.Printf("settimeofday: %v", err)
		}
	}
	os.Setenv("PATH", strings.TrimPrefix(pathEnv, "PATH="))
	unix.Sethostname([]byte("asl"))
	// Android can kill the VM without warning, so dirty pages reach the distro disks within ~3s instead of 30s.
	for file, value := range map[string]string{"dirty_expire_centisecs": "300", "dirty_writeback_centisecs": "100"} {
		if err := os.WriteFile("/proc/sys/vm/"+file, []byte(value), 0); err != nil {
			log.Print(err)
		}
	}
	startReaper()

	// busybox udhcpc does not raise the link itself; a down eth0 never sends a DISCOVER.
	for _, link := range []string{"lo", "eth0"} {
		if err := pipeline(nil, nil, exec.Command("ip", "link", "set", link, "up")); err != nil {
			log.Print(err)
		}
	}
	dhcp := exec.Command("udhcpc", "-f", "-i", "eth0", "-s", "/usr/share/udhcpc/default.script")
	dhcp.Stdout, dhcp.Stderr = os.Stderr, os.Stderr
	if _, _, err := spawn(dhcp); err != nil {
		log.Print(err)
	}

	a := &agent{distros: map[string]*distro{}}
	a.android.Store("")
	go a.watchPorts()
	log.Fatal(a.serve(uint32(cmdline("asl.port", 5000))))
}

func mountAll(root string, mounts []mnt) error {
	for _, m := range mounts {
		target := root + m.target
		if err := os.MkdirAll(target, 0o755); err != nil {
			return err
		}
		if err := unix.Mount(m.src, target, m.fstype, m.flags, m.data); err != nil && err != unix.EBUSY {
			return fmt.Errorf("mount %s on %s: %w", m.src, target, err)
		}
	}
	return nil
}

func cmdline(key string, def uint64) uint64 {
	data, _ := os.ReadFile("/proc/cmdline")
	for _, f := range strings.Fields(string(data)) {
		if v, ok := strings.CutPrefix(f, key+"="); ok {
			if n, err := strconv.ParseUint(v, 10, 64); err == nil {
				return n
			}
		}
	}
	return def
}

func (a *agent) serve(port uint32) error {
	fd, err := unix.Socket(unix.AF_VSOCK, unix.SOCK_STREAM|unix.SOCK_CLOEXEC, 0)
	if err != nil {
		return fmt.Errorf("vsock: %w", err)
	}
	if err := unix.Bind(fd, &unix.SockaddrVM{CID: unix.VMADDR_CID_ANY, Port: port}); err != nil {
		return fmt.Errorf("vsock bind %d: %w", port, err)
	}
	if err := unix.Listen(fd, 64); err != nil {
		return err
	}
	log.Printf("listening on vsock port %d", port)
	for {
		nfd, _, err := unix.Accept4(fd, unix.SOCK_CLOEXEC)
		if err == unix.EINTR || err == unix.ECONNABORTED {
			continue
		}
		if err != nil {
			return fmt.Errorf("vsock accept: %w", err)
		}
		go a.handle(&conn{fd: nfd, File: os.NewFile(uintptr(nfd), "vsock")})
	}
}

// conn keeps the vsock fd in blocking mode: usbip and 9p hand it to the kernel, import/export hand it to children.
type conn struct {
	*os.File
	fd      int
	replied bool
}

func (c *conn) readHeader() ([]byte, error) {
	var line []byte
	b := make([]byte, 1)
	for len(line) < 1<<16 {
		if _, err := io.ReadFull(c, b); err != nil {
			return nil, err
		}
		if b[0] == '\n' {
			return line, nil
		}
		line = append(line, b[0])
	}
	return nil, errors.New("header too long")
}

func (c *conn) reply(v any) error {
	c.replied = true
	return writeJSON(c, v)
}

func (c *conn) shutdown(how int) {
	unix.Shutdown(c.fd, how)
}

func writeJSON(w io.Writer, v any) error {
	b, err := json.Marshal(v)
	if err != nil {
		return err
	}
	_, err = w.Write(append(b, '\n'))
	return err
}

func (a *agent) handle(c *conn) {
	defer c.Close()
	var h struct {
		Svc string `json:"svc"`
	}
	raw, err := c.readHeader()
	if err == nil {
		err = json.Unmarshal(raw, &h)
	}
	if err == nil {
		if svc, ok := services[h.Svc]; ok {
			err = svc(a, c, raw)
		} else {
			err = fmt.Errorf("unknown service %q", h.Svc)
		}
	}
	switch {
	case err == nil:
	case c.replied:
		log.Printf("%s: %v", h.Svc, err)
	default:
		c.reply(obj{"ok": false, "error": err.Error()})
	}
}
