package main

import (
	"archive/zip"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestLifecycleStopInputSeparatesPendingTerminalLine(t *testing.T) {
	if lifecycleStopInput != "\nstop\n" {
		t.Fatalf("lifecycle stop input = %q, want a line separator followed by stop", lifecycleStopInput)
	}
}

func TestAgentServiceRequiresBearerAuthentication(t *testing.T) {
	root := t.TempDir()
	service, err := NewAgentService(AgentServiceConfig{Token: []byte("01234567890123456789012345678901"), Roots: []AgentRoot{{ID: "workspace", Path: root, Read: true}}})
	if err != nil {
		t.Fatal(err)
	}
	defer service.Close()
	server := httptest.NewServer(service.Handler())
	defer server.Close()
	response, err := http.Get(server.URL + "/v1/capabilities")
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusUnauthorized {
		t.Fatalf("expected unauthorized response, got %d", response.StatusCode)
	}
}

func TestAgentServiceScopesFilesToRoot(t *testing.T) {
	root := t.TempDir()
	service, err := NewAgentService(AgentServiceConfig{Token: []byte("01234567890123456789012345678901"), Roots: []AgentRoot{{ID: "workspace", Path: root, Read: true, Write: true}}})
	if err != nil {
		t.Fatal(err)
	}
	defer service.Close()
	server := httptest.NewServer(service.Handler())
	defer server.Close()
	request, err := http.NewRequest(http.MethodPut, server.URL+"/v1/roots/workspace/file?path=notes.txt", strings.NewReader("hello"))
	if err != nil {
		t.Fatal(err)
	}
	request.Header.Set("Authorization", "Bearer 01234567890123456789012345678901")
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	response.Body.Close()
	if response.StatusCode != http.StatusOK {
		t.Fatalf("expected file write, got %d", response.StatusCode)
	}
	request, err = http.NewRequest(http.MethodGet, server.URL+"/v1/roots/workspace/file?path=../outside.txt", nil)
	if err != nil {
		t.Fatal(err)
	}
	request.Header.Set("Authorization", "Bearer 01234567890123456789012345678901")
	response, err = http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	response.Body.Close()
	if response.StatusCode != http.StatusBadRequest {
		t.Fatalf("expected traversal rejection, got %d", response.StatusCode)
	}
	data, err := os.ReadFile(filepath.Join(root, "notes.txt"))
	if err != nil || string(data) != "hello" {
		t.Fatalf("written file was not preserved: %q %v", data, err)
	}
}

func TestAgentServiceRootOperationsRejectSymlinkEscapes(t *testing.T) {
	root := t.TempDir()
	outside := t.TempDir()
	if err := os.WriteFile(filepath.Join(outside, "secret.txt"), []byte("outside"), 0600); err != nil {
		t.Fatal(err)
	}
	link := filepath.Join(root, "linked")
	if err := os.Symlink(outside, link); err != nil {
		t.Skipf("symlinks are unavailable: %v", err)
	}
	archivePath := filepath.Join(outside, "source.zip")
	archiveFile, err := os.Create(archivePath)
	if err != nil {
		t.Fatal(err)
	}
	archive := zip.NewWriter(archiveFile)
	entry, err := archive.Create("created.txt")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := entry.Write([]byte("archive")); err != nil {
		t.Fatal(err)
	}
	if err := archive.Close(); err != nil {
		t.Fatal(err)
	}
	if err := archiveFile.Close(); err != nil {
		t.Fatal(err)
	}
	service, err := NewAgentService(AgentServiceConfig{
		Token: []byte("01234567890123456789012345678901"),
		Roots: []AgentRoot{{ID: "workspace", Path: root, Read: true, Write: true, Archive: true}},
	})
	if err != nil {
		t.Fatal(err)
	}
	defer service.Close()
	server := httptest.NewServer(service.Handler())
	defer server.Close()
	request := func(method, path string, body io.Reader) *http.Response {
		t.Helper()
		req, err := http.NewRequest(method, server.URL+path, body)
		if err != nil {
			t.Fatal(err)
		}
		req.Header.Set("Authorization", "Bearer 01234567890123456789012345678901")
		response, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		return response
	}
	for _, operation := range []struct {
		method string
		path   string
		body   io.Reader
	}{
		{http.MethodGet, "/v1/roots/workspace/entries?path=linked", nil},
		{http.MethodGet, "/v1/roots/workspace/file?path=linked/secret.txt", nil},
		{http.MethodPut, "/v1/roots/workspace/file?path=linked/new.txt", strings.NewReader("blocked")},
		{http.MethodPost, "/v1/roots/workspace/directory?path=linked/newdir", nil},
		{http.MethodDelete, "/v1/roots/workspace/entry?path=linked/secret.txt&recursive=true", nil},
	} {
		response := request(operation.method, operation.path, operation.body)
		response.Body.Close()
		if response.StatusCode != http.StatusBadRequest {
			t.Fatalf("expected symlink escape rejection for %s %s, got %d", operation.method, operation.path, response.StatusCode)
		}
	}
	archiveBody := `{"format":"zip","sourceRoot":"workspace","sourcePath":"linked/source.zip","targetRoot":"workspace","targetPath":"linked/out"}`
	response := request(http.MethodPost, "/v1/archive/extract", strings.NewReader(archiveBody))
	response.Body.Close()
	if response.StatusCode != http.StatusBadRequest {
		t.Fatalf("expected archive symlink escape rejection, got %d", response.StatusCode)
	}
	data, err := os.ReadFile(filepath.Join(outside, "secret.txt"))
	if err != nil || string(data) != "outside" {
		t.Fatalf("outside file changed: %q %v", data, err)
	}
	if _, err := os.Stat(filepath.Join(outside, "new.txt")); !os.IsNotExist(err) {
		t.Fatalf("symlink escape created outside file: %v", err)
	}
}

func TestAgentServiceRetainsConfiguredRootIdentity(t *testing.T) {
	parent := t.TempDir()
	configured := filepath.Join(parent, "workspace")
	moved := filepath.Join(parent, "workspace-moved")
	outside := filepath.Join(parent, "outside")
	if err := os.Mkdir(configured, 0755); err != nil {
		t.Fatal(err)
	}
	if err := os.Mkdir(outside, 0755); err != nil {
		t.Fatal(err)
	}
	service, err := NewAgentService(AgentServiceConfig{Token: []byte("01234567890123456789012345678901"), Roots: []AgentRoot{{ID: "workspace", Path: configured, Read: true, Write: true}}})
	if err != nil {
		t.Fatal(err)
	}
	defer service.Close()
	if err := os.Rename(configured, moved); err != nil {
		t.Skipf("root replacement is unavailable: %v", err)
	}
	if err := os.Symlink(outside, configured); err != nil {
		t.Skipf("symlinks are unavailable: %v", err)
	}
	server := httptest.NewServer(service.Handler())
	defer server.Close()
	request, err := http.NewRequest(http.MethodPut, server.URL+"/v1/roots/workspace/file?path=retained.txt", strings.NewReader("safe"))
	if err != nil {
		t.Fatal(err)
	}
	request.Header.Set("Authorization", "Bearer 01234567890123456789012345678901")
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	response.Body.Close()
	if response.StatusCode != http.StatusOK {
		t.Fatalf("expected retained root write, got %d", response.StatusCode)
	}
	if _, err := os.Stat(filepath.Join(outside, "retained.txt")); !os.IsNotExist(err) {
		t.Fatalf("replacement root received capability write: %v", err)
	}
	data, err := os.ReadFile(filepath.Join(moved, "retained.txt"))
	if err != nil || string(data) != "safe" {
		t.Fatalf("original root did not receive capability write: %q %v", data, err)
	}
}

func TestAgentArchiveRejectsTraversalAndReportsJob(t *testing.T) {
	root := t.TempDir()
	archivePath := filepath.Join(root, "unsafe.zip")
	file, err := os.Create(archivePath)
	if err != nil {
		t.Fatal(err)
	}
	archive := zip.NewWriter(file)
	entry, err := archive.Create("../escaped.txt")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := entry.Write([]byte("bad")); err != nil {
		t.Fatal(err)
	}
	if err := archive.Close(); err != nil {
		t.Fatal(err)
	}
	if err := file.Close(); err != nil {
		t.Fatal(err)
	}
	service, err := NewAgentService(AgentServiceConfig{Token: []byte("01234567890123456789012345678901"), Roots: []AgentRoot{{ID: "workspace", Path: root, Read: true, Write: true, Archive: true}}})
	if err != nil {
		t.Fatal(err)
	}
	defer service.Close()
	server := httptest.NewServer(service.Handler())
	defer server.Close()
	body := `{"format":"zip","sourceRoot":"workspace","sourcePath":"unsafe.zip","targetRoot":"workspace","targetPath":"out"}`
	request, err := http.NewRequest(http.MethodPost, server.URL+"/v1/archive/extract", strings.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	request.Header.Set("Authorization", "Bearer 01234567890123456789012345678901")
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	data, _ := io.ReadAll(response.Body)
	response.Body.Close()
	if response.StatusCode != http.StatusAccepted {
		t.Fatalf("expected archive job, got %d: %s", response.StatusCode, data)
	}
	var job struct {
		ID string `json:"jobId"`
	}
	if err := json.Unmarshal(data, &job); err != nil {
		t.Fatal(err)
	}
	for deadline := time.Now().Add(2 * time.Second); time.Now().Before(deadline); {
		request, err = http.NewRequest(http.MethodGet, server.URL+"/v1/jobs/"+job.ID, nil)
		if err != nil {
			t.Fatal(err)
		}
		request.Header.Set("Authorization", "Bearer 01234567890123456789012345678901")
		response, err = http.DefaultClient.Do(request)
		if err != nil {
			t.Fatal(err)
		}
		data, _ = io.ReadAll(response.Body)
		response.Body.Close()
		var snapshot agentJobSnapshot
		if err := json.Unmarshal(data, &snapshot); err != nil {
			t.Fatal(err)
		}
		if snapshot.Status == "failed" {
			if _, err := os.Stat(filepath.Join(root, "escaped.txt")); !os.IsNotExist(err) {
				t.Fatalf("archive escaped root")
			}
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatal("archive job did not finish")
}

func TestAgentJobsBoundCompletedRetention(t *testing.T) {
	manager := newAgentJobManager(1)
	for index := 0; index < 12; index++ {
		id, err := manager.start("test", func(ctx context.Context, reporter *agentJobReporter) (any, error) {
			return "done", nil
		})
		if err != nil {
			t.Fatal(err)
		}
		for deadline := time.Now().Add(time.Second); time.Now().Before(deadline); {
			snapshot, ok := manager.snapshot(id)
			if ok && snapshot.Status == "completed" {
				break
			}
			time.Sleep(time.Millisecond)
		}
	}
	manager.mu.Lock()
	count := len(manager.jobs)
	manager.mu.Unlock()
	if count > manager.maxRecords {
		t.Fatalf("completed job retention grew to %d records, max %d", count, manager.maxRecords)
	}
}

func TestModpackDownloadDestinationsStayWithinTarget(t *testing.T) {
	target := t.TempDir()
	items := []FetchItem{{Dest: "../escape.jar"}}
	if err := validateModpackFetchItems(target, items); err == nil || !strings.Contains(err.Error(), "escapes") {
		t.Fatalf("expected destination escape rejection, got %v", err)
	}
}

func TestReProxyRequiresExplicitSocketGrant(t *testing.T) {
	service, err := NewAgentService(AgentServiceConfig{
		Token:      []byte("01234567890123456789012345678901"),
		Roots:      []AgentRoot{{ID: "workspace", Path: t.TempDir(), Read: true, Execute: true}},
		Lifecycles: []AgentLifecycleBinding{{ID: "server", Root: "workspace", Command: "java -jar server.jar"}},
	})
	if err != nil {
		t.Fatal(err)
	}
	defer service.Close()
	command := agentReProxyCommand{OperationID: "operation", ConnectionID: "connection", SessionID: "session", Generation: "1", RouteRevision: "1", LifecycleID: "server"}
	command.Ticket.ConnectionID = command.ConnectionID
	command.Ticket.SessionID = command.SessionID
	command.Ticket.Generation = command.Generation
	command.Ticket.RouteRevision = command.RouteRevision
	command.Ticket.Token = "fresh-ticket"
	command.Ticket.ExpiresAt = time.Now().Add(time.Minute).UTC().Format(time.RFC3339)
	command.Ticket.Node.TunnelHost = "relay.example"
	command.Ticket.Node.TunnelScheme = "wss"
	command.Ticket.Node.TunnelPort = 443
	command.Ticket.Endpoints = []agentReProxyEndpoint{{ID: "12345678-1234-1234-1234-123456789abc", Protocol: "TCP", TargetHost: "127.0.0.1", TargetPort: 25565, Enabled: true}}
	body, err := json.Marshal(command)
	if err != nil {
		t.Fatal(err)
	}
	request := httptest.NewRequest(http.MethodPost, "/v1/reproxy/start", strings.NewReader(string(body)))
	request.Header.Set("Authorization", "Bearer 01234567890123456789012345678901")
	response := httptest.NewRecorder()
	service.ServeHTTP(response, request)
	if response.Code != http.StatusConflict || !strings.Contains(response.Body.String(), "Socket Grant Is Required") {
		t.Fatalf("execute permission admitted an ungranted socket: %d %s", response.Code, response.Body.String())
	}
}
