package redxax.oxy.remotely.web.platform;

import restudio.rebase.backend.FileExplorerProviders;
import restudio.rebase.backend.RemoteFileSystemProvider;
import restudio.rebase.backend.RemotePath;
import restudio.rebase.backend.TransferSource;
import restudio.rescreen.platform.Async;
import restudio.rescreen.platform.TaskScheduler;
import restudio.rebase.ui.screens.editor.FileEditorScreen;
import restudio.rescreen.platform.browser.BrowserHostActionHandler;
import restudio.rescreen.ui.core.Screen;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.platform.browser.BrowserFile;

import java.time.Duration;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

public final class BrowserFileExplorerAdapters {
    private static final long PENDING_EXTERNAL_OPEN_TTL_MILLIS = 120_000L;
    private static final Duration PENDING_EXTERNAL_OPEN_SWEEP = Duration.ofSeconds(30);
    private static final List<PendingExternalOpen> PENDING_EXTERNAL_OPENS = new ArrayList<>();
    private static final Map<Object, ExpiryRegistration> EXPIRY_TASKS = new IdentityHashMap<>();
    private static final Map<Object, BrowserExternalDragPreparation> EXTERNAL_DRAGS = new IdentityHashMap<>();

    private BrowserFileExplorerAdapters() {
    }

    public static void install(Object owner) {
        install(owner, null);
    }

    public static void install(Object owner, TaskScheduler scheduler) {
        close(owner);
        FileExplorerProviders.installEditorResolver(owner, resolver());
        FileExplorerProviders.installExternalOpenPreparation(owner, new FileExplorerProviders.ExternalOpenPreparation() {
            @Override
            public void prepare(RemoteFileSystemProvider provider, RemotePath path, boolean directory) {
                prepareExternalOpen(owner, provider, path, directory);
            }

            @Override
            public void cancel(RemoteFileSystemProvider provider, RemotePath path, boolean directory) {
                cancelExternalOpen(provider, path);
            }
        });
        FileExplorerProviders.installTransferSourceResolver(owner, new FileExplorerProviders.TransferSourceResolver() {
            @Override
            public TransferSource resolve(Object value) {
                return value instanceof BrowserFile file && owner instanceof BrowserApplicationHost host
                        ? BrowserTransferBridge.source(host.hostActionHandler(), file) : null;
            }

            @Override
            public List<TransferSource> resolve(List<?> values) {
                if (!(owner instanceof BrowserApplicationHost host) || values == null || values.isEmpty()
                        || values.stream().anyMatch(value -> !(value instanceof BrowserFile))) {
                    return FileExplorerProviders.TransferSourceResolver.super.resolve(values);
                }
                return BrowserTransferBridge.sources(values.stream().map(value -> (BrowserFile) value).toList(), host.hostActionHandler());
            }

            @Override
            public boolean pickerAvailable() {
                return owner instanceof BrowserApplicationHost;
            }

            @Override
            public void pick(Consumer<List<TransferSource>> callback) {
                if (!(owner instanceof BrowserApplicationHost host) || callback == null) return;
                host.hostActionHandler().pickTransferFiles(true, files -> callback.accept(BrowserTransferBridge.sources(files, host.hostActionHandler())));
            }
        });
        BrowserExternalDragPreparation externalDrag = owner instanceof BrowserApplicationHost host
                ? new BrowserExternalDragPreparation(host.hostActionHandler()) : null;
        FileExplorerProviders.installExternalDragPreparation(owner, externalDrag);
        if (externalDrag != null) {
            synchronized (EXTERNAL_DRAGS) {
                EXTERNAL_DRAGS.put(owner, externalDrag);
            }
        }
        ExpiryRegistration registration = new ExpiryRegistration(owner);
        synchronized (PENDING_EXTERNAL_OPENS) {
            EXPIRY_TASKS.put(owner, registration);
        }
        TaskScheduler activeScheduler = scheduler;
        if (activeScheduler == null && owner instanceof BrowserApplicationHost) activeScheduler = new BrowserTaskScheduler();
        if (activeScheduler == null) return;
        try {
            TaskScheduler.ScheduledTask expiryTask = activeScheduler.scheduleAtFixedRate(() -> expire(registration),
                    PENDING_EXTERNAL_OPEN_SWEEP, PENDING_EXTERNAL_OPEN_SWEEP);
            synchronized (PENDING_EXTERNAL_OPENS) {
                if (EXPIRY_TASKS.get(owner) != registration) {
                    expiryTask.cancel();
                    return;
                }
                registration.task = expiryTask;
            }
        } catch (Throwable ignored) {
            synchronized (PENDING_EXTERNAL_OPENS) {
                if (EXPIRY_TASKS.get(owner) == registration) EXPIRY_TASKS.remove(owner);
            }
        }
    }

    public static void close(Object owner) {
        BrowserExternalDragPreparation externalDrag;
        synchronized (EXTERNAL_DRAGS) {
            externalDrag = EXTERNAL_DRAGS.remove(owner);
        }
        if (externalDrag != null) externalDrag.close();
        FileExplorerProviders.clearExternalDragPreparation(owner);
        ExpiryRegistration registration;
        synchronized (PENDING_EXTERNAL_OPENS) {
            registration = EXPIRY_TASKS.remove(owner);
            for (int index = PENDING_EXTERNAL_OPENS.size() - 1; index >= 0; index--) {
                PendingExternalOpen pending = PENDING_EXTERNAL_OPENS.get(index);
                if (pending.owner() != owner) continue;
                boolean shouldClose = pending.settleLocked();
                PENDING_EXTERNAL_OPENS.remove(index);
                if (shouldClose) closeWindow(pending);
            }
        }
        if (registration != null && registration.task != null) registration.task.cancel();
        FileExplorerProviders.clearExternalOpenPreparation(owner);
    }

    static PendingExternalOpen takePendingExternalOpen(String serverId, String path) {
        String normalizedServerId = normalize(serverId);
        String normalizedPath = normalizePath(path);
        synchronized (PENDING_EXTERNAL_OPENS) {
            removeExpiredPendingWindows();
            for (int index = 0; index < PENDING_EXTERNAL_OPENS.size(); index++) {
                PendingExternalOpen pending = PENDING_EXTERNAL_OPENS.get(index);
                if (pending.matches(normalizedServerId, normalizedPath) && pending.claimLocked()) return pending;
            }
        }
        return null;
    }

    static void cancelPendingExternalOpen(String serverId, String path, int windowId) {
        String normalizedServerId = normalize(serverId);
        String normalizedPath = normalizePath(path);
        synchronized (PENDING_EXTERNAL_OPENS) {
            removeExpiredPendingWindows();
            for (int index = 0; index < PENDING_EXTERNAL_OPENS.size(); index++) {
                PendingExternalOpen pending = PENDING_EXTERNAL_OPENS.get(index);
                if (!pending.matches(normalizedServerId, normalizedPath)
                        || (windowId > 0 && pending.windowId() != windowId) || !pending.unclaimedLocked()) continue;
                boolean shouldClose = pending.settleLocked();
                PENDING_EXTERNAL_OPENS.remove(index);
                if (shouldClose) closeWindow(pending);
                return;
            }
        }
    }

    private static void prepareExternalOpen(Object owner, RemoteFileSystemProvider provider, RemotePath path, boolean directory) {
        if (directory || !(owner instanceof BrowserApplicationHost host) || provider == null || path == null) return;
        String serverId = normalize(provider.getMetadata("serverId"));
        if (serverId.isBlank()) return;
        String normalizedPath = normalizePath(path.asString());
        if (normalizedPath.isBlank()) return;
        BrowserHostActionHandler actions = host.hostActionHandler();
        int windowId = actions.openPendingBrowser();
        if (windowId < 0) return;
        synchronized (PENDING_EXTERNAL_OPENS) {
            if (EXPIRY_TASKS.get(owner) == null) {
                closeWindow(new PendingExternalOpen(owner, serverId, normalizedPath, actions, windowId,
                        System.currentTimeMillis(), true));
                return;
            }
            removeExpiredPendingWindows();
            PENDING_EXTERNAL_OPENS.add(new PendingExternalOpen(owner, serverId, normalizedPath, actions, windowId, System.currentTimeMillis(), false));
        }
    }

    private static void cancelExternalOpen(RemoteFileSystemProvider provider, RemotePath path) {
        if (provider == null || path == null) return;
        cancelPendingExternalOpen(provider.getMetadata("serverId"), path.asString(), -1);
    }

    static PendingExternalOpen reservePendingExternalOpen(Object owner, String serverId, String path,
                                                           BrowserHostActionHandler actions, int windowId) {
        if (owner == null || actions == null || windowId < 0) return null;
        String normalizedServerId = normalize(serverId);
        String normalizedPath = normalizePath(path);
        if (normalizedServerId.isBlank() || normalizedPath.isBlank()) return null;
        synchronized (PENDING_EXTERNAL_OPENS) {
            if (EXPIRY_TASKS.get(owner) == null) return null;
            removeExpiredPendingWindows();
            PendingExternalOpen pending = new PendingExternalOpen(owner, normalizedServerId, normalizedPath, actions,
                    windowId, System.currentTimeMillis(), true);
            PENDING_EXTERNAL_OPENS.add(pending);
            return pending;
        }
    }

    static boolean navigatePendingExternalOpen(PendingExternalOpen pending, String url) {
        if (pending == null) return false;
        synchronized (PENDING_EXTERNAL_OPENS) {
            if (!PENDING_EXTERNAL_OPENS.contains(pending)) return false;
            if (!pending.activeLocked()) {
                PENDING_EXTERNAL_OPENS.remove(pending);
                return false;
            }
            boolean navigated;
            try {
                navigated = pending.actions().navigatePendingBrowser(pending.windowId(), url);
            } catch (Throwable failure) {
                navigated = false;
            }
            pending.settleLocked();
            PENDING_EXTERNAL_OPENS.remove(pending);
            if (!navigated) closeWindow(pending);
            return navigated;
        }
    }

    static void failPendingExternalOpen(PendingExternalOpen pending) {
        if (pending == null) return;
        synchronized (PENDING_EXTERNAL_OPENS) {
            if (!PENDING_EXTERNAL_OPENS.contains(pending)) return;
            boolean shouldClose = pending.settleLocked();
            PENDING_EXTERNAL_OPENS.remove(pending);
            if (shouldClose) closeWindow(pending);
        }
    }

    private static void expire(ExpiryRegistration registration) {
        synchronized (PENDING_EXTERNAL_OPENS) {
            if (EXPIRY_TASKS.get(registration.owner) != registration) return;
            removeExpiredPendingWindows(registration.owner);
        }
    }

    private static void removeExpiredPendingWindows() {
        removeExpiredPendingWindows(null);
    }

    private static void removeExpiredPendingWindows(Object owner) {
        long now = System.currentTimeMillis();
        for (int index = PENDING_EXTERNAL_OPENS.size() - 1; index >= 0; index--) {
            PendingExternalOpen pending = PENDING_EXTERNAL_OPENS.get(index);
            if (owner != null && pending.owner() != owner) continue;
            if (now - pending.createdAt() < PENDING_EXTERNAL_OPEN_TTL_MILLIS) continue;
            boolean shouldClose = pending.settleLocked();
            PENDING_EXTERNAL_OPENS.remove(index);
            if (shouldClose) closeWindow(pending);
        }
    }

    private static void closeWindow(PendingExternalOpen pending) {
        try {
            pending.actions().closePendingBrowser(pending.windowId());
        } catch (Throwable ignored) {
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalizePath(String value) {
        String normalized = value == null ? "" : value.replace('\\', '/').trim();
        while (normalized.startsWith("/")) normalized = normalized.substring(1);
        while (normalized.startsWith("./")) normalized = normalized.substring(2);
        return ".".equals(normalized) ? "" : normalized;
    }

    static final class PendingExternalOpen {
        private final Object owner;
        private final String serverId;
        private final String path;
        private final BrowserHostActionHandler actions;
        private final int windowId;
        private final long createdAt;
        private boolean claimed;
        private boolean settled;

        private PendingExternalOpen(Object owner, String serverId, String path, BrowserHostActionHandler actions,
                                    int windowId, long createdAt, boolean claimed) {
            this.owner = owner;
            this.serverId = serverId;
            this.path = path;
            this.actions = actions;
            this.windowId = windowId;
            this.createdAt = createdAt;
            this.claimed = claimed;
        }

        private boolean matches(String requestedServerId, String requestedPath) {
            return serverId.equals(requestedServerId) && path.equals(requestedPath);
        }

        private boolean claimLocked() {
            if (claimed || settled) return false;
            claimed = true;
            return true;
        }

        private boolean activeLocked() {
            return claimed && !settled;
        }

        private boolean unclaimedLocked() {
            return !claimed && !settled;
        }

        private boolean settleLocked() {
            if (settled) return false;
            settled = true;
            return true;
        }

        private Object owner() {
            return owner;
        }

        private BrowserHostActionHandler actions() {
            return actions;
        }

        private int windowId() {
            return windowId;
        }

        private long createdAt() {
            return createdAt;
        }
    }

    private static final class ExpiryRegistration {
        private final Object owner;
        private TaskScheduler.ScheduledTask task;

        private ExpiryRegistration(Object owner) {
            this.owner = owner;
        }
    }

    static final class BrowserExternalDragPreparation implements FileExplorerProviders.ExternalDragPreparation {
        private final BrowserHostActionHandler actions;
        private final Runnable authenticationChanged = this::clear;
        private RemoteFileSystemProvider provider;
        private RemotePath path;
        private Async<String> request;
        private String token = "";
        private long generation;
        private boolean closed;

        BrowserExternalDragPreparation(BrowserHostActionHandler actions) {
            this.actions = actions;
            BrowserLaunchSession.addAuthStateListener(authenticationChanged);
            BrowserLaunchSession.addTicketListener(authenticationChanged);
        }

        @Override
        public synchronized void prepare(RemoteFileSystemProvider provider, RemoteFileSystemProvider.FileEntry entry,
                                         int x, int y, int width, int height) {
            if (closed || actions == null || !actions.downloadDragSupported() || entry == null || entry.isDirectory()
                    || !(provider instanceof BrowserDownloadDragSource source)) {
                clear();
                return;
            }
            clear();
            long expectedGeneration = ++generation;
            String expectedTicket = BrowserLaunchSession.ticket();
            this.provider = provider;
            this.path = entry.path();
            this.token = "restudio-download-drag-" + expectedGeneration;
            String expectedToken = token;
            try {
                request = source.downloadUrl(entry.path());
            } catch (Throwable failure) {
                clear();
                return;
            }
            if (request == null) {
                clear();
                return;
            }
            request.whenComplete((url, failure) -> {
                synchronized (BrowserExternalDragPreparation.this) {
                    if (closed || generation != expectedGeneration || BrowserExternalDragPreparation.this.provider != provider
                            || !Objects.equals(path, entry.path()) || !Objects.equals(expectedTicket, BrowserLaunchSession.ticket())) return;
                    request = null;
                    if (failure != null || url == null || url.isBlank()) {
                        clear();
                        return;
                    }
                    String contentType = entry.metadata().getOrDefault("mimetype", "application/octet-stream");
                    if (!actions.armDownloadDrag(expectedToken, entry.path().fileName(), contentType, url,
                            x, y, width, height)) clear();
                }
            });
        }

        @Override
        public synchronized void cancel(RemoteFileSystemProvider provider, RemotePath path) {
            if (this.provider == provider && Objects.equals(this.path, path)) clear();
        }

        @Override
        public synchronized void clear() {
            generation++;
            Async<String> activeRequest = request;
            request = null;
            if (activeRequest != null) activeRequest.cancel();
            String activeToken = token;
            token = "";
            provider = null;
            path = null;
            if (actions != null && !activeToken.isBlank()) actions.clearDownloadDrag(activeToken);
        }

        synchronized void close() {
            if (closed) return;
            closed = true;
            clear();
            BrowserLaunchSession.removeAuthStateListener(authenticationChanged);
            BrowserLaunchSession.removeTicketListener(authenticationChanged);
        }
    }

    interface BrowserDownloadDragSource {
        Async<String> downloadUrl(RemotePath path);
    }

    static FileExplorerProviders.EditorResolver resolver() {
        return new FileExplorerProviders.EditorResolver() {
            @Override
            public Async<Boolean> canOpen(RemoteFileSystemProvider provider, RemotePath path) {
                String authority = BrowserLaunchSession.authorityKey();
                String subject = BrowserLaunchSession.authenticated() ? BrowserLaunchSession.metadata().subjectId() : null;
                if (BrowserLaunchSession.renewing()) {
                    return BrowserLaunchSession.renewAsync().thenCompose(ignored -> sameAuthority(authority, subject)
                            ? FileEditorScreen.canOpen(provider, path)
                            : Async.failed(new IllegalStateException("Browser Session Changed")));
                }
                String ticket = BrowserLaunchSession.ticket();
                return FileEditorScreen.canOpen(provider, path).exceptionallyCompose(failure -> {
                    if (!sameAuthority(authority, subject)) return Async.failed(failure);
                    if (BrowserLaunchSession.renewing()) {
                        return BrowserLaunchSession.renewAsync().thenCompose(ignored -> sameAuthority(authority, subject)
                                ? FileEditorScreen.canOpen(provider, path)
                                : Async.failed(new IllegalStateException("Browser Session Changed")));
                    }
                    return !Objects.equals(ticket, BrowserLaunchSession.ticket())
                            ? FileEditorScreen.canOpen(provider, path) : Async.failed(failure);
                });
            }

            @Override
            public boolean open(Screen parent, Object context, RemoteFileSystemProvider provider, RemotePath workspaceRoot,
                                RemotePath initialFile, RemotePath configDir) {
                ScreenManager.getInstance().setScreen(createEditor(parent, context, provider, workspaceRoot, initialFile, configDir));
                return true;
            }

            @Override
            public boolean openWorkspace(Screen parent, Object context, RemoteFileSystemProvider provider, RemotePath workspaceRoot,
                                         RemotePath configDir) {
                if (workspaceRoot == null) return false;
                ScreenManager.getInstance().setScreen(createEditor(parent, context, provider, workspaceRoot, null, configDir));
                return true;
            }

            @Override
            public boolean openExternallyWhenRejected(RemoteFileSystemProvider provider, RemotePath path) {
                return false;
            }
        };
    }

    static FileEditorScreen createEditor(Screen parent, Object context, RemoteFileSystemProvider provider,
                                         RemotePath workspaceRoot, RemotePath initialFile, RemotePath configDir) {
        return new FileEditorScreen(parent, context, provider, workspaceRoot, initialFile, configDir);
    }

    private static boolean sameAuthority(String authority, String subject) {
        return BrowserLaunchSession.authenticated()
                && Objects.equals(authority, BrowserLaunchSession.authorityKey())
                && Objects.equals(subject, BrowserLaunchSession.metadata().subjectId());
    }
}
