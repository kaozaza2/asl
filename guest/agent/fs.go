package main

import (
	"bufio"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path"
	"strconv"
	"strings"

	"golang.org/x/sys/unix"
)

const (
	fileRead = iota
	fileWrite
	fileSize
	fileFsync
	fileTruncate
)

var fileModes = map[string]int{"r": unix.O_RDONLY, "w": unix.O_WRONLY, "rw": unix.O_RDWR}

type entry struct {
	Name   string `json:"name"`
	Type   string `json:"type"`
	Target string `json:"target,omitempty"`
	Size   int64  `json:"size"`
	Mtime  int64  `json:"mtime"`
	Mode   uint32 `json:"mode"`
}

// jail is an O_PATH fd on a distro root; every lookup goes through openat2(RESOLVE_IN_ROOT).
type jail struct {
	fd       int
	uid, gid int
}

func (a *agent) jail(id string) (*jail, error) {
	d, err := a.mounted(id)
	if err != nil {
		return nil, err
	}
	fd, err := unix.Open(d.root(), unix.O_PATH|unix.O_DIRECTORY|unix.O_CLOEXEC, 0)
	if err != nil {
		return nil, err
	}
	j := &jail{fd: fd}
	if p := j.passwd(0, d.defaultUser()); p != nil {
		j.uid, _ = strconv.Atoi(p[2])
		j.gid, _ = strconv.Atoi(p[3])
	}
	return j, nil
}

// passwd returns the first /etc/passwd entry whose field (0 name, 2 uid) equals value.
func (j *jail) passwd(field int, value string) []string {
	f, err := j.open("/etc/passwd", unix.O_RDONLY, 0)
	if err != nil {
		return nil
	}
	defer f.Close()
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		if p := strings.Split(sc.Text(), ":"); len(p) >= 4 && p[field] == value {
			return p
		}
	}
	return nil
}

func (j *jail) open(p string, flags int, mode uint32) (*os.File, error) {
	how := unix.OpenHow{Flags: uint64(flags | unix.O_CLOEXEC), Resolve: unix.RESOLVE_IN_ROOT}
	if flags&unix.O_CREAT != 0 {
		how.Mode = uint64(mode)
	}
	fd, err := unix.Openat2(j.fd, p, &how)
	if err != nil {
		return nil, &os.PathError{Op: "open", Path: p, Err: err}
	}
	return os.NewFile(uintptr(fd), p), nil
}

// parent opens the directory containing p; the root itself yields the root fd and an empty name.
func (j *jail) parent(p string) (*os.File, string, error) {
	p = path.Clean("/" + p)
	if p == "/" {
		root, err := j.open("/", unix.O_PATH|unix.O_DIRECTORY, 0)
		return root, "", err
	}
	dir, err := j.open(path.Dir(p), unix.O_PATH|unix.O_DIRECTORY, 0)
	return dir, path.Base(p), err
}

func fileType(mode uint32) string {
	switch mode & unix.S_IFMT {
	case unix.S_IFREG:
		return "file"
	case unix.S_IFDIR:
		return "dir"
	case unix.S_IFLNK:
		return "link"
	}
	return "other"
}

// stat lstat's name in dir; p is its full path, used to classify what a symlink resolves to.
func (j *jail) stat(dir *os.File, name, p string) (entry, error) {
	var st unix.Stat_t
	flags := unix.AT_SYMLINK_NOFOLLOW
	if name == "" {
		flags |= unix.AT_EMPTY_PATH
	}
	if err := unix.Fstatat(int(dir.Fd()), name, &st, flags); err != nil {
		return entry{}, &os.PathError{Op: "stat", Path: p, Err: err}
	}
	e := entry{Name: name, Type: fileType(st.Mode), Size: st.Size, Mtime: st.Mtim.Sec, Mode: st.Mode & 0o7777}
	if name == "" {
		e.Name = "/"
	}
	if e.Type == "link" {
		e.Target = "other"
		if f, err := j.open(p, unix.O_PATH, 0); err == nil {
			var ts unix.Stat_t
			if unix.Fstat(int(f.Fd()), &ts) == nil && fileType(ts.Mode) != "link" {
				e.Target = fileType(ts.Mode)
			}
			f.Close()
		}
	}
	return e, nil
}

func (j *jail) mkdir(p string, recursive bool) error {
	dir, name, err := j.parent(p)
	if errors.Is(err, unix.ENOENT) && recursive {
		if err = j.mkdir(path.Dir(path.Clean("/"+p)), true); err == nil {
			dir, name, err = j.parent(p)
		}
	}
	if err != nil {
		return err
	}
	defer dir.Close()
	if name == "" {
		return nil
	}
	err = unix.Mkdirat(int(dir.Fd()), name, 0o755)
	if err == unix.EEXIST && recursive {
		return nil
	}
	if err != nil {
		return &os.PathError{Op: "mkdir", Path: p, Err: err}
	}
	return unix.Fchownat(int(dir.Fd()), name, j.uid, j.gid, unix.AT_SYMLINK_NOFOLLOW)
}

func removeAt(dirfd int, name string, recursive bool) error {
	err := unix.Unlinkat(dirfd, name, 0)
	if err != unix.EISDIR {
		return err
	}
	if recursive {
		fd, err := unix.Openat(dirfd, name, unix.O_RDONLY|unix.O_DIRECTORY|unix.O_NOFOLLOW|unix.O_CLOEXEC, 0)
		if err != nil {
			return err
		}
		f := os.NewFile(uintptr(fd), name)
		names, err := f.Readdirnames(-1)
		for _, n := range names {
			if err == nil {
				err = removeAt(fd, n, true)
			}
		}
		f.Close()
		if err != nil {
			return err
		}
	}
	return unix.Unlinkat(dirfd, name, unix.AT_REMOVEDIR)
}

func (a *agent) fs(c *conn, raw []byte) error {
	var h struct {
		Distro, Op, Path, To string
		Recursive            bool
	}
	if err := json.Unmarshal(raw, &h); err != nil {
		return err
	}
	j, err := a.jail(h.Distro)
	if err != nil {
		return err
	}
	defer unix.Close(j.fd)
	p := path.Clean("/" + h.Path)
	switch h.Op {
	case "list":
		d, err := j.open(p, unix.O_RDONLY|unix.O_DIRECTORY, 0)
		if err != nil {
			return err
		}
		defer d.Close()
		names, err := d.Readdirnames(-1)
		if err != nil {
			return err
		}
		entries := []entry{}
		for _, n := range names {
			if e, err := j.stat(d, n, path.Join(p, n)); err == nil {
				entries = append(entries, e)
			}
		}
		return c.reply(obj{"ok": true, "entries": entries})
	case "stat":
		dir, name, err := j.parent(p)
		if err != nil {
			return err
		}
		defer dir.Close()
		e, err := j.stat(dir, name, p)
		if err != nil {
			return err
		}
		return c.reply(obj{"ok": true, "entry": e})
	case "mkdir":
		err = j.mkdir(p, h.Recursive)
	case "remove", "rename":
		dir, name, err := j.parent(p)
		if err != nil {
			return err
		}
		defer dir.Close()
		if name == "" {
			return errors.New("refusing to modify the distro root")
		}
		if h.Op == "remove" {
			if err := removeAt(int(dir.Fd()), name, h.Recursive); err != nil {
				return &os.PathError{Op: "remove", Path: p, Err: err}
			}
			break
		}
		to, toName, err := j.parent(h.To)
		if err != nil {
			return err
		}
		defer to.Close()
		if toName == "" {
			return errors.New("invalid rename target")
		}
		if err := unix.Renameat(int(dir.Fd()), name, int(to.Fd()), toName); err != nil {
			return &os.LinkError{Op: "rename", Old: p, New: h.To, Err: err}
		}
	default:
		return fmt.Errorf("unknown fs op %q", h.Op)
	}
	if err != nil {
		return err
	}
	return c.reply(obj{"ok": true})
}

func (a *agent) file(c *conn, raw []byte) error {
	var h struct {
		Distro, Path, Mode string
		Create, Truncate   bool
	}
	if err := json.Unmarshal(raw, &h); err != nil {
		return err
	}
	flags, ok := fileModes[h.Mode]
	if !ok {
		return fmt.Errorf("invalid mode %q", h.Mode)
	}
	if h.Truncate {
		flags |= unix.O_TRUNC
	}
	j, err := a.jail(h.Distro)
	if err != nil {
		return err
	}
	defer unix.Close(j.fd)
	var f *os.File
	if h.Create {
		if f, err = j.open(h.Path, flags|unix.O_CREAT|unix.O_EXCL, 0o644); err == nil {
			f.Chown(j.uid, j.gid)
		}
	}
	if f == nil && (!h.Create || errors.Is(err, unix.EEXIST)) {
		f, err = j.open(h.Path, flags, 0)
	}
	if err != nil {
		return err
	}
	defer f.Close()
	st, err := f.Stat()
	if err != nil {
		return err
	}
	if st.IsDir() {
		return &os.PathError{Op: "open", Path: h.Path, Err: unix.EISDIR}
	}
	if err := c.reply(obj{"ok": true, "size": st.Size()}); err != nil {
		return err
	}

	br := bufio.NewReader(c)
	bw := bufio.NewWriter(c)
	req := make([]byte, 13)
	var buf []byte
	for {
		if _, err := io.ReadFull(br, req); err != nil {
			if err == io.EOF {
				return nil
			}
			return err
		}
		off := int64(binary.BigEndian.Uint64(req[1:]))
		n := binary.BigEndian.Uint32(req[9:])
		if n > 64<<20 {
			return fmt.Errorf("request of %d bytes too large", n)
		}
		if uint32(cap(buf)) < n {
			buf = make([]byte, n)
		}
		buf = buf[:n]
		var result int64
		var out []byte
		var oerr error
		switch req[0] {
		case fileRead:
			var got int
			got, oerr = f.ReadAt(buf, off)
			if oerr == io.EOF {
				oerr = nil
			}
			result, out = int64(got), buf[:got]
		case fileWrite:
			if _, err := io.ReadFull(br, buf); err != nil {
				return err
			}
			var put int
			put, oerr = f.WriteAt(buf, off)
			result = int64(put)
		case fileSize:
			var st os.FileInfo
			if st, oerr = f.Stat(); oerr == nil {
				out = binary.BigEndian.AppendUint64(nil, uint64(st.Size()))
			}
		case fileFsync:
			oerr = f.Sync()
		case fileTruncate:
			oerr = f.Truncate(off)
		default:
			return fmt.Errorf("unknown file op %d", req[0])
		}
		if oerr != nil {
			var errno unix.Errno
			if !errors.As(oerr, &errno) {
				errno = unix.EIO
			}
			result, out = -int64(errno), nil
		}
		resp := binary.BigEndian.AppendUint32(nil, uint32(int32(result)))
		resp = binary.BigEndian.AppendUint32(resp, uint32(len(out)))
		bw.Write(resp)
		bw.Write(out)
		if err := bw.Flush(); err != nil {
			return err
		}
	}
}
