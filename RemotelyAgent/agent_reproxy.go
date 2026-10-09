package main

import (
	"bytes"
	"context"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"net/url"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode/utf8"
)

type AgentReProxyGrant struct {
	LifecycleID string `json:"lifecycleId"`
	Protocol    string `json:"protocol"`
	Port        int    `json:"port"`
	RelayOrigin string `json:"relayOrigin"`
}
type agentReProxyTargetGrant struct {
	ID           string   `json:"id"`
	Name         string   `json:"name"`
	ServerID     string   `json:"serverId"`
	Lifecycle    string   `json:"lifecycle"`
	Host         string   `json:"host"`
	TCPPorts     []int    `json:"tcpPorts"`
	UDPPorts     []int    `json:"udpPorts"`
	RelayOrigins []string `json:"relayOrigins"`
}
type agentReProxyCapabilities struct {
	Version int                 `json:"version"`
	Grants  []AgentReProxyGrant `json:"grants"`
}
type agentReProxyEndpoint struct {
	ID         string `json:"id"`
	Protocol   string `json:"protocol"`
	TargetHost string `json:"targetHost"`
	TargetPort int    `json:"targetPort"`
	Enabled    bool   `json:"enabled"`
}
type agentReProxyLimits struct {
	MaxEndpoints int             `json:"maxEndpoints"`
	MaxStreams   int             `json:"maxStreams"`
	MaxUDPFlows  int             `json:"maxUdpFlows"`
	QueuedBytes  json.RawMessage `json:"queuedBytes"`
	Admissions   int             `json:"admissionsPerSecond"`
	Packets      int             `json:"packetsPerSecond"`
	Bytes        string          `json:"bytesPerSecond"`
	DatagramAge  int             `json:"datagramAgeMillis"`
}
type agentReProxyTicket struct {
	ConnectionID  string `json:"connectionId"`
	SessionID     string `json:"sessionId"`
	Generation    string `json:"generation"`
	RouteRevision string `json:"routeRevision"`
	Node          struct {
		TunnelHost   string `json:"tunnelHost"`
		TunnelScheme string `json:"tunnelScheme"`
		TunnelPort   int    `json:"tunnelPort"`
	} `json:"node"`
	Token     string                 `json:"token"`
	ExpiresAt string                 `json:"expiresAt"`
	Endpoints []agentReProxyEndpoint `json:"endpoints"`
	Limits    agentReProxyLimits     `json:"limits"`
}
type agentReProxyCommand struct {
	OperationID   string             `json:"operationId"`
	ConnectionID  string             `json:"connectionId"`
	SessionID     string             `json:"sessionId"`
	Generation    string             `json:"generation"`
	RouteRevision string             `json:"routeRevision"`
	LifecycleID   string             `json:"lifecycleId"`
	Ticket        agentReProxyTicket `json:"ticket"`
}
type agentReProxyStatus struct {
	OperationID      string `json:"operationId"`
	ConnectionID     string `json:"connectionId"`
	SessionID        string `json:"sessionId"`
	Generation       string `json:"generation"`
	RouteRevision    string `json:"routeRevision"`
	State            string `json:"state"`
	Authenticated    bool   `json:"authenticated"`
	Ready            bool   `json:"ready"`
	ActiveStreams    int    `json:"activeStreams"`
	ActiveUDPFlows   int    `json:"activeUdpFlows"`
	DroppedDatagrams string `json:"droppedDatagrams"`
	LastError        string `json:"lastError"`
}
type agentReProxyService struct {
	mu       sync.Mutex
	owner    *AgentService
	grants   []AgentReProxyGrant
	sessions map[string]*agentReProxySession
	closed   bool
}
type agentReProxySession struct {
	mu               sync.Mutex
	service          *agentReProxyService
	previous         *agentReProxySession
	command          agentReProxyCommand
	origin           string
	generation       uint64
	desiredRevision  uint64
	activeRevision   uint64
	preparedRevision uint64
	allowed          map[string]agentReProxyTarget
	active           map[string]agentReProxyTarget
	prepared         map[string]agentReProxyTarget
	flows            map[uint64]*agentReProxyFlow
	socket           *agentWebSocket
	ctx              context.Context
	cancel           context.CancelFunc
	done             chan struct{}
	send             chan agentReProxyQueued
	stopped          bool
	authenticated    bool
	committed        bool
	revoked          bool
	ready            bool
	state            string
	lastError        string
	queued           int64
	dropped          uint64
	streams          int
	udp              int
	maxEndpoints     int
	maxStreams       int
	maxUDP           int
	maxQueued        int64
	maxPackets       int64
	maxBytes         int64
	maxAdmissions    int
	datagramAge      time.Duration
	window           time.Time
	packets          int64
	transferred      int64
	admissions       int
}
type agentReProxyQueued struct {
	data []byte
	flow *agentReProxyFlow
}
type agentReProxyTarget struct {
	id       string
	protocol byte
	host     string
	port     int
}
type agentReProxyPacket struct {
	kind byte
	data []byte
	at   time.Time
}
type agentReProxyFlow struct {
	id             uint64
	target         agentReProxyTarget
	conn           net.Conn
	queue          chan agentReProxyPacket
	done           chan struct{}
	once           sync.Once
	last           time.Time
	readEOF        bool
	readSent       bool
	writeEOF       bool
	writeRequested bool
	queued         int64
}

func parseAgentReProxyGrants(specs []string) ([]AgentReProxyGrant, error) {
	result := make([]AgentReProxyGrant, 0, len(specs))
	for _, spec := range specs {
		parts := strings.Split(spec, "|")
		if len(parts) != 4 {
			return nil, errors.New("ReProxy grant requires lifecycle|TCP-or-UDP|port|wss-origin")
		}
		port, err := strconv.Atoi(parts[2])
		if err != nil {
			return nil, err
		}
		result = append(result, AgentReProxyGrant{parts[0], strings.ToUpper(parts[1]), port, parts[3]})
	}
	return result, nil
}
func newAgentReProxyService(owner *AgentService, grants []AgentReProxyGrant) (*agentReProxyService, error) {
	service := &agentReProxyService{owner: owner, sessions: make(map[string]*agentReProxySession)}
	seen := make(map[AgentReProxyGrant]bool)
	for _, grant := range grants {
		if _, exists := owner.lifecycles[grant.LifecycleID]; !exists {
			return nil, errors.New("ReProxy grant references an unknown lifecycle")
		}
		if grant.Port < 1 || grant.Port > 65535 || (grant.Protocol != "TCP" && grant.Protocol != "UDP") {
			return nil, errors.New("invalid ReProxy transport grant")
		}
		origin, err := url.Parse(grant.RelayOrigin)
		if err != nil || origin.Scheme != "wss" || origin.Hostname() == "" || origin.User != nil || origin.RawQuery != "" || origin.Fragment != "" || (origin.Path != "" && origin.Path != "/") {
			return nil, errors.New("ReProxy relay grant must be a wss origin")
		}
		grant.RelayOrigin = "wss://" + strings.ToLower(origin.Host)
		if origin.Port() == "443" {
			grant.RelayOrigin = reProxyOrigin(origin.Hostname(), 443)
		}
		if seen[grant] {
			return nil, errors.New("duplicate ReProxy grant")
		}
		seen[grant] = true
		service.grants = append(service.grants, grant)
	}
	return service, nil
}
func (s *agentReProxyService) capabilities() agentReProxyCapabilities {
	return agentReProxyCapabilities{2, append([]AgentReProxyGrant{}, s.grants...)}
}
func (s *agentReProxyService) targetGrants() []agentReProxyTargetGrant {
	grouped := make(map[string]*agentReProxyTargetGrant)
	for _, grant := range s.grants {
		target := grouped[grant.LifecycleID]
		if target == nil {
			target = &agentReProxyTargetGrant{ID: grant.LifecycleID, Name: grant.LifecycleID, ServerID: grant.LifecycleID, Lifecycle: grant.LifecycleID, Host: "127.0.0.1", TCPPorts: []int{}, UDPPorts: []int{}, RelayOrigins: []string{}}
			grouped[grant.LifecycleID] = target
		}
		ports := &target.TCPPorts
		if grant.Protocol == "UDP" {
			ports = &target.UDPPorts
		}
		found := false
		for _, port := range *ports {
			if port == grant.Port {
				found = true
				break
			}
		}
		if !found {
			*ports = append(*ports, grant.Port)
		}
		found = false
		for _, origin := range target.RelayOrigins {
			if origin == grant.RelayOrigin {
				found = true
				break
			}
		}
		if !found {
			target.RelayOrigins = append(target.RelayOrigins, grant.RelayOrigin)
		}
	}
	result := make([]agentReProxyTargetGrant, 0, len(grouped))
	for _, target := range grouped {
		sort.Ints(target.TCPPorts)
		sort.Ints(target.UDPPorts)
		sort.Strings(target.RelayOrigins)
		result = append(result, *target)
	}
	sort.Slice(result, func(i, j int) bool { return result[i].ID < result[j].ID })
	return result
}
func (s *agentReProxyService) stopLifecycle(id string) {
	s.mu.Lock()
	sessions := make([]*agentReProxySession, 0)
	for _, session := range s.sessions {
		if session.command.LifecycleID == id {
			sessions = append(sessions, session)
		}
		if previous := session.previous; previous != nil && previous.command.LifecycleID == id {
			sessions = append(sessions, previous)
		}
	}
	s.mu.Unlock()
	for _, session := range sessions {
		session.stop("Server Stopped")
	}
}
func (s *agentReProxyService) close() {
	s.mu.Lock()
	s.closed = true
	sessions := make([]*agentReProxySession, 0, len(s.sessions)*2)
	for _, session := range s.sessions {
		sessions = append(sessions, session)
		if session.previous != nil {
			sessions = append(sessions, session.previous)
		}
	}
	s.mu.Unlock()
	for _, session := range sessions {
		session.stop("Agent Closed")
	}
}
func (s *agentReProxyService) fenced(connection, sessionID string, generation uint64) *agentReProxySession {
	current := s.sessions[connection]
	if current == nil {
		return nil
	}
	if current.command.SessionID == sessionID && current.generation == generation {
		return current
	}
	previous := current.previous
	if previous != nil && previous.command.SessionID == sessionID && previous.generation == generation {
		return previous
	}
	return nil
}
func (s *agentReProxyService) permitted(session *agentReProxySession) bool {
	if session == nil || session.service != s {
		return false
	}
	session.mu.Lock()
	defer session.mu.Unlock()
	if session.stopped || !session.ready || session.state != "ACTIVE" || session.socket == nil || session.ctx.Err() != nil {
		return false
	}
	if _, exists := s.owner.lifecycles[session.command.LifecycleID]; !exists {
		return false
	}
	for _, target := range session.active {
		protocol := "TCP"
		if target.protocol == 2 {
			protocol = "UDP"
		}
		granted := false
		for _, grant := range s.grants {
			if grant.LifecycleID == session.command.LifecycleID && grant.Protocol == protocol && grant.Port == target.port && grant.RelayOrigin == session.origin {
				granted = true
				break
			}
		}
		if !granted {
			return false
		}
	}
	return true
}
func (s *agentReProxyService) committed(session *agentReProxySession, revision uint64) {
	s.mu.Lock()
	if s.closed || s.sessions[session.command.ConnectionID] != session {
		s.mu.Unlock()
		return
	}
	session.mu.Lock()
	if session.stopped || !session.authenticated || session.activeRevision != revision {
		session.mu.Unlock()
		s.mu.Unlock()
		return
	}
	session.ready = true
	session.committed = true
	session.state = "ACTIVE"
	previous := session.previous
	session.mu.Unlock()
	s.mu.Unlock()
	if previous != nil {
		previous.stop("Replaced")
	}
}
func (s *agentReProxyService) restore(session *agentReProxySession) {
	s.mu.Lock()
	if s.closed || s.sessions[session.command.ConnectionID] != session {
		s.mu.Unlock()
		return
	}
	session.mu.Lock()
	if !session.stopped || session.state != "FAILED" || session.committed || session.revoked {
		session.mu.Unlock()
		s.mu.Unlock()
		return
	}
	previous := session.previous
	if previous != nil && previous.generation < session.generation && previous.command.ConnectionID == session.command.ConnectionID && s.permitted(previous) {
		previous.mu.Lock()
		alive := !previous.stopped && previous.ready && previous.state == "ACTIVE" && previous.ctx.Err() == nil
		if alive {
			session.previous = nil
			s.sessions[session.command.ConnectionID] = previous
		}
		previous.mu.Unlock()
		if alive {
			session.mu.Unlock()
			s.mu.Unlock()
			return
		}
	}
	session.previous = nil
	session.mu.Unlock()
	s.mu.Unlock()
	if previous != nil {
		previous.stop("Target Permission Changed")
	}
}
func (s *agentReProxyService) revoked(session *agentReProxySession) {
	s.mu.Lock()
	var previous *agentReProxySession
	if s.sessions[session.command.ConnectionID] == session {
		previous = session.previous
	}
	s.mu.Unlock()
	if previous != nil {
		previous.stop("Revoked")
	}
}
func (s *agentReProxyService) handle(w http.ResponseWriter, r *http.Request) {
	action := strings.TrimPrefix(r.URL.Path, "/v1/reproxy/")
	if action == "grants" && r.Method == http.MethodGet {
		s.owner.writeJSON(w, http.StatusOK, s.targetGrants())
		return
	}
	var command agentReProxyCommand
	if action == "status" && r.Method == http.MethodGet {
		command.ConnectionID = r.URL.Query().Get("connectionId")
		command.SessionID = r.URL.Query().Get("sessionId")
		command.Generation = r.URL.Query().Get("generation")
	} else if r.Method == http.MethodPost && (action == "start" || action == "update" || action == "stop" || action == "status") {
		decoder := json.NewDecoder(http.MaxBytesReader(w, r.Body, 256*1024))
		if err := decoder.Decode(&command); err != nil {
			http.Error(w, "Invalid ReProxy Command", http.StatusBadRequest)
			return
		}
		var extra any
		if decoder.Decode(&extra) != io.EOF {
			http.Error(w, "Invalid ReProxy Command", http.StatusBadRequest)
			return
		}
	} else {
		http.NotFound(w, r)
		return
	}
	generation, err := strconv.ParseUint(command.Generation, 10, 64)
	if err != nil || generation == 0 || !reProxyID(command.ConnectionID) || !reProxyID(command.SessionID) {
		http.Error(w, "Invalid ReProxy Lifecycle Fence", http.StatusBadRequest)
		return
	}
	if action == "start" || action == "update" {
		session, err := s.start(command, action == "update")
		if err != nil {
			http.Error(w, err.Error(), http.StatusConflict)
			return
		}
		s.owner.writeJSON(w, http.StatusAccepted, session.status())
		return
	}
	if action == "stop" && !reProxyID(command.OperationID) {
		http.Error(w, "Operation Identity Is Required", http.StatusBadRequest)
		return
	}
	s.mu.Lock()
	exists := s.sessions[command.ConnectionID] != nil
	session := s.fenced(command.ConnectionID, command.SessionID, generation)
	if session == nil {
		s.mu.Unlock()
		if exists {
			http.Error(w, "Stale ReProxy Lifecycle Fence", http.StatusConflict)
		} else {
			http.NotFound(w, r)
		}
		return
	}
	if action == "stop" {
		session.mu.Lock()
		session.command.OperationID = command.OperationID
		session.stopLocked("Stopped")
		session.state = "STOPPED"
		session.lastError = "Stopped"
		session.mu.Unlock()
	}
	s.mu.Unlock()
	s.owner.writeJSON(w, http.StatusOK, session.status())
}
func reProxyOrigin(host string, port int) string {
	host = strings.ToLower(host)
	if port == 443 {
		if strings.Contains(host, ":") {
			host = "[" + host + "]"
		}
		return "wss://" + host
	}
	return "wss://" + net.JoinHostPort(host, strconv.Itoa(port))
}
func reProxyID(value string) bool {
	return value != "" && len(value) <= 160 && !strings.ContainsAny(value, "\r\n\x00")
}
func reProxyUUID(value string) (string, error) {
	if len(value) != 36 || value[8] != '-' || value[13] != '-' || value[18] != '-' || value[23] != '-' {
		return "", errors.New("Invalid Endpoint Identity")
	}
	raw, err := hex.DecodeString(strings.ReplaceAll(value, "-", ""))
	if err != nil || len(raw) != 16 {
		return "", errors.New("Invalid Endpoint Identity")
	}
	return hex.EncodeToString(raw), nil
}
func reProxyLimitText(raw json.RawMessage) string {
	if len(raw) == 0 {
		return ""
	}
	var value string
	if json.Unmarshal(raw, &value) == nil {
		return value
	}
	return string(raw)
}
func reProxyNumber(value string, fallback, upper int64) int64 {
	number, err := strconv.ParseInt(value, 10, 64)
	if err != nil || number <= 0 {
		return fallback
	}
	if number > upper {
		return upper
	}
	return number
}
func reProxyBound(value, fallback, upper int) int {
	if value <= 0 {
		return fallback
	}
	if value > upper {
		return upper
	}
	return value
}
func (s *agentReProxyService) approved(command agentReProxyCommand) (string, map[string]agentReProxyTarget, error) {
	ticket := command.Ticket
	if !reProxyID(command.OperationID) || ticket.ConnectionID != command.ConnectionID || ticket.SessionID != command.SessionID || ticket.Generation != command.Generation || ticket.RouteRevision != command.RouteRevision || (len(ticket.Token) == 0 || len(ticket.Token) > 4096 || strings.ContainsAny(ticket.Token, "\r\n\x00")) {
		return "", nil, errors.New("ReProxy Ticket Fence Does Not Match")
	}
	expiry, err := time.Parse(time.RFC3339Nano, ticket.ExpiresAt)
	if err != nil || !expiry.After(time.Now()) {
		return "", nil, errors.New("ReProxy Ticket Expired")
	}
	if ticket.Node.TunnelScheme != "wss" || ticket.Node.TunnelHost == "" || ticket.Node.TunnelPort < 1 || ticket.Node.TunnelPort > 65535 {
		return "", nil, errors.New("Invalid ReProxy Relay")
	}
	if strings.ContainsAny(ticket.Node.TunnelHost, "/?#@\r\n") {
		return "", nil, errors.New("Invalid ReProxy Relay Host")
	}
	host := strings.ToLower(ticket.Node.TunnelHost)
	origin := reProxyOrigin(host, ticket.Node.TunnelPort)
	targets := make(map[string]agentReProxyTarget)
	if len(ticket.Endpoints) > reProxyBound(ticket.Limits.MaxEndpoints, 64, 64) {
		return "", nil, errors.New("Too Many ReProxy Endpoints")
	}
	for _, endpoint := range ticket.Endpoints {
		if !endpoint.Enabled {
			continue
		}
		id, err := reProxyUUID(endpoint.ID)
		if err != nil {
			return "", nil, err
		}
		ip := net.ParseIP(endpoint.TargetHost)
		if ip == nil || !ip.IsLoopback() {
			return "", nil, errors.New("ReProxy Target Must Be Explicit Loopback")
		}
		protocol := byte(1)
		if endpoint.Protocol == "UDP" {
			protocol = 2
		} else if endpoint.Protocol != "TCP" {
			return "", nil, errors.New("Unsupported ReProxy Transport")
		}
		granted := false
		for _, grant := range s.grants {
			if grant.LifecycleID == command.LifecycleID && grant.Protocol == endpoint.Protocol && grant.Port == endpoint.TargetPort && grant.RelayOrigin == origin {
				granted = true
				break
			}
		}
		if !granted {
			return "", nil, errors.New("ReProxy Socket Grant Is Required")
		}
		if _, exists := targets[id]; exists {
			return "", nil, errors.New("Duplicate ReProxy Endpoint")
		}
		targets[id] = agentReProxyTarget{id, protocol, ip.String(), endpoint.TargetPort}
	}
	if len(targets) == 0 {
		return "", nil, errors.New("ReProxy Has No Granted Endpoints")
	}
	return origin, targets, nil
}
func (s *agentReProxyService) start(command agentReProxyCommand, update bool) (*agentReProxySession, error) {
	origin, targets, err := s.approved(command)
	if err != nil {
		return nil, err
	}
	generation, err := strconv.ParseUint(command.Generation, 10, 64)
	if err != nil || generation == 0 {
		return nil, errors.New("Invalid Generation")
	}
	revision, err := strconv.ParseUint(command.RouteRevision, 10, 64)
	if err != nil || revision == 0 {
		return nil, errors.New("Invalid Route Revision")
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed {
		return nil, errors.New("Agent Is Closed")
	}
	existing := s.sessions[command.ConnectionID]
	var previous *agentReProxySession
	if existing != nil {
		existing.mu.Lock()
		if generation < existing.generation || (generation == existing.generation && (existing.stopped && existing.state != "FAILED" || existing.command.SessionID != command.SessionID || existing.command.LifecycleID != command.LifecycleID || existing.origin != origin)) {
			existing.mu.Unlock()
			return nil, errors.New("Stale ReProxy Generation")
		}
		if generation == existing.generation && !existing.stopped {
			if !update && existing.command.OperationID != command.OperationID {
				existing.mu.Unlock()
				return nil, errors.New("ReProxy Is Already Started")
			}
			if revision < existing.desiredRevision {
				existing.mu.Unlock()
				return nil, errors.New("Stale ReProxy Revision")
			}
			if revision == existing.desiredRevision && (!sameReProxyTargets(existing.allowed, targets)) {
				existing.mu.Unlock()
				return nil, errors.New("Conflicting ReProxy Revision")
			}
			existing.allowed = targets
			existing.desiredRevision = revision
			existing.command.OperationID = command.OperationID
			existing.mu.Unlock()
			return existing, nil
		}
		mayRetain := !existing.revoked
		existing.mu.Unlock()
		if generation > existing.generation && existing.command.ConnectionID == command.ConnectionID && s.permitted(existing) {
			previous = existing
		} else if mayRetain && existing.previous != nil && existing.previous.command.ConnectionID == command.ConnectionID && generation > existing.previous.generation && s.permitted(existing.previous) {
			previous = existing.previous
		}
		retained := existing.previous
		existing.previous = nil
		if existing != previous {
			existing.stop("Superseded")
		}
		if retained != nil && retained != previous {
			retained.stop("Superseded")
		}
	} else if update {
		return nil, errors.New("ReProxy Is Not Started")
	}
	if len(s.sessions) >= 256 && existing == nil {
		return nil, errors.New("ReProxy Lifecycle Capacity Reached")
	}
	ctx, cancel := context.WithCancel(context.Background())
	limits := command.Ticket.Limits
	session := &agentReProxySession{service: s, previous: previous, command: command, origin: origin, generation: generation, desiredRevision: revision, allowed: targets, active: make(map[string]agentReProxyTarget), flows: make(map[uint64]*agentReProxyFlow), ctx: ctx, cancel: cancel, done: make(chan struct{}), send: make(chan agentReProxyQueued, 256), state: "CONNECTING", maxEndpoints: reProxyBound(limits.MaxEndpoints, 64, 64), maxStreams: reProxyBound(limits.MaxStreams, 256, 4096), maxUDP: reProxyBound(limits.MaxUDPFlows, 256, 4096), maxQueued: reProxyNumber(reProxyLimitText(limits.QueuedBytes), 4*1024*1024, 32*1024*1024), maxPackets: int64(reProxyBound(limits.Packets, 10000, 1000000)), maxBytes: reProxyNumber(limits.Bytes, 64*1024*1024, 1024*1024*1024), maxAdmissions: reProxyBound(limits.Admissions, 256, 10000), datagramAge: time.Duration(reProxyBound(limits.DatagramAge, 1000, 10000)) * time.Millisecond}
	s.sessions[command.ConnectionID] = session
	go session.run()
	return session, nil
}
func sameReProxyTargets(a, b map[string]agentReProxyTarget) bool {
	if len(a) != len(b) {
		return false
	}
	for id, target := range a {
		if other, ok := b[id]; !ok || other != target {
			return false
		}
	}
	return true
}
func (s *agentReProxySession) status() agentReProxyStatus {
	s.mu.Lock()
	defer s.mu.Unlock()
	return agentReProxyStatus{s.command.OperationID, s.command.ConnectionID, s.command.SessionID, s.command.Generation, strconv.FormatUint(s.activeRevision, 10), s.state, s.authenticated, s.ready && !s.stopped, s.streams, s.udp, strconv.FormatUint(s.dropped, 10), s.lastError}
}
func (s *agentReProxySession) stop(reason string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.stopLocked(reason)
	s.state = "STOPPED"
	s.lastError = reason
}
func (s *agentReProxySession) stopLocked(reason string) {
	if s.stopped {
		return
	}
	s.stopped = true
	s.ready = false
	s.state = "STOPPED"
	s.lastError = reason
	s.cancel()
	close(s.done)
	if s.socket != nil {
		_ = s.socket.close()
	}
	for _, flow := range s.flows {
		s.closeFlowLocked(flow)
	}
}
func (s *agentReProxySession) fail(err error) {
	s.mu.Lock()
	failed := !s.stopped
	if failed {
		s.stopLocked(err.Error())
		s.state = "FAILED"
	}
	s.mu.Unlock()
	if failed {
		s.service.restore(s)
	}
}
func reProxyFrame(kind byte, id uint64, payload []byte) []byte {
	frame := make([]byte, 13+len(payload))
	frame[0] = kind
	binary.BigEndian.PutUint64(frame[1:9], id)
	binary.BigEndian.PutUint32(frame[9:13], uint32(len(payload)))
	copy(frame[13:], payload)
	return frame
}
func (s *agentReProxySession) enqueueLocked(kind byte, id uint64, payload []byte) bool {
	if s.stopped {
		return false
	}
	size := int64(13 + len(payload))
	if s.queued+size > s.maxQueued {
		return false
	}
	frame := reProxyFrame(kind, id, payload)
	select {
	case s.send <- agentReProxyQueued{frame, s.flows[id]}:
		s.queued += size
		return true
	default:
		return false
	}
}
func (s *agentReProxySession) sendFrame(kind byte, id uint64, payload []byte) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.enqueueLocked(kind, id, payload)
}
func (s *agentReProxySession) run() {
	defer func() { s.mu.Lock(); s.command.Ticket.Token = ""; s.mu.Unlock() }()
	dialContext, cancelDial := context.WithTimeout(s.ctx, 20*time.Second)
	socket, err := dialAgentWebSocket(dialContext, s.origin+"/reproxy/v2/tunnel", agentWebSocketConfig{NoSubprotocol: true, MaxFrame: 65520})
	cancelDial()
	if err != nil {
		s.fail(err)
		return
	}
	socket.readTimeout = 35 * time.Second
	socket.writeTimeout = 5 * time.Second
	s.mu.Lock()
	if s.stopped {
		s.mu.Unlock()
		_ = socket.close()
		return
	}
	s.socket = socket
	token := s.command.Ticket.Token
	s.command.Ticket.Token = ""
	s.mu.Unlock()
	auth, _ := json.Marshal(map[string]any{"version": 2, "sessionId": s.command.SessionID, "generation": s.command.Generation, "token": token})
	if err = socket.writeMessage(2, reProxyFrame(1, 0, auth)); err != nil {
		s.fail(err)
		return
	}
	token = ""
	auth = nil
	go s.writer(socket)
	for {
		opcode, data, err := socket.readMessage()
		if err != nil {
			s.fail(err)
			return
		}
		if opcode != 2 || len(data) < 13 || len(data) > 65520 || int(binary.BigEndian.Uint32(data[9:13])) != len(data)-13 {
			s.fail(errors.New("Invalid ReProxy Frame"))
			return
		}
		kind := data[0]
		id := binary.BigEndian.Uint64(data[1:9])
		payload := data[13:]
		if err = s.frame(kind, id, payload); err != nil {
			s.fail(err)
			return
		}
		if kind == 19 {
			s.service.revoked(s)
			return
		}
	}
}
func (s *agentReProxySession) writer(socket *agentWebSocket) {
	ticker := time.NewTicker(10 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case queued := <-s.send:
			data := queued.data
			s.mu.Lock()
			s.queued -= int64(len(data))
			id := binary.BigEndian.Uint64(data[1:9])
			current := s.flows[id]
			stale := queued.flow != nil && ((data[0] == 7 || data[0] == 12 || data[0] == 14) && current != queued.flow || current != nil && current != queued.flow)
			stopped := s.stopped
			s.mu.Unlock()
			if stopped {
				return
			}
			if stale {
				continue
			}
			if err := socket.writeMessage(2, data); err != nil {
				s.fail(err)
				return
			}
			if data[0] == 15 {
				s.service.committed(s, binary.BigEndian.Uint64(data[13:21]))
			}
			if data[0] == 14 {
				s.mu.Lock()
				if flow := queued.flow; flow != nil && s.flows[id] == flow {
					flow.readSent = true
					if flow.writeEOF {
						s.closeFlowLocked(flow)
					}
				}
				s.mu.Unlock()
			}
		case <-ticker.C:
			s.mu.Lock()
			for _, flow := range s.flows {
				if flow.target.protocol == 2 && time.Since(flow.last) > 60*time.Second {
					s.enqueueLocked(13, flow.id, nil)
					s.closeFlowLocked(flow)
				}
			}
			s.enqueueLocked(4, 0, nil)
			s.mu.Unlock()
		case <-s.done:
			return
		}
	}
}
func (s *agentReProxySession) frame(kind byte, id uint64, payload []byte) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.stopped {
		return net.ErrClosed
	}
	if kind == 2 {
		if id != 0 || s.authenticated {
			return errors.New("Invalid ReProxy Authentication")
		}
		var auth struct {
			SessionID     string `json:"sessionId"`
			Generation    string `json:"generation"`
			RouteRevision string `json:"routeRevision"`
			Lease         string `json:"leaseExpiresAt"`
		}
		if json.Unmarshal(payload, &auth) != nil || auth.SessionID != s.command.SessionID || auth.Generation != s.command.Generation {
			return errors.New("ReProxy Authentication Fence Mismatch")
		}
		lease, err := time.Parse(time.RFC3339Nano, auth.Lease)
		if err != nil || !lease.After(time.Now()) {
			return errors.New("ReProxy Authority Lease Expired")
		}
		s.authenticated = true
		s.state = "PREPARING"
		return nil
	}
	if kind == 3 {
		return errors.New("ReProxy Authentication Rejected")
	}
	if !s.authenticated {
		return errors.New("ReProxy Frame Before Authentication")
	}
	switch kind {
	case 4, 5:
		if id != 0 || len(payload) > 125 {
			return errors.New("Invalid ReProxy Ping")
		}
		if kind == 4 && !s.enqueueLocked(5, 0, payload) {
			return errors.New("ReProxy Control Queue Full")
		}
	case 15, 16:
		if id != 0 {
			return errors.New("Invalid ReProxy Snapshot ID")
		}
		var revision uint64
		var targets map[string]agentReProxyTarget
		var err error
		if kind == 15 {
			revision, targets, err = s.snapshotTargets(payload, s.active)
		} else {
			revision, targets, err = s.snapshot(payload)
		}
		if err != nil {
			if kind == 16 && len(payload) >= 8 {
				if !s.ackLocked(binary.BigEndian.Uint64(payload[:8]), false, err.Error()) {
					return errors.New("ReProxy Control Queue Full")
				}
				return nil
			}
			return err
		}
		if kind == 15 {
			if revision != s.activeRevision || !sameReProxyTargets(s.active, targets) {
				return errors.New("Committed ReProxy Snapshot Does Not Match Activation")
			}
			if !s.enqueueLocked(15, 0, payload[:8]) {
				return errors.New("ReProxy Control Queue Full")
			}
			return nil
		}
		if revision < s.activeRevision || revision < s.preparedRevision {
			return errors.New("Stale ReProxy Snapshot")
		}
		if (revision == s.activeRevision && s.activeRevision != 0 && !sameReProxyTargets(s.active, targets)) || (revision == s.preparedRevision && !sameReProxyTargets(s.prepared, targets)) {
			return errors.New("Conflicting ReProxy Snapshot")
		}
		s.prepared = targets
		s.preparedRevision = revision
		if !s.ackLocked(revision, true, "") {
			return errors.New("ReProxy Control Queue Full")
		}
	case 18:
		if id != 0 || len(payload) != 8 {
			return errors.New("Invalid ReProxy Activation")
		}
		revision := binary.BigEndian.Uint64(payload)
		if revision != s.preparedRevision || s.prepared == nil {
			return errors.New("Unprepared ReProxy Activation")
		}
		if revision != s.activeRevision {
			s.activateLocked(revision)
		}
		if !s.enqueueLocked(18, 0, payload) {
			return errors.New("ReProxy Control Queue Full")
		}
	case 19:
		if id != 0 || len(payload) > 1024 {
			return errors.New("Invalid ReProxy Revocation")
		}
		s.revoked = true
		s.stopLocked("Revoked")
	case 10, 11:
		if id == 0 || len(payload) != 16 {
			return errors.New("Invalid ReProxy Flow Admission")
		}
		if _, exists := s.flows[id]; exists {
			return errors.New("Duplicate ReProxy Flow ID")
		}
		target, exists := s.active[hex.EncodeToString(payload)]
		protocol := byte(1)
		if kind == 11 {
			protocol = 2
		}
		if !s.ready || !exists || target.protocol != protocol {
			return errors.New("ReProxy Endpoint Is Not Activated")
		}
		s.resetBudgetLocked()
		if s.admissions >= s.maxAdmissions || protocol == 1 && s.streams >= s.maxStreams || protocol == 2 && s.udp >= s.maxUDP {
			s.enqueueLocked(9, id, []byte("ReProxy Flow Capacity Reached"))
			if protocol == 2 {
				s.enqueueLocked(13, id, nil)
			}
			return nil
		}
		s.admissions++
		flow := &agentReProxyFlow{id: id, target: target, queue: make(chan agentReProxyPacket, 64), done: make(chan struct{}), last: time.Now()}
		s.flows[id] = flow
		if protocol == 1 {
			s.streams++
		} else {
			s.udp++
		}
		go s.openFlow(flow)
	case 7, 12, 14, 8, 9, 13:
		if id == 0 {
			return errors.New("Invalid ReProxy Flow ID")
		}
		flow := s.flows[id]
		if flow == nil {
			return nil
		}
		if kind == 8 || kind == 9 || kind == 13 {
			if (kind == 13) != (flow.target.protocol == 2) || len(payload) > 1024 {
				return errors.New("Invalid ReProxy Flow Close")
			}
			s.closeFlowLocked(flow)
			return nil
		}
		if kind == 14 && len(payload) != 0 {
			return errors.New("Invalid ReProxy Half Close")
		}
		if flow.target.protocol == 2 && kind != 12 || flow.target.protocol == 1 && kind != 7 && kind != 14 || kind == 12 && len(payload) > 65507 {
			return errors.New("Invalid ReProxy Flow Transport")
		}
		if flow.writeRequested {
			return errors.New("ReProxy Data After Half Close")
		}
		s.resetBudgetLocked()
		size := int64(len(payload) + 13)
		if kind == 7 && s.transferred+int64(len(payload)) > s.maxBytes {
			s.enqueueLocked(9, id, []byte("TCP Traffic Limit Reached"))
			s.closeFlowLocked(flow)
			return nil
		}
		if kind == 12 && (s.packets >= s.maxPackets || s.transferred+int64(len(payload)) > s.maxBytes) {
			s.dropped++
			s.lastError = "UDP Traffic Limit Reached"
			return nil
		}
		if flow.queued+size > 256*1024 || s.queued+size > s.maxQueued {
			if kind == 12 {
				s.dropped++
				s.lastError = "UDP Queue Full"
				return nil
			}
			s.enqueueLocked(9, id, []byte("TCP Queue Full"))
			s.closeFlowLocked(flow)
			return nil
		}
		packet := agentReProxyPacket{kind, bytes.Clone(payload), time.Now()}
		select {
		case flow.queue <- packet:
			flow.queued += size
			s.queued += size
			flow.last = time.Now()
			s.transferred += int64(len(payload))
			if kind == 12 {
				s.packets++
			}
			if kind == 14 {
				flow.writeRequested = true
			}
		default:
			if kind == 12 {
				s.dropped++
				s.lastError = "UDP Queue Full"
			} else {
				s.enqueueLocked(9, id, []byte("TCP Queue Full"))
				s.closeFlowLocked(flow)
			}
		}
	default:
		return errors.New("Unsupported ReProxy Frame")
	}
	return nil
}
func (s *agentReProxySession) resetBudgetLocked() {
	if time.Since(s.window) >= time.Second {
		s.window = time.Now()
		s.packets = 0
		s.transferred = 0
		s.admissions = 0
	}
}
func (s *agentReProxySession) snapshot(payload []byte) (uint64, map[string]agentReProxyTarget, error) {
	return s.snapshotTargets(payload, s.allowed)
}
func (s *agentReProxySession) snapshotTargets(payload []byte, approved map[string]agentReProxyTarget) (uint64, map[string]agentReProxyTarget, error) {
	if len(payload) < 10 {
		return 0, nil, errors.New("Short ReProxy Snapshot")
	}
	revision := binary.BigEndian.Uint64(payload[:8])
	count := int(binary.BigEndian.Uint16(payload[8:10]))
	if revision == 0 || count > s.maxEndpoints {
		return 0, nil, errors.New("Invalid ReProxy Snapshot")
	}
	targets := make(map[string]agentReProxyTarget, count)
	offset := 10
	for index := 0; index < count; index++ {
		if len(payload)-offset < 21 {
			return 0, nil, errors.New("Short ReProxy Endpoint")
		}
		id := hex.EncodeToString(payload[offset : offset+16])
		protocol := payload[offset+16]
		port := int(binary.BigEndian.Uint16(payload[offset+17 : offset+19]))
		length := int(binary.BigEndian.Uint16(payload[offset+19 : offset+21]))
		offset += 21
		if length < 1 || length > 255 || length > len(payload)-offset || !utf8.Valid(payload[offset:offset+length]) {
			return 0, nil, errors.New("Invalid ReProxy Target")
		}
		host := string(payload[offset : offset+length])
		offset += length
		ip := net.ParseIP(host)
		allowed, exists := approved[id]
		if ip == nil || !ip.IsLoopback() || !exists || allowed.protocol != protocol || allowed.port != port || allowed.host != ip.String() {
			return 0, nil, errors.New("ReProxy Target Is Not Granted")
		}
		if _, duplicate := targets[id]; duplicate {
			return 0, nil, errors.New("Duplicate ReProxy Endpoint")
		}
		targets[id] = allowed
	}
	if offset != len(payload) {
		return 0, nil, errors.New("Trailing ReProxy Snapshot Bytes")
	}
	return revision, targets, nil
}
func (s *agentReProxySession) activateLocked(revision uint64) {
	for _, flow := range s.flows {
		target, exists := s.prepared[flow.target.id]
		if !exists || target != flow.target {
			s.closeFlowLocked(flow)
		}
	}
	s.active = s.prepared
	s.activeRevision = revision
	s.ready = false
	s.state = "ACTIVATING"
}
func (s *agentReProxySession) ackLocked(revision uint64, success bool, message string) bool {
	payload := make([]byte, 9)
	binary.BigEndian.PutUint64(payload, revision)
	if success {
		payload[8] = 1
	}
	if len(message) > 512 {
		message = message[:512]
	}
	payload = append(payload, []byte(message)...)
	return s.enqueueLocked(17, 0, payload)
}
func (s *agentReProxySession) closeFlowLocked(flow *agentReProxyFlow) {
	if s.flows[flow.id] != flow {
		return
	}
	delete(s.flows, flow.id)
	if flow.target.protocol == 1 {
		s.streams--
	} else {
		s.udp--
	}
	s.queued -= flow.queued
	flow.queued = 0
	flow.once.Do(func() {
		close(flow.done)
		if flow.conn != nil {
			_ = flow.conn.Close()
		}
	})
}
func (s *agentReProxySession) flowFailure(flow *agentReProxyFlow, err error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.flows[flow.id] != flow {
		return
	}
	kind := byte(9)
	if flow.target.protocol == 2 {
		kind = 13
	}
	message := []byte(err.Error())
	if len(message) > 512 {
		message = message[:512]
	}
	if kind == 13 {
		message = nil
	}
	s.enqueueLocked(kind, flow.id, message)
	s.closeFlowLocked(flow)
}
func (s *agentReProxySession) openFlow(flow *agentReProxyFlow) {
	protocol := "tcp"
	if flow.target.protocol == 2 {
		protocol = "udp"
	}
	dialer := net.Dialer{Timeout: 5 * time.Second}
	conn, err := dialer.DialContext(s.ctx, protocol, net.JoinHostPort(flow.target.host, strconv.Itoa(flow.target.port)))
	if err != nil {
		s.flowFailure(flow, err)
		return
	}
	s.mu.Lock()
	if s.stopped || s.flows[flow.id] != flow {
		s.mu.Unlock()
		_ = conn.Close()
		return
	}
	flow.conn = conn
	s.mu.Unlock()
	go s.readFlow(flow, conn)
	for {
		select {
		case packet := <-flow.queue:
			s.mu.Lock()
			if s.flows[flow.id] != flow {
				s.mu.Unlock()
				return
			}
			size := int64(len(packet.data) + 13)
			s.queued -= size
			flow.queued -= size
			expired := packet.kind == 12 && time.Since(packet.at) > s.datagramAge
			if expired {
				s.dropped++
				s.lastError = "UDP Datagram Expired"
			}
			s.mu.Unlock()
			if expired {
				continue
			}
			_ = conn.SetWriteDeadline(time.Now().Add(5 * time.Second))
			if packet.kind == 14 {
				tcp, ok := conn.(*net.TCPConn)
				if !ok {
					s.flowFailure(flow, errors.New("TCP Half Close Unsupported"))
					return
				}
				err = tcp.CloseWrite()
			} else {
				written := 0
				for written < len(packet.data) || packet.kind == 12 {
					var n int
					n, err = conn.Write(packet.data[written:])
					written += n
					if packet.kind == 12 || err != nil {
						break
					}
					if n == 0 {
						err = io.ErrNoProgress
						break
					}
				}
			}
			if err != nil {
				s.flowFailure(flow, err)
				return
			}
			if packet.kind == 14 {
				s.mu.Lock()
				flow.writeEOF = true
				if flow.readSent {
					s.closeFlowLocked(flow)
				}
				s.mu.Unlock()
			}
		case <-flow.done:
			return
		case <-s.done:
			return
		}
	}
}
func (s *agentReProxySession) readFlow(flow *agentReProxyFlow, conn net.Conn) {
	buffer := make([]byte, 32*1024)
	if flow.target.protocol == 2 {
		buffer = make([]byte, 65508)
	}
	for {
		if flow.target.protocol == 2 {
			_ = conn.SetReadDeadline(time.Now().Add(60 * time.Second))
		}
		count, err := conn.Read(buffer)
		if count > 0 || flow.target.protocol == 2 && err == nil {
			s.mu.Lock()
			if s.stopped || s.flows[flow.id] != flow {
				s.mu.Unlock()
				return
			}
			flow.last = time.Now()
			s.resetBudgetLocked()
			kind := byte(7)
			if flow.target.protocol == 2 {
				kind = 12
			}
			if kind == 7 && s.transferred+int64(count) > s.maxBytes {
				s.enqueueLocked(9, flow.id, []byte("TCP Traffic Limit Reached"))
				s.closeFlowLocked(flow)
				s.mu.Unlock()
				return
			}
			if kind == 12 && (count > 65507 || s.packets >= s.maxPackets || s.transferred+int64(count) > s.maxBytes) {
				s.dropped++
				s.lastError = "UDP Traffic Limit Reached"
				s.mu.Unlock()
				continue
			}
			sent := s.enqueueLocked(kind, flow.id, buffer[:count])
			s.transferred += int64(count)
			if kind == 12 {
				s.packets++
				if !sent {
					s.dropped++
					s.lastError = "UDP Queue Full"
				}
			}
			s.mu.Unlock()
			if !sent && kind == 7 {
				s.flowFailure(flow, errors.New("TCP Queue Full"))
				return
			}
		}
		if err != nil {
			if errors.Is(err, io.EOF) && flow.target.protocol == 1 {
				s.mu.Lock()
				if s.flows[flow.id] == flow {
					flow.readEOF = true
					if !s.enqueueLocked(14, flow.id, nil) {
						s.closeFlowLocked(flow)
					}
				}
				s.mu.Unlock()
				return
			}
			s.flowFailure(flow, err)
			return
		}
	}
}
