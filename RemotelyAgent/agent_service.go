package main

import (
	"archive/tar"
	"archive/zip"
	"compress/gzip"
	"context"
	"crypto/rand"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

const lifecycleStopInput = "\nstop\n"

type AgentRoot struct {
	ID      string `json:"id"`
	Path    string `json:"path"`
	Read    bool   `json:"read"`
	Write   bool   `json:"write"`
	Execute bool   `json:"execute"`
	Archive bool   `json:"archive"`
}

type AgentLifecycleBinding struct {
	ID        string `json:"id"`
	Root      string `json:"root"`
	Directory string `json:"directory"`
	Command   string `json:"-"`
}

type AgentWorkflowBinding struct {
	ID        string   `json:"id"`
	Root      string   `json:"root"`
	Directory string   `json:"directory,omitempty"`
	Tool      string   `json:"-"`
	Args      []string `json:"-"`
}

type AgentLSPBinding struct {
	ID        string   `json:"id"`
	Root      string   `json:"root"`
	Directory string   `json:"directory,omitempty"`
	Program   string   `json:"-"`
	Args      []string `json:"-"`
}

type AgentServiceConfig struct {
	Token           []byte
	TokenFile       string
	AllowedOrigins  []string
	Roots           []AgentRoot
	Lifecycles      []AgentLifecycleBinding
	Workflows       []AgentWorkflowBinding
	ReProxy         []AgentReProxyGrant
	LSP             []AgentLSPBinding
	MaxFileBytes    int64
	MaxArchiveBytes int64
	MaxProcessBytes int64
	MaxJobs         int
}

type agentRoot struct {
	AgentRoot
	canonical  string
	filesystem *os.Root
}

type AgentService struct {
	config     AgentServiceConfig
	token      []byte
	roots      map[string]agentRoot
	lifecycles map[string]AgentLifecycleBinding
	jobs       *agentJobManager
	processes  *agentProcessManager
	developer  *agentDeveloperService
	reproxy    *agentReProxyService
}

type agentCapabilities struct {
	Version    string                     `json:"version"`
	Transport  string                     `json:"transport"`
	Auth       []string                   `json:"auth"`
	Roots      []AgentRoot                `json:"roots"`
	Lifecycles []AgentLifecycleBinding    `json:"lifecycles"`
	Features   map[string]bool            `json:"features"`
	ReProxy    agentReProxyCapabilities   `json:"reproxy"`
	Developer  agentDeveloperCapabilities `json:"developer"`
}

type agentEntry struct {
	Name       string `json:"name"`
	Path       string `json:"path"`
	Directory  bool   `json:"directory"`
	Symlink    bool   `json:"symlink"`
	Size       int64  `json:"size"`
	ModifiedAt int64  `json:"modifiedAt"`
	Mode       uint32 `json:"mode"`
}

type agentFileWriteResult struct {
	Path string `json:"path"`
	Size int64  `json:"size"`
}

type agentArchiveRequest struct {
	Format     string `json:"format"`
	SourceRoot string `json:"sourceRoot"`
	SourcePath string `json:"sourcePath"`
	TargetRoot string `json:"targetRoot"`
	TargetPath string `json:"targetPath"`
	Overwrite  bool   `json:"overwrite"`
}

type agentArchiveResult struct {
	Entries int   `json:"entries"`
	Bytes   int64 `json:"bytes"`
}

type agentProcessRequest struct {
	Root        string            `json:"root"`
	Path        string            `json:"path"`
	WorkingDir  string            `json:"workingDir"`
	Args        []string          `json:"args"`
	Environment map[string]string `json:"environment"`
	Terminal    bool              `json:"terminal"`
}

type agentTerminalResizeRequest struct {
	Rows    int `json:"rows"`
	Columns int `json:"columns"`
}

type agentProcessSnapshot struct {
	ID          string `json:"id"`
	JobID       string `json:"jobId,omitempty"`
	Status      string `json:"status"`
	Path        string `json:"path"`
	Terminal    bool   `json:"terminal"`
	PID         int    `json:"pid,omitempty"`
	ExitCode    int    `json:"exitCode,omitempty"`
	StartedAt   int64  `json:"startedAt"`
	FinishedAt  int64  `json:"finishedAt,omitempty"`
	OutputSize  int64  `json:"outputSize"`
	OutputLimit bool   `json:"outputLimit"`
	Error       string `json:"error,omitempty"`
}

type agentProcessOutput struct {
	mu        sync.Mutex
	data      []byte
	max       int64
	truncated bool
}

func newAgentProcessOutput(max int64) *agentProcessOutput {
	return &agentProcessOutput{max: max}
}

func (o *agentProcessOutput) Write(data []byte) (int, error) {
	o.mu.Lock()
	defer o.mu.Unlock()
	if o.max <= 0 {
		return len(data), nil
	}
	remaining := o.max - int64(len(o.data))
	if remaining <= 0 {
		o.truncated = true
		return len(data), nil
	}
	if int64(len(data)) > remaining {
		o.data = append(o.data, data[:remaining]...)
		o.truncated = true
		return len(data), nil
	}
	o.data = append(o.data, data...)
	return len(data), nil
}

func (o *agentProcessOutput) read(offset, limit int64) ([]byte, int64, bool, error) {
	o.mu.Lock()
	defer o.mu.Unlock()
	if offset < 0 {
		return nil, 0, false, errors.New("offset must not be negative")
	}
	if limit <= 0 || limit > 1024*1024 {
		limit = 256 * 1024
	}
	if offset > int64(len(o.data)) {
		offset = int64(len(o.data))
	}
	end := offset + limit
	if end > int64(len(o.data)) {
		end = int64(len(o.data))
	}
	return append([]byte(nil), o.data[offset:end]...), int64(len(o.data)), o.truncated, nil
}

func NewAgentService(config AgentServiceConfig) (*AgentService, error) {
	if len(config.Token) == 0 {
		if config.TokenFile == "" {
			return nil, errors.New("agent token is required")
		}
		token, err := loadAgentToken(config.TokenFile)
		if err != nil {
			return nil, err
		}
		config.Token = token
	}
	if len(config.Token) < 16 {
		return nil, errors.New("agent token must contain at least 16 bytes")
	}
	if len(config.Token) > 4096 || bytesContainLineBreak(config.Token) {
		return nil, errors.New("agent token is invalid")
	}
	if config.MaxFileBytes <= 0 {
		config.MaxFileBytes = 128 * 1024 * 1024
	}
	if config.MaxArchiveBytes <= 0 {
		config.MaxArchiveBytes = 512 * 1024 * 1024
	}
	if config.MaxProcessBytes <= 0 {
		config.MaxProcessBytes = 16 * 1024 * 1024
	}
	if config.MaxProcessBytes > 64*1024*1024 {
		config.MaxProcessBytes = 64 * 1024 * 1024
	}
	if config.MaxJobs <= 0 {
		config.MaxJobs = 64
	}
	if config.MaxJobs > 256 {
		config.MaxJobs = 256
	}

	service := &AgentService{
		config:     config,
		token:      append([]byte(nil), config.Token...),
		roots:      make(map[string]agentRoot),
		lifecycles: make(map[string]AgentLifecycleBinding),
		jobs:       newAgentJobManager(config.MaxJobs),
		processes:  newAgentProcessManager(config.MaxProcessBytes, config.MaxJobs),
	}
	for _, configured := range config.Roots {
		root, err := normalizeAgentRoot(configured)
		if err != nil {
			_ = service.Close()
			return nil, err
		}
		if _, exists := service.roots[root.ID]; exists {
			_ = root.filesystem.Close()
			_ = service.Close()
			return nil, fmt.Errorf("duplicate agent root %q", root.ID)
		}
		service.roots[root.ID] = root
	}
	for _, binding := range config.Lifecycles {
		if err := service.addLifecycleBinding(binding); err != nil {
			_ = service.Close()
			return nil, err
		}
	}
	developer, err := newAgentDeveloperService(service, config.Workflows, config.LSP)
	if err != nil {
		_ = service.Close()
		return nil, err
	}
	service.developer = developer
	service.reproxy, err = newAgentReProxyService(service, config.ReProxy)
	if err != nil {
		_ = service.Close()
		return nil, err
	}
	return service, nil
}

func normalizeAgentRoot(configured AgentRoot) (agentRoot, error) {
	if !validAgentID(configured.ID) {
		return agentRoot{}, fmt.Errorf("invalid agent root id %q", configured.ID)
	}
	if configured.Path == "" {
		return agentRoot{}, fmt.Errorf("agent root %q has no path", configured.ID)
	}
	abs, err := filepath.Abs(configured.Path)
	if err != nil {
		return agentRoot{}, err
	}
	canonical, err := filepath.EvalSymlinks(abs)
	if err != nil {
		return agentRoot{}, fmt.Errorf("agent root %q: %w", configured.ID, err)
	}
	filesystem, err := os.OpenRoot(canonical)
	if err != nil {
		return agentRoot{}, err
	}
	info, err := filesystem.Stat(".")
	if err != nil {
		_ = filesystem.Close()
		return agentRoot{}, err
	}
	if !info.IsDir() {
		_ = filesystem.Close()
		return agentRoot{}, fmt.Errorf("agent root %q is not a directory", configured.ID)
	}
	configured.Path = abs
	return agentRoot{AgentRoot: configured, canonical: canonical, filesystem: filesystem}, nil
}

func openAgentFilesystem(root agentRoot) (*os.Root, error) {
	if root.filesystem == nil {
		return nil, errors.New("agent root is closed")
	}
	return root.filesystem.OpenRoot(".")
}

func (s *AgentService) Close() error {
	if s == nil {
		return nil
	}
	var result error
	if s.reproxy != nil {
		s.reproxy.close()
	}
	if s.developer != nil {
		s.developer.close()
	}
	for id, root := range s.roots {
		if root.filesystem == nil {
			continue
		}
		if err := root.filesystem.Close(); err != nil && result == nil {
			result = err
		}
		root.filesystem = nil
		s.roots[id] = root
	}
	return result
}

func (s *AgentService) addLifecycleBinding(binding AgentLifecycleBinding) error {
	if !validAgentID(binding.ID) {
		return fmt.Errorf("invalid lifecycle id %q", binding.ID)
	}
	if binding.Root == "" || binding.Command == "" {
		return fmt.Errorf("lifecycle %q requires root and command", binding.ID)
	}
	root, ok := s.roots[binding.Root]
	if !ok {
		return fmt.Errorf("lifecycle %q references unknown root %q", binding.ID, binding.Root)
	}
	if !root.Execute {
		return fmt.Errorf("lifecycle %q requires execute capability on root %q", binding.ID, binding.Root)
	}
	relative, err := resolveAgentRelative(root, binding.Directory, false)
	if err != nil {
		return fmt.Errorf("lifecycle %q directory: %w", binding.ID, err)
	}
	filesystem, err := openAgentFilesystem(root)
	if err != nil {
		return err
	}
	defer filesystem.Close()
	info, err := filesystem.Stat(relative)
	if err != nil {
		return err
	}
	if !info.IsDir() {
		return fmt.Errorf("lifecycle %q directory is not a directory", binding.ID)
	}
	binding.Directory = filepath.Join(root.canonical, relative)
	if _, exists := s.lifecycles[binding.ID]; exists {
		return fmt.Errorf("duplicate lifecycle id %q", binding.ID)
	}
	s.lifecycles[binding.ID] = binding
	return nil
}

func (s *AgentService) Handler() http.Handler {
	return s
}

func (s *AgentService) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if !s.applyOrigin(w, r) {
		return
	}
	if r.Method == http.MethodOptions {
		w.WriteHeader(http.StatusNoContent)
		return
	}
	if !s.authenticate(r) {
		w.Header().Set("WWW-Authenticate", "Bearer")
		http.Error(w, "authentication required", http.StatusUnauthorized)
		return
	}
	if r.URL.Path == "/healthz" {
		s.writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
		return
	}
	if r.URL.Path == "/v1/capabilities" && r.Method == http.MethodGet {
		s.handleCapabilities(w)
		return
	}
	if strings.HasPrefix(r.URL.Path, "/v1/reproxy/") && s.reproxy != nil {
		s.reproxy.handle(w, r)
		return
	}
	if strings.HasPrefix(r.URL.Path, "/v1/roots/") {
		s.handleRoot(w, r)
		return
	}
	if strings.HasPrefix(r.URL.Path, "/v1/jobs/") {
		s.handleJob(w, r)
		return
	}
	if strings.HasPrefix(r.URL.Path, "/v1/processes") {
		s.handleProcess(w, r)
		return
	}
	if strings.HasPrefix(r.URL.Path, "/v1/archive/") {
		s.handleArchive(w, r)
		return
	}
	if strings.HasPrefix(r.URL.Path, "/v1/lifecycle/") {
		s.handleLifecycle(w, r)
		return
	}
	if strings.HasPrefix(r.URL.Path, "/v1/developer/") && s.developer != nil {
		s.developer.handle(w, r)
		return
	}
	http.NotFound(w, r)
}

func (s *AgentService) applyOrigin(w http.ResponseWriter, r *http.Request) bool {
	origin := r.Header.Get("Origin")
	if origin == "" {
		return true
	}
	allowed := false
	for _, configured := range s.config.AllowedOrigins {
		if configured == origin {
			allowed = true
			break
		}
	}
	if !allowed {
		http.Error(w, "origin is not allowed", http.StatusForbidden)
		return false
	}
	w.Header().Set("Access-Control-Allow-Origin", origin)
	w.Header().Set("Access-Control-Allow-Headers", "Authorization, Content-Type")
	w.Header().Set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
	w.Header().Set("Vary", "Origin")
	return true
}

func (s *AgentService) authenticate(r *http.Request) bool {
	header := r.Header.Get("Authorization")
	if !strings.HasPrefix(header, "Bearer ") {
		return false
	}
	provided := strings.TrimSpace(strings.TrimPrefix(header, "Bearer "))
	if provided == "" {
		return false
	}
	return subtle.ConstantTimeCompare([]byte(provided), s.token) == 1
}

func (s *AgentService) handleCapabilities(w http.ResponseWriter) {
	roots := make([]AgentRoot, 0, len(s.roots))
	for _, root := range s.roots {
		roots = append(roots, root.AgentRoot)
	}
	sort.Slice(roots, func(i, j int) bool { return roots[i].ID < roots[j].ID })
	lifecycles := make([]AgentLifecycleBinding, 0, len(s.lifecycles))
	for _, binding := range s.lifecycles {
		copyBinding := binding
		copyBinding.Directory = ""
		lifecycles = append(lifecycles, copyBinding)
	}
	sort.Slice(lifecycles, func(i, j int) bool { return lifecycles[i].ID < lifecycles[j].ID })
	s.writeJSON(w, http.StatusOK, agentCapabilities{
		Version:    agentVersion,
		Transport:  "http-loopback",
		Auth:       []string{"bearer"},
		Roots:      roots,
		Lifecycles: lifecycles,
		Developer:  s.developer.capabilities(),
		ReProxy:    s.reproxy.capabilities(),
		Features: map[string]bool{
			"filesystem": true,
			"archives":   true,
			"jobs":       true,
			"processes":  true,
			"terminal":   agentTerminalSupported(),
			"lifecycle":  len(lifecycles) > 0,
			"reproxy":    len(s.config.ReProxy) > 0,
		},
	})
}

func (s *AgentService) handleRoot(w http.ResponseWriter, r *http.Request) {
	parts := strings.Split(strings.Trim(strings.TrimPrefix(r.URL.Path, "/v1/roots/"), "/"), "/")
	if len(parts) < 2 || parts[0] == "" {
		http.NotFound(w, r)
		return
	}
	root, ok := s.roots[parts[0]]
	if !ok {
		http.NotFound(w, r)
		return
	}
	resource := strings.Join(parts[1:], "/")
	switch resource {
	case "entries":
		if r.Method != http.MethodGet {
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		s.handleEntries(w, r, root)
	case "file":
		s.handleFile(w, r, root)
	case "directory":
		if r.Method != http.MethodPost {
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		s.handleDirectory(w, r, root)
	case "entry":
		if r.Method != http.MethodDelete {
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		s.handleRemove(w, r, root)
	default:
		http.NotFound(w, r)
	}
}

func (s *AgentService) handleEntries(w http.ResponseWriter, r *http.Request, root agentRoot) {
	if !root.Read {
		http.Error(w, "read capability is not granted", http.StatusForbidden)
		return
	}
	relative, err := resolveAgentRelative(root, r.URL.Query().Get("path"), false)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	filesystem, err := openAgentFilesystem(root)
	if err != nil {
		http.Error(w, err.Error(), http.StatusNotFound)
		return
	}
	defer filesystem.Close()
	directory, err := filesystem.Open(relative)
	if err != nil {
		http.Error(w, err.Error(), http.StatusNotFound)
		return
	}
	defer directory.Close()
	entries, err := directory.ReadDir(-1)
	if err != nil {
		http.Error(w, err.Error(), http.StatusNotFound)
		return
	}
	result := make([]agentEntry, 0, len(entries))
	for _, entry := range entries {
		info, infoErr := entry.Info()
		if infoErr != nil {
			continue
		}
		entryRelative := filepath.Join(relative, entry.Name())
		if relative == "." {
			entryRelative = entry.Name()
		}
		result = append(result, agentEntry{
			Name:       entry.Name(),
			Path:       filepath.ToSlash(entryRelative),
			Directory:  info.IsDir(),
			Symlink:    info.Mode()&os.ModeSymlink != 0,
			Size:       info.Size(),
			ModifiedAt: info.ModTime().UnixMilli(),
			Mode:       uint32(info.Mode().Perm()),
		})
	}
	sort.Slice(result, func(i, j int) bool { return result[i].Name < result[j].Name })
	s.writeJSON(w, http.StatusOK, result)
}

func (s *AgentService) handleFile(w http.ResponseWriter, r *http.Request, root agentRoot) {
	relative, err := resolveAgentRelative(root, r.URL.Query().Get("path"), r.Method == http.MethodPut)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	switch r.Method {
	case http.MethodGet:
		if !root.Read {
			http.Error(w, "read capability is not granted", http.StatusForbidden)
			return
		}
		filesystem, err := openAgentFilesystem(root)
		if err != nil {
			http.Error(w, "file not found", http.StatusNotFound)
			return
		}
		defer filesystem.Close()
		file, err := filesystem.Open(relative)
		if err != nil {
			http.Error(w, "file not found", http.StatusNotFound)
			return
		}
		defer file.Close()
		info, err := file.Stat()
		if err != nil || !info.Mode().IsRegular() {
			http.Error(w, "file not found", http.StatusNotFound)
			return
		}
		if info.Size() > s.config.MaxFileBytes {
			http.Error(w, "file exceeds configured limit", http.StatusRequestEntityTooLarge)
			return
		}
		w.Header().Set("Content-Length", strconv.FormatInt(info.Size(), 10))
		http.ServeContent(w, r, filepath.ToSlash(relative), info.ModTime(), file)
	case http.MethodPut:
		if !root.Write {
			http.Error(w, "write capability is not granted", http.StatusForbidden)
			return
		}
		filesystem, err := openAgentFilesystem(root)
		if err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		defer filesystem.Close()
		result, err := writeAgentFileRoot(filesystem, relative, r.Body, s.config.MaxFileBytes)
		if err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		result.Path = filepath.ToSlash(relative)
		s.writeJSON(w, http.StatusOK, result)
	default:
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
	}
}

func (s *AgentService) handleDirectory(w http.ResponseWriter, r *http.Request, root agentRoot) {
	if !root.Write {
		http.Error(w, "write capability is not granted", http.StatusForbidden)
		return
	}
	relative, err := resolveAgentRelative(root, r.URL.Query().Get("path"), true)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	filesystem, err := openAgentFilesystem(root)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	defer filesystem.Close()
	if err := mkdirAgentAll(filesystem, relative); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	s.writeJSON(w, http.StatusOK, map[string]string{"path": r.URL.Query().Get("path")})
}

func (s *AgentService) handleRemove(w http.ResponseWriter, r *http.Request, root agentRoot) {
	if !root.Write {
		http.Error(w, "write capability is not granted", http.StatusForbidden)
		return
	}
	relative := r.URL.Query().Get("path")
	relative, err := resolveAgentRelative(root, relative, false)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	if relative == "." {
		http.Error(w, "root removal is not allowed", http.StatusBadRequest)
		return
	}
	filesystem, err := openAgentFilesystem(root)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	defer filesystem.Close()
	if r.URL.Query().Get("recursive") == "true" {
		err = removeAgentAll(filesystem, relative)
	} else {
		err = filesystem.Remove(relative)
	}
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *AgentService) resolveAgentPath(root agentRoot, relative string, write bool) (string, error) {
	return resolveAgentPath(root, relative, write)
}

func resolveAgentPath(root agentRoot, relative string, write bool) (string, error) {
	relative, err := resolveAgentRelative(root, relative, write)
	if err != nil {
		return "", err
	}
	return filepath.Join(root.canonical, relative), nil
}

func resolveAgentRelative(root agentRoot, relative string, write bool) (string, error) {
	relative, err := cleanAgentRelativePath(relative)
	if err != nil {
		return "", err
	}
	filesystem, err := openAgentFilesystem(root)
	if err != nil {
		return "", err
	}
	defer filesystem.Close()
	if _, err := filesystem.Lstat(relative); err == nil {
		if _, statErr := filesystem.Stat(relative); statErr != nil {
			return "", statErr
		}
		return relative, nil
	} else if !write || !errors.Is(err, os.ErrNotExist) {
		return "", err
	}
	parent := filepath.Dir(relative)
	for {
		info, parentErr := filesystem.Stat(parent)
		if parentErr == nil {
			if !info.IsDir() {
				return "", errors.New("path parent is not a directory")
			}
			return relative, nil
		}
		if !errors.Is(parentErr, os.ErrNotExist) {
			return "", parentErr
		}
		next := filepath.Dir(parent)
		if next == parent {
			return "", errors.New("path parent does not exist")
		}
		parent = next
	}
}

func cleanAgentRelativePath(relative string) (string, error) {
	if relative == "" {
		relative = "."
	}
	if strings.IndexByte(relative, 0) >= 0 || filepath.IsAbs(relative) || filepath.VolumeName(relative) != "" {
		return "", errors.New("path must be relative to the selected root")
	}
	relative = filepath.Clean(filepath.FromSlash(relative))
	if relative == ".." || strings.HasPrefix(relative, ".."+string(filepath.Separator)) {
		return "", errors.New("path escapes the selected root")
	}
	return relative, nil
}

func withinAgentRoot(root, candidate string) bool {
	relative, err := filepath.Rel(root, candidate)
	if err != nil || filepath.IsAbs(relative) {
		return false
	}
	return relative == "." || (relative != ".." && !strings.HasPrefix(relative, ".."+string(filepath.Separator)))
}

func ensureAgentDestination(root, candidate string) error {
	if !withinAgentRoot(root, candidate) {
		return errors.New("path escapes the selected root")
	}
	current := candidate
	for {
		if _, err := os.Lstat(current); err == nil {
			resolved, resolveErr := filepath.EvalSymlinks(current)
			if resolveErr != nil {
				return resolveErr
			}
			if !withinAgentRoot(root, resolved) {
				return errors.New("path resolves outside the selected root")
			}
			return nil
		} else if !errors.Is(err, os.ErrNotExist) {
			return err
		}
		parent := filepath.Dir(current)
		if parent == current {
			return errors.New("path parent does not exist")
		}
		current = parent
	}
}

func mkdirAgentAll(root *os.Root, relative string) error {
	if relative == "." {
		return nil
	}
	current := "."
	for _, part := range strings.Split(filepath.ToSlash(relative), "/") {
		if part == "" || part == "." {
			continue
		}
		if current == "." {
			current = part
		} else {
			current = filepath.Join(current, part)
		}
		err := root.Mkdir(current, 0755)
		if err == nil {
			continue
		}
		if !errors.Is(err, os.ErrExist) {
			return err
		}
		info, statErr := root.Stat(current)
		if statErr != nil {
			return statErr
		}
		if !info.IsDir() {
			return fmt.Errorf("path %q is not a directory", current)
		}
	}
	return nil
}

func removeAgentAll(root *os.Root, relative string) error {
	info, err := root.Lstat(relative)
	if err != nil {
		return err
	}
	if info.IsDir() && info.Mode()&os.ModeSymlink == 0 {
		directory, err := root.Open(relative)
		if err != nil {
			return err
		}
		entries, readErr := directory.ReadDir(-1)
		closeErr := directory.Close()
		if readErr != nil {
			return readErr
		}
		if closeErr != nil {
			return closeErr
		}
		for _, entry := range entries {
			child := filepath.Join(relative, entry.Name())
			if err := removeAgentAll(root, child); err != nil {
				return err
			}
		}
	}
	return root.Remove(relative)
}

func createAgentTempFile(root *os.Root, directory, prefix string) (*os.File, string, error) {
	for attempt := 0; attempt < 32; attempt++ {
		name := prefix + agentRandomID("tmp")
		relative := filepath.Join(directory, name)
		file, err := root.OpenFile(relative, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600)
		if err == nil {
			return file, relative, nil
		}
		if !errors.Is(err, os.ErrExist) {
			return nil, "", err
		}
	}
	return nil, "", errors.New("could not create a temporary file")
}

func commitAgentTempFile(root *os.Root, temporary, destination string, overwrite bool) error {
	temporaryFile, err := root.Open(temporary)
	if err != nil {
		return err
	}
	defer temporaryFile.Close()
	if _, err := temporaryFile.Seek(0, io.SeekStart); err != nil {
		return err
	}
	flags := os.O_WRONLY | os.O_CREATE
	if overwrite {
		flags |= os.O_TRUNC
	} else {
		flags |= os.O_EXCL
	}
	destinationFile, err := root.OpenFile(destination, flags, 0644)
	if err != nil {
		return err
	}
	_, copyErr := io.Copy(destinationFile, temporaryFile)
	if copyErr == nil {
		copyErr = destinationFile.Sync()
	}
	closeErr := destinationFile.Close()
	if copyErr != nil {
		return copyErr
	}
	return closeErr
}

func writeAgentFileRoot(root *os.Root, relative string, body io.Reader, max int64) (agentFileWriteResult, error) {
	if err := mkdirAgentAll(root, filepath.Dir(relative)); err != nil {
		return agentFileWriteResult{}, err
	}
	temporary, temporaryPath, err := createAgentTempFile(root, filepath.Dir(relative), ".remotely-agent-")
	if err != nil {
		return agentFileWriteResult{}, err
	}
	defer root.Remove(temporaryPath)
	limited := io.LimitReader(body, max+1)
	count, copyErr := io.Copy(temporary, limited)
	if copyErr != nil {
		_ = temporary.Close()
		return agentFileWriteResult{}, copyErr
	}
	if count > max {
		_ = temporary.Close()
		return agentFileWriteResult{}, errors.New("file exceeds configured limit")
	}
	if err := temporary.Sync(); err != nil {
		_ = temporary.Close()
		return agentFileWriteResult{}, err
	}
	if err := temporary.Close(); err != nil {
		return agentFileWriteResult{}, err
	}
	if err := commitAgentTempFile(root, temporaryPath, relative, true); err != nil {
		return agentFileWriteResult{}, err
	}
	return agentFileWriteResult{Size: count}, nil
}

func writeAgentFile(path string, body io.Reader, max int64) (agentFileWriteResult, error) {
	if err := os.MkdirAll(filepath.Dir(path), 0755); err != nil {
		return agentFileWriteResult{}, err
	}
	root, err := os.OpenRoot(filepath.Dir(path))
	if err != nil {
		return agentFileWriteResult{}, err
	}
	defer root.Close()
	return writeAgentFileRoot(root, filepath.Base(path), body, max)
}

func (s *AgentService) handleArchive(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost || r.URL.Path != "/v1/archive/extract" {
		http.NotFound(w, r)
		return
	}
	var request agentArchiveRequest
	if err := json.NewDecoder(io.LimitReader(r.Body, 64*1024)).Decode(&request); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	sourceRoot, ok := s.roots[request.SourceRoot]
	if !ok || !sourceRoot.Read || !sourceRoot.Archive {
		http.Error(w, "archive read capability is not granted", http.StatusForbidden)
		return
	}
	targetRoot, ok := s.roots[request.TargetRoot]
	if !ok || !targetRoot.Write || !targetRoot.Archive {
		http.Error(w, "archive write capability is not granted", http.StatusForbidden)
		return
	}
	sourcePath, err := resolveAgentRelative(sourceRoot, request.SourcePath, false)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	targetPath, err := resolveAgentRelative(targetRoot, request.TargetPath, true)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	jobID, err := s.jobs.start("archive.extract", func(ctx context.Context, report *agentJobReporter) (any, error) {
		return extractAgentArchiveFromRoots(ctx, sourceRoot, sourcePath, targetRoot, targetPath, request.Format, request.Overwrite, s.config.MaxArchiveBytes, report)
	})
	if err != nil {
		http.Error(w, err.Error(), http.StatusTooManyRequests)
		return
	}
	s.writeJSON(w, http.StatusAccepted, map[string]string{"jobId": jobID})
}

func extractAgentArchiveFromRoots(ctx context.Context, sourceRoot agentRoot, sourcePath string, targetRoot agentRoot, targetPath, format string, overwrite bool, maxBytes int64, report *agentJobReporter) (agentArchiveResult, error) {
	sourceFilesystem, err := openAgentFilesystem(sourceRoot)
	if err != nil {
		return agentArchiveResult{}, err
	}
	defer sourceFilesystem.Close()
	targetFilesystem, err := openAgentFilesystem(targetRoot)
	if err != nil {
		return agentArchiveResult{}, err
	}
	defer targetFilesystem.Close()
	file, err := sourceFilesystem.Open(sourcePath)
	if err != nil {
		return agentArchiveResult{}, err
	}
	defer file.Close()
	return extractAgentArchiveFile(ctx, file, sourcePath, targetFilesystem, targetPath, format, overwrite, maxBytes, report)
}

func extractAgentArchive(ctx context.Context, sourcePath, targetPath, format string, overwrite bool, targetRoot string, maxBytes int64, report *agentJobReporter) (agentArchiveResult, error) {
	targetFilesystem, err := os.OpenRoot(targetRoot)
	if err != nil {
		return agentArchiveResult{}, err
	}
	defer targetFilesystem.Close()
	targetRelative, err := filepath.Rel(targetRoot, targetPath)
	if err != nil {
		return agentArchiveResult{}, err
	}
	targetRelative, err = cleanAgentRelativePath(targetRelative)
	if err != nil {
		return agentArchiveResult{}, err
	}
	sourceFilesystem, err := os.OpenRoot(filepath.Dir(sourcePath))
	if err != nil {
		return agentArchiveResult{}, err
	}
	defer sourceFilesystem.Close()
	file, err := sourceFilesystem.Open(filepath.Base(sourcePath))
	if err != nil {
		return agentArchiveResult{}, err
	}
	defer file.Close()
	return extractAgentArchiveFile(ctx, file, sourcePath, targetFilesystem, targetRelative, format, overwrite, maxBytes, report)
}

func extractAgentArchiveFile(ctx context.Context, file *os.File, sourceName string, targetRoot *os.Root, targetPath, format string, overwrite bool, maxBytes int64, report *agentJobReporter) (agentArchiveResult, error) {
	format = strings.ToLower(strings.TrimSpace(format))
	if format == "" {
		lower := strings.ToLower(sourceName)
		switch {
		case strings.HasSuffix(lower, ".tar.gz"), strings.HasSuffix(lower, ".tgz"):
			format = "tar.gz"
		case strings.HasSuffix(lower, ".zip"):
			format = "zip"
		}
	}
	switch format {
	case "tar.gz", "tgz":
		gzipReader, err := gzip.NewReader(file)
		if err != nil {
			return agentArchiveResult{}, err
		}
		defer gzipReader.Close()
		return extractAgentTarRoot(ctx, tar.NewReader(gzipReader), targetRoot, targetPath, overwrite, maxBytes, report)
	case "zip":
		info, err := file.Stat()
		if err != nil {
			return agentArchiveResult{}, err
		}
		archive, err := zip.NewReader(file, info.Size())
		if err != nil {
			return agentArchiveResult{}, err
		}
		return extractAgentZipRoot(ctx, archive, targetRoot, targetPath, overwrite, maxBytes, report)
	default:
		return agentArchiveResult{}, errors.New("unsupported archive format")
	}
}

func extractAgentTarGz(ctx context.Context, sourcePath, targetPath string, overwrite bool, targetRoot string, maxBytes int64, report *agentJobReporter) (agentArchiveResult, error) {
	sourceFilesystem, err := os.OpenRoot(filepath.Dir(sourcePath))
	if err != nil {
		return agentArchiveResult{}, err
	}
	defer sourceFilesystem.Close()
	file, err := sourceFilesystem.Open(filepath.Base(sourcePath))
	if err != nil {
		return agentArchiveResult{}, err
	}
	defer file.Close()
	targetFilesystem, err := os.OpenRoot(targetRoot)
	if err != nil {
		return agentArchiveResult{}, err
	}
	defer targetFilesystem.Close()
	targetRelative, err := filepath.Rel(targetRoot, targetPath)
	if err != nil {
		return agentArchiveResult{}, err
	}
	targetRelative, err = cleanAgentRelativePath(targetRelative)
	if err != nil {
		return agentArchiveResult{}, err
	}
	gzipReader, err := gzip.NewReader(file)
	if err != nil {
		return agentArchiveResult{}, err
	}
	defer gzipReader.Close()
	return extractAgentTarRoot(ctx, tar.NewReader(gzipReader), targetFilesystem, targetRelative, overwrite, maxBytes, report)
}

func extractAgentTar(ctx context.Context, reader *tar.Reader, targetPath string, overwrite bool, targetRoot string, maxBytes int64, report *agentJobReporter) (agentArchiveResult, error) {
	targetFilesystem, err := os.OpenRoot(targetRoot)
	if err != nil {
		return agentArchiveResult{}, err
	}
	defer targetFilesystem.Close()
	targetRelative, err := filepath.Rel(targetRoot, targetPath)
	if err != nil {
		return agentArchiveResult{}, err
	}
	targetRelative, err = cleanAgentRelativePath(targetRelative)
	if err != nil {
		return agentArchiveResult{}, err
	}
	return extractAgentTarRoot(ctx, reader, targetFilesystem, targetRelative, overwrite, maxBytes, report)
}

func extractAgentTarRoot(ctx context.Context, reader *tar.Reader, targetRoot *os.Root, targetPath string, overwrite bool, maxBytes int64, report *agentJobReporter) (agentArchiveResult, error) {
	var result agentArchiveResult
	for {
		if err := ctx.Err(); err != nil {
			return result, err
		}
		header, err := reader.Next()
		if errors.Is(err, io.EOF) {
			break
		}
		if err != nil {
			return result, err
		}
		if result.Entries >= 100000 {
			return result, errors.New("archive contains too many entries")
		}
		relative, err := safeAgentArchivePath(header.Name)
		if err != nil {
			return result, err
		}
		destination := joinAgentArchivePath(targetPath, relative)
		switch header.Typeflag {
		case tar.TypeDir:
			if err := mkdirAgentAll(targetRoot, destination); err != nil {
				return result, err
			}
		case tar.TypeReg, tar.TypeRegA:
			if !overwrite {
				if _, statErr := targetRoot.Lstat(destination); statErr == nil {
					return result, fmt.Errorf("archive destination already exists: %s", relative)
				} else if !errors.Is(statErr, os.ErrNotExist) {
					return result, statErr
				}
			}
			if err := mkdirAgentAll(targetRoot, filepath.Dir(destination)); err != nil {
				return result, err
			}
			if err := writeAgentArchiveFileRoot(ctx, targetRoot, destination, reader, header.Size, maxBytes-result.Bytes, overwrite); err != nil {
				return result, err
			}
			result.Bytes += header.Size
		default:
			return result, errors.New("archive contains unsupported link or special entry")
		}
		result.Entries++
		report.set(int64(result.Entries), 0, relative)
	}
	return result, nil
}

func extractAgentZip(ctx context.Context, sourcePath, targetPath string, overwrite bool, targetRoot string, maxBytes int64, report *agentJobReporter) (agentArchiveResult, error) {
	sourceFilesystem, err := os.OpenRoot(filepath.Dir(sourcePath))
	if err != nil {
		return agentArchiveResult{}, err
	}
	defer sourceFilesystem.Close()
	file, err := sourceFilesystem.Open(filepath.Base(sourcePath))
	if err != nil {
		return agentArchiveResult{}, err
	}
	defer file.Close()
	targetFilesystem, err := os.OpenRoot(targetRoot)
	if err != nil {
		return agentArchiveResult{}, err
	}
	defer targetFilesystem.Close()
	targetRelative, err := filepath.Rel(targetRoot, targetPath)
	if err != nil {
		return agentArchiveResult{}, err
	}
	targetRelative, err = cleanAgentRelativePath(targetRelative)
	if err != nil {
		return agentArchiveResult{}, err
	}
	info, err := file.Stat()
	if err != nil {
		return agentArchiveResult{}, err
	}
	archive, err := zip.NewReader(file, info.Size())
	if err != nil {
		return agentArchiveResult{}, err
	}
	return extractAgentZipRoot(ctx, archive, targetFilesystem, targetRelative, overwrite, maxBytes, report)
}

func extractAgentZipRoot(ctx context.Context, archive *zip.Reader, targetRoot *os.Root, targetPath string, overwrite bool, maxBytes int64, report *agentJobReporter) (agentArchiveResult, error) {
	var result agentArchiveResult
	for _, entry := range archive.File {
		if err := ctx.Err(); err != nil {
			return result, err
		}
		if result.Entries >= 100000 {
			return result, errors.New("archive contains too many entries")
		}
		relative, err := safeAgentArchivePath(entry.Name)
		if err != nil {
			return result, err
		}
		destination := joinAgentArchivePath(targetPath, relative)
		if entry.FileInfo().IsDir() {
			if err := mkdirAgentAll(targetRoot, destination); err != nil {
				return result, err
			}
		} else {
			if entry.Mode()&os.ModeSymlink != 0 {
				return result, errors.New("archive contains unsupported link or special entry")
			}
			if !overwrite {
				if _, statErr := targetRoot.Lstat(destination); statErr == nil {
					return result, fmt.Errorf("archive destination already exists: %s", relative)
				} else if !errors.Is(statErr, os.ErrNotExist) {
					return result, statErr
				}
			}
			reader, openErr := entry.Open()
			if openErr != nil {
				return result, openErr
			}
			if err := mkdirAgentAll(targetRoot, filepath.Dir(destination)); err != nil {
				reader.Close()
				return result, err
			}
			if err := writeAgentArchiveFileRoot(ctx, targetRoot, destination, reader, int64(entry.UncompressedSize64), maxBytes-result.Bytes, overwrite); err != nil {
				reader.Close()
				return result, err
			}
			reader.Close()
			result.Bytes += int64(entry.UncompressedSize64)
		}
		result.Entries++
		report.set(int64(result.Entries), int64(len(archive.File)), relative)
	}
	return result, nil
}

func joinAgentArchivePath(target, relative string) string {
	if relative == "." {
		return target
	}
	if target == "." {
		return relative
	}
	return filepath.Join(target, relative)
}

func writeAgentArchiveFileRoot(ctx context.Context, root *os.Root, destination string, reader io.Reader, size, remaining int64, overwrite bool) error {
	if size < 0 || size > remaining {
		return errors.New("archive exceeds configured size limit")
	}
	if err := mkdirAgentAll(root, filepath.Dir(destination)); err != nil {
		return err
	}
	tmp, tmpPath, err := createAgentTempFile(root, filepath.Dir(destination), ".remotely-agent-archive-")
	if err != nil {
		return err
	}
	defer root.Remove(tmpPath)
	written, err := copyAgentWithContext(ctx, tmp, reader, size)
	if err == nil && written != size {
		err = errors.New("archive entry size does not match metadata")
	}
	if err == nil {
		err = tmp.Sync()
	}
	if closeErr := tmp.Close(); err == nil {
		err = closeErr
	}
	if err != nil {
		return err
	}
	return commitAgentTempFile(root, tmpPath, destination, overwrite)
}

func writeAgentArchiveFile(ctx context.Context, destination string, reader io.Reader, size, remaining int64) error {
	root, err := os.OpenRoot(filepath.Dir(destination))
	if err != nil {
		return err
	}
	defer root.Close()
	return writeAgentArchiveFileRoot(ctx, root, filepath.Base(destination), reader, size, remaining, true)
}

func copyAgentWithContext(ctx context.Context, destination io.Writer, source io.Reader, expected int64) (int64, error) {
	buffer := make([]byte, 64*1024)
	var total int64
	for total < expected {
		if err := ctx.Err(); err != nil {
			return total, err
		}
		want := int64(len(buffer))
		if remaining := expected - total; remaining < want {
			want = remaining
		}
		count, err := source.Read(buffer[:want])
		if count > 0 {
			written, writeErr := destination.Write(buffer[:count])
			total += int64(written)
			if writeErr != nil {
				return total, writeErr
			}
			if written != count {
				return total, io.ErrShortWrite
			}
		}
		if err != nil {
			if errors.Is(err, io.EOF) && total == expected {
				return total, nil
			}
			return total, err
		}
	}
	return total, nil
}

func safeAgentArchivePath(name string) (string, error) {
	name = strings.ReplaceAll(name, "\\", "/")
	name = strings.TrimPrefix(name, "./")
	if name == "" || name == "." {
		return ".", nil
	}
	if strings.IndexByte(name, 0) >= 0 || strings.HasPrefix(name, "/") || filepath.VolumeName(name) != "" || (len(name) >= 2 && name[1] == ':') {
		return "", errors.New("archive contains an absolute path")
	}
	cleaned := filepath.Clean(filepath.FromSlash(name))
	if cleaned == ".." || strings.HasPrefix(cleaned, ".."+string(filepath.Separator)) {
		return "", errors.New("archive contains a path traversal")
	}
	return cleaned, nil
}

func (s *AgentService) handleJob(w http.ResponseWriter, r *http.Request) {
	parts := strings.Split(strings.Trim(strings.TrimPrefix(r.URL.Path, "/v1/jobs/"), "/"), "/")
	if len(parts) != 1 || parts[0] == "" {
		http.NotFound(w, r)
		return
	}
	switch r.Method {
	case http.MethodGet:
		snapshot, ok := s.jobs.snapshot(parts[0])
		if !ok {
			http.NotFound(w, r)
			return
		}
		s.writeJSON(w, http.StatusOK, snapshot)
	case http.MethodPost:
		if r.URL.Query().Get("action") != "cancel" {
			http.Error(w, "unknown job action", http.StatusBadRequest)
			return
		}
		if !s.jobs.cancel(parts[0]) {
			http.NotFound(w, r)
			return
		}
		w.WriteHeader(http.StatusAccepted)
	default:
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
	}
}

func (s *AgentService) handleProcess(w http.ResponseWriter, r *http.Request) {
	trimmed := strings.Trim(strings.TrimPrefix(r.URL.Path, "/v1/processes"), "/")
	if trimmed == "" {
		if r.Method != http.MethodPost {
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		s.startProcess(w, r)
		return
	}
	parts := strings.Split(trimmed, "/")
	if len(parts) < 1 || parts[0] == "" {
		http.NotFound(w, r)
		return
	}
	process, ok := s.processes.get(parts[0])
	if !ok {
		http.NotFound(w, r)
		return
	}
	if len(parts) == 1 && r.Method == http.MethodGet {
		s.writeJSON(w, http.StatusOK, process.snapshot())
		return
	}
	if len(parts) == 1 && r.Method == http.MethodDelete {
		process.stop()
		w.WriteHeader(http.StatusAccepted)
		return
	}
	if len(parts) == 1 && r.Method == http.MethodPost && r.URL.Query().Get("action") == "stop" {
		process.stop()
		w.WriteHeader(http.StatusAccepted)
		return
	}
	if len(parts) == 2 && parts[1] == "input" && r.Method == http.MethodPost {
		body, err := io.ReadAll(io.LimitReader(r.Body, 64*1024+1))
		if err != nil || int64(len(body)) > 64*1024 {
			http.Error(w, "input exceeds configured limit", http.StatusRequestEntityTooLarge)
			return
		}
		if err := process.writeInputContext(r.Context(), body); err != nil {
			http.Error(w, err.Error(), http.StatusConflict)
			return
		}
		w.WriteHeader(http.StatusNoContent)
		return
	}
	if len(parts) == 2 && parts[1] == "resize" && r.Method == http.MethodPost {
		var request agentTerminalResizeRequest
		if err := json.NewDecoder(io.LimitReader(r.Body, 1024)).Decode(&request); err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		if err := process.resizeTerminal(request.Rows, request.Columns); err != nil {
			http.Error(w, err.Error(), http.StatusConflict)
			return
		}
		w.WriteHeader(http.StatusNoContent)
		return
	}
	if len(parts) == 2 && parts[1] == "output" && r.Method == http.MethodGet {
		offset, _ := strconv.ParseInt(r.URL.Query().Get("offset"), 10, 64)
		limit, _ := strconv.ParseInt(r.URL.Query().Get("limit"), 10, 64)
		data, total, truncated, err := process.output.read(offset, limit)
		if err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		w.Header().Set("Content-Type", "application/octet-stream")
		w.Header().Set("X-Output-Offset", strconv.FormatInt(offset, 10))
		w.Header().Set("X-Output-Total", strconv.FormatInt(total, 10))
		w.Header().Set("X-Output-Truncated", strconv.FormatBool(truncated))
		_, _ = w.Write(data)
		return
	}
	http.NotFound(w, r)
}

func (s *AgentService) startProcess(w http.ResponseWriter, r *http.Request) {
	var request agentProcessRequest
	if err := json.NewDecoder(io.LimitReader(r.Body, 256*1024)).Decode(&request); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	root, ok := s.roots[request.Root]
	if !ok || !root.Execute {
		http.Error(w, "execute capability is not granted", http.StatusForbidden)
		return
	}
	process, err := s.processes.start(root, request)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	jobID, err := s.jobs.start("process", func(ctx context.Context, report *agentJobReporter) (any, error) {
		return process.await(ctx, report)
	})
	if err != nil {
		process.stop()
		http.Error(w, err.Error(), http.StatusTooManyRequests)
		return
	}
	process.setJobID(jobID)
	s.writeJSON(w, http.StatusAccepted, map[string]string{"processId": process.id, "jobId": jobID})
}

func (s *AgentService) handleLifecycle(w http.ResponseWriter, r *http.Request) {
	parts := strings.Split(strings.Trim(strings.TrimPrefix(r.URL.Path, "/v1/lifecycle/"), "/"), "/")
	if len(parts) < 2 || parts[0] == "" {
		http.NotFound(w, r)
		return
	}
	binding, ok := s.lifecycles[parts[0]]
	if !ok {
		http.NotFound(w, r)
		return
	}
	switch parts[1] {
	case "status":
		if r.Method != http.MethodGet {
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		s.writeJSON(w, http.StatusOK, lifecycleReadStatus(binding.Directory))
	case "start":
		if r.Method != http.MethodPost {
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		if err := lifecycleStart(binding.Directory, binding.Command); err != nil {
			http.Error(w, err.Error(), http.StatusConflict)
			return
		}
		w.WriteHeader(http.StatusAccepted)
	case "stop":
		if r.Method != http.MethodPost {
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		if s.reproxy != nil {
			s.reproxy.stopLifecycle(binding.ID)
		}
		if err := lifecycleStop(binding.Directory, 180*time.Second); err != nil {
			http.Error(w, err.Error(), http.StatusConflict)
			return
		}
		w.WriteHeader(http.StatusAccepted)
	case "send":
		if r.Method != http.MethodPost {
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		body, err := io.ReadAll(io.LimitReader(r.Body, 64*1024+1))
		if err != nil || len(body) > 64*1024 {
			http.Error(w, "command exceeds configured limit", http.StatusRequestEntityTooLarge)
			return
		}
		if err := lifecycleSend(binding.Directory, strings.TrimSpace(string(body))); err != nil {
			http.Error(w, err.Error(), http.StatusConflict)
			return
		}
		w.WriteHeader(http.StatusNoContent)
	default:
		http.NotFound(w, r)
	}
}

func (s *AgentService) writeJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}

func validAgentID(id string) bool {
	if id == "" || len(id) > 96 {
		return false
	}
	for _, char := range id {
		if (char < 'a' || char > 'z') && (char < 'A' || char > 'Z') && (char < '0' || char > '9') && char != '.' && char != '_' && char != '-' {
			return false
		}
	}
	return true
}

func loadAgentToken(path string) ([]byte, error) {
	if path == "" {
		return nil, errors.New("agent token file is required")
	}
	path, err := filepath.Abs(path)
	if err != nil {
		return nil, err
	}
	if token, present, readErr := readAgentSecret(path, 16, 4096); readErr != nil {
		return nil, readErr
	} else if present {
		return []byte(token), nil
	}
	if err := os.MkdirAll(filepath.Dir(path), 0700); err != nil {
		return nil, err
	}
	if err := secureAgentSecretDirectory(filepath.Dir(path)); err != nil {
		return nil, err
	}
	raw := make([]byte, 32)
	if _, err := rand.Read(raw); err != nil {
		return nil, err
	}
	token := []byte(hex.EncodeToString(raw))
	file, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600)
	if err != nil {
		if errors.Is(err, os.ErrExist) {
			existing, present, readErr := readAgentSecret(path, 16, 4096)
			if readErr != nil {
				return nil, readErr
			}
			if !present {
				return nil, errors.New("agent token file disappeared while opening")
			}
			return []byte(existing), nil
		}
		return nil, err
	}
	if _, err := file.Write(token); err != nil {
		file.Close()
		return nil, err
	}
	if err := file.Sync(); err != nil {
		file.Close()
		return nil, err
	}
	if err := file.Close(); err != nil {
		return nil, err
	}
	if err := secureAgentSecretFile(path); err != nil {
		return nil, err
	}
	return token, nil
}

func readAgentSecret(path string, minimum, maximum int64) (string, bool, error) {
	info, err := os.Lstat(path)
	if errors.Is(err, os.ErrNotExist) {
		return "", false, nil
	}
	if err != nil {
		return "", false, err
	}
	if info.Mode()&os.ModeSymlink != 0 || !info.Mode().IsRegular() {
		return "", false, errors.New("agent secret file must be a regular file")
	}
	if info.Size() > maximum {
		return "", false, errors.New("agent secret file exceeds the supported limit")
	}
	if err := secureAgentSecretFile(path); err != nil {
		return "", false, err
	}
	file, err := os.Open(path)
	if err != nil {
		return "", false, err
	}
	defer file.Close()
	openedInfo, err := file.Stat()
	if err != nil {
		return "", false, err
	}
	if !openedInfo.Mode().IsRegular() || !os.SameFile(info, openedInfo) {
		return "", false, errors.New("agent secret file changed while opening")
	}
	if err := file.Chmod(0600); err != nil {
		return "", false, err
	}
	data, err := io.ReadAll(io.LimitReader(file, maximum+1))
	if err != nil {
		return "", false, err
	}
	if int64(len(data)) > maximum {
		return "", false, errors.New("agent secret file exceeds the supported limit")
	}
	secret := strings.TrimSpace(string(data))
	if int64(len(secret)) < minimum || int64(len(secret)) > maximum || strings.ContainsAny(secret, "\r\n") {
		return "", false, errors.New("agent secret file contains an invalid value")
	}
	return secret, true, nil
}

func bytesContainLineBreak(value []byte) bool {
	for _, current := range value {
		if current == '\r' || current == '\n' {
			return true
		}
	}
	return false
}

func agentRandomID(prefix string) string {
	raw := make([]byte, 12)
	if _, err := rand.Read(raw); err != nil {
		return prefix + "-" + strconv.FormatInt(time.Now().UnixNano(), 36)
	}
	return prefix + "-" + hex.EncodeToString(raw)
}

func loopbackAgentAddress(address string) error {
	host, _, err := net.SplitHostPort(address)
	if err != nil {
		return err
	}
	if host == "localhost" || host == "127.0.0.1" || host == "::1" {
		return nil
	}
	ip := net.ParseIP(host)
	if ip != nil && ip.IsLoopback() {
		return nil
	}
	return errors.New("agent service must listen on a loopback address")
}
