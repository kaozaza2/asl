package main

import (
	"bufio"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"maps"
	"os"
	"os/exec"
	"regexp"
	"slices"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/creack/pty"
	"golang.org/x/sys/unix"
)

const (
	frameData = iota
	frameResize
	frameEOF
	frameStderr
	frameExit
)

var envName = regexp.MustCompile(`^[A-Za-z_][A-Za-z0-9_]*$`)

type frameWriter struct {
	mu sync.Mutex
	w  io.Writer
}

func (f *frameWriter) write(typ byte, p []byte) error {
	buf := make([]byte, 5, 5+len(p))
	buf[0] = typ
	binary.BigEndian.PutUint32(buf[1:], uint32(len(p)))
	f.mu.Lock()
	defer f.mu.Unlock()
	_, err := f.w.Write(append(buf, p...))
	return err
}

func shellQuote(s string) string {
	return "'" + strings.ReplaceAll(s, "'", `'\''`) + "'"
}

func (a *agent) session(c *conn, raw []byte) error {
	h := struct {
		Distro, User, Cwd string
		Argv              []string
		Env               map[string]string
		Cols, Rows        uint16
		Tty, Oobe         bool
	}{Cols: 80, Rows: 24, Tty: true}
	if err := json.Unmarshal(raw, &h); err != nil {
		return err
	}
	d, err := a.get(h.Distro, "")
	if err != nil {
		return err
	}
	holder, err := a.start(d)
	if err != nil {
		return err
	}
	user := h.User
	if user == "" {
		user = d.defaultUser()
	}
	if strings.HasPrefix(user, "-") {
		return fmt.Errorf("invalid user %q", user)
	}

	var script []string
	for _, k := range slices.Sorted(maps.Keys(h.Env)) {
		if !envName.MatchString(k) {
			return fmt.Errorf("invalid environment variable %q", k)
		}
		script = append(script, "export "+k+"="+shellQuote(h.Env[k]))
	}
	// GUI: like WSLg's env injection, sessions can drive the distro's display server when one is up.
	if d.display.Load() != 0 {
		script = append(script, "export DISPLAY=:0")
	}
	switch {
	case h.Cwd == "" || h.Cwd == "~":
	case strings.HasPrefix(h.Cwd, "~/"):
		script = append(script, `cd "$HOME"/`+shellQuote(h.Cwd[2:]))
	default:
		script = append(script, "cd "+shellQuote(h.Cwd))
	}
	if len(h.Argv) > 0 {
		script = append(script, strings.Join(h.Argv, " "))
	} else {
		script = append(script, `exec "$SHELL" -l`)
	}

	// Like WSL, the image's [oobe] runs once, as root, before the first interactive login shell.
	var oobe wslConf
	if h.Oobe && h.Tty && len(h.Argv) == 0 && h.User == "" && d.oobeClaimed.CompareAndSwap(false, true) {
		oobe = loadConf(d.root(), "wsl-distribution.conf")
		log.Printf("distro %s: first-run setup %q", d.ID, oobe["oobe.command"])
		if oobe["oobe.command"] == "" {
			a.emit(obj{"event": "oobe", "distro": d.ID})
			oobe = nil
		}
	}

	term := "dumb"
	type stream struct {
		f   *os.File
		typ byte
	}
	var streams []stream
	var stdin, ptmx *os.File
	var child []*os.File
	if h.Tty {
		var tty *os.File
		if ptmx, tty, err = pty.Open(); err != nil {
			return err
		}
		defer ptmx.Close()
		pty.Setsize(ptmx, &pty.Winsize{Cols: h.Cols, Rows: h.Rows})
		stdin, child, term = ptmx, []*os.File{tty, tty, tty}, "xterm-256color"
		streams = []stream{{ptmx, frameData}}
	} else {
		var p [3][2]*os.File
		for i := range p {
			if p[i][0], p[i][1], err = os.Pipe(); err != nil {
				return err
			}
			defer p[i][0].Close()
			defer p[i][1].Close()
		}
		stdin, child = p[0][1], []*os.File{p[0][0], p[1][1], p[2][1]}
		streams = []stream{{p[1][0], frameData}, {p[2][0], frameStderr}}
	}
	// The child ends close once the last process is spawned, so the readers see EOF when it exits.
	release := func() {
		for _, f := range child {
			f.Close()
		}
		child = nil
	}
	defer release()

	// busybox nsenter lacks -a; "--" before the user passes -c to the shell, so util-linux su keeps the controlling tty.
	run := func(user string, script []string) (int, <-chan unix.WaitStatus, error) {
		// Minimal rootfs tarballs set no locale; without one, shells treat UTF-8 (e.g. Thai) as raw bytes.
		script = append([]string{`[ -n "$LANG" ] || export LANG=C.UTF-8`}, script...)
		args := []string{"-t", strconv.Itoa(holder), "-m", "-u", "-i", "-p", "-r", "-w", "--", "su", "-l", "--", user, "-c", strings.Join(script, "; ")}
		cmd := exec.Command("nsenter", args...)
		cmd.SysProcAttr = &syscall.SysProcAttr{Setsid: true, Setctty: h.Tty}
		cmd.Stdin, cmd.Stdout, cmd.Stderr = child[0], child[1], child[2]
		cmd.Env = []string{pathEnv, "TERM=" + term}
		return spawn(cmd)
	}

	var pid atomic.Int32
	first, wait, err := func() (int, <-chan unix.WaitStatus, error) {
		if oobe != nil {
			return run("root", []string{oobe["oobe.command"]})
		}
		return run(user, script)
	}()
	if err != nil {
		if oobe != nil {
			d.oobeClaimed.Store(false)
		}
		return err
	}
	pid.Store(int32(first))
	if oobe == nil {
		release()
	}
	if err := c.reply(obj{"ok": true}); err != nil {
		unix.Kill(-first, unix.SIGKILL)
		<-wait
		return err
	}

	fw := &frameWriter{w: c}
	var wg sync.WaitGroup
	for _, s := range streams {
		wg.Add(1)
		go func() {
			defer wg.Done()
			buf := make([]byte, 32<<10)
			for {
				n, err := s.f.Read(buf)
				if n > 0 && fw.write(s.typ, buf[:n]) != nil {
					return
				}
				if err != nil {
					return
				}
			}
		}()
	}

	exited := make(chan struct{})
	go func() {
		br := bufio.NewReader(c)
		hdr := make([]byte, 5)
		for {
			if _, err := io.ReadFull(br, hdr); err != nil {
				break
			}
			payload := make([]byte, binary.BigEndian.Uint32(hdr[1:]))
			if _, err := io.ReadFull(br, payload); err != nil {
				break
			}
			switch hdr[0] {
			case frameData:
				stdin.Write(payload)
			case frameResize:
				if ptmx != nil && len(payload) == 4 {
					pty.Setsize(ptmx, &pty.Winsize{Cols: binary.BigEndian.Uint16(payload), Rows: binary.BigEndian.Uint16(payload[2:])})
				}
			case frameEOF:
				if ptmx != nil {
					ptmx.Write([]byte{4})
				} else {
					stdin.Close()
				}
			}
		}
		select {
		case <-exited:
		default:
			if ptmx != nil {
				ptmx.Close()
			}
			p := int(pid.Load())
			unix.Kill(-p, unix.SIGHUP)
			unix.Kill(p, unix.SIGHUP)
		}
	}()

	code := exitCode(<-wait)
	if oobe != nil {
		if code == 0 {
			ev := obj{"event": "oobe", "distro": d.ID}
			if uid := oobe["oobe.defaultuid"]; uid != "" {
				if j, err := a.jail(d.ID); err == nil {
					if p := j.passwd(2, uid); p != nil {
						user, ev["user"] = p[0], p[0]
					}
					unix.Close(j.fd)
				}
			}
			a.emit(ev)
		} else {
			d.oobeClaimed.Store(false)
			fw.write(frameData, []byte(fmt.Sprintf("\r\n[first-run setup exited with code %d; it will run again next time]\r\n", code)))
		}
		next, w, err := run(user, script)
		release()
		if err != nil {
			return err
		}
		pid.Store(int32(next))
		code = exitCode(<-w)
	}
	close(exited)
	for _, s := range streams {
		s.f.SetReadDeadline(time.Now().Add(200 * time.Millisecond))
	}
	wg.Wait()
	exit := make([]byte, 4)
	binary.BigEndian.PutUint32(exit, uint32(int32(code)))
	err = fw.write(frameExit, exit)
	c.shutdown(unix.SHUT_RDWR)
	return err
}
