package main

import (
	"crypto/subtle"
	"errors"
	"io"
	"net"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"syscall/js"
	"time"
	"unicode/utf8"

	"golang.org/x/crypto/ssh"
)

const (
	carrierProtocol  = "restudio.ssh.v1"
	maxFrameBytes    = 32 * 1024
	maxQueuedFrames  = 16
	maxBufferedBytes = 256 * 1024
	maxDiagnostics   = 32
)

var (
	hostKeyPattern     = regexp.MustCompile(`^SHA256:[A-Za-z0-9+/]{43}=?$`)
	errHostKeyMismatch = errors.New("host key mismatch")
	errCarrierClosed   = errors.New("carrier closed")
	openOnce           sync.Once
	activeMu           sync.Mutex
	active             *operation
)

type operation struct {
	mu                  sync.Mutex
	closeOnce           sync.Once
	closed              chan struct{}
	inputs              chan []byte
	resizes             chan dimensions
	carrier             *webSocketConn
	client              *ssh.Client
	session             *ssh.Session
	stdin               io.WriteCloser
	explicit            bool
	receivedDiagnostics int
	writtenDiagnostics  int
}

type frame struct {
	bytes []byte
}

type webSocketConn struct {
	socket        js.Value
	frames        chan frame
	closed        chan struct{}
	closeOnce     sync.Once
	writeMu       sync.Mutex
	deadlineMu    sync.Mutex
	readDeadline  time.Time
	writeDeadline time.Time
	callbacks     []js.Func
	buffer        []byte
}

type socketAddress string

type deadlineError struct{}

type terminalWriter struct {
	mu   sync.Mutex
	tail []byte
}

type dimensions struct {
	columns int
	rows    int
}

func main() {
	js.Global().Set("onmessage", js.FuncOf(receiveCommand))
	emit("engine-ready", "", "")
	select {}
}

func receiveCommand(this js.Value, arguments []js.Value) any {
	if len(arguments) == 0 {
		return nil
	}
	message := arguments[0].Get("data")
	command := message.Get("type").String()
	switch command {
	case "open":
		openOnce.Do(func() {
			request := openRequest{
				endpoint: message.Get("endpoint").String(),
				grant:    message.Get("grant").String(),
				username: message.Get("username").String(),
				password: message.Get("password").String(),
				hostKey:  message.Get("hostKey").String(),
				columns:  boundedDimension(message.Get("columns").Int()),
				rows:     boundedDimension(message.Get("rows").Int()),
			}
			message.Set("grant", "")
			message.Set("password", "")
			current := &operation{
				closed:  make(chan struct{}),
				inputs:  make(chan []byte, maxQueuedFrames),
				resizes: make(chan dimensions, 1),
			}
			activeMu.Lock()
			active = current
			activeMu.Unlock()
			go current.run(request)
		})
	case "input":
		if current := currentOperation(); current != nil {
			bytes, valid := copyBytes(message.Get("data"))
			if valid && len(bytes) > 0 && len(bytes) <= maxFrameBytes {
				if current.enqueueInput(bytes) {
					current.recordInputReceived(len(bytes))
				}
			}
		}
	case "resize":
		if current := currentOperation(); current != nil {
			columns := boundedDimension(message.Get("columns").Int())
			rows := boundedDimension(message.Get("rows").Int())
			current.enqueueResize(dimensions{columns: columns, rows: rows})
		}
	case "close":
		if current := currentOperation(); current != nil {
			current.close(true)
		}
	}
	return nil
}

type openRequest struct {
	endpoint string
	grant    string
	username string
	password string
	hostKey  string
	columns  int
	rows     int
}

func (current *operation) run(request openRequest) {
	if err := validateRequest(request); err != nil {
		current.fail("configuration_invalid", "Browser SSH Configuration Is Invalid")
		return
	}
	carrier, err := openCarrier(current, request.endpoint, request.grant)
	if err != nil {
		current.fail(classifyCarrier(err), carrierMessage(err))
		return
	}
	if current.isClosed() {
		carrier.Close()
		return
	}
	carrier.SetDeadline(time.Now().Add(12 * time.Second))
	config := &ssh.ClientConfig{
		User: request.username,
		Auth: []ssh.AuthMethod{ssh.Password(request.password)},
		HostKeyCallback: func(hostname string, remote net.Addr, key ssh.PublicKey) error {
			actual := ssh.FingerprintSHA256(key)
			if subtle.ConstantTimeCompare([]byte(actual), []byte(request.hostKey)) != 1 {
				return errHostKeyMismatch
			}
			return nil
		},
	}
	connection, channels, requests, err := ssh.NewClientConn(carrier, "rewind", config)
	config.Auth = nil
	request.password = ""
	if err != nil {
		if errors.Is(err, errHostKeyMismatch) {
			current.fail("host_key_mismatch", "Browser SSH Host Key Did Not Match")
		} else {
			current.fail("authentication_rejected", "Browser SSH Authentication Was Rejected")
		}
		return
	}
	carrier.SetDeadline(time.Time{})
	client := ssh.NewClient(connection, channels, requests)
	if !current.bindClient(client) {
		client.Close()
		return
	}
	session, err := client.NewSession()
	if err != nil {
		current.fail("session_rejected", "Browser SSH Session Was Rejected")
		return
	}
	if !current.bindSession(session) {
		session.Close()
		return
	}
	stdin, err := session.StdinPipe()
	if err != nil {
		current.fail("session_rejected", "Browser SSH Input Is Unavailable")
		return
	}
	if !current.bindStdin(stdin) {
		stdin.Close()
		return
	}
	writer := &terminalWriter{}
	session.Stdout = writer
	session.Stderr = writer
	modes := ssh.TerminalModes{
		ssh.ECHO:          1,
		ssh.TTY_OP_ISPEED: 14400,
		ssh.TTY_OP_OSPEED: 14400,
	}
	if err := session.RequestPty("xterm-256color", request.rows, request.columns, modes); err != nil {
		current.fail("pty_rejected", "Browser SSH PTY Was Rejected")
		return
	}
	if err := session.Shell(); err != nil {
		current.fail("shell_rejected", "Browser SSH Shell Was Rejected")
		return
	}
	if current.isClosed() {
		return
	}
	go current.pumpInput(stdin)
	go current.pumpResize(session)
	emit("ready", "", "")
	err = session.Wait()
	explicit := current.close(false)
	if !explicit {
		if err == nil || errors.Is(err, io.EOF) {
			emit("closed", "connection_closed", "Browser SSH Connection Closed")
		} else {
			emit("error", "connection_lost", "Browser SSH Connection Was Lost")
		}
	}
}

func (current *operation) enqueueInput(bytes []byte) bool {
	select {
	case current.inputs <- bytes:
		return true
	case <-current.closed:
		return false
	default:
		current.fail("input_overflow", "Browser SSH Input Queue Is Full")
		return false
	}
}

func (current *operation) enqueueResize(size dimensions) {
	for {
		select {
		case current.resizes <- size:
			return
		case <-current.closed:
			return
		default:
		}
		select {
		case <-current.resizes:
		default:
		}
	}
}

func (current *operation) pumpInput(stdin io.Writer) {
	for {
		select {
		case bytes := <-current.inputs:
			if err := writeAll(stdin, bytes); err != nil {
				if !current.isClosed() {
					current.fail("connection_lost", "Browser SSH Input Failed")
				}
				return
			}
			current.recordInputWritten(len(bytes))
		case <-current.closed:
			return
		}
	}
}

func writeAll(writer io.Writer, bytes []byte) error {
	for len(bytes) > 0 {
		written, err := writer.Write(bytes)
		if written > 0 {
			bytes = bytes[written:]
		}
		if err != nil {
			return err
		}
		if written == 0 {
			return io.ErrShortWrite
		}
	}
	return nil
}

func (current *operation) recordInputReceived(length int) {
	if current.receivedDiagnostics >= maxDiagnostics {
		return
	}
	current.receivedDiagnostics++
	emitInputDiagnostic("input_received", length)
}

func (current *operation) recordInputWritten(length int) {
	if current.writtenDiagnostics >= maxDiagnostics {
		return
	}
	current.writtenDiagnostics++
	emitInputDiagnostic("input_written", length)
}

func (current *operation) pumpResize(session *ssh.Session) {
	for {
		select {
		case size := <-current.resizes:
			if err := session.WindowChange(size.rows, size.columns); err != nil && !current.isClosed() {
				current.fail("resize_failed", "Browser SSH Resize Failed")
				return
			}
		case <-current.closed:
			return
		}
	}
}

func (current *operation) fail(code string, message string) {
	explicit := current.close(false)
	if !explicit {
		emit("error", code, message)
	}
}

func (current *operation) close(explicit bool) bool {
	current.closeOnce.Do(func() {
		current.mu.Lock()
		current.explicit = explicit
		stdin := current.stdin
		session := current.session
		client := current.client
		carrier := current.carrier
		close(current.closed)
		current.mu.Unlock()
		if stdin != nil {
			stdin.Close()
		}
		if session != nil {
			session.Close()
		}
		if client != nil {
			client.Close()
		}
		if carrier != nil {
			carrier.Close()
		}
	})
	current.mu.Lock()
	value := current.explicit
	current.mu.Unlock()
	return value
}

func (current *operation) isClosed() bool {
	select {
	case <-current.closed:
		return true
	default:
		return false
	}
}

func currentOperation() *operation {
	activeMu.Lock()
	defer activeMu.Unlock()
	return active
}

func validateRequest(request openRequest) error {
	endpoint, err := url.Parse(request.endpoint)
	if err != nil || endpoint.Scheme != "wss" || endpoint.Host == "" || endpoint.User != nil || endpoint.RawQuery != "" || endpoint.Fragment != "" || !strings.HasSuffix(endpoint.EscapedPath(), "/api/browser-ssh") {
		return errors.New("invalid endpoint")
	}
	if request.grant == "" || len(request.grant) > 8*1024 || request.username == "" || len(request.username) > 512 || request.password == "" || len(request.password) > 4096 || !hostKeyPattern.MatchString(request.hostKey) {
		return errors.New("invalid credential")
	}
	return nil
}

func openCarrier(current *operation, endpoint string, grant string) (*webSocketConn, error) {
	constructor := js.Global().Get("WebSocket")
	if constructor.Type() != js.TypeFunction {
		return nil, errors.New("websocket unavailable")
	}
	socket := constructor.New(endpoint, carrierProtocol)
	socket.Set("binaryType", "arraybuffer")
	connection := &webSocketConn{
		socket: socket,
		frames: make(chan frame, maxQueuedFrames),
		closed: make(chan struct{}),
	}
	if !current.bindCarrier(connection) {
		connection.Close()
		return nil, errCarrierClosed
	}
	opened := make(chan struct{}, 1)
	connection.callbacks = []js.Func{
		js.FuncOf(func(this js.Value, arguments []js.Value) any {
			select {
			case opened <- struct{}{}:
			default:
			}
			return nil
		}),
		js.FuncOf(func(this js.Value, arguments []js.Value) any {
			if len(arguments) == 0 || connection.isClosed() {
				return nil
			}
			bytes, valid := copyBytes(arguments[0].Get("data"))
			if !valid || len(bytes) > maxFrameBytes {
				connection.Close()
				return nil
			}
			select {
			case connection.frames <- frame{bytes: bytes}:
			default:
				connection.Close()
			}
			return nil
		}),
		js.FuncOf(func(this js.Value, arguments []js.Value) any {
			connection.finish()
			return nil
		}),
		js.FuncOf(func(this js.Value, arguments []js.Value) any {
			connection.finish()
			return nil
		}),
	}
	socket.Set("onopen", connection.callbacks[0])
	socket.Set("onmessage", connection.callbacks[1])
	socket.Set("onclose", connection.callbacks[2])
	socket.Set("onerror", connection.callbacks[3])
	select {
	case <-opened:
	case <-connection.closed:
		return nil, errCarrierClosed
	case <-time.After(10 * time.Second):
		connection.Close()
		return nil, deadlineError{}
	}
	if _, err := connection.Write([]byte(grant)); err != nil {
		connection.Close()
		return nil, err
	}
	connection.SetReadDeadline(time.Now().Add(5 * time.Second))
	ack := make([]byte, 2)
	count, err := connection.Read(ack)
	connection.SetReadDeadline(time.Time{})
	if err != nil || count != 1 || ack[0] != 1 {
		connection.Close()
		if err != nil {
			return nil, err
		}
		return nil, errors.New("admission rejected")
	}
	return connection, nil
}

func (current *operation) bindCarrier(connection *webSocketConn) bool {
	current.mu.Lock()
	defer current.mu.Unlock()
	if current.isClosed() {
		return false
	}
	current.carrier = connection
	return true
}

func (current *operation) bindClient(client *ssh.Client) bool {
	current.mu.Lock()
	defer current.mu.Unlock()
	if current.isClosed() {
		return false
	}
	current.client = client
	return true
}

func (current *operation) bindSession(session *ssh.Session) bool {
	current.mu.Lock()
	defer current.mu.Unlock()
	if current.isClosed() {
		return false
	}
	current.session = session
	return true
}

func (current *operation) bindStdin(stdin io.WriteCloser) bool {
	current.mu.Lock()
	defer current.mu.Unlock()
	if current.isClosed() {
		return false
	}
	current.stdin = stdin
	return true
}

func (connection *webSocketConn) Read(bytes []byte) (int, error) {
	if len(bytes) == 0 {
		return 0, nil
	}
	if len(connection.buffer) > 0 {
		count := copy(bytes, connection.buffer)
		connection.buffer = connection.buffer[count:]
		return count, nil
	}
	deadline := connection.readDeadlineValue()
	var timer *time.Timer
	var timeout <-chan time.Time
	if !deadline.IsZero() {
		duration := time.Until(deadline)
		if duration <= 0 {
			return 0, deadlineError{}
		}
		timer = time.NewTimer(duration)
		timeout = timer.C
		defer timer.Stop()
	}
	select {
	case next := <-connection.frames:
		count := copy(bytes, next.bytes)
		if count < len(next.bytes) {
			connection.buffer = next.bytes[count:]
		}
		return count, nil
	case <-connection.closed:
		return 0, io.EOF
	case <-timeout:
		return 0, deadlineError{}
	}
}

func (connection *webSocketConn) Write(bytes []byte) (int, error) {
	connection.writeMu.Lock()
	defer connection.writeMu.Unlock()
	written := 0
	for written < len(bytes) {
		end := min(written+maxFrameBytes, len(bytes))
		if err := connection.writeFrame(bytes[written:end]); err != nil {
			return written, err
		}
		written = end
	}
	return written, nil
}

func (connection *webSocketConn) writeFrame(bytes []byte) error {
	deadline := connection.writeDeadlineValue()
	maximum := time.Now().Add(15 * time.Second)
	if deadline.IsZero() || maximum.Before(deadline) {
		deadline = maximum
	}
	for connection.socket.Get("bufferedAmount").Int() > maxBufferedBytes {
		if connection.isClosed() {
			return errCarrierClosed
		}
		if time.Now().After(deadline) {
			return deadlineError{}
		}
		time.Sleep(5 * time.Millisecond)
	}
	if connection.socket.Get("readyState").Int() != 1 {
		return errCarrierClosed
	}
	array := js.Global().Get("Uint8Array").New(len(bytes))
	js.CopyBytesToJS(array, bytes)
	connection.socket.Call("send", array)
	return nil
}

func (connection *webSocketConn) Close() error {
	connection.finish()
	if connection.socket.Truthy() && connection.socket.Get("readyState").Int() < 2 {
		connection.socket.Call("close", 1000, "Closed")
	}
	return nil
}

func (connection *webSocketConn) finish() {
	connection.closeOnce.Do(func() {
		close(connection.closed)
	})
}

func (connection *webSocketConn) isClosed() bool {
	select {
	case <-connection.closed:
		return true
	default:
		return false
	}
}

func (connection *webSocketConn) LocalAddr() net.Addr {
	return socketAddress("browser")
}

func (connection *webSocketConn) RemoteAddr() net.Addr {
	return socketAddress("rewind")
}

func (connection *webSocketConn) SetDeadline(deadline time.Time) error {
	connection.deadlineMu.Lock()
	connection.readDeadline = deadline
	connection.writeDeadline = deadline
	connection.deadlineMu.Unlock()
	return nil
}

func (connection *webSocketConn) SetReadDeadline(deadline time.Time) error {
	connection.deadlineMu.Lock()
	connection.readDeadline = deadline
	connection.deadlineMu.Unlock()
	return nil
}

func (connection *webSocketConn) SetWriteDeadline(deadline time.Time) error {
	connection.deadlineMu.Lock()
	connection.writeDeadline = deadline
	connection.deadlineMu.Unlock()
	return nil
}

func (connection *webSocketConn) readDeadlineValue() time.Time {
	connection.deadlineMu.Lock()
	defer connection.deadlineMu.Unlock()
	return connection.readDeadline
}

func (connection *webSocketConn) writeDeadlineValue() time.Time {
	connection.deadlineMu.Lock()
	defer connection.deadlineMu.Unlock()
	return connection.writeDeadline
}

func (writer *terminalWriter) Write(bytes []byte) (int, error) {
	writer.mu.Lock()
	defer writer.mu.Unlock()
	data := append(writer.tail, bytes...)
	writer.tail = nil
	var output strings.Builder
	for len(data) > 0 {
		if !utf8.FullRune(data) {
			writer.tail = append(writer.tail, data...)
			break
		}
		runeValue, size := utf8.DecodeRune(data)
		output.WriteRune(runeValue)
		data = data[size:]
	}
	if output.Len() > 0 {
		emitOutput(output.String())
	}
	return len(bytes), nil
}

func emitOutput(message string) {
	event := js.Global().Get("Object").New()
	event.Set("type", "output")
	event.Set("message", message)
	js.Global().Call("postMessage", event)
}

func emit(kind string, code string, message string) {
	event := js.Global().Get("Object").New()
	event.Set("type", kind)
	if code != "" {
		event.Set("code", code)
	}
	if message != "" {
		event.Set("message", boundedMessage(message))
	}
	js.Global().Call("postMessage", event)
}

func emitInputDiagnostic(code string, length int) {
	emit("diagnostic", code, strconv.Itoa(length))
}

func copyBytes(value js.Value) ([]byte, bool) {
	if value.IsUndefined() || value.IsNull() {
		return nil, false
	}
	arrayBuffer := js.Global().Get("ArrayBuffer")
	if arrayBuffer.Type() != js.TypeFunction || value.Type() != js.TypeObject {
		return nil, false
	}
	var array js.Value
	if value.InstanceOf(arrayBuffer) {
		array = js.Global().Get("Uint8Array").New(value)
	} else if arrayBuffer.Call("isView", value).Bool() {
		array = js.Global().Get("Uint8Array").New(value.Get("buffer"), value.Get("byteOffset"), value.Get("byteLength"))
	} else {
		return nil, false
	}
	bytes := make([]byte, array.Get("byteLength").Int())
	js.CopyBytesToGo(bytes, array)
	return bytes, true
}

func boundedDimension(value int) int {
	if value < 1 {
		return 1
	}
	if value > 4096 {
		return 4096
	}
	return value
}

func boundedMessage(message string) string {
	message = strings.Map(func(value rune) rune {
		if value < 0x20 && value != '\t' {
			return -1
		}
		return value
	}, message)
	if len(message) > 240 {
		return message[:240]
	}
	return message
}

func classifyCarrier(err error) string {
	if errors.As(err, new(deadlineError)) {
		return "carrier_timeout"
	}
	if errors.Is(err, errCarrierClosed) || err.Error() == "admission rejected" {
		return "admission_rejected"
	}
	return "carrier_open_failed"
}

func carrierMessage(err error) string {
	if classifyCarrier(err) == "carrier_timeout" {
		return "Browser SSH Carrier Timed Out"
	}
	if classifyCarrier(err) == "admission_rejected" {
		return "Browser SSH Admission Was Rejected"
	}
	return "Browser SSH Carrier Could Not Open"
}

func (socketAddress) Network() string {
	return "wss"
}

func (address socketAddress) String() string {
	return string(address)
}

func (deadlineError) Error() string {
	return "deadline exceeded"
}

func (deadlineError) Timeout() bool {
	return true
}

func (deadlineError) Temporary() bool {
	return true
}
