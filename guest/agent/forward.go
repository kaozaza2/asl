package main

import (
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"strconv"
	"time"

	"golang.org/x/sys/unix"
)

// chunkReader decodes the host→guest framing `[u32 len][bytes]...` that ends with a zero-length chunk.
type chunkReader struct {
	r    io.Reader
	left uint32
	eof  bool
}

func (c *chunkReader) Read(p []byte) (int, error) {
	if c.eof {
		return 0, io.EOF
	}
	if c.left == 0 {
		var hdr [4]byte
		if _, err := io.ReadFull(c.r, hdr[:]); err != nil {
			return 0, fmt.Errorf("chunk header: %w", io.ErrUnexpectedEOF)
		}
		if c.left = binary.BigEndian.Uint32(hdr[:]); c.left == 0 {
			c.eof = true
			return 0, io.EOF
		}
	}
	if uint32(len(p)) > c.left {
		p = p[:c.left]
	}
	n, err := c.r.Read(p)
	c.left -= uint32(n)
	if err == io.EOF {
		err = io.ErrUnexpectedEOF
	}
	return n, err
}

func (a *agent) forward(c *conn, raw []byte) error {
	var h struct{ Port int }
	if err := json.Unmarshal(raw, &h); err != nil {
		return err
	}
	if h.Port < 1 || h.Port > 65535 {
		return fmt.Errorf("invalid port %d", h.Port)
	}
	var tcp net.Conn
	var err error
	for _, host := range []string{"127.0.0.1", "::1"} {
		if tcp, err = net.DialTimeout("tcp", net.JoinHostPort(host, strconv.Itoa(h.Port)), 5*time.Second); err == nil {
			break
		}
	}
	if err != nil {
		return err
	}
	defer tcp.Close()
	if err := c.reply(obj{"ok": true}); err != nil {
		return err
	}
	done := make(chan struct{})
	go func() {
		defer close(done)
		if _, err := io.Copy(struct{ io.Writer }{c}, tcp); err != nil {
			c.shutdown(unix.SHUT_RDWR)
			return
		}
		c.shutdown(unix.SHUT_WR)
	}()
	if _, err := io.Copy(tcp, &chunkReader{r: c}); err != nil {
		tcp.Close()
	} else {
		tcp.(*net.TCPConn).CloseWrite()
	}
	<-done
	return nil
}
