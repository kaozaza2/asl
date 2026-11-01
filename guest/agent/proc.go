package main

import (
	"bytes"
	"fmt"
	"io"
	"os"
	"os/exec"
	"os/signal"
	"sync"

	"golang.org/x/sys/unix"
)

// As PID 1 we reap every child, so exec.Cmd.Wait is never used; spawn hands out the status instead.
var procs = struct {
	sync.Mutex
	m map[int]chan unix.WaitStatus
}{m: map[int]chan unix.WaitStatus{}}

func startReaper() {
	sig := make(chan os.Signal, 1)
	signal.Notify(sig, unix.SIGCHLD)
	go func() {
		for range sig {
			procs.Lock()
			for {
				var ws unix.WaitStatus
				pid, err := unix.Wait4(-1, &ws, unix.WNOHANG, nil)
				if err == unix.EINTR {
					continue
				}
				if err != nil || pid <= 0 {
					break
				}
				if ch, ok := procs.m[pid]; ok {
					ch <- ws
					delete(procs.m, pid)
				}
			}
			procs.Unlock()
		}
	}()
}

func spawn(cmd *exec.Cmd) (int, <-chan unix.WaitStatus, error) {
	procs.Lock()
	defer procs.Unlock()
	if err := cmd.Start(); err != nil {
		return 0, nil, err
	}
	pid := cmd.Process.Pid
	ch := make(chan unix.WaitStatus, 1)
	procs.m[pid] = ch
	cmd.Process.Release()
	return pid, ch, nil
}

func exitCode(ws unix.WaitStatus) int {
	if ws.Signaled() {
		return 128 + int(ws.Signal())
	}
	return ws.ExitStatus()
}

// pipeline runs cmds connected stdout→stdin; a nil stdout folds the last command's output into the error text.
func pipeline(stdin, stdout *os.File, cmds ...*exec.Cmd) error {
	er, ew, err := os.Pipe()
	if err != nil {
		return err
	}
	defer er.Close()
	var waits []<-chan unix.WaitStatus
	in := stdin
	for i, cmd := range cmds {
		var next, out *os.File
		if i < len(cmds)-1 {
			if next, out, err = os.Pipe(); err != nil {
				break
			}
		} else if out = stdout; out == nil {
			out = ew
		}
		if in != nil {
			cmd.Stdin = in
		}
		cmd.Stdout, cmd.Stderr = out, ew
		_, wait, serr := spawn(cmd)
		if in != stdin {
			in.Close()
		}
		if out != stdout && out != ew {
			out.Close()
		}
		if serr != nil {
			err = serr
			if next != nil {
				next.Close()
			}
			break
		}
		waits = append(waits, wait)
		in = next
	}
	ew.Close()
	msg, _ := io.ReadAll(io.LimitReader(er, 4096))
	io.Copy(io.Discard, er)
	for i, wait := range waits {
		if code := exitCode(<-wait); code != 0 && err == nil {
			err = fmt.Errorf("%s: exit %d: %s", cmds[i].Args[0], code, bytes.TrimSpace(msg))
		}
	}
	return err
}
