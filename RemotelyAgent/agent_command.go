package main

import (
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"
)

type agentStringList []string

func (v *agentStringList) String() string {
	return strings.Join(*v, ",")
}

func (v *agentStringList) Set(value string) error {
	if strings.TrimSpace(value) == "" {
		return errors.New("value must not be empty")
	}
	*v = append(*v, value)
	return nil
}

type agentEndpoint struct {
	Address   string `json:"address"`
	TokenFile string `json:"tokenFile"`
	Token     string `json:"token,omitempty"`
}

func cmdServe(args []string) {
	fs := flag.NewFlagSet("serve", flag.ExitOnError)
	listen := fs.String("listen", "127.0.0.1:0", "Loopback listen address")
	tokenFile := fs.String("token-file", defaultAgentTokenFile(), "Authentication token file")
	printToken := fs.Bool("print-token", false, "Print the authentication token once")
	var roots agentStringList
	var writeRoots agentStringList
	var executeRoots agentStringList
	var archiveRoots agentStringList
	var origins agentStringList
	var lifecycles agentStringList
	var workflows agentStringList
	var lsp agentStringList
	var reproxy agentStringList
	fs.Var(&roots, "root", "Allowed root as id=path")
	fs.Var(&writeRoots, "write-root", "Grant write capability to a root id")
	fs.Var(&executeRoots, "execute-root", "Grant execute capability to a root id")
	fs.Var(&archiveRoots, "archive-root", "Grant archive capability to a root id")
	fs.Var(&origins, "origin", "Allowed browser origin")
	fs.Var(&lifecycles, "lifecycle", "Lifecycle as id|root|directory|startup-command")
	fs.Var(&workflows, "workflow", "Workflow as id|root|directory|tool|JSON-args")
	fs.Var(&reproxy, "reproxy", "Explicit socket grant as lifecycle|TCP-or-UDP|port|wss-origin")
	fs.Var(&lsp, "lsp", "LSP as id|root|directory|program|JSON-args")
	maxFile := fs.Int64("max-file-bytes", 128*1024*1024, "Maximum file transfer size")
	maxArchive := fs.Int64("max-archive-bytes", 512*1024*1024, "Maximum extracted archive size")
	maxProcess := fs.Int64("max-process-bytes", 16*1024*1024, "Maximum retained output per process")
	_ = fs.Parse(args)
	configuredRoots, err := parseAgentRoots(roots, writeRoots, executeRoots, archiveRoots)
	if err != nil {
		fmt.Fprintln(os.Stderr, "serve:", err)
		os.Exit(2)
	}
	configuredLifecycles, err := parseAgentLifecycles(lifecycles)
	if err != nil {
		fmt.Fprintln(os.Stderr, "serve:", err)
		os.Exit(2)
	}
	configuredWorkflows, err := parseAgentWorkflows(workflows)
	if err != nil {
		fmt.Fprintln(os.Stderr, "serve:", err)
		os.Exit(2)
	}
	configuredReProxy, err := parseAgentReProxyGrants(reproxy)
	if err != nil {
		fmt.Fprintln(os.Stderr, "reproxy:", err)
		os.Exit(2)
	}
	configuredLSP, err := parseAgentLSP(lsp)
	if err != nil {
		fmt.Fprintln(os.Stderr, "serve:", err)
		os.Exit(2)
	}
	tokenPath, err := filepath.Abs(*tokenFile)
	if err != nil {
		fmt.Fprintln(os.Stderr, "serve:", err)
		os.Exit(2)
	}
	service, err := NewAgentService(AgentServiceConfig{TokenFile: tokenPath, AllowedOrigins: origins, Roots: configuredRoots, Lifecycles: configuredLifecycles, Workflows: configuredWorkflows, LSP: configuredLSP, ReProxy: configuredReProxy, MaxFileBytes: *maxFile, MaxArchiveBytes: *maxArchive, MaxProcessBytes: *maxProcess})
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	defer service.Close()
	if err := loopbackAgentAddress(*listen); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(2)
	}
	listener, err := net.Listen("tcp", *listen)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	if err := loopbackAgentAddress(listener.Addr().String()); err != nil {
		_ = listener.Close()
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	endpoint := agentEndpoint{Address: "http://" + listener.Addr().String(), TokenFile: tokenPath}
	if *printToken {
		endpoint.Token = string(service.token)
	}
	fmt.Printf("%s\n", formatAgentEndpoint(endpoint))
	server := &http.Server{Handler: service.Handler(), ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 2 * time.Minute, WriteTimeout: 2 * time.Minute, IdleTimeout: 2 * time.Minute, MaxHeaderBytes: 16 * 1024}
	if err := server.Serve(listener); err != nil && !errors.Is(err, http.ErrServerClosed) {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

func defaultAgentTokenFile() string {
	home, err := os.UserHomeDir()
	if err != nil || home == "" {
		home = os.TempDir()
	}
	return filepath.Join(home, ".remotely", "agent", "token")
}

func formatAgentEndpoint(endpoint agentEndpoint) string {
	data, _ := json.Marshal(endpoint)
	return string(data)
}
