package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"os"
	"os/signal"
	"strings"
)

func cmdRelay(args []string) {
	fs := flag.NewFlagSet("relay", flag.ExitOnError)
	endpoint := fs.String("endpoint", "", "ReStudio relay WSS endpoint")
	pairingCode := fs.String("pairing-code", "", "One-time device pairing code")
	credentialFile := fs.String("credential-file", defaultAgentCredentialFile(), "Revocable device credential file")
	var roots agentStringList
	var writeRoots agentStringList
	var executeRoots agentStringList
	var archiveRoots agentStringList
	var lifecycles agentStringList
	var workflows agentStringList
	var lsp agentStringList
	var reproxy agentStringList
	var tlsPins agentStringList
	fs.Var(&roots, "root", "Allowed root as id=path")
	fs.Var(&writeRoots, "write-root", "Grant write capability to a root id")
	fs.Var(&executeRoots, "execute-root", "Grant execute capability to a root id")
	fs.Var(&archiveRoots, "archive-root", "Grant archive capability to a root id")
	fs.Var(&lifecycles, "lifecycle", "Lifecycle as id|root|directory|startup-command")
	fs.Var(&workflows, "workflow", "Workflow as id|root|directory|tool|JSON-args")
	fs.Var(&reproxy, "reproxy", "Explicit socket grant as lifecycle|TCP-or-UDP|port|wss-origin")
	fs.Var(&lsp, "lsp", "LSP as id|root|directory|program|JSON-args")
	fs.Var(&tlsPins, "tls-pin", "Allowed relay TLS SPKI pin")
	maxFile := fs.Int64("max-file-bytes", 128*1024*1024, "Maximum file transfer size")
	maxArchive := fs.Int64("max-archive-bytes", 512*1024*1024, "Maximum extracted archive size")
	maxProcess := fs.Int64("max-process-bytes", 16*1024*1024, "Maximum retained output per process")
	_ = fs.Parse(args)
	if *endpoint == "" {
		fmt.Fprintln(os.Stderr, "relay: --endpoint is required")
		os.Exit(2)
	}
	configuredRoots, err := parseAgentRoots(roots, writeRoots, executeRoots, archiveRoots)
	if err != nil {
		fmt.Fprintln(os.Stderr, "relay:", err)
		os.Exit(2)
	}
	configuredLifecycles, err := parseAgentLifecycles(lifecycles)
	if err != nil {
		fmt.Fprintln(os.Stderr, "relay:", err)
		os.Exit(2)
	}
	configuredWorkflows, err := parseAgentWorkflows(workflows)
	if err != nil {
		fmt.Fprintln(os.Stderr, "relay:", err)
		os.Exit(2)
	}
	configuredReProxy, err := parseAgentReProxyGrants(reproxy)
	if err != nil {
		fmt.Fprintln(os.Stderr, "reproxy:", err)
		os.Exit(2)
	}
	configuredLSP, err := parseAgentLSP(lsp)
	if err != nil {
		fmt.Fprintln(os.Stderr, "relay:", err)
		os.Exit(2)
	}
	service, err := NewAgentService(AgentServiceConfig{Roots: configuredRoots, Lifecycles: configuredLifecycles, Workflows: configuredWorkflows, LSP: configuredLSP, ReProxy: configuredReProxy, MaxFileBytes: *maxFile, MaxArchiveBytes: *maxArchive, MaxProcessBytes: *maxProcess, Token: []byte(agentRandomID("relay-service"))})
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	defer service.Close()
	relay, err := NewAgentRelay(AgentRelayConfig{Endpoint: *endpoint, PairingCode: *pairingCode, CredentialFile: *credentialFile, Service: service, PinnedSPKI: tlsPins})
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(2)
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	interrupt := make(chan os.Signal, 1)
	signal.Notify(interrupt, os.Interrupt)
	defer signal.Stop(interrupt)
	go func() {
		<-interrupt
		cancel()
	}()
	if err := relay.Run(ctx); err != nil && !errors.Is(err, context.Canceled) {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

func parseAgentRoots(specs, writeRoots, executeRoots, archiveRoots []string) ([]AgentRoot, error) {
	if len(specs) == 0 {
		return nil, errors.New("at least one --root is required")
	}
	rootMap := make(map[string]AgentRoot, len(specs))
	for _, spec := range specs {
		parts := strings.SplitN(spec, "=", 2)
		if len(parts) != 2 || !validAgentID(parts[0]) || parts[1] == "" {
			return nil, fmt.Errorf("invalid root %q", spec)
		}
		if _, exists := rootMap[parts[0]]; exists {
			return nil, fmt.Errorf("duplicate root %q", parts[0])
		}
		rootMap[parts[0]] = AgentRoot{ID: parts[0], Path: parts[1], Read: true}
	}
	for _, id := range writeRoots {
		root, ok := rootMap[id]
		if !ok {
			return nil, fmt.Errorf("unknown write root %q", id)
		}
		root.Write = true
		rootMap[id] = root
	}
	for _, id := range executeRoots {
		root, ok := rootMap[id]
		if !ok {
			return nil, fmt.Errorf("unknown execute root %q", id)
		}
		root.Execute = true
		rootMap[id] = root
	}
	for _, id := range archiveRoots {
		root, ok := rootMap[id]
		if !ok {
			return nil, fmt.Errorf("unknown archive root %q", id)
		}
		root.Archive = true
		rootMap[id] = root
	}
	result := make([]AgentRoot, 0, len(rootMap))
	for _, root := range rootMap {
		result = append(result, root)
	}
	return result, nil
}

func parseAgentLifecycles(specs []string) ([]AgentLifecycleBinding, error) {
	result := make([]AgentLifecycleBinding, 0, len(specs))
	for _, spec := range specs {
		parts := strings.SplitN(spec, "|", 4)
		if len(parts) != 4 || !validAgentID(parts[0]) || parts[1] == "" || parts[2] == "" || parts[3] == "" {
			return nil, fmt.Errorf("invalid lifecycle %q", spec)
		}
		result = append(result, AgentLifecycleBinding{ID: parts[0], Root: parts[1], Directory: parts[2], Command: parts[3]})
	}
	return result, nil
}

func parseAgentWorkflows(specs []string) ([]AgentWorkflowBinding, error) {
	result := make([]AgentWorkflowBinding, 0, len(specs))
	for _, spec := range specs {
		parts, args, err := parseAgentDeveloperBinding(spec)
		if err != nil {
			return nil, fmt.Errorf("invalid workflow %q: %w", spec, err)
		}
		result = append(result, AgentWorkflowBinding{ID: parts[0], Root: parts[1], Directory: parts[2], Tool: parts[3], Args: args})
	}
	return result, nil
}

func parseAgentLSP(specs []string) ([]AgentLSPBinding, error) {
	result := make([]AgentLSPBinding, 0, len(specs))
	for _, spec := range specs {
		parts, args, err := parseAgentDeveloperBinding(spec)
		if err != nil {
			return nil, fmt.Errorf("invalid lsp %q: %w", spec, err)
		}
		result = append(result, AgentLSPBinding{ID: parts[0], Root: parts[1], Directory: parts[2], Program: parts[3], Args: args})
	}
	return result, nil
}

func parseAgentDeveloperBinding(spec string) ([]string, []string, error) {
	parts := strings.SplitN(spec, "|", 5)
	if len(parts) != 5 || !validAgentID(parts[0]) || parts[1] == "" || parts[3] == "" {
		return nil, nil, errors.New("expected id|root|directory|program|JSON-args")
	}
	var args []string
	if err := json.Unmarshal([]byte(parts[4]), &args); err != nil {
		return nil, nil, errors.New("arguments must be a JSON string array")
	}
	if len(args) > 256 {
		return nil, nil, errors.New("too many configured arguments")
	}
	return parts, args, nil
}
