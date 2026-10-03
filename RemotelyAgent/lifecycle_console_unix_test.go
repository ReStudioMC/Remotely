//go:build linux || darwin || freebsd

package main

import (
	"bufio"
	"bytes"
	"io"
	"net"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"syscall"
	"testing"
	"time"
)

func TestLifecycleConsoleReconnectKeepsFirstInputAndDropsDisconnectedInput(t *testing.T) {
	root := t.TempDir()
	socket := filepath.Join(root, "console.sock")
	log := filepath.Join(root, "console.log")
	history := bytes.Repeat([]byte("\x1b[31mHéllo\r\x1b[0m\n"), 20_000)
	if err := os.WriteFile(log, history, 0600); err != nil {
		t.Fatal(err)
	}
	listener, connections := listenConsole(t, socket)
	defer listener.Close()
	input, keyboard := io.Pipe()
	defer input.Close()
	defer keyboard.Close()
	output := &consoleOutput{changed: make(chan struct{}, 1)}
	changes := make(chan os.Signal, 4)
	finished := make(chan error, 1)
	go func() { finished <- lifecycleConsoleStream(input, output, socket, log, changes) }()
	defer func() { changes <- syscall.SIGTERM }()

	first := nextConsole(t, connections)
	defer first.Close()
	if _, err := first.Write([]byte("first> ")); err != nil {
		t.Fatal(err)
	}
	waitConsoleOutput(t, output, "first> ")
	if _, err := keyboard.Write([]byte("s")); err != nil {
		t.Fatal(err)
	}
	readConsoleInput(t, first, []byte("s"))

	listener.Close()
	first.Close()
	waitConsoleOutput(t, output, "Server Closed")
	if _, err := keyboard.Write([]byte("discard while disconnected")); err != nil {
		t.Fatal(err)
	}
	listener, connections = listenConsole(t, socket)
	defer listener.Close()
	second := nextConsole(t, connections)
	defer second.Close()
	if _, err := second.Write([]byte("second> ")); err != nil {
		t.Fatal(err)
	}
	waitConsoleOutput(t, output, "second> ")
	if _, err := keyboard.Write([]byte("a")); err != nil {
		t.Fatal(err)
	}
	readConsoleInput(t, second, []byte("a"))
	payload := []byte("say Héllo\x1b[D\x7f\r")
	for _, value := range payload {
		if _, err := keyboard.Write([]byte{value}); err != nil {
			t.Fatal(err)
		}
	}
	readConsoleInput(t, second, payload)
	output.lock.Lock()
	replays := bytes.Count(output.data.Bytes(), history)
	output.lock.Unlock()
	if replays != 2 {
		t.Fatalf("complete ANSI/UTF8 history replays = %d, want 2", replays)
	}
	keyboard.Close()
	select {
	case err := <-finished:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("console did not terminate after input EOF")
	}
}

func TestLifecycleConsoleHistoryStopsAtCapturedSizeWhileLogGrows(t *testing.T) {
	root := t.TempDir()
	socket := filepath.Join(root, "console.sock")
	log := filepath.Join(root, "console.log")
	history := bytes.Repeat([]byte("\x1b[32mBefore Héllo\r\x1b[0m\n"), 4_000)
	if err := os.WriteFile(log, history, 0600); err != nil {
		t.Fatal(err)
	}
	growing, err := os.OpenFile(log, os.O_WRONLY|os.O_APPEND, 0600)
	if err != nil {
		t.Fatal(err)
	}
	defer growing.Close()
	appended := []byte("Appended During Replay\r\n")
	capture := &consoleOutput{changed: make(chan struct{}, 1)}
	output := &growingConsoleOutput{consoleOutput: capture, log: growing, appended: appended}
	listener, connections := listenConsole(t, socket)
	defer listener.Close()
	input, keyboard := io.Pipe()
	defer input.Close()
	defer keyboard.Close()
	changes := make(chan os.Signal, 4)
	finished := make(chan error, 1)
	go func() { finished <- lifecycleConsoleStream(input, output, socket, log, changes) }()
	defer func() { changes <- syscall.SIGTERM }()

	conn := nextConsole(t, connections)
	defer conn.Close()
	capture.lock.Lock()
	actual := append([]byte(nil), capture.data.Bytes()...)
	capture.lock.Unlock()
	if !bytes.Equal(actual, history) {
		t.Fatal("history replay included data appended after its captured size")
	}
	stored, err := os.ReadFile(log)
	if err != nil || !bytes.Equal(stored, append(append([]byte(nil), history...), appended...)) {
		t.Fatalf("live log append was not preserved: %v", err)
	}
	keyboard.Close()
	select {
	case err := <-finished:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("console did not finish after captured replay and input EOF")
	}
}

func TestLifecycleConsoleShortenedHistoryStillAttaches(t *testing.T) {
	root := t.TempDir()
	socket := filepath.Join(root, "console.sock")
	log := filepath.Join(root, "console.log")
	history := bytes.Repeat([]byte("\x1b[32mBefore Héllo\r\x1b[0m\n"), 4_000)
	if err := os.WriteFile(log, history, 0600); err != nil {
		t.Fatal(err)
	}
	changing, err := os.OpenFile(log, os.O_WRONLY, 0600)
	if err != nil {
		t.Fatal(err)
	}
	defer changing.Close()
	capture := &consoleOutput{changed: make(chan struct{}, 1)}
	output := &growingConsoleOutput{consoleOutput: capture, log: changing, truncate: true}
	listener, connections := listenConsole(t, socket)
	defer listener.Close()
	input, keyboard := io.Pipe()
	defer input.Close()
	defer keyboard.Close()
	changes := make(chan os.Signal, 4)
	finished := make(chan error, 1)
	go func() { finished <- lifecycleConsoleStream(input, output, socket, log, changes) }()
	defer func() { changes <- syscall.SIGTERM }()

	conn := nextConsole(t, connections)
	defer conn.Close()
	capture.lock.Lock()
	actual := append([]byte(nil), capture.data.Bytes()...)
	capture.lock.Unlock()
	if len(actual) == 0 || len(actual) >= len(history) || !bytes.Equal(actual, history[:len(actual)]) {
		t.Fatal("shortened history did not preserve its available original bytes")
	}
	if _, err := conn.Write([]byte("live> ")); err != nil {
		t.Fatal(err)
	}
	waitConsoleOutput(t, capture, "live> ")
	keyboard.Close()
	select {
	case err := <-finished:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("console did not finish after shortened history and input EOF")
	}
}

func TestLifecycleConsoleHistoryOutputEOFRemainsFatal(t *testing.T) {
	root := t.TempDir()
	socket := filepath.Join(root, "console.sock")
	log := filepath.Join(root, "console.log")
	if err := os.WriteFile(log, []byte("history"), 0600); err != nil {
		t.Fatal(err)
	}
	listener, _ := listenConsole(t, socket)
	defer listener.Close()
	input, keyboard := io.Pipe()
	defer input.Close()
	defer keyboard.Close()
	output := &growingConsoleOutput{writeFailure: io.EOF}
	changes := make(chan os.Signal, 4)
	finished := make(chan error, 1)
	go func() { finished <- lifecycleConsoleStream(input, output, socket, log, changes) }()
	defer func() { changes <- syscall.SIGTERM }()
	select {
	case err := <-finished:
		if err != io.EOF {
			t.Fatalf("history output failure = %v, want EOF", err)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("history output failure did not terminate console")
	}
}

type growingConsoleOutput struct {
	*consoleOutput
	log          *os.File
	appended     []byte
	truncate     bool
	writeFailure error
	once         sync.Once
	failure      error
}

func (output *growingConsoleOutput) Write(data []byte) (int, error) {
	if output.writeFailure != nil {
		return 0, output.writeFailure
	}
	output.once.Do(func() {
		if output.truncate {
			output.failure = output.log.Truncate(0)
		} else {
			_, output.failure = output.log.Write(output.appended)
		}
	})
	if output.failure != nil {
		return 0, output.failure
	}
	return output.consoleOutput.Write(data)
}

func TestLifecycleConsoleStopsOnEOFAndSignalWhileDisconnected(t *testing.T) {
	for _, stop := range []string{"eof", "signal"} {
		t.Run(stop, func(t *testing.T) {
			input, keyboard := io.Pipe()
			defer input.Close()
			defer keyboard.Close()
			output := &consoleOutput{changed: make(chan struct{}, 1)}
			changes := make(chan os.Signal, 4)
			finished := make(chan error, 1)
			socket := filepath.Join(t.TempDir(), "missing.sock")
			go func() { finished <- lifecycleConsoleStream(input, output, socket, "", changes) }()
			waitConsoleOutput(t, output, "Waiting For Start")
			if stop == "eof" {
				keyboard.Close()
			} else {
				changes <- syscall.SIGHUP
			}
			select {
			case err := <-finished:
				if err != nil {
					t.Fatal(err)
				}
			case <-time.After(3 * time.Second):
				t.Fatal("disconnected console did not terminate")
			}
		})
	}
}

func listenConsole(t *testing.T, socket string) (net.Listener, <-chan net.Conn) {
	t.Helper()
	listener, err := net.Listen("unix", socket)
	if err != nil {
		t.Fatal(err)
	}
	connections := make(chan net.Conn, 4)
	go func() {
		for {
			conn, err := listener.Accept()
			if err != nil {
				return
			}
			conn.SetReadDeadline(time.Now().Add(3 * time.Second))
			line, err := bufio.NewReader(conn).ReadString('\n')
			if err != nil {
				conn.Close()
				continue
			}
			if strings.HasPrefix(line, "RESIZE ") {
				conn.Write([]byte("OK\n"))
				conn.Close()
			} else if line == "ATTACH\n" {
				conn.SetReadDeadline(time.Time{})
				connections <- conn
			} else {
				conn.Close()
			}
		}
	}()
	return listener, connections
}

func nextConsole(t *testing.T, connections <-chan net.Conn) net.Conn {
	t.Helper()
	select {
	case conn := <-connections:
		return conn
	case <-time.After(3 * time.Second):
		t.Fatal("console did not attach")
		return nil
	}
}

func readConsoleInput(t *testing.T, conn net.Conn, expected []byte) {
	t.Helper()
	conn.SetReadDeadline(time.Now().Add(3 * time.Second))
	actual := make([]byte, len(expected))
	if _, err := io.ReadFull(conn, actual); err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(actual, expected) {
		t.Fatalf("input = %q, want %q", actual, expected)
	}
}

type consoleOutput struct {
	lock    sync.Mutex
	data    bytes.Buffer
	changed chan struct{}
}

func (output *consoleOutput) Write(data []byte) (int, error) {
	output.lock.Lock()
	n, err := output.data.Write(data)
	output.lock.Unlock()
	select {
	case output.changed <- struct{}{}:
	default:
	}
	return n, err
}

func waitConsoleOutput(t *testing.T, output *consoleOutput, text string) {
	t.Helper()
	timer := time.NewTimer(3 * time.Second)
	defer timer.Stop()
	for {
		output.lock.Lock()
		found := strings.Contains(output.data.String(), text)
		output.lock.Unlock()
		if found {
			return
		}
		select {
		case <-output.changed:
		case <-timer.C:
			t.Fatalf("console output does not contain %q", text)
		}
	}
}
