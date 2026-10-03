//go:build linux || darwin || freebsd

package main

import (
	"bufio"
	"crypto/sha1"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"net"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/creack/pty"
	"golang.org/x/term"
)

type lifecyclePaths struct {
	RunDir        string
	SocketDir     string
	SocketPath    string
	LogPath       string
	StatusPath    string
	SupervisorPid string
	ServerPid     string
	ExitCode      string
}

type lifecycleStatus struct {
	Status        string `json:"status"`
	Running       bool   `json:"running"`
	SupervisorPid string `json:"supervisorPid"`
	ServerPid     string `json:"serverPid"`
	ExitCode      string `json:"exitCode"`
	SocketPath    string `json:"socketPath"`
}

func cmdLifecycleStart(args []string) {
	fs := flag.NewFlagSet("lifecycle-start", flag.ExitOnError)
	dir := fs.String("dir", "", "Instance directory")
	command := fs.String("command", "", "Startup command")
	_ = fs.Parse(args)
	if *dir == "" || *command == "" {
		fmt.Fprintln(os.Stderr, "lifecycle-start: --dir and --command are required")
		os.Exit(2)
	}
	if err := lifecycleStart(*dir, *command); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

func cmdLifecycleStop(args []string) {
	fs := flag.NewFlagSet("lifecycle-stop", flag.ExitOnError)
	dir := fs.String("dir", "", "Instance directory")
	timeout := fs.Int("timeout", 180, "Grace timeout seconds")
	_ = fs.Parse(args)
	if *dir == "" {
		fmt.Fprintln(os.Stderr, "lifecycle-stop: --dir is required")
		os.Exit(2)
	}
	if err := lifecycleStop(*dir, time.Duration(*timeout)*time.Second); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

func cmdLifecycleSend(args []string) {
	fs := flag.NewFlagSet("lifecycle-send", flag.ExitOnError)
	dir := fs.String("dir", "", "Instance directory")
	command := fs.String("command", "", "Command")
	_ = fs.Parse(args)
	if *dir == "" || *command == "" {
		fmt.Fprintln(os.Stderr, "lifecycle-send: --dir and --command are required")
		os.Exit(2)
	}
	if err := lifecycleSend(*dir, *command); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

func cmdLifecycleStatus(args []string) {
	fs := flag.NewFlagSet("lifecycle-status", flag.ExitOnError)
	dir := fs.String("dir", "", "Instance directory")
	_ = fs.Parse(args)
	if *dir == "" {
		fmt.Fprintln(os.Stderr, "lifecycle-status: --dir is required")
		os.Exit(2)
	}
	status := lifecycleReadStatus(*dir)
	data, _ := json.Marshal(status)
	fmt.Println(string(data))
}

func cmdLifecycleConsole(args []string) {
	fs := flag.NewFlagSet("lifecycle-console", flag.ExitOnError)
	dir := fs.String("dir", "", "Instance directory")
	_ = fs.Parse(args)
	if *dir == "" {
		fmt.Fprintln(os.Stderr, "lifecycle-console: --dir is required")
		os.Exit(2)
	}
	if err := lifecycleConsole(*dir); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

func cmdLifecycleSupervise(args []string) {
	fs := flag.NewFlagSet("lifecycle-supervise", flag.ExitOnError)
	dir := fs.String("dir", "", "Instance directory")
	command := fs.String("command", "", "Startup command")
	_ = fs.Parse(args)
	if *dir == "" || *command == "" {
		os.Exit(2)
	}
	os.Exit(lifecycleSupervise(*dir, *command))
}

func lifecycleStart(dir string, command string) error {
	paths, err := lifecycleResolvePaths(dir)
	if err != nil {
		return err
	}
	if lifecycleSocketReady(paths.SocketPath, 300*time.Millisecond) {
		fmt.Println("RUNNING")
		return nil
	}
	_ = os.Remove(paths.SocketPath)
	if err := os.MkdirAll(paths.RunDir, 0755); err != nil {
		return err
	}
	if err := os.MkdirAll(paths.SocketDir, 0700); err != nil {
		return err
	}
	_ = os.Remove(paths.ExitCode)
	_ = os.WriteFile(paths.LogPath, nil, 0644)
	_ = os.WriteFile(paths.StatusPath, []byte("starting"), 0644)

	exe, err := os.Executable()
	if err != nil {
		return err
	}
	cmd := exec.Command(exe, "lifecycle-supervise", "--dir", dir, "--command", command)
	cmd.Stdout = nil
	cmd.Stderr = nil
	cmd.Stdin = nil
	cmd.SysProcAttr = &syscall.SysProcAttr{Setsid: true}
	if err := cmd.Start(); err != nil {
		return err
	}
	_ = os.WriteFile(paths.SupervisorPid, []byte(strconv.Itoa(cmd.Process.Pid)), 0644)
	_ = cmd.Process.Release()

	deadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		if lifecycleSocketReady(paths.SocketPath, 200*time.Millisecond) {
			fmt.Println("STARTED")
			return nil
		}
		if code := readTrim(paths.ExitCode); code != "" {
			_ = os.WriteFile(paths.StatusPath, []byte("crashed"), 0644)
			return fmt.Errorf("START_FAILED exit=%s", code)
		}
		time.Sleep(100 * time.Millisecond)
	}
	_ = os.WriteFile(paths.StatusPath, []byte("crashed"), 0644)
	return errors.New("START_FAILED socket timeout")
}

func lifecycleStop(dir string, timeout time.Duration) error {
	paths, err := lifecycleResolvePaths(dir)
	if err != nil {
		return err
	}
	if !lifecycleSocketReady(paths.SocketPath, 300*time.Millisecond) {
		fmt.Println("STOPPED")
		return nil
	}
	_, _ = lifecycleControl(paths.SocketPath, "STOP\n", 2*time.Second)
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if !lifecycleSocketReady(paths.SocketPath, 200*time.Millisecond) {
			fmt.Println("STOPPED")
			return nil
		}
		time.Sleep(time.Second)
	}
	killPidFile(paths.ServerPid)
	killPidFile(paths.SupervisorPid)
	fmt.Println("KILLED")
	return nil
}

func lifecycleSend(dir string, command string) error {
	paths, err := lifecycleResolvePaths(dir)
	if err != nil {
		return err
	}
	payload := base64.StdEncoding.EncodeToString([]byte(command))
	out, err := lifecycleControl(paths.SocketPath, "SEND "+payload+"\n", 3*time.Second)
	if err != nil {
		return err
	}
	fmt.Println(strings.TrimSpace(out))
	return nil
}

func lifecycleReadStatus(dir string) lifecycleStatus {
	paths, err := lifecycleResolvePaths(dir)
	if err != nil {
		return lifecycleStatus{Status: "stopped", Running: false}
	}
	running := lifecycleSocketReady(paths.SocketPath, 100*time.Millisecond)
	status := readTrim(paths.StatusPath)
	if status == "" {
		status = "stopped"
	}
	if !running && (status == "starting" || status == "running" || status == "stopping") {
		status = "stopped"
	}
	return lifecycleStatus{
		Status:        status,
		Running:       running,
		SupervisorPid: readTrim(paths.SupervisorPid),
		ServerPid:     readTrim(paths.ServerPid),
		ExitCode:      readTrim(paths.ExitCode),
		SocketPath:    paths.SocketPath,
	}
}

func lifecycleConsole(dir string) error {
	paths, err := lifecycleResolvePaths(dir)
	if err != nil {
		return err
	}
	defer os.Stdin.Close()
	oldState, rawErr := term.MakeRaw(int(os.Stdin.Fd()))
	if rawErr == nil {
		defer term.Restore(int(os.Stdin.Fd()), oldState)
	}
	_ = pty.InheritSize(os.Stdin, os.Stdout)
	changes := make(chan os.Signal, 4)
	signal.Notify(changes, syscall.SIGWINCH, syscall.SIGTERM, syscall.SIGHUP)
	defer signal.Stop(changes)
	return lifecycleConsoleStream(os.Stdin, os.Stdout, paths.SocketPath, paths.LogPath, changes)
}

type lifecycleInput struct {
	generation uint64
	data       []byte
	err        error
}

func lifecycleConsoleStream(input io.Reader, output io.Writer, socket string, log string, changes <-chan os.Signal) error {
	packets := make(chan lifecycleInput, 16)
	stopped := make(chan struct{})
	defer close(stopped)
	var active atomic.Uint64
	go func() {
		buffer := make([]byte, 8192)
		for {
			n, err := input.Read(buffer)
			generation := active.Load()
			if n > 0 && generation != 0 {
				packet := lifecycleInput{generation: generation, data: append([]byte(nil), buffer[:n]...)}
				select {
				case packets <- packet:
				case <-stopped:
					return
				}
			}
			if err != nil {
				select {
				case packets <- lifecycleInput{err: err}:
				case <-stopped:
				}
				return
			}
			select {
			case <-stopped:
				return
			default:
			}
		}
	}()
	generation := uint64(0)
	announced := false
	for {
		conn, err := net.DialTimeout("unix", socket, 500*time.Millisecond)
		if err != nil {
			if !announced {
				if _, err := fmt.Fprint(output, "\r\nServer Not Running\r\nWaiting For Start\r\n"); err != nil {
					return err
				}
				announced = true
			}
			timer := time.NewTimer(500 * time.Millisecond)
			waiting := true
			for waiting {
				select {
				case packet := <-packets:
					if packet.err != nil {
						timer.Stop()
						if errors.Is(packet.err, io.EOF) {
							return nil
						}
						return packet.err
					}
				case change := <-changes:
					if change != syscall.SIGWINCH {
						timer.Stop()
						return nil
					}
				case <-timer.C:
					waiting = false
				}
			}
			continue
		}
		announced = false
		if history, readErr := os.Open(log); readErr == nil {
			stamp, statErr := history.Stat()
			if statErr != nil {
				_ = history.Close()
				_ = conn.Close()
				return statErr
			}
			_, replayErr := io.Copy(output, io.LimitReader(history, stamp.Size()))
			_ = history.Close()
			if replayErr != nil {
				_ = conn.Close()
				return replayErr
			}
		}
		if _, err := conn.Write([]byte("ATTACH\n")); err != nil {
			_ = conn.Close()
			continue
		}
		generation++
		active.Store(generation)
		_ = lifecycleResize(socket)
		done := make(chan struct{})
		go func() {
			_, _ = io.Copy(output, conn)
			_ = conn.Close()
			close(done)
		}()
		var inputErr error
		attached := true
		for attached {
			select {
			case packet := <-packets:
				if packet.err != nil {
					inputErr = packet.err
					attached = false
				} else if packet.generation == generation {
					_ = conn.SetWriteDeadline(time.Now().Add(5 * time.Second))
					written, err := conn.Write(packet.data)
					if err != nil || written != len(packet.data) {
						attached = false
					}
				}
			case change := <-changes:
				if change == syscall.SIGWINCH {
					_ = lifecycleResize(socket)
				}
				if change != syscall.SIGWINCH {
					inputErr = io.EOF
					attached = false
				}
			case <-done:
				attached = false
			}
		}
		active.Store(0)
		_ = conn.Close()
		<-done
		if inputErr != nil {
			if errors.Is(inputErr, io.EOF) {
				return nil
			}
			return inputErr
		}
		if _, err := fmt.Fprint(output, "\r\nServer Closed\r\nWaiting For Start\r\n"); err != nil {
			return err
		}
		announced = true
	}
}

func lifecycleSupervise(dir string, command string) int {
	paths, err := lifecycleResolvePaths(dir)
	if err != nil {
		return 2
	}
	_ = os.MkdirAll(paths.RunDir, 0755)
	_ = os.MkdirAll(paths.SocketDir, 0700)
	_ = os.Remove(paths.SocketPath)
	logFile, err := os.OpenFile(paths.LogPath, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0644)
	if err != nil {
		return 1
	}
	defer logFile.Close()

	cmd := exec.Command("bash", "-lc", command)
	cmd.Dir = dir
	ptmx, err := pty.StartWithSize(cmd, &pty.Winsize{Rows: 24, Cols: 80})
	if err != nil {
		_, _ = fmt.Fprintf(logFile, "failed to start: %v\n", err)
		return 1
	}
	defer ptmx.Close()
	_ = os.WriteFile(paths.ServerPid, []byte(strconv.Itoa(cmd.Process.Pid)), 0644)
	_ = os.WriteFile(paths.StatusPath, []byte("running"), 0644)

	listener, err := net.Listen("unix", paths.SocketPath)
	if err != nil {
		_, _ = fmt.Fprintf(logFile, "failed to listen: %v\n", err)
		_ = cmd.Process.Kill()
		return 1
	}
	defer listener.Close()
	_ = os.Chmod(paths.SocketPath, 0600)

	var clientsMu sync.Mutex
	clients := map[net.Conn]struct{}{}
	removeClient := func(conn net.Conn) {
		clientsMu.Lock()
		delete(clients, conn)
		clientsMu.Unlock()
		_ = conn.Close()
	}
	go func() {
		buf := make([]byte, 8192)
		for {
			n, readErr := ptmx.Read(buf)
			if n > 0 {
				data := append([]byte(nil), buf[:n]...)
				_, _ = logFile.Write(data)
				clientsMu.Lock()
				for conn := range clients {
					if _, err := conn.Write(data); err != nil {
						_ = conn.Close()
						delete(clients, conn)
					}
				}
				clientsMu.Unlock()
			}
			if readErr != nil {
				return
			}
		}
	}()
	go func() {
		for {
			conn, acceptErr := listener.Accept()
			if acceptErr != nil {
				return
			}
			go lifecycleHandleClient(conn, ptmx, &clientsMu, clients, removeClient, paths)
		}
	}()

	waitErr := cmd.Wait()
	exitCode := 0
	if waitErr != nil {
		if exitErr, ok := waitErr.(*exec.ExitError); ok {
			exitCode = exitErr.ExitCode()
		} else {
			exitCode = 1
		}
	}
	_ = os.Remove(paths.SocketPath)
	_ = os.WriteFile(paths.ExitCode, []byte(strconv.Itoa(exitCode)), 0644)
	if exitCode == 0 {
		_ = os.WriteFile(paths.StatusPath, []byte("stopped"), 0644)
	} else {
		_ = os.WriteFile(paths.StatusPath, []byte("crashed"), 0644)
	}
	return exitCode
}

func lifecycleHandleClient(conn net.Conn, ptmx *os.File, clientsMu *sync.Mutex, clients map[net.Conn]struct{}, removeClient func(net.Conn), paths lifecyclePaths) {
	reader := bufio.NewReader(conn)
	line, err := reader.ReadString('\n')
	if err != nil {
		_ = conn.Close()
		return
	}
	line = strings.TrimSpace(line)
	switch {
	case line == "ATTACH":
		clientsMu.Lock()
		clients[conn] = struct{}{}
		clientsMu.Unlock()
		if buffered := reader.Buffered(); buffered > 0 {
			data := make([]byte, buffered)
			_, _ = io.ReadFull(reader, data)
			_, _ = ptmx.Write(data)
		}
		_, _ = io.Copy(ptmx, reader)
		removeClient(conn)
	case strings.HasPrefix(line, "SEND "):
		payload, err := base64.StdEncoding.DecodeString(strings.TrimPrefix(line, "SEND "))
		if err == nil {
			_, err = ptmx.Write(append(payload, '\n'))
		}
		if err != nil {
			_, _ = conn.Write([]byte("ERR\n"))
		} else {
			_, _ = conn.Write([]byte("OK\n"))
		}
		_ = conn.Close()
	case line == "STOP":
		_ = os.WriteFile(paths.StatusPath, []byte("stopping"), 0644)
		_, err := ptmx.Write([]byte(lifecycleStopInput))
		if err != nil {
			_, _ = conn.Write([]byte("ERR\n"))
		} else {
			_, _ = conn.Write([]byte("OK\n"))
		}
		_ = conn.Close()
	case strings.HasPrefix(line, "RESIZE "):
		parts := strings.Fields(line)
		if len(parts) == 3 {
			rows, _ := strconv.Atoi(parts[1])
			cols, _ := strconv.Atoi(parts[2])
			_ = pty.Setsize(ptmx, &pty.Winsize{Rows: uint16(rows), Cols: uint16(cols)})
		}
		_, _ = conn.Write([]byte("OK\n"))
		_ = conn.Close()
	default:
		_, _ = conn.Write([]byte("ERR\n"))
		_ = conn.Close()
	}
}

func lifecycleResize(sock string) error {
	rows, cols, err := pty.Getsize(os.Stdin)
	if err != nil {
		return err
	}
	_, err = lifecycleControl(sock, fmt.Sprintf("RESIZE %d %d\n", rows, cols), 500*time.Millisecond)
	return err
}

func lifecycleControl(sock string, command string, timeout time.Duration) (string, error) {
	conn, err := net.DialTimeout("unix", sock, timeout)
	if err != nil {
		return "", err
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(timeout))
	if _, err := conn.Write([]byte(command)); err != nil {
		return "", err
	}
	data, err := io.ReadAll(conn)
	if err != nil {
		return "", err
	}
	return string(data), nil
}

func lifecycleSocketReady(sock string, timeout time.Duration) bool {
	conn, err := net.DialTimeout("unix", sock, timeout)
	if err != nil {
		return false
	}
	_ = conn.Close()
	return true
}

func lifecycleResolvePaths(dir string) (lifecyclePaths, error) {
	abs, err := filepath.Abs(dir)
	if err != nil {
		return lifecyclePaths{}, err
	}
	runDir := filepath.Join(abs, ".remotely", "run")
	home, err := os.UserHomeDir()
	if err != nil || home == "" {
		home = os.TempDir()
	}
	sum := sha1.Sum([]byte(abs))
	socketDir := filepath.Join(home, ".remotely", "run", "sockets")
	socketPath := filepath.Join(socketDir, hex.EncodeToString(sum[:])[:24]+".sock")
	return lifecyclePaths{
		RunDir:        runDir,
		SocketDir:     socketDir,
		SocketPath:    socketPath,
		LogPath:       filepath.Join(runDir, "console.log"),
		StatusPath:    filepath.Join(runDir, "status"),
		SupervisorPid: filepath.Join(runDir, "supervisor.pid"),
		ServerPid:     filepath.Join(runDir, "server.pid"),
		ExitCode:      filepath.Join(runDir, "exit.code"),
	}, nil
}

func readTrim(path string) string {
	data, err := os.ReadFile(path)
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(data))
}

func killPidFile(path string) {
	pid, err := strconv.Atoi(readTrim(path))
	if err != nil || pid <= 0 {
		return
	}
	_ = syscall.Kill(-pid, syscall.SIGTERM)
	_ = syscall.Kill(pid, syscall.SIGTERM)
	time.Sleep(2 * time.Second)
	_ = syscall.Kill(-pid, syscall.SIGKILL)
	_ = syscall.Kill(pid, syscall.SIGKILL)
}
