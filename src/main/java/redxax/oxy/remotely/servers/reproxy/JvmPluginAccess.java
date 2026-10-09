package redxax.oxy.remotely.servers.reproxy;

import redxax.oxy.remotely.servers.ReProxyTarget;
import redxax.oxy.remotely.servers.reproxy.PluginForwarding.Document;
import redxax.oxy.remotely.servers.reproxy.PluginForwarding.Port;
import restudio.rebase.backend.FileSystemProvider;
import restudio.rebase.backend.RemotePath;
import restudio.rebase.instance.Instance;
import restudio.rebase.instance.InstanceState;
import restudio.rebase.localcontrol.LocalServerControllerClient;
import restudio.rebase.localcontrol.LocalServerControllerClient.PluginSecretFiles;
import restudio.rebase.platform.jvm.JvmAsyncBridge;
import restudio.rebase.platform.jvm.JvmWorkspaceFileWatcher;
import restudio.rebase.reproxy.ReProxyModels.Binding;
import restudio.rebase.restudio.ReStudio;
import restudio.rebase.util.Executors;
import restudio.rescreen.platform.Async;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.IntSupplier;

public final class JvmPluginAccess implements PluginForwarding.Access {
    private final Instance instance;
    private final Path root;
    private final ReStudio studio;
    private final String account;
    private final IntSupplier port;
    private JvmWorkspaceFileWatcher watcher;
    private int watches;
    private volatile PluginSecretFiles secretProof;
    private int changes;

    public JvmPluginAccess(Instance instance) {
        this(instance, () -> {
            try { return Integer.parseInt(instance.getServerProperties().getProperty("server-port", "25565")); }
            catch (NumberFormatException ignored) { return 25565; }
        });
    }

    public JvmPluginAccess(Instance instance, IntSupplier port) {
        this.instance = instance;
        this.port = Objects.requireNonNull(port, "Server Port Is Required");
        root = Path.of(instance.getPath()).toAbsolutePath().normalize();
        studio = ReStudio.getInstance();
        account = studio.getUserId();
    }

    @Override
    public String serverKey() { return "local:" + instance.getInstanceId(); }

    @Override
    public String name() { return instance.getName(); }

    @Override
    public int serverPort() {
        int current = port.getAsInt();
        return current > 0 && current <= 65535 ? current : 25565;
    }

    @Override
    public boolean proxy() { return instance.isProxyServer(); }

    @Override
    public boolean local() { return true; }

    @Override
    public String hostScope() { return "local"; }

    @Override
    public AutoCloseable networkChange(String id, long revision) { return change(); }

    @Override
    public synchronized AutoCloseable change() {
        if (changes++ == 0) secretProof = null;
        return new AutoCloseable() {
            private boolean closed;
            @Override
            public void close() {
                synchronized (JvmPluginAccess.this) {
                    if (closed) return;
                    closed = true;
                    if (--changes == 0) secretProof = null;
                }
            }
        };
    }

    @Override
    public Async<Void> secretFiles() {
        return JvmAsyncBridge.fromFuture(CompletableFuture.runAsync(() -> {
            files();
            PluginSecretFiles proof = secretProof;
            try {
                if (proof == null) secretProof = LocalServerControllerClient.pluginSecretFiles(instance);
                else LocalServerControllerClient.requirePluginSecretFiles(instance, proof);
            } catch (IOException failure) {
                if (secretProof == proof) secretProof = null;
                throw new CompletionException(failure);
            }
        }, Executors.IO));
    }

    private Async<Void> requireSecretFiles() {
        return JvmAsyncBridge.fromFuture(CompletableFuture.runAsync(() -> {
            files();
            PluginSecretFiles proof = secretProof;
            if (proof == null) throw new IllegalStateException("Check The Host Again Before Configuring Plasmo Voice");
            try {
                LocalServerControllerClient.requirePluginSecretFiles(instance, proof);
            } catch (IOException failure) {
                if (secretProof == proof) secretProof = null;
                throw new CompletionException(failure);
            }
        }, Executors.IO));
    }

    @Override
    public String authorityStamp() { return account + ":" + serverKey() + ":" + root; }

    @Override
    public Async<Boolean> running() {
        if (!studio.isAuthenticated() || account == null || !account.equals(studio.getUserId())) {
            return Async.failed(new IllegalStateException("Sign In With The Account That Owns This Plugin Connection"));
        }
        InstanceState state = instance.getState();
        return Async.completed(state != InstanceState.STOPPED && state != InstanceState.CRASHED);
    }

    @Override
    public Async<Document> observe(String path) {
        FileSystemProvider files = files();
        Path target = path(path);
        return JvmAsyncBridge.fromFuture(files.exists(target)).thenCompose(exists -> !exists
                ? Async.completed(new Document(false, "", ""))
                : JvmAsyncBridge.fromFuture(files.read(target)).thenApply(content -> new Document(true, content, stamp(content))));
    }

    @Override
    public synchronized AutoCloseable watch(String value, Runnable changed) {
        Objects.requireNonNull(changed, "changed");
        files();
        Path target = path(value);
        String relative = ReProxyIntegrations.relativePath(value);
        if (relative.startsWith(".remotely/")) throw new IllegalArgumentException("Plugin File Is Unavailable");
        if (watcher == null) {
            try {
                watcher = new JvmWorkspaceFileWatcher();
            } catch (IOException failure) {
                throw new IllegalStateException("Plugin File Watching Is Unavailable", failure);
            }
        }
        JvmWorkspaceFileWatcher owner = watcher;
        AtomicBoolean active = new AtomicBoolean(true);
        AutoCloseable registration;
        try {
            registration = owner.watch(RemotePath.of(target.toString()), change -> {
                if (!active.get() || !current()) return;
                try {
                    changed.run();
                } catch (RuntimeException ignored) {
                }
            });
        } catch (RuntimeException failure) {
            if (watches == 0) {
                owner.close();
                watcher = null;
            }
            throw failure;
        }
        watches++;
        return () -> {
            if (!active.compareAndSet(true, false)) return;
            try {
                registration.close();
            } finally {
                synchronized (JvmPluginAccess.this) {
                    if (watcher == owner && --watches == 0) {
                        watcher = null;
                        owner.close();
                    }
                }
            }
        };
    }

    @Override
    public Async<Document> mutate(String path, Document expected, String content, String operationId) {
        return JvmAsyncBridge.fromFuture(files().writeConditional(path(path), expected.exists(), expected.content(), content))
                .thenCompose(ignored -> observe(path));
    }

    @Override
    public Async<Document> mutate(ReProxyIntegrations.Integration integration, String path, Document expected, String content, String operationId) {
        boolean journal = path.equals(".remotely/plugin-forwarding-" + integration.id() + ".json");
        return running().thenCompose(active -> active && !journal && integration.requiresStopped()
                ? Async.failed(new IllegalStateException("Stop The Server Before Changing " + integration.name()))
                : (!journal && "plasmovoice".equals(integration.id()) ? requireSecretFiles() : Async.<Void>completed(null))
                        .thenCompose(ignored -> mutate(path, expected, content, operationId)));
    }

    @Override
    public Async<Void> reload(ReProxyIntegrations.Integration integration, String path, Document expected) {
        return observe(path).thenCompose(current -> {
            if (!current.equals(expected)) return Async.failed(new IllegalStateException("Plugin Settings Changed Before Reload"));
            Async<Void> commands = Async.completed(null);
            for (String command : integration.reloadCommands()) commands = commands.thenCompose(ignored -> {
                files();
                return JvmAsyncBridge.fromFuture(instance.getBackend().getExecution().sendCommand(command));
            });
            return commands;
        });
    }

    @Override
    public Async<Port> reserve(ReProxyIntegrations.Integration integration, int desiredPort, String operationId) {
        return Async.failed(new UnsupportedOperationException("Local Plugin Ports Are Managed By ReProxy"));
    }

    @Override
    public Async<Void> release(ReProxyIntegrations.Integration integration, Port port) {
        return Async.completed(null);
    }

    @Override
    public ReProxyTarget localTarget() {
        return new ReProxyTarget(instance.getInstanceId(), name(), serverPort(),
                new Binding("", "LOCAL", instance.getInstanceId(), "", "", "", "READY"),
                instance.getSettings().getProperty("reproxy.connectionId", ""), instance.getSettings().getProperty("reproxy.bindingId", ""));
    }

    private FileSystemProvider files() {
        if (!studio.isAuthenticated() || account == null || !account.equals(studio.getUserId())) {
            throw new IllegalStateException("Sign In With The Account That Owns This Plugin Connection");
        }
        if (!Path.of(instance.getPath()).toAbsolutePath().normalize().equals(root)
                || instance.getBackendConfig() != null && !"LOCAL".equalsIgnoreCase(instance.getBackendConfig().type)) {
            throw new IllegalStateException("Server Location Changed; Reopen Server Details");
        }
        if (instance.getBackend() == null) throw new IllegalStateException("Server Files Are Unavailable");
        FileSystemProvider files = instance.getBackend().getFileSystem();
        if (!files.supportsConditionalWrites()) throw new UnsupportedOperationException("Safe Plugin Configuration Is Unavailable For This Server");
        return files;
    }

    private boolean current() {
        return studio.isAuthenticated() && account != null && account.equals(studio.getUserId())
                && Path.of(instance.getPath()).toAbsolutePath().normalize().equals(root)
                && (instance.getBackendConfig() == null || "LOCAL".equalsIgnoreCase(instance.getBackendConfig().type));
    }

    private Path path(String value) {
        Path target = root.resolve(ReProxyIntegrations.relativePath(value)).normalize();
        if (!target.startsWith(root)) throw new IllegalArgumentException("Plugin File Is Outside The Server Folder");
        try {
            Path existing = target;
            while (!Files.exists(existing) && existing.getParent() != null) existing = existing.getParent();
            if (!existing.toRealPath().startsWith(root.toRealPath())) throw new IllegalArgumentException("Plugin File Is Outside The Server Folder");
        } catch (IOException failure) { throw new IllegalStateException("Server Folder Is Unavailable", failure); }
        return target;
    }

    private static String stamp(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }
}
