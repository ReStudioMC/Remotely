package main

import (
	"bufio"
	"context"
	"crypto/rand"
	"crypto/sha1"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"
	"unicode/utf8"
)

const (
	agentRelaySubprotocol = "remotely-agent.v1"
	agentRelayMaxFrame    = 8 * 1024 * 1024
	agentRelayMaxMessage  = 8 * 1024 * 1024
)

type agentWebSocket struct {
	conn         net.Conn
	reader       *bufio.Reader
	writeMu      sync.Mutex
	closeMu      sync.Mutex
	closed       bool
	maxFrame     int64
	readTimeout  time.Duration
	writeTimeout time.Duration
}

type agentWebSocketConfig struct {
	TLSConfig     *tls.Config
	PinnedSPKI    []string
	PairingCode   string
	Credential    string
	MaxFrame      int64
	Subprotocol   string
	NoSubprotocol bool
}

type agentRelayAuthError struct {
	status int
}

func (e *agentRelayAuthError) Error() string {
	return fmt.Sprintf("relay authentication failed with status %d", e.status)
}

func dialAgentWebSocket(ctx context.Context, endpoint string, config agentWebSocketConfig) (*agentWebSocket, error) {
	parsed, err := url.Parse(endpoint)
	if err != nil {
		return nil, err
	}
	if parsed.Scheme != "wss" {
		return nil, errors.New("relay endpoint must use wss")
	}
	if parsed.User != nil {
		return nil, errors.New("relay endpoint must not contain user information")
	}
	if parsed.Hostname() == "" {
		return nil, errors.New("relay endpoint has no host")
	}
	if config.MaxFrame <= 0 || config.MaxFrame > agentRelayMaxFrame {
		config.MaxFrame = agentRelayMaxFrame
	}
	port := parsed.Port()
	if port == "" {
		port = "443"
	}
	address := net.JoinHostPort(parsed.Hostname(), port)
	dialer := &net.Dialer{Timeout: 15 * time.Second}
	plain, err := dialer.DialContext(ctx, "tcp", address)
	if err != nil {
		return nil, err
	}
	serverName := parsed.Hostname()
	tlsConfig := &tls.Config{MinVersion: tls.VersionTLS12, ServerName: serverName}
	if config.TLSConfig != nil {
		tlsConfig = config.TLSConfig.Clone()
		if tlsConfig.MinVersion < tls.VersionTLS12 {
			tlsConfig.MinVersion = tls.VersionTLS12
		}
		if tlsConfig.ServerName != "" && !strings.EqualFold(tlsConfig.ServerName, serverName) {
			plain.Close()
			return nil, errors.New("relay TLS server name must match the endpoint host")
		}
		tlsConfig.ServerName = serverName
	}
	if tlsConfig.InsecureSkipVerify {
		plain.Close()
		return nil, errors.New("relay TLS verification cannot be disabled")
	}
	secure := tls.Client(plain, tlsConfig)
	if err := secure.HandshakeContext(ctx); err != nil {
		plain.Close()
		return nil, err
	}
	if err := validateAgentTLS(secure.ConnectionState(), config.PinnedSPKI); err != nil {
		secure.Close()
		return nil, err
	}
	keyBytes := make([]byte, 16)
	if _, err := rand.Read(keyBytes); err != nil {
		secure.Close()
		return nil, err
	}
	key := base64.StdEncoding.EncodeToString(keyBytes)
	path := parsed.EscapedPath()
	if path == "" {
		path = "/"
	}
	if parsed.RawQuery != "" {
		path += "?" + parsed.RawQuery
	}
	subprotocol := config.Subprotocol
	if subprotocol == "" && !config.NoSubprotocol {
		subprotocol = agentRelaySubprotocol
	}
	request := "GET " + path + " HTTP/1.1\r\n" +
		"Host: " + parsed.Host + "\r\n" +
		"Upgrade: websocket\r\n" +
		"Connection: Upgrade\r\n" +
		"Sec-WebSocket-Key: " + key + "\r\n" +
		"Sec-WebSocket-Version: 13\r\n"
	if subprotocol != "" {
		request += "Sec-WebSocket-Protocol: " + subprotocol + "\r\n"
	}
	if config.Credential != "" {
		if len(config.Credential) > 4096 || strings.ContainsAny(config.Credential, "\r\n") {
			secure.Close()
			return nil, errors.New("invalid relay credential")
		}
		request += "Authorization: Bearer " + config.Credential + "\r\n"
	} else if config.PairingCode != "" {
		if len(config.PairingCode) > 256 || strings.ContainsAny(config.PairingCode, "\r\n") {
			secure.Close()
			return nil, errors.New("invalid pairing code")
		}
		request += "X-Remotely-Pairing-Code: " + config.PairingCode + "\r\n"
	}
	request += "\r\n"
	deadline := time.Now().Add(15 * time.Second)
	_ = secure.SetDeadline(deadline)
	if _, err := io.WriteString(secure, request); err != nil {
		secure.Close()
		return nil, err
	}
	reader := bufio.NewReaderSize(secure, 32*1024)
	response, err := http.ReadResponse(reader, &http.Request{Method: http.MethodGet})
	if err != nil {
		secure.Close()
		return nil, err
	}
	if response.Body != nil {
		response.Body.Close()
	}
	if response.StatusCode != http.StatusSwitchingProtocols {
		secure.Close()
		if response.StatusCode == http.StatusUnauthorized || response.StatusCode == http.StatusForbidden {
			return nil, &agentRelayAuthError{status: response.StatusCode}
		}
		return nil, fmt.Errorf("relay handshake failed with status %s", response.Status)
	}
	if !headerContains(response.Header.Get("Upgrade"), "websocket") || !headerContains(response.Header.Get("Connection"), "upgrade") {
		secure.Close()
		return nil, errors.New("relay handshake did not negotiate websocket")
	}
	if response.Header.Get("Sec-WebSocket-Accept") != websocketAccept(key) {
		secure.Close()
		return nil, errors.New("relay handshake has an invalid accept key")
	}
	if response.Header.Get("Sec-WebSocket-Protocol") != subprotocol {
		secure.Close()
		return nil, errors.New("relay handshake selected an invalid protocol")
	}
	_ = secure.SetDeadline(time.Time{})
	return &agentWebSocket{conn: secure, reader: reader, maxFrame: config.MaxFrame, writeTimeout: 30 * time.Second}, nil
}

func validateAgentTLS(state tls.ConnectionState, pins []string) error {
	if len(state.PeerCertificates) == 0 {
		return errors.New("relay TLS connection has no peer certificate")
	}
	if len(pins) == 0 {
		return nil
	}
	digest := sha256.Sum256(state.PeerCertificates[0].RawSubjectPublicKeyInfo)
	encoded := base64.RawURLEncoding.EncodeToString(digest[:])
	hexDigest := hex.EncodeToString(digest[:])
	for _, pin := range pins {
		trimmed := strings.TrimSpace(pin)
		if trimmed == encoded || strings.EqualFold(trimmed, hexDigest) {
			return nil
		}
	}
	return errors.New("relay TLS public key pin mismatch")
}

func websocketAccept(key string) string {
	digest := sha1.New()
	_, _ = io.WriteString(digest, key+"258EAFA5-E914-47DA-95CA-C5AB0DC85B11")
	return base64.StdEncoding.EncodeToString(digest.Sum(nil))
}

func headerContains(value, wanted string) bool {
	for _, part := range strings.Split(value, ",") {
		if strings.EqualFold(strings.TrimSpace(part), wanted) {
			return true
		}
	}
	return false
}

func (w *agentWebSocket) writeMessage(opcode byte, payload []byte) error {
	if int64(len(payload)) > w.maxFrame {
		return errors.New("relay websocket frame exceeds limit")
	}
	w.writeMu.Lock()
	defer w.writeMu.Unlock()
	w.closeMu.Lock()
	closed := w.closed
	w.closeMu.Unlock()
	if closed {
		return net.ErrClosed
	}
	if w.writeTimeout > 0 {
		_ = w.conn.SetWriteDeadline(time.Now().Add(w.writeTimeout))
		defer w.conn.SetWriteDeadline(time.Time{})
	}
	mask := make([]byte, 4)
	if _, err := rand.Read(mask); err != nil {
		return err
	}
	header := make([]byte, 0, 14)
	header = append(header, 0x80|opcode)
	switch {
	case len(payload) < 126:
		header = append(header, 0x80|byte(len(payload)))
	case int64(len(payload)) <= 0xffff:
		header = append(header, 0x80|126, byte(len(payload)>>8), byte(len(payload)))
	default:
		header = append(header, 0x80|127, 0, 0, 0, 0, byte(len(payload)>>24), byte(len(payload)>>16), byte(len(payload)>>8), byte(len(payload)))
	}
	header = append(header, mask...)
	masked := make([]byte, len(payload))
	for i, value := range payload {
		masked[i] = value ^ mask[i%4]
	}
	if _, err := w.conn.Write(header); err != nil {
		return err
	}
	_, err := w.conn.Write(masked)
	return err
}

func (w *agentWebSocket) readMessage() (byte, []byte, error) {
	var message []byte
	var messageOpcode byte
	frames := 0
	if w.readTimeout > 0 {
		_ = w.conn.SetReadDeadline(time.Now().Add(w.readTimeout))
	}
	for {
		frames++
		if frames > 1024 {
			return 0, nil, errors.New("relay websocket message uses too many frames")
		}
		first, err := w.reader.ReadByte()
		if err != nil {
			return 0, nil, err
		}
		second, err := w.reader.ReadByte()
		if err != nil {
			return 0, nil, err
		}
		if first&0x70 != 0 {
			return 0, nil, errors.New("relay websocket frame uses an unsupported extension")
		}
		final := first&0x80 != 0
		opcode := first & 0x0f
		masked := second&0x80 != 0
		lengthCode := second & 0x7f
		length := int64(lengthCode)
		switch lengthCode {
		case 126:
			var extended [2]byte
			if _, err := io.ReadFull(w.reader, extended[:]); err != nil {
				return 0, nil, err
			}
			length = int64(extended[0])<<8 | int64(extended[1])
			if length < 126 {
				return 0, nil, errors.New("relay websocket frame has a non-minimal length")
			}
		case 127:
			var extended [8]byte
			if _, err := io.ReadFull(w.reader, extended[:]); err != nil {
				return 0, nil, err
			}
			if extended[0]&0x80 != 0 {
				return 0, nil, errors.New("relay websocket frame has an invalid length")
			}
			for _, value := range extended {
				length = length<<8 | int64(value)
			}
			if length <= 0xffff {
				return 0, nil, errors.New("relay websocket frame has a non-minimal length")
			}
		}
		if length > w.maxFrame {
			return 0, nil, errors.New("relay websocket frame exceeds limit")
		}
		if opcode >= 8 && (!final || length > 125) {
			return 0, nil, errors.New("relay websocket control frame is invalid")
		}
		if masked {
			return 0, nil, errors.New("relay websocket server frame must not be masked")
		}
		var mask [4]byte
		if masked {
			if _, err := io.ReadFull(w.reader, mask[:]); err != nil {
				return 0, nil, err
			}
		}
		payload := make([]byte, length)
		if _, err := io.ReadFull(w.reader, payload); err != nil {
			return 0, nil, err
		}
		if masked {
			for i := range payload {
				payload[i] ^= mask[i%4]
			}
		}
		switch opcode {
		case 0x8:
			if len(payload) == 1 {
				return 0, nil, errors.New("relay websocket close frame is invalid")
			}
			if len(payload) >= 2 {
				return 0, nil, fmt.Errorf("relay closed with code %d", int(payload[0])<<8|int(payload[1]))
			}
			return 0, nil, io.EOF
		case 0x9:
			if err := w.writeMessage(0xa, payload); err != nil {
				return 0, nil, err
			}
			continue
		case 0xa:
			continue
		case 0x1, 0x2:
			if messageOpcode != 0 {
				return 0, nil, errors.New("relay websocket message started before continuation ended")
			}
			messageOpcode = opcode
		case 0x0:
			if messageOpcode == 0 {
				return 0, nil, errors.New("relay websocket continuation has no message")
			}
		default:
			return 0, nil, errors.New("relay websocket opcode is invalid")
		}
		if int64(len(message))+length > w.maxFrame {
			return 0, nil, errors.New("relay websocket message exceeds limit")
		}
		message = append(message, payload...)
		if final {
			if messageOpcode == 0x1 && !utf8.Valid(message) {
				return 0, nil, errors.New("relay websocket text message is not valid UTF-8")
			}
			return messageOpcode, message, nil
		}
	}
}

func (w *agentWebSocket) close() error {
	w.closeMu.Lock()
	if w.closed {
		w.closeMu.Unlock()
		return nil
	}
	w.closed = true
	w.closeMu.Unlock()
	return w.conn.Close()
}

func validateAgentRelayURL(endpoint string) error {
	parsed, err := url.Parse(endpoint)
	if err != nil {
		return err
	}
	if parsed.Scheme != "wss" || parsed.Hostname() == "" || parsed.User != nil {
		return errors.New("relay endpoint must be a wss URL")
	}
	return nil
}

func parseAgentPin(cert *x509.Certificate) string {
	digest := sha256.Sum256(cert.RawSubjectPublicKeyInfo)
	return base64.RawURLEncoding.EncodeToString(digest[:])
}
