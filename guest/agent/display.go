package main

import (
	"encoding/json"
	"fmt"
	"log"
	"os"
	"os/exec"
	"slices"
	"strconv"
	"time"

	"golang.org/x/sys/unix"
)

// The display service is ASL's WSLg: it runs an X server with a VNC port inside the distro's
// namespaces, so apps in the distro use DISPLAY=:0 (the session service exports it) while an
// Android GUI window renders the desktop over the forward service. Raw pixels (the X11
// protocol) stay inside Linux; only RFB crosses, like WSLg's remote presentation. Unlike WSLg
// there is no system compositor — every distro runs its own desktop, and windows are the
// whole screen rather than per-app seamless windows.

const (
	// VNC display resolution; the X server scales nothing, the Android GUI window scales the framebuffer.
	displayWidth, displayHeight = 1280, 800
	displayBasePort             = 5900 // matches the Xvnc/x11vnc default; display windows reserve 5900-5999
	displayWait                 = 30 * time.Second
)

// displayScript starts one X server flavor and exits, leaving it as an orphan for distro-init.
// TigerVNC's Xvnc is the single-binary path (X server and RFB server in one); Xvfb + x11vnc
// else. Unauthenticated but localhost-only: the only route to the port is the forward service.
func displayScript(port int) string {
	return fmt.Sprintf(`
export PATH=%s
mkdir -p /tmp/.X11-unix && chmod 1777 /tmp/.X11-unix
if command -v Xvnc >/dev/null 2>&1; then
	nohup Xvnc :0 -depth 24 -geometry %dx%d -rfbport %d -SecurityTypes None -localhost yes >/var/log/asl-xvnc.log 2>&1 &
elif command -v Xvfb >/dev/null 2>&1 && command -v x11vnc >/dev/null 2>&1; then
	nohup Xvfb :0 -screen 0 %dx%dx24 >/var/log/asl-xvfb.log 2>&1 &
	for i in $(seq 1 50); do [ -S /tmp/.X11-unix/X0 ] && break; sleep 0.2; done
	nohup x11vnc -display :0 -rfbport %d -localhost -forever -shared -nopw -quiet >/var/log/asl-xvnc.log 2>&1 &
else
	echo "no X server: install tigervnc-standalone-server, or xvfb and x11vnc" >&2
	exit 1
fi`, pathEnv, displayWidth, displayHeight, port, displayWidth, displayHeight, port)
}

// displayRunning reports whether a TCP listener occupies the port in the shared guest network.
func displayRunning(port int) bool {
	return slices.Contains(listeningPorts(), port)
}

func (a *agent) display(c *conn, raw []byte) error {
	h := struct {
		Distro string
		Port   int
	}{Port: displayBasePort}
	if err := json.Unmarshal(raw, &h); err != nil {
		return err
	}
	if h.Port < displayBasePort || h.Port > displayBasePort+99 {
		return fmt.Errorf("display port %d out of range", h.Port)
	}
	d, err := a.get(h.Distro, "")
	if err != nil {
		return err
	}
	// Serializes setup per distro: two GUI windows must not race two X servers onto one port.
	d.displayMu.Lock()
	defer d.displayMu.Unlock()
	holder, err := a.start(d)
	if err != nil {
		return err
	}
	if !displayRunning(h.Port) {
		// Same pipeline as session: nsenter enters the distro's namespaces and -r chroots to the
		// holder's (chrooted) root, so the X socket lands in the distro's /tmp and PATH resolves.
		cmd := exec.Command("nsenter", "-t", strconv.Itoa(holder), "-m", "-u", "-i", "-p", "-r", "-w", "--", "su", "-l", "--", "root", "-c", displayScript(h.Port))
		cmd.SysProcAttr = &unix.SysProcAttr{Setsid: true}
		cmd.Stdout, cmd.Stderr = os.Stderr, os.Stderr
		_, wait, err := spawn(cmd)
		if err != nil {
			return err
		}
		log.Printf("distro %s: starting display server on port %d", d.ID, h.Port)
		// The script backgrounds the X server and exits; wait for it, but never longer than it
		// could take, then let the port decide success (a hung script still leaves RFB broken).
		select {
		case ws := <-wait:
			if code := exitCode(ws); code != 0 {
				return fmt.Errorf("display setup exited %d", code)
			}
		case <-time.After(10 * time.Second):
		}
	}
	if !displayRunning(h.Port) {
		for start := time.Now(); !displayRunning(h.Port); {
			if time.Since(start) > displayWait {
				return fmt.Errorf("display server did not open port %d; check %s in the distribution", h.Port, "/var/log/asl-xvnc.log")
			}
			time.Sleep(250 * time.Millisecond)
		}
	}
	d.display.Store(int32(h.Port))
	return c.reply(obj{"ok": true, "display": ":0", "port": h.Port})
}
