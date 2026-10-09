package redxax.oxy.remotely.servers.reproxy;

import redxax.oxy.remotely.servers.ReProxyTarget;
import redxax.oxy.remotely.network.NetworkHostScope;
import redxax.oxy.remotely.util.TaskSchedulers;
import restudio.rebase.backend.BackendConfig;
import restudio.rebase.backend.FileSystemProvider;
import restudio.rebase.backend.impl.SshBackend.SshFileSystem;
import restudio.rebase.backend.feature.RemoteShellFeature;
import restudio.rebase.instance.Instance;
import restudio.rebase.instance.InstanceRepairer;
import restudio.rebase.instance.InstanceState;
import restudio.rebase.localcontrol.LocalServerControllerClient;
import restudio.rebase.platform.jvm.JvmAsyncBridge;
import restudio.rebase.util.Executors;
import restudio.rescreen.platform.Async;
import restudio.rescreen.platform.Sha256;
import restudio.rescreen.platform.TaskScheduler;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.IntSupplier;

public final class SshPluginAccess implements PluginForwarding.Access {
    private final Instance instance;
    private final String root;
    private final String stamp;
    private final IntSupplier port;
    private volatile SecretProof secretProof;
    private int changes;
    private record SecretProof(String script, PluginForwarding.Document document, boolean changesDirectory, Object backend) { }
    private record Script(String path, PluginForwarding.Document document) { }

    public SshPluginAccess(Instance instance, IntSupplier port) {
        this.instance = Objects.requireNonNull(instance, "Server Is Required");
        this.port = Objects.requireNonNull(port, "Server Port Is Required");
        root = canonical(instance.getPath());
        stamp = location();
    }

    @Override
    public String serverKey() { return "ssh:" + instance.getInstanceId(); }

    @Override
    public String authorityStamp() { return stamp; }

    @Override
    public String name() { return instance.getName(); }

    @Override
    public int serverPort() { return port.getAsInt(); }

    @Override
    public boolean local() { return false; }

    @Override
    public Ports ports() { return Ports.PRIVATE; }

    @Override
    public boolean proxy() { return instance.isProxyServer(); }

    @Override
    public AutoCloseable networkChange(String id, long revision) { return change(); }

    @Override
    public synchronized AutoCloseable change() {
        if (changes++ == 0) secretProof = null;
        return new AutoCloseable() {
            private boolean closed;
            @Override
            public void close() {
                synchronized (SshPluginAccess.this) {
                    if (closed) return;
                    closed = true;
                    if (--changes == 0) secretProof = null;
                }
            }
        };
    }

    @Override
    public Async<Void> secretFiles() { return secretFiles(true); }

    private Async<Void> secretFiles(boolean admit) {
        SecretProof proof = secretProof;
        if (!admit && proof == null) return Async.failed(new IllegalStateException("Check The Host Again Before Configuring Plasmo Voice"));
        List<String> scripts = InstanceRepairer.usesForgeScript(instance) ? List.of("run.sh") : instance.startupScriptCandidates(false);
        return running().thenCompose(active -> active ? Async.failed(new IllegalStateException("Stop The Server Before Configuring Plasmo Voice"))
                : script(scripts, 0)).thenCompose(found -> JvmAsyncBridge.fromFuture(CompletableFuture.runAsync(() -> {
                    try {
                        files();
                        boolean changesDirectory;
                        if (proof == null) changesDirectory = LocalServerControllerClient.requirePluginSecretScript(found.document().content(), false);
                        else {
                            if (!proof.script().equals(found.path()) || !proof.document().equals(found.document()) || proof.backend() != instance.getBackend()) {
                                throw new IllegalStateException("Server Startup Script Changed Before Configuration");
                            }
                            changesDirectory = proof.changesDirectory();
                        }
                        RemoteShellFeature shell = instance.getBackend().getFeature(RemoteShellFeature.class)
                                .orElseThrow(() -> new IllegalStateException("Server Launch Environment Is Unavailable For This Host"));
                        String flags = shell.output("if [ \"${PLASMO_VOICE_FORWARDING_SECRET+x}\" = x ] || [ \"${PLASMO_VOICE_FORWARDING_SECRET_FILE+x}\" = x ] || [ \"${BASH_ENV+x}\" = x ] || [ \"${ENV+x}\" = x ]; then printf override; else printf clear; fi");
                        if (!"clear".equals(flags.strip())) throw new IllegalStateException("Remove Plasmo Voice Secret Overrides And Shell Startup Hooks Before Configuring Network Voice Chat");
                        if (changesDirectory) shell.run("test \"$(readlink -f -- " + shell.quote(shell.toRemote(Path.of(root))) + ")\" = \"$(readlink -f -- "
                                + shell.quote(shell.toRemote(path(found.path()).getParent())) + ")\"", 10000L);
                        if (proof == null) secretProof = new SecretProof(found.path(), found.document(), changesDirectory, instance.getBackend());
                    } catch (Exception failure) {
                        if (secretProof == proof) secretProof = null;
                        throw new CompletionException(failure);
                    }
                }, Executors.STREAMS)));
    }

    private Async<Script> script(List<String> candidates, int index) {
        if (index >= candidates.size()) return Async.failed(new IllegalStateException("Create The Server Startup Script Before Configuring Plasmo Voice"));
        String candidate = candidates.get(index);
        return observe(candidate).thenCompose(document -> document.exists() ? Async.completed(new Script(candidate, document)) : script(candidates, index + 1));
    }

    @Override
    public Async<Boolean> running() {
        files();
        return JvmAsyncBridge.fromFuture(instance.getBackend().getExecution().getStatus()).thenApply(status ->
                status.state() != InstanceState.STOPPED && status.state() != InstanceState.CRASHED);
    }

    @Override
    public Async<PluginForwarding.Document> observe(String value) {
        FileSystemProvider files = files();
        Path target = path(value);
        if (!(files instanceof SshFileSystem ssh)) return Async.failed(new IllegalStateException("Safe Plugin Observation Is Unavailable For This Host"));
        return JvmAsyncBridge.fromFuture(ssh.readWithin(Path.of(root), target)).thenApply(valueRead -> valueRead
                .map(content -> new PluginForwarding.Document(true, content, Sha256.hex(content.getBytes(StandardCharsets.UTF_8))))
                .orElseGet(() -> new PluginForwarding.Document(false, "", "")));
    }

    @Override
    public Async<PluginForwarding.Document> mutate(String value, PluginForwarding.Document expected, String content, String operationId) {
        FileSystemProvider files = files();
        if (!(files instanceof SshFileSystem ssh)) return Async.failed(new IllegalStateException("Safe Plugin Configuration Is Unavailable For This Host"));
        return JvmAsyncBridge.fromFuture(ssh.writeConditionalWithin(Path.of(root), path(value), expected.exists(), expected.content(), content))
                .thenCompose(ignored -> observe(value));
    }

    @Override
    public Async<PluginForwarding.Document> mutate(ReProxyIntegrations.Integration integration, String value, PluginForwarding.Document expected, String content, String operationId) {
        boolean journal = value.equals(".remotely/plugin-forwarding-" + integration.id() + ".json");
        return running().thenCompose(active -> active && !journal && integration.requiresStopped()
                ? Async.failed(new IllegalStateException("Stop The Server Before Changing " + integration.name()))
                : (!journal && "plasmovoice".equals(integration.id()) ? secretFiles(false) : Async.<Void>completed(null))
                        .thenCompose(ignored -> mutate(value, expected, content, operationId)));
    }

    @Override
    public AutoCloseable watch(String value, Runnable changed) {
        path(value);
        return new FileWatch(value, Objects.requireNonNull(changed, "Changed Handler Is Required"));
    }

    @Override
    public Async<PluginForwarding.Port> reserve(ReProxyIntegrations.Integration integration, int desiredPort, String operationId) {
        return Async.failed(new PluginForwarding.ReservationRejected("This Host Must Provide A Reachable " + integration.protocol() + " Port Before Connecting " + integration.name()));
    }

    @Override
    public Async<Void> release(ReProxyIntegrations.Integration integration, PluginForwarding.Port port) {
        return Async.completed(null);
    }

    @Override
    public Async<Void> reload(ReProxyIntegrations.Integration integration, String value, PluginForwarding.Document expected) {
        return observe(value).thenCompose(current -> {
            if (!current.equals(expected)) throw new IllegalStateException("Plugin Settings Changed Before Reload");
            Async<Void> result = Async.completed(null);
            for (String command : integration.reloadCommands()) result = result.thenCompose(ignored ->
                    JvmAsyncBridge.fromFuture(instance.getBackend().getExecution().sendCommand(command)));
            return result;
        });
    }

    @Override
    public ReProxyTarget localTarget() { return null; }

    @Override
    public String hostScope() { return NetworkHostScope.resolve(instance); }

    private FileSystemProvider files() {
        if (!stamp.equals(location())) throw new IllegalStateException("Server Location Changed. Reopen Server Details");
        if (instance.getBackend() == null || !instance.getBackend().isConnected()) throw new IllegalStateException("Connect To The Host Before Configuring Plugins");
        FileSystemProvider files = instance.getBackend().getFileSystem();
        if (files == null || !files.supportsConditionalWrites()) throw new IllegalStateException("Safe Plugin Configuration Is Unavailable For This Host");
        return files;
    }

    private Path path(String value) {
        String relative = ReProxyIntegrations.relativePath(value);
        Path target = Path.of(root).resolve(relative).normalize();
        if (!target.startsWith(Path.of(root))) throw new IllegalArgumentException("Plugin File Is Outside The Server Folder");
        return target;
    }

    private String location() {
        BackendConfig config = instance.getBackendConfig();
        if (config == null || !"SSH".equalsIgnoreCase(config.type)) throw new IllegalStateException("SSH Server Is Unavailable");
        Map<String, String> credentials = config.credentials == null ? Map.of() : config.credentials;
        String identity = instance.getInstanceId() + "\n" + canonical(instance.getPath()) + "\n"
                + credentials.getOrDefault("hostId", "") + "\n" + credentials.getOrDefault("host", "") + "\n"
                + credentials.getOrDefault("port", "22") + "\n" + credentials.getOrDefault("user", "");
        return Sha256.hex(identity.getBytes(StandardCharsets.UTF_8));
    }

    public boolean currentLocation() { return stamp.equals(location()); }

    private static String canonical(String value) {
        return Path.of(value).normalize().toString().replace('\\', '/');
    }

    private final class FileWatch implements AutoCloseable {
        private final String path;
        private final Runnable changed;
        private PluginForwarding.Document admitted;
        private TaskScheduler.ScheduledTask task;
        private boolean closed;
        private boolean observing;

        private FileWatch(String path, Runnable changed) {
            this.path = path;
            this.changed = changed;
            poll();
        }

        private void poll() {
            synchronized (this) {
                if (closed || observing) return;
                observing = true;
            }
            try { observe(path).whenComplete(this::observed); }
            catch (RuntimeException failure) { observed(null, failure); }
        }

        private void observed(PluginForwarding.Document document, Throwable failure) {
            boolean notify = false;
            synchronized (this) {
                observing = false;
                if (closed) return;
                if (failure == null && !Objects.equals(document, admitted)) {
                    notify = admitted != null;
                    admitted = document;
                }
            }
            if (notify) {
                try { changed.run(); } catch (RuntimeException ignored) { }
            }
            synchronized (this) {
                if (!closed) task = TaskSchedulers.current().schedule(this::poll, Duration.ofSeconds(4));
            }
        }

        @Override
        public synchronized void close() {
            closed = true;
            if (task != null) task.cancel();
        }
    }
}
